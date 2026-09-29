package app.strategyforge.engine.market

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

enum class SessionState { OPEN, CLOSED, UNVERIFIED }

data class Session(
    val open: Instant,
    val close: Instant,
)

/**
 * US equity regular trading sessions (NYSE/Nasdaq) with full-day holidays and 13:00 ET
 * early closes for 2023-2027 (D-008). Dates outside the table are UNVERIFIED and block
 * equity trading (fail closed). Crypto trades 24/7.
 */
object MarketCalendar {
    val NEW_YORK: ZoneId = ZoneId.of("America/New_York")
    const val FIRST_YEAR = 2023
    const val LAST_YEAR = 2027
    private val OPEN = LocalTime.of(9, 30)
    private val CLOSE = LocalTime.of(16, 0)
    private val EARLY_CLOSE = LocalTime.of(13, 0)

    private val HOLIDAYS: Set<LocalDate> =
        listOf(
            "2023-01-02",
            "2023-01-16",
            "2023-02-20",
            "2023-04-07",
            "2023-05-29",
            "2023-06-19",
            "2023-07-04",
            "2023-09-04",
            "2023-11-23",
            "2023-12-25",
            "2024-01-01",
            "2024-01-15",
            "2024-02-19",
            "2024-03-29",
            "2024-05-27",
            "2024-06-19",
            "2024-07-04",
            "2024-09-02",
            "2024-11-28",
            "2024-12-25",
            "2025-01-01",
            "2025-01-09",
            "2025-01-20",
            "2025-02-17",
            "2025-04-18",
            "2025-05-26",
            "2025-06-19",
            "2025-07-04",
            "2025-09-01",
            "2025-11-27",
            "2025-12-25",
            "2026-01-01",
            "2026-01-19",
            "2026-02-16",
            "2026-04-03",
            "2026-05-25",
            "2026-06-19",
            "2026-07-03",
            "2026-09-07",
            "2026-11-26",
            "2026-12-25",
            "2027-01-01",
            "2027-01-18",
            "2027-02-15",
            "2027-03-26",
            "2027-05-31",
            "2027-06-18",
            "2027-07-05",
            "2027-09-06",
            "2027-11-25",
            "2027-12-24",
        ).map(LocalDate::parse).toSet()

    private val EARLY_CLOSES: Set<LocalDate> =
        listOf(
            "2023-07-03",
            "2023-11-24",
            "2024-07-03",
            "2024-11-29",
            "2024-12-24",
            "2025-07-03",
            "2025-11-28",
            "2025-12-24",
            "2026-11-27",
            "2026-12-24",
            "2027-11-26",
        ).map(LocalDate::parse).toSet()

    fun covers(date: LocalDate): Boolean = date.year in FIRST_YEAR..LAST_YEAR

    fun isTradingDay(date: LocalDate): Boolean? {
        if (!covers(date)) return null
        return date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY && date !in HOLIDAYS
    }

    fun session(date: LocalDate): Session? {
        if (isTradingDay(date) != true) return null
        val close = if (date in EARLY_CLOSES) EARLY_CLOSE else CLOSE
        return Session(ZonedDateTime.of(date, OPEN, NEW_YORK).toInstant(), ZonedDateTime.of(date, close, NEW_YORK).toInstant())
    }

    fun state(
        assetClass: AssetClass,
        at: Instant,
    ): SessionState {
        if (assetClass == AssetClass.CRYPTO) return SessionState.OPEN
        val date = at.atZone(NEW_YORK).toLocalDate()
        val trading = isTradingDay(date) ?: return SessionState.UNVERIFIED
        if (!trading) return SessionState.CLOSED
        val s = session(date)!!
        return if (!at.isBefore(s.open) && at.isBefore(s.close)) SessionState.OPEN else SessionState.CLOSED
    }

    /** The session containing [at], or the most recent session that closed before it. */
    fun currentOrPreviousSession(at: Instant): Session? {
        var d = at.atZone(NEW_YORK).toLocalDate()
        repeat(10) {
            session(d)?.let { s -> if (!at.isBefore(s.open)) return s }
            d = d.minusDays(1)
        }
        return null
    }

    fun nextSession(at: Instant): Session? {
        var d = at.atZone(NEW_YORK).toLocalDate()
        repeat(10) {
            session(d)?.let { s -> if (at.isBefore(s.close)) return s }
            d = d.plusDays(1)
        }
        return null
    }

    /** Whether a bar that opened at [openTime] lies inside a regular session (used for gap detection). */
    fun isSessionBarStart(
        assetClass: AssetClass,
        openTime: Instant,
    ): Boolean = assetClass == AssetClass.CRYPTO || state(assetClass, openTime) == SessionState.OPEN
}
