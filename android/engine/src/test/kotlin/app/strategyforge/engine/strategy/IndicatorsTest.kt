package app.strategyforge.engine.strategy

import app.strategyforge.engine.market.CandleData
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.IntRange
import net.jqwik.api.constraints.Size
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class IndicatorsTest {
    private fun d(vararg xs: Int) = xs.map { BigDecimal(it) }

    private fun bar(
        i: Int,
        o: String,
        h: String,
        l: String,
        c: String,
    ) = CandleData(Instant.ofEpochSecond(i * 3600L), BigDecimal(o), BigDecimal(h), BigDecimal(l), BigDecimal(c), BigDecimal(1000))

    @Test
    fun `FR-045 SMA and EMA match hand-computed values`() {
        assertThat(Indicators.sma(d(1, 2, 3, 4, 5), 3).map { it?.stripTrailingZeros()?.toPlainString() }).containsExactly(null, null, "2", "3", "4")
        assertThat(Indicators.ema(d(1, 2, 3, 4, 5, 6), 3).map { it?.stripTrailingZeros()?.toPlainString() }).containsExactly(null, null, "2", "3", "4", "5")
    }

    @Test
    fun `FR-045 RSI uses Wilder smoothing`() {
        val r = Indicators.rsi(d(1, 2, 1, 2, 3), 2)
        assertThat(r[2]).isEqualByComparingTo("50")
        assertThat(r[3]).isEqualByComparingTo("75")
        assertThat(r[4]).isEqualByComparingTo("87.5")
        assertThat(Indicators.rsi((1..20).map { BigDecimal(it) }, 14)[19]).isEqualByComparingTo("100")
    }

    @Test
    fun `FR-045 ATR, Bollinger Bands and MACD behave on known series`() {
        val bars = (0 until 20).map { bar(it, "10", "11", "9", "10") }
        assertThat(Indicators.atr(bars, 5)[19]).isEqualByComparingTo("2")
        val flat = List(30) { BigDecimal("50") }
        val bb = Indicators.bollinger(flat, 20, BigDecimal(2))
        assertThat(bb.upper[29]).isEqualByComparingTo("50")
        assertThat(bb.lower[29]).isEqualByComparingTo("50")
        val m = Indicators.macd(List(40) { BigDecimal("50") }, 12, 26, 9)
        assertThat(Indicators.macd(flat, 12, 26, 9).histogram.last()).`as`("signal needs slow + signal - 1 bars").isNull()
        assertThat(m.value[29]).isEqualByComparingTo("0")
        assertThat(m.histogram.last()).isEqualByComparingTo("0")
        val bb2 = Indicators.bollinger(d(1, 2, 3, 4), 4, BigDecimal.ONE)
        // population sd of 1..4 = sqrt(1.25)
        assertThat(bb2.upper[3]!!.subtract(BigDecimal("2.5")).setScale(10, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo("1.1180339887")
    }

    @Property(tries = 100)
    fun `FR-050 indicators are causal - values never change when future bars are appended`(
        @ForAll @Size(min = 40, max = 80) prices: List<
            @IntRange(min = 1, max = 1000)
            Int,
        >,
        @ForAll @IntRange(min = 30, max = 39) cut: Int,
    ) {
        val bars = prices.mapIndexed { i, p -> bar(i, "$p", "${p + 2}", "${maxOf(1, p - 2)}", "$p") }
        val specs =
            listOf(
                IndicatorSpec("S", IndicatorType.SMA, 5, null, null, null, null, PriceField.CLOSE),
                IndicatorSpec("E", IndicatorType.EMA, 7, null, null, null, null, PriceField.CLOSE),
                IndicatorSpec("R", IndicatorType.RSI, 6, null, null, null, null, PriceField.CLOSE),
                IndicatorSpec("A", IndicatorType.ATR, 5, null, null, null, null, PriceField.CLOSE),
                IndicatorSpec("M", IndicatorType.MACD, null, 3, 8, 4, null, PriceField.CLOSE),
                IndicatorSpec("B", IndicatorType.BOLLINGER_BANDS, 10, null, null, null, BigDecimal(2), PriceField.CLOSE),
            )
        val full = Indicators.compute(specs, bars)
        val prefix = Indicators.compute(specs, bars.take(cut))
        prefix.forEach { (k, v) -> v.forEachIndexed { i, x -> assertThat(full.getValue(k)[i]).`as`("$k[$i]").isEqualTo(x) } }
    }
}
