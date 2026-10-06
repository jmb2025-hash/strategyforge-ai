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
import app.strategyforge.android.core.model.LimitValues
import app.strategyforge.android.core.model.PlanLimits
import app.strategyforge.android.core.model.RiskLimitsInfo
import app.strategyforge.android.ui.RiskLimitsContent
import app.strategyforge.android.ui.SfTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** D-063 risk limits: each running plan's own limits, a warning when they would block it, and the editable defaults. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class RiskLimitsUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `plans show their own limits and warnings, and the defaults can be saved`() {
        val info =
            RiskLimitsInfo(
                global = LimitValues("20", "25", "50", "100", "5", "25", 25),
                globalVersion = 3,
                plans =
                    listOf(
                        PlanLimits(
                            "st1",
                            "BTC trend core",
                            "CRYPTO",
                            1,
                            "Crypto slot 1 (2)",
                            "100",
                            "PERCENT_OF_EQUITY 95",
                            LimitValues("100", "100", null, null, "25", "60", 1),
                        ),
                        PlanLimits("st2", "Tight plan", "CRYPTO", 2, warning = "Its entries use 95% of the portfolio but its limits allow 20%, so they will be rejected."),
                    ),
            )
        val saved = mutableListOf<Map<String, String>>()
        rule.setContent { SfTheme { Column(Modifier.verticalScroll(rememberScrollState())) { RiskLimitsContent(info) { saved += it } } } }
        rule.onNodeWithText("Crypto 1 · BTC trend core").assertExists()
        rule.onNodeWithText("Trades in Crypto slot 1 (2) · 100% of it").assertExists()
        rule.onNodeWithText("60%").assertExists()
        rule.onNodeWithText("so they will be rejected", substring = true).assertExists()
        rule.onNodeWithTag("save-global-limits").performScrollTo().performClick()
        assertEquals("20", saved.single()["maxTradePercent"])
        assertEquals("50", saved.single()["maxCryptoPercent"])
    }
}
