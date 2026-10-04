package app.strategyforge.engine

import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.db.str
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.MarketMode
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** What one tick did, for the service's status line and for tests. */
data class TickReport(
    val at: Instant,
    val mode: MarketMode,
    val ran: List<String>,
    val marketTime: Instant,
)

/**
 * Drives the engine from the phone's foreground service (D-027). The service calls [tick] every
 * few seconds on the engine thread; each task runs when its interval has elapsed, with the same
 * cadence the Version 1 server scheduler used. In demo mode each tick instead advances replay time
 * by the configured step, which runs the whole pipeline deterministically for every replay minute.
 */
class EngineScheduler(
    private val engine: Engine,
    private val wall: Clock = engine.wall,
) {
    private val log = EngineLog.of(javaClass)
    private val lastRun = mutableMapOf<String, Instant>()

    private val liveTasks: List<Triple<String, Duration, () -> Unit>> =
        listOf(
            Triple("stream", Duration.ZERO, { streamTick() }),
            Triple("ingestion", Duration.ofSeconds(15), { engine.ingestion.refreshAll() }),
            Triple("execution", Duration.ZERO, { engine.execution.processAll() }),
            Triple("maintenance", Duration.ofSeconds(60), { engine.maintenance.runAll() }),
            Triple("reconciliation", Duration.ZERO, { engine.reconciliation.runDirty() }),
            Triple("reconciliation-all", Duration.ofMinutes(5), { engine.reconciliation.runAll() }),
            Triple("evaluation", Duration.ofSeconds(20), { engine.evaluation.evaluateAll() }),
            Triple("expiry", Duration.ofSeconds(15), { engine.recommendations.expireDue() }),
            Triple("equity", Duration.ofSeconds(60), { engine.portfolios.recordPeriodicEquity() }),
        )

    /** Stores the streams' fresh ticks for the watched instruments (D-056). */
    private fun streamTick() {
        if (engine.streams.streams().isNotEmpty()) engine.ingestion.refreshStreamed()
    }

    /**
     * Keeps the price streams subscribed (D-056): watched crypto and stocks in LIVE mode, and the
     * listings running TSX plans hold during the TSX session in either mode.
     */
    private fun syncStreams(live: Boolean) {
        if (engine.streams.streams().isEmpty()) return
        val symbols = if (live) engine.ingestion.interestSymbols() else emptyMap()
        val tsx = if (engine.tsx.sessionOpen()) engine.tsx.heldSymbols() else emptySet()
        engine.streams.sync(live, symbols[AssetClass.CRYPTO].orEmpty(), symbols[AssetClass.US_EQUITY].orEmpty(), tsx)
    }

    /** Replay minutes per tick in demo mode; 0 pauses the demo clock. Persisted. */
    var demoStepMinutes: Long
        get() =
            engine.db
                .sql("select value from settings where key = :k")
                .param("k", DEMO_STEP_KEY)
                .firstOrNull { it.str("value").toLongOrNull() }
                ?: DEFAULT_DEMO_STEP
        set(v) {
            require(v in 0..60) { "Demo speed must be 0-60 replay minutes per tick" }
            engine.db
                .sql("insert or replace into settings(key, value) values (:k, :v)")
                .param("k", DEMO_STEP_KEY)
                .param("v", v.toString())
                .update()
        }

    fun tick(): TickReport {
        val now = wall.instant()
        val mode = engine.marketMode()
        val ran = mutableListOf<String>()
        // TSX portfolio plans use real daily data in both modes (D-055).
        if (lastRun["tsx"]?.let { now.isBefore(it.plus(TSX_EVERY)) } != true) {
            runCatching { engine.tsx.tick() }.onFailure { log.error("Scheduled task tsx failed", it) }
            lastRun["tsx"] = now
            ran += "tsx"
        }
        runCatching { syncStreams(mode == MarketMode.LIVE) }.onFailure { log.error("Stream sync failed", it) }
        if (mode == MarketMode.DEMO) {
            val step = demoStepMinutes
            if (step > 0) {
                engine.replay.autoAdvance(Duration.ofMinutes(step))
                ran += "replay+${step}m"
            }
        } else {
            liveTasks.forEach { (name, every, task) ->
                val last = lastRun[name]
                if (last == null || !now.isBefore(last.plus(every))) {
                    runCatching(task).onFailure { log.error("Scheduled task {} failed", name, it) }
                    lastRun[name] = now
                    ran += name
                }
            }
        }
        return TickReport(now, mode, ran, engine.marketClock.now())
    }

    companion object {
        const val DEMO_STEP_KEY = "demo_step_minutes"
        const val DEFAULT_DEMO_STEP = 1L

        /** How often TSX portfolio plans refresh their state and intraday prices (daily data downloads are at most every few hours). */
        val TSX_EVERY: Duration = Duration.ofMinutes(1)

        /** How often the foreground service should call [tick]. */
        val TICK_INTERVAL: Duration = Duration.ofSeconds(5)
    }
}
