package app.strategyforge.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.api.SfJson
import app.strategyforge.android.core.model.CandleChart
import app.strategyforge.android.core.model.EquityChart
import app.strategyforge.android.core.model.PortfolioSummary
import app.strategyforge.android.ui.AllocationCard
import app.strategyforge.android.ui.Art
import app.strategyforge.android.ui.EmptyState
import app.strategyforge.android.ui.EquityCard
import app.strategyforge.android.ui.Loading
import app.strategyforge.android.ui.PriceChartCard
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.formatters
import app.strategyforge.android.ui.toMarkers
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-038 charts: equity curve with ranges, candlesticks with trade markers, and allocation. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ChartsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val fmt = formatters("UTC")

    private val equity =
        SfJson.decodeFromString(
            EquityChart.serializer(),
            """
            {"portfolioId":"p1","range":"1W","change":"1250.00","changePercent":"1.25","points":[
              {"at":"2026-09-22T00:00:00Z","value":"100000.00"},{"at":"2026-09-24T00:00:00Z","value":"99500.00"},
              {"at":"2026-09-26T00:00:00Z","value":"100800.00"},{"at":"2026-09-29T00:00:00Z","value":"101250.00"}]}
            """.trimIndent(),
        )

    private val candles =
        SfJson.decodeFromString(
            CandleChart.serializer(),
            """
            {"symbol":"BTC-USD","timeframe":"1h","status":"VERIFIED","detail":"OK","bars":[
              {"t":"2026-09-29T10:00:00Z","o":"64000","h":"64500","l":"63800","c":"64400","v":"12"},
              {"t":"2026-09-29T11:00:00Z","o":"64400","h":"64900","l":"64300","c":"64800","v":"15"},
              {"t":"2026-09-29T12:00:00Z","o":"64800","h":"64850","l":"64100","c":"64200","v":"20"}],
             "trades":[{"at":"2026-09-29T10:20:00Z","side":"BUY","price":"64100","quantity":"0.1"},
                       {"at":"2026-09-29T12:30:00Z","side":"SELL","price":"64250","quantity":"0.1"}]}
            """.trimIndent(),
        )

    @Test
    fun `D-038 the equity card shows value, change over the range and a chart, and switches range`() {
        val picked = mutableListOf<String>()
        rule.setContent {
            SfTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    EquityCard(equity, "1W", { picked += it }, fmt, "101250.00", "Main paper")
                }
            }
        }
        rule.onNodeWithText("Main paper").assertExists()
        rule.onNodeWithText("$101,250.00").assertExists()
        rule.onNodeWithContentDescription("gain of $1,250.00", substring = true).assertExists()
        rule.onNodeWithContentDescription("Portfolio value chart for 1W: from $100,000.00 to $101,250.00").assertExists()
        rule.onNodeWithTag("equity-chart").performTouchInput { swipeRight() }
        rule.onNodeWithTag("range-1M").performScrollTo().performClick()
        assertEquals(listOf("1M"), picked)
    }

    @Test
    fun `D-038 candlesticks are summarised for screen readers and trades become buy and sell markers`() {
        var tf = ""
        rule.setContent {
            SfTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PriceChartCard(candles, "1h", { tf = it }, fmt)
                }
            }
        }
        rule.onNodeWithText("BTC-USD").assertExists()
        rule.onNodeWithText("▲ 1 buy · ▼ 1 sell on this chart").assertExists()
        rule
            .onNodeWithContentDescription("BTC-USD 1h candlestick chart, 3 bars, last 64,200, high 64,900, low 63,800, 2 trades marked")
            .assertExists()
            .performTouchInput { swipeRight() }
        rule.onNodeWithTag("tf-4h").performScrollTo().performClick()
        assertEquals("4h", tf)

        val markers = candles.toMarkers(fmt)
        assertEquals(listOf(true, false), markers.map { it.buy })
        assertEquals("Bought 0.1 @ 64,100", markers.first().label)
    }

    @Test
    fun `D-038 missing price data says so instead of spinning`() {
        rule.setContent { SfTheme { PriceChartCard(null, "1h", {}, fmt, error = "Add a Twelve Data key first") } }
        rule.onNodeWithText("Warning: Add a Twelve Data key first").assertExists()
    }

    @Test
    fun `D-038 allocation shows cash and positions as shares of the portfolio`() {
        val s =
            SfJson.decodeFromString(
                PortfolioSummary.serializer(),
                """
                {"portfolio":{"id":"p1","name":"Main","status":"ACTIVE","startingBalance":"100000","reconciliationStatus":"OK"},
                 "cash":"75000","buyingPower":"75000","equity":"100000","asOf":"2026-09-29T12:00:00Z",
                 "positions":[{"instrumentId":"i1","symbol":"BTC-USD","assetClass":"CRYPTO","side":"LONG","quantity":"0.3","costBasis":"19000","averageCost":"63333","marketValue":"20000"},
                              {"instrumentId":"i2","symbol":"ETH-USD","assetClass":"CRYPTO","side":"LONG","quantity":"2","costBasis":"5100","averageCost":"2550","marketValue":"5000"}]}
                """.trimIndent(),
            )
        rule.setContent { SfTheme { AllocationCard(s, fmt) } }
        rule.onNodeWithContentDescription("Allocation: Cash 75.0%, BTC-USD 20.0%, ETH-USD 5.0%").assertExists()
    }

    @Test
    fun `D-038 empty screens show an illustration with their message, and loading shows a placeholder`() {
        rule.setContent {
            SfTheme {
                Column {
                    EmptyState("No research yet.", Art.RESEARCH)
                    Loading()
                }
            }
        }
        rule.onNodeWithTag("empty").assertExists()
        rule.onNodeWithText("No research yet.").assertExists()
        rule.onNodeWithContentDescription("Loading").assertExists()
    }
}
