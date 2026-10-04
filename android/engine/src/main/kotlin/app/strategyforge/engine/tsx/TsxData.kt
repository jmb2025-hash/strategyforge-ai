package app.strategyforge.engine.tsx

import java.time.LocalDate

/** One listing's daily history: closes (split-adjusted), total-return closes and dividends by ex-date. */
class TsxSeries(
    val dates: List<LocalDate>,
    val close: DoubleArray,
    val adj: DoubleArray,
    val dividends: Map<LocalDate, Double>,
) {
    private val byDate = HashMap<LocalDate, Int>(dates.size * 2).also { m -> dates.forEachIndexed { i, d -> m[d] = i } }
    val first: LocalDate? get() = dates.firstOrNull()
    val last: LocalDate? get() = dates.lastOrNull()

    fun indexOn(d: LocalDate): Int? = byDate[d]

    /** Index of the last bar on or before [d], or -1. */
    fun asOfIndex(d: LocalDate): Int {
        var lo = 0
        var hi = dates.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (dates[mid] <= d) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    fun closeOn(d: LocalDate): Double? = indexOn(d)?.let { close[it] }

    fun closeAsOf(d: LocalDate): Double? = asOfIndex(d).takeIf { it >= 0 }?.let { close[it] }

    fun adjAsOf(d: LocalDate): Double? = asOfIndex(d).takeIf { it >= 0 }?.let { adj[it] }

    /** Dividends with ex-dates in (d - 1 year, d]. */
    fun ttmDividends(d: LocalDate): Double {
        val from = d.minusYears(1)
        return dividends.entries.sumOf { (k, v) -> if (k > from && k <= d) v else 0.0 }
    }
}

/**
 * Daily TSX history for the plan engine (D-055). The trading calendar is every date any listing
 * traded. Dividends whose ex-date is not a trading day are paid on the next trading day.
 */
class TsxData(
    val series: Map<String, TsxSeries>,
    /** The first date the data could cover (history before it was not loaded). */
    val dataStart: LocalDate,
) {
    val calendar: List<LocalDate> =
        series.values
            .flatMap { it.dates }
            .toSortedSet()
            .toList()
    private val calIndex = HashMap<LocalDate, Int>().also { m -> calendar.forEachIndexed { i, d -> m[d] = i } }

    /** Dividends paid per trading day (ex-dates moved to the next trading day). */
    val payouts: Map<String, Map<LocalDate, Double>> =
        series.mapValues { (_, s) ->
            val out = HashMap<LocalDate, Double>()
            s.dividends.forEach { (d, a) ->
                val day = if (calIndex.containsKey(d)) d else calendar.firstOrNull { it > d }
                if (day != null) out[day] = (out[day] ?: 0.0) + a
            }
            out
        }

    fun daysBetween(
        from: LocalDate,
        to: LocalDate,
    ): List<LocalDate> = calendar.filter { it >= from && it <= to }

    fun close(
        sym: String,
        d: LocalDate,
    ): Double? = series[sym]?.closeOn(d)

    fun lastDay(sym: String): LocalDate? = series[sym]?.last

    /** Total return over the last [months] months, from total-return closes as of each date. */
    fun momentum(
        sym: String,
        d: LocalDate,
        months: Int,
    ): Double? {
        val s = series[sym] ?: return null
        val p0 = s.adjAsOf(d.minusMonths(months.toLong())) ?: return null
        val p1 = s.adjAsOf(d) ?: return null
        if (p0 == 0.0) return null
        return p1 / p0 - 1
    }

    fun barsUpTo(
        sym: String,
        d: LocalDate,
    ): Int = (series[sym]?.asOfIndex(d) ?: -1) + 1

    /** The last close is above the average of the last [n] closes (needs [n] bars). */
    fun aboveSma(
        sym: String,
        d: LocalDate,
        n: Int,
    ): Boolean {
        val s = series[sym] ?: return false
        val i = s.asOfIndex(d)
        if (i + 1 < n) return false
        var sum = 0.0
        for (k in i - n + 1..i) sum += s.close[k]
        return s.close[i] > sum / n
    }

    /** Yield, cut flag and whether it pays at all, from dividends on or before [d] (D-055). */
    fun dividendFeatures(
        sym: String,
        d: LocalDate,
    ): DividendFeatures? {
        val s = series[sym] ?: return null
        val p = s.closeAsOf(d) ?: return null
        if (p <= 0) return null
        val first = s.first ?: return null
        val floor = maxOf(first, dataStart)
        val ys = (0..3).map { k -> s.ttmDividends(d.minusYears(k.toLong())) }
        // Only years that lie fully inside the loaded history count towards the cut screen.
        val avail = ys.filterIndexed { k, _ -> !d.minusYears((k + 1).toLong()).isBefore(floor) }
        val cut = avail.size >= 2 && (0 until avail.size - 1).any { k -> avail[k] < 0.95 * avail[k + 1] }
        return DividendFeatures(ys[0] / p, cut, ys[0] > 0)
    }
}

data class DividendFeatures(
    val yld: Double,
    val cut: Boolean,
    val pays: Boolean,
)
