package app.strategyforge.engine.tsx

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/**
 * Made-up daily TSX history for tests: every listing trades on weekdays from [START], drifts
 * upward at its own pace and, for stocks, pays a quarterly dividend on the 15th of Mar/Jun/Sep/Dec.
 */
object SyntheticTsx {
    val START: LocalDate = LocalDate.parse("2023-01-02")

    private fun seed(s: String) = abs(s.hashCode() % 1000) / 1000.0

    fun close(
        s: String,
        d: LocalDate,
    ): Double {
        val t = ChronoUnit.DAYS.between(START, d).toDouble()
        val k = seed(s)
        return 20 * (1 + k) * exp((0.0001 + 0.0004 * k) * t + 0.04 * sin(t / 15.0 + k * 6))
    }

    val provider =
        TsxHistoryProvider { s, from, to ->
            val k = seed(s)
            val bars = mutableListOf<TsxBar>()
            var d = maxOf(from, START)
            while (!d.isAfter(to)) {
                if (d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY) {
                    val c = close(s, d)
                    bars += TsxBar(d, c, c * (1 + 0.0001 * ChronoUnit.DAYS.between(START, d)))
                }
                d = d.plusDays(1)
            }
            val divs =
                if (TsxCatalog.listing(s)?.isStock == true) {
                    generateSequence(LocalDate.of(START.year, 3, 15)) { it.plusMonths(3) }
                        .takeWhile { !it.isAfter(to) }
                        .filter { !it.isBefore(maxOf(from, START)) }
                        .associateWith { close(s, it) * (0.006 + 0.012 * k) }
                } else {
                    emptyMap()
                }
            TsxFetch.Ok(bars, divs)
        }
}
