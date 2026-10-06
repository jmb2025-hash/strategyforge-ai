package app.strategyforge.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.data.OrderDraft
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.PortfolioSummary
import app.strategyforge.android.core.notify.DeepLinks
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.core.state.OrderForm
import app.strategyforge.android.core.state.RecommendationPresenter
import app.strategyforge.android.core.state.RecommendationState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PortfolioScreen(
    vm: PortfolioViewModel,
    fmt: Formatters,
    onChart: (symbol: String, portfolioId: String) -> Unit = { _, _ -> },
    session: SessionViewModel? = null,
    /** Opens a symbol's information screen (D-065). */
    onInfo: (symbol: String, portfolioId: String?) -> Unit = { _, _ -> },
    /** A side and symbol chosen on the information screen, to fill the order form with. */
    prefill: Pair<String, String>? = null,
    onPrefillUsed: () -> Unit = {},
) {
    var confirmShorting by remember { mutableStateOf(false) }
    val state by vm.state.collectAsStateWithLifecycle()
    val equity by vm.equity.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val portfolios by vm.portfolios.collectAsStateWithLifecycle()
    val selected by vm.selected.collectAsStateWithLifecycle()
    val orders by vm.orders.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val plans by vm.plans.collectAsStateWithLifecycle()
    val planKey by vm.plan.collectAsStateWithLifecycle()
    val scorecard by vm.scorecard.collectAsStateWithLifecycle()
    val tsxRun by vm.tsxRun.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadPlans() }
    AutoRefresh(LIVE_REFRESH_MS) { vm.refreshLive() }
    AutoRefresh(PLANS_REFRESH_MS) { vm.loadPlans() }
    val plan = plans.firstOrNull { it.key == planKey }
    val slotPlan = plan as? RunningPlan.SlotPlan
    var name by rememberSaveable { mutableStateOf("") }
    var balance by rememberSaveable { mutableStateOf("100000") }
    var symbol by rememberSaveable { mutableStateOf("") }
    var qty by rememberSaveable { mutableStateOf("") }
    var side by rememberSaveable { mutableStateOf("BUY") }
    var limit by rememberSaveable { mutableStateOf("") }
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    // Problems found before sending, per field; engine problems arrive in [action] with their field (D-065).
    var formErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Counts submits, so pressing Submit again scrolls to the same problem again.
    var attempts by remember { mutableStateOf(0) }
    val targets = rememberFieldTargets(OrderForm.SYMBOL, OrderForm.QUANTITY, OrderForm.LIMIT, "submit")
    val failedField = (action as? ActionState.Failed)?.field?.takeIf { it in targets }
    val fieldErrors = if (failedField != null) formErrors + (failedField to (action as ActionState.Failed).message) else formErrors
    LaunchedEffect(attempts, action) {
        val f = OrderForm.first(formErrors) ?: failedField
        f?.let { targets[it]?.show() }
    }
    LaunchedEffect(prefill) {
        prefill?.let { (s, sym) ->
            side = s
            symbol = sym
            vm.clearSuggestions()
            formErrors = emptyMap()
            targets.getValue(OrderForm.QUANTITY).show()
            onPrefillUsed()
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Paper portfolios")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            portfolios.forEach { p ->
                FilterChip(selected = plan == null && selected == p.id, onClick = { vm.select(p.id) }, label = { Text(p.name + if (p.status != "ACTIVE") " (${p.status.lowercase()})" else "") })
            }
        }
        if (plans.isNotEmpty()) {
            SectionTitle("Running plans")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                plans.forEach { p -> FilterChip(selected = planKey == p.key, onClick = { vm.selectPlan(p.key) }, label = { Text(p.label) }, modifier = Modifier.testTag("plan-${p.key}")) }
            }
        }
        if (plan is RunningPlan.TsxPlan) {
            val r = tsxRun
            if (r == null) Loading() else TsxRunDetail(r, action, { vm.tsxAction("approve") }, { vm.tsxAction("decline") }, { vm.tsxAction("stop") })
        } else if (selected == null) {
            EmptyState("Create your first simulated portfolio below.", Art.PORTFOLIO)
        } else {
            ResourceContent(state, fmt, vm::reload) { s ->
                if (slotPlan != null) SlotPlanCard(slotPlan, scorecard, s, fmt)
                EquityCard(equity, range, vm::setRange, fmt, s.equity, s.portfolio.name)
                AllocationCard(s, fmt)
                val shorting = portfolios.firstOrNull { it.id == s.portfolio.id }?.shortingEnabled ?: s.portfolio.shortingEnabled
                ShortingSwitch(shorting) { on -> if (on) confirmShorting = true else vm.setShorting(s.portfolio.id, false) }
                SfCard {
                    LabelValue("Equity", fmt.money(s.equity))
                    LabelValue("Cash", fmt.money(s.cash))
                    LabelValue("Reserved for orders", fmt.money(s.reservedCash))
                    LabelValue("Buying power", fmt.money(s.buyingPower))
                    PnlValue("Realized", s.realizedPnl, fmt)
                    PnlValue("Unrealized", s.unrealizedPnl, fmt)
                    PnlValue("Total return", s.totalReturn, fmt)
                    LabelValue("Fees", fmt.money(s.fees))
                    LabelValue("As of", fmt.dateTime(s.asOf))
                    if (s.portfolio.reconciliationStatus != "OK") Banner("Reconciliation ${s.portfolio.reconciliationStatus}: orders are blocked until it passes.", BannerKind.ERROR)
                }
                val planSymbols = slotPlan?.symbols
                val positions = if (planSymbols == null) s.positions else s.positions.filter { it.symbol in planSymbols }
                SectionTitle(if (slotPlan != null) "This plan's positions" else "Positions")
                if (positions.isEmpty()) Text("No open positions.")
                positions.forEach { p ->
                    SfCard(Modifier.clickable { onChart(p.symbol, s.portfolio.id) }.testTag("position-${p.symbol}")) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${p.symbol} · ${p.side}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                            Text("Chart ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(8.dp))
                            if (p.priceStatus != "VERIFIED") StatusChip(if (p.priceStatus == "STALE") "Stale data" else p.priceStatus)
                        }
                        LabelValue("Quantity", fmt.quantity(p.quantity))
                        LabelValue("Average cost", fmt.money(p.averageCost))
                        LabelValue("Market value", fmt.money(p.marketValue))
                        PnlValue("Unrealized", p.unrealizedPnl, fmt)
                        p.priceTimestamp?.let { LabelValue("Price time", fmt.dateTime(it)) }
                        if (p.manualReviewRequired) Banner("Manual Review Required: a corporate action could not be verified.", BannerKind.WARNING)
                    }
                }
            }
            SectionTitle(if (slotPlan != null) "This plan's orders" else "Orders")
            val o = orders
            if (o is Resource.Data) {
                val items = if (slotPlan != null) o.value.items.filter { it.strategyId == slotPlan.strategyId } else o.value.items
                if (items.isEmpty()) Text("No orders yet.")
                items.take(50).forEach { order ->
                    SfCard {
                        Row {
                            Text("${order.side} ${fmt.quantity(order.quantity)} ${order.symbol}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                            StatusChip(order.status)
                        }
                        Text("${order.orderType} · ${order.source.lowercase()} · ${fmt.dateTime(order.createdAt)}", style = MaterialTheme.typography.bodySmall)
                        order.averageFillPrice?.let { LabelValue("Average fill", fmt.money(it)) }
                        order.rejectionReason?.let { Text("Rejected: $it", style = MaterialTheme.typography.bodySmall) }
                        if (order.status in setOf("VALIDATED", "PENDING", "PARTIALLY_FILLED")) TextButton(onClick = { vm.cancel(order.id) }) { Text("Cancel") }
                    }
                }
            }
            SectionTitle("New simulated order")
            Row {
                listOf("BUY", "SELL", "SELL_SHORT", "BUY_TO_COVER").forEach { s ->
                    FilterChip(selected = side == s, onClick = { side = s }, label = { Text(s.lowercase().replace('_', ' ')) })
                }
            }
            SymbolField(
                symbol,
                {
                    symbol = it
                    formErrors = formErrors - OrderForm.SYMBOL
                    if (failedField == OrderForm.SYMBOL) vm.clearAction()
                    vm.searchSymbols(it)
                },
                suggestions,
                { h ->
                    symbol = h.symbol
                    formErrors = formErrors - OrderForm.SYMBOL
                    vm.clearSuggestions()
                },
                { sym -> onInfo(sym, selected) },
                fmt,
                targets.getValue(OrderForm.SYMBOL).modifier,
                error = fieldErrors[OrderForm.SYMBOL],
            )
            if (symbol.isNotBlank() && suggestions.isEmpty()) {
                TextButton(onClick = { onInfo(symbol.trim().uppercase(), selected) }, modifier = Modifier.testTag("symbol-info")) { Text("About ${symbol.trim().uppercase()} ›") }
            }
            Field(
                "Quantity",
                qty,
                {
                    qty = it
                    formErrors = formErrors - OrderForm.QUANTITY
                },
                targets.getValue(OrderForm.QUANTITY).modifier.testTag("quantity-field"),
                number = true,
                error = fieldErrors[OrderForm.QUANTITY],
            )
            Field(
                "Limit price (blank for market)",
                limit,
                {
                    limit = it
                    formErrors = formErrors - OrderForm.LIMIT
                },
                targets.getValue(OrderForm.LIMIT).modifier.testTag("limit-field"),
                number = true,
                error = fieldErrors[OrderForm.LIMIT],
            )
            Button(
                onClick = {
                    formErrors = OrderForm.problems(symbol, qty, limit)
                    attempts++
                    val id = selected
                    if (formErrors.isEmpty() && id != null) {
                        vm.placeOrder(OrderDraft(id, symbol.trim().uppercase(), side, if (limit.isBlank()) "MARKET" else "LIMIT", qty.trim(), limit.trim().ifBlank { null }, timeInForce = "DAY"))
                    }
                },
                modifier = targets.getValue("submit").modifier.testTag("submit-order"),
            ) { Text("Submit paper order") }
            // Rejections and other order problems show here, by the button (D-065).
            if (failedField == "submit") Banner((action as ActionState.Failed).message, BannerKind.ERROR, Modifier.testTag("order-error"))
            if (action is ActionState.Done && (action as ActionState.Done).message.startsWith("Paper order")) Banner((action as ActionState.Done).message)
        }
        if (plan == null) {
            SectionTitle("Create portfolio")
            Field("Name", name, { name = it })
            Field("Starting balance (USD)", balance, { balance = it }, number = true)
            OutlinedButton(onClick = { vm.createPortfolio(name, balance) }, enabled = name.isNotBlank()) { Text("Create") }
        }
        // The TSX plan view shows its own action feedback.
        // Field problems are shown at their field instead.
        if (plan !is RunningPlan.TsxPlan && failedField == null && !(action is ActionState.Done && (action as ActionState.Done).message.startsWith("Paper order"))) ActionFeedback(action)
    }
    if (confirmShorting) {
        val id = selected
        ReauthDialog({ confirmShorting = false }) { pw, totp ->
            confirmShorting = false
            if (id != null) session?.reauthenticate(pw, totp) { vm.setShorting(id, true) } ?: vm.setShorting(id, true)
        }
    }
}

/** How often the Portfolio screen re-reads which plans are running. */
private const val PLANS_REFRESH_MS = 30_000L

/**
 * A running crypto or stock plan shown as a portfolio (D-058): which slot it runs in, how it trades,
 * its results so far and the portfolio it trades in. Totals below are the whole portfolio's, so they
 * include any other plan sharing it.
 */
@Composable
fun SlotPlanCard(
    p: RunningPlan.SlotPlan,
    score: app.strategyforge.android.core.model.Scorecard?,
    summary: PortfolioSummary,
    fmt: Formatters,
) {
    val a = p.slot.activation
    val symbols = p.symbols
    val unrealized =
        summary.positions
            .filter { it.symbol in symbols }
            .mapNotNull { it.unrealizedPnl?.toBigDecimalOrNull() }
            .fold(java.math.BigDecimal.ZERO, java.math.BigDecimal::add)
    SfCard(Modifier.testTag("slot-plan")) {
        Text(p.slot.strategy?.name ?: "", style = MaterialTheme.typography.titleMedium)
        Text(
            (if (p.slot.assetClass == "CRYPTO") "Crypto" else "Stock") + " slot ${p.slot.number} · " +
                (if (a?.mode == "AUTONOMOUS") "Autonomous" else "Notifications") + " · trades in ${summary.portfolio.name}",
            style = MaterialTheme.typography.bodySmall,
        )
        a?.allocationPercent?.let { LabelValue("Share of the portfolio", "$it%") }
        val live = score?.live
        if (live != null) {
            PnlValue("Realized (closed trades)", live.realizedPnl, fmt)
            PnlValue("Unrealized (open positions)", unrealized.toPlainString(), fmt)
            PnlValue(
                "Total profit/loss",
                live.realizedPnl
                    .toBigDecimalOrNull()
                    ?.add(unrealized)
                    ?.toPlainString(),
                fmt,
            )
            LabelValue("Closed trades", if (live.closedTrades == 0) "None yet" else "${live.closedTrades} (${live.wins} won, ${live.losses} lost)")
            live.winRatePercent?.let { LabelValue("Win rate", fmt.percent(it)) }
            LabelValue("Days running", live.activeDays)
        } else {
            PnlValue("Unrealized (open positions)", unrealized.toPlainString(), fmt)
        }
        Text(
            "Equity, cash and the chart below are the whole ${summary.portfolio.name} portfolio's; positions and orders are this plan's.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** Simulated short selling for this portfolio (D-042): needed by strategies that can go short. */
@Composable
fun ShortingSwitch(
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    SfCard(Modifier.testTag("shorting")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Simulated short selling", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Lets strategies profit from falling prices in crypto and stocks, like a futures short. Losses on a short can exceed " +
                        "the amount reserved, and a daily borrow fee is charged. Paper trading only.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = enabled, onCheckedChange = onChange, modifier = Modifier.testTag("shorting-switch"))
        }
    }
}

@Composable
fun ActivityScreen(
    recs: RecommendationsViewModel,
    inbox: InboxViewModel,
    fmt: Formatters,
    nav: NavHostController,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Recommendations") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Inbox") })
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
            if (tab == 0) RecommendationsList(recs, fmt, nav) else Inbox(inbox, fmt, nav)
        }
    }
}

@Composable
private fun RecommendationsList(
    vm: RecommendationsViewModel,
    fmt: Formatters,
    nav: NavHostController,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    Row {
        listOf("PENDING" to "Pending", null to "History").forEach { (value, label) ->
            FilterChip(selected = filter == value, onClick = { vm.setFilter(value) }, label = { Text(label) })
            Spacer(Modifier.width(8.dp))
        }
    }
    ResourceContent(state, fmt, vm::refresh, empty = { it.items.isEmpty() }, emptyText = "No recommendations.", art = Art.INBOX) { page ->
        page.items.forEach { r ->
            SfCard(Modifier.clickable { nav.navigate("recommendation/${r.id}") }) {
                Row {
                    Text("${r.side} ${fmt.quantity(r.quantity)} ${r.symbol}", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    StatusChip(r.status)
                }
                Text("${r.strategyName} · ${fmt.dateTime(r.createdAt)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun Inbox(
    vm: InboxViewModel,
    fmt: Formatters,
    nav: NavHostController,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    TextButton(onClick = vm::markAllRead) { Text("Mark all read") }
    ActionFeedback(action)
    ResourceContent(state, fmt, vm::refresh, empty = { it.items.isEmpty() }, emptyText = "Inbox is empty.", art = Art.INBOX) { page ->
        page.items.forEach { n ->
            SfCard(
                Modifier.clickable {
                    vm.markRead(n.id)
                    DeepLinks.parse(n.deepLink)?.let { nav.navigate(routeFor(it)) }
                },
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (n.readAt == null) "● " else "") + n.title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (n.critical) StatusChip("CRITICAL")
                }
                Text(n.body, style = MaterialTheme.typography.bodySmall)
                Text("${fmt.dateTime(n.createdAt)} · push ${n.pushStatus.lowercase().replace('_', ' ')}", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun RecommendationScreen(
    presenter: RecommendationPresenter,
    fmt: Formatters,
) {
    val s by presenter.state.collectAsStateWithLifecycle()
    RecommendationContent(s, fmt, presenter::setQuantity, presenter::setLimitPrice, presenter::accept, presenter::decline, presenter::snooze, presenter::pauseStrategy, presenter::load)
}

/** Stateless recommendation detail (FR-061, FR-063): all decisions go through the backend. */
@Composable
fun RecommendationContent(
    s: RecommendationState,
    fmt: Formatters,
    onQuantity: (String) -> Unit,
    onLimit: (String) -> Unit,
    onAccept: () -> Unit,
    onDecline: (String?) -> Unit,
    onSnooze: (Int) -> Unit,
    onPause: () -> Unit,
    onReload: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        val d = s.detail
        when {
            s.loading && d == null -> Loading()
            d == null -> {
                Banner(s.loadError ?: "Could not load the recommendation", BannerKind.ERROR)
                Button(onClick = onReload) { Text("Retry") }
            }
            else -> {
                val r = d.recommendation
                SectionTitle("${r.side} ${fmt.quantity(r.quantity)} ${r.symbol}")
                StatusChip(r.status)
                Banner(d.disclaimer)
                LabelValue("Strategy", r.strategyName)
                LabelValue("Version hash", r.versionHash.take(16))
                LabelValue("Order type", r.orderType + (r.limitPrice?.let { " @ ${fmt.money(it)}" } ?: ""))
                LabelValue("Reference price", fmt.money(r.referencePrice))
                LabelValue("Max price deviation", fmt.percent(r.maxDeviationPercent))
                LabelValue("Expires", fmt.dateTime(r.expiresAt))
                r.snoozedUntil?.let { LabelValue("Snoozed until", fmt.dateTime(it)) }
                SectionTitle("Why")
                Text(r.rationale)
                r.triggeredRules.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                r.statusReason?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                r.orderId?.let { LabelValue("Paper order", it.take(8)) }
                if (r.status == "PENDING") {
                    SectionTitle("Decide")
                    Text("You may only reduce the quantity or choose a more conservative limit price.")
                    Field("Quantity", s.quantity, onQuantity, number = true, modifier = Modifier.testTag("rec-qty"))
                    if (r.limitPrice != null) Field("Limit price", s.limitPrice, onLimit, number = true)
                    s.validation?.let { Banner(it, BannerKind.WARNING) }
                    Button(
                        onClick = onAccept,
                        enabled = s.validation == null && d.actionToken != null && s.action != ActionState.Running,
                        modifier = Modifier.fillMaxWidth().testTag("accept"),
                    ) { Text("Accept (simulated order)") }
                    Row {
                        OutlinedButton(onClick = { onDecline(null) }, modifier = Modifier.testTag("decline")) { Text("Decline") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { onSnooze(10) }) { Text("Snooze 10 min") }
                    }
                    TextButton(onClick = onPause) { Text("Pause this strategy") }
                } else {
                    Banner("This recommendation is ${r.status.lowercase()}; no further action is possible.")
                }
                s.result?.let { res -> LabelValue("Order status", res.orderStatus ?: "-") }
            }
        }
        ActionFeedback(s.action)
    }
}

/** Where the portfolio's value sits: cash and each position at market value (cost when unpriced). */
@Composable
fun AllocationCard(
    s: PortfolioSummary,
    fmt: Formatters,
) {
    val palette = Sf.colors.series
    val cash = s.cash.toDoubleOrNull() ?: 0.0
    val slices =
        listOf(DonutSlice("Cash", cash.coerceAtLeast(0.0), Sf.colors.neutral)) +
            s.positions
                .map { p -> p.symbol to kotlin.math.abs(p.marketValue?.toDoubleOrNull() ?: p.costBasis.toDoubleOrNull() ?: 0.0) }
                .sortedByDescending { it.second }
                .mapIndexed { i, (sym, v) -> DonutSlice(sym, v, palette[i % palette.size]) }
    if (s.positions.isEmpty()) return
    SfCard(Modifier.testTag("allocation")) {
        Text("Allocation", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 10.dp))
        DonutChart(slices, "Positions", s.positions.size.toString())
    }
}
