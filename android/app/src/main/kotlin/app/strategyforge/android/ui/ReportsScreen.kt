package app.strategyforge.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.strategyforge.android.core.format.Formatters
import app.strategyforge.android.core.model.ReportView

/** FR-103 reports and FR-104 descriptive outcome comparisons for a paper portfolio. */
@Composable
fun ReportsScreen(
    vm: ReportsViewModel,
    fmt: Formatters,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val noPortfolio by vm.noPortfolio.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        SectionTitle("Reports")
        if (noPortfolio) {
            Text("Create a paper portfolio to see reports.", modifier = Modifier.testTag("empty"))
        } else {
            ResourceContent(state, fmt, vm::refresh) { ReportBody(it, fmt) }
        }
        TextButton(onClick = vm::refresh) { Text("Refresh") }
    }
}

@Composable
fun ReportBody(
    v: ReportView,
    fmt: Formatters,
) {
    val r = v.report
    Banner(r.disclaimer, BannerKind.INFO)
    SfCard {
        Text(r.summary.portfolio.name, style = MaterialTheme.typography.titleSmall)
        LabelValue("Equity", fmt.money(r.summary.equity))
        PnlValue("Realized P and L", r.summary.realizedPnl, fmt)
        PnlValue("Unrealized P and L", r.summary.unrealizedPnl, fmt)
        LabelValue("Period return", fmt.percent(r.periodReturnPercent))
        LabelValue("Maximum drawdown", fmt.percent(r.maxDrawdownPercent))
    }
    SectionTitle("Costs")
    SfCard {
        LabelValue("Commissions", fmt.money(r.costs.commissions))
        LabelValue("Spread", fmt.money(r.costs.spread))
        LabelValue("Slippage", fmt.money(r.costs.slippage))
        LabelValue("Borrow fees", fmt.money(r.costs.borrow))
        LabelValue("Total costs", fmt.money(r.costs.total))
    }
    if (r.byAsset.isNotEmpty()) {
        SectionTitle("By asset")
        r.byAsset.forEach { a -> SfCard { PnlValue("${a.label} (${a.trades} trades)", a.realizedPnl, fmt) } }
    }
    if (r.byStrategy.isNotEmpty()) {
        SectionTitle("By strategy")
        r.byStrategy.forEach { a -> SfCard { PnlValue("${a.label} (${a.trades} trades)", a.realizedPnl, fmt) } }
    }
    if (r.benchmarks.isNotEmpty()) {
        SectionTitle("Benchmarks")
        r.benchmarks.forEach { b -> LabelValue(b.symbol, b.returnPercent?.let(fmt::percent) ?: (b.note ?: "Not available")) }
    }
    SectionTitle("Outcome comparison")
    Banner(v.outcomes.disclaimer, BannerKind.WARNING)
    (v.outcomes.bySource + v.outcomes.byRecommendationDecision).forEach { g ->
        SfCard {
            PnlValue("${g.group.lowercase().replace('_', ' ')} (${g.count})", g.realizedPnl, fmt)
            if (g.note.isNotBlank()) Text(g.note, style = MaterialTheme.typography.bodySmall)
        }
    }
}
