package app.strategyforge.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.strategyforge.android.core.data.Repository
import app.strategyforge.android.core.model.LimitValues
import app.strategyforge.android.core.model.PlanLimits
import app.strategyforge.android.core.model.RiskLimitsInfo
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ------------------------------------------------------------------ risk limits (D-063)

@HiltViewModel
class RiskLimitsViewModel
    @Inject
    constructor(
        private val repo: Repository,
    ) : AccountViewModel() {
        private val _info = MutableStateFlow<RiskLimitsInfo?>(null)
        val info: StateFlow<RiskLimitsInfo?> = _info.asStateFlow()

        init {
            load()
        }

        override fun load() {
            viewModelScope.launch { runCatching { repo.riskLimits() }.onSuccess { _info.value = it } }
        }

        fun saveGlobal(values: Map<String, String>) =
            act("Default limits saved") {
                _info.value = repo.setGlobalLimits(values)
            }
    }

@Composable
fun RiskLimitsScreen(
    vm: RiskLimitsViewModel,
    session: SessionViewModel,
) {
    val info by vm.info.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        val i = info
        if (i == null) Loading() else RiskLimitsContent(i, vm::saveGlobal)
        // Shows the outcome, and the device-lock prompt when raising a limit needs it.
        ReauthHostFor(action, session, vm)
    }
}

/** Stateless content, testable alone. */
@Composable
fun RiskLimitsContent(
    info: RiskLimitsInfo,
    onSaveGlobal: (Map<String, String>) -> Unit,
) {
    SectionTitle("Running plans")
    Text(
        "A running plan's own orders use the limits written in the plan (its largest position, daily loss and drawdown caps), " +
            "so a plan built to hold one coin at 95% can trade. A portfolio's own limits can only make them stricter.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (info.plans.isEmpty()) Text("No crypto or stock plan is running.", style = MaterialTheme.typography.bodyMedium)
    info.plans.forEach { p -> PlanLimitsCard(p) }
    SectionTitle("Defaults for everything else")
    Text(
        "Manual orders, and any strategy not running in a slot, use these limits. Raising one asks you to confirm it's you.",
        style = MaterialTheme.typography.bodySmall,
    )
    val g = info.global
    var trade by rememberSaveable(info.globalVersion) { mutableStateOf(g.maxTradePercent ?: "") }
    var instrument by rememberSaveable(info.globalVersion) { mutableStateOf(g.maxInstrumentPercent ?: "") }
    var crypto by rememberSaveable(info.globalVersion) { mutableStateOf(g.maxCryptoPercent ?: "") }
    var stocks by rememberSaveable(info.globalVersion) { mutableStateOf(g.maxStocksPercent ?: "") }
    var dailyLoss by rememberSaveable(info.globalVersion) { mutableStateOf(g.maxDailyLossPercent ?: "") }
    var drawdown by rememberSaveable(info.globalVersion) { mutableStateOf(g.maxDrawdownPercent ?: "") }
    var positions by rememberSaveable(info.globalVersion) { mutableStateOf(g.maxOpenPositions?.toString() ?: "") }
    Field("Largest single trade (% of portfolio)", trade, { trade = it }, number = true)
    Field("Largest holding of one symbol (%)", instrument, { instrument = it }, number = true)
    Field("All crypto together (%)", crypto, { crypto = it }, number = true)
    Field("All stocks together (%)", stocks, { stocks = it }, number = true)
    Field("Daily loss that stops trading (%)", dailyLoss, { dailyLoss = it }, number = true)
    Field("Drawdown that stops trading (%)", drawdown, { drawdown = it }, number = true)
    Field("Open positions at most", positions, { positions = it }, number = true)
    Button(
        onClick = {
            onSaveGlobal(
                mapOf(
                    "maxTradePercent" to trade,
                    "maxInstrumentPercent" to instrument,
                    "maxCryptoPercent" to crypto,
                    "maxStocksPercent" to stocks,
                    "maxDailyLossPercent" to dailyLoss,
                    "maxDrawdownPercent" to drawdown,
                    "maxOpenPositions" to positions,
                ),
            )
        },
        modifier = Modifier.padding(vertical = 8.dp).testTag("save-global-limits"),
    ) { Text("Save defaults") }
}

@Composable
private fun PlanLimitsCard(p: PlanLimits) {
    SfCard(Modifier.testTag("plan-limits-${p.strategyId}")) {
        Text("${if (p.assetClass == "CRYPTO") "Crypto" else "Stock"} ${p.slot} · ${p.name}", style = MaterialTheme.typography.titleSmall)
        p.portfolio?.let { Text("Trades in $it" + (p.allocationPercent?.let { a -> " · $a% of it" } ?: ""), style = MaterialTheme.typography.bodySmall) }
        p.sizing?.let {
            Text(
                "Entry size: " +
                    it
                        .replace("PERCENT_OF_EQUITY", "% of portfolio")
                        .replace("RISK_PERCENT", "risk %")
                        .replace('_', ' ')
                        .lowercase(),
                style = MaterialTheme.typography.bodySmall,
            )
        }
        LimitRows(p.limits)
        p.warning?.let { Banner(it, BannerKind.WARNING) }
    }
}

@Composable
private fun LimitRows(l: LimitValues) {
    l.maxTradePercent?.let { LabelValue("Largest single trade", "$it%") }
    l.maxInstrumentPercent?.let { LabelValue("Largest holding of one symbol", "$it%") }
    l.maxDailyLossPercent?.let { LabelValue("Daily loss that stops trading", "$it%") }
    l.maxDrawdownPercent?.let { LabelValue("Drawdown that stops trading", "$it%") }
    l.maxOpenPositions?.let { LabelValue("Open positions at most", it.toString()) }
}
