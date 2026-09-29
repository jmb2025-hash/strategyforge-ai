package app.strategyforge.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.CandleChart
import app.strategyforge.android.core.model.ChartPoint
import app.strategyforge.android.core.model.EquityChart
import app.strategyforge.android.core.model.Scorecard

// ------------------------------------------------------------------ chart glue (D-038)

val TIMEFRAMES = listOf("1m" to "1m", "5m" to "5m", "15m" to "15m", "1h" to "1H", "4h" to "4H", "1d" to "1D")
val RANGES = listOf("1D" to "1D", "1W" to "1W", "1M" to "1M", "3M" to "3M", "ALL" to "All")

fun List<ChartPoint>.toLine(fmt: Formatters): List<LinePoint> =
    mapNotNull { p ->
        val x = fmt.epochMillis(p.at) ?: return@mapNotNull null
        val y = p.value.toDoubleOrNull() ?: return@mapNotNull null
        LinePoint(x, y)
    }

fun CandleChart.toBars(fmt: Formatters): List<CandleBar> =
    bars.mapNotNull { b ->
        val t = fmt.epochMillis(b.t) ?: return@mapNotNull null
        CandleBar(t, b.o.toDouble(), b.h.toDouble(), b.l.toDouble(), b.c.toDouble(), b.v.toDoubleOrNull() ?: 0.0)
    }

fun CandleChart.toMarkers(fmt: Formatters): List<ChartMarker> =
    trades.mapNotNull { t ->
        val at = fmt.epochMillis(t.at) ?: return@mapNotNull null
        val buy = t.side == "BUY" || t.side == "BUY_TO_COVER"
        val verb =
            when (t.side) {
                "BUY" -> "Bought"
                "SELL" -> "Sold"
                "SELL_SHORT" -> "Shorted"
                "BUY_TO_COVER" -> "Covered"
                else -> t.side
            }
        ChartMarker(at, t.price.toDouble(), buy, "$verb ${fmt.quantity(t.quantity)} @ ${fmt.chartNumber(t.price.toDouble())}")
    }

private fun axisTime(
    fmt: Formatters,
    xs: List<Long>,
): (Long) -> String {
    val span = if (xs.size < 2) 0L else xs.max() - xs.min()
    return { fmt.chartTime(it, span) }
}

/** Portfolio value over a chosen range, with the change over that range. */
@Composable
fun EquityCard(
    chart: EquityChart?,
    range: String,
    onRange: (String) -> Unit,
    fmt: Formatters,
    equityNow: String?,
    name: String,
) {
    val line = chart?.points?.toLine(fmt).orEmpty()
    val gain = (chart?.change?.toDoubleOrNull() ?: 0.0) >= 0
    val color = if (gain) Sf.colors.gain else Sf.colors.loss
    HeroCard(
        label = name,
        value = fmt.money(equityNow),
        modifier = Modifier.testTag("equity-card"),
        change = { if (chart?.change != null) ChangePill(chart.change, chart.changePercent, fmt, suffix = " · ${RANGES.firstOrNull { it.first == range }?.second ?: range}") },
    ) {
        Spacer(Modifier.padding(top = 10.dp))
        if (chart == null) {
            Loading()
        } else {
            val first = line.firstOrNull()?.y
            LineChart(
                listOf(LineSeries("Equity", line, color)),
                formatY = fmt::chartNumber,
                formatX = axisTime(fmt, line.map { it.x }),
                description =
                    if (line.size < 2) {
                        "Portfolio value chart: not enough history yet"
                    } else {
                        "Portfolio value chart for $range: from ${fmt.money(line.first().y.toString())} to ${fmt.money(line.last().y.toString())}"
                    },
                height = 180.dp,
                baseline = first,
                modifier = Modifier.testTag("equity-chart"),
            )
        }
        ChoiceRow(RANGES, range, onRange, "range")
    }
}

/** Candlesticks for one symbol with the trades on it; timeframe pills underneath. */
@Composable
fun PriceChartCard(
    chart: CandleChart?,
    timeframe: String,
    onTimeframe: (String) -> Unit,
    fmt: Formatters,
    error: String? = null,
    title: String? = null,
    height: androidx.compose.ui.unit.Dp = 300.dp,
) {
    SfCard(Modifier.testTag("price-card")) {
        val bars = chart?.toBars(fmt).orEmpty()
        val markers = chart?.toMarkers(fmt).orEmpty()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title ?: chart?.symbol ?: "", style = MaterialTheme.typography.titleMedium)
                bars.lastOrNull()?.let { last ->
                    val first = bars.first()
                    val change = last.c - first.o
                    val pct = if (first.o != 0.0) change / first.o * 100 else 0.0
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(fmt.chartNumber(last.c), style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(8.dp))
                        ChangePill(
                            java.math
                                .BigDecimal(change)
                                .setScale(2, java.math.RoundingMode.HALF_EVEN)
                                .toPlainString(),
                            String.format(java.util.Locale.US, "%.2f", pct),
                            fmt,
                        )
                    }
                }
            }
        }
        if (markers.isNotEmpty()) {
            val buys = markers.count { it.buy }
            Text("▲ $buys buy · ▼ ${markers.size - buys} sell on this chart", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.padding(top = 6.dp))
        when {
            error != null && chart == null -> Banner(error, BannerKind.WARNING)
            chart == null -> Loading()
            else ->
                CandlestickChart(
                    bars,
                    markers,
                    formatY = fmt::chartNumber,
                    formatX = axisTime(fmt, bars.map { it.t }),
                    description =
                        if (bars.isEmpty()) {
                            "${chart.symbol} price chart: no data"
                        } else {
                            "${chart.symbol} ${chart.timeframe} candlestick chart, ${bars.size} bars, last ${fmt.chartNumber(bars.last().c)}, " +
                                "high ${fmt.chartNumber(bars.maxOf { it.h })}, low ${fmt.chartNumber(bars.minOf { it.l })}, ${markers.size} trades marked"
                        },
                    height = height,
                )
        }
        if (chart != null && chart.status != "VERIFIED" && chart.detail.isNotBlank() && chart.detail != "OK") {
            Text(chart.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ChoiceRow(TIMEFRAMES, timeframe, onTimeframe, "tf")
    }
}

/** Full-screen price chart for one symbol (from a position or a strategy). */
@Composable
fun ChartScreen(
    vm: ChartViewModel,
    fmt: Formatters,
) {
    val chart by vm.chart.collectAsStateWithLifecycle()
    val tf by vm.timeframe.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        PriceChartCard(chart, tf, vm::setTimeframe, fmt, error, title = vm.symbol, height = 380.dp)
        Text(
            "Drag across the chart to read prices. ▲ marks simulated buys and ▼ simulated sells. Prices refresh every 20 seconds while this screen is open.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
    }
}

// ------------------------------------------------------------------ strategy comparison

val COMPARE_METRICS =
    listOf(
        "pnl" to "Paper P&L",
        "win" to "Win rate",
        "bt" to "Backtest return",
        "dd" to "Backtest drawdown",
        "pf" to "Profit factor",
    )

/** Side-by-side bars for one measure and cumulative paper P&L lines for every strategy. */
@Composable
fun ComparisonCharts(
    cards: List<Scorecard>,
    fmt: Formatters,
) {
    var metric by rememberSaveable { mutableStateOf("pnl") }
    SfCard(Modifier.testTag("comparison")) {
        Text("Compare", style = MaterialTheme.typography.titleMedium)
        ChoiceRow(COMPARE_METRICS, metric, { metric = it }, "metric")
        val items =
            cards.map { c ->
                val (v, text) =
                    when (metric) {
                        "pnl" -> c.live.realizedPnl.toDoubleOrNull() to fmt.pnl(c.live.realizedPnl).text
                        "win" -> c.live.winRatePercent?.toDoubleOrNull() to fmt.percent(c.live.winRatePercent)
                        "bt" -> c.backtest?.netReturnPercent?.toDoubleOrNull() to fmt.percent(c.backtest?.netReturnPercent)
                        "dd" ->
                            c.backtest
                                ?.maxDrawdownPercent
                                ?.toDoubleOrNull()
                                ?.let { -kotlin.math.abs(it) } to fmt.percent(c.backtest?.maxDrawdownPercent)
                        else -> c.backtest?.profitFactor?.toDoubleOrNull() to (c.backtest?.profitFactor?.let { fmt.quantity(it) } ?: "—")
                    }
                BarItem(c.strategy.name, v, if (v == null) "no data" else text)
            }
        BarComparison(items, signed = metric in setOf("pnl", "bt", "dd"))
        val colors = Sf.colors.series
        val lines =
            cards
                .filter { it.live.pnlSeries.isNotEmpty() }
                .mapIndexed { i, c -> LineSeries(c.strategy.name, c.live.pnlSeries.toLine(fmt), colors[i % colors.size]) }
        if (lines.isNotEmpty()) {
            Text("Cumulative paper P&L", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))
            val xs = lines.flatMap { l -> l.points.map { it.x } }
            LineChart(
                lines,
                formatY = fmt::chartNumber,
                formatX = axisTime(fmt, xs),
                description = "Cumulative paper profit and loss: " + cards.joinToString { "${it.strategy.name} ${fmt.money(it.live.realizedPnl)}" },
                height = 200.dp,
                baseline = 0.0,
                modifier = Modifier.testTag("pnl-lines"),
            )
            ChartLegend(lines)
        }
    }
}
