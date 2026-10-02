package app.strategyforge.engine.support

/** Trading plan documents for tests (D-045). */
object Plans {
    /**
     * A range-trading plan in the spirit of the owner's research: a long swing failure at the range
     * low, a short swing failure at the range high, and a breakout continuation, under a weekly-VWAP
     * context, with plan-wide risk.
     */
    fun rangePlan(
        timeframe: String = "4h",
        symbols: String = "\"BTC-USD\", \"ETH-USD\"",
        conflict: String = "ONE_PER_SYMBOL",
        capital: String = "SHARED",
        openRisk: Number? = 3,
        allocations: Boolean = false,
        context: Boolean = true,
    ) = """
        {
          "schemaVersion": "2.0",
          "metadata": {"name": "Range playbook", "assetClass": "CRYPTO", "timeframe": "$timeframe", "createdBy": "IMPORTED",
            "description": "Swing failures at the range extremes and breakout continuation."},
          "universe": {"symbols": [$symbols]},
          "dataRequirements": {
            "minimumHistoryBars": 50,
            "maximumQuoteAgeSeconds": 120,
            "indicators": [
              {"id": "RANGE_LOW", "type": "LOWEST", "period": 30},
              {"id": "RANGE_HIGH", "type": "HIGHEST", "period": 30},
              {"id": "WVWAP", "type": "VWAP", "anchor": "WEEK"},
              {"id": "RVOL", "type": "RELATIVE_VOLUME", "period": 20},
              {"id": "EMA_50", "type": "EMA", "period": 50}
            ]
          },
          ${if (context) "\"context\": {\"longWhen\": {\"operator\": \"ANY\", \"conditions\": [{\"left\": \"CLOSE\", \"comparison\": \"GT\", \"right\": \"EMA_50\"}, {\"left\": \"LOW\", \"comparison\": \"LT\", \"right\": \"RANGE_LOW.value\"}]}}," else ""}
          "planRules": {"conflictPolicy": "$conflict", "capitalPolicy": "$capital"${openRisk?.let { ", \"maximumOpenRiskPercent\": $it" } ?: ""}},
          "setups": [
            {"id": "SFP_LOW", "name": "Swing failure at range low", "priority": 1, "direction": "LONG_ONLY"${if (allocations) ", \"allocationPercent\": 40" else ""},
             "entryRules": {"operator": "ALL", "conditions": [
               {"left": "LOW", "comparison": "LT", "right": "RANGE_LOW.value"},
               {"left": "CLOSE", "comparison": "GT", "right": "RANGE_LOW.value"}]},
             "exitRules": {"stopLossPercent": 3, "takeProfitPercent": 8, "maximumHoldingBars": 30,
               "partialTakeProfit": {"atPercent": 3, "closePercent": 50, "moveStopToEntry": true}},
             "positionSizing": {"method": "RISK_PERCENT", "value": 1}},
            {"id": "SFP_HIGH", "name": "Swing failure at range high", "priority": 2, "direction": "SHORT_ONLY"${if (allocations) ", \"allocationPercent\": 30" else ""},
             "entryRules": {"operator": "ALL", "conditions": [
               {"left": "HIGH", "comparison": "GT", "right": "RANGE_HIGH.value"},
               {"left": "CLOSE", "comparison": "LT", "right": "RANGE_HIGH.value"}]},
             "exitRules": {"stopLossPercent": 3, "takeProfitPercent": 8, "maximumHoldingBars": 30},
             "positionSizing": {"method": "RISK_PERCENT", "value": 1}},
            {"id": "BREAKOUT", "name": "Breakout continuation", "priority": 3, "direction": "LONG_ONLY"${if (allocations) ", \"allocationPercent\": 30" else ""},
             "appliesWhen": {"operator": "ALL", "conditions": [{"left": "CLOSE", "comparison": "GT", "right": "WVWAP"}]},
             "entryRules": {"operator": "ALL", "conditions": [
               {"left": "CLOSE", "comparison": "CROSSES_ABOVE", "right": "RANGE_HIGH.value"},
               {"left": "RVOL.value", "comparison": "GT", "right": 1.2}]},
             "exitRules": {"stopLossPercent": 2, "takeProfitPercent": 6, "maximumHoldingBars": 20},
             "positionSizing": {"method": "RISK_PERCENT", "value": 0.5}}
          ],
          "orderInstructions": {"orderType": "MARKET", "timeInForce": "GTC"},
          "riskLimits": {"maximumOpenPositions": 3, "maximumDailyTrades": 10, "maximumDailyLossPercent": 4, "maximumDrawdownPercent": 20,
            "maximumPositionPercent": 30, "maximumDailyLosingTrades": 3, "allowShort": true},
          "inactivityConditions": ["STALE_MARKET_DATA", "MISSING_HISTORY", "PROVIDER_UNAVAILABLE"]
        }
        """.trimIndent()
}
