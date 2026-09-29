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
import app.strategyforge.android.core.model.Slot
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
    val slots by vm.slots.collectAsStateWithLifecycle()
    var showImport by rememberSaveable { mutableStateOf(false) }
    var json by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(imported) { imported?.let { nav.navigate("strategy/$it") } }
    LaunchedEffect(Unit) { vm.loadSlots() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SlotsSection(slots, onOpen = { nav.navigate("strategy/$it") }, onStop = vm::stop)
        SectionTitle("Strategy library")
        Row {
            Button(onClick = { showImport = !showImport }) { Text(if (showImport) "Close import" else "Create / import") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { nav.navigate("research") }) { Text("AI research") }
        }
        OutlinedButton(onClick = { nav.navigate("scorecards") }, modifier = Modifier.testTag("compare")) { Text("Compare results / build a better strategy") }
        if (showImport) {
            Text("Paste a strategy file (JSON, schema 1.0). It is validated on this phone; unknown content requires manual review and executable content is rejected.")
            Field("Strategy JSON", json, { json = it }, singleLine = false, modifier = Modifier.testTag("strategy-json"))
            Button(onClick = { vm.import(json) }, enabled = json.isNotBlank()) { Text("Validate and import") }
        }
        ActionFeedback(action)
        ResourceContent(state, fmt, vm::refresh, empty = { it.isEmpty() }, emptyText = "No strategies yet. Import one or start AI research.", art = Art.STRATEGIES) { list ->
            list.forEach { s -> StrategyRow(s, fmt) { nav.navigate("strategy/${s.id}") } }
        }
    }
}

/** The two slots (D-035): the crypto strategy and the stock strategy running now. */
@Composable
fun SlotsSection(
    slots: List<Slot>,
    onOpen: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    var confirmStop by rememberSaveable { mutableStateOf<String?>(null) }
    Column { SlotCards(slots, onOpen) { confirmStop = it } }
    confirmStop?.let { id ->
        ConfirmDialog(
            "Stop this strategy?",
            "It stops creating recommendations and trades. Its open positions stay open until you close them or activate a strategy that takes them over.",
            "Stop",
            onDismiss = { confirmStop = null },
        ) {
            confirmStop = null
            onStop(id)
        }
    }
}

@Composable
private fun SlotCards(
    slots: List<Slot>,
    onOpen: (String) -> Unit,
    onStop: (String) -> Unit,
) {
    SectionTitle("Running now")
    Text("One crypto strategy and one stock strategy can run at a time. Activating another one replaces it.", style = MaterialTheme.typography.bodySmall)
    listOf("CRYPTO" to "Crypto", "US_EQUITY" to "Stocks").forEach { (ac, label) ->
        val slot = slots.firstOrNull { it.assetClass == ac }
        val s = slot?.strategy
        SfCard(Modifier.testTag("slot-$ac")) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (s == null) {
                Text("No $label strategy running. Open a ${label.lowercase()} strategy below and activate it.", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    StatusChip(s.status)
                }
                Text(
                    if (slot.activation?.mode == "AUTONOMOUS") "Autonomous: trades are placed automatically (simulated)" else "Notifications: you approve each trade",
                    style = MaterialTheme.typography.bodySmall,
                )
                val open = slot.holdings
                Text(if (open.isEmpty()) "No open positions" else "Open: " + open.joinToString { "${it.quantity} ${it.symbol}" }, style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { onOpen(s.id) }) { Text("Open") }
                    TextButton(onClick = { onStop(s.id) }, modifier = Modifier.testTag("stop-$ac")) { Text("Stop") }
                }
            }
        }
    }
}

/** Asked every time a strategy replaces another (D-035); keeping the positions is preselected. */
@Composable
fun SlotConflictDialog(
    conflict: SlotConflict,
    onDecide: (keep: Boolean?) -> Unit,
) {
    var keep by rememberSaveable { mutableStateOf(true) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { onDecide(null) },
        title = { Text("Replace ${conflict.currentName}?") },
        text = {
            Column {
                Text("${conflict.currentName} is running in this slot. It will be stopped.")
                if (conflict.openPositions.isEmpty()) {
                    Text("It has no open positions.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Text("Its open positions: ${conflict.openPositions.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { keep = true }) {
                        androidx.compose.material3.RadioButton(selected = keep, onClick = { keep = true }, modifier = Modifier.testTag("keep"))
                        Text("Keep them: the new strategy manages them (positions in symbols it does not trade stay open for you to close)")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { keep = false }) {
                        androidx.compose.material3.RadioButton(selected = !keep, onClick = { keep = false }, modifier = Modifier.testTag("close"))
                        Text("Close them now at market (simulated)")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDecide(keep) }, modifier = Modifier.testTag("replace")) { Text("Replace") } },
        dismissButton = { TextButton(onClick = { onDecide(null) }) { Text("Cancel") } },
    )
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
    onChart: (symbol: String, strategyId: String, timeframe: String) -> Unit = { _, _, _ -> },
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val backtests by vm.backtests.collectAsStateWithLifecycle()
    val price by vm.price.collectAsStateWithLifecycle()
    val priceSymbol by vm.priceSymbol.collectAsStateWithLifecycle()
    val priceTf by vm.priceTimeframe.collectAsStateWithLifecycle()
    val priceError by vm.priceError.collectAsStateWithLifecycle()
    val disclosure by vm.disclosure.collectAsStateWithLifecycle()
    val portfolios by vm.portfolios.collectAsStateWithLifecycle()
    val conflict by vm.slotConflict.collectAsStateWithLifecycle()
    val replaced by vm.replaced.collectAsStateWithLifecycle()
    val scorecard by vm.scorecard.collectAsStateWithLifecycle()
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
            description(d)?.let {
                SectionTitle("About this strategy")
                Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("description"))
            }
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
            val syms = symbols(d)
            if (syms.isNotEmpty()) {
                LaunchedEffect(d.strategy.id) { vm.initPrice(syms.first(), timeframe(d) ?: "1h") }
                SectionTitle("Price and trades")
                if (syms.size > 1) ChoiceRow(syms.take(12).map { it to it }, priceSymbol ?: syms.first(), { vm.loadPrice(it, priceTf) }, "sym")
                PriceChartCard(price, priceTf, { vm.loadPrice(priceSymbol ?: syms.first(), it) }, fmt, priceError, title = priceSymbol ?: syms.first())
                TextButton(onClick = { onChart(priceSymbol ?: syms.first(), d.strategy.id, priceTf) }) { Text("Open full chart") }
            }
            scorecard?.let {
                SectionTitle("Results so far")
                ScorecardCard(null, it, fmt)
            }
            SectionTitle("Backtests")
            if (backtests.isEmpty()) Text("No backtests yet. A clean backtest is required before paper trading.")
            Button(onClick = { vm.quickBacktest(timeframe(d)) }, modifier = Modifier.fillMaxWidth().testTag("quick-backtest")) { Text("Backtest on recent history") }
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
            OutlinedButton(onClick = { vm.backtest(from, to, capital) }) { Text("Run backtest for these dates") }

            SectionTitle("Activation")
            replaced?.let { r ->
                val a = r.activation
                Banner(
                    "Replaced ${r.replacedName}. " +
                        when {
                            a.closingOrders.isNotEmpty() -> "Its positions are being closed at market."
                            a.handedOver.isNotEmpty() -> "This strategy now manages ${a.handedOver.joinToString { it.symbol }}."
                            else -> "It had no open positions this strategy takes over."
                        } +
                        (if (a.leftOpen.isNotEmpty()) " Still open and unmanaged: ${a.leftOpen.joinToString { "${it.quantity} ${it.symbol}" }}; close them from the Portfolio tab." else ""),
                )
            }
            if (s.status.startsWith("ACTIVE")) {
                Banner(if (s.status == "ACTIVE_AUTONOMOUS") "Autonomous paper trading is active." else "Notifications mode is active: you approve each trade.", if (s.status == "ACTIVE_AUTONOMOUS") BannerKind.WARNING else BannerKind.INFO)
                Button(onClick = vm::deactivate) { Text("Stop strategy") }
                Text("To change mode or allocation, choose them below and activate again.", style = MaterialTheme.typography.bodySmall)
            }
            run {
                Text("Notifications mode is the default: every trade waits for your approval. Activating replaces the ${if (s.assetClass == "CRYPTO") "crypto" else "stock"} strategy that is running now.")
                portfolios.forEach { p ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(selected = portfolioId == p.id, onClick = { portfolioId = p.id }, label = { Text(p.name) })
                    }
                }
                Field("Allocation % of portfolio", allocation, { allocation = it }, number = true)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = autonomous, onCheckedChange = { autonomous = it }, modifier = Modifier.testTag("autonomous-switch"))
                    Spacer(Modifier.width(8.dp))
                    Text("Autonomous: place simulated trades automatically")
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
                ) { Text(if (autonomous) "Activate in autonomous mode" else "Activate with notifications") }
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
    conflict?.let { SlotConflictDialog(it, vm::resolveSlot) }
}

/** The plain-English summary the compiler writes into metadata.description, if any. */
private fun description(d: app.strategyforge.android.core.model.StrategyDetail): String? =
    ((d.currentVersion?.content as? kotlinx.serialization.json.JsonObject)?.get("metadata") as? kotlinx.serialization.json.JsonObject)
        ?.get("description")
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        ?.takeIf { it.isNotBlank() }

private fun timeframe(d: app.strategyforge.android.core.model.StrategyDetail): String? =
    ((d.currentVersion?.content as? kotlinx.serialization.json.JsonObject)?.get("metadata") as? kotlinx.serialization.json.JsonObject)
        ?.get("timeframe")
        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }

private fun symbols(d: app.strategyforge.android.core.model.StrategyDetail): List<String> =
    (((d.currentVersion?.content as? kotlinx.serialization.json.JsonObject)?.get("universe") as? kotlinx.serialization.json.JsonObject)?.get("symbols") as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        .orEmpty()
