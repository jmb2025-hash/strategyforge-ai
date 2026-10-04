package app.strategyforge.engine.tsx

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.time.LocalDate

class BookTest {
    private val days = (0L..5L).map { LocalDate.parse("2026-03-02").plusDays(it) }

    private fun series(
        n: Int,
        price: Double,
        dividends: Map<LocalDate, Double> = emptyMap(),
    ) = TsxSeries(days.take(n), DoubleArray(n) { price }, DoubleArray(n) { price }, dividends)

    private val data =
        TsxData(
            mapOf(
                "AAA" to series(6, 10.0, mapOf(days[2] to 1.0)),
                "BBB" to series(2, 5.0),
            ),
            days.first(),
        )

    @Test
    fun `rebalance buys target weights at the close and charges the cost`() {
        val b = Book(1000.0)
        val (trades, turnover) = b.rebalance(days[0], mapOf("AAA" to 0.5, "BBB" to 0.5), data, 0.001)
        assertThat(b.shares["AAA"]).isCloseTo(50.0, within(1e-9))
        assertThat(b.shares["BBB"]).isCloseTo(100.0, within(1e-9))
        assertThat(trades).hasSize(2)
        assertThat(turnover).isCloseTo(1000.0, within(1e-9))
        assertThat(b.cash).isCloseTo(-1.0, within(1e-9))
    }

    @Test
    fun `DRIP buys more of the payer while paid out dividends leave the book`() {
        val drip = Book(1000.0).also { it.rebalance(days[0], mapOf("AAA" to 1.0), data, 0.0) }
        val paid = Book(1000.0).also { it.rebalance(days[0], mapOf("AAA" to 1.0), data, 0.0) }
        val d1 = days.flatMap { drip.dailyStep(it, data, true, 10) }
        val d2 = days.flatMap { paid.dailyStep(it, data, false, 10) }
        assertThat(d1.single().amount).isCloseTo(100.0, within(1e-9))
        assertThat(d1.single().reinvested).isTrue()
        assertThat(drip.shares["AAA"]).isCloseTo(110.0, within(1e-9))
        assertThat(d2.single().reinvested).isFalse()
        assertThat(paid.shares["AAA"]).isCloseTo(100.0, within(1e-9))
        assertThat(paid.cash).isCloseTo(0.0, within(1e-9))
    }

    @Test
    fun `a listing whose prices stop is turned into cash at its last price after the grace period`() {
        val b = Book(1000.0).also { it.rebalance(days[0], mapOf("BBB" to 1.0), data, 0.0) }
        val gone = mutableListOf<Pair<String, Double>>()
        days.forEach { b.dailyStep(it, data, true, 2) { s, v -> gone += s to v } }
        assertThat(gone).containsExactly("BBB" to 1000.0)
        assertThat(b.shares).doesNotContainKey("BBB")
        assertThat(b.cash).isCloseTo(1000.0, within(1e-9))
    }
}
