package app.strategyforge.android

import android.app.Application
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

/** D-035/D-051 numbered slots per asset class; replacing asks each time, with keep preselected. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class StrategySlotsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val slots =
        SfJson.decodeFromString(
            ListSerializer(Slot.serializer()),
            """
            [{"assetClass":"CRYPTO","number":1,"strategy":{"id":"st1","name":"Chart Champions BTC","assetClass":"CRYPTO","status":"ACTIVE_AUTONOMOUS"},
              "activation":{"id":"a1","strategyId":"st1","portfolioId":"p1","mode":"AUTONOMOUS","allocationPercent":"40","status":"ACTIVE","createdAt":"2026-10-01T12:00:00Z"},
              "holdings":[{"symbol":"BTC-USD","side":"LONG","quantity":"0.1"}]},
             {"assetClass":"CRYPTO","number":2},
             {"assetClass":"US_EQUITY","number":1},
             {"assetClass":"US_EQUITY","number":2}]
            """.trimIndent(),
        )

    @Test
    fun `D-073 a paused crypto plan is listed under Crypto with its reason and Resume`() {
        val resumed = mutableListOf<String>()
        val paused =
            listOf(
                app.strategyforge.android.core.model.Strategy(
                    "st9",
                    "BTC trend core",
                    "CRYPTO",
                    "PAUSED",
                    statusReason = "Autonomous mode paused until re-authorized: global risk profile changed",
                ),
            )
        val empty = slots.map { if (it.assetClass == "CRYPTO") it.copy(strategy = null, activation = null, holdings = emptyList()) else it }
        rule.setContent { SfTheme { SlotsSection(empty, onOpen = {}, onStop = {}, paused = paused, onResume = { resumed += it }) } }
        rule.onNodeWithText("BTC trend core").assertExists()
        rule.onNodeWithText("global risk profile changed", substring = true).assertExists()
        rule.onNodeWithText("No crypto strategy running", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("resume-st9").performClick()
        assertEquals(listOf("st9"), resumed)
    }

    @Test
    fun `D-051 the slots show the running crypto strategy with its slot number, no stock strategy, and stop asks first`() {
        val stopped = mutableListOf<String>()
        rule.setContent { SfTheme { SlotsSection(slots, onOpen = {}, onStop = { stopped += it }) } }
        rule.onNodeWithText("Slot 1 · Chart Champions BTC").assertExists()
        rule.onNodeWithText("Crypto · 1 of 2 slots in use").assertExists()
        rule.onNodeWithText("Autonomous: trades are placed automatically", substring = true).assertExists()
        rule.onNodeWithText("Open: 0.1 BTC-USD").assertExists()
        rule.onNodeWithText("No stocks strategy running", substring = true).assertExists()
        rule.onNodeWithTag("stop-CRYPTO-1").performClick()
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

    @Test
    fun `D-061 running TSX plans are listed with the slots and stopping asks first`() {
        val runs =
            SfJson.decodeFromString(
                ListSerializer(
                    app.strategyforge.android.core.model.TsxRun
                        .serializer(),
                ),
                """
                [{"id":"r1","planId":"tsx-dividend-growth-momentum","planName":"Dividend Growth & Momentum 24","slot":1,"mode":"NOTIFY","drip":true,
                  "startingCash":1000.0,"cash":0.0,"value":999.0,"liveValue":995.82,"status":"ACTIVE",
                  "holdings":[{"symbol":"RY","shares":1.0,"price":180.0,"value":180.0}]}]
                """.trimIndent(),
            )
        val stopped = mutableListOf<String>()
        rule.setContent {
            SfTheme {
                androidx.compose.foundation.layout.Column(
                    androidx.compose.ui.Modifier
                        .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                ) {
                    SlotsSection(slots, onOpen = {}, onStop = {}, tsxRuns = runs, onOpenTsx = {}, onStopTsx = { stopped += it })
                }
            }
        }
        rule.onNodeWithText("TSX · 1 of 10 slots in use").assertExists()
        rule.onNodeWithText("Slot 1 · Dividend Growth & Momentum 24").assertExists()
        rule.onNodeWithText("Notifications: you approve each rebalance").assertExists()
        rule.onNodeWithText("C$996 · -0.4% since start · 1 holding").assertExists()
        rule.onNodeWithTag("stop-TSX-1").performScrollTo().performClick()
        rule.onNodeWithText("Stop this TSX plan?").assertExists()
        rule.onAllNodesWithText("Stop").onLast().performClick()
        assertEquals(listOf("r1"), stopped)
    }
}
