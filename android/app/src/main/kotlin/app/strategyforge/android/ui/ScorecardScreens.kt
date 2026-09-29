package app.strategyforge.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.Scorecard
import app.strategyforge.android.core.state.ActionState

// ------------------------------------------------------------------ scorecards and a better strategy (D-037)

@Composable
fun ScorecardsScreen(
    vm: ScorecardsViewModel,
    fmt: Formatters,
    nav: NavHostController,
) {
    val asset by vm.asset.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val action by vm.action.collectAsStateWithLifecycle()
    val started by vm.started.collectAsStateWithLifecycle()
    LaunchedEffect(started) {
        started?.let {
            vm.consumeStarted()
            nav.navigate("research/$it")
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).verticalScroll(rememberScrollState())) {
        Scorecards(asset, cards, fmt, action, vm::select, vm::buildBetter) { nav.navigate("strategy/$it") }
    }
}

/** The comparison list and the "build me a better strategy" button; stateless so it can be tested alone. */
@Composable
fun Scorecards(
    asset: String,
    cards: List<Scorecard>?,
    fmt: Formatters,
    action: ActionState,
    onAsset: (String) -> Unit,
    onBuild: () -> Unit,
    onOpen: (String) -> Unit,
) {
    Column {
        SectionTitle("How your strategies did")
        Text(
            "Paper results come from the simulated trades each strategy made while it was running; the backtest is its latest test on past data. " +
                "Few trades say little: a strategy needs about 20 closed trades before its results mean much.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(Modifier.padding(vertical = 4.dp)) {
            listOf("CRYPTO" to "Crypto", "US_EQUITY" to "Stocks").forEach { (a, label) ->
                FilterChip(selected = asset == a, onClick = { onAsset(a) }, label = { Text(label) }, modifier = Modifier.testTag("score-asset-$a"))
                Spacer(Modifier.width(8.dp))
            }
        }
        when {
            cards == null -> Loading()
            cards.isEmpty() -> Text("No tested ${if (asset == "CRYPTO") "crypto" else "stock"} strategies yet. Create one with AI research and backtest it.")
            else -> {
                if (cards.size >= 2) ComparisonCharts(cards, fmt)
                cards.forEachIndexed { i, c -> ScorecardCard(i + 1, c, fmt) { onOpen(c.strategy.id) } }
            }
        }
        SectionTitle("Build me a better strategy")
        Text(
            "The AI compares these strategies' rules and results and proposes one that combines what worked. " +
                "It opens as a research conversation: refine it, compile it, then backtest it before running it.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Button(
            onClick = onBuild,
            enabled = (cards?.size ?: 0) >= 2 && action != ActionState.Running,
            modifier = Modifier.testTag("build-better"),
        ) { Text("Build me a better strategy") }
        if ((cards?.size ?: 0) < 2) Text("Needs at least two tested strategies.", style = MaterialTheme.typography.bodySmall)
        ActionFeedback(action)
    }
}

@Composable
fun ScorecardCard(
    rank: Int?,
    c: Scorecard,
    fmt: Formatters,
    onOpen: (() -> Unit)? = null,
) {
    SfCard(Modifier.testTag("scorecard").let { m -> onOpen?.let { m.clickable(onClick = it) } ?: m }) {
        Text((rank?.let { "$it. " } ?: "") + c.strategy.name, fontWeight = FontWeight.SemiBold)
        StatusChip(c.strategy.status)
        val l = c.live
        Text("Paper trading", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        if (l.pnlSeries.size >= 2) {
            val up = (l.realizedPnl.toDoubleOrNull() ?: 0.0) >= 0
            Sparkline(l.pnlSeries.mapNotNull { it.value.toDoubleOrNull() }, if (up) Sf.colors.gain else Sf.colors.loss, Modifier.padding(vertical = 6.dp), height = 36.dp)
        }
        if (l.closedTrades == 0) {
            Text("No closed paper trades yet" + if (l.openPositions > 0) " (${l.openPositions} open)" else "", style = MaterialTheme.typography.bodyMedium)
        } else {
            PnlValue("Realized profit/loss", l.realizedPnl, fmt)
            LabelValue("Closed trades", "${l.closedTrades} (${l.wins} won, ${l.losses} lost)")
            LabelValue("Win rate", fmt.percent(l.winRatePercent))
            LabelValue("Average trade", fmt.money(l.averagePnl))
            LabelValue("Best / worst trade", "${fmt.money(l.bestTrade)} / ${fmt.money(l.worstTrade)}")
            LabelValue("Largest drawdown", fmt.money(l.maxDrawdown))
            LabelValue("Open positions", l.openPositions.toString())
        }
        LabelValue("Days running", l.activeDays)
        Text("Latest backtest", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
        val b = c.backtest
        if (b == null) {
            Text("Not backtested yet", style = MaterialTheme.typography.bodyMedium)
        } else {
            LabelValue("Net return", fmt.percent(b.netReturnPercent))
            LabelValue("Max drawdown", fmt.percent(b.maxDrawdownPercent))
            LabelValue("Trades", b.trades?.toString() ?: "—")
            LabelValue("Win rate", fmt.percent(b.winRatePercent))
            LabelValue("Profit factor", b.profitFactor?.let { fmt.quantity(it) } ?: "—")
        }
        c.sampleWarning?.let { Banner(it, BannerKind.WARNING) }
    }
}
