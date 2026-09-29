package app.strategyforge.engine.reports

import app.strategyforge.engine.autonomy.StrategySlots
import app.strategyforge.engine.backtest.BacktestService
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.money.Decimals
import app.strategyforge.engine.strategy.StrategyExplainer
import app.strategyforge.engine.strategy.StrategyService
import app.strategyforge.engine.strategy.StrategyStatus
import app.strategyforge.engine.strategy.StrategyView
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Simulated trading results of one strategy: trades its orders closed while it ran. */
data class LiveStats(
    val closedTrades: Int,
    val wins: Int,
    val losses: Int,
    val winRatePercent: BigDecimal?,
    val realizedPnl: BigDecimal,
    val averagePnl: BigDecimal?,
    val bestTrade: BigDecimal?,
    val worstTrade: BigDecimal?,
    /** Largest fall of cumulative realized P&L from its previous peak, in dollars. */
    val maxDrawdown: BigDecimal,
    val openPositions: Int,
    val activeDays: BigDecimal,
    val firstTradeAt: Instant?,
    val lastTradeAt: Instant?,
)

data class BacktestStats(
    val backtestId: UUID,
    val netReturnPercent: BigDecimal?,
    val maxDrawdownPercent: BigDecimal?,
    val trades: Int?,
    val winRatePercent: BigDecimal?,
    val profitFactor: BigDecimal?,
    val completedAt: Instant?,
)

data class Scorecard(
    val strategy: StrategyView,
    val live: LiveStats,
    val backtest: BacktestStats?,
    /** Plain warning when the numbers rest on too few trades to judge. */
    val sampleWarning: String?,
)

/**
 * Per-strategy scorecards (D-037): how each saved strategy did in simulated trading and in its
 * latest backtest, so strategies can be compared and the best ideas combined. Money is summed in
 * Kotlin from exact decimal text.
 */
class ScorecardService(
    private val db: Db,
    private val strategies: StrategyService,
    private val backtests: BacktestService,
    private val slots: StrategySlots,
    private val clock: Clock,
) {
    fun all(assetClass: String? = null): List<Scorecard> =
        strategies
            .list(includeArchived = false)
            .filter { assetClass == null || it.assetClass == assetClass }
            .filter { it.status !in setOf(StrategyStatus.DRAFT, StrategyStatus.VALIDATION_FAILED) }
            .map { scorecard(it) }
            .sortedWith(compareByDescending<Scorecard> { it.live.realizedPnl }.thenByDescending { it.backtest?.netReturnPercent ?: BigDecimal(-1_000_000) })

    fun scorecard(id: UUID): Scorecard = scorecard(strategies.get(id))

    /**
     * The opening message of a "build me a better strategy" research conversation (D-037): every
     * saved strategy of the asset class with its rules and results, best first, within the message
     * limit. The AI proposes a combination; the owner refines it and compiles it like any research.
     */
    fun combineBrief(assetClass: String): String {
        if (assetClass !in setOf("CRYPTO", "US_EQUITY")) throw Problems.badRequest("invalid-asset-class", "assetClass must be US_EQUITY or CRYPTO")
        val cards = all(assetClass).filter { it.strategy.currentVersionId != null }
        if (cards.size < 2) throw Problems.unprocessable("not-enough-strategies", "Save and test at least two ${if (assetClass == "CRYPTO") "crypto" else "stock"} strategies first")
        val intro =
            """
            Build me a better ${if (assetClass == "CRYPTO") "crypto" else "stock"} strategy from the results of the strategies I have tried in this app.
            Below are my saved strategies, best paper results first, each with its rules, its simulated trading results and its latest backtest.
            Compare what the winners have in common and what the losers got wrong, weigh how many trades each result rests on, and propose one
            strategy that combines the rules with the best evidence. Explain which parts come from which strategy and why, and what you left out.
            Treat small samples as weak evidence and do not assume past results will repeat.
            """.trimIndent()
        val budget = MAX_BRIEF - intro.length
        val per = budget / cards.take(MAX_COMBINED).size
        val parts = cards.take(MAX_COMBINED).mapIndexed { i, c -> describe(i + 1, c).let { if (it.length > per) it.take(per - 1) + "…" else it } }
        return (intro + "\n\n" + parts.joinToString("\n\n")).take(MAX_BRIEF)
    }

    private fun describe(
        n: Int,
        c: Scorecard,
    ): String {
        val l = c.live
        val rules = runCatching { StrategyExplainer.explain(strategies.definition(c.strategy.currentVersionId!!)) }.getOrElse { "(rules unavailable)" }
        val paper =
            if (l.closedTrades == 0) {
                "no closed paper trades"
            } else {
                "${l.closedTrades} closed paper trades, ${l.winRatePercent}% winners, realized P&L ${l.realizedPnl} USD, worst drawdown ${l.maxDrawdown} USD, ${l.activeDays} days active"
            }
        val bt =
            c.backtest?.let {
                "backtest: ${it.netReturnPercent ?: "?"}% net return, ${it.maxDrawdownPercent ?: "?"}% max drawdown, ${it.trades ?: "?"} trades, " +
                    "${it.winRatePercent ?: "?"}% winners, profit factor ${it.profitFactor ?: "?"}"
            } ?: "no backtest"
        return "$n. ${c.strategy.name}\nResults: $paper; $bt.\nRules: $rules"
    }

    private fun scorecard(s: StrategyView): Scorecard {
        val live = live(s.id)
        val bt = latestBacktest(s)
        return Scorecard(s, live, bt, warning(live, bt))
    }

    private fun live(id: UUID): LiveStats {
        val closes =
            db
                .sql(
                    """
                    select e.realized_pnl, e.executed_at from paper_executions e join paper_orders o on o.id = e.order_id
                    where o.strategy_id = :s and e.side in ('SELL', 'BUY_TO_COVER') order by e.executed_at, e.fill_seq
                    """.trimIndent(),
                ).param("s", id)
                .list { it.dec("realized_pnl") to it.instant("executed_at") }
        val pnls = closes.map { it.first }
        val total = pnls.fold(BigDecimal.ZERO, BigDecimal::add)
        var peak = BigDecimal.ZERO
        var running = BigDecimal.ZERO
        var drawdown = BigDecimal.ZERO
        pnls.forEach { p ->
            running = running.add(p)
            peak = peak.max(running)
            drawdown = drawdown.max(peak.subtract(running))
        }
        val wins = pnls.count { it.signum() > 0 }
        val losses = pnls.count { it.signum() < 0 }
        val now = clock.instant()
        val activeMillis =
            db
                .sql("select created_at, ended_at from strategy_activations where strategy_id = :s")
                .param("s", id)
                .list { Duration.between(it.instant("created_at"), it.instantOrNull("ended_at") ?: now).toMillis().coerceAtLeast(0) }
                .sum()
        return LiveStats(
            pnls.size,
            wins,
            losses,
            if (pnls.isEmpty()) null else pct(wins, pnls.size),
            Decimals.money(total),
            if (pnls.isEmpty()) null else Decimals.money(total.divide(BigDecimal(pnls.size), Decimals.MC)),
            pnls.maxOrNull()?.let { Decimals.money(it) },
            pnls.minOrNull()?.let { Decimals.money(it) },
            Decimals.money(drawdown),
            slots.holdings(id).size,
            BigDecimal(activeMillis).divide(BigDecimal(DAY_MS), 1, RoundingMode.HALF_EVEN),
            closes.firstOrNull()?.second,
            closes.lastOrNull()?.second,
        )
    }

    private fun latestBacktest(s: StrategyView): BacktestStats? {
        val b = backtests.list(s.id).firstOrNull { it.status == "COMPLETED" && it.metrics != null } ?: return null
        val m = b.metrics!!

        fun dec(k: String) = m[k]?.takeIf { !it.isNull }?.let { runCatching { BigDecimal(it.asText()) }.getOrNull() }
        return BacktestStats(b.id, dec("netReturnPercent"), dec("maxDrawdownPercent"), m["trades"]?.takeIf { it.canConvertToInt() }?.asInt(), dec("winRatePercent"), dec("profitFactor"), b.completedAt)
    }

    private fun warning(
        live: LiveStats,
        bt: BacktestStats?,
    ): String? =
        when {
            live.closedTrades == 0 && bt == null -> "No trades or backtest yet: nothing to judge."
            live.closedTrades < MIN_TRADES && (bt?.trades ?: 0) < MIN_TRADES ->
                "Only ${live.closedTrades} paper trade(s) and ${bt?.trades ?: 0} backtest trade(s): too few to tell skill from luck."
            live.closedTrades < MIN_TRADES -> "Only ${live.closedTrades} paper trade(s) so far; the backtest has more (${bt?.trades}), but paper results are not yet meaningful."
            else -> null
        }

    private fun pct(
        part: Int,
        whole: Int,
    ) = BigDecimal(part).multiply(BigDecimal(100)).divide(BigDecimal(whole), 1, RoundingMode.HALF_EVEN)

    companion object {
        /** Fewer closed trades than this is flagged as too small a sample (D-037). */
        const val MIN_TRADES = 20
        private const val DAY_MS = 86_400_000L

        /** Fits the research message limit with room to spare. */
        private const val MAX_BRIEF = 7_800
        private const val MAX_COMBINED = 6
    }
}
