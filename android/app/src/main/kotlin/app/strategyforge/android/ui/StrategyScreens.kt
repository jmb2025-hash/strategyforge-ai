package app.strategyforge.android.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.Strategy

@Composable
fun StrategiesScreen(
    vm: StrategiesViewModel,
    fmt: Formatters,
    nav: NavHostController,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val imported by vm.imported.collectAsStateWithLifecycle()
    var showImport by rememberSaveable { mutableStateOf(false) }
    var json by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(imported) { imported?.let { nav.navigate("strategy/$it") } }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Strategy library")
        Row {
            Button(onClick = { showImport = !showImport }) { Text(if (showImport) "Close import" else "Create / import") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { nav.navigate("research") }) { Text("AI research") }
        }
        if (showImport) {
            Text("Paste a strategy file (JSON, schema 1.0). It is validated on this phone; unknown content requires manual review and executable content is rejected.")
            Field("Strategy JSON", json, { json = it }, singleLine = false, modifier = Modifier.testTag("strategy-json"))
            Button(onClick = { vm.import(json) }, enabled = json.isNotBlank()) { Text("Validate and import") }
        }
        ActionFeedback(action)
        ResourceContent(state, fmt, vm::refresh, empty = { it.isEmpty() }, emptyText = "No strategies yet. Import one or start AI research.") { list ->
            list.forEach { s -> StrategyRow(s, fmt) { nav.navigate("strategy/${s.id}") } }
        }
    }
}

@Composable
fun StrategyRow(
    s: Strategy,
    fmt: Formatters,
    onClick: () -> Unit,
) {
    SfCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            StatusChip(s.status)
        }
        Text("${s.assetClass} · v${s.currentVersion ?: "-"} · ${s.contentHash?.take(12) ?: "no version"}", style = MaterialTheme.typography.bodySmall)
        s.updatedAt?.let { Text("Updated ${fmt.dateTime(it)}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun StrategyDetailScreen(
    vm: StrategyDetailViewModel,
    fmt: Formatters,
    session: SessionViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val backtests by vm.backtests.collectAsStateWithLifecycle()
    val disclosure by vm.disclosure.collectAsStateWithLifecycle()
    val portfolios by vm.portfolios.collectAsStateWithLifecycle()
    var from by rememberSaveable { mutableStateOf("2026-01-02T00:00:00Z") }
    var to by rememberSaveable { mutableStateOf("2026-06-19T00:00:00Z") }
    var capital by rememberSaveable { mutableStateOf("100000") }
    var allocation by rememberSaveable { mutableStateOf("25") }
    var autonomous by rememberSaveable { mutableStateOf(false) }
    var accepted by rememberSaveable { mutableStateOf(false) }
    var portfolioId by rememberSaveable { mutableStateOf<String?>(null) }
    var showReauth by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        ResourceContent(state, fmt, vm::refresh) { d ->
            val s = d.strategy
            SectionTitle(s.name)
            StatusChip(s.status)
            s.statusReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            LabelValue("Asset class", s.assetClass)
            LabelValue("Version", "${s.currentVersion ?: "-"} (${s.contentHash?.take(16) ?: "-"})")
            d.currentVersion?.let { LabelValue("Source", it.source + (it.sourceRef?.let { r -> " · $r" } ?: "")) }
            if (s.status == "MANUAL_REVIEW_REQUIRED") Banner("Manual Review Required: the file contains content the validator does not recognise. It cannot be backtested or activated.", BannerKind.WARNING)
            d.validation?.let { v ->
                SectionTitle("Validation: ${v.status.lowercase().replace('_', ' ')}")
                v.issues.forEach { i -> Text("• [${i.severity ?: i.category ?: ""}] ${i.path ?: ""} ${i.message}", style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = vm::revalidate) { Text("Re-run validation") }
            }
            d.explanation?.let {
                SectionTitle("How it works")
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            SectionTitle("Backtests")
            if (backtests.isEmpty()) Text("No backtests yet. A clean backtest is required before paper trading.")
            backtests.forEach { b ->
                SfCard {
                    Row {
                        Text(fmt.dateTime(b.createdAt), modifier = Modifier.weight(1f))
                        StatusChip(b.resultStatus ?: b.status)
                    }
                    b.metrics?.let { m ->
                        listOf("netReturnPercent" to "Net return %", "maxDrawdownPercent" to "Max drawdown %", "trades" to "Trades", "winRatePercent" to "Win rate %").forEach { (k, label) ->
                            m[k]?.let { LabelValue(label, it.toString().trim('"')) }
                        }
                    }
                    b.error?.let { Banner(it, BannerKind.ERROR) }
                }
            }
            Field("From (UTC, ISO-8601)", from, { from = it })
            Field("To (UTC, ISO-8601)", to, { to = it })
            Field("Starting capital (USD)", capital, { capital = it }, number = true)
            OutlinedButton(onClick = { vm.backtest(from, to, capital) }) { Text("Run backtest") }

            SectionTitle("Activation")
            if (s.status.startsWith("ACTIVE")) {
                Banner(if (s.status == "ACTIVE_AUTONOMOUS") "Autonomous paper trading is active." else "Recommendation Mode is active.", if (s.status == "ACTIVE_AUTONOMOUS") BannerKind.WARNING else BannerKind.INFO)
                Button(onClick = vm::deactivate) { Text("Pause strategy") }
            } else {
                Text("Recommendation Mode is the default: every action waits for your decision.")
                portfolios.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = portfolioId == p.id, onClick = { portfolioId = p.id }, label = { Text(p.name) })
                    }
                }
                Field("Allocation % of portfolio", allocation, { allocation = it }, number = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = autonomous, onCheckedChange = { autonomous = it }, modifier = Modifier.testTag("autonomous-switch"))
                    Spacer(Modifier.width(8.dp))
                    Text("Autonomous paper trading")
                }
                if (autonomous) {
                    disclosure?.let { dis ->
                        Banner(dis.text, BannerKind.WARNING)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = accepted, onCheckedChange = { accepted = it }, modifier = Modifier.testTag("disclosure-accept"))
                            Text("I have read and accept the disclosure (version ${dis.version})")
                        }
                    }
                }
                Button(
                    onClick = { portfolioId?.let { vm.activate(it, allocation, autonomous, accepted) } },
                    enabled = portfolioId != null && (!autonomous || accepted),
                    modifier = Modifier.testTag("activate"),
                ) { Text(if (autonomous) "Enable autonomous mode" else "Enable Recommendation Mode") }
            }
        }
        ActionFeedback(action, onReauth = { showReauth = true })
        TextButton(onClick = {
            vm.refresh()
            vm.loadExtras()
        }) { Text("Refresh") }
    }
    if (showReauth) {
        ReauthDialog({ showReauth = false }) { pw, totp ->
            showReauth = false
            session.reauthenticate(pw, totp) { vm.clearAction() }
        }
    }
}
