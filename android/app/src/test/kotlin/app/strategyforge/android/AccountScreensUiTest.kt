package app.strategyforge.android

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.strategyforge.android.core.model.AiBudget
import app.strategyforge.android.core.model.BackupFile
import app.strategyforge.android.core.model.BackupVerification
import app.strategyforge.android.core.model.Device
import app.strategyforge.android.core.model.Me
import app.strategyforge.android.core.model.Portfolio
import app.strategyforge.android.core.model.SessionInfo
import app.strategyforge.android.core.model.TotpSetup
import app.strategyforge.android.core.state.ExportDataset
import app.strategyforge.android.core.state.ExportFormat
import app.strategyforge.android.ui.BackupsContent
import app.strategyforge.android.ui.BackupsState
import app.strategyforge.android.ui.BudgetContent
import app.strategyforge.android.ui.ExportsContent
import app.strategyforge.android.ui.ExportsState
import app.strategyforge.android.ui.SecurityActions
import app.strategyforge.android.ui.SecurityContent
import app.strategyforge.android.ui.SecurityState
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.formatters
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** FR-002, FR-037, FR-105, FR-112 account screens: required states, confirmations and callbacks. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AccountScreensUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val fmt = formatters("America/Halifax")
    private val me = Me("o", "owner", totpEnabled = false, sessionId = "s1", lastAuthenticatedAt = "2026-06-22T13:00:00Z", recentAuthValidUntil = "2026-06-22T13:05:00Z", remainingRecoveryCodes = 2)

    private fun show(content: @Composable () -> Unit) = rule.setContent { SfTheme { Column(Modifier.verticalScroll(rememberScrollState())) { content() } } }

    @Test
    fun `FR-002 security screen offers two-factor setup, warns about few recovery codes and confirms before signing out a session`() {
        val revoked = mutableListOf<String>()
        val state =
            SecurityState(
                me = me,
                sessions = listOf(SessionInfo("s1", "Pixel", current = true), SessionInfo("s2", "Old tablet", current = false)),
                devices = listOf(Device("d1", "Pixel", "ANDROID", pushEnabled = true, pushTokenRegistered = true)),
                loading = false,
            )
        show { SecurityContent(state, fmt, SecurityActions(revokeSession = { revoked += it })) }
        rule.onNodeWithText("Set up two-factor authentication").assertExists()
        rule.onNodeWithText("Few recovery codes remain. Create a new set.", substring = true).assertExists()
        rule.onAllNodesWithTag("session").fetchSemanticsNodes().let { assertEquals(2, it.size) }
        rule.onNodeWithText("Sign out all other sessions").assertExists()
        // The current session cannot sign itself out from here; the other one asks for confirmation first.
        rule.onAllNodesWithText("Sign out").fetchSemanticsNodes().let { assertEquals(1, it.size) }
        rule.onNodeWithText("Sign out").performScrollTo().performClick()
        assertEquals(emptyList<String>(), revoked)
        rule.onNodeWithText("Sign out Old tablet?").assertExists()
        rule.onNodeWithText("Confirm").performClick()
        assertEquals(listOf("s2"), revoked)
    }

    @Test
    fun `FR-002 TOTP setup shows the grouped key and sends the entered code, recovery codes are shown once`() {
        val codes = mutableListOf<String>()
        val state =
            SecurityState(
                me = me.copy(remainingRecoveryCodes = 10),
                totpSetup = TotpSetup("JBSWY3DPEHPK3PXP", "otpauth://totp/x"),
                recoveryCodes = listOf("aaaa-bbbb", "cccc-dddd"),
                loading = false,
            )
        show { SecurityContent(state, fmt, SecurityActions(confirmTotp = { codes += it })) }
        rule.onNodeWithTag("totp-secret").assertExists()
        rule.onNodeWithText("JBSW Y3DP EHPK 3PXP").assertExists()
        rule.onNodeWithText("Turn on").assertIsNotEnabled()
        rule.onNodeWithText("6-digit code").performTextReplacement("123 456")
        rule.onNodeWithText("Turn on").performClick()
        assertEquals(listOf("123 456"), codes)
        rule.onNodeWithText("aaaa-bbbb").assertExists()
        rule.onNodeWithText("I have stored these codes").assertExists()
    }

    @Test
    fun `FR-037 budget shows usage against limits and saves edited limits`() {
        var saved: List<String>? = null
        show { BudgetContent(AiBudget("20.00", 40, 8000, 60000, monthCostUsd = "1.25", todayRequests = 3), fmt) { m, d, o, i -> saved = listOf(m, d, o, i) } }
        rule.onNodeWithText("$1.25 of $20.00").assertExists()
        rule.onNodeWithText("3 of 40").assertExists()
        rule.onNodeWithText("Monthly limit (USD)").performTextReplacement("10")
        rule.onNodeWithText("Save limits").performScrollTo().performClick()
        assertEquals(listOf("10", "40", "8000", "60000"), saved)
    }

    @Test
    fun `FR-112 backups list shows verification results and an empty state`() {
        val name = "strategyforge-20260928-031700-ab12.sfbk"
        val other = "strategyforge-20260927-031700-cd34.sfbk"
        val verified = mutableListOf<String>()
        val state =
            BackupsState(
                items = listOf(BackupFile(name, 4096, "2026-09-28T03:17:05Z"), BackupFile(other, 2048)),
                verifications = mapOf(name to BackupVerification(name, valid = true, schemaVersion = "9", tables = 57, rows = 217)),
                loading = false,
            )
        show { BackupsContent(state, fmt, {}, { verified += it }, {}) }
        rule.onNodeWithText("Verified: schema 9, 57 tables, 217 rows", substring = true).assertExists()
        rule.onNodeWithText("Verify").performScrollTo().performClick()
        assertEquals(listOf(other), verified)
        rule.onNodeWithText("never from the phone", substring = true).assertExists()
    }

    @Test
    fun `FR-112 empty backup list is flagged`() {
        show { BackupsContent(BackupsState(loading = false), fmt, {}, {}, {}) }
        rule.onNodeWithText("No backup exists yet.", substring = true).assertExists()
    }

    @Test
    fun `FR-105 exports hide the portfolio choice for the audit log and report reconciliation`() {
        val selections = mutableListOf<Triple<ExportDataset?, ExportFormat?, String?>>()
        var exported = 0
        val portfolios = listOf(Portfolio("3f2b8c1e-8a52-4a9e-9f0e-2d9f0c7b1a11", "Main paper", "ACTIVE", startingBalance = "100000"))
        show {
            ExportsContent(ExportsState(portfolios = portfolios, dataset = ExportDataset.AUDIT, lastReconciled = true), { d, f, p -> selections += Triple(d, f, p) }) { exported++ }
        }
        rule.onNodeWithText("Main paper").assertDoesNotExist()
        rule.onNodeWithText("Last export reconciled with the ledger.", substring = true).assertExists()
        rule.onNodeWithText("JSON").performScrollTo().performClick()
        rule.onNodeWithText("Save export…").performScrollTo().performClick()
        assertEquals(listOf(Triple<ExportDataset?, ExportFormat?, String?>(null, ExportFormat.JSON, null)), selections)
        assertEquals(1, exported)
    }
}
