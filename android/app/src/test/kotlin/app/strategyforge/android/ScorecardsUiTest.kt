package app.strategyforge.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.api.SfJson
import app.strategyforge.android.core.model.Scorecard
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.ui.Scorecards
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.formatters
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-037 scorecards: paper and backtest results side by side, and building a better strategy from them. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ScorecardsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val cards =
        SfJson.decodeFromString(
            ListSerializer(Scorecard.serializer()),
            """
            [{"strategy":{"id":"st1","name":"Chart Champions BTC","assetClass":"CRYPTO","status":"ACTIVE_AUTONOMOUS"},
              "live":{"closedTrades":24,"wins":14,"losses":10,"winRatePercent":"58.3","realizedPnl":"812.40","averagePnl":"33.85",
                      "bestTrade":"210.00","worstTrade":"-95.10","maxDrawdown":"240.00","openPositions":1,"activeDays":"12.5"},
              "backtest":{"backtestId":"b1","netReturnPercent":"6.2","maxDrawdownPercent":"3.1","trades":88,"winRatePercent":"55.0","profitFactor":"1.42"}},
             {"strategy":{"id":"st2","name":"Breakout ETH","assetClass":"CRYPTO","status":"PAUSED"},
              "live":{"closedTrades":0,"realizedPnl":"0","maxDrawdown":"0","activeDays":"0.0"},
              "sampleWarning":"No trades or backtest yet: nothing to judge."}]
            """.trimIndent(),
        )

    private fun show(
        list: List<Scorecard>?,
        onBuild: () -> Unit = {},
        onOpen: (String) -> Unit = {},
    ) = rule.setContent {
        SfTheme {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Scorecards("CRYPTO", list, formatters("UTC"), ActionState.Idle, onAsset = {}, onBuild = onBuild, onOpen = onOpen)
            }
        }
    }

    @Test
    fun `D-037 each strategy shows its paper results, its backtest and a warning when there is too little to judge`() {
        val opened = mutableListOf<String>()
        show(cards, onOpen = { opened += it })
        rule.onNodeWithText("1. Chart Champions BTC").assertExists()
        rule.onNodeWithText("24 (14 won, 10 lost)").assertExists()
        rule.onNodeWithText("58.30%").assertExists()
        rule.onNodeWithText("6.20%").assertExists()
        rule.onNodeWithText("2. Breakout ETH").assertExists()
        rule.onNodeWithText("No closed paper trades yet").assertExists()
        rule.onNodeWithText("Not backtested yet").assertExists()
        rule.onNodeWithText("nothing to judge", substring = true).assertExists()
        assertEquals(2, rule.onAllNodesWithTag("scorecard").fetchSemanticsNodes().size)
        rule.onNodeWithText("1. Chart Champions BTC").performScrollTo().performClick()
        assertEquals(listOf("st1"), opened)
    }

    @Test
    fun `D-037 building a better strategy needs at least two strategies`() {
        var built = 0
        show(cards, onBuild = { built++ })
        rule
            .onNodeWithTag("build-better")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, built)
    }

    @Test
    fun `D-037 with one strategy the build button is off and says why`() {
        show(cards.take(1))
        rule.onNodeWithTag("build-better").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("Needs at least two tested strategies.").assertExists()
    }

    @Test
    fun `D-038 strategies are compared side by side on the chosen measure`() {
        show(cards)
        rule.onNodeWithTag("comparison").assertExists()
        // Once in the comparison bars and once on the strategy's own card.
        rule.onAllNodesWithText("▲ +$812.40").assertCountEquals(2)
        rule.onNodeWithTag("metric-bt").performScrollTo().performClick()
        rule.onAllNodesWithText("6.20%").assertCountEquals(2)
        rule.onAllNodesWithText("no data").fetchSemanticsNodes().let { assertEquals(1, it.size) }
    }
}
