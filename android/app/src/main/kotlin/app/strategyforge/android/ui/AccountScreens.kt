package app.strategyforge.android.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.AiBudget
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.core.state.ExportDataset
import app.strategyforge.android.core.state.ExportFormat

/** Re-authentication prompt shared by the account screens; the refused action is retried afterwards. */
@Composable
private fun ReauthHost(
    action: ActionState,
    session: SessionViewModel,
    vm: AccountViewModel,
) {
    var show by rememberSaveable { mutableStateOf(false) }
    ActionFeedback(action, onReauth = { show = true })
    if (show) {
        ReauthDialog({ show = false }) { pw, totp ->
            show = false
            session.reauthenticate(pw, totp) { vm.retryAfterReauth() }
        }
    }
}

// ------------------------------------------------------------------ security (FR-002)

@Composable
fun SecurityScreen(
    vm: SecurityViewModel,
    fmt: Formatters,
    session: SessionViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SecurityContent(
            state = state,
            fmt = fmt,
            actions =
                SecurityActions(
                    startTotp = vm::startTotpSetup,
                    openAuthenticator = { uri ->
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
                        } catch (e: ActivityNotFoundException) {
                            // No authenticator app registered for otpauth:// links; the key is shown for manual entry.
                        }
                    },
                    confirmTotp = vm::confirmTotp,
                    cancelTotp = vm::cancelTotpSetup,
                    disableTotp = vm::disableTotp,
                    regenerateCodes = vm::regenerateRecoveryCodes,
                    dismissCodes = vm::dismissRecoveryCodes,
                    changePassword = vm::changePassword,
                    revokeSession = vm::revokeSession,
                    revokeOthers = vm::revokeOtherSessions,
                    revokeDevice = vm::revokeDevice,
                    retry = vm::load,
                ),
        )
        ReauthHost(action, session, vm)
    }
}

data class SecurityActions(
    val startTotp: () -> Unit = {},
    val openAuthenticator: (String) -> Unit = {},
    val confirmTotp: (String) -> Unit = {},
    val cancelTotp: () -> Unit = {},
    val disableTotp: (String) -> Unit = {},
    val regenerateCodes: () -> Unit = {},
    val dismissCodes: () -> Unit = {},
    val changePassword: (String, String, String) -> Unit = { _, _, _ -> },
    val revokeSession: (String) -> Unit = {},
    val revokeOthers: () -> Unit = {},
    val revokeDevice: (String) -> Unit = {},
    val retry: () -> Unit = {},
)

@Composable
fun SecurityContent(
    state: SecurityState,
    fmt: Formatters,
    actions: SecurityActions,
) {
    var confirm by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    SectionTitle("Security")
    when {
        state.loading -> Loading()
        state.loadError != null -> {
            Banner(state.loadError, BannerKind.ERROR)
            TextButton(onClick = actions.retry) { Text("Retry") }
        }
    }
    val me = state.me ?: return

    // Two-factor authentication
    SectionTitle("Two-factor authentication")
    val setup = state.totpSetup
    when {
        setup != null -> {
            Text("1. Add this key to your authenticator app (or open it directly). 2. Enter the 6-digit code it shows.")
            SfCard {
                Text("Setup key", style = MaterialTheme.typography.labelMedium)
                Text(setup.secret.chunked(4).joinToString(" "), fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("totp-secret"))
            }
            OutlinedButton(onClick = { actions.openAuthenticator(setup.otpauthUri) }, modifier = Modifier.fillMaxWidth()) { Text("Open in authenticator app") }
            var code by rememberSaveable { mutableStateOf("") }
            Field("6-digit code", code, { code = it }, number = true)
            Row {
                Button(onClick = { actions.confirmTotp(code) }, enabled = code.isNotBlank()) { Text("Turn on") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = actions.cancelTotp) { Text("Cancel") }
            }
        }
        me.totpEnabled -> {
            Banner("Two-factor authentication is on. Sign-in and sensitive actions ask for a code.")
            var code by rememberSaveable { mutableStateOf("") }
            Field("Current 6-digit code", code, { code = it }, number = true)
            OutlinedButton(onClick = { actions.disableTotp(code) }, enabled = code.isNotBlank()) { Text("Turn off two-factor authentication") }
        }
        else -> {
            Banner("Two-factor authentication is off. Turning it on is strongly recommended.", BannerKind.WARNING)
            Button(onClick = actions.startTotp) { Text("Set up two-factor authentication") }
        }
    }

    // Recovery codes
    SectionTitle("Recovery codes")
    val codes = state.recoveryCodes
    if (codes != null) {
        Banner("Store these single-use codes offline now. They are shown only once.", BannerKind.WARNING)
        SfCard { codes.forEach { Text(it, fontFamily = FontFamily.Monospace) } }
        Button(onClick = actions.dismissCodes) { Text("I have stored these codes") }
    } else {
        LabelValue("Unused recovery codes", me.remainingRecoveryCodes.toString())
        if (me.remainingRecoveryCodes <= LOW_CODES) Banner("Few recovery codes remain. Create a new set.", BannerKind.WARNING)
        OutlinedButton(onClick = { confirm = "Create new recovery codes? All existing codes stop working." to actions.regenerateCodes }) { Text("Create new recovery codes") }
    }

    // Password
    SectionTitle("Password")
    var current by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    Field("Current password", current, { current = it }, password = true)
    Field("New password (12+ characters)", new, { new = it }, password = true)
    Field("Repeat new password", again, { again = it }, password = true)
    Button(onClick = {
        actions.changePassword(current, new, again)
        current = ""
        new = ""
        again = ""
    }, enabled = current.isNotBlank() && new.isNotBlank()) { Text("Change password") }

    // Sessions
    SectionTitle("Signed-in sessions")
    state.sessions.forEach { s ->
        SfCard(Modifier.testTag("session")) {
            Row {
                Text(s.deviceName ?: "Unknown device", style = MaterialTheme.typography.titleSmall)
                if (s.current) {
                    Spacer(Modifier.width(8.dp))
                    StatusChip("THIS DEVICE")
                }
            }
            s.lastSeenAt?.let { LabelValue("Last active", fmt.dateTime(it)) }
            s.expiresAt?.let { LabelValue("Expires", fmt.dateTime(it)) }
            if (!s.current) TextButton(onClick = { confirm = "Sign out ${s.deviceName ?: "this session"}?" to { actions.revokeSession(s.id) } }) { Text("Sign out") }
        }
    }
    if (state.sessions.count { !it.current } > 0) {
        OutlinedButton(onClick = { confirm = "Sign out every other session? Use this if a device is lost." to actions.revokeOthers }, modifier = Modifier.fillMaxWidth()) {
            Text("Sign out all other sessions")
        }
    }

    // Devices
    SectionTitle("Devices")
    if (state.devices.isEmpty()) Text("No devices registered for notifications.")
    state.devices.forEach { d ->
        SfCard(Modifier.testTag("device")) {
            Text(d.name, style = MaterialTheme.typography.titleSmall)
            LabelValue("Push notifications", if (d.pushEnabled && d.pushTokenRegistered) "On" else "Off")
            d.lastSeenAt?.let { LabelValue("Last seen", fmt.dateTime(it)) }
            TextButton(onClick = { confirm = "Remove ${d.name}? It stops receiving notifications." to { actions.revokeDevice(d.id) } }) { Text("Remove device") }
        }
    }

    confirm?.let { (text, run) ->
        ConfirmDialog("Please confirm", text, "Confirm", { confirm = null }) {
            confirm = null
            run()
        }
    }
}

private const val LOW_CODES = 3

// ------------------------------------------------------------------ AI budget (FR-037)

@Composable
fun BudgetScreen(
    vm: BudgetViewModel,
    fmt: Formatters,
    session: SessionViewModel,
) {
    val budget by vm.budget.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("AI budget")
        ResourceContent(budget, fmt, vm::load) { b -> BudgetContent(b, fmt) { m, d, o, i -> vm.save(b, m, d, o, i) } }
        ReauthHost(action, session, vm)
    }
}

@Composable
fun BudgetContent(
    b: AiBudget,
    fmt: Formatters,
    onSave: (String, String, String, String) -> Unit,
) {
    Text("Every AI request reserves its worst-case cost before it is sent; a request that could exceed a limit is refused.")
    SfCard {
        LabelValue("Spent this month", "${fmt.money(b.monthCostUsd)} of ${fmt.money(b.monthlyCostLimitUsd)}")
        LabelValue("Requests today", "${b.todayRequests} of ${b.dailyRequestLimit}")
    }
    var monthly by rememberSaveable(b.version) { mutableStateOf(b.monthlyCostLimitUsd) }
    var daily by rememberSaveable(b.version) { mutableStateOf(b.dailyRequestLimit.toString()) }
    var maxOut by rememberSaveable(b.version) { mutableStateOf(b.maxOutputTokens.toString()) }
    var maxIn by rememberSaveable(b.version) { mutableStateOf(b.maxInputChars.toString()) }
    Field("Monthly limit (USD)", monthly, { monthly = it }, number = true)
    Field("Requests per day", daily, { daily = it }, number = true)
    Field("Maximum output tokens per request", maxOut, { maxOut = it }, number = true)
    Field("Maximum input characters per request", maxIn, { maxIn = it }, number = true)
    Text("Lowering a limit applies immediately. Raising one asks for your password.", style = MaterialTheme.typography.bodySmall)
    Button(onClick = { onSave(monthly, daily, maxOut, maxIn) }) { Text("Save limits") }
}

// ------------------------------------------------------------------ backups (FR-112)

@Composable
fun BackupsScreen(
    vm: BackupsViewModel,
    fmt: Formatters,
    session: SessionViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        BackupsContent(state, fmt, vm::create, vm::verify, vm::load)
        ReauthHost(action, session, vm)
    }
}

@Composable
fun BackupsContent(
    state: BackupsState,
    fmt: Formatters,
    onCreate: () -> Unit,
    onVerify: (String) -> Unit,
    onRetry: () -> Unit,
) {
    SectionTitle("Backups")
    Text("Backups are encrypted with the server's master key and stored on the backend host. Restoring is done on the host (see docs/BACKUP_RESTORE.md), never from the phone.")
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
    when {
        state.loading -> Loading()
        state.loadError != null -> {
            Banner(state.loadError, BannerKind.ERROR)
            TextButton(onClick = onRetry) { Text("Retry") }
        }
        state.items.isEmpty() -> Banner("No backup exists yet.", BannerKind.WARNING)
    }
    state.items.forEach { f ->
        SfCard(Modifier.testTag("backup")) {
            Text(f.name, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            f.modifiedAt?.let { LabelValue("Created", fmt.dateTime(it)) }
            LabelValue("Size", "${(f.sizeBytes + KIB - 1) / KIB} KiB")
            val v = state.verifications[f.name]
            when {
                v == null -> TextButton(onClick = { onVerify(f.name) }) { Text("Verify") }
                v.valid -> Banner("Verified: schema ${v.schemaVersion}, ${v.tables} tables, ${v.rows} rows; every table hash matches.")
                else -> Banner("Verification failed: ${v.error ?: "unknown error"}", BannerKind.ERROR)
            }
        }
    }
}

private const val KIB = 1024L

// ------------------------------------------------------------------ exports (FR-105)

@Composable
fun ExportsScreen(
    vm: ExportsViewModel,
    session: SessionViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val saveCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.CSV.mimeType)) { vm.onDocumentChosen(it) }
    val saveJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ExportFormat.JSON.mimeType)) { vm.onDocumentChosen(it) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        ExportsContent(
            state,
            onSelect = { d, f, p -> vm.select(d, f, p) },
            onExport = {
                vm.prepare()?.let { r -> if (r.format == ExportFormat.CSV) saveCsv.launch(r.fileName) else saveJson.launch(r.fileName) }
            },
        )
        ReauthHost(action, session, vm)
    }
}

@Composable
fun ExportsContent(
    state: ExportsState,
    onSelect: (ExportDataset?, ExportFormat?, String?) -> Unit,
    onExport: () -> Unit,
) {
    SectionTitle("Exports")
    Text("Exports use a stable schema (version 1) and include totals and reconciliation checks against the ledger. CSV cells are protected against spreadsheet formulas.")
    SectionTitle("Data")
    ExportDataset.entries.forEach { d ->
        FilterChip(selected = state.dataset == d, onClick = { onSelect(d, null, null) }, label = { Text(d.label) })
    }
    SectionTitle("Format")
    Row {
        ExportFormat.entries.forEach { f ->
            FilterChip(selected = state.format == f, onClick = { onSelect(null, f, null) }, label = { Text(f.name) })
            Spacer(Modifier.width(8.dp))
        }
    }
    if (state.dataset.perPortfolio) {
        SectionTitle("Portfolio")
        if (state.portfolios.isEmpty()) Text("Create a paper portfolio first.")
        state.portfolios.forEach { p ->
            FilterChip(selected = state.portfolioId == p.id, onClick = { onSelect(null, null, p.id) }, label = { Text(p.name) })
        }
    }
    Button(onClick = onExport, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save export…") }
    when (state.lastReconciled) {
        true -> Banner("Last export reconciled with the ledger.")
        false -> Banner("Last export did not reconcile with the ledger. Check Diagnostics before relying on it.", BannerKind.WARNING)
        null -> Unit
    }
}
