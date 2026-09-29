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

@Composable
fun ResearchListScreen(
    vm: ResearchListViewModel,
    fmt: Formatters,
    nav: NavHostController,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val providers by vm.providers.collectAsStateWithLifecycle()
    var providerId by rememberSaveable { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("") }
    var asset by rememberSaveable { mutableStateOf("US_EQUITY") }
    var symbols by rememberSaveable { mutableStateOf("SPY") }
    var timeframe by rememberSaveable { mutableStateOf("1d") }
    var horizon by rememberSaveable { mutableStateOf("weeks") }
    var approach by rememberSaveable { mutableStateOf("trend following") }
    var prompt by rememberSaveable { mutableStateOf("") }
    var retrieval by rememberSaveable { mutableStateOf(false) }
    var maxRequests by rememberSaveable { mutableStateOf("3") }
    var maxCost by rememberSaveable { mutableStateOf("2") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("AI research")
        Banner("AI output is unverified until you review it and can only become a strategy through validation and a backtest.")
        ResourceContent(state, fmt, vm::refresh, empty = { it.isEmpty() }, emptyText = "No research sessions yet.") { list ->
            list.forEach { r ->
                SfCard(Modifier.clickable { nav.navigate("research/${r.id}") }) {
                    Row {
                        Text(r.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        StatusChip(r.reviewStatus)
                    }
                    Text("${r.providerType} ${r.model} · ${r.requestsUsed}/${r.maxRequests} requests · ${fmt.money(r.costUsd)} of ${fmt.money(r.maxCostUsd)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        SectionTitle("New research")
        if (providers.isEmpty()) Text("Add an AI provider first (More → AI providers and keys). Google Gemini has a free tier.")
        providers.forEach { p -> FilterChip(selected = providerId == p.id, onClick = { providerId = p.id }, label = { Text("${p.displayName} (${p.providerType})") }) }
        Field("Title", title, { title = it })
        Row {
            FilterChip(selected = asset == "US_EQUITY", onClick = { asset = "US_EQUITY" }, label = { Text("US equities") })
            Spacer(Modifier.width(8.dp))
            FilterChip(selected = asset == "CRYPTO", onClick = { asset = "CRYPTO" }, label = { Text("Crypto") })
        }
        Field("Symbols (comma separated)", symbols, { symbols = it })
        Field("Timeframe (1m, 5m, 15m, 1h, 4h, 1d)", timeframe, { timeframe = it })
        Field("Horizon", horizon, { horizon = it })
        Field("Approach", approach, { approach = it })
        Field("Research question", prompt, { prompt = it }, singleLine = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = retrieval, onCheckedChange = { retrieval = it })
            Spacer(Modifier.width(8.dp))
            Text("Use cited web search (supported providers only)")
        }
        Field("Max requests", maxRequests, { maxRequests = it }, number = true)
        Field("Max cost (USD)", maxCost, { maxCost = it }, number = true)
        Button(
            onClick = {
                providerId?.let {
                    vm.create(it, title, asset, symbols.split(',').map { s -> s.trim().uppercase() }.filter { s -> s.isNotEmpty() }, timeframe, horizon, approach, prompt, retrieval, maxRequests.toIntOrNull() ?: 1, maxCost)
                }
            },
            enabled = providerId != null && title.isNotBlank() && prompt.isNotBlank(),
        ) { Text("Create session") }
        ActionFeedback(action)
    }
}

@Composable
fun ResearchDetailScreen(
    vm: ResearchDetailViewModel,
    fmt: Formatters,
    nav: NavHostController,
) {
    val detail by vm.detail.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        val d = detail
        if (d == null) {
            Loading()
        } else {
            SectionTitle(d.session.title)
            Banner(d.disclaimer)
            LabelValue("Status", d.session.status)
            LabelValue("Review", d.session.reviewStatus)
            LabelValue("Spend", "${fmt.money(d.session.costUsd)} of ${fmt.money(d.session.maxCostUsd)}")
            Row {
                Button(onClick = vm::run, enabled = d.session.status != "RUNNING") { Text("Run research") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = vm::load) { Text("Refresh") }
            }
            d.runs.forEach { r ->
                SfCard {
                    Row {
                        Text("${r.purpose} · ${fmt.dateTime(r.startedAt)}", modifier = Modifier.weight(1f))
                        StatusChip(r.status)
                    }
                    Text(r.label, style = MaterialTheme.typography.labelMedium)
                    r.failureDetail?.let { Banner("${r.failureCode}: $it", BannerKind.ERROR) }
                    r.responseText?.let { Text(it.take(4000), style = MaterialTheme.typography.bodySmall) }
                    r.sources.forEach { s -> Text("Source: ${s.title ?: s.url} (${s.url})", style = MaterialTheme.typography.bodySmall) }
                    r.estimatedCostUsd?.let { LabelValue("Estimated cost", fmt.money(it)) }
                }
            }
            SectionTitle("Review")
            Row {
                Button(onClick = { vm.review(true) }) { Text("Approve") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { vm.review(false) }) { Text("Reject") }
            }
            SectionTitle("Compile")
            Text("Compiles approved research into the strategy schema. The result is validated like any import.")
            Button(onClick = vm::compile, enabled = d.session.reviewStatus == "REVIEWED") { Text("Compile to strategy") }
            d.compilations.forEach { c ->
                SfCard(Modifier.clickable(enabled = c.strategyId != null) { c.strategyId?.let { nav.navigate("strategy/$it") } }) {
                    Row {
                        Text(fmt.dateTime(c.createdAt), modifier = Modifier.weight(1f))
                        StatusChip(c.status)
                    }
                    c.contentHash?.let { Text("Version hash ${it.take(16)}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        ActionFeedback(action)
    }
}
