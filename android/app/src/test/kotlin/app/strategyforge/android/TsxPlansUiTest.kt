package app.strategyforge.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.api.SfJson
import app.strategyforge.android.core.model.TsxDataStatus
import app.strategyforge.android.core.model.TsxRun
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.TsxDataCard
import app.strategyforge.android.ui.TsxRunDetail
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-055 TSX portfolio plans: data download, approving a proposed rebalance, stopping asks first. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class TsxPlansUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val run =
        SfJson.decodeFromString(
            TsxRun.serializer(),
            """
            {"id":"r1","planId":"tsx-high-yield-trend","planName":"TSX high yield with trend guard","slot":2,"mode":"NOTIFY","drip":true,
             "startingCash":10000.0,"cash":12.5,"value":10250.0,"dividendsReceived":61.2,"status":"ACTIVE","startDay":"2026-09-01","lastDay":"2026-10-02",
             "pendingDay":"2026-10-01","pending":[{"symbol":"ENB","name":"Enbridge","weight":0.1}],
             "holdings":[{"symbol":"BCE","name":"BCE Inc.","shares":100.0,"price":33.0,"value":3300.0}],
             "values":[{"day":"2026-09-01","value":10000.0},{"day":"2026-10-02","value":10250.0}],
             "monthlyDividends":[{"month":"2026-09","amount":61.2}],
             "events":[{"day":"2026-09-15","kind":"DIVIDEND_REINVESTED","symbol":"BCE","amount":61.2}]}
            """.trimIndent(),
        )

    @Test
    fun `a proposed rebalance is approved from the run and stopping asks first`() {
        val calls = mutableListOf<String>()
        rule.setContent {
            SfTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TsxRunDetail(run, ActionState.Idle, { calls += "approve" }, { calls += "decline" }, { calls += "stop" })
                }
            }
        }
        rule.onNodeWithText("Slot 2: TSX high yield with trend guard").assertExists()
        rule.onNodeWithText("Rebalance waiting for approval (2026-10-01)").assertExists()
        rule.onNodeWithText("ENB · Enbridge").assertExists()
        rule.onNodeWithText("Dividend C$61.20 reinvested").assertExists()
        rule.onNodeWithTag("tsx-approve").performScrollTo().performClick()
        rule.onNodeWithTag("tsx-stop").performScrollTo().performClick()
        rule.onNodeWithText("Stop this plan?").assertExists()
        rule.onNodeWithText("Stop").performClick()
        assertEquals(listOf("approve", "stop"), calls)
    }

    @Test
    fun `without data the card offers the first download`() {
        var refreshed = false
        rule.setContent { SfTheme { TsxDataCard(TsxDataStatus(listings = 173), busy = false) { refreshed = true } } }
        rule.onNodeWithText("Latest trading day").assertExists()
        rule.onNodeWithTag("tsx-refresh").performClick()
        assertEquals(true, refreshed)
    }
}
