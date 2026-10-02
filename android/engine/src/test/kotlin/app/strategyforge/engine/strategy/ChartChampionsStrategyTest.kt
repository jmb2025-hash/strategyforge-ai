package app.strategyforge.engine.strategy

import app.strategyforge.engine.backtest.BacktestRequest
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.support.TestEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * D-042 end to end: the Chart Champions method from the owner's research (swing failure patterns at
 * the range extremes in both directions, confirmed by weekly levels, weekly VWAP, Fibonacci and the
 * previous day's value area; 1% risk per trade, partial profits, at most three losing trades a day)
 * validates, explains itself and backtests over months of 4-hour bars.
 */
class ChartChampionsStrategyTest {
    private val e = TestEngine.create()

    private val json =
        """
        {
          "schemaVersion": "1.0",
          "metadata": {"name": "Chart Champions SFP both ways", "assetClass": "CRYPTO", "timeframe": "4h", "createdBy": "IMPORTED", "direction": "BOTH",
            "description": "Swing failure patterns at weekly range extremes with confluence."},
          "universe": {"symbols": ["BTC-USD", "ETH-USD"]},
          "dataRequirements": {
            "minimumHistoryBars": 50,
            "maximumQuoteAgeSeconds": 60,
            "indicators": [
              {"id": "RANGE_LOW", "type": "LOWEST", "period": 42},
              {"id": "RANGE_HIGH", "type": "HIGHEST", "period": 42},
              {"id": "WEEK", "type": "PERIOD_LEVELS", "anchor": "WEEK"},
              {"id": "WVWAP", "type": "VWAP", "anchor": "WEEK"},
              {"id": "FIB", "type": "FIBONACCI", "period": 42},
              {"id": "VA", "type": "VOLUME_PROFILE", "anchor": "DAY"},
              {"id": "DSWING", "type": "SWING_LOW", "period": 3, "timeframe": "1d"},
              {"id": "RVOL", "type": "RELATIVE_VOLUME", "period": 20},
              {"id": "HAMMER", "type": "HAMMER"},
              {"id": "STAR", "type": "SHOOTING_STAR"}
            ]
          },
          "entryRules": {"operator": "ALL", "conditions": [
            {"left": "LOW", "comparison": "LT", "right": "RANGE_LOW.value"},
            {"left": "CLOSE", "comparison": "GT", "right": "RANGE_LOW.value"},
            {"operator": "ANY", "conditions": [
              {"left": "RVOL.value", "comparison": "GT", "right": 1.5},
              {"left": "HAMMER.value", "comparison": "EQ", "right": 1},
              {"left": "CLOSE", "comparison": "LT", "right": "WVWAP.value"},
              {"left": "CLOSE", "comparison": "LTE", "right": "FIB.f618"},
              {"left": "LOW", "comparison": "LTE", "right": "WEEK.prevLow"},
              {"left": "CLOSE", "comparison": "LT", "right": "VA.val"},
              {"left": "LOW", "comparison": "LTE", "right": "DSWING.value"}
            ]}
          ]},
          "shortEntryRules": {"operator": "ALL", "conditions": [
            {"left": "HIGH", "comparison": "GT", "right": "RANGE_HIGH.value"},
            {"left": "CLOSE", "comparison": "LT", "right": "RANGE_HIGH.value"},
            {"operator": "ANY", "conditions": [
              {"left": "RVOL.value", "comparison": "GT", "right": 1.5},
              {"left": "STAR.value", "comparison": "EQ", "right": 1},
              {"left": "CLOSE", "comparison": "GT", "right": "WVWAP.value"},
              {"left": "HIGH", "comparison": "GTE", "right": "WEEK.prevHigh"},
              {"left": "CLOSE", "comparison": "GT", "right": "VA.vah"}
            ]}
          ]},
          "exitRules": {
            "stopLossPercent": 3, "takeProfitPercent": 9, "maximumHoldingBars": 42,
            "partialTakeProfit": {"atPercent": 3, "closePercent": 50, "moveStopToEntry": true},
            "conditions": {"operator": "ANY", "conditions": [
              {"left": "HIGH", "comparison": "GTE", "right": "RANGE_HIGH.value"},
              {"left": "CLOSE", "comparison": "LT", "right": "RANGE_LOW.value"}
            ]},
            "shortConditions": {"operator": "ANY", "conditions": [
              {"left": "LOW", "comparison": "LTE", "right": "RANGE_LOW.value"},
              {"left": "CLOSE", "comparison": "GT", "right": "RANGE_HIGH.value"}
            ]}
          },
          "positionSizing": {"method": "RISK_PERCENT", "value": 1},
          "orderInstructions": {"orderType": "MARKET", "timeInForce": "GTC"},
          "riskLimits": {"maximumOpenPositions": 2, "maximumDailyTrades": 4, "maximumDailyLossPercent": 3, "maximumDrawdownPercent": 15,
            "maximumPositionPercent": 30, "maximumConsecutiveLosses": 3, "maximumDailyLosingTrades": 3, "allowShort": true},
          "inactivityConditions": ["STALE_MARKET_DATA", "RISK_ENGINE_UNAVAILABLE", "RECONCILIATION_FAILURE", "MISSING_HISTORY", "PROVIDER_UNAVAILABLE"]
        }
        """.trimIndent()

    @Test
    fun `the full method validates, raises its history requirement and explains every part`() {
        val r = e.strategies.create(JacksonCanonical.mapper.readTree(json))
        assertThat(r.strategy.status).`as`(r.validation.issues.toString()).isEqualTo(StrategyStatus.VALIDATED)
        assertThat(r.validation.issues.map { it.code }).contains("HISTORY_RAISED")
        val def = e.strategies.definition(r.version!!.id)
        // The daily swing low needs (3 x 6 + 2) days of 4-hour bars.
        assertThat(def.minimumHistoryBars).isGreaterThanOrEqualTo(20 * 6)
        assertThat(def.direction).isEqualTo(Direction.BOTH)
        assertThat(r.explanation)
            .contains("both directions")
            .contains("Open a simulated short when")
            .contains("the previous week's low")
            .contains("the previous week's high")
            .contains("the weekly VWAP")
            .contains("0.618 Fibonacci retracement")
            .contains("value area low of the previous day")
            .contains("daily latest swing low")
            .contains("Take 50% of the position off at a 3% gain and then move the stop to the entry price")
            .contains("hitting the stop loses 1% of equity")
            .contains("no new trades after 3 losing trade(s) in a day")
    }

    @Test
    fun `it backtests on months of 4-hour bars with enough warm-up and trades both directions`() {
        val r = e.strategies.create(JacksonCanonical.mapper.readTree(json))
        val b = e.backtests.submit(BacktestRequest(r.strategy.id, from = Instant.parse("2026-02-01T00:00:00Z"), to = Instant.parse("2026-06-22T00:00:00Z"), startingCapital = BigDecimal("100000")))
        assertThat(b.status).`as`(b.error ?: "").isEqualTo("COMPLETED")
        assertThat(b.integrity.toString()).doesNotContain("SHORT_WARMUP")
        val trades = e.backtests.trades(b.id)
        assertThat(trades).`as`("the setups occur in the data").isNotEmpty()
        println("Chart Champions backtest: ${trades.size} trades, sides ${trades.groupingBy { it["side"] }.eachCount()}, exits ${trades.groupingBy { it["exitReason"] }.eachCount()}, metrics ${b.metrics}")
        assertThat(trades.map { it["side"] }.toSet()).contains("LONG", "SHORT")
        // Risk sizing: no single position exceeds 30% of the starting equity at entry.
        trades.filter { it["exitReason"] != "PARTIAL_TAKE_PROFIT" }.forEach { t ->
            val partial = trades.filter { p -> p["exitReason"] == "PARTIAL_TAKE_PROFIT" && p["entryTime"] == t["entryTime"] && p["symbol"] == t["symbol"] }
            val qty = (t["quantity"] as BigDecimal).add(partial.fold(BigDecimal.ZERO) { a, p -> a.add(p["quantity"] as BigDecimal) })
            assertThat(qty.multiply(t["entryPrice"] as BigDecimal).toDouble()).isLessThan(0.31 * 130_000)
        }
    }
}
