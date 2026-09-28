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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        Tab("strategies", "Strategies", Icons.AutoMirrored.Filled.List),
        Tab("portfolio", "Portfolio", Icons.Filled.Star),
        Tab("activity", "Activity", Icons.Filled.Notifications),
        Tab("more", "More", Icons.Filled.MoreVert),
    )

fun routeFor(r: Route): String =
    when (r) {
        is Route.Recommendation -> "recommendation/${r.id}"
        is Route.Strategy -> "strategy/${r.id}"
        is Route.Portfolio -> "portfolio?id=${r.id}"
        is Route.Research -> "research/${r.id}"
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
    // A revoked or expired session (HTTP 401) returns to the sign-in screen.
    LaunchedEffect(dash) {
        val err = (dash as? Resource.Failure)?.error ?: (dash as? Resource.Data<Dashboard>)?.error
        if (err is app.strategyforge.android.core.api.ApiError.Unauthorized) session.signOut()
    }
    LaunchedEffect(pendingRoute) {
        pendingRoute?.let {
            nav.navigate(routeFor(it))
            onRouteConsumed()
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StrategyForge") },
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
            NavigationBar {
                tabs.forEach { t ->
                    NavigationBarItem(
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
        NavHost(nav, startDestination = "home", modifier = Modifier.padding(padding)) {
            composable("home") { HomeScreen(home, fmt, nav) }
            composable("strategies") { StrategiesScreen(hiltViewModel(), fmt, nav) }
            composable("strategy/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { StrategyDetailScreen(hiltViewModel(), fmt, session) }
            composable("research") { ResearchListScreen(hiltViewModel(), fmt, nav) }
            composable("research/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { ResearchDetailScreen(hiltViewModel(), fmt, nav) }
            composable(
                "portfolio?id={id}",
                arguments =
                    listOf(
                        navArgument("id") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        },
                    ),
            ) { PortfolioScreen(hiltViewModel(), fmt) }
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
            composable("security") { SecurityScreen(hiltViewModel(), fmt, session) }
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
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        ResourceContent(state, fmt, onRetry = vm::refresh) { d -> DashboardContent(d, fmt, onOpen = { nav.navigate(it) }) }
        TextButton(onClick = vm::refresh) { Text("Refresh") }
    }
}

@Composable
fun DashboardContent(
    d: Dashboard,
    fmt: Formatters,
    onOpen: (String) -> Unit,
) {
    if (d.emergency.pauseAll) Banner("Pause All is engaged: no strategies are evaluated and no orders are placed.", BannerKind.ERROR)
    if (d.emergency.preventNewPositions) Banner("Prevent New Positions is engaged: only position-reducing orders are allowed.", BannerKind.WARNING)
    d.clock?.let { c -> if (c.mode == "REPLAY") Banner("Replay/demo mode: market data is synthetic (market time ${fmt.dateTime(c.now)}).", BannerKind.WARNING) }
    SectionTitle("Portfolio")
    val p = d.primary
    if (p == null) {
        Text("No active paper portfolio yet.", modifier = Modifier.testTag("empty"))
        Button(onClick = { onOpen("portfolio") }) { Text("Create a portfolio") }
    } else {
        SfCard(Modifier.clickable { onOpen("portfolio?id=${p.portfolio.id}") }) {
            Text(p.portfolio.name, style = MaterialTheme.typography.titleSmall)
            LabelValue("Equity", fmt.money(p.equity))
            LabelValue("Cash", fmt.money(p.cash))
            PnlValue("Total return", p.totalReturn, fmt)
            PnlValue("Unrealized", p.unrealizedPnl, fmt)
            LabelValue("As of", fmt.dateTime(p.asOf))
            if (!p.fullyPriced) Banner("Some positions are carried at cost because a verified price is unavailable.", BannerKind.WARNING)
            if (p.portfolio.reconciliationStatus != "OK") Banner("Reconciliation ${p.portfolio.reconciliationStatus}: trading on this portfolio is blocked.", BannerKind.ERROR)
        }
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
