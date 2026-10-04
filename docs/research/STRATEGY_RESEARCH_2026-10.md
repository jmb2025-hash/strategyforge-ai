# Strategy research, October 2026: stocks, novel patterns and a slot plan

All results are backtests in the app's own engine (the same code that runs your paper trading), from a $10,000 start per period, with the app's default trading costs. Each rule was chosen on an early span and then run unchanged on a later span it had never seen. Research results, not trading advice.

## Data

- **Crypto:** Bitstamp BTC/USD 1-minute candles, January 2018 to October 3 2026 (4.6 million rows), aggregated to daily bars.
- **Stocks:** daily bars for SPY, QQQ, IWM, AAPL, IBM, BAC, AIG and GOOG from QuantConnect's public sample data, adjusted for splits and dividends, 1998 to March 31 2021.
- **Stocks, 2015 to October 2 2026:** Yahoo Finance daily bars (split- and dividend-adjusted) for all 36 stock symbols the app can trade, downloaded once access was allowed (October 4 2026). Every stock rule was then run unchanged on 2021–2026, which played no part in choosing it.

## What was tested on stocks

About 6,800 rule combinations across 8 symbols:
- 2-, 3- and 4-day RSI dips;
- internal bar strength (where the close sits in the day's range);
- Bollinger-band dips and runs of down days;
- 10-day lows and the new 'dip score' composites;
- failed breakdowns below last week's and last month's low;
- reversal days, narrow-range breakouts and 55-day breakouts;
- trend filters (none, 50-, 100- and 200-day averages), five exits and optional stops.

**What held up:**
- **Dips in an up-trend.** Short-term dips while the stock is above its 50-day average bounce about 70% of the time. The edge per trade fell from about 1.1% (1998–2012) to 0.3–0.7% (2013–2021) but stayed positive on 7–8 of 8 symbols.
- **The dip score (new).** Count how many independent oversold signals agree on the same day: 2-day RSI under 10, close in the bottom 20% of the day's range, close under the lower Bollinger band, and a 10-day closing low.
  - With 3 of 4 agreeing above the 50-day average: 1.25% per trade and 73% won in 1998–2012, then 0.69% per trade and 74% won in 2013–2021, positive on all 8 symbols in both spans.
  - Requiring agreement beats any single signal.
  - It does **not** work on BTC (tested 2018–2026): crypto dips behave differently.

**What failed:**
- Failed breakdowns below the weekly or monthly low and reversal days were not consistent across symbols.
- Narrow-range and 55-day breakouts were strong in 1998–2012 but weak afterwards.
- Mean reversion without a trend filter lost in bear markets.

**Trend cores:** holding an index only while it is above its 200-day average gave up some return in bull markets but avoided most of 2000–2002 and 2008. SPY: 1998–2021 +224%, worst drop 26.7%, against about 55% for buy and hold.

## What was tested on BTC

Trend cores (SMA and EMA from 20 to 200 days, long-only or long and short, and EMA crossovers), chosen on 2018–2022 by return per unit of drawdown:

- **Long-only holding above the 100–150-day average** held up in both halves.
- **Rules that also short below the average** looked best in 2018–2022, then collapsed in 2023–2026: EMA-100 long and short went from +50% a year to +11%.
- **The 100-day long-only core:** 2018–2022 +531%, 2023–Oct 2026 +231%, whole span +1,985% ($10,000 to $208,500), worst drop 37%. BTC itself: +530%, with an 81% worst drop.

## Second unseen test: stocks 2021 – October 2026 (Yahoo data)

| Plan | 2015–2020 | 2021 – Oct 2026 (unseen) | 2015 – Oct 2026 |
|---|---|---|---|
| S&P 500 trend core | +43%, worst drop 19%, 16 trades, 31% won | +87%, worst drop 19%, 16 trades, 50% won | +166%, worst drop 19%, 31 trades, 39% won |
| Index dip score | +4%, worst drop 6%, 63 trades, 70% won | +14%, worst drop 4%, 78 trades, 71% won | +18%, worst drop 6%, 141 trades, 70% won |
| Large-cap dip score (new: 31 stocks, up to 5 positions) | +45%, worst drop 12%, 506 trades, 66% won | +25%, worst drop 13%, 539 trades, 62% won | +80%, worst drop 14%, 1045 trades, 64% won |
| SPY buy and hold | +105%, worst drop 34% | +125%, worst drop 24% | +355%, worst drop 34% |

| Stock plan | 15 | 16 | 17 | 18 | 19 | 20 | 21 | 22 | 23 | 24 | 25 | 26 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S&P 500 trend core | -6.3% | +10.3% | +19.5% | -4.0% | +13.5% | +6.4% | +28.2% | -13.8% | +11.5% | +23.8% | +9.4% | +8.9% |
| Index dip score | +1.3% | +1.7% | +1.7% | -3.6% | -0.8% | +3.0% | +5.4% | +1.1% | +0.3% | +3.3% | +0.2% | +1.3% |
| Large-cap dip score | +0.2% | +2.9% | +6.6% | -6.3% | +10.5% | +24.4% | +16.3% | -8.0% | -0.0% | +7.4% | +3.0% | +5.9% |
| SPY buy and hold | +1% | +14% | +21% | -5% | +31% | +17% | +31% | -19% | +27% | +26% | +18% | +14% |

**Reading it honestly:**
- **The patterns held on data they had never seen.** Both dip scores kept winning 6–7 trades in 10, and the trend core kept its smaller drops.
- **They did not beat simply holding the index in this bull decade.** The trend core gave up about half the return in exchange for cutting the worst drop from 34% to 19%. The dip scores are steady but sit in cash much of the time.
- **More exposure is the lever for the dip score.** Spreading it over 31 stocks with up to 5 positions raised the return from +19% to +80% over 2015–2026, with a 13.5% worst drop.

## Results by year (app engine, $10,000 fresh each year)

| Crypto plan | 2018 | 2019 | 2020 | 2021 | 2022 | 2023 | 2024 | 2025 | 2026 |
|---|---|---|---|---|---|---|---|---|---|
| BTC trend core | -27.2% | +127.8% | +162.6% | +70.2% | -19.9% | +76.7% | +54.8% | +2.2% | +11.6% |
| BTC daily trend dip and rip | -15.3% | +27.0% | +48.5% | +53.6% | +19.5% | +5.7% | -3.0% | +20.2% | +2.4% |
| Chart Champions v3 | -13.7% | +16.2% | -6.8% | +12.0% | -5.8% | -12.6% | -14.4% | +11.3% | +9.8% |

| Stock plan | 98 | 99 | 00 | 01 | 02 | 03 | 04 | 05 | 06 | 07 | 08 | 09 | 10 | 11 | 12 | 13 | 14 | 15 | 16 | 17 | 18 | 19 | 20 | 21 |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| S&P 500 trend core | +10.8% | +8.9% | -11.2% | -0.5% | -4.0% | +18.0% | +2.8% | -1.3% | +8.2% | -4.5% | -0.8% | +19.9% | -7.8% | -7.7% | +11.2% | +27.4% | +11.5% | -6.6% | +10.4% | +19.2% | -4.0% | +13.3% | +6.6% | +7.3% |
| Index dip score | +0.8% | +2.7% | -10.2% | +1.2% | +1.7% | +0.4% | +2.6% | -2.8% | +4.0% | +2.7% | +1.2% | +5.5% | -0.8% | +1.9% | +2.3% | +5.0% | +2.4% | +1.9% | +1.8% | +1.6% | -3.6% | -0.8% | +3.0% | +2.6% |

(2021 is January to March only.)

## The plan: what to run in the slots

Give every slot its own paper portfolio with its starting cash. The activation screen has a "New portfolio" button for that, so each plan's results stay separate and can be compared.

| Slot | Plan | Role | Backtest |
|---|---|---|---|
| Crypto 1 | BTC trend core | Main return engine: rides bull markets, cash in bear markets | 2018–Oct 2026: +1985%, worst drop 37%, 57 trades, 23% won |
| Crypto 2 | BTC daily trend dip and rip | High win rate; trades both directions with the trend | 2018–Oct 2026: +270%, worst drop 22%, 160 trades, 73% won |
| Crypto 3 | Chart Champions v3 | Your Chart Champions research, kept for comparison | 2018–Oct 2026: profitable in 4 of 9 years |
| Stock 1 | S&P 500 trend core | Main stock holding with a bear-market exit | 1998–Mar 2021: +224%, worst drop 27%; 2015–Oct 2026: +166%, worst drop 19% |
| Stock 2 | Large-cap dip score | High win rate across 31 large stocks, about 60% invested | 2015–Oct 2026: +80%, worst drop 14%, 1045 trades, 64% won |
| Stock 3 | Index dip score | High win rate on SPY, QQQ and IWM dips; small but steady | 1998–Mar 2021: +33%, 73% won; 2015–Oct 2026: +19%, worst drop 6%, 70% won |

**Notes**
- **Win rate and return pull against each other.** The trend cores make most of the money but lose on most trades; the dip plans win most trades but are in the market only a small part of the time. Running both kinds side by side is the point of the separate slots.
- **Live data.** Stock plans need live stock data in the app (a Twelve Data key, entered in the app's settings).
- **Done:** the stock plans were re-run on 2021 – October 2026 once data access was allowed (above).
