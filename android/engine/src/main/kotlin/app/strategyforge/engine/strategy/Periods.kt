package app.strategyforge.engine.strategy

import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.market.MarketCalendar
import app.strategyforge.engine.market.Timeframe
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

/** How the strategy's bars map onto calendar periods: their duration and the calendar's time zone. */
data class SeriesContext(
    val barDuration: Duration,
    val zone: ZoneId,
    /** Futures context lined up with the bars (D-044); null when the strategy does not use it. */
    val derivatives: app.strategyforge.engine.market.AlignedDerivatives? = null,
) {
    companion object {
        fun of(
            timeframe: Timeframe,
            assetClass: AssetClass,
        ) = SeriesContext(timeframe.duration, zoneFor(assetClass))

        fun zoneFor(assetClass: AssetClass): ZoneId = if (assetClass == AssetClass.US_EQUITY) MarketCalendar.NEW_YORK else ZoneOffset.UTC

        /** For callers without a strategy: UTC calendar, bar duration from the first two bars. */
        fun infer(bars: List<CandleData>) =
            SeriesContext(
                if (bars.size >= 2) Duration.between(bars[0].openTime, bars[1].openTime).takeIf { !it.isNegative && !it.isZero } ?: Duration.ofMinutes(1) else Duration.ofMinutes(1),
                ZoneOffset.UTC,
            )
    }
}

/** One daily, weekly or monthly bar built from strategy bars. */
data class PeriodBar(
    val start: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
    /** Index range of the strategy bars inside this period. */
    val firstIndex: Int,
    val lastIndex: Int,
) {
    fun toCandle() = CandleData(start, open, high, low, close, volume)
}

/**
 * Calendar periods (D-042). A period is complete only once a strategy bar closes at or after the
 * period's end, or a bar from a later period arrives; so at bar i only periods finished by bar i's
 * close are "previous" periods and nothing from the future is ever used.
 */
object Periods {
    fun start(
        t: Instant,
        anchor: Anchor,
        zone: ZoneId,
    ): Instant {
        val d = t.atZone(zone).toLocalDate()
        val day =
            when (anchor) {
                Anchor.DAY -> d
                Anchor.WEEK -> d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                Anchor.MONTH -> d.withDayOfMonth(1)
            }
        return day.atStartOfDay(zone).toInstant()
    }

    fun end(
        start: Instant,
        anchor: Anchor,
        zone: ZoneId,
    ): Instant {
        val d = start.atZone(zone).toLocalDate()
        val next =
            when (anchor) {
                Anchor.DAY -> d.plusDays(1)
                Anchor.WEEK -> d.plusWeeks(1)
                Anchor.MONTH -> d.plusMonths(1)
            }
        return next.atStartOfDay(zone).toInstant()
    }

    /** Upper bound of strategy bars in one period, used for history requirements. */
    fun barsPer(
        anchor: Anchor,
        base: Timeframe,
        assetClass: AssetClass,
    ): Int {
        val minutes = base.duration.toMinutes().coerceAtLeast(1)
        val perDay =
            if (assetClass == AssetClass.US_EQUITY) {
                // Regular session: 6.5 hours; a daily bar is one bar.
                if (base == Timeframe.D1) 1 else ((390 + minutes - 1) / minutes).toInt()
            } else {
                ((1440 + minutes - 1) / minutes).toInt()
            }
        return when (anchor) {
            Anchor.DAY -> perDay
            Anchor.WEEK -> perDay * if (assetClass == AssetClass.US_EQUITY) 5 else 7
            Anchor.MONTH -> perDay * if (assetClass == AssetClass.US_EQUITY) 23 else 31
        }
    }

    /**
     * Aggregates strategy bars into calendar periods. [completedBefore] (i) is the number of periods
     * complete at the close of bar i; [periodOf] (i) is the index of the period containing bar i.
     */
    class Aggregation(
        val periods: List<PeriodBar>,
        val completedAt: IntArray,
        val periodOf: IntArray,
    )

    fun aggregate(
        bars: List<CandleData>,
        anchor: Anchor,
        ctx: SeriesContext,
    ): Aggregation {
        val periods = mutableListOf<PeriodBar>()
        val completedAt = IntArray(bars.size)
        val periodOf = IntArray(bars.size)
        var completed = 0
        for (i in bars.indices) {
            val b = bars[i]
            val start = start(b.openTime, anchor, ctx.zone)
            val last = periods.lastOrNull()
            if (last == null || last.start != start) {
                // A bar from a new period closes the previous one.
                completed = periods.size
                periods += PeriodBar(start, b.open, b.high, b.low, b.close, b.volume, i, i)
            } else {
                periods[periods.lastIndex] = last.copy(high = last.high.max(b.high), low = last.low.min(b.low), close = b.close, volume = last.volume.add(b.volume), lastIndex = i)
            }
            periodOf[i] = periods.lastIndex
            if (!b.openTime.plus(ctx.barDuration).isBefore(end(start, anchor, ctx.zone))) completed = periods.size
            completedAt[i] = completed
        }
        return Aggregation(periods, completedAt, periodOf)
    }
}
