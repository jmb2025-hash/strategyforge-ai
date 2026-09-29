package app.strategyforge.android

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.api.SfJson
import app.strategyforge.android.core.model.Slot
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.SlotConflict
import app.strategyforge.android.ui.SlotConflictDialog
import app.strategyforge.android.ui.SlotsSection
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-035 one crypto and one stock strategy; replacing asks each time, with keep preselected. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StrategySlotsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val slots =
        SfJson.decodeFromString(
            ListSerializer(Slot.serializer()),
            """
            [{"assetClass":"CRYPTO","strategy":{"id":"st1","name":"Chart Champions BTC","assetClass":"CRYPTO","status":"ACTIVE_AUTONOMOUS"},
              "activation":{"id":"a1","strategyId":"st1","portfolioId":"p1","mode":"AUTONOMOUS","allocationPercent":"40","status":"ACTIVE","createdAt":"2026-10-01T12:00:00Z"},
              "holdings":[{"symbol":"BTC-USD","side":"LONG","quantity":"0.1"}]},
             {"assetClass":"US_EQUITY"}]
            """.trimIndent(),
        )

    @Test
    fun `D-035 the slots show the running crypto strategy, an empty stock slot, and stop asks first`() {
        val stopped = mutableListOf<String>()
        rule.setContent { SfTheme { SlotsSection(slots, onOpen = {}, onStop = { stopped += it }) } }
        rule.onNodeWithText("Chart Champions BTC").assertExists()
        rule.onNodeWithText("Autonomous: trades are placed automatically", substring = true).assertExists()
        rule.onNodeWithText("Open: 0.1 BTC-USD").assertExists()
        rule.onNodeWithText("No Stocks strategy running", substring = true).assertExists()
        rule.onNodeWithTag("stop-CRYPTO").performClick()
        rule.onNodeWithText("Stop this strategy?").assertExists()
        rule.onAllNodesWithText("Stop").onLast().performClick()
        assertEquals(listOf("st1"), stopped)
    }

    @Test
    fun `D-035 replacing asks keep or close with keep preselected`() {
        var decided: Boolean? = null
        val conflict = SlotConflict("Chart Champions BTC", listOf("long 0.1 BTC-USD"), retry = {})
        rule.setContent { SfTheme { SlotConflictDialog(conflict) { decided = it } } }
        rule.onNodeWithText("Replace Chart Champions BTC?").assertExists()
        rule.onNodeWithText("long 0.1 BTC-USD", substring = true).assertExists()
        rule.onNodeWithTag("replace").performClick()
        assertEquals(true, decided)
    }

    @Test
    fun `choosing close sends close`() {
        var decided: Boolean? = null
        rule.setContent { SfTheme { SlotConflictDialog(SlotConflict("Old", listOf("long 1 ETH-USD"), retry = {})) { decided = it } } }
        rule.onNodeWithTag("close").performClick()
        rule.onNodeWithTag("replace").performClick()
        assertEquals(false, decided)
    }
}
