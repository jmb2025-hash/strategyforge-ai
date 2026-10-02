package app.strategyforge.engine.market

import java.time.Duration
import java.time.Instant

/**
 * Perpetual-futures context for a crypto symbol (D-044): open interest, funding and taker delta.
 * These come from a futures exchange, not from the spot candles, so they are kept as separate
 * timestamped series and lined up with the strategy's bars without using any later data.
 */
data class DerivPoint(
    val ts: Instant,
    val value: Double,
)

data class DerivativesData(
    /** Open interest in contracts (for Kraken PF_ perpetuals, units of the coin). */
    val openInterest: List<DerivPoint>,
    /** Funding rate in percent per hour, at the time each rate applied. */
    val fundingRate: List<DerivPoint>,
    /** Taker buy volume minus taker sell volume per interval starting at [DerivPoint.ts]. */
    val delta: List<DerivPoint>,
    val source: String,
) {
    /** One value per bar: the latest point known by that bar's close. */
    fun align(
        bars: List<CandleData>,
        barDuration: Duration,
    ): AlignedDerivatives = AlignedDerivatives(alignIntervals(openInterest, bars, barDuration), alignEvents(fundingRate, bars, barDuration), alignDelta(delta, bars, barDuration))

    companion object {
        val EMPTY = DerivativesData(emptyList(), emptyList(), emptyList(), "none")

        /**
         * Interval series (open interest, analytics) are labelled with the interval's start, so a point
         * labelled at or before a bar's open covers time up to that bar's close at the latest.
         */
        private fun alignIntervals(
            points: List<DerivPoint>,
            bars: List<CandleData>,
            barDuration: Duration,
        ): List<Double?> = latestAtOrBefore(points, bars) { it.openTime }.let { stale(it, points, bars, barDuration) }

        /**
         * Funding rates are stamped when they applied. Only rates stamped at or before the bar's open are
         * used, so a rate published right at the bar's close is never assumed to be known in time.
         */
        private fun alignEvents(
            points: List<DerivPoint>,
            bars: List<CandleData>,
            barDuration: Duration,
        ): List<Double?> {
            val values = latestAtOrBefore(points, bars) { it.openTime }
            val sorted = points.sortedBy { it.ts }
            var j = -1
            return bars.mapIndexed { i, b ->
                while (j + 1 < sorted.size && !sorted[j + 1].ts.isAfter(b.openTime)) j++
                // Funding is hourly; older than a day (or two bars on daily charts) means the feed stopped.
                val limit = maxOf(Duration.ofHours(25), barDuration.multipliedBy(2))
                if (j >= 0 && Duration.between(sorted[j].ts, b.openTime) > limit) null else values[i]
            }
        }

        /**
         * Delta is summed over every interval that starts inside the bar, so a bar built from finer
         * intervals gets the whole bar's buy-minus-sell volume; a bar with no interval gets null.
         */
        private fun alignDelta(
            points: List<DerivPoint>,
            bars: List<CandleData>,
            barDuration: Duration,
        ): List<Double?> {
            val sorted = points.sortedBy { it.ts }
            var j = 0
            return bars.map { b ->
                val end = b.openTime.plus(barDuration)
                while (j < sorted.size && sorted[j].ts.isBefore(b.openTime)) j++
                var sum = 0.0
                var any = false
                var k = j
                while (k < sorted.size && sorted[k].ts.isBefore(end)) {
                    sum += sorted[k].value
                    any = true
                    k++
                }
                if (any) sum else null
            }
        }

        private fun latestAtOrBefore(
            points: List<DerivPoint>,
            bars: List<CandleData>,
            cutoff: (CandleData) -> Instant,
        ): List<Double?> {
            val sorted = points.sortedBy { it.ts }
            var j = -1
            return bars.map { b ->
                val t = cutoff(b)
                while (j + 1 < sorted.size && !sorted[j + 1].ts.isAfter(t)) j++
                if (j >= 0) sorted[j].value else null
            }
        }

        /** A value older than three bars (or an hour, whichever is longer) is treated as missing. */
        private fun stale(
            values: List<Double?>,
            points: List<DerivPoint>,
            bars: List<CandleData>,
            barDuration: Duration,
        ): List<Double?> {
            val sorted = points.sortedBy { it.ts }
            val limit = maxOf(barDuration.multipliedBy(3), Duration.ofHours(1))
            var j = -1
            return bars.mapIndexed { i, b ->
                while (j + 1 < sorted.size && !sorted[j + 1].ts.isAfter(b.openTime)) j++
                if (j >= 0 && Duration.between(sorted[j].ts, b.openTime) > limit) null else values[i]
            }
        }
    }
}

/** Derivatives values lined up one per strategy bar; null where nothing was known yet. */
data class AlignedDerivatives(
    val openInterest: List<Double?>,
    val fundingRate: List<Double?>,
    val delta: List<Double?>,
)

/** A source of perpetual-futures context for crypto symbols. */
interface DerivativesProvider {
    val name: String

    /** The futures contract that tracks a spot symbol, or null when there is none. */
    fun contractFor(symbol: String): String?

    fun history(
        symbol: String,
        timeframe: Timeframe,
        from: Instant,
        to: Instant,
        now: Instant,
    ): ProviderResult<DerivativesData>

    fun diagnose(now: Instant): ProviderTestResult
}
