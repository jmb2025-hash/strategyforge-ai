package app.strategyforge.market

import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

enum class ClockMode { LIVE, REPLAY }

/** Published after the replay clock moves; replay-mode pipelines react synchronously. */
data class MarketClockAdvanced(
    val from: Instant,
    val to: Instant,
)

/**
 * Market time for all market decisions (freshness, sessions, expiry, evaluation buckets).
 * In REPLAY mode it is the persisted, deterministic replay clock; otherwise the wall clock.
 */
@Component
class MarketClock(
    private val jdbc: JdbcClient,
    private val wall: Clock,
) {
    private val mode = AtomicReference(ClockMode.LIVE)
    private val replayNow = AtomicReference<Instant?>(null)

    fun now(): Instant = if (mode.get() == ClockMode.REPLAY) replayNow.get() ?: loadReplay() else wall.instant()

    fun mode(): ClockMode = mode.get()

    fun useLive() {
        mode.set(ClockMode.LIVE)
    }

    fun useReplay(defaultStart: Instant) {
        val existing =
            jdbc
                .sql("select market_time from replay_state")
                .query { rs, _ -> rs.instant("market_time") }
                .optional()
                .orElse(null)
        if (existing == null) {
            jdbc
                .sql("insert into replay_state(singleton, market_time, updated_at) values (true, :t, :now) on conflict do nothing")
                .param("t", ts(defaultStart))
                .param("now", ts(wall.instant()))
                .update()
        }
        replayNow.set(existing ?: defaultStart)
        mode.set(ClockMode.REPLAY)
    }

    /** Moves the replay clock; persisted so restarts resume at the same market time. */
    fun setReplayTime(t: Instant) {
        check(mode.get() == ClockMode.REPLAY) { "Replay clock is only available in REPLAY mode" }
        jdbc
            .sql("update replay_state set market_time = :t, updated_at = :now")
            .param("t", ts(t))
            .param("now", ts(wall.instant()))
            .update()
        replayNow.set(t)
    }

    private fun loadReplay(): Instant {
        val t = jdbc.sql("select market_time from replay_state").query { rs, _ -> rs.instant("market_time") }.single()
        replayNow.set(t)
        return t
    }
}
