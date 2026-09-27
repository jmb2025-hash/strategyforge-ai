package app.strategyforge.common.time

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Wall-clock time (sessions, audit, created_at). All storage is UTC (NFR-001).
 * Market-time decisions use [app.strategyforge.market.clock.MarketClock], which is
 * the deterministic replay clock in replay/demo mode.
 */
@Configuration
class ClockConfig {
    @Bean
    fun wallClock(): Clock = Clock.systemUTC()
}

object TimeFormat {
    private val DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss zzz")

    /** Timezone-aware display formatting (NFR-001). */
    fun display(
        instant: Instant,
        zone: ZoneId,
    ): String = ZonedDateTime.ofInstant(instant, zone).format(DISPLAY)
}
