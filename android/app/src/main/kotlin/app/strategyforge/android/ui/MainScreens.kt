package app.strategyforge.android.ui

import android.net.Uri
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import app.strategyforge.android.core.cache.Resource
import app.strategyforge.android.core.data.Dashboard
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.EquityChart
import app.strategyforge.android.core.notify.Route
import app.strategyforge.android.core.state.ActionState
import app.strategyforge.android.core.state.EmergencyPresenter
import app.strategyforge.android.core.state.EmergencyUiState

private data class Tab(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

private val tabs =
    listOf(
        Tab("home", "Home", Icons.Filled.Home),
        Tab("strategies", "Plans", SfIcons.Candles),
        Tab("portfolio", "Portfolio", SfIcons.Wallet),
        Tab("activity", "Activity", Icons.Filled.Notifications),
        Tab("more", "More", Icons.Filled.MoreVert),
    )

private fun isTab(route: String?): Boolean = tabs.any { it.route == route?.substringBefore('?') }

fun routeFor(r: Route): String =
    when (r) {
        is Route.Recommendation -> "recommendation/${r.id}"
        is Route.Strategy -> "strategy/${r.id}"
        is Route.Portfolio -> "portfolio?id=${r.id}"
        is Route.Research -> "research/${r.id}"
        is Route.TsxRun -> "tsx-run/${r.id}"
        is Route.Order, is Route.Notification, Route.Inbox -> "activity"
        Route.Diagnostics -> "diagnostics"
        Route.Emergency -> "emergency"
    }

/** Bottom navigation: Home, Strategies, Portfolio, Activity, More; Pause All stays reachable (section 16). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainShell(
    nav: NavHostController,
    session: SessionViewModel,
    pendingRoute: Route?,
    onRouteConsumed: () -> Unit,
) {
    val tz by session.timezone.collectAsStateWithLifecycle()
    val fmt = remember(tz) { formatters(tz) }
    val home: HomeViewModel = hiltViewModel()
    val dash by home.state.collectAsStateWithLifecycle()
    val anyActive = (dash as? Resource.Data<Dashboard>)?.value?.strategies?.any { it.status.startsWith("ACTIVE") } == true
    val paused = (dash as? Resource.Data<Dashboard>)?.value?.emergency?.pauseAll == true
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let {
            nav.navigate(routeFor(it))
            onRouteConsumed()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(SfIcons.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("StrategyForge", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.width(8.dp))
                        Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), shape = RoundedCornerShape(50)) {
                            Text(
                                "PAPER",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                        }
                    }
                },
                actions = {
                    if (anyActive && !paused) {
                        TextButton(onClick = { nav.navigate("emergency") }, modifier = Modifier.testTag("pause-all-shortcut")) { Text("Pause all") }
                    }
                },
            )
        },
        bottomBar = {
            val entry by nav.currentBackStackEntryAsState()
            val current = entry?.destination?.route
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, tonalElevation = 0.dp) {
                tabs.forEach { t ->
                    NavigationBarItem(
                        colors =
                            NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                            ),
                        selected = current?.substringBefore('?') == t.route,
                        onClick = {
                            nav.navigate(t.route) {
                                popUpTo("home") { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(
            nav,
            startDestination = "home",
            modifier = Modifier.padding(padding),
            // Screens slide in from the side and fade; going back reverses it. Tabs cross-fade.
            enterTransition = {
                if (isTab(targetState.destination.route)) {
                    fadeIn(tween(220))
                } else {
                    slideInHorizontally(tween(300)) { it / 4 } + fadeIn(tween(300))
                }
            },
            exitTransition = {
                if (isTab(targetState.destination.route)) fadeOut(tween(180)) else slideOutHorizontally(tween(300)) { -it / 8 } + fadeOut(tween(200))
            },
            popEnterTransition = { slideInHorizontally(tween(300)) { -it / 8 } + fadeIn(tween(300)) },
            popExitTransition = { slideOutHorizontally(tween(300)) { it / 4 } + fadeOut(tween(200)) },
        ) {
            composable("home") { HomeScreen(home, fmt, nav) }
            composable("strategies") { StrategiesScreen(hiltViewModel(), fmt, nav) }
            composable("strategy/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                StrategyDetailScreen(hiltViewModel(), fmt, session, onChart = { sym, sid, tf -> nav.navigate("chart/${Uri.encode(sym)}?strategyId=$sid&timeframe=$tf") })
            }
            composable("scorecards") { ScorecardsScreen(hiltViewModel(), fmt, nav) }
            composable("tsx") { TsxPlansScreen(hiltViewModel(), nav) }
            composable("tsx-run/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { TsxRunScreen(hiltViewModel()) }
            composable("research") { ResearchListScreen(hiltViewModel(), fmt, nav) }
            composable("research/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { ResearchDetailScreen(hiltViewModel(), fmt, nav) }
            composable(
                "portfolio?id={id}&plan={plan}",
                arguments =
                    listOf(
                        navArgument("id") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("plan") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
            ) { PortfolioScreen(hiltViewModel(), fmt, onChart = { sym, pid -> nav.navigate("chart/${Uri.encode(sym)}?portfolioId=$pid") }, session = session) }
            composable(
                "chart/{symbol}?portfolioId={portfolioId}&strategyId={strategyId}&timeframe={timeframe}",
                arguments =
                    listOf(
                        navArgument("symbol") { type = NavType.StringType },
                        navArgument("portfolioId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("strategyId") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                        navArgument("timeframe") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
            ) { ChartScreen(hiltViewModel(), fmt) }
            composable("activity") { ActivityScreen(hiltViewModel(), hiltViewModel(), fmt, nav) }
            composable("recommendation/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) {
                val vm: RecommendationDetailViewModel = hiltViewModel()
                RecommendationScreen(vm.presenter, fmt)
            }
            composable("more") { MoreScreen(nav, session) }
            composable("emergency") {
                val vm: EmergencyViewModel = hiltViewModel()
                EmergencyScreen(vm.presenter, fmt, session)
            }
            composable("diagnostics") { DiagnosticsScreen(hiltViewModel(), fmt) }
            composable(
                "reports?id={id}",
                arguments =
                    listOf(
                        navArgument("id") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
            ) { ReportsScreen(hiltViewModel(), fmt) }
            composable("settings") { SettingsScreen(hiltViewModel(), fmt, session) }
            composable("ai-providers") { AiProvidersScreen(hiltViewModel(), session) }
            composable("engine") { EngineScreen(hiltViewModel(), fmt, session) }
            composable("budget") { BudgetScreen(hiltViewModel(), fmt, session) }
            composable("backups") { BackupsScreen(hiltViewModel(), fmt, session) }
            composable("exports") { ExportsScreen(hiltViewModel(), session) }
        }
    }
}

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    fmt: Formatters,
    nav: NavHostController,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val equity by vm.equity.collectAsStateWithLifecycle()
    val primaryId =
        (state as? Resource.Data<Dashboard>)
            ?.value
            ?.primary
            ?.portfolio
            ?.id
    val plans by vm.plans.collectAsStateWithLifecycle()
    LaunchedEffect(primaryId) { primaryId?.let { vm.loadEquity(it) } }
    LaunchedEffect(Unit) { vm.loadPlans() }
    AutoRefresh(LIVE_REFRESH_MS) { vm.refresh() }
    AutoRefresh(HOME_PLANS_REFRESH_MS) { vm.loadPlans() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        ResourceContent(state, fmt, onRetry = vm::refresh) { d -> DashboardContent(d, fmt, equity, plans, onOpen = { nav.navigate(it) }) }
        TextButton(onClick = vm::refresh) { Text("Refresh") }
    }
}

@Composable
fun DashboardContent(
    d: Dashboard,
    fmt: Formatters,
    equity: EquityChart? = null,
    plans: List<PlanSnapshot> = emptyList(),
    onOpen: (String) -> Unit,
) {
    if (d.emergency.pauseAll) Banner("Pause All is engaged: no strategies are evaluated and no orders are placed.", BannerKind.ERROR)
    if (d.emergency.preventNewPositions) Banner("Prevent New Positions is engaged: only position-reducing orders are allowed.", BannerKind.WARNING)
    d.clock?.let { c -> if (c.mode == "REPLAY") Banner("Replay/demo mode: market data is synthetic (market time ${fmt.dateTime(c.now)}).", BannerKind.WARNING) }
    SectionTitle("Portfolio")
    val p = d.primary
    if (p == null) {
        EmptyState("No active paper portfolio yet.", Art.PORTFOLIO) {
            Button(onClick = { onOpen("portfolio") }) { Text("Create a portfolio") }
        }
    } else {
        HeroCard(
            label = p.portfolio.name,
            value = fmt.money(p.equity),
            modifier = Modifier.clickable { onOpen("portfolio?id=${p.portfolio.id}") },
            change = { ChangePill(p.totalReturn, p.totalReturnPercent, fmt, suffix = " all time") },
        ) {
            val line = equity?.points?.toLine(fmt).orEmpty()
            if (line.size >= 2) {
                val up = line.last().y >= line.first().y
                Sparkline(line.map { it.y }, if (up) Sf.colors.gain else Sf.colors.loss, Modifier.padding(vertical = 10.dp).testTag("home-sparkline"), height = 56.dp)
            }
            PnlValue("Total return", p.totalReturn, fmt)
            PnlValue("Unrealized", p.unrealizedPnl, fmt)
            LabelValue("Cash", fmt.money(p.cash))
            LabelValue("As of", fmt.dateTime(p.asOf))
            if (!p.fullyPriced) Banner("Some positions are carried at cost because a verified price is unavailable.", BannerKind.WARNING)
            if (p.portfolio.reconciliationStatus != "OK") Banner("Reconciliation ${p.portfolio.reconciliationStatus}: trading on this portfolio is blocked.", BannerKind.ERROR)
        }
    }
    if (plans.isNotEmpty()) {
        SectionTitle("Running plans")
        plans.forEach { s -> PlanSnapshotCard(s, fmt) { onOpen("portfolio?plan=${Uri.encode(s.plan.key)}") } }
    }
    SectionTitle("Mode")
    val active = d.strategies.filter { it.status.startsWith("ACTIVE") }
    val autonomous = active.count { it.status == "ACTIVE_AUTONOMOUS" }
    LabelValue("Active strategies", "${active.size} ($autonomous autonomous)")
    if (autonomous > 0) Banner("Autonomous paper trading is active for $autonomous strategy(ies).", BannerKind.WARNING)
    d.strategies.filter { it.status == "SUSPENDED" || it.status == "PAUSED" }.forEach {
        Banner("${it.name} is ${it.status.lowercase()}${it.statusReason?.let { r -> ": $r" } ?: ""}", BannerKind.WARNING)
    }
    SectionTitle("Recommendations")
    if (d.pendingRecommendations.items.isEmpty()) {
        Text("No pending recommendations.")
    } else {
        d.pendingRecommendations.items.take(5).forEach { r ->
            SfCard(Modifier.clickable { onOpen("recommendation/${r.id}") }) {
                Text("${r.side} ${fmt.quantity(r.quantity)} ${r.symbol}", style = MaterialTheme.typography.titleSmall)
                Text("${r.strategyName} · expires ${fmt.dateTime(r.expiresAt)}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    SectionTitle("System health")
    Row(Modifier.fillMaxWidth().clickable { onOpen("diagnostics") }) {
        StatusChip(d.diagnostics.overall)
        Spacer(Modifier.width(8.dp))
        Text("${d.diagnostics.components.count { it.status != "OK" }} component(s) need attention", style = MaterialTheme.typography.bodyMedium)
    }
    LabelValue("Unread notifications", d.unread.unread.toString())
    SectionTitle("Emergency controls")
    OutlinedButton(onClick = { onOpen("emergency") }, modifier = Modifier.fillMaxWidth()) { Text("Open emergency controls") }
}

@Composable
fun EmergencyScreen(
    presenter: EmergencyPresenter,
    fmt: Formatters,
    session: SessionViewModel,
) {
    val s by presenter.state.collectAsStateWithLifecycle()
    EmergencyContent(s, fmt, presenter, session)
}

@Composable
fun EmergencyContent(
    s: EmergencyUiState,
    fmt: Formatters,
    presenter: EmergencyPresenter,
    session: SessionViewModel?,
) {
    var confirmClose by remember { mutableStateOf(false) }
    var showReauth by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Emergency controls")
        Text("These controls act on simulated paper trading only.")
        val st = s.state
        if (st == null) {
            Loading()
        } else {
            LabelValue("Pause All", if (st.pauseAll) "Engaged" else "Off")
            LabelValue("Prevent New Positions", if (st.preventNewPositions) "Engaged" else "Off")
            LabelValue("Last change", fmt.dateTime(st.updatedAt))
            st.reason?.let { LabelValue("Reason", it) }
            Button(
                onClick = { presenter.pauseAll(!st.pauseAll) },
                colors = if (!st.pauseAll) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().testTag("pause-all"),
            ) { Text(if (st.pauseAll) "Release Pause All" else "Pause All") }
            OutlinedButton(onClick = { presenter.preventNewPositions(!st.preventNewPositions) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (st.preventNewPositions) "Allow new positions" else "Prevent New Positions")
            }
            OutlinedButton(onClick = presenter::cancelPendingOrders, modifier = Modifier.fillMaxWidth()) { Text("Cancel Pending Orders") }
            OutlinedButton(onClick = presenter::disableAutonomous, modifier = Modifier.fillMaxWidth()) { Text("Disable Autonomous Mode") }
            SectionTitle("Close all simulated positions")
            Text("Submits simulated market orders that flatten every paper position. Type the phrase to confirm.")
            Field("Type ${EmergencyPresenter.CONFIRMATION}", s.confirmation, presenter::setConfirmation, modifier = Modifier.testTag("close-confirm"))
            Button(
                onClick = { confirmClose = true },
                enabled = s.confirmation.trim() == EmergencyPresenter.CONFIRMATION,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth().testTag("close-all"),
            ) { Text("Close All Simulated Positions") }
        }
        ActionFeedback(s.action, onReauth = { showReauth = true })
    }
    if (confirmClose) {
        ConfirmDialog("Close all simulated positions?", "Every open paper position will be closed at simulated market prices.", "Close all", { confirmClose = false }) {
            confirmClose = false
            presenter.closeAll()
        }
    }
    if (showReauth && session != null) {
        ReauthDialog({ showReauth = false }) { pw, totp ->
            showReauth = false
            session.reauthenticate(pw, totp) { presenter.reauthenticated() }
        }
    }
}

@Composable
fun DiagnosticsScreen(
    vm: DiagnosticsViewModel,
    fmt: Formatters,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Diagnostics")
        ResourceContent(state, fmt, vm::refresh) { d ->
            LabelValue("Overall", d.overall)
            LabelValue("Generated", fmt.dateTime(d.generatedAt))
            d.components.forEach { c ->
                SfCard {
                    Row {
                        Text(c.component, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.width(8.dp))
                        StatusChip(c.status)
                    }
                    Text(c.detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        TextButton(onClick = vm::refresh) { Text("Refresh") }
    }
}

val ActionState.running get() = this == ActionState.Running

/** How often Home re-reads the running plans' numbers (they need several requests each). */
private const val HOME_PLANS_REFRESH_MS = 15_000L

/** One running plan on Home (D-060); tapping it opens the plan in the Portfolio screen. */
@Composable
fun PlanSnapshotCard(
    s: PlanSnapshot,
    fmt: Formatters,
    onOpen: () -> Unit,
) {
    SfCard(Modifier.clickable(onClick = onOpen).testTag("home-plan-${s.plan.key}")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(s.plan.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("Open ›", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        when (s) {
            is PlanSnapshot.Slot -> {
                val a = s.plan.slot.activation
                Text(
                    (if (a?.mode == "AUTONOMOUS") "Autonomous" else "Notifications") + (s.portfolioName?.let { " · $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
                PnlValue("Profit/loss", s.profitLoss, fmt)
                LabelValue("Open positions / closed trades", "${s.openPositions} / ${s.closedTrades}")
            }
            is PlanSnapshot.Tsx -> {
                val r = s.plan.run
                val now = r.liveValue ?: r.value
                Text((if (r.mode == "AUTONOMOUS") "Autonomous" else "Notifications") + if (r.drip) " · DRIP" else " · dividends paid out", style = MaterialTheme.typography.bodySmall)
                LabelValue(if (r.liveValue != null) "Value now" else "Value at last close", cad(now))
                LabelValue("Change since start", pctText(if (r.startingCash > 0) 100 * (now / r.startingCash - 1) else null))
                r.liveValue?.let { LabelValue("Today", pctText(100 * (it / r.value - 1))) }
                if (r.pending != null) Banner("A rebalance is waiting for your approval.", BannerKind.WARNING)
            }
        }
    }
}
