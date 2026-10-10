package app.strategyforge.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.HoldingChart
import app.strategyforge.android.core.model.HoldingPoint
import app.strategyforge.android.core.model.HoldingTrade
import app.strategyforge.android.core.model.Portfolio
import app.strategyforge.android.core.model.PortfolioSummary
import app.strategyforge.android.core.model.Position
import app.strategyforge.android.core.state.Holdings
import app.strategyforge.android.ui.HoldingContent
import app.strategyforge.android.ui.HoldingRow
import app.strategyforge.android.ui.PortfolioTotalsCard
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.gainLabel
import app.strategyforge.android.ui.unitOf
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.math.BigDecimal
import java.time.ZoneId

/** D-080 each holding with what was paid, what it is worth and the difference, and its own page with history. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class HoldingUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val fmt = Formatters(ZoneId.of("UTC"))
    private val btc = Position("i1", "BTC-USD", "CRYPTO", "LONG", "0.5", "40000", "80000", "86000", "43000", "3000", name = "Bitcoin")
    private val aapl = Position("i2", "AAPL", "US_EQUITY", "LONG", "10", "2000", "200", "190", "1900", "-100", name = "Apple Inc.")
    private val summary =
        PortfolioSummary(
            Portfolio("p1", "Crypto slot 1", "ACTIVE", startingBalance = "50000"),
            cash = "6000",
            buyingPower = "6000",
            equity = "50900",
            realizedPnl = "250",
            totalReturn = "900",
            totalReturnPercent = "1.80",
            positions = listOf(aapl, btc),
            asOf = "2026-10-10T12:00:00Z",
        )
    private val chart =
        HoldingChart(
            "p1",
            "BTC-USD",
            "Bitcoin",
            "CRYPTO",
            "1M",
            listOf(
                HoldingPoint("2026-09-12T00:00:00Z", "78000", "0.5", "39000", "40000"),
                HoldingPoint("2026-10-10T00:00:00Z", "86000", "0.5", "43000", "40000"),
            ),
            gainChange = "4000",
            priceChangePercent = "10.26",
            realizedPnl = "250",
            fees = "12",
            firstBoughtAt = "2026-09-01T14:00:00Z",
            trades =
                listOf(
                    HoldingTrade("2026-09-20T10:00:00Z", "SELL", "0.1", "82500", "2", "250", "s1"),
                    HoldingTrade("2026-09-01T14:00:00Z", "BUY", "0.6", "80000", "10", "0", null),
                ),
        )

    @Test
    fun `labels show signed money and percent, and crypto units by coin`() {
        assertEquals("+$3,000.00 (+7.50%)", gainLabel(BigDecimal("3000"), BigDecimal("7.50"), fmt))
        assertEquals("-$100.00 (-5.00%)", gainLabel(BigDecimal("-100"), BigDecimal("-5.00"), fmt))
        assertEquals("$0.00", gainLabel(BigDecimal.ZERO, null, fmt))
        assertEquals("—", gainLabel(null, null, fmt))
        assertEquals("BTC", unitOf("BTC-USD"))
        assertEquals("shares", unitOf("AAPL"))
    }

    @Test
    fun `the portfolio card totals the holdings and each row opens its holding`() {
        val opened = mutableListOf<String>()
        rule.setContent {
            SfTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PortfolioTotalsCard(summary, fmt)
                    Holdings.all(summary).forEach { h -> HoldingRow(h, summary.positions.first { it.symbol == h.symbol }, fmt) { opened += h.symbol } }
                }
            }
        }
        rule.onNodeWithTag("holdings-gain").assertTextEquals("+$2,900.00 (+6.90%)")
        rule.onNodeWithTag("total-return").assertTextEquals("+$900.00 (+1.80%)")
        rule.onNodeWithText("Bitcoin").assertExists()
        rule.onNodeWithText("0.5 BTC · $86,000.00 each").performScrollTo().assertExists()
        rule.onNodeWithText("-$100.00 (-5.00%)").performScrollTo().assertExists()
        rule.onNodeWithText("84.48% of portfolio").performScrollTo().assertExists()
        rule.onNodeWithTag("holding-AAPL").performScrollTo().performClick()
        assertEquals(listOf("AAPL"), opened)
    }

    @Test
    fun `a holding's page shows worth against paid, profit taken, the trades and buy and sell`() {
        val trades = mutableListOf<String>()
        var range = "1M"
        rule.setContent {
            SfTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HoldingContent("BTC-USD", chart, summary, "1M", { range = it }, fmt, { side, sym -> trades += "$side $sym" }, {}, {})
                }
            }
        }
        rule.onNodeWithText("BTC-USD · Bitcoin").assertExists()
        rule.onNodeWithTag("holding-gain").assertTextEquals("+$3,000.00 (+7.50%)")
        rule.onNodeWithTag("holding-chart").assertExists()
        rule.onNodeWithTag("range-gain").assertTextEquals("+$4,000.00")
        rule.onNodeWithTag("position-gain").performScrollTo().assertTextEquals("+$3,000.00 (+7.50%)")
        rule.onNodeWithTag("realized").performScrollTo().assertTextEquals("+$250.00")
        rule.onNodeWithTag("total-gain").performScrollTo().assertTextEquals("+$3,250.00")
        rule.onNodeWithText("You paid").performScrollTo().assertExists()
        rule.onNodeWithText("Sold 0.1 @ $82,500.00").performScrollTo().assertExists()
        rule.onNodeWithText("Bought 0.6 @ $80,000.00").performScrollTo().assertExists()
        rule.onNodeWithTag("holding-range-ALL").performScrollTo().performClick()
        assertEquals("ALL", range)
        rule.onNodeWithTag("holding-buy").performScrollTo().performClick()
        rule.onNodeWithTag("holding-sell").performScrollTo().performClick()
        assertEquals(listOf("BUY BTC-USD", "SELL BTC-USD"), trades)
    }

    @Test
    fun `a holding sold off still shows its history and profit, and cannot be sold`() {
        rule.setContent {
            SfTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    HoldingContent("BTC-USD", chart, summary.copy(positions = listOf(aapl)), "1M", {}, fmt, { _, _ -> }, {}, {})
                }
            }
        }
        rule.onNodeWithText("You no longer hold BTC-USD in this portfolio.").assertExists()
        rule.onNodeWithTag("realized").performScrollTo().assertTextEquals("+$250.00")
        rule.onNodeWithTag("holding-sell").performScrollTo().assertIsNotEnabled()
    }
}
