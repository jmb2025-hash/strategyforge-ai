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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.strategyforge.android.core.data.Repository
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.InstrumentHit
import app.strategyforge.android.core.model.InstrumentInfo
import app.strategyforge.android.core.model.Position
import app.strategyforge.android.core.state.toFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.math.RoundingMode
import javax.inject.Inject

// ------------------------------------------------------------------ symbol search (D-065)

/**
 * The order screen's symbol field: as the owner types, matching symbols appear underneath with
 * their name and last price. Tapping one picks it; "Info" opens its information screen.
 */
@Composable
fun SymbolField(
    value: String,
    onChange: (String) -> Unit,
    suggestions: List<InstrumentHit>,
    onPick: (InstrumentHit) -> Unit,
    onInfo: (String) -> Unit,
    fmt: Formatters,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    Column(modifier) {
        Field("Symbol or name (for example Apple, AAPL, Bitcoin)", value, onChange, Modifier.testTag("symbol-field"), error = error)
        // Hidden once the text is exactly a suggestion the owner picked.
        val shown = suggestions.takeUnless { s -> s.size == 1 && s.first().symbol == value }.orEmpty()
        if (shown.isNotEmpty()) {
            SfCard(Modifier.testTag("symbol-suggestions")) {
                shown.forEachIndexed { i, h ->
                    if (i > 0) HorizontalDivider()
                    SuggestionRow(h, fmt, { onPick(h) }, { onInfo(h.symbol) })
                }
            }
        }
    }
}

@Composable
private fun SuggestionRow(
    h: InstrumentHit,
    fmt: Formatters,
    onPick: () -> Unit,
    onInfo: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 6.dp)
            .testTag("suggestion-${h.symbol}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(h.symbol, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                h.lastPrice?.let { Text(fmt.money(it), style = MaterialTheme.typography.bodySmall) }
            }
            Text(h.name, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            Text(hitKind(h), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        TextButton(onClick = onInfo, modifier = Modifier.testTag("info-${h.symbol}")) { Text("Info") }
    }
}

/** "Crypto", or "NASDAQ · Technology", plus a note for stocks the app adds when picked. */
fun hitKind(h: InstrumentHit): String {
    val base = if (h.assetClass == "CRYPTO") "Crypto" else listOfNotNull(h.exchange, h.sector).joinToString(" · ").ifBlank { "US stock" }
    return when {
        !h.active -> "$base · not available for trading"
        !h.added -> "$base · added when you trade it"
        else -> base
    }
}

// ------------------------------------------------------------------ symbol information (D-065)

val INFO_RANGES = listOf("1D" to "1D", "5D" to "5D", "1M" to "1M", "6M" to "6M", "1Y" to "1Y", "5Y" to "5Y")

/** How often the information screen re-reads prices. */
const val INFO_REFRESH_MS = 15_000L

@HiltViewModel
class InstrumentViewModel
    @Inject
    constructor(
        private val repo: Repository,
        saved: SavedStateHandle,
    ) : ViewModel() {
        val symbol: String = checkNotNull(saved["symbol"])
        private val portfolioId: String? = saved["portfolioId"]
        private val _range = MutableStateFlow("1M")
        val range: StateFlow<String> = _range.asStateFlow()
        private val _info = MutableStateFlow<InstrumentInfo?>(null)
        val info: StateFlow<InstrumentInfo?> = _info.asStateFlow()
        private val _position = MutableStateFlow<Position?>(null)

        /** The owner's position in this symbol in the portfolio the screen was opened from. */
        val position: StateFlow<Position?> = _position.asStateFlow()
        private val _error = MutableStateFlow<String?>(null)
        val error: StateFlow<String?> = _error.asStateFlow()
        private var first = true

        fun load() {
            val r = _range.value
            val refresh = first
            first = false
            viewModelScope.launch {
                runCatching { repo.instrumentInfo(symbol, r, refresh) }
                    .onSuccess {
                        if (_range.value == r) _info.value = it
                        _error.value = null
                    }.onFailure { _error.value = it.toFailure().message }
                portfolioId?.let { id -> runCatching { repo.portfolioSummaryNow(id) }.onSuccess { s -> _position.value = s.positions.firstOrNull { it.symbol == symbol } } }
            }
        }

        fun setRange(r: String) {
            if (r == _range.value) return
            _range.value = r
            load()
        }
    }

@Composable
fun InstrumentScreen(
    vm: InstrumentViewModel,
    fmt: Formatters,
    onTrade: (side: String, symbol: String) -> Unit,
    onCandles: (String) -> Unit,
) {
    val info by vm.info.collectAsStateWithLifecycle()
    val range by vm.range.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val position by vm.position.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) { vm.load() }
    AutoRefresh(INFO_REFRESH_MS) { vm.load() }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        val i = info
        if (i == null) {
            if (error != null) Banner(error!!, BannerKind.ERROR) else Loading()
            return@Column
        }
        InstrumentContent(i, range, vm::setRange, fmt, position, onTrade, onCandles)
        error?.let { Banner("Could not refresh: $it", BannerKind.WARNING) }
    }
}

@Composable
fun InstrumentContent(
    i: InstrumentInfo,
    range: String,
    onRange: (String) -> Unit,
    fmt: Formatters,
    position: Position?,
    onTrade: (side: String, symbol: String) -> Unit,
    onCandles: (String) -> Unit,
) {
    val h = i.instrument
    val line = i.points.toLine(fmt)
    val first = line.firstOrNull()?.y
    val last = i.price?.toDoubleOrNull() ?: line.lastOrNull()?.y
    // Over one day the change is from yesterday's close; over longer ranges, from the range's start.
    val base = if (range == "1D") i.previousClose?.toDoubleOrNull() ?: first else first
    val change = if (last != null && base != null && base != 0.0) last - base else null
    val gain = (change ?: 0.0) >= 0
    HeroCard(
        label = "${h.symbol} · ${h.name}",
        value = i.price?.let { fmt.money(it) } ?: "—",
        modifier = Modifier.testTag("instrument-hero"),
        change = {
            if (change != null) {
                ChangePill(
                    BigDecimal(change).setScale(2, RoundingMode.HALF_EVEN).toPlainString(),
                    String.format(java.util.Locale.US, "%.2f", change / base!! * 100),
                    fmt,
                    suffix = " · " + (if (range == "1D") "today" else range),
                )
            }
        },
    ) {
        Text(hitKind(h), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        h.industry?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Spacer(Modifier.padding(top = 10.dp))
        LineChart(
            listOf(LineSeries(h.symbol, line, if (gain) Sf.colors.gain else Sf.colors.loss)),
            formatY = fmt::chartNumber,
            formatX = { x -> fmt.chartTime(x, if (line.size < 2) 0L else line.last().x - line.first().x) },
            description =
                if (line.size < 2) {
                    "${h.symbol} price chart: not enough data"
                } else {
                    "${h.symbol} price over $range: from ${fmt.chartNumber(line.first().y)} to ${fmt.chartNumber(line.last().y)}"
                },
            height = 200.dp,
            baseline = base,
            modifier = Modifier.testTag("instrument-chart"),
        )
        ChoiceRow(INFO_RANGES, range, onRange, "range")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        Button(onClick = { onTrade("BUY", h.symbol) }, enabled = h.active, modifier = Modifier.weight(1f).testTag("info-buy")) { Text("Buy") }
        OutlinedButton(onClick = { onTrade("SELL", h.symbol) }, enabled = h.active, modifier = Modifier.weight(1f).testTag("info-sell")) { Text("Sell") }
    }
    position?.let { p ->
        SfCard(Modifier.testTag("instrument-position")) {
            Text("Your position", style = MaterialTheme.typography.titleSmall)
            LabelValue("Quantity", "${fmt.quantity(p.quantity)} · ${p.side.lowercase()}")
            LabelValue("Average cost", fmt.money(p.averageCost))
            LabelValue("Market value", fmt.money(p.marketValue))
            PnlValue("Unrealized", p.unrealizedPnl, fmt)
        }
    }
    SfCard(Modifier.testTag("instrument-stats")) {
        Text("Key numbers", style = MaterialTheme.typography.titleSmall)
        LabelValue("Previous close", i.previousClose?.let { fmt.money(it) } ?: "—")
        LabelValue("Day range", span(i.dayLow, i.dayHigh, fmt))
        LabelValue("52-week range", span(i.yearLow, i.yearHigh, fmt))
        i.volume?.let { LabelValue("Volume today", volume(it)) }
        i.exchange?.let { LabelValue("Exchange", it) }
        i.currency?.let { LabelValue("Currency", it) }
        i.marketTime?.let { LabelValue("Price time", fmt.dateTime(it)) }
    }
    SfCard {
        Text("Paper trading price", style = MaterialTheme.typography.titleSmall)
        if (i.tradingPrice != null) {
            LabelValue("Orders fill near", fmt.money(i.tradingPrice))
            i.tradingSource?.let { LabelValue("From", sourceLabel(it)) }
            i.tradingPriceAt?.let { LabelValue("As of", fmt.dateTime(it)) }
        } else {
            Text(
                if (h.added) "No trading price yet. One is fetched when you place an order." else "Added to the app when you first trade it; its trading price is fetched then.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            "Prices, ranges and the chart above come from Yahoo Finance and are for information. Paper orders fill at the trading price from the app's market data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        if (h.added) TextButton(onClick = { onCandles(h.symbol) }, modifier = Modifier.testTag("info-candles")) { Text("Candles and my trades ›") }
    }
}

private fun span(
    low: String?,
    high: String?,
    fmt: Formatters,
): String = if (low == null || high == null) "—" else "${fmt.money(low)} – ${fmt.money(high)}"

/** 126384437 → "126.4M". */
fun volume(v: String): String {
    val d = v.toDoubleOrNull() ?: return v
    return when {
        d >= 1e9 -> String.format(java.util.Locale.US, "%.1fB", d / 1e9)
        d >= 1e6 -> String.format(java.util.Locale.US, "%.1fM", d / 1e6)
        d >= 1e3 -> String.format(java.util.Locale.US, "%.1fK", d / 1e3)
        else -> String.format(java.util.Locale.US, "%.0f", d)
    }
}

private fun sourceLabel(provider: String): String =
    when (provider) {
        "COINBASE" -> "Coinbase"
        "TWELVE_DATA" -> "Twelve Data"
        "REPLAY" -> "Demo replay data"
        else -> provider.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
