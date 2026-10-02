package app.strategyforge.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
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
import app.strategyforge.android.core.model.ImportNotes
import app.strategyforge.android.core.model.ResearchDetail
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.ui.ImportNotesSection
import app.strategyforge.android.ui.ImportResearch
import app.strategyforge.android.ui.ImportStrategyPanel
import app.strategyforge.android.ui.NewResearch
import app.strategyforge.android.ui.PlanSection
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

    @Test
    fun `D-040 the message box counts characters against the budget limit and blocks sending when too long`() {
        var started = 0
        rule.setContent { SfTheme { NewResearch(busy = false, limit = 20) { _, _ -> started++ } } }
        rule.onNodeWithTag("research-message").performTextReplacement("Research Chart Champions")
        rule.onNodeWithText("24 / 20 characters", substring = true).assertExists()
        rule.onNodeWithText("More → AI budget", substring = true).assertExists()
        rule.onNodeWithTag("start-research").assertIsNotEnabled()
        rule.onNodeWithTag("research-message").performTextReplacement("Research BTC")
        rule.onNodeWithText("12 / 20 characters").assertExists()
        rule.onNodeWithTag("start-research").assertIsEnabled().performClick()
        assertEquals(1, started)
    }

    @Test
    fun `D-041 research from another AI can be pasted in whole and turned into a strategy`() {
        val imported = mutableListOf<Pair<String, String>>()
        rule.setContent { SfTheme { Column(Modifier.verticalScroll(rememberScrollState())) { ImportResearch(busy = false) { t, a -> imported += t to a } } } }
        rule.onNodeWithTag("open-import").performClick()
        rule.onNodeWithTag("import-asset-US_EQUITY").performClick()
        val long = "Entry and exit rules. ".repeat(5_000)
        rule.onNodeWithTag("import-text").performTextReplacement(long)
        rule.onNodeWithText("109,999 characters", substring = true).assertExists()
        rule.onNodeWithTag("import-research").performScrollTo().performClick()
        assertEquals(listOf(long to "US_EQUITY"), imported)
    }

    @Test
    fun `D-041 instructions for my own AI can be copied and its reply imported`() {
        val copied = mutableListOf<String>()
        var imports = 0
        rule.setContent {
            SfTheme {
                var json by remember { mutableStateOf("") }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ImportStrategyPanel(json, { json = it }, onCopyInstructions = { copied += it }, onOpenAi = {}, onImport = { imports++ })
                }
            }
        }
        rule.onNodeWithTag("author-asset-US_EQUITY").performClick()
        rule.onNodeWithTag("copy-instructions").performClick()
        assertEquals(listOf("US_EQUITY"), copied)
        rule.onNodeWithTag("import-strategy").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("strategy-json").performScrollTo().performTextReplacement("Here you go:\n```json\n{}\n```")
        rule
            .onNodeWithTag("import-strategy")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(1, imports)
    }

    @Test
    fun `D-043 the AI's readback, research log and missing points are shown with the strategy`() {
        rule.setContent {
            SfTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ImportNotesSection(
                        ImportNotes(
                            readback = listOf("Long when price sweeps the 42-bar low and closes back above it"),
                            furtherResearch = listOf("Stop placement: vague -> under the SFP wick (source: course notes)"),
                            stillMissing = listOf("Short entry: never described - left out"),
                        ),
                    )
                }
            }
        }
        rule.onNodeWithText("From your research AI").assertIsDisplayed()
        rule.onNodeWithText("• Long when price sweeps the 42-bar low and closes back above it").assertIsDisplayed()
        rule.onNodeWithTag("notes-research").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("notes-missing").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `D-045 a plan shows its setups with results and its rules can be changed`() {
        val saved = mutableListOf<Triple<String, String, String?>>()
        val plan =
            app.strategyforge.android.core.model.PlanInfo(
                maximumOpenRiskPercent = "3",
                longContext = true,
                setups =
                    listOf(
                        app.strategyforge.android.core.model
                            .SetupInfo("SFP_HIGH", "Swing failure at range high", priority = 2, direction = "SHORT_ONLY"),
                        app.strategyforge.android.core.model
                            .SetupInfo("SFP_LOW", "Swing failure at range low", priority = 1, conditional = true),
                    ),
            )
        val scores =
            listOf(
                app.strategyforge.android.core.model
                    .SetupScore("SFP_LOW", "Swing failure at range low", backtestTrades = 42, backtestWinRatePercent = "55", backtestNetPnl = "1200"),
            )
        rule.setContent {
            SfTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PlanSection(plan, scores, "PAPER_ELIGIBLE") { c, k, r -> saved += Triple(c, k, r) }
                }
            }
        }
        rule.onNodeWithText("Setups (2)").assertExists()
        rule.onNodeWithText("1. Swing failure at range low").assertExists()
        rule.onNodeWithText("2. Swing failure at range high").assertExists()
        rule.onNodeWithTag("setup-backtest-SFP_LOW").assertExists()
        rule.onNodeWithTag("save-plan-rules").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("conflict-STACK").performScrollTo().performClick()
        rule
            .onNodeWithTag("save-plan-rules")
            .performScrollTo()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf(Triple("STACK", "SHARED", "3")), saved)
    }
}
