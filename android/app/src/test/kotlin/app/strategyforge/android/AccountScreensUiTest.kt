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
import app.strategyforge.android.core.model.Portfolio
import app.strategyforge.android.core.state.ExportDataset
import app.strategyforge.android.core.state.ExportFormat
import app.strategyforge.android.ui.BackupsContent
import app.strategyforge.android.ui.BackupsState
import app.strategyforge.android.ui.BudgetContent
import app.strategyforge.android.ui.ExportsContent
import app.strategyforge.android.ui.ExportsState
import app.strategyforge.android.ui.SfTheme
import app.strategyforge.android.ui.formatters
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** FR-037, FR-105, FR-112 account screens: required states, confirmations and callbacks. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class AccountScreensUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val fmt = formatters("America/Halifax")

    private fun show(content: @Composable () -> Unit) = rule.setContent { SfTheme { Column(Modifier.verticalScroll(rememberScrollState())) { content() } } }

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
        val restored = mutableListOf<String>()
        show { BackupsContent(state, fmt, {}, { verified += it }, {}, onRestore = { restored += it }) }
        rule.onNodeWithText("Verified: schema 9, 57 tables, 217 rows", substring = true).assertExists()
        rule.onNodeWithText("Verify").performScrollTo().performClick()
        assertEquals(listOf(other), verified)
        rule.onNodeWithText("contain no AI keys", substring = true).assertExists()
        rule.onAllNodesWithText("Restore")[0].performScrollTo().performClick()
        assertEquals(listOf(name), restored)
    }

    @Test
    fun `D-069 the daily backup to Downloads shows where the last copy is and can be turned off or run now`() {
        val toggles = mutableListOf<Boolean>()
        var ran = 0
        val auto =
            app.strategyforge.android.core.model.AutoBackupInfo(
                enabled = true,
                exportAvailable = true,
                lastAt = "2026-10-06T12:00:00Z",
                lastName = "strategyforge-20261006-120000-auto1a2b.sfbk",
                lastLocation = "Download/StrategyForge/strategyforge-20261006-120000-auto1a2b.sfbk",
                nextDueAt = "2026-10-07T12:00:00Z",
            )
        show { BackupsContent(BackupsState(loading = false, auto = auto), fmt, {}, {}, {}, onAuto = { toggles += it }, onRunAuto = { ran++ }) }
        rule.onNodeWithText("Download/StrategyForge/strategyforge-20261006-120000-auto1a2b.sfbk").assertExists()
        rule.onNodeWithText("Daily backup to Downloads").assertExists()
        rule.onNodeWithTag("auto-backup-switch").performClick()
        assertEquals(listOf(false), toggles)
        rule.onNodeWithTag("auto-backup-now").performScrollTo().performClick()
        assertEquals(1, ran)
    }

    @Test
    fun `D-069 a failed daily backup is shown`() {
        val auto =
            app.strategyforge.android.core.model
                .AutoBackupInfo(enabled = true, exportAvailable = true, lastError = "Downloads is full")
        show { BackupsContent(BackupsState(loading = false, auto = auto), fmt, {}, {}, {}) }
        rule.onNodeWithText("The last daily backup failed: Downloads is full", substring = true).assertExists()
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
