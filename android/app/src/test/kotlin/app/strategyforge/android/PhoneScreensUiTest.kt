package app.strategyforge.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.model.Provider
import app.strategyforge.android.core.model.ProviderType
import app.strategyforge.android.core.model.RuntimeState
import app.strategyforge.android.core.model.StockData
import app.strategyforge.android.ui.AiProvidersContent
import app.strategyforge.android.ui.AiProvidersState
import app.strategyforge.android.ui.EngineContent
import app.strategyforge.android.ui.EngineUiState
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.formatters
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Phone-only screens (D-027, D-030, D-032): AI providers, market data and background running. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PhoneScreensUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val fmt = formatters("America/Halifax")

    private fun show(content: @Composable () -> Unit) = rule.setContent { SfTheme { Column(Modifier.verticalScroll(rememberScrollState())) { content() } } }

    private val gemini = ProviderType("GEMINI", "Google Gemini (free tier available)", mapOf("model" to "gemini-2.5-flash", "inputPricePerMillionTokensUsd" to "0", "outputPricePerMillionTokensUsd" to "0"), "https://aistudio.google.com/apikey")

    @Test
    fun `D-030 Gemini is preselected with free-tier prices and a provider needs a key before it can be added`() {
        var created: List<Any>? = null
        show {
            AiProvidersContent(AiProvidersState(types = listOf(gemini), loading = false), { t, n, s, k -> created = listOf(t, n, s, k) }, { _, _ -> }, { _, _ -> }, {})
        }
        rule.onNodeWithText("No AI provider yet", substring = true).assertExists()
        rule.onNodeWithText("Get a key: https://aistudio.google.com/apikey").assertExists()
        rule.onNodeWithTag("add-provider").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithText("API key").performScrollTo().performTextReplacement("AIza-test")
        rule.onNodeWithTag("add-provider").performScrollTo().performClick()
        assertEquals("GEMINI", created!![0])
        @Suppress("UNCHECKED_CAST")
        val settings = created!![2] as Map<String, String>
        assertEquals("gemini-2.5-flash", settings["model"])
        assertEquals("0", settings["inputPricePerMillionTokensUsd"])
        assertEquals("AIza-test", created!![3])
    }

    @Test
    fun `D-030 a stored key shows only its fingerprint and can be disabled`() {
        val toggles = mutableListOf<Boolean>()
        val p = Provider("p1", "AI", "GEMINI", "Gemini", active = true, settings = mapOf("model" to "gemini-2.5-flash"), credentialConfigured = true, credentialFingerprint = "0123456789ab")
        show { AiProvidersContent(AiProvidersState(types = listOf(gemini), providers = listOf(p), loading = false), { _, _, _, _ -> }, { _, _ -> }, { _, a -> toggles += a }, {}) }
        rule.onNodeWithText("stored (…89ab)").assertExists()
        rule.onNodeWithTag("provider-active").performClick()
        assertEquals(listOf(false), toggles)
    }

    @Test
    fun `D-039 a failed test is shown plainly and the model can be changed and re-tested`() {
        val saved = mutableListOf<String>()
        val p =
            Provider(
                "p1",
                "AI",
                "GEMINI",
                "Gemini",
                active = true,
                lastTestStatus = "FAILED",
                lastTestDetail = "BAD_REQUEST: The provider does not offer this model to your key (HTTP 404)",
                settings = mapOf("model" to "gemini-2.5-flash"),
                credentialConfigured = true,
                credentialFingerprint = "0123456789ab",
            )
        show {
            AiProvidersContent(AiProvidersState(types = listOf(gemini), providers = listOf(p), loading = false), { _, _, _, _ -> }, { _, _ -> }, { _, _ -> }, {}) { _, m -> saved += m }
        }
        rule.onNodeWithText("does not offer this model", substring = true).assertExists()
        rule.onNodeWithTag("save-model").assertDoesNotExist()
        rule.onNodeWithTag("provider-model").performScrollTo().performTextReplacement("gemini-flash-latest")
        rule.onNodeWithTag("save-model").performScrollTo().performClick()
        assertEquals(listOf("gemini-flash-latest"), saved)
    }

    @Test
    fun `D-027 demo mode shows speed controls and the battery warning offers the exemption`() {
        var battery = 0
        val speeds = mutableListOf<Int>()
        show {
            EngineContent(
                EngineUiState(runtime = RuntimeState("DEMO", demoStepMinutes = 1, marketTime = "2026-06-22T14:00:00Z"), batteryUnrestricted = false),
                fmt,
                onMode = {},
                onDemoSpeed = { speeds += it },
                onRunInBackground = {},
                onKeepAwake = {},
                onBattery = { battery++ },
            )
        }
        rule.onNodeWithText("5 min").performClick()
        assertEquals(listOf(5), speeds)
        rule.onNodeWithTag("battery").performScrollTo().performClick()
        assertEquals(1, battery)
    }

    @Test
    fun `D-032 live mode explains stock data and saves a Twelve Data key`() {
        val keys = mutableListOf<String?>()
        show {
            EngineContent(
                EngineUiState(runtime = RuntimeState("LIVE"), stocks = StockData(configured = false), batteryUnrestricted = true),
                fmt,
                onMode = {},
                onDemoSpeed = {},
                onRunInBackground = {},
                onKeepAwake = {},
                onBattery = {},
                onStockKey = { keys += it },
            )
        }
        rule.onNodeWithText("Coinbase", substring = true).assertExists()
        rule.onNodeWithText("free Twelve Data key", substring = true).assertExists()
        rule.onNodeWithText("Twelve Data API key").performScrollTo().performTextReplacement("td-key-1234")
        rule.onNodeWithTag("save-stock-key").performScrollTo().performClick()
        assertEquals(listOf<String?>("td-key-1234"), keys)
    }

    @Test
    fun `D-044 the futures data source can be tested and its result is shown`() {
        var tests = 0
        show {
            EngineContent(
                EngineUiState(
                    runtime = RuntimeState("LIVE"),
                    futures =
                        app.strategyforge.android.core.model
                            .FuturesTest("KRAKEN_FUTURES", "OK", "BTC perpetual: open interest 1950.6 BTC, funding 0.00100% per hour"),
                    batteryUnrestricted = true,
                ),
                fmt,
                onMode = {},
                onDemoSpeed = {},
                onRunInBackground = {},
                onKeepAwake = {},
                onBattery = {},
                onTestFutures = { tests++ },
            )
        }
        rule.onNodeWithText("Crypto futures data (Kraken Futures)").performScrollTo().assertExists()
        rule.onNodeWithTag("futures-test-detail").performScrollTo().assertExists()
        rule.onNodeWithTag("test-futures").performScrollTo().performClick()
        assertEquals(1, tests)
    }
}
