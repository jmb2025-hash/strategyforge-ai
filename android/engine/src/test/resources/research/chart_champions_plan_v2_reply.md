I re-checked each rule against the research document and the app's new chart-level features.

```json
{
  "schemaVersion": "2.0",
  "metadata": {
    "name": "Chart Champions BTC playbook v2",
    "assetClass": "CRYPTO",
    "timeframe": "30m",
    "createdBy": "IMPORTED",
    "description": "Chart Champions style BTC plan on 30-minute bars: level first, then reaction, then trigger. Swing failure patterns and failed auctions at confluent prior-day, prior-week, value-area, naked-POC, round-number and range levels with stops beyond the wick or excursion and targets at the POC and the opposite side; CCV value-area rotation on two 30-minute closes; the CC Fibonacci 0.618-0.66 zone with the 4-hour 26/55 EMA trend; and the 4-hour 26/55 EMA swing with trailing. Left out: CCW ladder, Daily Open quartile branches, opening range breakout (stock session), liquidation scalps, chart patterns, altcoin versus BTC, CCV 2.0, SFP 2.0 and other unpublished playbooks."
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
    "conflictPolicy": "ONE_PER_SYMBOL",
    "capitalPolicy": "SHARED",
    "maximumOpenRiskPercent": 2
  },
  "setups": [
    {
      "id": "SFP",
      "name": "Swing failure at a confluent level",
      "priority": 1,
      "direction": "BOTH",
      "description": "A wick takes a significant low (high) and the 30-minute candle closes back inside, at a level swept together with at least one other.",
      "entryRules": {
        "operator": "ALL",
        "conditions": [
          {
            "operator": "ANY",
            "conditions": [
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "LOW",
                    "comparison": "LT",
                    "right": "DAY.prevLow"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "GT",
                    "right": "DAY.prevLow"
                  }
                ]
              },
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "LOW",
                    "comparison": "LT",
                    "right": "WEEK.prevLow"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "GT",
                    "right": "WEEK.prevLow"
                  }
                ]
              },
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "LOW",
                    "comparison": "LT",
                    "right": "VA.val"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "GT",
                    "right": "VA.val"
                  }
                ]
              },
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "LOW",
                    "comparison": "LT",
                    "right": "NPOC.below"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "GT",
                    "right": "NPOC.below"
                  }
                ]
              },
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "LOW",
                    "comparison": "LT",
                    "right": "RN.below"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "GT",
                    "right": "RN.below"
                  }
                ]
              },
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "LOW",
                    "comparison": "LT",
                    "right": "RANGE_LOW"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "GT",
                    "right": "RANGE_LOW"
                  }
                ]
              }
            ]
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
                "right": "WEEK.prevLow"
              },
              {
                "left": "LOW",
                "comparison": "LTE",
                "right": "VA.val"
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
              },
              {
                "left": "LOW",
                "comparison": "LTE",
                "right": "RANGE_LOW"
              }
            ]
          }
        ]
      },
      "shortEntryRules": {
        "operator": "ALL",
        "conditions": [
          {
            "operator": "ANY",
            "conditions": [
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "HIGH",
                    "comparison": "GT",
                    "right": "DAY.prevHigh"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "LT",
                    "right": "DAY.prevHigh"
                  }
                ]
              },
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "HIGH",
                    "comparison": "GT",
                    "right": "WEEK.prevHigh"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "LT",
                    "right": "WEEK.prevHigh"
                  }
                ]
              },
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "HIGH",
                    "comparison": "GT",
                    "right": "VA.vah"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "LT",
                    "right": "VA.vah"
                  }
                ]
              },
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "HIGH",
                    "comparison": "GT",
                    "right": "NPOC.above"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "LT",
                    "right": "NPOC.above"
                  }
                ]
              },
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "HIGH",
                    "comparison": "GT",
                    "right": "RN.above"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "LT",
                    "right": "RN.above"
                  }
                ]
              },
              {
                "operator": "ALL",
                "conditions": [
                  {
                    "left": "HIGH",
                    "comparison": "GT",
                    "right": "RANGE_HIGH"
                  },
                  {
                    "left": "CLOSE",
                    "comparison": "LT",
                    "right": "RANGE_HIGH"
                  }
                ]
              }
            ]
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
                "right": "WEEK.prevHigh"
              },
              {
                "left": "HIGH",
                "comparison": "GTE",
                "right": "VA.vah"
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
              },
              {
                "left": "HIGH",
                "comparison": "GTE",
                "right": "RANGE_HIGH"
              }
            ]
          }
        ]
      },
      "exitRules": {
        "stopLossPercent": 2.5,
        "takeProfitPercent": 8,
        "maximumHoldingBars": 96,
        "stop": {
          "at": "SIGNAL_WICK",
          "bufferPercent": 0.1
        },
        "targets": [
          {
            "at": "VA.poc",
            "closePercent": 50
          },
          {
            "at": "RANGE_HIGH",
            "closePercent": 50
          }
        ],
        "shortTargets": [
          {
            "at": "VA.poc",
            "closePercent": 50
          },
          {
            "at": "RANGE_LOW",
            "closePercent": 50
          }
        ],
        "breakevenAfterTarget": 1
      },
      "positionSizing": {
        "method": "RISK_PERCENT",
        "value": 1
      }
    },
    {
      "id": "FAILED_AUCTION",
      "name": "Failed auction back into value",
      "priority": 2,
      "direction": "BOTH",
      "description": "Price is accepted beyond a level (closes there), fails to hold it and closes back inside.",
      "entryRules": {
        "operator": "ANY",
        "conditions": [
          {
            "operator": "ALL",
            "conditions": [
              {
                "left": "CLOSE",
                "comparison": "LT",
                "right": "DAY.prevLow",
                "offsetBars": 1,
                "withinBars": 6,
                "minimumBars": 2
              },
              {
                "left": "CLOSE",
                "comparison": "CROSSES_ABOVE",
                "right": "DAY.prevLow"
              }
            ]
          },
          {
            "operator": "ALL",
            "conditions": [
              {
                "left": "CLOSE",
                "comparison": "LT",
                "right": "VA.val",
                "offsetBars": 1,
                "withinBars": 6,
                "minimumBars": 2
              },
              {
                "left": "CLOSE",
                "comparison": "CROSSES_ABOVE",
                "right": "VA.val"
              }
            ]
          },
          {
            "operator": "ALL",
            "conditions": [
              {
                "left": "CLOSE",
                "comparison": "LT",
                "right": "WEEK.prevLow",
                "offsetBars": 1,
                "withinBars": 6,
                "minimumBars": 2
              },
              {
                "left": "CLOSE",
                "comparison": "CROSSES_ABOVE",
                "right": "WEEK.prevLow"
              }
            ]
          }
        ]
      },
      "shortEntryRules": {
        "operator": "ANY",
        "conditions": [
          {
            "operator": "ALL",
            "conditions": [
              {
                "left": "CLOSE",
                "comparison": "GT",
                "right": "DAY.prevHigh",
                "offsetBars": 1,
                "withinBars": 6,
                "minimumBars": 2
              },
              {
                "left": "CLOSE",
                "comparison": "CROSSES_BELOW",
                "right": "DAY.prevHigh"
              }
            ]
          },
          {
            "operator": "ALL",
            "conditions": [
              {
                "left": "CLOSE",
                "comparison": "GT",
                "right": "VA.vah",
                "offsetBars": 1,
                "withinBars": 6,
                "minimumBars": 2
              },
              {
                "left": "CLOSE",
                "comparison": "CROSSES_BELOW",
                "right": "VA.vah"
              }
            ]
          },
          {
            "operator": "ALL",
            "conditions": [
              {
                "left": "CLOSE",
                "comparison": "GT",
                "right": "WEEK.prevHigh",
                "offsetBars": 1,
                "withinBars": 6,
                "minimumBars": 2
              },
              {
                "left": "CLOSE",
                "comparison": "CROSSES_BELOW",
                "right": "WEEK.prevHigh"
              }
            ]
          }
        ]
      },
      "exitRules": {
        "stopLossPercent": 3,
        "takeProfitPercent": 8,
        "maximumHoldingBars": 96,
        "stop": {
          "at": "EXC_LOW",
          "bufferPercent": 0.1
        },
        "shortStop": {
          "at": "EXC_HIGH",
          "bufferPercent": 0.1
        },
        "targets": [
          {
            "at": "VA.poc",
            "closePercent": 50
          },
          {
            "at": "VA.vah",
            "closePercent": 50
          }
        ],
        "shortTargets": [
          {
            "at": "VA.poc",
            "closePercent": 50
          },
          {
            "at": "VA.val",
            "closePercent": 50
          }
        ],
        "breakevenAfterTarget": 1
      },
      "positionSizing": {
        "method": "RISK_PERCENT",
        "value": 1
      }
    },
    {
      "id": "CCV",
      "name": "CCV value-area rotation",
      "priority": 3,
      "direction": "BOTH",
      "description": "The day opens outside the prior day's value area; two consecutive 30-minute closes back inside; rotate to the other side of value.",
      "entryRules": {
        "operator": "ALL",
        "conditions": [
          {
            "left": "DAY.open",
            "comparison": "LT",
            "right": "VA.val"
          },
          {
            "left": "CLOSE",
            "comparison": "GT",
            "right": "VA.val"
          },
          {
            "left": "CLOSE",
            "comparison": "GT",
            "right": "VA.val",
            "offsetBars": 1
          },
          {
            "left": "CLOSE",
            "comparison": "LTE",
            "right": "VA.val",
            "offsetBars": 2
          }
        ]
      },
      "shortEntryRules": {
        "operator": "ALL",
        "conditions": [
          {
            "left": "DAY.open",
            "comparison": "GT",
            "right": "VA.vah"
          },
          {
            "left": "CLOSE",
            "comparison": "LT",
            "right": "VA.vah"
          },
          {
            "left": "CLOSE",
            "comparison": "LT",
            "right": "VA.vah",
            "offsetBars": 1
          },
          {
            "left": "CLOSE",
            "comparison": "GTE",
            "right": "VA.vah",
            "offsetBars": 2
          }
        ]
      },
      "exitRules": {
        "stopLossPercent": 3,
        "takeProfitPercent": 6,
        "maximumHoldingBars": 48,
        "stop": {
          "at": "DAY.low",
          "bufferPercent": 0.1
        },
        "shortStop": {
          "at": "DAY.high",
          "bufferPercent": 0.1
        },
        "targets": [
          {
            "at": "VA.vah",
            "closePercent": 100
          }
        ],
        "shortTargets": [
          {
            "at": "VA.val",
            "closePercent": 100
          }
        ]
      },
      "positionSizing": {
        "method": "RISK_PERCENT",
        "value": 1
      }
    },
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
            "count": 1,
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
            "count": 1,
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
          "bufferPercent": 0.1
        },
        "targets": [
          {
            "rMultiple": 1,
            "closePercent": 50
          },
          {
            "at": "FIB.high",
            "closePercent": 50
          }
        ],
        "shortTargets": [
          {
            "rMultiple": 1,
            "closePercent": 50
          },
          {
            "at": "FIB.low",
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
    "maximumOpenPositions": 1,
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

RULE READBACK
- [Observed] Plan on BTC-USD 30-minute bars with prior-day, prior-week, prior-day value area, untested daily POC, $1,000 round-number and 2-day range levels.
- [Published] SFP long: a 30-minute candle's low takes one of those levels and the candle closes back above it.
- [Published] SFP confluence: the same candle takes at least two of the levels.
- [Published] SFP short: the mirror at the highs.
- [Published] SFP stop: beyond the new wick.
- [Proposed] SFP stop buffer 0.1%; no trade if the stop would be more than 2.5% away.
- [Published] SFP targets: half at the prior day's POC, the rest at the opposite end of the 2-day range; stop to entry after the first.
- [Proposed] SFP outer cap 8%, at most 96 bars (2 days).
- [Published] Failed auction short: after at least two 30-minute closes above the prior day's high, value area high or weekly high within the last 6 bars, a close back below it.
- [Proposed] Failed auction dwell: two closes within 6 bars (the method gives no minimum dwell time).
- [Proposed] Failed auction stop beyond the highest high (lowest low) of the last 8 bars plus 0.1%; at most 3% away.
- [Legacy] Failed auction targets: half at the POC, the rest at the other side of value; stop to entry after the first.
- [Legacy] CCV long: the day opens below the prior day's value area low, then two consecutive 30-minute closes back above it.
- [Legacy] CCV target: the prior day's value area high; short is the mirror.
- [Proposed] CCV stop below the day's low so far (above the high for shorts) plus 0.1%, at most 3% away; at most 48 bars.
- [Published] CC Fibonacci long: 4-hour 26 EMA above the 55 EMA, the latest 2-day move is up, the low reaches the 0.618 level and the close holds above 0.66, plus one more confluence.
- [Proposed] CC Fibonacci anchors: high and low of the last 96 bars; stop beyond the 0.786 level plus 0.1%; half at 1R, rest at the swing high (low); stop to entry after the first; skip below 1R.
- [Published] EMA swing: on a 4-hour close the 26 EMA crosses above (below) the 55 EMA.
- [Legacy] EMA swing: the 100 and 200 EMAs give context: price on the right side of the 200 EMA, stop beyond the 100 EMA plus 0.5%, at most 8% away.
- [Legacy] EMA swing management: half at 2R, then trail the rest to each confirmed 12-hour swing; exit on the opposite cross.
- [Published] Each trade risks 1% of equity, sized from the actual stop distance.
- [Proposed] One position at a time, no leverage (at most 100% of equity); at most 2% of equity at risk; 6 trades and 3 losing trades per day; stop for the day after a 3% loss; suspend after a 20% drawdown.

FURTHER RESEARCH
- CC Fibonacci zone: 0.618-0.66 with two or more confluences -> confirmed (source: Chart Champions Fibonacci retracement guide, S1).
- Risk per trade: not stated in the research -> the journal uses 1% as an example (source: Chart Champions journal updates, S9).
- Failed auction confirmation: 30-minute close back inside -> confirmed as the published acceptance signal (source: S7, S10).

STILL MISSING
- Failed auction minimum dwell time beyond the level: not published - two closes within 6 bars used.
- SFP stop buffer and maximum distance: not published - 0.1% and 2.5% used.
- CCW ladder: no first-party source re-verified, and the app cannot ladder entries - left out.
- Daily Open quartiles: the directional branches and measurement horizon are not published - left out.
- Opening range breakout: a stock-market session setup - not applicable to this crypto plan.
- Liquidation scalp, flags, wedges, triangles, three-candle (Severin) method, CCV 2.0, SFP 2.0, CCTR, Frontrun, 3rd Touch, Order Blocks: rules not published - left out.
- Altcoin versus BTC context: the app cannot read another symbol - plan limited to BTC-USD.
- Statistics (CCV 80%, Daily Open 90%): historical claims, not used as rules.
