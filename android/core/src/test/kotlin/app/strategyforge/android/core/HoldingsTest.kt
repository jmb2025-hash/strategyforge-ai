package app.strategyforge.android.core

import app.strategyforge.android.core.model.Portfolio
import app.strategyforge.android.core.model.PortfolioSummary
import app.strategyforge.android.core.model.Position
import app.strategyforge.android.core.state.Holdings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** D-080 each holding's shares, cost, value, gain and weight, and the holdings together. */
class HoldingsTest {
    private val btc = Position("i1", "BTC-USD", "CRYPTO", "LONG", "0.5", "40000", "80000", "86000", "43000", "3000", name = "Bitcoin")
    private val aapl = Position("i2", "AAPL", "US_EQUITY", "LONG", "10", "2000", "200", "190", "1900", "-100")
    private val short = Position("i3", "ETH-USD", "CRYPTO", "SHORT", "-2", "-6000", "3000", "2800", "-5600", "400")
    private val summary =
        PortfolioSummary(Portfolio("p", "Crypto slot 1", "ACTIVE", startingBalance = "50000"), cash = "5000", buyingPower = "5000", equity = "50000", positions = listOf(aapl, btc, short), asOf = "2026-10-10T00:00:00Z")

    private fun eq(
        expected: String,
        actual: BigDecimal?,
    ) = assertEquals(0, BigDecimal(expected).compareTo(actual))

    @Test
    fun `a holding shows what was paid, what it is worth and the difference`() {
        val b = Holdings.stats(btc, "50000")
        eq("0.5", b.shares)
        eq("40000", b.paid)
        eq("43000", b.value)
        eq("3000", b.gain)
        eq("7.50", b.gainPercent)
        eq("86.00", b.weightPercent)
        assertEquals("Bitcoin", b.name)
        assertFalse(b.short)
    }

    @Test
    fun `a short shows positive shares and amounts, and gains when the price falls`() {
        val s = Holdings.stats(short, "50000")
        assertTrue(s.short)
        eq("2", s.shares)
        eq("6000", s.paid)
        eq("5600", s.value)
        eq("6.67", s.gainPercent)
    }

    @Test
    fun `holdings are listed biggest first and totalled`() {
        assertEquals(listOf("BTC-USD", "ETH-USD", "AAPL"), Holdings.all(summary).map { it.symbol })
        val t = Holdings.totals(summary)
        eq("48000", t.paid)
        eq("50500", t.value)
        eq("3300", t.gain)
        eq("6.88", t.gainPercent)
        assertTrue(t.complete)
        assertFalse(Holdings.totals(summary.copy(positions = listOf(btc.copy(marketValue = null, unrealizedPnl = null)))).complete)
    }
}
