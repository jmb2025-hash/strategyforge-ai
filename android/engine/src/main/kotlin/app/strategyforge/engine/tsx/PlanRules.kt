package app.strategyforge.engine.tsx

import java.time.LocalDate

/** What a sleeve last selected, kept until its own cadence comes round again. */
data class SleevePick(
    val period: Int,
    val weights: Map<String, Double>,
)

/**
 * Turns a portfolio plan into target weights on a rebalance day (D-055), using only data on or
 * before that day. The rules are the ones selected in the TSX research (D-054).
 */
class PlanRules(
    private val data: TsxData,
    listings: List<TsxListing>,
) {
    private val bySymbol = listings.associateBy { it.symbol }
    private val stocks = listings.filter { it.isStock && it.symbol in data.series && it.symbol !in EXCLUDED }

    fun sector(sym: String): String = bySymbol[sym]?.sector ?: "other"

    /** Combined target weights; [state] holds each sleeve's last pick by index and is updated. */
    fun targets(
        plan: PortfolioPlan,
        d: LocalDate,
        state: MutableMap<Int, SleevePick>,
    ): Map<String, Double> {
        val out = linkedMapOf<String, Double>()
        plan.sleeves.forEachIndexed { i, s ->
            val period = s.select.period(d)
            val pick = state[i]?.takeIf { it.period == period } ?: SleevePick(period, sleeve(s.rule, d)).also { state[i] = it }
            pick.weights.forEach { (sym, w) -> out[sym] = (out[sym] ?: 0.0) + w * s.share }
        }
        return out.filterValues { it > 0 }
    }

    fun sleeve(
        rule: SleeveRule,
        d: LocalDate,
    ): Map<String, Double> =
        when (rule) {
            is SleeveRule.Fixed -> rule.weights.filterKeys { it in data.series }
            is SleeveRule.Dividend -> dividend(rule, d)
            is SleeveRule.Momentum -> momentum(rule, d)
        }

    private fun dividend(
        r: SleeveRule.Dividend,
        d: LocalDate,
    ): Map<String, Double> {
        data class Cand(
            val sym: String,
            val yld: Double,
            val mom: Double,
        )
        val cands =
            stocks.mapNotNull { l ->
                if (data.barsUpTo(l.symbol, d) < MIN_BARS) return@mapNotNull null
                val f = data.dividendFeatures(l.symbol, d) ?: return@mapNotNull null
                if (!f.pays || f.yld < r.minYield || (r.excludeCuts && f.cut)) return@mapNotNull null
                Cand(l.symbol, f.yld, data.momentum(l.symbol, d, r.momentumMonths) ?: -1.0)
            }
        val ordered =
            when (r.rank) {
                "YIELD" -> cands.sortedByDescending { it.yld }
                "YIELD_MOMENTUM" -> {
                    val ry = pctRank(cands.map { it.yld })
                    val rm = pctRank(cands.map { it.mom })
                    cands.indices.sortedByDescending { ry[it] + rm[it] }.map { cands[it] }
                }
                else -> error("Unknown dividend rank ${r.rank}")
            }
        val picks = capped(ordered.map { it.sym }, r.sectorCap, r.maxHoldings)
        if (picks.isEmpty()) return emptyMap()
        val w = 1.0 / picks.size
        val out = linkedMapOf<String, Double>()
        picks.forEach { s ->
            val key = if (r.trendSmaDays != null && r.trendSafe != null && !data.aboveSma(s, d, r.trendSmaDays)) r.trendSafe else s
            out[key] = (out[key] ?: 0.0) + w
        }
        return out
    }

    private fun momentum(
        r: SleeveRule.Momentum,
        d: LocalDate,
    ): Map<String, Double> {
        val pool =
            if (r.universe.isNotEmpty()) {
                r.universe.filter { it in data.series }
            } else {
                stocks.map { it.symbol }.filter { data.barsUpTo(it, d) >= r.minBars }
            }
        val scored = pool.mapNotNull { s -> data.momentum(s, d, r.lookbackMonths)?.let { s to it } }.sortedByDescending { it.second }
        val picks = capped(scored.map { it.first }, r.sectorCap, r.top)
        val out = linkedMapOf<String, Double>()
        picks.forEach { out[it] = 1.0 / r.top }
        if (picks.size < r.top && r.fillWith != null) out[r.fillWith] = (out[r.fillWith] ?: 0.0) + (r.top - picks.size).toDouble() / r.top
        return out
    }

    private fun capped(
        ordered: List<String>,
        cap: Int,
        n: Int,
    ): List<String> {
        val count = mutableMapOf<String, Int>()
        val out = mutableListOf<String>()
        for (s in ordered) {
            val sec = sector(s)
            if (cap > 0 && (count[sec] ?: 0) >= cap) continue
            out += s
            count[sec] = (count[sec] ?: 0) + 1
            if (out.size == n) break
        }
        return out
    }

    companion object {
        /** Days of history a stock needs before a plan considers it (about a year). */
        const val MIN_BARS = 260

        /** Listings whose free history is unreliable (ticker reuse). */
        val EXCLUDED = setOf("BLX", "IIP-UN")

        /** Percentile ranks with ties averaged, as rank / n. */
        fun pctRank(v: List<Double>): List<Double> {
            val n = v.size
            val idx = v.indices.sortedBy { v[it] }
            val out = DoubleArray(n)
            var i = 0
            while (i < n) {
                var j = i
                while (j + 1 < n && v[idx[j + 1]] == v[idx[i]]) j++
                val avg = (i + j) / 2.0 + 1
                for (k in i..j) out[idx[k]] = avg / n
                i = j + 1
            }
            return out.toList()
        }
    }
}
