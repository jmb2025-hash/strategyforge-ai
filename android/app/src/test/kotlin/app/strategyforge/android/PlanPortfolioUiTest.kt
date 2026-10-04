package app.strategyforge.android

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.api.SfJson
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.PortfolioSummary
import app.strategyforge.android.core.model.Scorecard
import app.strategyforge.android.core.model.Slot
import app.strategyforge.android.ui.RunningPlan
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.SlotPlanCard
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

/** D-058 running plans in the Portfolio menu: a slot plan shown as a portfolio with its own results. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PlanPortfolioUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val slot =
        SfJson.decodeFromString(
            Slot.serializer(),
            """
            {"assetClass":"CRYPTO","number":1,"strategy":{"id":"st1","name":"Chart Champions v3","assetClass":"CRYPTO","status":"ACTIVE_AUTONOMOUS"},
             "activation":{"id":"a1","strategyId":"st1","portfolioId":"p1","mode":"AUTONOMOUS","allocationPercent":"40","status":"ACTIVE","createdAt":"2026-10-01T12:00:00Z"},
             "holdings":[{"symbol":"BTC-USD","side":"LONG","quantity":"0.1"}]}
            """.trimIndent(),
        )

    private val summary =
        SfJson.decodeFromString(
            PortfolioSummary.serializer(),
            """
            {"portfolio":{"id":"p1","name":"Crypto slot 1","status":"ACTIVE","startingBalance":"10000"},"cash":"4000","buyingPower":"4000","equity":"10150.50",
             "positions":[{"instrumentId":"i1","symbol":"BTC-USD","assetClass":"CRYPTO","side":"LONG","quantity":"0.1","costBasis":"6000","averageCost":"60000","unrealizedPnl":"30.00"},
                          {"instrumentId":"i2","symbol":"ETH-USD","assetClass":"CRYPTO","side":"LONG","quantity":"1","costBasis":"2600","averageCost":"2600","unrealizedPnl":"-99.00"}],
             "asOf":"2026-10-04T12:00:00Z"}
            """.trimIndent(),
        )

    private val score =
        SfJson.decodeFromString(
            Scorecard.serializer(),
            """
            {"strategy":{"id":"st1","name":"Chart Champions v3","assetClass":"CRYPTO","status":"ACTIVE_AUTONOMOUS"},
             "live":{"closedTrades":3,"wins":2,"losses":1,"winRatePercent":"66.67","realizedPnl":"120.50","activeDays":"3"}}
            """.trimIndent(),
        )

    @Test
    fun `a slot plan shows its slot, its own positions' profit and its closed trades`() {
        val plan = RunningPlan.SlotPlan(slot)
        assertEquals("Crypto 1 · Chart Champions v3", plan.label)
        assertEquals("p1", plan.portfolioId)
        rule.setContent { SfTheme { SlotPlanCard(plan, score, summary, Formatters(ZoneId.of("UTC"))) } }
        rule.onNodeWithText("Chart Champions v3").assertExists()
        rule.onNodeWithText("Crypto slot 1 · Autonomous · trades in Crypto slot 1").assertExists()
        // Only BTC-USD belongs to the plan: unrealized +30.00, total 120.50 + 30.00.
        rule.onNodeWithText("+$30.00", substring = true).assertExists()
        rule.onNodeWithText("+$150.50", substring = true).assertExists()
        rule.onNodeWithText("3 (2 won, 1 lost)").assertExists()
    }
}
