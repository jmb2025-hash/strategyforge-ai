package app.strategyforge.android

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.api.ApiError
import app.strategyforge.android.core.api.SfJson
import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.data.Dashboard
import app.strategyforge.android.core.model.RecommendationDetail
import app.strategyforge.android.core.state.AccessState
import app.strategyforge.android.core.state.AccessStep
import app.strategyforge.android.core.state.RecommendationPresenter
import app.strategyforge.android.core.state.RecommendationState
import app.strategyforge.android.ui.AccessActions
import app.strategyforge.android.ui.AccessScreen
import app.strategyforge.android.ui.DashboardContent
import app.strategyforge.android.ui.FreshnessBanner
import app.strategyforge.android.ui.RecommendationContent
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.formatters
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/** Compose UI tests (section 17): required states, accessibility cues and decision controls. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ComposeUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val fmt = formatters("America/Halifax")

    private val rec =
        """{"id":"11111111-1111-1111-1111-111111111111","strategyId":"s","strategyName":"Momentum","portfolioId":"p","symbol":"BTC-USD","status":"PENDING",
        "side":"BUY","quantity":"0.01","orderType":"LIMIT","limitPrice":"60000","referencePrice":"60010","maxDeviationPercent":"1.0","expiresAt":"2026-06-22T14:01:00Z",
        "rationale":"Entry: CLOSE GT 1","triggeredRules":["CLOSE GT 1"],"versionHash":"abcdef","createdAt":"2026-06-22T13:31:00Z"}"""

    private fun detail(
        status: String = "PENDING",
        token: Boolean = true,
    ): RecommendationDetail =
        SfJson.decodeFromString(
            RecommendationDetail.serializer(),
            """{"recommendation":${rec.replace("\"PENDING\"", "\"$status\"")},"actionToken":${if (token) """{"token":"t","expiresAt":"2026-06-22T13:40:00Z"}""" else "null"},"decisions":[],"disclaimer":"Simulated paper trade."}""",
        )

    private fun recScreen(
        state: RecommendationState,
        onAccept: () -> Unit = {},
    ) {
        rule.setContent { SfTheme { RecommendationContent(state, fmt, {}, {}, onAccept, {}, {}, {}, {}) } }
    }

    @Test
    fun `FR-063 pending recommendation can be accepted once and shows its rationale and disclaimer`() {
        var accepted = 0
        val d = detail()
        recScreen(RecommendationState(loading = false, detail = d, quantity = "0.01", limitPrice = "60000")) { accepted++ }
        rule.onNodeWithText("Entry: CLOSE GT 1").assertIsDisplayed()
        rule.onNodeWithText("Info: Simulated paper trade.").assertIsDisplayed()
        rule.onNodeWithTag("accept").assertIsEnabled().performClick()
        assertEquals(1, accepted)
    }

    @Test
    fun `a disallowed modification disables acceptance and explains why`() {
        val s = RecommendationState(loading = false, detail = detail(), quantity = "0.02", limitPrice = "60000")
        recScreen(s.copy(validation = RecommendationPresenter.validate(s)))
        rule.onNodeWithTag("accept").assertIsNotEnabled()
        rule.onNodeWithText("Warning: Quantity can only be reduced (max 0.01)").assertIsDisplayed()
    }

    @Test
    fun `expired recommendations offer no decision controls`() {
        recScreen(RecommendationState(loading = false, detail = detail("EXPIRED", token = false), quantity = "0.01"))
        assertEquals(0, rule.onAllNodesWithTag("accept").fetchSemanticsNodes().size)
        rule.onNodeWithText("Info: This recommendation is expired; no further action is possible.").assertIsDisplayed()
    }

    private val dashboard =
        """{"portfolios":[],"primary":{"portfolio":{"id":"p","name":"Main","status":"ACTIVE","startingBalance":"100000","reconciliationStatus":"OK"},
        "cash":"90000","buyingPower":"90000","equity":"101234.50","unrealizedPnl":"-12.30","totalReturn":"1234.50","fullyPriced":false,"positions":[],"asOf":"2026-06-22T13:31:00Z"},
        "emergency":{"pauseAll":true,"preventNewPositions":false,"updatedAt":"2026-06-22T13:00:00Z"},"unread":{"unread":2},
        "pendingRecommendations":{"items":[]},"diagnostics":{"overall":"OK","generatedAt":"2026-06-22T13:31:00Z","components":[]},
        "strategies":[{"id":"s","name":"Momentum","assetClass":"CRYPTO","status":"ACTIVE_AUTONOMOUS"}],"clock":{"mode":"REPLAY","now":"2026-06-22T13:31:00Z"}}"""

    @Test
    fun `NFR-009 dashboard P and L has spoken gain or loss and safety states are visible`() {
        val d = SfJson.decodeFromString(Dashboard.serializer(), dashboard)
        rule.setContent { SfTheme { DashboardContent(d, fmt) {} } }
        rule.onNodeWithContentDescription("Total return: gain of $1,234.50").assertIsDisplayed()
        rule.onNodeWithContentDescription("Unrealized: loss of $12.30").assertIsDisplayed()
        rule.onNodeWithText("Error: Pause All is engaged: no strategies are evaluated and no orders are placed.").assertIsDisplayed()
        rule.onNodeWithText("Warning: Autonomous paper trading is active for 1 strategy(ies).").assertIsDisplayed()
        rule.onNodeWithText("Warning: Some positions are carried at cost because a verified price is unavailable.").assertIsDisplayed()
    }

    @Test
    fun `offline cached data is labelled with its age`() {
        val now = Instant.parse("2026-06-22T14:00:00Z")
        val r = Resource.Data("x", Instant.parse("2026-06-22T13:50:00Z"), fromCache = true, stale = true, refreshing = false, offline = true, error = ApiError.Offline(null))
        rule.setContent { SfTheme { FreshnessBanner(r, fmt, now) } }
        rule.onNodeWithText("Warning: Offline. Showing data from 10 min ago; actions are unavailable until the backend is reachable.").assertIsDisplayed()
    }

    @Test
    fun `first-run recovery codes are shown with a confirmation`() {
        var saved = false
        val actions = AccessActions({}, { _, _, _, _ -> }, { _, _, _ -> }, { _, _, _ -> }, {}, {}, { saved = true }, {})
        rule.setContent { SfTheme { AccessScreen(AccessState(step = AccessStep.RECOVERY_CODES, recoveryCodes = listOf("aaaa-bbbb", "cccc-dddd")), actions) } }
        assertEquals(2, rule.onAllNodesWithTag("code").fetchSemanticsNodes().size)
        rule.onNodeWithText("I have stored them safely").performClick()
        assertEquals(true, saved)
    }
}
