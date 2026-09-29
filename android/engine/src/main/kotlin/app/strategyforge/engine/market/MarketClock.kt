package app.strategyforge.engine.market

import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.instant
import java.time.Clock
import java.time.Instant

enum class ClockMode { LIVE, REPLAY }

/**
 * Market time for all market decisions (freshness, sessions, expiry, evaluation buckets).
 * In REPLAY (demo) mode it is the persisted, deterministic replay clock; otherwise the wall clock.
 */
class MarketClock(
    private val db: Db,
    private val wall: Clock,
) {
    @Volatile private var mode = ClockMode.LIVE

    @Volatile private var replayNow: Instant? = null

    fun now(): Instant = if (mode == ClockMode.REPLAY) replayNow ?: loadReplay() else wall.instant()

    fun mode(): ClockMode = mode

    fun useLive() {
        mode = ClockMode.LIVE
    }

    fun useReplay(defaultStart: Instant) {
        val existing = db.sql("select market_time from replay_state").firstOrNull { it.instant("market_time") }
        if (existing == null) {
            db
                .sql("insert or ignore into replay_state(singleton, market_time, updated_at) values (1, :t, :now)")
                .param("t", defaultStart)
                .param("now", wall.instant())
                .update()
        }
        replayNow = existing ?: defaultStart
        mode = ClockMode.REPLAY
    }

    /** Moves the replay clock; persisted so restarts resume at the same market time. */
    fun setReplayTime(t: Instant) {
        check(mode == ClockMode.REPLAY) { "Replay clock is only available in demo mode" }
        db
            .sql("update replay_state set market_time = :t, updated_at = :now")
            .param("t", t)
            .param("now", wall.instant())
            .update()
        replayNow = t
    }

    private fun loadReplay(): Instant = db.sql("select market_time from replay_state").single { it.instant("market_time") }.also { replayNow = it }
}
