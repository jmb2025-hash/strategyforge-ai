package app.strategyforge.engine.tsx

import java.time.LocalDate
import kotlin.math.pow

data class SimPoint(
    val day: LocalDate,
    val value: Double,
)

data class SimResult(
    val values: List<SimPoint>,
    val dividends: List<DividendPaid>,
    val trades: Int,
    val turnover: Double,
    val lastTargets: Map<String, Double>,
    val lastRebalance: LocalDate?,
)

data class SimMetrics(
    val cagrPercent: Double,
    val totalPercent: Double,
    val maxDrawdownPercent: Double,
    val worst12mPercent: Double?,
    val positive12mPercent: Double?,
)

/** Backtests a portfolio plan over loaded TSX history (D-055), the same way the live runs trade. */
object PlanSimulator {
    fun run(
        plan: PortfolioPlan,
        data: TsxData,
        listings: List<TsxListing>,
        from: LocalDate,
        to: LocalDate,
        capital: Double,
        drip: Boolean,
        cost: Double = plan.cost,
    ): SimResult {
        val rules = PlanRules(data, listings)
        val book = Book(capital)
        val state = mutableMapOf<Int, SleevePick>()
        val values = mutableListOf<SimPoint>()
        val divs = mutableListOf<DividendPaid>()
        var lastPeriod: Int? = null
        var trades = 0
        var turnover = 0.0
        var lastTargets = emptyMap<String, Double>()
        var lastRebalance: LocalDate? = null
        for (day in data.daysBetween(from, to)) {
            divs += book.dailyStep(day, data, drip, delistGraceDays = 0)
            val period = plan.rebalance.period(day)
            if (period != lastPeriod) {
                lastTargets = rules.targets(plan, day, state)
                val (t, v) = book.rebalance(day, lastTargets, data, cost)
                trades += t.size
                turnover += v
                lastPeriod = period
                lastRebalance = day
            }
            values += SimPoint(day, book.value())
        }
        return SimResult(values, divs, trades, turnover, lastTargets, lastRebalance)
    }

    fun metrics(values: List<SimPoint>): SimMetrics {
        if (values.size < 2) return SimMetrics(0.0, 0.0, 0.0, null, null)
        val v0 = values.first().value
        val years = (values.last().day.toEpochDay() - values.first().day.toEpochDay()) / 365.25
        val total = values.last().value / v0
        var peak = 0.0
        var dd = 0.0
        values.forEach {
            peak = maxOf(peak, it.value)
            dd = minOf(dd, it.value / peak - 1)
        }
        val monthly = monthEnds(values)
        val r12 = (12 until monthly.size).map { monthly[it].value / monthly[it - 12].value - 1 }
        return SimMetrics(
            cagrPercent = 100 * (total.pow(1 / years) - 1),
            totalPercent = 100 * (total - 1),
            maxDrawdownPercent = 100 * dd,
            worst12mPercent = r12.minOrNull()?.let { it * 100 },
            positive12mPercent = if (r12.isEmpty()) null else 100.0 * r12.count { it > 0 } / r12.size,
        )
    }

    /** The last value of each calendar month. */
    fun monthEnds(values: List<SimPoint>): List<SimPoint> = values.groupBy { it.day.year * 100 + it.day.monthValue }.values.map { it.last() }

    /** Dividends received per calendar month, keyed yyyy-MM. */
    fun monthlyDividends(divs: List<DividendPaid>): Map<String, Double> = divs.groupBy { "%04d-%02d".format(it.day.year, it.day.monthValue) }.mapValues { (_, v) -> v.sumOf { it.amount } }.toSortedMap()
}
