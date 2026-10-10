package app.strategyforge.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.strategyforge.android.core.model.TsxBacktest
import app.strategyforge.android.core.model.TsxBenchmark
import app.strategyforge.android.core.model.TsxDataStatus
import app.strategyforge.android.core.model.TsxPlan
import app.strategyforge.android.core.model.TsxResearch
import app.strategyforge.android.core.model.TsxRun
import app.strategyforge.android.core.state.ActionState
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

// ------------------------------------------------------------------ TSX portfolio plans (D-055)

private val monthLabel = DateTimeFormatter.ofPattern("MMM yy", Locale.CANADA)

internal fun cad(v: Double?): String = v?.let { String.format(Locale.CANADA, "C$%,.0f", it) } ?: "—"

internal fun cad2(v: Double?): String = v?.let { String.format(Locale.CANADA, "C$%,.2f", it) } ?: "—"

internal fun pctText(v: Double?): String = v?.let { String.format(Locale.CANADA, "%+.1f%%", it) } ?: "—"

/** "2024-05" or "2024-05-31" as epoch millis, for the chart x axis. */
internal fun monthMillis(m: String): Long =
    LocalDate
        .parse(if (m.length == 7) "$m-01" else m.take(10))
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant()
        .toEpochMilli()

private val clockTime = DateTimeFormatter.ofPattern("HH:mm", Locale.CANADA)

/** An ISO instant as Toronto clock time, for intraday prices. */
internal fun liveTime(iso: String): String =
    runCatching {
        java.time.Instant
            .parse(iso)
            .atZone(java.time.ZoneId.of("America/Toronto"))
            .format(clockTime) + " Toronto"
    }.getOrDefault(iso)

private fun monthText(ms: Long): String =
    java.time.Instant
        .ofEpochMilli(ms)
        .atZone(ZoneOffset.UTC)
        .toLocalDate()
        .format(monthLabel)

private fun series(
    name: String,
    months: List<String>,
    values: List<Double>,
    color: Color,
) = LineSeries(name, months.zip(values).map { (m, v) -> LinePoint(monthMillis(m), v) }, color)

@Composable
fun TsxPlansScreen(
    vm: TsxPlansViewModel,
    nav: NavHostController,
) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val runs by vm.runs.collectAsStateWithLifecycle()
    val backtests by vm.backtests.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val started by vm.started.collectAsStateWithLifecycle()
    LaunchedEffect(started) {
        started?.let {
            vm.consumeStarted()
            nav.navigate("tsx-run/$it")
        }
    }
    AutoRefresh(TSX_REFRESH_MS) { vm.loadRuns() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("TSX plans")
        Text(
            "Canadian portfolio plans in C$, paper only. Each holds a basket of TSX stocks and ETFs at target weights, " +
                "re-weights on its schedule at the day's closing prices, and either reinvests dividends (DRIP) or pays them out as income.",
            style = MaterialTheme.typography.bodyMedium,
        )
        ActionFeedback(action)
        TsxDataCard(data, action == ActionState.Running, vm::refreshData)
        val active = runs.filter { it.status == "ACTIVE" }
        SectionTitle("Running TSX slots (${active.size} of ${catalog?.slots ?: 10})")
        if (runs.isEmpty()) {
            Text("No TSX plan is running. Start one from a plan below.", style = MaterialTheme.typography.bodyMedium)
        }
        runs.forEach { r -> TsxRunCard(r) { nav.navigate("tsx-run/${r.id}") } }
        SectionTitle("Built-in plans")
        val c = catalog
        if (c == null) {
            Loading()
        } else {
            c.plans.forEach { p ->
                TsxPlanCard(
                    plan = p,
                    benchmarks = c.benchmarks,
                    names = c.names,
                    usedSlots = active.map { it.slot }.toSet(),
                    slots = c.slots,
                    hasData = data?.latestDay != null,
                    backtest = backtests[p.id],
                    busy = action == ActionState.Running,
                    onStart = { slot, cash, drip, auto -> vm.start(p.id, slot, cash, drip, auto) },
                    onBacktest = { from, cash -> vm.backtest(p.id, from, cash) },
                )
            }
        }
        Text(
            "Research results use daily TSX prices from 2016 to 2026 with 0.1% trading costs. Plans were chosen on 2016–2021 and checked on 2022–2026, " +
                "which they had not seen. Past results do not predict future returns.",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
}

@Composable
fun TsxDataCard(
    data: TsxDataStatus?,
    busy: Boolean,
    onRefresh: () -> Unit,
) {
    SfCard(Modifier.testTag("tsx-data")) {
        Text("TSX prices", fontWeight = FontWeight.SemiBold)
        if (data == null) {
            Text("Checking…", style = MaterialTheme.typography.bodyMedium)
            return@SfCard
        }
        LabelValue("Symbols stored", "${data.cached} of ${data.listings}")
        LabelValue("Latest trading day", data.latestDay ?: "None yet")
        if (data.refreshing) {
            LabelValue("Downloading", "${data.done} of ${data.total}" + if (data.failed > 0) " (${data.failed} failed)" else "")
        }
        data.error?.let { Banner(it, BannerKind.WARNING) }
        if (data.latestDay == null) {
            Text("Download the price history once (about 10 years, a few minutes). After that it updates itself while a plan runs.", style = MaterialTheme.typography.bodySmall)
        }
        Button(onClick = onRefresh, enabled = !busy && !data.refreshing, modifier = Modifier.padding(top = 6.dp).testTag("tsx-refresh")) {
            Text(if (data.latestDay == null) "Download TSX prices" else "Update prices now")
        }
    }
}

@Composable
fun TsxRunCard(
    r: TsxRun,
    onOpen: () -> Unit,
) {
    SfCard(Modifier.testTag("tsx-run").clickable(onClick = onOpen)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Slot ${r.slot}: ${r.planName}", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            StatusChip(if (r.status == "ACTIVE" && r.mode == "AUTONOMOUS") "ACTIVE_AUTONOMOUS" else r.status)
        }
        LabelValue("Value at last close", cad(r.value))
        r.liveValue?.let { live -> LabelValue("Now (intraday)", "${cad(live)} · ${pctText(100 * (live / r.value - 1))} today", Modifier.testTag("tsx-live")) }
        LabelValue("Change since start", pctText(if (r.startingCash > 0) 100 * ((r.liveValue ?: r.value) / r.startingCash - 1) else null))
        LabelValue("Dividends received", cad2(r.dividendsReceived))
        LabelValue("Dividends", if (r.drip) "Reinvested (DRIP)" else "Paid out as cash")
        if (r.pending != null) Banner("A rebalance is waiting for your approval.", BannerKind.WARNING)
    }
}

@Composable
fun TsxPlanCard(
    plan: TsxPlan,
    benchmarks: Map<String, TsxBenchmark>,
    names: Map<String, String>,
    usedSlots: Set<Int>,
    slots: Int,
    hasData: Boolean,
    backtest: TsxBacktest?,
    busy: Boolean,
    onStart: (Int?, String, Boolean, Boolean) -> Unit,
    onBacktest: (String?, String) -> Unit,
) {
    var view by rememberSaveable(plan.id) { mutableStateOf("drip") }
    var showStart by rememberSaveable(plan.id) { mutableStateOf(false) }
    var showRules by rememberSaveable(plan.id) { mutableStateOf(false) }
    SfCard(Modifier.testTag("tsx-plan-${plan.id}")) {
        Text(plan.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(plan.horizon + " · rebalances " + plan.rebalance.lowercase(), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(plan.summary, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
        val r = plan.research
        LabelValue("Per year (10 years, DRIP)", pctText(r.perYear))
        LabelValue("Per year, chosen on 2016–21 / unseen 2022–26", "${pctText(r.selectionPerYear)} / ${pctText(r.unseenPerYear)}")
        LabelValue("Worst fall from a peak", pctText(r.maxDrawdown))
        r.positive12m?.let { LabelValue("12-month periods that gained", "${it.toInt()}%") }
        LabelValue("C$${"%,.0f".format(r.startingCash)} became (DRIP / paid out)", "${cad(r.endDrip)} / ${cad(r.endPaidOut)}")
        LabelValue("Dividends paid out (total / last 12 months)", "${cad(r.dividendsTotal)} / ${cad(r.dividendsLast12m)}")
        ChoiceRow(
            listOf("drip" to "DRIP growth", "income" to "Income view", "years" to "By year"),
            view,
            { view = it },
            "tsx-view-${plan.id}",
        )
        TsxResearchView(view, r, benchmarks)
        TextButton(onClick = { showRules = !showRules }) { Text(if (showRules) "Hide rules" else "Show rules and holdings") }
        if (showRules) {
            plan.rules.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (r.lastTargets.isNotEmpty()) {
                Text("Holdings at the end of the research", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                r.lastTargets.entries.sortedByDescending { it.value }.forEach { (s, w) ->
                    LabelValue(s.replace('-', '.') + (names[s]?.let { " · $it" } ?: ""), String.format(Locale.CANADA, "%.1f%%", w))
                }
            }
        }
        backtest?.let { TsxBacktestResult(it, benchmarks) }
        Row(Modifier.padding(top = 6.dp)) {
            Button(onClick = { showStart = !showStart }, enabled = hasData, modifier = Modifier.testTag("tsx-start-${plan.id}")) { Text(if (showStart) "Close" else "Run in a TSX slot") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { onBacktest(null, "10000") }, enabled = hasData && !busy, modifier = Modifier.testTag("tsx-backtest-${plan.id}")) { Text("Backtest on my data") }
        }
        if (!hasData) Text("Download TSX prices first.", style = MaterialTheme.typography.bodySmall)
        if (showStart) TsxStartForm(usedSlots, slots, busy) { slot, cash, drip, auto -> onStart(slot, cash, drip, auto) }
    }
}

@Composable
private fun TsxResearchView(
    view: String,
    r: TsxResearch,
    benchmarks: Map<String, TsxBenchmark>,
) {
    when (view) {
        "drip" -> {
            val lines =
                listOf(series("This plan", r.months, r.valueDrip, MaterialTheme.colorScheme.primary)) +
                    benchmarks.entries.mapIndexed { i, (k, b) ->
                        series("$k (${b.name.substringBefore(' ')})", r.months, b.valueDrip, if (i == 0) Sf.colors.neutral else MaterialTheme.colorScheme.tertiary)
                    }
            LineChart(
                lines,
                formatY = { cad(it) },
                formatX = ::monthText,
                description = "Value with dividends reinvested, ${cad(r.valueDrip.firstOrNull())} to ${cad(r.valueDrip.lastOrNull())}, compared with TSX index funds",
                baseline = r.startingCash,
            )
            ChartLegend(lines)
        }
        "income" -> {
            val line = listOf(series("Value, dividends paid out", r.months, r.valuePaidOut, MaterialTheme.colorScheme.primary))
            Text("Monthly value with dividends taken as cash", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            LineChart(line, formatY = { cad(it) }, formatX = ::monthText, description = "Monthly portfolio value with dividends paid out", height = 160.dp, baseline = r.startingCash)
            Text("Dividends paid each month", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            MonthlyBars(r.months, r.dividendsPaid, MaterialTheme.colorScheme.secondary)
            if (r.dividendYears.isNotEmpty()) {
                Text("Dividends per year", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                r.dividendYears.entries
                    .sortedBy { it.key }
                    .forEach { (y, a) -> LabelValue(y, cad(a)) }
            }
        }
        else -> {
            BarComparison(
                r.years.entries
                    .sortedBy { it.key }
                    .map { (y, v) -> BarItem(y, v, pctText(v)) },
                signed = true,
                modifier = Modifier.padding(vertical = 6.dp),
            )
            Text("2016 and the latest year are part years.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun TsxBacktestResult(
    b: TsxBacktest,
    benchmarks: Map<String, TsxBenchmark>,
) {
    Text("Backtest on this phone's data", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
    when (b.status) {
        "RUNNING" -> Text("Running…", style = MaterialTheme.typography.bodyMedium)
        "FAILED" -> Banner(b.error ?: "The backtest failed", BannerKind.ERROR)
        else ->
            b.result?.let { r ->
                LabelValue("Period", "${r.from ?: b.from} to ${r.to ?: b.to}")
                LabelValue("Per year (DRIP)", pctText(r.perYear))
                LabelValue("Total return", pctText(r.totalPercent))
                LabelValue("Worst fall from a peak", pctText(r.maxDrawdown))
                LabelValue("Ended with (DRIP / paid out)", "${cad(r.endDrip)} / ${cad(r.endPaidOut)}")
                LabelValue("Dividends paid out", cad(r.dividendsTotal))
                TsxResearchView("drip", r, emptyMap())
                MonthlyBars(r.months, r.dividendsPaid, MaterialTheme.colorScheme.secondary)
            }
    }
}

@Composable
private fun TsxStartForm(
    usedSlots: Set<Int>,
    slots: Int,
    busy: Boolean,
    onStart: (Int?, String, Boolean, Boolean) -> Unit,
) {
    val free = (1..slots).filter { it !in usedSlots }
    var slot by rememberSaveable { mutableStateOf(free.firstOrNull()) }
    var cash by rememberSaveable { mutableStateOf("10000") }
    var drip by rememberSaveable { mutableStateOf(true) }
    var auto by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp).testTag("tsx-start-form")) {
        if (free.isEmpty()) {
            Banner("All $slots TSX slots are in use. Stop a plan first.", BannerKind.WARNING)
            return@Column
        }
        Text("TSX slot", style = MaterialTheme.typography.labelLarge)
        ChoiceRow(free.map { it.toString() to "Slot $it" }, slot?.toString() ?: "", { slot = it.toInt() }, "tsx-slot")
        Field("Starting cash (C$)", cash, { cash = it.filter { c -> c.isDigit() || c == '.' } }, number = true)
        ToggleRow("Reinvest dividends (DRIP)", "Off: dividends are paid out as cash income.", drip) { drip = it }
        ToggleRow(
            "Autonomous",
            "On: rebalances happen on schedule without asking. Off: you get a notification and approve each rebalance.",
            auto,
        ) { auto = it }
        Button(onClick = { onStart(slot, cash, drip, auto) }, enabled = !busy && cash.toDoubleOrNull() != null, modifier = Modifier.testTag("tsx-start-confirm")) {
            Text("Start plan")
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    help: String,
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(help, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = on, onCheckedChange = onChange)
    }
}

/** Vertical bars, one per month (dividends paid each month). */
@Composable
fun MonthlyBars(
    months: List<String>,
    values: List<Double>,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 90.dp,
) {
    val n = minOf(months.size, values.size)
    if (n == 0) {
        Text("No dividends yet", style = MaterialTheme.typography.bodySmall)
        return
    }
    val max = values.take(n).maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val track = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(height)
                .testTag("monthly-bars")
                .semantics {
                    contentDescription = "Monthly dividends from ${months.first()} to ${months[n - 1]}, largest ${cad2(max)}, total ${cad2(values.take(n).sum())}"
                },
        ) {
            val w = size.width / n
            drawLine(track, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 2f)
            for (i in 0 until n) {
                val h = (values[i] / max * size.height).toFloat()
                if (h <= 0f) continue
                drawRect(color, Offset(i * w + w * 0.15f, size.height - h), Size((w * 0.7f).coerceAtLeast(1f), h))
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(monthText(monthMillis(months.first())), style = MaterialTheme.typography.labelSmall)
            Text("max ${cad2(max)}", style = MaterialTheme.typography.labelSmall)
            Text(monthText(monthMillis(months[n - 1])), style = MaterialTheme.typography.labelSmall)
        }
    }
}

// ------------------------------------------------------------------ one TSX run

@Composable
fun TsxRunScreen(vm: TsxRunViewModel) {
    val run by vm.run.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    AutoRefresh(TSX_REFRESH_MS) { vm.load() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        val r = run
        if (r == null) {
            ActionFeedback(action)
            Loading()
            return@Column
        }
        TsxRunDetail(r, action, vm::approve, vm::decline, vm::stop, vm::setMode)
    }
}

/** Stateless run detail, testable alone. */
@Composable
fun TsxRunDetail(
    r: TsxRun,
    action: ActionState,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onStop: () -> Unit,
    /** "autonomous" or "notify" (D-078). */
    onMode: (String) -> Unit = {},
) {
    var view by rememberSaveable { mutableStateOf("value") }
    SectionTitle("Slot ${r.slot}: ${r.planName}")
    StatusChip(if (r.status == "ACTIVE" && r.mode == "AUTONOMOUS") "ACTIVE_AUTONOMOUS" else r.status)
    ActionFeedback(action)
    SfCard {
        r.liveValue?.let { live ->
            LabelValue("Now (intraday)", cad2(live), Modifier.testTag("tsx-live"))
            LabelValue("Today", "${cad2(live - r.value)} (${pctText(100 * (live / r.value - 1))})")
            r.liveAt?.let { LabelValue("Prices from", liveTime(it)) }
        }
        LabelValue("Value at last close", cad2(r.value))
        LabelValue("Started with", cad2(r.startingCash) + (r.startDay?.let { " on $it" } ?: ""))
        LabelValue("Change since start", pctText(if (r.startingCash > 0) 100 * ((r.liveValue ?: r.value) / r.startingCash - 1) else null))
        LabelValue("Cash", cad2(r.cash))
        LabelValue("Dividends received", cad2(r.dividendsReceived))
        LabelValue("Dividends", if (r.drip) "Reinvested (DRIP)" else "Paid out as cash")
        LabelValue("Mode", if (r.mode == "AUTONOMOUS") "Autonomous" else "Notify and approve")
        if (r.status == "ACTIVE") {
            OutlinedButton(
                onClick = { onMode(if (r.mode == "AUTONOMOUS") "notify" else "autonomous") },
                enabled = action != ActionState.Running,
                modifier = Modifier.testTag("tsx-mode"),
            ) { Text(if (r.mode == "AUTONOMOUS") "Switch to notify and approve" else "Switch to autonomous") }
            if (r.mode != "AUTONOMOUS" && r.pending != null) {
                Text("Switching to autonomous also applies the rebalance waiting below.", style = MaterialTheme.typography.bodySmall)
            }
        }
        r.lastDay?.let { LabelValue("Closing prices as of", it) }
        Text(
            "Intraday values are for display and may be delayed up to 15 minutes. Dividends and rebalancing use closing prices, as in the research.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    r.pending?.let { p ->
        SfCard(Modifier.testTag("tsx-pending")) {
            Text("Rebalance waiting for approval" + (r.pendingDay?.let { " ($it)" } ?: ""), fontWeight = FontWeight.SemiBold)
            Text("Approving buys and sells to these weights at the latest closing prices.", style = MaterialTheme.typography.bodySmall)
            p.forEach { w -> LabelValue(w.symbol.replace('-', '.') + (w.name?.let { " · $it" } ?: ""), String.format(Locale.CANADA, "%.1f%%", w.weight * 100)) }
            Row(Modifier.padding(top = 6.dp)) {
                Button(onClick = onApprove, enabled = action != ActionState.Running, modifier = Modifier.testTag("tsx-approve")) { Text("Approve") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = onDecline, enabled = action != ActionState.Running, modifier = Modifier.testTag("tsx-decline")) { Text("Decline") }
            }
        }
    }
    ChoiceRow(listOf("value" to "Value", "income" to "Dividends by month"), view, { view = it }, "tsx-run-view")
    if (view == "value") {
        LineChart(
            listOf(LineSeries("Value", r.values.map { LinePoint(monthMillis(it.day), it.value) }, MaterialTheme.colorScheme.primary)),
            formatY = { cad(it) },
            formatX = ::monthText,
            description = "Daily value of this plan",
            baseline = r.startingCash,
        )
        if (r.monthlyValues.isNotEmpty()) {
            Text("Month-end values", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
            r.monthlyValues
                .takeLast(12)
                .reversed()
                .forEach { LabelValue(it.month, cad2(it.value)) }
        }
    } else {
        MonthlyBars(r.monthlyDividends.map { it.month }, r.monthlyDividends.map { it.amount }, MaterialTheme.colorScheme.secondary)
        r.monthlyDividends
            .takeLast(12)
            .reversed()
            .forEach { LabelValue(it.month, cad2(it.amount)) }
    }
    SectionTitle("Holdings")
    if (r.holdings.isEmpty()) Text("No holdings yet.", style = MaterialTheme.typography.bodyMedium)
    r.holdings.forEach { h ->
        SfCard {
            Text(h.symbol.replace('-', '.') + (h.name?.let { " · $it" } ?: ""), fontWeight = FontWeight.SemiBold)
            LabelValue("Shares", String.format(Locale.CANADA, "%,.3f", h.shares))
            LabelValue("Last close", cad2(h.price))
            h.livePrice?.let { p -> LabelValue("Now", "${cad2(p)} (${pctText(100 * (p / h.price - 1))})") }
            val v = h.shares * (h.livePrice ?: h.price)
            val total = r.liveValue ?: r.value
            LabelValue("Value", cad2(v) + if (total > 0) String.format(Locale.CANADA, " (%.1f%%)", 100 * v / total) else "")
        }
    }
    SectionTitle("Activity")
    if (r.events.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodyMedium)
    r.events.take(100).forEach { e ->
        val what =
            when (e.kind) {
                "BUY" -> "Bought ${String.format(Locale.CANADA, "%,.3f", e.shares ?: 0.0)} at ${cad2(e.price)}"
                "SELL" -> "Sold ${String.format(Locale.CANADA, "%,.3f", e.shares ?: 0.0)} at ${cad2(e.price)}"
                "DIVIDEND_REINVESTED" -> "Dividend ${cad2(e.amount)} reinvested"
                "DIVIDEND_PAID" -> "Dividend ${cad2(e.amount)} paid out"
                "REBALANCE_PROPOSED" -> "Rebalance proposed"
                "REBALANCE_APPROVED" -> "Rebalance approved"
                "REBALANCE_DECLINED" -> "Rebalance declined"
                "TAKEN_OVER_OR_DELISTED" -> "No longer trades; sold at last price ${cad2(e.price)}"
                else -> e.kind.lowercase().replace('_', ' ')
            }
        LabelValue(e.day + (e.symbol?.let { " · ${it.replace('-', '.')}" } ?: ""), what)
    }
    if (r.status == "ACTIVE") {
        var confirm by rememberSaveable { mutableStateOf(false) }
        OutlinedButton(onClick = { confirm = true }, enabled = action != ActionState.Running, modifier = Modifier.padding(vertical = 12.dp).testTag("tsx-stop")) { Text("Stop this plan") }
        if (confirm) {
            AlertDialog(
                onDismissRequest = { confirm = false },
                title = { Text("Stop this plan?") },
                text = { Text("It stops following its schedule and frees TSX slot ${r.slot}. Its history stays here.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirm = false
                        onStop()
                    }) { Text("Stop") }
                },
                dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep running") } },
            )
        }
    }
}
