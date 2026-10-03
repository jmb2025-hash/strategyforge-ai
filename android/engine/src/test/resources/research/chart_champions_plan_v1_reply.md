I worked through the research document section by section and checked each rule against what the app's schema can express.

```json
{
  "schemaVersion": "2.0",
  "metadata": {
    "name": "Chart Champions BTC playbook v1",
    "assetClass": "CRYPTO",
    "timeframe": "1h",
    "createdBy": "IMPORTED",
    "description": "Chart Champions style BTC plan: level first, then reaction. Swing failure patterns at confluent prior-day, prior-week, value-area and range levels; CCV rotation through the prior day's value area; the CC Fibonacci 0.618-0.66 zone with the daily 26/55 EMA trend; and the 26/55 EMA swing. Approximations: chart-level stops and targets are written as percentages or as exits when the target level is reached; 30-minute confirmations are 1-hour closes. Left out: CCW ladder, Daily Open quartiles, nPOCs, round numbers, opening range breakout, liquidation scalps, chart patterns, altcoin versus BTC, CCV 2.0, SFP 2.0 and other unpublished playbooks."
  },
  "universe": {"symbols": ["BTC-USD"]},
  "dataRequirements": {
    "minimumHistoryBars": 200,
    "maximumQuoteAgeSeconds": 120,
    "indicators": [
      {"id": "DAY", "type": "PERIOD_LEVELS", "anchor": "DAY"},
      {"id": "WEEK", "type": "PERIOD_LEVELS", "anchor": "WEEK"},
      {"id": "VA", "type": "VOLUME_PROFILE", "anchor": "DAY"},
      {"id": "RANGE_HIGH", "type": "HIGHEST", "period": 48},
      {"id": "RANGE_LOW", "type": "LOWEST", "period": 48},
      {"id": "FIB", "type": "FIBONACCI", "period": 72},
      {"id": "E26", "type": "EMA", "period": 26, "timeframe": "1d"},
      {"id": "E55", "type": "EMA", "period": 55, "timeframe": "1d"}
    ]
  },
  "planRules": {"conflictPolicy": "ONE_PER_SYMBOL", "capitalPolicy": "SHARED", "maximumOpenRiskPercent": 2},
  "setups": [
    {
      "id": "SFP",
      "name": "Swing failure at a confluent level",
      "description": "A wick takes a significant low (high) and the candle closes back inside, at a level with at least two confluences.",
      "priority": 1,
      "direction": "BOTH",
      "entryRules": {"operator": "ALL", "conditions": [
        {"operator": "ANY", "conditions": [
          {"operator": "ALL", "conditions": [{"left": "LOW", "comparison": "LT", "right": "DAY.prevLow"}, {"left": "CLOSE", "comparison": "GT", "right": "DAY.prevLow"}]},
          {"operator": "ALL", "conditions": [{"left": "LOW", "comparison": "LT", "right": "WEEK.prevLow"}, {"left": "CLOSE", "comparison": "GT", "right": "WEEK.prevLow"}]},
          {"operator": "ALL", "conditions": [{"left": "LOW", "comparison": "LT", "right": "VA.val"}, {"left": "CLOSE", "comparison": "GT", "right": "VA.val"}]},
          {"operator": "ALL", "conditions": [{"left": "LOW", "comparison": "LT", "right": "RANGE_LOW"}, {"left": "CLOSE", "comparison": "GT", "right": "RANGE_LOW"}]}
        ]},
        {"operator": "ANY", "conditions": [
          {"operator": "ALL", "conditions": [{"left": "LOW", "comparison": "LTE", "right": "DAY.prevLow"}, {"left": "LOW", "comparison": "LTE", "right": "VA.val"}]},
          {"operator": "ALL", "conditions": [{"left": "LOW", "comparison": "LTE", "right": "DAY.prevLow"}, {"left": "LOW", "comparison": "LTE", "right": "RANGE_LOW"}]},
          {"operator": "ALL", "conditions": [{"left": "LOW", "comparison": "LTE", "right": "DAY.prevLow"}, {"left": "LOW", "comparison": "LTE", "right": "WEEK.prevLow"}]},
          {"operator": "ALL", "conditions": [{"left": "LOW", "comparison": "LTE", "right": "VA.val"}, {"left": "LOW", "comparison": "LTE", "right": "RANGE_LOW"}]},
          {"operator": "ALL", "conditions": [{"left": "LOW", "comparison": "LTE", "right": "VA.val"}, {"left": "LOW", "comparison": "LTE", "right": "WEEK.prevLow"}]},
          {"operator": "ALL", "conditions": [{"left": "LOW", "comparison": "LTE", "right": "RANGE_LOW"}, {"left": "LOW", "comparison": "LTE", "right": "WEEK.prevLow"}]}
        ]}
      ]},
      "shortEntryRules": {"operator": "ALL", "conditions": [
        {"operator": "ANY", "conditions": [
          {"operator": "ALL", "conditions": [{"left": "HIGH", "comparison": "GT", "right": "DAY.prevHigh"}, {"left": "CLOSE", "comparison": "LT", "right": "DAY.prevHigh"}]},
          {"operator": "ALL", "conditions": [{"left": "HIGH", "comparison": "GT", "right": "WEEK.prevHigh"}, {"left": "CLOSE", "comparison": "LT", "right": "WEEK.prevHigh"}]},
          {"operator": "ALL", "conditions": [{"left": "HIGH", "comparison": "GT", "right": "VA.vah"}, {"left": "CLOSE", "comparison": "LT", "right": "VA.vah"}]},
          {"operator": "ALL", "conditions": [{"left": "HIGH", "comparison": "GT", "right": "RANGE_HIGH"}, {"left": "CLOSE", "comparison": "LT", "right": "RANGE_HIGH"}]}
        ]},
        {"operator": "ANY", "conditions": [
          {"operator": "ALL", "conditions": [{"left": "HIGH", "comparison": "GTE", "right": "DAY.prevHigh"}, {"left": "HIGH", "comparison": "GTE", "right": "VA.vah"}]},
          {"operator": "ALL", "conditions": [{"left": "HIGH", "comparison": "GTE", "right": "DAY.prevHigh"}, {"left": "HIGH", "comparison": "GTE", "right": "RANGE_HIGH"}]},
          {"operator": "ALL", "conditions": [{"left": "HIGH", "comparison": "GTE", "right": "DAY.prevHigh"}, {"left": "HIGH", "comparison": "GTE", "right": "WEEK.prevHigh"}]},
          {"operator": "ALL", "conditions": [{"left": "HIGH", "comparison": "GTE", "right": "VA.vah"}, {"left": "HIGH", "comparison": "GTE", "right": "RANGE_HIGH"}]},
          {"operator": "ALL", "conditions": [{"left": "HIGH", "comparison": "GTE", "right": "VA.vah"}, {"left": "HIGH", "comparison": "GTE", "right": "WEEK.prevHigh"}]},
          {"operator": "ALL", "conditions": [{"left": "HIGH", "comparison": "GTE", "right": "RANGE_HIGH"}, {"left": "HIGH", "comparison": "GTE", "right": "WEEK.prevHigh"}]}
        ]}
      ]},
      "exitRules": {
        "stopLossPercent": 1.2, "takeProfitPercent": 6, "maximumHoldingBars": 48,
        "partialTakeProfit": {"atPercent": 1.5, "closePercent": 50, "moveStopToEntry": true},
        "conditions": {"operator": "ANY", "conditions": [{"left": "HIGH", "comparison": "GTE", "right": "RANGE_HIGH"}]},
        "shortConditions": {"operator": "ANY", "conditions": [{"left": "LOW", "comparison": "LTE", "right": "RANGE_LOW"}]}
      },
      "positionSizing": {"method": "RISK_PERCENT", "value": 1}
    },
    {
      "id": "CCV",
      "name": "CCV value-area rotation",
      "description": "The day opens outside the prior day's value area, two closes are accepted back inside, and price rotates to the opposite side of value.",
      "priority": 2,
      "direction": "BOTH",
      "entryRules": {"operator": "ALL", "conditions": [
        {"left": "DAY.open", "comparison": "LT", "right": "VA.val"},
        {"left": "CLOSE", "comparison": "GT", "right": "VA.val"},
        {"left": "CLOSE", "comparison": "GT", "right": "VA.val", "offsetBars": 1},
        {"left": "CLOSE", "comparison": "LTE", "right": "VA.val", "offsetBars": 2}
      ]},
      "shortEntryRules": {"operator": "ALL", "conditions": [
        {"left": "DAY.open", "comparison": "GT", "right": "VA.vah"},
        {"left": "CLOSE", "comparison": "LT", "right": "VA.vah"},
        {"left": "CLOSE", "comparison": "LT", "right": "VA.vah", "offsetBars": 1},
        {"left": "CLOSE", "comparison": "GTE", "right": "VA.vah", "offsetBars": 2}
      ]},
      "exitRules": {
        "stopLossPercent": 1.5, "takeProfitPercent": 6, "maximumHoldingBars": 24,
        "conditions": {"operator": "ANY", "conditions": [{"left": "HIGH", "comparison": "GTE", "right": "VA.vah"}]},
        "shortConditions": {"operator": "ANY", "conditions": [{"left": "LOW", "comparison": "LTE", "right": "VA.val"}]}
      },
      "positionSizing": {"method": "RISK_PERCENT", "value": 1}
    },
    {
      "id": "CC_FIB",
      "name": "CC Fibonacci zone with the daily trend",
      "description": "A pullback into the 0.618-0.66 zone of the latest move, in the direction of the daily 26/55 EMA trend, with a second confluence.",
      "priority": 3,
      "direction": "BOTH",
      "entryRules": {"operator": "ALL", "conditions": [
        {"left": "E26", "comparison": "GT", "right": "E55"},
        {"left": "FIB.trend", "comparison": "EQ", "right": 1},
        {"left": "LOW", "comparison": "LTE", "right": "FIB.f618"},
        {"left": "CLOSE", "comparison": "GTE", "right": "FIB.f660"},
        {"operator": "ANY", "conditions": [
          {"left": "LOW", "comparison": "LTE", "right": "DAY.prevLow"},
          {"left": "LOW", "comparison": "LTE", "right": "VA.val"},
          {"left": "LOW", "comparison": "LTE", "right": "VA.poc"}
        ]}
      ]},
      "shortEntryRules": {"operator": "ALL", "conditions": [
        {"left": "E26", "comparison": "LT", "right": "E55"},
        {"left": "FIB.trend", "comparison": "EQ", "right": -1},
        {"left": "HIGH", "comparison": "GTE", "right": "FIB.f618"},
        {"left": "CLOSE", "comparison": "LTE", "right": "FIB.f660"},
        {"operator": "ANY", "conditions": [
          {"left": "HIGH", "comparison": "GTE", "right": "DAY.prevHigh"},
          {"left": "HIGH", "comparison": "GTE", "right": "VA.vah"},
          {"left": "HIGH", "comparison": "GTE", "right": "VA.poc"}
        ]}
      ]},
      "exitRules": {
        "stopLossPercent": 2, "takeProfitPercent": 6, "maximumHoldingBars": 72,
        "conditions": {"operator": "ANY", "conditions": [{"left": "CLOSE", "comparison": "LT", "right": "FIB.f786"}, {"left": "HIGH", "comparison": "GTE", "right": "FIB.high"}]},
        "shortConditions": {"operator": "ANY", "conditions": [{"left": "CLOSE", "comparison": "GT", "right": "FIB.f786"}, {"left": "LOW", "comparison": "LTE", "right": "FIB.low"}]}
      },
      "positionSizing": {"method": "RISK_PERCENT", "value": 1}
    },
    {
      "id": "EMA_SWING",
      "name": "26/55 EMA swing",
      "description": "Daily 26 EMA crosses the 55 EMA; exit on the opposite cross.",
      "priority": 4,
      "direction": "BOTH",
      "entryRules": {"operator": "ALL", "conditions": [{"left": "E26", "comparison": "CROSSES_ABOVE", "right": "E55"}]},
      "shortEntryRules": {"operator": "ALL", "conditions": [{"left": "E26", "comparison": "CROSSES_BELOW", "right": "E55"}]},
      "exitRules": {
        "stopLossPercent": 8, "takeProfitPercent": 40, "maximumHoldingBars": 2160,
        "conditions": {"operator": "ANY", "conditions": [{"left": "E26", "comparison": "CROSSES_BELOW", "right": "E55"}]},
        "shortConditions": {"operator": "ANY", "conditions": [{"left": "E26", "comparison": "CROSSES_ABOVE", "right": "E55"}]}
      },
      "positionSizing": {"method": "RISK_PERCENT", "value": 1}
    }
  ],
  "orderInstructions": {"orderType": "MARKET", "timeInForce": "GTC"},
  "riskLimits": {"maximumOpenPositions": 1, "maximumDailyTrades": 6, "maximumDailyLossPercent": 3, "maximumDrawdownPercent": 15,
    "maximumPositionPercent": 100, "maximumDailyLosingTrades": 3, "allowShort": true},
  "inactivityConditions": ["STALE_MARKET_DATA", "MISSING_HISTORY", "PROVIDER_UNAVAILABLE"]
}
```

RULE READBACK
- [Observed] Plan on BTC-USD 1-hour bars; levels from the prior day, prior week, prior day's value area and the last 48 bars.
- [Published] SFP long: a candle's low goes below the prior day's low, prior week's low, prior day's value area low or the 48-bar low and the candle closes back above that level.
- [Published] SFP confluence: at least two of those levels are taken by the same candle.
- [Published] SFP short: the mirror at the prior day's high, prior week's high, value area high or 48-bar high.
- [Approximation] SFP stop 1.2% from entry, replacing "beyond the new wick" (the observed zones were about 0.8% wide).
- [Approximation] SFP target: exit when price reaches the opposite end of the 48-bar range (filled at the next bar's open), replacing "opposite side of the range".
- [Proposed] SFP partial: 50% off at +1.5% and the stop moved to entry; at most 48 bars held.
- [Legacy] CCV long: the day opens below the prior day's value area low, then two consecutive closes back above it after a close below.
- [Approximation] CCV confirmation uses 1-hour closes, replacing two 30-minute closes.
- [Legacy] CCV target: exit when price reaches the prior day's value area high; short is the mirror.
- [Proposed] CCV stop 1.5%, at most 24 bars held.
- [Published] CC Fibonacci long: daily 26 EMA above the 55 EMA, the latest 72-bar move is up, the low reaches the 0.618 level and the close holds above 0.66, plus one more confluence (prior day low, value area low or POC).
- [Proposed] CC Fibonacci anchors: the high and low of the last 72 bars.
- [Proposed] CC Fibonacci exits: close beyond 0.786, or the swing high (low) is retested; stop 2%, at most 72 bars.
- [Published] EMA swing: daily 26 EMA crosses above (below) the 55 EMA.
- [Approximation] The 100 and 200 EMA context and the "fan out" are left out: daily 100/200 EMAs need more history than the app can load on 1-hour bars.
- [Proposed] EMA swing exits on the opposite cross; stop 8%, target 40%, at most 90 days.
- [Published] Each trade risks 1% of equity, sized from the stop distance.
- [Proposed] One position at a time; at most 2% of equity at risk; 6 trades and 3 losing trades per day; stop for the day after a 3% loss; suspend after a 15% drawdown.

FURTHER RESEARCH
- CC Fibonacci zone: the research gave 0.618-0.66 and two or more confluences -> confirmed in the Fibonacci guide (source: Chart Champions Fibonacci retracement guide, S1).
- Risk per trade: the research gave no number -> the journal uses 1% as an example (source: Chart Champions journal updates, S9).
- SFP execution timeframe: the research named 5-minute and 30-minute examples -> no single timeframe is published; 1-hour chosen to reach weekly levels (source: S7, S10).

STILL MISSING
- SFP stop buffer beyond the wick: not published - approximated as a 1.2% stop.
- Failed auction: the dwell time beyond the level is not published - setup left out.
- CCW ladder: no first-party source re-verified, and the app cannot ladder entries - left out.
- Daily Open quartiles: directional branches not published, and quartile levels cannot be expressed - left out.
- nPOCs and psychological round numbers: the app has no such levels - left out.
- Opening range breakout: a stock-market session setup - not applicable to this crypto plan.
- Liquidation scalp, flags, wedges, triangles, three-candle (Severin) method, CCV 2.0, SFP 2.0, CCTR, Frontrun, 3rd Touch, Order Blocks: rules not published - left out.
- Altcoin versus BTC context: the app cannot read another symbol - plan limited to BTC-USD.
- Statistics (CCV 80%, Daily Open 90%): historical claims, not used as rules.
