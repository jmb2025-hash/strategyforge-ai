package app.strategyforge.engine.backtest

import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.strategy.StrategyDefinition
import app.strategyforge.engine.support.Plans
import app.strategyforge.engine.support.TestEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import kotlin.math.sin

/** D-045 backtests of trading plans: setups, conflict and capital policies, plan context and results per setup. */
class TradingPlanBacktestTest {
    private val start = Instant.parse("2025-01-06T00:00:00Z")

    private fun waves(n: Int): List<CandleData> =
        (0 until n).map { i ->
            val p = { k: Int -> 10000 + 600 * sin(k * 2 * Math.PI / 48) }
            val o = BigDecimal(p(i)).setScale(2, RoundingMode.HALF_EVEN)
            val c = BigDecimal(p(i + 1)).setScale(2, RoundingMode.HALF_EVEN)
            CandleData(start.plus(Duration.ofHours(i.toLong())), o, o.max(c).add(BigDecimal(15)), o.min(c).subtract(BigDecimal(15)), c, BigDecimal(1000))
        }

    private val series = listOf(SymbolSeries("BTC-USD", AssetClass.CRYPTO, waves(24 * 21), BigDecimal("0.01"), BigDecimal("0.00000001"), BigDecimal("0.00000001")))
    private val params = BacktestParams(start.plus(Duration.ofHours(24)), start.plus(Duration.ofHours(24 * 20)), BigDecimal(100000), CostModel())

    /** Two setups with identical long rules, so both want every entry. */
    private fun twin(
        conflict: String = "ONE_PER_SYMBOL",
        capital: String = "SHARED",
        openRisk: String = "",
        allocA: String = "",
        longWhen: String = "",
    ) = StrategyDefinition.from(
        JacksonCanonical.mapper.readTree(
            """
            {"schemaVersion":"2.0","metadata":{"name":"twin","assetClass":"CRYPTO","timeframe":"1h"},"universe":{"symbols":["BTC-USD"]},
             "dataRequirements":{"minimumHistoryBars":10,"maximumQuoteAgeSeconds":60,"indicators":[{"id":"S","type":"SMA","period":6}]},
             ${if (longWhen.isNotEmpty()) "\"context\":{\"longWhen\":$longWhen}," else ""}
             "planRules":{"conflictPolicy":"$conflict","capitalPolicy":"$capital"$openRisk},
             "setups":[
               {"id":"A","name":"First","priority":1,"direction":"LONG_ONLY"$allocA,
                "entryRules":{"operator":"ALL","conditions":[{"left":"CLOSE","comparison":"CROSSES_ABOVE","right":"S"}]},
                "exitRules":{"stopLossPercent":3,"takeProfitPercent":6,"maximumHoldingBars":30},"positionSizing":{"method":"RISK_PERCENT","value":1}},
               {"id":"B","name":"Second","priority":2,"direction":"LONG_ONLY",
                "entryRules":{"operator":"ALL","conditions":[{"left":"CLOSE","comparison":"CROSSES_ABOVE","right":"S"}]},
                "exitRules":{"stopLossPercent":2,"takeProfitPercent":4,"maximumHoldingBars":10},"positionSizing":{"method":"RISK_PERCENT","value":1}}],
             "orderInstructions":{"orderType":"MARKET","timeInForce":"GTC"},
             "riskLimits":{"maximumOpenPositions":5,"maximumDailyTrades":50,"maximumDailyLossPercent":90,"allowShort":false},"inactivityConditions":[]}
            """.trimIndent(),
        ),
    )

    private fun run(def: StrategyDefinition) = BacktestEngine(def, params).run(series)

    /** Positions as (setup, entry time, last exit time), merging partial exits. */
    private fun positions(out: BacktestOutput) = out.trades.groupBy { Triple(it.setup, it.symbol, it.entryTime) }.map { (k, ts) -> Triple(k.first, k.third, ts.mapNotNull { it.exitTime }.maxOrNull() ?: Instant.MAX) }

    private fun overlaps(out: BacktestOutput): Int {
        val ps = positions(out)
        return ps.sumOf { a -> ps.count { b -> a !== b && a.second < b.third && b.second < a.third } } / 2
    }

    @Test
    fun `one position per symbol goes to the highest-priority setup`() {
        val out = run(twin())
        assertThat(out.trades).isNotEmpty()
        assertThat(out.trades.map { it.setup }.toSet()).containsExactly("A")
        assertThat(overlaps(out)).isZero()
    }

    @Test
    fun `stacking lets every setup hold its own position, each managed by its own exits`() {
        val out = run(twin(conflict = "STACK"))
        assertThat(out.trades.map { it.setup }.toSet()).containsExactlyInAnyOrder("A", "B")
        assertThat(overlaps(out)).isGreaterThan(0)
        // B holds at most 10 bars; A up to 30.
        assertThat(out.trades.filter { it.setup == "B" }.maxOf { it.holdingBars }).isLessThanOrEqualTo(10)
        val metrics = BacktestMetrics.bySetup(out, twin(conflict = "STACK"))
        assertThat(metrics.map { it["id"] }).containsExactly("A", "B")
        assertThat(metrics.sumOf { it["trades"] as Int }).isEqualTo(out.trades.size)
    }

    @Test
    fun `the plan-wide open-risk cap blocks a second position`() {
        // Each entry risks 1% of equity; a 1.5% cap leaves room for only one at a time.
        val out = run(twin(conflict = "STACK", openRisk = ",\"maximumOpenRiskPercent\":1.5"))
        assertThat(out.riskBlockedEntries).isGreaterThan(0)
        assertThat(overlaps(out)).isZero()
    }

    @Test
    fun `an allocated setup sizes from and stays within its share of equity`() {
        val shared = run(twin())
        val allocated = run(twin(capital = "ALLOCATED", allocA = ",\"allocationPercent\":10"))
        val first = allocated.trades.first()
        assertThat(first.quantity.multiply(first.entryPrice).toDouble()).isLessThanOrEqualTo(10_100.0)
        assertThat(first.quantity).isLessThan(shared.trades.first().quantity)
    }

    @Test
    fun `the plan context stops entries on the side it does not allow`() {
        val never = """{"operator":"ALL","conditions":[{"left":"CLOSE","comparison":"LT","right":0}]}"""
        assertThat(run(twin(longWhen = never)).trades).isEmpty()
    }

    @Test
    fun `a three-setup plan backtests on demo data with results for each setup`() {
        val e = TestEngine.create()
        val r = e.strategies.create(JacksonCanonical.mapper.readTree(Plans.rangePlan()))
        val b = e.backtests.submit(BacktestRequest(r.strategy.id, from = Instant.parse("2026-02-01T00:00:00Z"), to = Instant.parse("2026-06-22T00:00:00Z"), startingCapital = BigDecimal("100000")))
        assertThat(b.status).`as`(b.error ?: "").isEqualTo("COMPLETED")
        val trades = e.backtests.trades(b.id)
        val bySetup = trades.groupingBy { it["setup"] }.eachCount()
        println("Range plan backtest: ${trades.size} trades by setup $bySetup, sides ${trades.groupingBy { it["side"] }.eachCount()}")
        assertThat(bySetup.keys).contains("SFP_LOW", "SFP_HIGH")
        val setups = b.metrics!!["setups"]
        assertThat(setups.map { it["id"].asText() }).containsExactly("SFP_LOW", "SFP_HIGH", "BREAKOUT")
        // One position per symbol: positions in the same symbol never overlap.
        trades.groupBy { it["symbol"] }.forEach { (_, ts) ->
            val ps = ts.groupBy { it["setup"] to it["entryTime"] }.map { (k, xs) -> (k.second as Instant) to (xs.mapNotNull { it["exitTime"] as Instant? }.maxOrNull() ?: Instant.MAX) }
            ps.forEach { a -> ps.forEach { c -> if (a !== c) assertThat(a.first < c.second && c.first < a.second).isFalse() } }
        }
    }
}
