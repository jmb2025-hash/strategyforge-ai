# Chart Champions BTC playbook v3 (tuned)

Built from plan v2 (the owner's Chart Champions research) by a parameter sweep on real Bitstamp BTC/USD
30-minute data. Every choice was made on 2025 only; Q1, Q2 and Q3 2026 were then run unchanged.
Perpetual-futures costs (0.02% spread, 0.05% fee, 2 bps slippage), $100,000 start, 1% risk per trade.

| Period | Return | Max drawdown | Positions | Win rate (per position) | Profit factor |
|---|---|---|---|---|---|
| 2025 (tuning) | +10.9% | 6.5% | 73 | 56% | 1.41 |
| Q1 2026 (unseen) | +10.4% | 4.0% | 25 | 60% | 1.88 |
| Q2 2026 (unseen) | +3.6% | 5.7% | 24 | 54% | 1.36 |
| Q3 2026 (unseen) | +0.7% | 5.7% | 29 | 48% | 1.06 |

Research result, not trading advice.

```json
{
  "schemaVersion": "2.0",
  "metadata": {
    "name": "Chart Champions BTC playbook v3",
    "assetClass": "CRYPTO",
    "timeframe": "30m",
    "createdBy": "IMPORTED",
    "description": "Chart Champions style BTC plan on 30-minute bars, tuned on real 2025 BTC data and checked unchanged on 2026. Two setups: the CC Fibonacci 0.618-0.66 pullback with the 4-hour 26/55 EMA trend and at least two more confluences (stop 0.3% beyond the 0.786 level, half off at 1R, the rest at 3R, then breakeven), and the 4-hour 26/55 EMA swing on the side of the 200 EMA with a trailing stop. Both may hold a position at once; 1% risk each, 2.5% open risk at most. Left out after testing: swing failure patterns, failed auctions and CCV value rotation, which lost money in every tested period as mechanical rules."
  },
  "universe": {
    "symbols": [
      "BTC-USD"
    ]
  },
  "dataRequirements": {
    "minimumHistoryBars": 200,
    "maximumQuoteAgeSeconds": 120,
    "indicators": [
      {
        "id": "DAY",
        "type": "PERIOD_LEVELS",
        "anchor": "DAY"
      },
      {
        "id": "WEEK",
        "type": "PERIOD_LEVELS",
        "anchor": "WEEK"
      },
      {
        "id": "VA",
        "type": "VOLUME_PROFILE",
        "anchor": "DAY"
      },
      {
        "id": "NPOC",
        "type": "NAKED_POC",
        "anchor": "DAY",
        "period": 20
      },
      {
        "id": "RN",
        "type": "ROUND_NUMBER",
        "step": 1000
      },
      {
        "id": "RANGE_HIGH",
        "type": "HIGHEST",
        "period": 96
      },
      {
        "id": "RANGE_LOW",
        "type": "LOWEST",
        "period": 96
      },
      {
        "id": "EXC_HIGH",
        "type": "HIGHEST",
        "period": 8
      },
      {
        "id": "EXC_LOW",
        "type": "LOWEST",
        "period": 8
      },
      {
        "id": "FIB",
        "type": "FIBONACCI",
        "period": 96
      },
      {
        "id": "E26",
        "type": "EMA",
        "period": 26,
        "timeframe": "4h"
      },
      {
        "id": "E55",
        "type": "EMA",
        "period": 55,
        "timeframe": "4h"
      },
      {
        "id": "E100",
        "type": "EMA",
        "period": 100,
        "timeframe": "4h"
      },
      {
        "id": "E200",
        "type": "EMA",
        "period": 200,
        "timeframe": "4h"
      }
    ]
  },
  "planRules": {
    "conflictPolicy": "STACK",
    "capitalPolicy": "SHARED",
    "maximumOpenRiskPercent": 2.5
  },
  "setups": [
    {
      "id": "CC_FIB",
      "name": "CC Fibonacci zone with the 4-hour trend",
      "priority": 4,
      "direction": "BOTH",
      "description": "A pullback into the 0.618-0.66 zone of the latest move, with the 4-hour 26/55 EMA trend and at least one more confluence.",
      "entryRules": {
        "operator": "ALL",
        "conditions": [
          {
            "left": "E26",
            "comparison": "GT",
            "right": "E55"
          },
          {
            "left": "FIB.trend",
            "comparison": "EQ",
            "right": 1
          },
          {
            "left": "LOW",
            "comparison": "LTE",
            "right": "FIB.f618"
          },
          {
            "left": "CLOSE",
            "comparison": "GTE",
            "right": "FIB.f660"
          },
          {
            "operator": "AT_LEAST",
            "count": 2,
            "conditions": [
              {
                "left": "LOW",
                "comparison": "LTE",
                "right": "DAY.prevLow"
              },
              {
                "left": "LOW",
                "comparison": "LTE",
                "right": "VA.val"
              },
              {
                "left": "LOW",
                "comparison": "LTE",
                "right": "VA.poc"
              },
              {
                "left": "LOW",
                "comparison": "LTE",
                "right": "NPOC.below"
              },
              {
                "left": "LOW",
                "comparison": "LTE",
                "right": "RN.below"
              }
            ]
          }
        ]
      },
      "shortEntryRules": {
        "operator": "ALL",
        "conditions": [
          {
            "left": "E26",
            "comparison": "LT",
            "right": "E55"
          },
          {
            "left": "FIB.trend",
            "comparison": "EQ",
            "right": -1
          },
          {
            "left": "HIGH",
            "comparison": "GTE",
            "right": "FIB.f618"
          },
          {
            "left": "CLOSE",
            "comparison": "LTE",
            "right": "FIB.f660"
          },
          {
            "operator": "AT_LEAST",
            "count": 2,
            "conditions": [
              {
                "left": "HIGH",
                "comparison": "GTE",
                "right": "DAY.prevHigh"
              },
              {
                "left": "HIGH",
                "comparison": "GTE",
                "right": "VA.vah"
              },
              {
                "left": "HIGH",
                "comparison": "GTE",
                "right": "VA.poc"
              },
              {
                "left": "HIGH",
                "comparison": "GTE",
                "right": "NPOC.above"
              },
              {
                "left": "HIGH",
                "comparison": "GTE",
                "right": "RN.above"
              }
            ]
          }
        ]
      },
      "exitRules": {
        "stopLossPercent": 3,
        "takeProfitPercent": 10,
        "maximumHoldingBars": 192,
        "stop": {
          "at": "FIB.f786",
          "bufferPercent": 0.3
        },
        "targets": [
          {
            "rMultiple": 1.0,
            "closePercent": 50
          },
          {
            "rMultiple": 3,
            "closePercent": 50
          }
        ],
        "shortTargets": [
          {
            "rMultiple": 1.0,
            "closePercent": 50
          },
          {
            "rMultiple": 3,
            "closePercent": 50
          }
        ],
        "breakevenAfterTarget": 1,
        "minimumRewardRisk": 1
      },
      "positionSizing": {
        "method": "RISK_PERCENT",
        "value": 1
      }
    },
    {
      "id": "EMA_SWING",
      "name": "4-hour 26/55 EMA swing",
      "priority": 5,
      "direction": "BOTH",
      "decisionTimeframe": "4h",
      "description": "On a 4-hour close the 26 EMA crosses the 55 EMA on the side of the 200 EMA; trail the rest after the first target.",
      "entryRules": {
        "operator": "ALL",
        "conditions": [
          {
            "left": "E26",
            "comparison": "CROSSES_ABOVE",
            "right": "E55"
          },
          {
            "left": "CLOSE",
            "comparison": "GT",
            "right": "E200"
          }
        ]
      },
      "shortEntryRules": {
        "operator": "ALL",
        "conditions": [
          {
            "left": "E26",
            "comparison": "CROSSES_BELOW",
            "right": "E55"
          },
          {
            "left": "CLOSE",
            "comparison": "LT",
            "right": "E200"
          }
        ]
      },
      "exitRules": {
        "stopLossPercent": 8,
        "takeProfitPercent": 60,
        "maximumHoldingBars": 4320,
        "stop": {
          "at": "E100",
          "bufferPercent": 0.5
        },
        "targets": [
          {
            "rMultiple": 2,
            "closePercent": 50
          },
          {
            "rMultiple": 8,
            "closePercent": 50
          }
        ],
        "breakevenAfterTarget": 1,
        "trailing": {
          "swingPeriod": 24,
          "afterTarget": 1
        },
        "conditions": {
          "operator": "ANY",
          "conditions": [
            {
              "left": "E26",
              "comparison": "CROSSES_BELOW",
              "right": "E55"
            }
          ]
        },
        "shortConditions": {
          "operator": "ANY",
          "conditions": [
            {
              "left": "E26",
              "comparison": "CROSSES_ABOVE",
              "right": "E55"
            }
          ]
        }
      },
      "positionSizing": {
        "method": "RISK_PERCENT",
        "value": 1
      }
    }
  ],
  "orderInstructions": {
    "orderType": "MARKET",
    "timeInForce": "GTC"
  },
  "riskLimits": {
    "maximumOpenPositions": 2,
    "maximumDailyTrades": 6,
    "maximumDailyLossPercent": 3,
    "maximumDrawdownPercent": 20,
    "maximumPositionPercent": 100,
    "maximumDailyLosingTrades": 3,
    "allowShort": true
  },
  "inactivityConditions": [
    "STALE_MARKET_DATA",
    "MISSING_HISTORY",
    "PROVIDER_UNAVAILABLE"
  ]
}
```
