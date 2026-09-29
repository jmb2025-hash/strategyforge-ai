package app.strategyforge.engine.common

import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Step-up confirmation for risk-increasing operations. On the phone the owner confirms with the
 * device lock (biometric or PIN); the app calls [confirmed] after a successful prompt and the
 * engine accepts sensitive operations for [window] afterwards (same rule as the Version 1 server).
 */
class RecentAuth(
    private val clock: Clock,
    var window: Duration = Duration.ofMinutes(5),
) {
    @Volatile
    var lastConfirmedAt: Instant? = null
        private set

    fun confirmed() {
        lastConfirmedAt = clock.instant()
    }

    fun forget() {
        lastConfirmedAt = null
    }

    fun isRecent(): Boolean = lastConfirmedAt?.plus(window)?.isBefore(clock.instant()) == false

    fun require(operation: String) {
        if (!isRecent()) throw Problems.recentAuthRequired(operation)
    }
}
