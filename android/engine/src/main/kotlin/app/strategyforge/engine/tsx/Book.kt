package app.strategyforge.engine.tsx

import java.time.LocalDate

/** A dividend paid on a holding: reinvested (DRIP) or paid out as income. */
data class DividendPaid(
    val day: LocalDate,
    val symbol: String,
    val amount: Double,
    val reinvested: Boolean,
)

data class Trade(
    val day: LocalDate,
    val symbol: String,
    val shares: Double,
    val price: Double,
)

/**
 * A paper portfolio in C$ for a portfolio plan (D-055): cash, shares and the last price seen for
 * each holding. Dividends are paid on the ex-date; with DRIP they buy more of the payer at that
 * day's close, otherwise they leave the portfolio as income. A listing whose prices stop for more
 * than [delistGraceDays] trading days is treated as taken over and turned into cash at its last price.
 */
class Book(
    var cash: Double,
    val shares: MutableMap<String, Double> = linkedMapOf(),
    val lastPx: MutableMap<String, Double> = mutableMapOf(),
) {
    fun value(): Double = cash + shares.entries.sumOf { (s, n) -> n * (lastPx[s] ?: 0.0) }

    /** Delistings and dividends for one trading day. */
    fun dailyStep(
        day: LocalDate,
        data: TsxData,
        drip: Boolean,
        delistGraceDays: Int,
        delisted: (String, Double) -> Unit = { _, _ -> },
    ): List<DividendPaid> {
        for (s in shares.keys.toList()) {
            val p = data.close(s, day)
            if (p == null) {
                val last = data.lastDay(s)
                if (last == null || data.daysBetween(last, day).size - 1 > delistGraceDays) {
                    val proceeds = (shares.remove(s) ?: 0.0) * (lastPx[s] ?: 0.0)
                    cash += proceeds
                    delisted(s, proceeds)
                }
            } else {
                lastPx[s] = p
            }
        }
        val paid = mutableListOf<DividendPaid>()
        for (s in shares.keys.toList()) {
            val a = data.payouts[s]?.get(day) ?: continue
            if (a <= 0) continue
            val amt = (shares[s] ?: 0.0) * a
            val p = data.close(s, day)
            if (drip) {
                if (p != null && p > 0) shares[s] = (shares[s] ?: 0.0) + amt / p else cash += amt
                paid += DividendPaid(day, s, amt, true)
            } else {
                paid += DividendPaid(day, s, amt, false)
            }
        }
        return paid
    }

    /**
     * Re-weights to [targets] at [day]'s closes; a target without a price that day stays in cash.
     * Returns the trades and the traded value.
     */
    fun rebalance(
        day: LocalDate,
        targets: Map<String, Double>,
        data: TsxData,
        cost: Double,
    ): Pair<List<Trade>, Double> {
        val v = value()
        val tgt = targets.mapNotNull { (s, w) -> data.close(s, day)?.takeIf { w > 0 && it > 0 }?.let { s to v * w / it } }.toMap()
        val trades = mutableListOf<Trade>()
        var turnover = 0.0
        for (s in (shares.keys + tgt.keys).toList()) {
            val p = data.close(s, day) ?: lastPx[s] ?: continue
            val delta = (tgt[s] ?: 0.0) - (shares[s] ?: 0.0)
            if (delta == 0.0) continue
            cash -= delta * p + kotlin.math.abs(delta * p) * cost
            turnover += kotlin.math.abs(delta * p)
            trades += Trade(day, s, delta, p)
            if ((tgt[s] ?: 0.0) > 0) {
                shares[s] = tgt.getValue(s)
                lastPx[s] = p
            } else {
                shares.remove(s)
            }
        }
        return trades to turnover
    }
}
