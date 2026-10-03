package app.strategyforge.engine.research

import app.strategyforge.engine.backtest.BacktestEngine
import app.strategyforge.engine.backtest.BacktestMetrics
import app.strategyforge.engine.backtest.BacktestParams
import app.strategyforge.engine.backtest.SymbolSeries
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.strategy.StrategyDefinition
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * Research harness, not a regression test: replays trading plans built from the owner's research on
 * real BTC/USD history. Runs only when SF_REAL_BTC_CSV points at a 1-minute OHLCV file
 * (timestamp,open,high,low,close,volume with epoch seconds, for example Bitstamp's public export);
 * CI does not have it and skips. Results are printed for design decisions, never asserted.
 */
class RealDataResearchTest {
    private val csv = System.getenv("SF_REAL_BTC_CSV")?.let(::File)?.takeIf { it.exists() }

    /** 1-minute rows aggregated into bars of [minutes]. */
    private fun bars(minutes: Long): List<CandleData> {
        val step = minutes * 60
        val out = mutableListOf<CandleData>()
        var start = -1L
        var o = 0.0
        var h = 0.0
        var l = 0.0
        var c = 0.0
        var v = 0.0

        fun flush() {
            if (start >= 0) {
                out += CandleData(Instant.ofEpochSecond(start), BigDecimal.valueOf(o), BigDecimal.valueOf(h), BigDecimal.valueOf(l), BigDecimal.valueOf(c), BigDecimal.valueOf(v))
            }
        }
        csv!!.bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val f = line.split(',')
                val t = f[0].toLong()
                val bucket = t - t % step
                val oo = f[1].toDouble()
                val hh = f[2].toDouble()
                val ll = f[3].toDouble()
                val cc = f[4].toDouble()
                val vv = f[5].toDouble()
                if (bucket != start) {
                    flush()
                    start = bucket
                    o = oo
                    h = hh
                    l = ll
                    v = 0.0
                }
                h = maxOf(h, hh)
                l = minOf(l, ll)
                c = cc
                v += vv
            }
        }
        flush()
        return out
    }

    private fun plan(resource: String): StrategyDefinition {
        val text = javaClass.getResource(resource)!!.readText()
        val json = Regex("```json\\s*\\n(.*?)\\n```", RegexOption.DOT_MATCHES_ALL).find(text)!!.groupValues[1]
        return StrategyDefinition.from(JacksonCanonical.mapper.readTree(json))
    }

    private val barCache = mutableMapOf<Long, List<CandleData>>()

    private fun report(
        label: String,
        def: StrategyDefinition,
        minutes: Long,
        from: String,
        to: String,
        costs: CostModel = CostModel(),
    ) {
        val b = barCache.getOrPut(minutes) { bars(minutes) }
        val params = BacktestParams(Instant.parse(from), Instant.parse(to), BigDecimal(100000), costs)
        val out = BacktestEngine(def, params).run(listOf(SymbolSeries("BTC-USD", AssetClass.CRYPTO, b, BigDecimal("0.01"), BigDecimal("0.00000001"), BigDecimal("0.00000001"))))
        val m = BacktestMetrics.compute(out, params.startingCapital, params.from, params.to, null)
        println("== $label: ${b.size} bars of ${minutes}m, ${b.first().openTime} .. ${b.last().openTime}; window $from .. $to")
        listOf("netReturnPercent", "maxDrawdownPercent", "trades", "closedTrades", "winRatePercent", "profitFactor", "expectancy", "fees", "spreadCost", "slippage", "riskBlockedEntries", "exposurePercent")
            .forEach { k -> println("   $k = ${m[k]}") }
        BacktestMetrics.bySetup(out, def).forEach { println("   setup $it") }
        out.trades
            .groupBy { it.setup to it.exitReason }
            .forEach { (k, ts) -> println("   exits ${k.first} ${k.second}: ${ts.size}") }
        val held = out.trades.filter { it.exitTime != null }.map { Duration.between(it.entryTime, it.exitTime).toHours() }
        if (held.isNotEmpty()) println("   median hold ${held.sorted()[held.size / 2]} h")
    }

    @Test
    fun `Chart Champions plan v1 on real BTC history`() {
        assumeTrue(csv != null, "SF_REAL_BTC_CSV not set")
        report("CC plan v1", plan("/research/chart_champions_plan_v1_reply.md"), 60, "2025-07-01T00:00:00Z", "2026-10-01T00:00:00Z")
    }

    @Test
    fun `Chart Champions plan v2 on real BTC history`() {
        assumeTrue(csv != null, "SF_REAL_BTC_CSV not set")
        report("CC plan v2", plan("/research/chart_champions_plan_v2_reply.md"), 30, "2025-07-01T00:00:00Z", "2026-10-01T00:00:00Z")
    }

    /** How much of the result is trading costs: the same plan with no spread or slippage. */
    @Test
    fun `Chart Champions plan v2 without trading costs`() {
        assumeTrue(csv != null, "SF_REAL_BTC_CSV not set")
        val none = CostModel(slippageBps = BigDecimal.ZERO, cryptoFallbackSpreadPercent = BigDecimal.ZERO)
        report("CC plan v2, no costs", plan("/research/chart_champions_plan_v2_reply.md"), 30, "2025-07-01T00:00:00Z", "2026-10-01T00:00:00Z", none)
    }

    /** Costs close to a BTC perpetual on a major exchange: 0.02% spread, 0.05% taker fee, 2 bps slippage. */
    @Test
    fun `Chart Champions plans with perpetual futures costs`() {
        assumeTrue(csv != null, "SF_REAL_BTC_CSV not set")
        val perp = CostModel(commissionPercent = BigDecimal("0.05"), cryptoFallbackSpreadPercent = BigDecimal("0.02"))
        report("CC plan v1, futures costs", plan("/research/chart_champions_plan_v1_reply.md"), 60, "2025-07-01T00:00:00Z", "2026-10-01T00:00:00Z", perp)
        report("CC plan v2, futures costs", plan("/research/chart_champions_plan_v2_reply.md"), 30, "2025-07-01T00:00:00Z", "2026-10-01T00:00:00Z", perp)
    }
}
