package app.strategyforge.engine.strategy

import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.market.Timeframe
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * D-042 calendar levels, higher-timeframe indicators, VWAP, anchored VWAP, Fibonacci and volume
 * profile: hand-checked values, the right period boundaries, and no use of later bars.
 */
class LevelIndicatorsTest {
    private val crypto6h = SeriesContext.of(Timeframe.H4, AssetClass.CRYPTO).copy(barDuration = Duration.ofHours(6))

    /** Four 6-hour bars per UTC day; day d (0-based) trades around 100 + 10d. */
    private fun days(
        n: Int,
        start: String = "2026-06-01T00:00:00Z",
    ): List<CandleData> =
        (0 until n * 4).map { k ->
            val d = k / 4
            val base = 100 + 10 * d
            val q = k % 4
            CandleData(
                Instant.parse(start).plus(Duration.ofHours(6L * k)),
                BigDecimal(base + q),
                BigDecimal(base + q + 3),
                BigDecimal(base + q - 2),
                BigDecimal(base + q + 1),
                BigDecimal(10 + q),
            )
        }

    private fun spec(
        type: IndicatorType,
        period: Int? = null,
        timeframe: Anchor? = null,
        anchor: Anchor? = null,
        point: AnchorPoint? = null,
    ) = IndicatorSpec("X", type, period, null, null, null, null, PriceField.CLOSE, timeframe, anchor, point)

    private fun BigDecimal?.num() = this?.toDouble()

    @Test
    fun `previous-day levels appear from the first bar of the next day and today's levels build up`() {
        val bars = days(3)
        val v = Indicators.compute(listOf(spec(IndicatorType.PERIOD_LEVELS, anchor = Anchor.DAY)), bars, crypto6h)
        // Day 0 has no previous day.
        assertThat(v.getValue("X.prevHigh").take(4)).containsOnlyNulls()
        // Day 0: highs 103..106, lows 98..101, open 100, close 104.
        assertThat(v.getValue("X.prevHigh")[4].num()).isEqualTo(106.0)
        assertThat(v.getValue("X.prevLow")[4].num()).isEqualTo(98.0)
        assertThat(v.getValue("X.prevClose")[4].num()).isEqualTo(104.0)
        assertThat(v.getValue("X.prevEq")[4].num()).isEqualTo(102.0)
        assertThat(v.getValue("X.prevOpen")[7].num()).isEqualTo(100.0)
        // Day 1 opens at 110; its high so far grows bar by bar.
        assertThat(v.getValue("X.open").subList(4, 8).map { it.num() }).containsOnly(110.0)
        assertThat(v.getValue("X.high").subList(4, 8).map { it.num() }).containsExactly(113.0, 114.0, 115.0, 116.0)
        assertThat(v.getValue("X.low")[7].num()).isEqualTo(108.0)
    }

    @Test
    fun `weeks start on Monday and US stock days follow New York time`() {
        assertThat(Periods.start(Instant.parse("2026-06-04T15:00:00Z"), Anchor.WEEK, SeriesContext.zoneFor(AssetClass.CRYPTO))).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
        assertThat(Periods.start(Instant.parse("2026-06-15T10:00:00Z"), Anchor.MONTH, SeriesContext.zoneFor(AssetClass.CRYPTO))).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
        // 02:00 UTC is still the previous evening in New York.
        assertThat(Periods.start(Instant.parse("2026-06-04T02:00:00Z"), Anchor.DAY, SeriesContext.zoneFor(AssetClass.US_EQUITY))).isEqualTo(Instant.parse("2026-06-03T04:00:00Z"))
    }

    @Test
    fun `a daily indicator inside a faster strategy uses only completed days`() {
        val bars = days(4)
        val sma = Indicators.compute(listOf(spec(IndicatorType.SMA, period = 2, timeframe = Anchor.DAY)), bars, crypto6h).getValue("X.value")
        // Daily closes: 104, 114, 124, 134. The 2-day SMA exists once two days are complete.
        assertThat(sma.take(7)).containsOnlyNulls()
        // The last bar of day 1 closes at midnight: day 1 is complete at that bar's close.
        assertThat(sma[7].num()).isEqualTo(109.0)
        assertThat(sma.subList(8, 11).map { it.num() }).containsOnly(109.0)
        assertThat(sma[11].num()).isEqualTo(119.0)
    }

    @Test
    fun `session VWAP resets each day and includes the current bar`() {
        val bars = days(2)
        val vwap = Indicators.compute(listOf(spec(IndicatorType.VWAP, anchor = Anchor.DAY)), bars, crypto6h).getValue("X.value")

        fun tp(b: CandleData) = (b.high.toDouble() + b.low.toDouble() + b.close.toDouble()) / 3
        assertThat(vwap[0].num()).isCloseTo(tp(bars[0]), within(1e-9))
        val expected = (0..1).sumOf { tp(bars[it]) * bars[it].volume.toDouble() } / (bars[0].volume.toDouble() + bars[1].volume.toDouble())
        assertThat(vwap[1].num()).isCloseTo(expected, within(1e-9))
        assertThat(vwap[4].num()).`as`("new day").isCloseTo(tp(bars[4]), within(1e-9))
    }

    @Test
    fun `anchored VWAP starts at the lowest low of the previous bars`() {
        val bars = days(2)
        val v = Indicators.compute(listOf(spec(IndicatorType.ANCHORED_VWAP, period = 4, point = AnchorPoint.LOWEST_LOW)), bars, crypto6h).getValue("X.value")
        assertThat(v.take(4)).containsOnlyNulls()

        // At bar 4 the previous four bars are 0..3; the lowest low is bar 0, so the VWAP spans bars 0..4.
        fun tp(b: CandleData) = (b.high.toDouble() + b.low.toDouble() + b.close.toDouble()) / 3
        val expected = (0..4).sumOf { tp(bars[it]) * bars[it].volume.toDouble() } / (0..4).sumOf { bars[it].volume.toDouble() }
        assertThat(v[4].num()).isCloseTo(expected, within(1e-9))
    }

    @Test
    fun `Fibonacci levels are measured from the latest extreme of the range`() {
        val rising = days(2)
        val f = Indicators.compute(listOf(spec(IndicatorType.FIBONACCI, period = 4)), rising, crypto6h)
        // At bar 7 the previous four bars (3..6) make a low of 101 and then a high of 115: an up move.
        val i = 7
        assertThat(f.getValue("X.high")[i].num()).isEqualTo(115.0)
        assertThat(f.getValue("X.low")[i].num()).isEqualTo(101.0)
        val h = f.getValue("X.high")[i]!!.toDouble()
        val l = f.getValue("X.low")[i]!!.toDouble()
        assertThat(f.getValue("X.trend")[i].num()).isEqualTo(1.0)
        assertThat(f.getValue("X.f618")[i].num()).isCloseTo(h - (h - l) * 0.618, within(1e-9))
        assertThat(f.getValue("X.f660")[i].num()).isCloseTo(h - (h - l) * 0.66, within(1e-9))
        val falling = rising.reversed().mapIndexed { k, b -> b.copy(openTime = rising[k].openTime) }
        val g = Indicators.compute(listOf(spec(IndicatorType.FIBONACCI, period = 4)), falling, crypto6h)
        val gh = g.getValue("X.high")[i]!!.toDouble()
        val gl = g.getValue("X.low")[i]!!.toDouble()
        assertThat(g.getValue("X.trend")[i].num()).isEqualTo(-1.0)
        assertThat(g.getValue("X.f382")[i].num()).isCloseTo(gl + (gh - gl) * 0.382, within(1e-9))
    }

    @Test
    fun `the volume profile finds where most volume traded and a value area around it`() {
        val t = Instant.parse("2026-06-01T00:00:00Z")
        // Heavy volume at 100, light volume spread up to 120.
        val bars =
            (0 until 30).map { k ->
                val heavy = k % 3 != 0
                val mid = if (heavy) 100.0 else 100.0 + (k % 20)
                CandleData(t.plus(Duration.ofHours(k.toLong())), BigDecimal(mid), BigDecimal(mid + 0.5), BigDecimal(mid - 0.5), BigDecimal(mid), BigDecimal(if (heavy) 1000 else 10))
            }
        val p = Indicators.compute(listOf(spec(IndicatorType.VOLUME_PROFILE, period = 20)), bars, SeriesContext.of(Timeframe.H1, AssetClass.CRYPTO))
        val poc = p.getValue("X.poc")[25]!!.toDouble()
        val vah = p.getValue("X.vah")[25]!!.toDouble()
        val v = p.getValue("X.val")[25]!!.toDouble()
        assertThat(poc).isCloseTo(100.0, within(0.6))
        assertThat(vah).isGreaterThanOrEqualTo(poc)
        assertThat(v).isLessThanOrEqualTo(poc)
        assertThat(vah - v).`as`("the value area is narrow around the heavy volume").isLessThan(5.0)

        val daily = Indicators.compute(listOf(spec(IndicatorType.VOLUME_PROFILE, anchor = Anchor.DAY)), days(3), crypto6h)
        assertThat(daily.getValue("X.poc").take(4)).containsOnlyNulls()
        assertThat(daily.getValue("X.poc")[4]!!.toDouble()).isBetween(98.0, 106.0)
    }

    @Test
    fun `no new indicator changes its past values when later bars arrive`() {
        val base = days(20)
        val more = base + days(6, "2026-06-21T00:00:00Z").map { it.copy(high = it.high.add(BigDecimal(50)), volume = BigDecimal(999)) }
        val specs =
            listOf(
                spec(IndicatorType.PERIOD_LEVELS, anchor = Anchor.DAY),
                spec(IndicatorType.PERIOD_LEVELS, anchor = Anchor.WEEK),
                spec(IndicatorType.VWAP, anchor = Anchor.WEEK),
                spec(IndicatorType.ANCHORED_VWAP, period = 6, point = AnchorPoint.HIGHEST_HIGH),
                spec(IndicatorType.FIBONACCI, period = 8),
                spec(IndicatorType.FIBONACCI, period = 3, timeframe = Anchor.DAY),
                spec(IndicatorType.VOLUME_PROFILE, period = 8),
                spec(IndicatorType.VOLUME_PROFILE, anchor = Anchor.DAY),
                spec(IndicatorType.SWING_LOW, period = 2, timeframe = Anchor.DAY),
                spec(IndicatorType.RSI, period = 3, timeframe = Anchor.DAY),
                spec(IndicatorType.HAMMER, timeframe = Anchor.WEEK),
            )
        specs.forEach { s ->
            val a = Indicators.compute(listOf(s), base, crypto6h)
            val b = Indicators.compute(listOf(s), more, crypto6h)
            a.forEach { (k, series) -> assertThat(b.getValue(k).take(base.size)).`as`("${s.type} ${s.timeframe} ${s.anchor} $k").isEqualTo(series) }
        }
    }

    @Test
    fun `history requirements cover whole periods and are raised automatically`() {
        val weekly = spec(IndicatorType.PERIOD_LEVELS, anchor = Anchor.WEEK)
        assertThat(weekly.lookback(Timeframe.H4, AssetClass.CRYPTO)).isEqualTo(2 * 7 * 6 + 1)
        assertThat(spec(IndicatorType.SWING_LOW, period = 3, timeframe = Anchor.WEEK).lookback(Timeframe.H1, AssetClass.CRYPTO)).isEqualTo((3 * 6 + 1 + 1) * 168 + 1)
        assertThat(spec(IndicatorType.PERIOD_LEVELS, anchor = Anchor.DAY).lookback(Timeframe.H1, AssetClass.US_EQUITY)).isEqualTo(2 * 7 + 1)
    }
}
