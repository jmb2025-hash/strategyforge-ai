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
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.api.SfJson
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.Portfolio
import app.strategyforge.android.core.model.PortfolioSlot
import app.strategyforge.android.core.model.PortfolioSummary
import app.strategyforge.android.core.model.Slot
import app.strategyforge.android.core.model.TsxRun
import app.strategyforge.android.ui.PortfolioGroup
import app.strategyforge.android.ui.PortfolioOverview
import app.strategyforge.android.ui.RunningPlan
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.SlotPortfolioInfo
import app.strategyforge.android.ui.ownsPortfolio
import app.strategyforge.android.ui.portfolioEntries
import app.strategyforge.android.ui.titleOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneId

/** D-079 the Portfolio tab opens on an overview of every slot's own portfolio, the TSX plans and the owner's own portfolios. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class PortfolioOverviewUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val fmt = Formatters(ZoneId.of("UTC"))

    private val crypto1 = Portfolio("p1", "Crypto slot 1", "ACTIVE", startingBalance = "10000", slot = PortfolioSlot("CRYPTO", 1))
    private val stock4 = Portfolio("p4", "Stock slot 4", "ACTIVE", startingBalance = "10000", slot = PortfolioSlot("US_EQUITY", 4))
    private val mine = Portfolio("m1", "Crypto slot 1 (2)", "ACTIVE", startingBalance = "5000")

    private fun slot(
        n: Int,
        name: String,
        portfolio: String,
    ) = SfJson.decodeFromString(
        Slot.serializer(),
        """
        {"assetClass":"CRYPTO","number":$n,"strategy":{"id":"s$n","name":"$name","assetClass":"CRYPTO","status":"ACTIVE_AUTONOMOUS"},
         "activation":{"id":"a$n","strategyId":"s$n","portfolioId":"$portfolio","mode":"AUTONOMOUS","allocationPercent":"100","status":"ACTIVE","createdAt":"2026-10-01T12:00:00Z"},
         "portfolioId":"$portfolio"}
        """.trimIndent(),
    )

    private val tsx =
        SfJson.decodeFromString(
            TsxRun.serializer(),
            """{"id":"r1","planId":"x","planName":"Dividend Growth & Momentum 24","slot":1,"mode":"NOTIFY","drip":true,"startingCash":10000.0,"cash":0.0,"value":10500.0,"status":"ACTIVE"}""",
        )

    private fun summary(
        p: Portfolio,
        equity: String,
        pct: String,
    ) = PortfolioSummary(p, cash = "0", buyingPower = "0", equity = equity, totalReturnPercent = pct, asOf = "2026-10-10T00:00:00Z")

    private val plans =
        listOf(
            RunningPlan.SlotPlan(slot(1, "BTC daily trend dip and rip", "p1")),
            // From before 1.24.0: slot 2's plan still trades in the owner's own portfolio.
            RunningPlan.SlotPlan(slot(2, "BTC trend core", "m1")),
            RunningPlan.TsxPlan(tsx),
        )

    @Test
    fun `entries group each slot's portfolio with its plan, TSX plans and the owner's own portfolios`() {
        val e = portfolioEntries(listOf(crypto1, stock4, mine), plans, mapOf("p1" to summary(crypto1, "10250", "2.5")), fmt)
        assertEquals(listOf(PortfolioGroup.CRYPTO, PortfolioGroup.CRYPTO, PortfolioGroup.STOCK, PortfolioGroup.TSX, PortfolioGroup.MINE), e.map { it.group })
        assertEquals("Slot 1 · BTC daily trend dip and rip", e[0].title)
        assertEquals("Autonomous · Crypto slot 1", e[0].detail)
        assertEquals("$10,250.00", e[0].value)
        assertEquals("slot:CRYPTO:1", e[0].planKey)
        assertEquals("Autonomous · shares Crypto slot 1 (2)", e[1].detail)
        assertEquals("No plan running · Stock slot 4", e[2].detail)
        assertEquals(null, e[2].planKey)
        assertEquals("C$10,500", e[3].value)
        assertEquals(0, e[3].changePercent!!.compareTo(java.math.BigDecimal("5")))
        assertTrue(e[4].detail.startsWith("Your own trades · also used by Crypto 2"))
        assertTrue(ownsPortfolio(plans[0] as RunningPlan.SlotPlan, crypto1))
        assertFalse(ownsPortfolio(plans[1] as RunningPlan.SlotPlan, mine))
        assertEquals("Crypto slot 1 · Crypto slot 1", titleOf(crypto1))
        assertEquals("Crypto slot 1 (2)", titleOf(mine))
    }

    @Test
    fun `the overview opens a card and creates the owner's own portfolio`() {
        val opened = mutableListOf<String>()
        var created = ""
        val e = portfolioEntries(listOf(crypto1, mine), plans.take(1), emptyMap(), fmt)
        rule.setContent {
            SfTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    PortfolioOverview(e, { opened += it.key }, { n, b -> created = "$n $b" })
                }
            }
        }
        rule.onNodeWithText("Crypto slots").assertExists()
        rule.onNodeWithText("My portfolios").assertExists()
        rule.onNodeWithText("Stock slots").assertDoesNotExist()
        rule.onNodeWithTag("entry-p:p1").performClick()
        rule.onNodeWithTag("entry-p:m1").performScrollTo().performClick()
        assertEquals(listOf("p:p1", "p:m1"), opened)
        rule.onNodeWithTag("new-portfolio-name").performScrollTo().performTextInput("Swing trades")
        rule.onNodeWithTag("create-portfolio").performScrollTo().performClick()
        assertEquals("Swing trades 100000", created)
    }

    @Test
    fun `the activation form names the slot's portfolio, or asks starting cash for a new one`() {
        rule.setContent {
            SfTheme {
                Column {
                    SlotPortfolioInfo(1, "Crypto slot 1", "10000") {}
                    SlotPortfolioInfo(3, null, "10000") {}
                }
            }
        }
        rule.onNodeWithText("Trades in slot 1's own portfolio, Crypto slot 1.", substring = true).assertExists()
        rule.onNodeWithText("Slot 3 gets its own portfolio when this plan starts.", substring = true).assertExists()
        rule.onNodeWithTag("slot-starting-cash").assertExists()
    }
}
