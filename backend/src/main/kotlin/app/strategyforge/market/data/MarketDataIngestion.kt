package app.strategyforge.market.data

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.common.web.Problems
import app.strategyforge.market.ClockMode
import app.strategyforge.market.InstrumentService
import app.strategyforge.market.MarketCalendar
import app.strategyforge.market.MarketClock
import app.strategyforge.market.MarketClockAdvanced
import app.strategyforge.market.MarketInterest
import app.strategyforge.market.PriceAlertService
import app.strategyforge.market.SessionState
import app.strategyforge.market.provider.ReplayFixtures
import app.strategyforge.notifications.NotificationCategory
import app.strategyforge.notifications.NotificationService
import app.strategyforge.notifications.Severity
import app.strategyforge.operations.DiagnosticsService
import app.strategyforge.operations.HealthStatus
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

data class IngestionReport(
    val at: Instant,
    val instruments: Int,
    val verified: Int,
    val failures: Map<String, String>,
)

@Service
class MarketDataIngestion(
    private val interests: ObjectProvider<MarketInterest>,
    private val instruments: InstrumentService,
    private val data: MarketDataService,
    private val alerts: PriceAlertService,
    private val notifications: NotificationService,
    private val clock: MarketClock,
    private val props: StrategyForgeProperties,
    private val diagnostics: DiagnosticsService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    val lastReport = AtomicReference<IngestionReport?>(null)

    fun interestIds(): Set<UUID> =
        interests
            .orderedStream()
            .toList()
            .flatMap { it.instrumentIds() }
            .toSet()

    /** Refreshes quotes for every instrument of interest, evaluates alerts and raises stale-data events. */
    fun refreshAll(): IngestionReport {
        val now = clock.now()
        val failures = linkedMapOf<String, String>()
        var verified = 0
        val list = instruments.byIds(interestIds()).filter { it.active }
        list.forEach { i ->
            val v =
                runCatching { data.refreshQuote(i) }.getOrElse {
                    log.warn("Quote refresh failed for {}", i.symbol, it)
                    QuoteVerification(DataStatus.PROVIDER_ERROR, null, null, it.javaClass.simpleName)
                }
            val quote = v.quote
            if (v.verified && quote != null) {
                verified++
                runCatching { alerts.evaluate(i, quote) }.onFailure { log.warn("Alert evaluation failed for {}", i.symbol, it) }
            } else {
                failures[i.symbol] = "${v.status}: ${v.detail}"
                if (MarketCalendar.state(i.assetClass, now) == SessionState.OPEN) {
                    val bucket = now.truncatedTo(ChronoUnit.HOURS)
                    notifications.notify(
                        NotificationCategory.STALE_DATA,
                        Severity.CRITICAL,
                        "Market data unavailable for ${i.symbol}",
                        "Quote status ${v.status}: ${v.detail}. New positions in ${i.symbol} are blocked until data is verified.",
                        "Instrument",
                        i.id,
                        "stale:${i.id}:$bucket",
                    )
                }
            }
        }
        if (data.latestFx()?.asOf?.isBefore(now.minus(Duration.ofHours(6))) != false) runCatching { data.refreshFx() }
        val report = IngestionReport(now, list.size, verified, failures)
        lastReport.set(report)
        if (failures.isNotEmpty()) diagnostics.recordEvent("market-data", HealthStatus.DEGRADED, "${failures.size} instrument(s) not verified: ${failures.keys.take(10)}")
        return report
    }

    @Scheduled(fixedDelayString = "\${strategyforge.scheduler.market-data-interval-ms:15000}", initialDelay = 5000)
    fun scheduled() {
        if (!props.scheduler.enabled || clock.mode() != ClockMode.LIVE) return
        CorrelationIdFilter.withCorrelation("ingest") { runCatching { refreshAll() }.onFailure { log.error("Scheduled ingestion failed", it) } }
    }

    /** Replay mode: ingestion runs first after every clock step. */
    @EventListener
    @Order(10)
    fun onReplayStep(e: MarketClockAdvanced) {
        refreshAll()
    }
}

data class ReplayClockView(
    val mode: ClockMode,
    val now: Instant,
    val replayStart: Instant?,
    val replayEnd: Instant?,
    val synthetic: Boolean,
)

/**
 * Deterministic replay stepping (FR-112 replay/demo mode). Each step moves the replay clock and
 * synchronously runs the replay pipeline (ingestion, execution, evaluation, expiry) in order.
 */
@Service
class ReplayService(
    private val clock: MarketClock,
    private val fixtures: ReplayFixtures,
    private val events: ApplicationEventPublisher,
    private val audit: AuditService,
    private val jdbc: JdbcClient,
    private val props: StrategyForgeProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun view(): ReplayClockView {
        val replay = clock.mode() == ClockMode.REPLAY
        return ReplayClockView(clock.mode(), clock.now(), if (replay) fixtures.dataset.replayStart else null, if (replay) fixtures.dataset.replayEnd else null, replay)
    }

    @Synchronized
    fun advance(
        minutes: Long,
        stepMinutes: Long,
    ): ReplayClockView {
        requireReplay()
        if (minutes !in 1..MAX_ADVANCE_MINUTES) throw Problems.badRequest("invalid-advance", "minutes must be 1-$MAX_ADVANCE_MINUTES")
        if (stepMinutes !in 1..minutes) throw Problems.badRequest("invalid-step", "stepMinutes must be 1-$minutes")
        val start = clock.now()
        var t = start
        val target = start.plus(Duration.ofMinutes(minutes))
        while (t.isBefore(target)) {
            val next = minOf(t.plus(Duration.ofMinutes(stepMinutes)), target)
            clock.setReplayTime(next)
            CorrelationIdFilter.withCorrelation("replay") { events.publishEvent(MarketClockAdvanced(t, next)) }
            t = next
        }
        audit.record(AuditCategory.MARKET_DATA, "REPLAY_ADVANCED", details = mapOf("from" to start.toString(), "to" to target.toString(), "stepMinutes" to stepMinutes))
        return view()
    }

    /** Moves the replay clock without running the pipeline. Backwards only before any paper order exists. */
    @Synchronized
    fun setTime(to: Instant): ReplayClockView {
        requireReplay()
        val now = clock.now()
        if (to.isBefore(now) && hasTradingHistory()) {
            throw Problems.conflict("replay-rewind-blocked", "The replay clock cannot move backwards after paper orders exist (ledger timelines must stay monotonic)")
        }
        clock.setReplayTime(to)
        audit.record(AuditCategory.MARKET_DATA, "REPLAY_CLOCK_SET", details = mapOf("from" to now.toString(), "to" to to.toString()))
        return view()
    }

    private fun hasTradingHistory(): Boolean =
        jdbc.sql("select to_regclass('public.paper_orders') is not null").query(Boolean::class.java).single() &&
            jdbc.sql("select exists(select 1 from paper_orders)").query(Boolean::class.java).single()

    private fun requireReplay() {
        if (clock.mode() != ClockMode.REPLAY) throw Problems.conflict("not-replay-mode", "The replay clock is only available when the REPLAY provider is active")
    }

    @Scheduled(fixedDelayString = "\${strategyforge.replay.auto-advance-interval-ms:5000}", initialDelay = 10000)
    fun autoAdvance() {
        if (!props.replay.autoAdvance || !props.scheduler.enabled || clock.mode() != ClockMode.REPLAY) return
        if (!clock.now().isBefore(fixtures.dataset.replayEnd)) return
        runCatching {
            advance(
                props.replay.autoAdvanceStep
                    .toMinutes()
                    .coerceAtLeast(1),
                props.replay.autoAdvanceStep
                    .toMinutes()
                    .coerceAtLeast(1),
            )
        }.onFailure { log.error("Replay auto-advance failed", it) }
    }

    companion object {
        const val MAX_ADVANCE_MINUTES = 60L * 24 * 7
    }
}
