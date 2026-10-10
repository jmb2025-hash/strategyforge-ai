package app.strategyforge.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.strategyforge.android.core.data.Repository
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.HoldingChart
import app.strategyforge.android.core.model.PortfolioSummary
import app.strategyforge.android.core.model.Position
import app.strategyforge.android.core.state.HoldingStats
import app.strategyforge.android.core.state.Holdings
import app.strategyforge.android.core.state.toFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import javax.inject.Inject

// ------------------------------------------------------------------ holdings like a stock app (D-080)

/** "+$1,234.00 (+5.20%)", "-$12.00 (-0.40%)" or "$0.00 (0.00%)"; the percent is left out when unknown. */
fun gainLabel(
    gain: BigDecimal?,
    percent: BigDecimal?,
    fmt: Formatters,
): String {
    if (gain == null) return "—"
    val money = (if (gain.signum() > 0) "+" else "") + fmt.money(gain.toPlainString())
    val pct = percent?.let { " (" + (if (it.signum() > 0) "+" else "") + fmt.percent(it.toPlainString()) + ")" } ?: ""
    return money + pct
}

/** What one unit is called: shares for stocks, the coin for crypto (BTC for BTC-USD). */
fun unitOf(symbol: String): String = if (symbol.endsWith("-USD")) symbol.removeSuffix("-USD") else "shares"

@Composable
private fun gainColor(v: BigDecimal?): Color =
    when (v?.signum()) {
        1 -> Sf.colors.gain
        -1 -> Sf.colors.loss
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

/** A gain or loss with its percent, in the gain or loss colour. */
@Composable
fun GainText(
    gain: BigDecimal?,
    percent: BigDecimal?,
    fmt: Formatters,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    modifier: Modifier = Modifier,
) {
    Text(gainLabel(gain, percent, fmt), style = style, color = gainColor(gain), fontWeight = FontWeight.Medium, modifier = modifier)
}

/** A label on the left and a coloured gain or loss on the right. */
@Composable
fun GainRow(
    label: String,
    gain: BigDecimal?,
    percent: BigDecimal?,
    fmt: Formatters,
    tag: String? = null,
) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        GainText(gain, percent, fmt, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
    }
}

/**
 * The whole portfolio in stock-app terms (D-080): what the holdings cost and are worth now and the
 * difference, profit already taken, cash, and the return since the start.
 */
@Composable
fun PortfolioTotalsCard(
    s: PortfolioSummary,
    fmt: Formatters,
) {
    val t = Holdings.totals(s)
    SfCard(Modifier.testTag("portfolio-totals")) {
        Text("Portfolio", style = MaterialTheme.typography.titleMedium)
        LabelValue("Total value (holdings + cash)", fmt.money(s.equity))
        GainRow("Total return since the start", fmt.decimal(s.totalReturn), fmt.decimal(s.totalReturnPercent), fmt, "total-return")
        Spacer(Modifier.padding(top = 6.dp))
        Text("Holdings", style = MaterialTheme.typography.titleSmall)
        LabelValue("You paid", fmt.money(t.paid.toPlainString()))
        LabelValue("Worth now", fmt.money(t.value.toPlainString()))
        GainRow("Gain / loss on holdings", t.gain, t.gainPercent, fmt, "holdings-gain")
        GainRow("Profit taken on sales", fmt.decimal(s.realizedPnl), null, fmt)
        if (!t.complete) Text("Some prices are missing, so these totals leave them out.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.padding(top = 6.dp))
        Text("Cash", style = MaterialTheme.typography.titleSmall)
        LabelValue("Cash", fmt.money(s.cash))
        LabelValue("Reserved for orders", fmt.money(s.reservedCash))
        LabelValue("Buying power", fmt.money(s.buyingPower))
        LabelValue("Fees paid", fmt.money(s.fees))
        LabelValue("As of", fmt.dateTime(s.asOf))
        if (s.portfolio.reconciliationStatus != "OK") Banner("Reconciliation ${s.portfolio.reconciliationStatus}: orders are blocked until it passes.", BannerKind.ERROR)
    }
}

/** One holding in the list (D-080): shares and price, worth now, gain or loss, and its share of the portfolio. */
@Composable
fun HoldingRow(
    h: HoldingStats,
    position: Position,
    fmt: Formatters,
    onClick: () -> Unit,
) {
    SfCard(Modifier.clickable(onClick = onClick).testTag("holding-${h.symbol}")) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(h.symbol + if (h.short) " · short" else "", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (position.priceStatus != "VERIFIED") {
                        Spacer(Modifier.width(6.dp))
                        StatusChip(if (position.priceStatus == "STALE") "Stale data" else position.priceStatus)
                    }
                }
                h.name?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text(
                    "${fmt.quantity(h.shares.toPlainString())} ${unitOf(h.symbol)} · ${fmt.money(h.price?.toPlainString())} each",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(fmt.money(h.value?.toPlainString()), style = MaterialTheme.typography.titleSmall)
                GainText(h.gain, h.gainPercent, fmt, MaterialTheme.typography.bodySmall)
                h.weightPercent?.let { Text("${fmt.percent(it.toPlainString())} of portfolio", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Text(
            "Paid ${fmt.money(h.paid?.toPlainString())} (avg ${fmt.money(h.averageCost?.toPlainString())}) · now worth ${fmt.money(h.value?.toPlainString())} ›",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp),
        )
        if (position.manualReviewRequired) Banner("Manual Review Required: a corporate action could not be verified.", BannerKind.WARNING)
    }
}

@HiltViewModel
class HoldingViewModel
    @Inject
    constructor(
        private val repo: Repository,
        saved: SavedStateHandle,
    ) : ViewModel() {
        val symbol: String = checkNotNull(saved["symbol"])
        val portfolioId: String = checkNotNull(saved["portfolioId"])
        private val _range = MutableStateFlow("1M")
        val range: StateFlow<String> = _range.asStateFlow()
        private val _chart = MutableStateFlow<HoldingChart?>(null)

        /** The holding's value and cost over [range], and its trades. */
        val chart: StateFlow<HoldingChart?> = _chart.asStateFlow()
        private val _summary = MutableStateFlow<PortfolioSummary?>(null)

        /** The portfolio now, for the position's live numbers and its share of the portfolio. */
        val summary: StateFlow<PortfolioSummary?> = _summary.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()

        fun load() {
            val r = _range.value
            viewModelScope.launch {
                runCatching { repo.holding(portfolioId, symbol, r) }
                    .onSuccess {
                        if (_range.value == r) _chart.value = it
                        _error.value = null
                    }.onFailure { _error.value = it.toFailure().message }
                runCatching { repo.portfolioSummaryNow(portfolioId) }.onSuccess { _summary.value = it }
            }
        }

        fun setRange(r: String) {
            if (r == _range.value) return
            _range.value = r
            _chart.value = null
            load()
        }
    }

@Composable
fun HoldingScreen(
    vm: HoldingViewModel,
    fmt: Formatters,
    onTrade: (side: String, symbol: String) -> Unit,
    onChart: (symbol: String, portfolioId: String) -> Unit,
    onInfo: (symbol: String, portfolioId: String) -> Unit,
) {
    val chart by vm.chart.collectAsStateWithLifecycle()
    val summary by vm.summary.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    AutoRefresh(INFO_REFRESH_MS) { vm.load() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        HoldingContent(
            vm.symbol,
            chart,
            summary,
            range,
            vm::setRange,
            fmt,
            onTrade,
            { onChart(vm.symbol, vm.portfolioId) },
            { onInfo(vm.symbol, vm.portfolioId) },
        )
        error?.let { Banner("Could not load the history: $it", BannerKind.WARNING) }
    }
}

/**
 * One holding (D-080): what it is worth against what was paid over time, the stock-app numbers
 * (shares, average cost, price, paid, worth now, gain or loss, profit taken, share of portfolio),
 * buy and sell buttons, and every trade in it.
 */
@Composable
fun HoldingContent(
    symbol: String,
    chart: HoldingChart?,
    summary: PortfolioSummary?,
    range: String,
    onRange: (String) -> Unit,
    fmt: Formatters,
    onTrade: (side: String, symbol: String) -> Unit,
    onPriceChart: () -> Unit,
    onAbout: () -> Unit,
) {
    val position = summary?.positions?.firstOrNull { it.symbol == symbol }
    val h = position?.let { Holdings.stats(it, summary?.equity) }
    val name = chart?.name ?: position?.name
    val worth = h?.value ?: BigDecimal.ZERO
    HeroCard(
        label = symbol + (name?.let { " · $it" } ?: ""),
        value = fmt.money(worth.toPlainString()),
        modifier = Modifier.testTag("holding-hero"),
        change = { if (h != null) GainText(h.gain, h.gainPercent, fmt, modifier = Modifier.testTag("holding-gain")) },
    ) {
        Text(
            if (h == null) "You no longer hold $symbol in this portfolio." else "What your ${fmt.quantity(h.shares.toPlainString())} ${unitOf(symbol)} are worth, against what you paid for them.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.padding(top = 8.dp))
        if (chart == null) {
            Loading()
        } else {
            val worthLine = chart.points.mapNotNull { p -> fmt.epochMillis(p.at)?.let { x -> p.value.toDoubleOrNull()?.let { LinePoint(x, kotlin.math.abs(it)) } } }
            val paidLine = chart.points.mapNotNull { p -> fmt.epochMillis(p.at)?.let { x -> p.cost.toDoubleOrNull()?.let { LinePoint(x, kotlin.math.abs(it)) } } }
            val up = (chart.gainChange?.toBigDecimalOrNull()?.signum() ?: 0) >= 0
            LineChart(
                listOf(LineSeries("Worth", worthLine, if (up) Sf.colors.gain else Sf.colors.loss), LineSeries("Paid", paidLine, Sf.colors.neutral)),
                formatY = fmt::chartNumber,
                formatX = { fmt.chartTime(it, (worthLine.lastOrNull()?.x ?: 0L) - (worthLine.firstOrNull()?.x ?: 0L)) },
                description = "Value of your $symbol against what you paid, over ${RANGES.firstOrNull { it.first == range }?.second ?: range}",
                height = 200.dp,
                modifier = Modifier.testTag("holding-chart"),
            )
            ChartLegend(listOf(LineSeries("Worth", worthLine, if (up) Sf.colors.gain else Sf.colors.loss), LineSeries("Paid", paidLine, Sf.colors.neutral)))
            chart.gainChange?.let {
                GainRow("Gain / loss change over ${RANGES.firstOrNull { r -> r.first == range }?.second ?: range}", fmt.decimal(it), null, fmt, "range-gain")
            }
            chart.priceChangePercent?.let { LabelValue("$symbol price change over the same time", (if (it.toBigDecimal().signum() > 0) "+" else "") + fmt.percent(it)) }
        }
        ChoiceRow(RANGES, range, onRange, "holding-range")
    }
    SfCard(Modifier.testTag("holding-numbers")) {
        Text("Your position", style = MaterialTheme.typography.titleMedium)
        if (h != null) {
            LabelValue(if (h.short) "Shorted" else "You own", "${fmt.quantity(h.shares.toPlainString())} ${unitOf(symbol)}")
            LabelValue("Average cost", fmt.money(h.averageCost?.toPlainString()))
            LabelValue("Price now", fmt.money(h.price?.toPlainString()))
            LabelValue(if (h.short) "You sold them for" else "You paid", fmt.money(h.paid?.toPlainString()))
            LabelValue(if (h.short) "Buying them back costs" else "Worth now", fmt.money(h.value?.toPlainString()))
            GainRow("Gain / loss", h.gain, h.gainPercent, fmt, "position-gain")
            h.weightPercent?.let { LabelValue("Share of the portfolio", fmt.percent(it.toPlainString())) }
            position?.priceTimestamp?.let { LabelValue("Price time", fmt.dateTime(it)) }
        }
        val realized = chart?.realizedPnl?.toBigDecimalOrNull()
        GainRow("Profit taken on sales", realized, null, fmt, "realized")
        val open = h?.gain
        if (open != null && realized != null) GainRow("Total gain / loss (open + taken)", open.add(realized), null, fmt, "total-gain")
        chart?.fees?.let { LabelValue("Commissions paid", fmt.money(it)) }
        chart?.firstBoughtAt?.let { LabelValue("First bought", fmt.dateTime(it)) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        Button(onClick = { onTrade("BUY", symbol) }, modifier = Modifier.weight(1f).testTag("holding-buy")) { Text("Buy more") }
        OutlinedButton(onClick = { onTrade("SELL", symbol) }, enabled = h != null && !h.short, modifier = Modifier.weight(1f).testTag("holding-sell")) { Text("Sell") }
    }
    Row {
        TextButton(onClick = onPriceChart, modifier = Modifier.testTag("holding-price-chart")) { Text("Price chart with trades ›") }
        TextButton(onClick = onAbout) { Text("About $symbol ›") }
    }
    SectionTitle("Trades")
    val trades = chart?.trades.orEmpty()
    if (chart != null && trades.isEmpty()) Text("No trades yet.")
    trades.forEach { t ->
        val verb =
            when (t.side) {
                "BUY" -> "Bought"
                "SELL" -> "Sold"
                "SELL_SHORT" -> "Shorted"
                "BUY_TO_COVER" -> "Covered"
                else -> t.side
            }
        SfCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("$verb ${fmt.quantity(t.quantity)} @ ${fmt.money(t.price)}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text(
                    fmt.money(
                        t.quantity
                            .toBigDecimalOrNull()
                            ?.multiply(t.price.toBigDecimalOrNull() ?: BigDecimal.ZERO)
                            ?.toPlainString(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(fmt.dateTime(t.at) + " · " + if (t.strategyId != null) "by the plan" else "by you", style = MaterialTheme.typography.bodySmall)
            t.realizedPnl
                .toBigDecimalOrNull()
                ?.takeIf { it.signum() != 0 }
                ?.let { GainRow("Profit on this sale", it, null, fmt) }
        }
    }
}
