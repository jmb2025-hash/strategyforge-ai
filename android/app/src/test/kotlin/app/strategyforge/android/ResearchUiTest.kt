package app.strategyforge.android

import android.app.Application
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.api.SfJson
import app.strategyforge.android.core.model.ResearchDetail
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.ui.NewResearch
import app.strategyforge.android.ui.ResearchConversation
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.formatters
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-034 research as a conversation: one message to start, replies, sources and compile. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class ResearchUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val fmt = formatters("America/Halifax")

    private fun detail(
        status: String = "COMPLETED",
        lastRun: String = "SUCCEEDED",
        compilations: String = "[]",
    ): ResearchDetail =
        SfJson.decodeFromString(
            ResearchDetail.serializer(),
            """
            {"session":{"id":"s1","title":"Chart Champions","providerType":"GEMINI","model":"gemini-2.5-flash","assetClass":"CRYPTO","universe":[],"timeframe":"",
              "status":"$status","reviewStatus":"UNVERIFIED","requestsUsed":2,"maxRequests":100,"costUsd":"0","maxCostUsd":"20","createdAt":"2026-10-01T12:00:00Z","conversation":true},
             "runs":[
              {"id":"r1","purpose":"RESEARCH","status":"SUCCEEDED","label":"UNVERIFIED AI OUTPUT","responseText":"They trade Bitcoin and Ethereum.","startedAt":"2026-10-01T12:00:00Z",
               "ownerMessage":"Research Chart Champions","sources":[{"url":"https://example.org/review","title":"example.org"}]},
              {"id":"r2","purpose":"RESEARCH","status":"$lastRun","label":"x","responseText":${if (lastRun == "SUCCEEDED") "\"Use the 4h chart.\"" else "null"},
               "failureCode":${if (lastRun == "FAILED") "\"RATE_LIMITED\"" else "null"},"startedAt":"2026-10-01T12:05:00Z","ownerMessage":"Focus on Bitcoin"}],
             "compilations":$compilations,
             "disclaimer":"AI research is unverified until you review it."}
            """.trimIndent(),
        )

    @Test
    fun `D-034 new research needs only a message and the crypto or stocks choice`() {
        val started = mutableListOf<Pair<String, String>>()
        rule.setContent { SfTheme { NewResearch(busy = false) { m, a -> started += m to a } } }
        rule.onNodeWithTag("start-research").assertIsNotEnabled()
        rule.onNodeWithTag("asset-US_EQUITY").performClick()
        rule.onNodeWithTag("research-message").performTextReplacement("Research Warren Buffett's investing rules")
        rule.onNodeWithTag("start-research").performClick()
        assertEquals(listOf("Research Warren Buffett's investing rules" to "US_EQUITY"), started)
    }

    @Test
    fun `D-034 the conversation shows both sides with sources, replies and compiles`() {
        val sent = mutableListOf<String>()
        var compiled = 0
        rule.setContent { SfTheme { ResearchConversation(detail(), fmt, ActionState.Idle, { sent += it }, {}, { compiled++ }, {}) } }
        rule.onNodeWithText("Research Chart Champions").assertExists()
        rule.onNodeWithText("They trade Bitcoin and Ethereum.").assertExists()
        rule.onNodeWithText("Focus on Bitcoin").assertExists()
        rule.onNodeWithText("• example.org").assertExists()
        rule.onNodeWithTag("reply").performScrollTo().performTextReplacement("What stop-loss do they use?")
        rule.onNodeWithTag("send").performScrollTo().performClick()
        assertEquals(listOf("What stop-loss do they use?"), sent)
        rule
            .onNodeWithTag("compile")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, compiled)
    }

    @Test
    fun `while the AI answers, sending and compiling wait, and a failed answer offers a retry`() {
        rule.setContent { SfTheme { ResearchConversation(detail(status = "RUNNING", lastRun = "RUNNING"), fmt, ActionState.Idle, {}, {}, {}, {}) } }
        rule.onNodeWithTag("thinking").assertExists()
        rule.onNodeWithTag("reply").performScrollTo().performTextReplacement("more")
        rule.onNodeWithTag("send").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("compile").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun `a failed answer explains the reason and can be retried`() {
        var retried = 0
        rule.setContent { SfTheme { ResearchConversation(detail(status = "FAILED", lastRun = "FAILED"), fmt, ActionState.Idle, {}, { retried++ }, {}, {}) } }
        rule.onNodeWithText("free quota", substring = true).assertExists()
        rule.onNodeWithTag("retry").performScrollTo().performClick()
        assertEquals(1, retried)
    }

    @Test
    fun `a compiled strategy can be opened from the conversation`() {
        val opened = mutableListOf<String>()
        val compilations = """[{"id":"c1","status":"COMPILED","strategyId":"st1","createdAt":"2026-10-01T12:10:00Z","issues":[]}]"""
        rule.setContent { SfTheme { ResearchConversation(detail(compilations = compilations), fmt, ActionState.Idle, {}, {}, {}, { opened += it }) } }
        rule.onNodeWithText("Strategy created and validated", substring = true).performScrollTo().assertExists()
        rule.onNodeWithTag("open-strategy").performScrollTo().performClick()
        assertEquals(listOf("st1"), opened)
    }
}
