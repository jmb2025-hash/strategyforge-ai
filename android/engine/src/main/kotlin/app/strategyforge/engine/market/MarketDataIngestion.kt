package app.strategyforge.engine.market

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.market.ClockMode
import app.strategyforge.engine.market.InstrumentService
import app.strategyforge.engine.market.MarketCalendar
import app.strategyforge.engine.market.MarketClock
import app.strategyforge.engine.market.MarketInterest
import app.strategyforge.engine.market.PriceAlertService
import app.strategyforge.engine.market.ReplayFixtures
import app.strategyforge.engine.market.SessionState
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.notifications.NotificationService
import app.strategyforge.engine.notifications.Severity
import app.strategyforge.engine.operations.DiagnosticsService
import app.strategyforge.engine.operations.HealthStatus
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

class MarketDataIngestion(
    private val interests: () -> List<MarketInterest>,
    private val volumeInterests: () -> List<VolumeInterest>,
    private val instruments: InstrumentService,
    private val data: MarketDataService,
    private val alerts: PriceAlertService,
    private val notifications: NotificationService,
    private val clock: MarketClock,
    private val diagnostics: DiagnosticsService,
) {
    private val log = EngineLog.of(javaClass)
    val lastReport = AtomicReference<IngestionReport?>(null)

    fun interestIds(): Set<UUID> =
        interests()
            .flatMap { it.instrumentIds() }
            .toSet()

    /** The watched instruments' symbols per asset class, for the live streams (D-056). */
    fun interestSymbols(): Map<AssetClass, Set<String>> =
        instruments
            .byIds(interestIds())
            .filter { it.active }
            .groupBy({ it.assetClass }, { it.symbol })
            .mapValues { it.value.toSet() }

    /** Stores fresh streamed quotes for the watched instruments (no network) and evaluates alerts; returns how many were new. */
    fun refreshStreamed(): Int {
        var n = 0
        instruments.byIds(interestIds()).filter { it.active }.forEach { i ->
            val v = runCatching { data.streamQuote(i) }.getOrNull() ?: return@forEach
            val q = v.quote
            if (v.verified && q != null) {
                n++
                runCatching { alerts.evaluate(i, q) }.onFailure { log.warn("Alert evaluation failed for {}", i.symbol, it) }
            }
        }
        return n
    }

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
        if (clock.mode() == ClockMode.LIVE) {
            val volumeIds =
                volumeInterests()
                    .flatMap { it.instrumentIds() }
                    .toSet()
            instruments.byIds(volumeIds).forEach { i ->
                runCatching { data.candles(i, Timeframe.M1, now.minus(Duration.ofMinutes(10)), now, now, hydrate = true) }
                    .onFailure { log.warn("1m volume refresh failed for {}", i.symbol, it) }
            }
        }
        if (data.latestFx()?.asOf?.isBefore(now.minus(Duration.ofHours(6))) != false) runCatching { data.refreshFx() }
        val report = IngestionReport(now, list.size, verified, failures)
        lastReport.set(report)
        if (failures.isNotEmpty()) diagnostics.recordEvent("market-data", HealthStatus.DEGRADED, "${failures.size} instrument(s) not verified: ${failures.keys.take(10)}")
        return report
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
class ReplayService(
    private val clock: MarketClock,
    private val fixtures: ReplayFixtures,
    private val audit: AuditService,
    private val db: Db,
    /** Runs the replay pipeline (ingestion, execution, maintenance, evaluation, expiry) for one step. */
    private val pipeline: (from: Instant, to: Instant) -> Unit,
) {
    private val log = EngineLog.of(javaClass)

    fun view(): ReplayClockView {
        val replay = clock.mode() == ClockMode.REPLAY
        return ReplayClockView(clock.mode(), clock.now(), if (replay) fixtures.dataset.replayStart else null, if (replay) fixtures.dataset.replayEnd else null, replay)
    }

    /** Each stage commits its own work, so this must not run inside a caller's transaction. */
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
            pipeline(t, next)
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

    private fun hasTradingHistory(): Boolean = db.sql("select count(*) n from paper_orders").long() > 0

    private fun requireReplay() {
        if (clock.mode() != ClockMode.REPLAY) throw Problems.conflict("not-replay-mode", "The replay clock is only available in demo mode")
    }

    /** Demo mode on the phone: each engine tick moves replay time forward by [step] until the dataset ends. */
    fun autoAdvance(step: Duration) {
        if (clock.mode() != ClockMode.REPLAY) return
        if (!clock.now().isBefore(fixtures.dataset.replayEnd)) return
        val minutes = step.toMinutes().coerceAtLeast(1)
        runCatching { advance(minutes, minutes) }.onFailure { log.error("Replay auto-advance failed", it) }
    }

    companion object {
        const val MAX_ADVANCE_MINUTES = 60L * 24 * 7
    }
}
