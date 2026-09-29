package app.strategyforge.engine.market

import app.strategyforge.engine.market.BarSchedule
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MarketCalendarTest {
    @Test
    fun `FR-082 US equity sessions honour weekends, holidays and early closes`() {
        assertThat(MarketCalendar.isTradingDay(LocalDate.parse("2026-06-19"))).isFalse() // Juneteenth
        assertThat(MarketCalendar.isTradingDay(LocalDate.parse("2026-06-20"))).isFalse() // Saturday
        assertThat(MarketCalendar.isTradingDay(LocalDate.parse("2026-06-22"))).isTrue()
        val early = MarketCalendar.session(LocalDate.parse("2026-11-27"))!!
        assertThat(early.close).isEqualTo(Instant.parse("2026-11-27T18:00:00Z"))
        assertThat(MarketCalendar.state(AssetClass.US_EQUITY, Instant.parse("2026-06-22T13:29:59Z"))).isEqualTo(SessionState.CLOSED)
        assertThat(MarketCalendar.state(AssetClass.US_EQUITY, Instant.parse("2026-06-22T13:30:00Z"))).isEqualTo(SessionState.OPEN)
        assertThat(MarketCalendar.state(AssetClass.US_EQUITY, Instant.parse("2026-06-22T20:00:00Z"))).isEqualTo(SessionState.CLOSED)
        assertThat(MarketCalendar.state(AssetClass.CRYPTO, Instant.parse("2026-06-20T03:00:00Z"))).isEqualTo(SessionState.OPEN)
    }

    @Test
    fun `FR-082 dates outside the verified calendar fail closed`() {
        assertThat(MarketCalendar.state(AssetClass.US_EQUITY, Instant.parse("2031-03-03T15:00:00Z"))).isEqualTo(SessionState.UNVERIFIED)
        assertThat(BarSchedule.lastClosedBarStart(AssetClass.US_EQUITY, Timeframe.H1, Instant.parse("2031-03-03T15:00:00Z"))).isNull()
    }

    @Test
    fun `D-008 calendar covers the current year (update the table annually)`() {
        assertThat(MarketCalendar.covers(LocalDate.now(ZoneOffset.UTC))).`as`("MarketCalendar must be extended to cover the current year").isTrue()
    }

    @Test
    fun `FR-024 bar schedule computes expected bars, gaps and the latest closed bar`() {
        val day = BarSchedule.expected(AssetClass.US_EQUITY, Timeframe.H1, Instant.parse("2026-06-22T00:00:00Z"), Instant.parse("2026-06-23T00:00:00Z"))
        assertThat(day).hasSize(7).startsWith(Instant.parse("2026-06-22T13:30:00Z"))
        assertThat(BarSchedule.closeTime(AssetClass.US_EQUITY, Timeframe.H1, day.last())).isEqualTo(Instant.parse("2026-06-22T20:00:00Z"))
        val present = day.filterIndexed { i, _ -> i != 3 }
        assertThat(BarSchedule.missing(AssetClass.US_EQUITY, Timeframe.H1, present)).containsExactly(day[3])
        assertThat(BarSchedule.lastClosedBarStart(AssetClass.US_EQUITY, Timeframe.H1, Instant.parse("2026-06-22T16:10:00Z"))).isEqualTo(Instant.parse("2026-06-22T14:30:00Z"))
        assertThat(BarSchedule.lastClosedBarStart(AssetClass.US_EQUITY, Timeframe.H1, Instant.parse("2026-06-23T12:00:00Z"))).isEqualTo(Instant.parse("2026-06-22T19:30:00Z"))
        assertThat(BarSchedule.lastClosedBarStart(AssetClass.CRYPTO, Timeframe.H1, Instant.parse("2026-06-22T10:37:00Z"))).isEqualTo(Instant.parse("2026-06-22T09:00:00Z"))
        assertThat(BarSchedule.expected(AssetClass.CRYPTO, Timeframe.H4, Instant.parse("2026-06-22T00:00:00Z"), Instant.parse("2026-06-23T00:00:00Z"))).hasSize(6)
    }
}
