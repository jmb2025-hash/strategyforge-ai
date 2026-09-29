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
    Text("Lowering a limit applies immediately. Raising one asks you to confirm with your screen lock.", style = MaterialTheme.typography.bodySmall)
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
    var saving by remember { mutableStateOf<String?>(null) }
    var restoring by rememberSaveable { mutableStateOf<String?>(null) }
    val saveAs =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
            val name = saving
            saving = null
            if (uri != null && name != null) vm.saveCopy(name, uri)
        }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importFile(uri) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        BackupsContent(
            state,
            fmt,
            vm::create,
            vm::verify,
            vm::load,
            onSaveCopy = { name ->
                saving = name
                saveAs.launch(name)
            },
            onRestore = { restoring = it },
            onImport = { pick.launch(arrayOf("*/*")) },
        )
        ReauthHost(action, session, vm)
    }
    restoring?.let { name ->
        ConfirmDialog(
            "Restore this backup?",
            "Everything on this phone (portfolios, strategies, orders, recommendations and history) is replaced by the backup from $name. A safety backup of the current state is saved first.",
            "Restore",
            onDismiss = { restoring = null },
        ) {
            restoring = null
            vm.restore(name)
        }
    }
}

@Composable
fun BackupsContent(
    state: BackupsState,
    fmt: Formatters,
    onCreate: () -> Unit,
    onVerify: (String) -> Unit,
    onRetry: () -> Unit,
    onSaveCopy: (String) -> Unit = {},
    onRestore: (String) -> Unit = {},
    onImport: () -> Unit = {},
) {
    SectionTitle("Backups")
    Text(
        "Backups are kept in the app's private storage and contain no AI keys. Save a copy somewhere safe (for example Google Drive) before uninstalling or moving to a new phone; " +
            "restore it here with \"Restore from a file\".",
    )
    Button(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Back up now") }
    OutlinedButton(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Restore from a file") }
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
                v.valid -> Banner("Verified: schema ${v.schemaVersion}, ${v.tables} tables, ${v.rows} rows; the checksum matches.")
                else -> Banner("Verification failed: ${v.error ?: "unknown error"}", BannerKind.ERROR)
            }
            Row {
                TextButton(onClick = { onSaveCopy(f.name) }) { Text("Save a copy") }
                TextButton(onClick = { onRestore(f.name) }) { Text("Restore") }
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
