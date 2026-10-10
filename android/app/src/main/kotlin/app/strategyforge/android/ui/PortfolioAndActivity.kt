package app.strategyforge.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import app.strategyforge.android.core.model.Portfolio
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
    val overview by vm.overview.collectAsStateWithLifecycle()
    val summaries by vm.summaries.collectAsStateWithLifecycle()
    // Back from an open portfolio returns to the overview (D-079).
    androidx.activity.compose.BackHandler(enabled = !overview) { vm.showOverview() }
    LaunchedEffect(Unit) { vm.loadPlans() }
    AutoRefresh(LIVE_REFRESH_MS) { vm.refreshLive() }
    AutoRefresh(PLANS_REFRESH_MS) { vm.loadPlans() }
    val plan = plans.firstOrNull { it.key == planKey }
    val slotPlan = plan as? RunningPlan.SlotPlan
    var symbol by rememberSaveable { mutableStateOf("") }
    var qty by rememberSaveable { mutableStateOf("") }
    var side by rememberSaveable { mutableStateOf("BUY") }
    var limit by rememberSaveable { mutableStateOf("") }
    // Order by dollar amount (fractions included) or by quantity (D-066).
    var byAmount by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableStateOf("") }
    // Last known price of the picked symbol, for the estimate under the amount.
    var pickedPrice by rememberSaveable { mutableStateOf<String?>(null) }
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    // Problems found before sending, per field; engine problems arrive in [action] with their field (D-065).
    var formErrors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    // Counts submits, so pressing Submit again scrolls to the same problem again.
    var attempts by remember { mutableStateOf(0) }
    val targets = rememberFieldTargets(OrderForm.SYMBOL, OrderForm.AMOUNT, OrderForm.QUANTITY, OrderForm.LIMIT, "submit")
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
            pickedPrice = null
            vm.clearSuggestions()
            formErrors = emptyMap()
            targets.getValue(if (byAmount) OrderForm.AMOUNT else OrderForm.QUANTITY).show()
            onPrefillUsed()
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        if (overview) {
            PortfolioOverview(portfolioEntries(portfolios, plans, summaries, fmt), vm::open, vm::createPortfolio)
            ActionFeedback(action)
        } else {
            TextButton(onClick = vm::showOverview, modifier = Modifier.testTag("all-portfolios")) { Text("‹ All portfolios") }
            if (plan is RunningPlan.TsxPlan) {
                val r = tsxRun
                if (r == null) Loading() else TsxRunDetail(r, action, { vm.tsxAction("approve") }, { vm.tsxAction("decline") }, { vm.tsxAction("stop") }, { vm.tsxAction(it) })
            } else if (selected == null) {
                EmptyState("Choose a portfolio from All portfolios.", Art.PORTFOLIO)
            } else {
                ResourceContent(state, fmt, vm::reload) { s ->
                    SectionTitle(portfolios.firstOrNull { it.id == s.portfolio.id }?.let { titleOf(it) } ?: s.portfolio.name)
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
                    // A plan in its slot's own portfolio owns everything in it; one sharing a portfolio shows only its own symbols.
                    val planSymbols = slotPlan?.takeIf { !ownsPortfolio(it, s.portfolio) }?.symbols
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
                        pickedPrice = null
                        formErrors = formErrors - OrderForm.SYMBOL
                        if (failedField == OrderForm.SYMBOL) vm.clearAction()
                        vm.searchSymbols(it)
                    },
                    suggestions,
                    { h ->
                        symbol = h.symbol
                        pickedPrice = h.lastPrice
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = byAmount, onClick = { byAmount = true }, label = { Text("Amount ($)") }, modifier = Modifier.testTag("by-amount"))
                    FilterChip(selected = !byAmount, onClick = { byAmount = false }, label = { Text("Quantity") }, modifier = Modifier.testTag("by-quantity"))
                }
                if (byAmount) {
                    Field(
                        if (side == "SELL" || side == "SELL_SHORT") "Amount to sell (USD)" else "Amount to spend (USD)",
                        amount,
                        {
                            amount = it
                            formErrors = formErrors - OrderForm.AMOUNT
                        },
                        targets.getValue(OrderForm.AMOUNT).modifier.testTag("amount-field"),
                        number = true,
                        error = fieldErrors[OrderForm.AMOUNT],
                    )
                    Text(
                        amountHint(OrderForm.cleanAmount(amount), limit.ifBlank { pickedPrice }, symbol, fmt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("amount-hint"),
                    )
                } else {
                    Field(
                        "Quantity (fractions allowed, for example 0.005)",
                        qty,
                        {
                            qty = it
                            formErrors = formErrors - OrderForm.QUANTITY
                        },
                        targets.getValue(OrderForm.QUANTITY).modifier.testTag("quantity-field"),
                        number = true,
                        error = fieldErrors[OrderForm.QUANTITY],
                    )
                }
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
                        formErrors = OrderForm.problems(symbol, if (byAmount) amount else qty, limit, byAmount)
                        attempts++
                        val id = selected
                        if (formErrors.isEmpty() && id != null) {
                            vm.placeOrder(
                                OrderDraft(
                                    id,
                                    symbol.trim().uppercase(),
                                    side,
                                    if (limit.isBlank()) "MARKET" else "LIMIT",
                                    if (byAmount) "" else qty.trim(),
                                    limit.trim().ifBlank { null },
                                    timeInForce = "DAY",
                                    amount = if (byAmount) OrderForm.cleanAmount(amount) else null,
                                ),
                            )
                        }
                    },
                    modifier = targets.getValue("submit").modifier.testTag("submit-order"),
                ) { Text("Submit paper order") }
                // Rejections and other order problems show here, by the button (D-065).
                if (failedField == "submit") Banner((action as ActionState.Failed).message, BannerKind.ERROR, Modifier.testTag("order-error"))
                if (action is ActionState.Done && (action as ActionState.Done).message.startsWith("Paper order")) Banner((action as ActionState.Done).message)
            }
            // The TSX plan view shows its own action feedback.
            // Field problems are shown at their field instead.
            if (plan !is RunningPlan.TsxPlan && failedField == null && !(action is ActionState.Done && (action as ActionState.Done).message.startsWith("Paper order"))) ActionFeedback(action)
        }
    }
    if (confirmShorting) {
        val id = selected
        ReauthDialog({ confirmShorting = false }) { pw, totp ->
            confirmShorting = false
            if (id != null) session?.reauthenticate(pw, totp) { vm.setShorting(id, true) } ?: vm.setShorting(id, true)
        }
    }
}

/** True when [p] trades in its own slot's portfolio (D-079). */
fun ownsPortfolio(
    p: RunningPlan.SlotPlan,
    portfolio: Portfolio,
): Boolean = portfolio.slot?.let { it.assetClass == p.slot.assetClass && it.number == p.slot.number } == true

/** A portfolio's heading: its slot first when a slot owns it (D-079). */
fun titleOf(p: Portfolio): String = p.slot?.let { (if (it.assetClass == "CRYPTO") "Crypto" else "Stock") + " slot ${it.number} · ${p.name}" } ?: p.name

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
    val own = ownsPortfolio(p, summary.portfolio)
    val unrealized =
        summary.positions
            .filter { own || it.symbol in symbols }
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
            if (own) {
                "${summary.portfolio.name} is this slot's own portfolio: everything below is this plan's, plus any trades you place in it yourself."
            } else {
                "Equity, cash and the chart below are the whole ${summary.portfolio.name} portfolio's, which another slot or your own trades share; positions and orders are this plan's. " +
                    "To give it its own portfolio, stop it and start it again."
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("slot-plan-note"),
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

/**
 * Under the amount field (D-066): roughly how much of the symbol the amount buys at its last known
 * price. The exact quantity is worked out from the live price, net of costs, when the order is placed.
 */
fun amountHint(
    amount: String,
    price: String?,
    symbol: String,
    fmt: Formatters,
): String {
    val a = amount.toBigDecimalOrNull()
    val p = price?.toBigDecimalOrNull()
    if (a == null || a.signum() <= 0 || p == null || p.signum() <= 0) return "Fractions are allowed: the app works out the quantity from the live price, after costs."
    val q = a.divide(p, 8, java.math.RoundingMode.DOWN).stripTrailingZeros().toPlainString()
    return "About $q ${symbol.trim().uppercase().removeSuffix("-USD")} at ${fmt.money(p.toPlainString())}, before costs."
}

/** The groups of the Portfolio overview, in order (D-079). */
enum class PortfolioGroup(
    val title: String,
) {
    CRYPTO("Crypto slots"),
    STOCK("Stock slots"),
    TSX("TSX slots"),
    MINE("My portfolios"),
}

/** One card of the Portfolio overview (D-079): a slot's portfolio, a TSX plan, or one of the owner's own portfolios. */
data class PortfolioEntry(
    val key: String,
    val group: PortfolioGroup,
    val title: String,
    val detail: String,
    /** Value now, formatted. */
    val value: String,
    /** Change since the start in percent, or null when unknown. */
    val changePercent: java.math.BigDecimal?,
    /** The portfolio to open, or null for a TSX plan. */
    val portfolioId: String? = null,
    /** The running plan to open, if any. */
    val planKey: String? = null,
    /** Sort order within the group. */
    val order: Int = 0,
)

private fun modeLabel(mode: String?) =
    when (mode) {
        "AUTONOMOUS" -> "Autonomous"
        "NOTIFY", "RECOMMENDATION" -> "Notifications"
        else -> mode?.lowercase().orEmpty()
    }

/**
 * Builds the Portfolio overview (D-079): each crypto and stock slot with its own portfolio and the
 * plan running in it, the TSX plans, and the owner's own portfolios for manual trades. A plan still
 * trading in a portfolio its slot does not own (from before 1.24.0) is listed under its slot, marked shared.
 */
fun portfolioEntries(
    portfolios: List<Portfolio>,
    plans: List<RunningPlan>,
    summaries: Map<String, PortfolioSummary>,
    fmt: Formatters,
): List<PortfolioEntry> {
    val slotPlans = plans.filterIsInstance<RunningPlan.SlotPlan>()
    val byId = portfolios.associateBy { it.id }

    fun group(assetClass: String) = if (assetClass == "CRYPTO") PortfolioGroup.CRYPTO else PortfolioGroup.STOCK

    fun value(id: String) = summaries[id]?.equity?.let { fmt.money(it) } ?: "—"

    fun change(id: String) = summaries[id]?.totalReturnPercent?.let { fmt.decimal(it) }
    val out = mutableListOf<PortfolioEntry>()
    val shown = mutableSetOf<String>()
    portfolios.filter { it.slot != null && it.status == "ACTIVE" }.forEach { p ->
        val s = p.slot!!
        val plan = slotPlans.firstOrNull { it.slot.assetClass == s.assetClass && it.slot.number == s.number && it.portfolioId == p.id }
        plan?.let { shown += it.key }
        out +=
            PortfolioEntry(
                "p:${p.id}",
                group(s.assetClass),
                "Slot ${s.number}" + (
                    plan
                        ?.slot
                        ?.strategy
                        ?.name
                        ?.let { " · $it" } ?: ""
                ),
                if (plan != null) "${modeLabel(plan.slot.activation?.mode)} · ${p.name}" else "No plan running · ${p.name}",
                value(p.id),
                change(p.id),
                p.id,
                plan?.key,
                s.number,
            )
    }
    slotPlans.filter { it.key !in shown }.forEach { plan ->
        val name = byId[plan.portfolioId]?.name ?: "another portfolio"
        out +=
            PortfolioEntry(
                plan.key,
                group(plan.slot.assetClass),
                "Slot ${plan.slot.number} · ${plan.slot.strategy?.name ?: ""}",
                "${modeLabel(plan.slot.activation?.mode)} · shares $name",
                value(plan.portfolioId),
                change(plan.portfolioId),
                plan.portfolioId,
                plan.key,
                plan.slot.number,
            )
    }
    plans.filterIsInstance<RunningPlan.TsxPlan>().forEach { t ->
        val r = t.run
        val now = r.liveValue ?: r.value
        out +=
            PortfolioEntry(
                t.key,
                PortfolioGroup.TSX,
                "Slot ${r.slot} · ${r.planName}",
                (if (r.mode == "AUTONOMOUS") "Autonomous" else "Notify and approve") + " · Canadian dollars",
                cad(now),
                if (r.startingCash > 0) java.math.BigDecimal((now / r.startingCash - 1) * 100) else null,
                planKey = t.key,
                order = r.slot,
            )
    }
    portfolios.filter { it.slot == null && it.status == "ACTIVE" }.forEachIndexed { i, p ->
        val users = slotPlans.filter { it.portfolioId == p.id }
        out +=
            PortfolioEntry(
                "p:${p.id}",
                PortfolioGroup.MINE,
                p.name,
                if (users.isEmpty()) "Your own trades" else "Your own trades · also used by " + users.joinToString { it.label },
                value(p.id),
                change(p.id),
                p.id,
                order = i,
            )
    }
    return out.sortedWith(compareBy({ it.group.ordinal }, { it.order }))
}

/**
 * The Portfolio tab's first page (D-079): every portfolio at a glance, grouped by slot type, with
 * what runs in it. Each slot is its own portfolio, so plans in different slots never share cash or
 * positions; the owner's own trades go in My portfolios.
 */
@Composable
fun PortfolioOverview(
    entries: List<PortfolioEntry>,
    onOpen: (PortfolioEntry) -> Unit,
    onCreate: (name: String, balance: String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var balance by rememberSaveable { mutableStateOf("100000") }
    Text(
        "Each slot is its own portfolio: a plan trades only its slot's cash and positions, so plans in different slots never get in each other's way. " +
            "Trades you place yourself go in My portfolios. Tap a portfolio to open it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )
    PortfolioGroup.entries.forEach { g ->
        val items = entries.filter { it.group == g }
        if (items.isEmpty() && g != PortfolioGroup.MINE) return@forEach
        SectionTitle(g.title)
        if (items.isEmpty()) Text("None yet. Create one below to place your own trades.", style = MaterialTheme.typography.bodySmall)
        items.forEach { e -> PortfolioEntryCard(e) { onOpen(e) } }
    }
    SectionTitle("New portfolio for your own trades")
    Field("Name", name, { name = it }, modifier = Modifier.testTag("new-portfolio-name"))
    Field("Starting balance (USD)", balance, { balance = it }, number = true)
    OutlinedButton(onClick = { onCreate(name.trim(), balance) }, enabled = name.isNotBlank(), modifier = Modifier.testTag("create-portfolio")) { Text("Create") }
    Text(
        "Plans get their slot's portfolio automatically when you start them on the Strategies tab.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PortfolioEntryCard(
    e: PortfolioEntry,
    onClick: () -> Unit,
) {
    SfCard(Modifier.clickable(onClick = onClick).testTag("entry-${e.key}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(e.title, style = MaterialTheme.typography.titleSmall)
                Text(e.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text(e.value, style = MaterialTheme.typography.titleSmall)
                e.changePercent?.let { c ->
                    val v = c.setScale(2, java.math.RoundingMode.HALF_EVEN)
                    Text(
                        (
                            if (v.signum() > 0) {
                                "▲ +"
                            } else if (v.signum() < 0) {
                                "▼ "
                            } else {
                                "■ "
                            }
                        ) + v.toPlainString() + "%",
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            when (v.signum()) {
                                1 -> Sf.colors.gain
                                -1 -> Sf.colors.loss
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
            Text(" ›", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
    }
}
