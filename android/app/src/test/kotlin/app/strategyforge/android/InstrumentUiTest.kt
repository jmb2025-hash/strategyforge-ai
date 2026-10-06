package app.strategyforge.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.ChartPoint
import app.strategyforge.android.core.model.InstrumentHit
import app.strategyforge.android.core.model.InstrumentInfo
import app.strategyforge.android.core.model.Position
import app.strategyforge.android.ui.Field
import app.strategyforge.android.ui.InstrumentContent
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.SymbolField
import app.strategyforge.android.ui.hitKind
import app.strategyforge.android.ui.rememberFieldTargets
import app.strategyforge.android.ui.volume
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

/** D-065 the order screen's symbol search, field errors that scroll into view, and the symbol information screen. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class InstrumentUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val fmt = Formatters(ZoneId.of("UTC"))

    private val btc = InstrumentHit("BTC-USD", "Bitcoin", "CRYPTO", lastPrice = "85806.09")
    private val rklb = InstrumentHit("RKLB", "Rocket Lab Corporation", "US_EQUITY", "NASDAQ", "Industrials", "Aerospace & Defense", added = false)

    @Test
    fun `suggestions show names and prices, and picking or asking for info reports the symbol`() {
        val picked = mutableListOf<String>()
        val info = mutableListOf<String>()
        rule.setContent {
            SfTheme {
                var text by remember { mutableStateOf("ro") }
                var hits by remember { mutableStateOf(listOf(btc, rklb)) }
                SymbolField(text, { text = it }, hits, {
                    picked += it.symbol
                    text = it.symbol
                    hits = listOf(it)
                }, { info += it }, fmt)
            }
        }
        rule.onNodeWithText("Rocket Lab Corporation").assertExists()
        rule.onNodeWithText("NASDAQ · Industrials · added when you trade it").assertExists()
        rule.onNodeWithTag("info-BTC-USD").performClick()
        assertEquals(listOf("BTC-USD"), info)
        rule.onNodeWithTag("suggestion-RKLB").performClick()
        assertEquals(listOf("RKLB"), picked)
        // Once the field holds the picked symbol, the list closes.
        rule.onNodeWithTag("symbol-suggestions").assertDoesNotExist()
    }

    @Test
    fun `a field with a problem shows it underneath and is scrolled into view`() {
        rule.setContent {
            SfTheme {
                val targets = rememberFieldTargets("quantity")
                val scope = rememberCoroutineScope()
                var error by remember { mutableStateOf<String?>(null) }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Button(onClick = {
                        error = "Enter a quantity above zero"
                        scope.launch { targets.getValue("quantity").show() }
                    }) { Text("Submit") }
                    Spacer(Modifier.height(2000.dp))
                    Field("Quantity", "0", {}, targets.getValue("quantity").modifier.testTag("qty"), number = true, error = error)
                }
            }
        }
        rule.onNodeWithText("Submit").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Enter a quantity above zero").assertExists()
        rule.onNodeWithTag("qty").assertIsDisplayed()
    }

    @Test
    fun `the information screen shows the price, change, ranges, position and trading price`() {
        val info =
            InstrumentInfo(
                btc,
                price = "85806.09",
                previousClose = "86639.24",
                currency = "USD",
                exchange = "CCC",
                dayHigh = "85940.48",
                dayLow = "85750.58",
                yearHigh = "126198.07",
                yearLow = "57747.77",
                volume = "30001854464",
                range = "1D",
                points = listOf(ChartPoint("2026-10-06T00:00:00Z", "86600"), ChartPoint("2026-10-06T12:00:00Z", "85806.09")),
                tradingPrice = "85790.12",
                tradingPriceAt = "2026-10-06T12:00:01Z",
                tradingSource = "COINBASE",
            )
        val trades = mutableListOf<String>()
        val position = Position("i", "BTC-USD", "CRYPTO", "LONG", "0.5", "43000", "86000", "85806.09", "42903.05", "-96.95")
        rule.setContent {
            SfTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    InstrumentContent(info, "1D", {}, fmt, position, { side, sym -> trades += "$side $sym" }, {})
                }
            }
        }
        rule.onNodeWithText("BTC-USD · Bitcoin").assertExists()
        rule.onNodeWithText("(-0.96%) · today", substring = true).assertExists()
        rule.onNodeWithText("30.0B").performScrollTo().assertExists()
        rule.onNodeWithText("Coinbase").performScrollTo().assertExists()
        rule.onNodeWithTag("instrument-position").performScrollTo().assertExists()
        rule.onNodeWithTag("info-buy").performScrollTo().performClick()
        rule.onNodeWithTag("info-sell").performScrollTo().performClick()
        assertEquals(listOf("BUY BTC-USD", "SELL BTC-USD"), trades)
    }

    @Test
    fun `D-066 the amount hint estimates a fractional quantity from the last price`() {
        assertEquals(
            "About 0.00582709 BTC at $85,806.09, before costs.",
            app.strategyforge.android.ui
                .amountHint("500", "85806.09", "BTC-USD", fmt),
        )
        assertEquals(
            "About 2.5 AAPL at $200.00, before costs.",
            app.strategyforge.android.ui
                .amountHint("500", "200", "aapl", fmt),
        )
        assert(
            app.strategyforge.android.ui
                .amountHint("", "200", "AAPL", fmt)
                .startsWith("Fractions are allowed"),
        )
        assert(
            app.strategyforge.android.ui
                .amountHint("500", null, "AAPL", fmt)
                .startsWith("Fractions are allowed"),
        )
    }

    @Test
    fun `labels for search results and volumes`() {
        assertEquals("Crypto", hitKind(btc))
        assertEquals("NASDAQ · Industrials · added when you trade it", hitKind(rklb))
        assertEquals("US stock · not available for trading", hitKind(InstrumentHit("X", "X", "US_EQUITY", active = false)))
        assertEquals("126.4M", volume("126384437"))
        assertEquals("950", volume("950"))
    }
}
