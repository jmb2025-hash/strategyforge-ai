package app.strategyforge.engine.strategy

import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.market.DerivPoint
import app.strategyforge.engine.market.DerivativesData
import app.strategyforge.engine.market.Timeframe
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/** D-044 open interest, funding and CVD: lined up with bars without later data, and computed as described. */
class DerivativesIndicatorsTest {
    private val t0 = Instant.parse("2026-06-01T00:00:00Z")
    private val hour = Duration.ofHours(1)

    private fun bars(n: Int) =
        (0 until n).map { i ->
            CandleData(t0.plus(hour.multipliedBy(i.toLong())), BigDecimal(100), BigDecimal(101), BigDecimal(99), BigDecimal(100), BigDecimal(10))
        }

    private fun at(h: Long) = t0.plus(hour.multipliedBy(h))

    private fun spec(
        type: IndicatorType,
        period: Int? = null,
    ) = IndicatorSpec("X", type, period, null, null, null, null, PriceField.CLOSE)

    @Test
    fun `values line up with the bar they describe and never come from a later interval`() {
        val b = bars(6)
        val data =
            DerivativesData(
                openInterest = listOf(DerivPoint(at(0), 100.0), DerivPoint(at(1), 110.0), DerivPoint(at(3), 130.0)),
                // Funding stamped at 02:00 is first used by the bar that opens at 02:00, never by the bar closing then.
                fundingRate = listOf(DerivPoint(at(2), 0.01)),
                // Quarter-hour delta intervals: bar 0 gets 1 + 2, bar 1 gets 3; nothing for later bars.
                delta = listOf(DerivPoint(at(0), 1.0), DerivPoint(at(0).plusSeconds(900), 2.0), DerivPoint(at(1), 3.0), DerivPoint(at(2).minusSeconds(1), 100.0)),
                source = "test",
            )
        val a = data.align(b, hour)
        assertThat(a.openInterest).containsExactly(100.0, 110.0, 110.0, 130.0, 130.0, 130.0)
        assertThat(a.fundingRate).containsExactly(null, null, 0.01, 0.01, 0.01, 0.01)
        assertThat(a.delta).containsExactly(3.0, 103.0, null, null, null, null)
    }

    @Test
    fun `open interest goes missing when the feed stops`() {
        val b = bars(8)
        val a = DerivativesData(listOf(DerivPoint(at(0), 100.0)), emptyList(), emptyList(), "test").align(b, hour)
        // Three bars (at least an hour) old is still usable; older is not.
        assertThat(a.openInterest.take(4)).containsOnly(100.0)
        assertThat(a.openInterest.drop(4)).containsOnlyNulls()
    }

    @Test
    fun `open interest change, annualised funding and cumulative delta are computed as described`() {
        val b = bars(5)
        val data =
            DerivativesData(
                (0 until 5).map { DerivPoint(at(it.toLong()), 100.0 + 10 * it) },
                (0 until 5).map { DerivPoint(at(it.toLong()), 0.002) },
                listOf(1.0, -4.0, 2.0, 5.0, -1.0).mapIndexed { i, v -> DerivPoint(at(i.toLong()), v) },
                "test",
            )
        val ctx = SeriesContext.of(Timeframe.H1, AssetClass.CRYPTO).copy(derivatives = data.align(b, hour))
        val v = Indicators.compute(listOf(spec(IndicatorType.OPEN_INTEREST, 2).copy(id = "OI"), spec(IndicatorType.FUNDING_RATE).copy(id = "F"), spec(IndicatorType.CVD, 3).copy(id = "C")), b, ctx)
        assertThat(v.getValue("OI.value")[4]!!.toDouble()).isEqualTo(140.0)
        assertThat(v.getValue("OI.change").take(2)).containsOnlyNulls()
        // (140 - 120) / 120
        assertThat(v.getValue("OI.change")[4]!!.toDouble()).isCloseTo(16.6666666667, within(1e-6))
        assertThat(v.getValue("F.annualized")[0]!!.toDouble()).isCloseTo(17.52, within(1e-9))
        assertThat(v.getValue("C.delta").map { it!!.toDouble() }).containsExactly(1.0, -4.0, 2.0, 5.0, -1.0)
        assertThat(v.getValue("C.value").take(2)).containsOnlyNulls()
        assertThat(v.getValue("C.value").drop(2).map { it!!.toDouble() }).containsExactly(-1.0, 3.0, 6.0)
    }

    @Test
    fun `without futures data every value is empty, so rules using them cannot trigger`() {
        val v = Indicators.compute(listOf(spec(IndicatorType.CVD, 2), spec(IndicatorType.OPEN_INTEREST, 2).copy(id = "Y")), bars(4), SeriesContext.of(Timeframe.H1, AssetClass.CRYPTO))
        v.values.forEach { assertThat(it).containsOnlyNulls() }
    }

    @Test
    fun `adding later futures data never changes earlier values`() {
        val b = bars(30)

        fun data(n: Int) =
            DerivativesData(
                (0 until n).map { DerivPoint(at(it.toLong()), 1000.0 + it * it) },
                (0 until n).map { DerivPoint(at(it.toLong()), 0.001 * (it % 5)) },
                (0 until n).map { DerivPoint(at(it.toLong()), (it % 7) - 3.0) },
                "test",
            )
        val specs = listOf(spec(IndicatorType.OPEN_INTEREST, 4).copy(id = "A"), spec(IndicatorType.FUNDING_RATE).copy(id = "B"), spec(IndicatorType.CVD, 5).copy(id = "C"))
        val base = SeriesContext.of(Timeframe.H1, AssetClass.CRYPTO)
        val early = Indicators.compute(specs, b.take(20), base.copy(derivatives = data(20).align(b.take(20), hour)))
        val late = Indicators.compute(specs, b, base.copy(derivatives = data(30).align(b, hour)))
        early.forEach { (k, series) -> assertThat(late.getValue(k).take(20)).`as`(k).isEqualTo(series) }
    }
}
