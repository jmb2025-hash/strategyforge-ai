package app.strategyforge.engine.support

import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** A test clock that only moves when told to. */
class MutableClock(
    @Volatile private var now: Instant,
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now

    fun advanceSeconds(s: Long) {
        now = now.plusSeconds(s)
    }
}
