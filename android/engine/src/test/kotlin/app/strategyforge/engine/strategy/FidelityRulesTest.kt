package app.strategyforge.engine.strategy

import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.market.Timeframe
import app.strategyforge.engine.support.TestEngine
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant

/**
 * D-047 rule language and levels for the owner's Chart Champions research: "at least N of",
 * "within the last N bars", 30-minute to 4-hour periods, the level calculator, round numbers and
 * untested POCs, all computed from closed bars only.
 */
class FidelityRulesTest {
    private val t0 = Instant.parse("2026-06-01T00:00:00Z")

    private fun bar(
        i: Int,
        o: Number,
        h: Number,
        l: Number,
        c: Number,
        v: Number = 10,
        minutes: Long = 60,
    ) = CandleData(t0.plus(Duration.ofMinutes(minutes * i)), BigDecimal(o.toString()), BigDecimal(h.toString()), BigDecimal(l.toString()), BigDecimal(c.toString()), BigDecimal(v.toString()))

    private fun cond(
        left: String,
        cmp: Comparison,
        right: Number,
        within: Int = 1,
        minimum: Int = 1,
    ) = Condition(Operand.Price(PriceField.valueOf(left)), cmp, Operand.Constant(BigDecimal(right.toString())), 0, within, minimum)

    @Test
    fun `at least N of a group, and conditions over a window of bars`() {
        val bars = listOf(bar(0, 10, 12, 9, 11), bar(1, 11, 13, 10, 12), bar(2, 12, 12, 8, 9), bar(3, 9, 10, 8, 9.5))
        val ev = RuleEvaluator(bars, emptyMap())
        val two = RuleGroup(GroupOperator.AT_LEAST, listOf(cond("CLOSE", Comparison.GT, 9), cond("HIGH", Comparison.GT, 11), cond("LOW", Comparison.LT, 8.5)), 2)
        // Bar 3: close 9.5 > 9 yes; high 10 > 11 no; low 8 < 8.5 yes.
        assertThat(ev.evaluate(two, 3)).isTrue()
        assertThat(ev.evaluate(two.copy(count = 3), 3)).isFalse()
        // "The low went below 8.5 within the last 2 bars" at bar 3: bars 2 and 3 both did.
        assertThat(ev.evaluate(cond("LOW", Comparison.LT, 8.5, within = 2), 3)).isTrue()
        // "Closed above 10 on at least 2 of the last 3 bars": bars 1..3 closed 12, 9, 9.5 -> only once.
        assertThat(ev.evaluate(cond("CLOSE", Comparison.GT, 10, within = 3, minimum = 2), 3)).isFalse()
        assertThat(ev.evaluate(cond("CLOSE", Comparison.GT, 10, within = 3, minimum = 1), 3)).isTrue()
        // A window never looks at later bars: at bar 0 only bar 0 exists.
        assertThat(ev.evaluate(cond("LOW", Comparison.LT, 8.5, within = 4), 0)).isFalse()
    }

    @Test
    fun `4-hour candles inside an hourly strategy and indicators on 4-hour bars`() {
        val bars = (0 until 12).map { i -> bar(i, 100 + i, 101 + i, 99 + i, 100.5 + i) }
        val ctx = SeriesContext.of(Timeframe.H1, AssetClass.CRYPTO)
        val h4 = Indicators.compute(listOf(IndicatorSpec("H4", IndicatorType.PERIOD_LEVELS, null, null, null, null, null, PriceField.CLOSE, anchor = Anchor.H4)), bars, ctx)
        // Hours 4..7 form the second 4-hour candle; at hour 4 the previous candle is hours 0..3.
        assertThat(h4.getValue("H4.prevHigh")[4]!!.toInt()).isEqualTo(104)
        assertThat(h4.getValue("H4.prevLow")[4]!!.toInt()).isEqualTo(99)
        assertThat(h4.getValue("H4.low")[7]!!.toInt()).isEqualTo(103)
        assertThat(h4.getValue("H4.prevHigh").take(4)).containsOnlyNulls()
        val sma = Indicators.compute(listOf(IndicatorSpec("S", IndicatorType.SMA, 2, null, null, null, null, PriceField.CLOSE, timeframe = Anchor.H4)), bars, ctx).getValue("S.value")
        // 4-hour closes: 103.5, 107.5, 111.5; the 2-period SMA exists once two 4-hour candles are complete (hour 7).
        assertThat(sma.take(7)).containsOnlyNulls()
        assertThat(sma[7]!!.toDouble()).isEqualTo(105.5)
    }

    @Test
    fun `the level calculator, round numbers and untested POCs`() {
        val bars = (0 until 6).map { i -> bar(i, 100, 110, 90, 105) }
        val ctx = SeriesContext.of(Timeframe.H1, AssetClass.CRYPTO)
        val lo = IndicatorSpec("LO", IndicatorType.LOWEST, 2, null, null, null, null, PriceField.CLOSE)
        val hi = IndicatorSpec("HI", IndicatorType.HIGHEST, 2, null, null, null, null, PriceField.CLOSE)
        val q = IndicatorSpec("Q", IndicatorType.LEVEL, null, null, null, null, null, PriceField.CLOSE, from = Operand.IndicatorRef("LO", "value"), to = Operand.IndicatorRef("HI", "value"), ratio = BigDecimal("0.25"))
        val ext = q.copy(id = "X", ratio = BigDecimal("-1.5"))
        val v = Indicators.compute(listOf(q, lo, hi, ext), bars, ctx)
        assertThat(v.getValue("Q.value")[3]!!.toDouble()).isEqualTo(95.0)
        assertThat(v.getValue("X.value")[3]!!.toDouble()).isEqualTo(60.0)
        val rn = Indicators.compute(listOf(IndicatorSpec("RN", IndicatorType.ROUND_NUMBER, null, null, null, null, null, PriceField.CLOSE, step = BigDecimal(1000))), listOf(bar(0, 80500, 81200, 79800, 80950)), ctx)
        assertThat(rn.getValue("RN.below")[0]!!.toInt()).isEqualTo(80000)
        assertThat(rn.getValue("RN.above")[0]!!.toInt()).isEqualTo(81000)

        // Day 1 trades heavily at 100, day 2 at 120; day 3 stays above 110 until it trades down through 100 at hour 6.
        val days =
            (0 until 24).map { h -> bar(h, 100, 100.5, 99.5, 100, 1000) } +
                (24 until 48).map { h -> bar(h, 120, 120.5, 119.5, 120, 1000) } +
                (48 until 60).map { h -> if (h < 54) bar(h, 115, 116, 114, 115, 10) else bar(h, 105, 106, 98, 99, 10) }
        val n = Indicators.compute(listOf(IndicatorSpec("N", IndicatorType.NAKED_POC, 5, null, null, null, null, PriceField.CLOSE, anchor = Anchor.DAY)), days, ctx)
        val below = n.getValue("N.below")
        val above = n.getValue("N.above")
        assertThat(below[50]!!.toDouble()).`as`("day 1 POC is untested below").isCloseTo(
            100.0,
            org.assertj.core.api.Assertions
                .within(0.5),
        )
        assertThat(above[50]!!.toDouble()).`as`("day 2 POC above").isCloseTo(
            120.0,
            org.assertj.core.api.Assertions
                .within(0.5),
        )
        // Hour 54 trades through 100 and closes at 99: 100 is still shown (above) on that bar,
        // and from hour 55 on it is no longer naked, leaving 120 as the nearest POC above.
        assertThat(above[54]!!.toDouble()).isCloseTo(
            100.0,
            org.assertj.core.api.Assertions
                .within(0.5),
        )
        assertThat(above[55]!!.toDouble()).isCloseTo(
            120.0,
            org.assertj.core.api.Assertions
                .within(0.5),
        )
        assertThat(below[55]).isNull()
    }

    @Test
    fun `the validator accepts the new rules and catches their mistakes`() {
        val e = TestEngine.create()

        fun doc(
            indicators: String,
            entry: String,
        ) = JacksonCanonical.mapper.readTree(
            """
            {"schemaVersion":"1.0","metadata":{"name":"x","assetClass":"CRYPTO","timeframe":"30m"},"universe":{"symbols":["BTC-USD"]},
             "dataRequirements":{"minimumHistoryBars":10,"maximumQuoteAgeSeconds":60,"indicators":[$indicators]},
             "entryRules":$entry,
             "exitRules":{"stopLossPercent":2,"takeProfitPercent":4,"maximumHoldingBars":10},"positionSizing":{"method":"RISK_PERCENT","value":1},
             "orderInstructions":{"orderType":"MARKET","timeInForce":"GTC"},"riskLimits":{"maximumOpenPositions":1,"maximumDailyTrades":5,"maximumDailyLossPercent":3,"allowShort":false},
             "inactivityConditions":[]}
            """.trimIndent(),
        ) as ObjectNode
        val good =
            e.validator.validateDocument(
                doc(
                    """{"id":"D","type":"PERIOD_LEVELS","anchor":"DAY"},{"id":"Q1","type":"LEVEL","from":"D.prevLow","to":"D.prevHigh","ratio":0.25},
                   {"id":"RN","type":"ROUND_NUMBER","step":1000},{"id":"NP","type":"NAKED_POC","anchor":"DAY","period":10},{"id":"H4","type":"PERIOD_LEVELS","anchor":"H4"},
                   {"id":"E","type":"EMA","period":26,"timeframe":"4h"}""",
                    """{"operator":"AT_LEAST","count":2,"conditions":[{"left":"LOW","comparison":"LT","right":"Q1","withinBars":4},{"left":"LOW","comparison":"LT","right":"RN.below"},
                    {"left":"H4.low","comparison":"LTE","right":"NP.below"},{"left":"CLOSE","comparison":"GT","right":"E","withinBars":3,"minimumBars":2}]}""",
                ),
            )
        assertThat(good.outcome).`as`(good.issues.toString()).isEqualTo(ValidationOutcome.VALIDATED)
        val bad =
            e.validator.validateDocument(
                doc(
                    """{"id":"Q1","type":"LEVEL","from":"NOPE","to":"HIGH","ratio":0.5},{"id":"H","type":"PERIOD_LEVELS","anchor":"M30"}""",
                    """{"operator":"AT_LEAST","count":3,"conditions":[{"left":"CLOSE","comparison":"GT","right":"Q1","withinBars":2,"minimumBars":3},{"left":"LOW","comparison":"LT","right":"H.low"}]}""",
                ),
            )
        assertThat(bad.errors.map { it.code }).contains("UNKNOWN_REFERENCE", "INVALID_COUNT", "CONTRADICTORY_PARAMETERS")
        assertThat(bad.errors.map { it.message }).anyMatch { it.contains("anchor 30m is not longer") }
        // Explanations read naturally.
        val ex = StrategyExplainer.explain(StrategyDefinition.from(good.document!!))
        assertThat(ex)
            .contains("at least 2 of:")
            .contains("within the last 4 bars")
            .contains("on at least 2 of the last 3 bars")
            .contains("the level 0.25 of the way from the previous day's low to the previous day's high")
            .contains("the round number (multiple of 1000) at or below the close")
            .contains("this 4-hour candle's low so far")
    }
}
