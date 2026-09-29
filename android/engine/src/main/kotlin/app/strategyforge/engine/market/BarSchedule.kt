package app.strategyforge.engine.market

import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.market.MarketCalendar
import app.strategyforge.engine.market.Timeframe
import app.strategyforge.engine.market.barCloseTime
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Expected bar timetable. Equity intraday bars align to the session open (09:30 ET) and
 * never extend past the session close; equity daily bars are sessions; crypto bars are
 * UTC-aligned and continuous.
 */
object BarSchedule {
    fun closeTime(
        assetClass: AssetClass,
        tf: Timeframe,
        openTime: Instant,
    ): Instant = barCloseTime(assetClass, tf, openTime)

    /** Bucket start for [t] in timeframe [tf]. */
    fun bucketStart(
        assetClass: AssetClass,
        tf: Timeframe,
        t: Instant,
    ): Instant? {
        if (assetClass == AssetClass.CRYPTO) {
            val sec = tf.duration.seconds
            return Instant.ofEpochSecond(Math.floorDiv(t.epochSecond, sec) * sec)
        }
        val session = MarketCalendar.currentOrPreviousSession(t) ?: return null
        if (!t.isBefore(session.close)) return null
        if (tf == Timeframe.D1) return session.open
        val k = Duration.between(session.open, t).seconds / tf.duration.seconds
        return session.open.plusSeconds(k * tf.duration.seconds)
    }

    /** All expected bar starts in [from, to). */
    fun expected(
        assetClass: AssetClass,
        tf: Timeframe,
        from: Instant,
        to: Instant,
    ): List<Instant> {
        val out = mutableListOf<Instant>()
        if (assetClass == AssetClass.CRYPTO) {
            var t = bucketStart(assetClass, tf, from)!!
            if (t.isBefore(from)) t = t.plus(tf.duration)
            while (t.isBefore(to)) {
                out += t
                t = t.plus(tf.duration)
            }
            return out
        }
        var d: LocalDate = from.atZone(MarketCalendar.NEW_YORK).toLocalDate()
        val last = to.atZone(MarketCalendar.NEW_YORK).toLocalDate()
        while (!d.isAfter(last)) {
            MarketCalendar.session(d)?.let { s ->
                if (tf == Timeframe.D1) {
                    if (!s.open.isBefore(from) && s.open.isBefore(to)) out += s.open
                } else {
                    var t = s.open
                    while (t.isBefore(s.close)) {
                        if (!t.isBefore(from) && t.isBefore(to)) out += t
                        t = t.plus(tf.duration)
                    }
                }
            }
            d = d.plusDays(1)
        }
        return out
    }

    /** Expected bars between the first and last present bar that are absent. */
    fun missing(
        assetClass: AssetClass,
        tf: Timeframe,
        present: List<Instant>,
    ): List<Instant> {
        if (present.size < 2) return emptyList()
        val have = present.toHashSet()
        return expected(assetClass, tf, present.first(), present.last().plusSeconds(1)).filter { it !in have }
    }

    /** Start of the most recent bar that has fully closed at [now], or null if the calendar cannot say. */
    fun lastClosedBarStart(
        assetClass: AssetClass,
        tf: Timeframe,
        now: Instant,
    ): Instant? {
        if (assetClass == AssetClass.CRYPTO) return bucketStart(assetClass, tf, now)!!.minus(tf.duration)
        val from = now.minus(Duration.ofDays(10))
        if (!MarketCalendar.covers(from.atZone(ZoneOffset.UTC).toLocalDate()) || !MarketCalendar.covers(now.atZone(ZoneOffset.UTC).toLocalDate())) return null
        return expected(assetClass, tf, from, now).lastOrNull { !closeTime(assetClass, tf, it).isAfter(now) }
    }

    /** Wall-time span that comfortably contains [bars] bars of [tf]. */
    fun lookback(
        assetClass: AssetClass,
        tf: Timeframe,
        bars: Int,
    ): Duration {
        if (assetClass == AssetClass.CRYPTO) return tf.duration.multipliedBy(bars.toLong() + 1)
        val perDay =
            when (tf) {
                Timeframe.D1 -> 1L
                Timeframe.H4 -> 2L
                Timeframe.H1 -> 7L
                Timeframe.M15 -> 26L
                Timeframe.M5 -> 78L
                Timeframe.M1 -> 390L
            }
        val tradingDays = (bars + perDay - 1) / perDay + 1
        // Trading days to calendar days (weekends and holidays), plus margin.
        return Duration.ofDays(tradingDays * 7 / 5 + 5)
    }
}

/** Builds coarser bars from finer ones. Incomplete buckets are omitted and reported as gaps. */
object CandleAggregator {
    fun aggregate(
        fine: List<CandleData>,
        assetClass: AssetClass,
        source: Timeframe,
        target: Timeframe,
        asOf: Instant,
    ): Pair<List<CandleData>, List<Instant>> {
        val groups = linkedMapOf<Instant, MutableList<CandleData>>()
        fine.forEach { b -> BarSchedule.bucketStart(assetClass, target, b.openTime)?.let { groups.getOrPut(it) { mutableListOf() }.add(b) } }
        val out = mutableListOf<CandleData>()
        val gaps = mutableListOf<Instant>()
        groups.forEach { (start, bars) ->
            val close = BarSchedule.closeTime(assetClass, target, start)
            if (close.isAfter(asOf)) return@forEach
            val expected = BarSchedule.expected(assetClass, source, start, close).size
            if (bars.size != expected) {
                gaps += start
                return@forEach
            }
            out +=
                CandleData(
                    start,
                    bars.first().open,
                    bars.maxOf { it.high },
                    bars.minOf { it.low },
                    bars.last().close,
                    bars.fold(BigDecimal.ZERO) { acc, b -> acc.add(b.volume) },
                )
        }
        return out to gaps
    }
}
