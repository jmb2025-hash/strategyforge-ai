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
        if (System.getenv("SF_LIST_TRADES") != null) {
            out.trades.forEach { t ->
                println("   trade ${t.setup} ${t.side} ${t.entryTime} @ ${t.entryPrice.setScale(0, java.math.RoundingMode.HALF_EVEN)} -> ${t.exitReason} ${t.exitTime} @ ${t.exitPrice?.setScale(0, java.math.RoundingMode.HALF_EVEN)} net ${t.netPnl.setScale(0, java.math.RoundingMode.HALF_EVEN)}")
            }
        }
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

    /**
     * The weeks of the Chart Champions trade plans published in the owner's research (June 30 to
     * September 22, 2026), with futures costs, so the plan's trades can be compared with those ideas.
     */
    @Test
    fun `Chart Champions plan v2 over the published trade-plan weeks`() {
        assumeTrue(csv != null, "SF_REAL_BTC_CSV not set")
        val perp = CostModel(commissionPercent = BigDecimal("0.05"), cryptoFallbackSpreadPercent = BigDecimal("0.02"))
        report("CC plan v2, Jun 15 - Oct 1 2026, futures costs", plan("/research/chart_champions_plan_v2_reply.md"), 30, "2026-06-15T00:00:00Z", "2026-10-01T00:00:00Z", perp)
    }

    /**
     * Parameter sweep: every plan JSON in SF_SWEEP_DIR is validated and run over each reporting
     * period with futures costs. One line per plan and period, with the win rate counted per
     * position (a partial target followed by a breakeven exit is one position), the way a trader
     * reports it.
     */
    @Test
    fun `sweep plan variants over the reporting periods`() {
        val dir = System.getenv("SF_SWEEP_DIR")?.let(::File)
        assumeTrue(csv != null && dir != null, "SF_REAL_BTC_CSV or SF_SWEEP_DIR not set")
        // SF_COSTS=app uses the app's default costs (0.2% spread); otherwise perpetual-futures costs.
        val costs = if (System.getenv("SF_COSTS") == "app") CostModel() else CostModel(commissionPercent = BigDecimal("0.05"), cryptoFallbackSpreadPercent = BigDecimal("0.02"))
        val start = BigDecimal(System.getenv("SF_START") ?: "100000")
        // SF_PERIODS=years runs every calendar year in the file plus the whole span.
        val years = System.getenv("SF_PERIODS") == "years"
        val periods =
            if (years) {
                (2018..2026).map { y -> "$y" to ("$y-01-01T00:00:00Z" to "${y + 1}-01-01T00:00:00Z") } +
                    listOf(
                        "2018-2022" to ("2018-01-01T00:00:00Z" to "2023-01-01T00:00:00Z"),
                        "2023-2026" to ("2023-01-01T00:00:00Z" to "2026-10-03T00:00:00Z"),
                        "ALL" to ("2018-01-01T00:00:00Z" to "2026-10-03T00:00:00Z"),
                    )
            } else {
                listOf(
                    "2025" to ("2025-01-07T00:00:00Z" to "2026-01-01T00:00:00Z"),
                    "Q1-2026" to ("2026-01-01T00:00:00Z" to "2026-04-01T00:00:00Z"),
                    "Q2-2026" to ("2026-04-01T00:00:00Z" to "2026-07-01T00:00:00Z"),
                    "Q3-2026" to ("2026-07-01T00:00:00Z" to "2026-10-01T00:00:00Z"),
                    "2026-YTD" to ("2026-01-01T00:00:00Z" to "2026-10-03T00:00:00Z"),
                    "ALL" to ("2025-01-07T00:00:00Z" to "2026-10-03T00:00:00Z"),
                )
            }
        val validator =
            app.strategyforge.engine.support.TestEngine
                .create()
                .validator
        dir!!.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }.forEach { f ->
            val doc = JacksonCanonical.mapper.readTree(f) as com.fasterxml.jackson.databind.node.ObjectNode
            val v = validator.validateDocument(doc)
            if (v.document == null || v.errors.isNotEmpty()) {
                println("SWEEP ${f.nameWithoutExtension} INVALID ${v.errors.map { it.code + ": " + it.message }}")
                return@forEach
            }
            v.warnings.forEach { println("SWEEP ${f.nameWithoutExtension} WARNING ${it.code}: ${it.message}") }
            val def = StrategyDefinition.from(v.document!!)
            val minutes = mapOf("30m" to 30L, "1h" to 60L, "4h" to 240L, "1d" to 1440L).getValue(doc.path("metadata").path("timeframe").asText())
            val b = barCache.getOrPut(minutes) { bars(minutes) }
            periods.forEach { (name, w) ->
                val params = BacktestParams(Instant.parse(w.first), Instant.parse(w.second), start, costs)
                val out = BacktestEngine(def, params).run(listOf(SymbolSeries("BTC-USD", AssetClass.CRYPTO, b, BigDecimal("0.01"), BigDecimal("0.00000001"), BigDecimal("0.00000001"))))
                val m = BacktestMetrics.compute(out, params.startingCapital, params.from, params.to, null)
                val positions =
                    out.trades
                        .groupBy { Triple(it.setup, it.side, it.entryTime) }
                        .values
                        .map { ts -> ts.sumOf { it.netPnl } }
                val wins = positions.count { it.signum() > 0 }
                val gain = positions.filter { it.signum() > 0 }.sumOf { it }
                val loss = positions.filter { it.signum() < 0 }.sumOf { it }.abs()
                val pf = if (loss.signum() == 0) "inf" else String.format("%.2f", gain.toDouble() / loss.toDouble())
                val bySetup = positions.size.let { out.trades.groupBy { it.setup }.mapValues { (_, ts) -> ts.map { it.entryTime }.distinct().size } }
                println(
                    "SWEEP ${f.nameWithoutExtension} $name ret=${String.format("%.1f", (m["netReturnPercent"] as BigDecimal).toDouble())}% " +
                        "dd=${String.format("%.1f", (m["maxDrawdownPercent"] as BigDecimal).toDouble())}% positions=${positions.size} " +
                        "win=${if (positions.isEmpty()) "-" else String.format("%.0f", 100.0 * wins / positions.size)}% pf=$pf setups=$bySetup",
                )
            }
        }
    }
}
