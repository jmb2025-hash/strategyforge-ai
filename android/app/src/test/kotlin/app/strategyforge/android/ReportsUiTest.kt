package app.strategyforge.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.api.SfJson
import app.strategyforge.android.core.model.ReportView
import app.strategyforge.android.ui.ReportBody
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.formatters
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** FR-103 / FR-104 report screen: figures, accessible P and L and both disclaimers are shown. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ReportsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val view =
        """{"report":{"summary":{"portfolio":{"id":"p1","name":"Main paper","status":"ACTIVE","startingBalance":"100000"},"cash":"95000","buyingPower":"95000",
        "equity":"100120.50","realizedPnl":"12.00","unrealizedPnl":"-8.25","asOf":"2026-06-22T14:00:00Z"},"maxDrawdownPercent":"0.42","periodReturnPercent":"0.12",
        "costs":{"commissions":"4.50","spread":"1.20","slippage":"0.80","borrow":"0","dividends":"0","total":"6.50"},
        "byAsset":[{"key":"BTC-USD","label":"BTC-USD","realizedPnl":"12.00","trades":2}],"byStrategy":[],
        "benchmarks":[{"symbol":"SPY","returnPercent":"1.0"}],"disclaimer":"Simulated paper-trading results."},
        "outcomes":{"bySource":[{"group":"MANUAL","count":3,"realizedPnl":"12.00","note":"Manual orders"}],"byRecommendationDecision":[],
        "disclaimer":"These comparisons are descriptive and do not show that any decision caused an outcome."}}"""

    @Test
    fun `FR-103 FR-104 report shows costs, spoken P and L and the no-causation disclaimer`() {
        val v = SfJson.decodeFromString(ReportView.serializer(), view)
        val fmt = formatters("America/Halifax")
        rule.setContent { SfTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ReportBody(v, fmt) } } }
        rule.onNodeWithText("Main paper").assertExists()
        rule.onNodeWithText("$6.50").assertExists()
        rule.onNodeWithContentDescription("Unrealized P and L: loss of $8.25").assertExists()
        rule.onNodeWithText("Simulated paper-trading results.", substring = true).assertExists()
        rule.onNodeWithText("do not show that any decision caused an outcome", substring = true).assertExists()
        rule.onNodeWithText("1.00%").assertExists()
    }
}
