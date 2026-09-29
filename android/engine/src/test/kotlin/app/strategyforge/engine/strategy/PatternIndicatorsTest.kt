package app.strategyforge.engine.strategy

import app.strategyforge.engine.backtest.BacktestRequest
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.TestEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/** D-036 chart structure, volume and candlestick patterns: correct shapes, and never using future bars. */
class PatternIndicatorsTest {
    private var t = Instant.parse("2026-06-22T00:00:00Z")

    private fun bar(
        o: String,
        h: String,
        l: String,
        c: String,
        v: String = "100",
    ): CandleData = CandleData(t, BigDecimal(o), BigDecimal(h), BigDecimal(l), BigDecimal(c), BigDecimal(v)).also { t = t.plusSeconds(60) }

    private fun flags(values: List<BigDecimal?>) = values.map { it?.toInt() }

    @Test
    fun `engulfing, hammer, shooting star and doji are recognised on the bar that completes them`() {
        val bars =
            listOf(
                bar("10", "10.2", "9.4", "9.5"), // falling
                bar("9.4", "10.6", "9.3", "10.5"), // rising body engulfs the previous body
                bar("10", "10.1", "8", "9.9"), // hammer: long lower shadow
                bar("10", "12", "9.95", "10.1"), // shooting star: long upper shadow
                bar("10", "10.5", "9.5", "10.02"), // doji
            )
        assertThat(flags(Indicators.candlestick(IndicatorType.BULLISH_ENGULFING, bars))).containsExactly(null, 1, 0, 0, 0)
        assertThat(flags(Indicators.candlestick(IndicatorType.BEARISH_ENGULFING, bars))).containsExactly(null, 0, 0, 0, 0)
        assertThat(flags(Indicators.candlestick(IndicatorType.HAMMER, bars))).containsExactly(0, 0, 1, 0, 0)
        assertThat(flags(Indicators.candlestick(IndicatorType.SHOOTING_STAR, bars))).containsExactly(0, 0, 0, 1, 0)
        assertThat(flags(Indicators.candlestick(IndicatorType.DOJI, bars))[4]).isEqualTo(1)
    }

    @Test
    fun `morning and evening stars need all three bars`() {
        val morning = listOf(bar("12", "12.1", "9.9", "10"), bar("9.8", "10", "9.6", "9.85"), bar("9.9", "11.6", "9.8", "11.5"))
        assertThat(flags(Indicators.candlestick(IndicatorType.MORNING_STAR, morning))).containsExactly(null, null, 1)
        val evening = listOf(bar("10", "12.1", "9.9", "12"), bar("12.1", "12.3", "12", "12.15"), bar("12.1", "12.2", "10.4", "10.5"))
        assertThat(flags(Indicators.candlestick(IndicatorType.EVENING_STAR, evening))).containsExactly(null, null, 1)
        assertThat(flags(Indicators.candlestick(IndicatorType.MORNING_STAR, evening))).containsExactly(null, null, 0)
    }

    @Test
    fun `breakout levels exclude the current bar and swing points are only known once confirmed`() {
        val highs = listOf("10", "11", "15", "12", "11", "13", "16")
        val bars = highs.map { h -> bar("9", h, "8", "9.5") }
        val hh = Indicators.highest(bars, 3)
        assertThat(hh.take(3)).containsOnlyNulls()
        assertThat(hh[3]).isEqualByComparingTo("15") // bars 0..2
        assertThat(hh[6]).isEqualByComparingTo("13") // bars 3..5: the 16 itself is excluded, so closing above 13 is a breakout

        val swing = Indicators.swingHigh(bars, 2)
        // Bar 2 (high 15) is a swing high, confirmed two bars later at bar 4.
        assertThat(swing.take(4)).containsOnlyNulls()
        assertThat(swing[4]).isEqualByComparingTo("15")
        assertThat(swing[6]).isEqualByComparingTo("15")

        val lows = listOf("8", "7", "5", "6", "7", "6.5", "8").map { l -> bar("9", "10", l, "9.5") }
        assertThat(Indicators.swingLow(lows, 2)[4]).isEqualByComparingTo("5")
    }

    @Test
    fun `relative volume compares a bar with the average of the bars before it`() {
        val bars = listOf("100", "100", "100", "300").map { v -> bar("10", "11", "9", "10", v) }
        val rv = Indicators.relativeVolume(bars, 3)
        assertThat(rv.take(3)).containsOnlyNulls()
        assertThat(rv[3]).isEqualByComparingTo("3")
    }

    @Test
    fun `values never change when later bars are added (no look-ahead)`() {
        val base = (0 until 40).map { i -> bar("${10 + i % 7}", "${12 + i % 5}", "${8 + i % 3}", "${11 + i % 4}", "${100 + i * 3}") }
        val more = base + (0 until 10).map { bar("30", "40", "20", "35", "999") }
        IndicatorType.entries.filter { it.pattern || it in setOf(IndicatorType.HIGHEST, IndicatorType.LOWEST, IndicatorType.SWING_HIGH, IndicatorType.SWING_LOW, IndicatorType.RELATIVE_VOLUME) }.forEach { type ->
            val spec = IndicatorSpec("X", type, if (type.pattern) null else 3, null, null, null, null, PriceField.CLOSE)
            val a = Indicators.compute(listOf(spec), base).getValue("X.value")
            val b = Indicators.compute(listOf(spec), more).getValue("X.value").take(base.size)
            assertThat(b).`as`(type.name).isEqualTo(a)
        }
    }

    @Test
    fun `a pattern and breakout strategy validates, explains itself and backtests`() {
        val e = TestEngine.create()
        val content =
            Strategies.alwaysLong("Engulfing breakout", "1m", symbol = "BTC-USD") +
                mapOf(
                    "dataRequirements" to
                        mapOf(
                            "minimumHistoryBars" to 40,
                            "maximumQuoteAgeSeconds" to 120,
                            "indicators" to
                                listOf(
                                    mapOf("id" to "ENGULF", "type" to "BULLISH_ENGULFING"),
                                    mapOf("id" to "HH20", "type" to "HIGHEST", "period" to 20),
                                    mapOf("id" to "SUPPORT", "type" to "SWING_LOW", "period" to 3),
                                    mapOf("id" to "RVOL", "type" to "RELATIVE_VOLUME", "period" to 20),
                                    mapOf("id" to "VOLAVG", "type" to "SMA", "period" to 10, "source" to "VOLUME"),
                                ),
                        ),
                    "entryRules" to
                        mapOf(
                            "operator" to "ANY",
                            "conditions" to
                                listOf(
                                    mapOf("left" to "ENGULF.value", "comparison" to "EQ", "right" to 1),
                                    mapOf(
                                        "operator" to "ALL",
                                        "conditions" to
                                            listOf(
                                                mapOf("left" to "CLOSE", "comparison" to "CROSSES_ABOVE", "right" to "HH20.value"),
                                                mapOf("left" to "RVOL.value", "comparison" to "GT", "right" to 1.2),
                                                mapOf("left" to "CLOSE", "comparison" to "GT", "right" to "SUPPORT.value"),
                                            ),
                                    ),
                                ),
                        ),
                )
        val r = e.strategies.create(JacksonCanonical.mapper.valueToTree(content))
        assertThat(r.strategy.status).`as`(r.validation.issues.toString()).isEqualTo(StrategyStatus.VALIDATED)
        assertThat(r.explanation).contains("bullish engulfing").contains("highest high of the previous 20 bars").contains("support")
        val b = e.backtests.submit(BacktestRequest(r.strategy.id, from = Instant.parse("2026-06-22T00:00:00Z"), to = Instant.parse("2026-06-22T13:00:00Z"), startingCapital = BigDecimal("100000")))
        assertThat(b.status).`as`(b.error ?: "").isEqualTo("COMPLETED")
    }

    @Test
    fun `patterns take no period and structure indicators require one`() {
        val e = TestEngine.create()
        val bad =
            Strategies.alwaysLong("Bad params", "1m") +
                mapOf(
                    "dataRequirements" to
                        mapOf(
                            "minimumHistoryBars" to 30,
                            "maximumQuoteAgeSeconds" to 120,
                            "indicators" to listOf(mapOf("id" to "HAMMER1", "type" to "HAMMER", "period" to 5), mapOf("id" to "LOW10", "type" to "LOWEST")),
                        ),
                )
        val r = e.strategies.create(JacksonCanonical.mapper.valueToTree(bad))
        assertThat(r.strategy.status).isEqualTo(StrategyStatus.VALIDATION_FAILED)
        assertThat(r.validation.issues.map { it.code }).contains("UNSUPPORTED_PARAMETER", "MISSING_PARAMETER")
    }
}
