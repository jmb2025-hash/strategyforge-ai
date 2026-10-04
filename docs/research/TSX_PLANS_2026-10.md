# TSX four-plan research, October 2026

Interactive report with charts: https://claude.ai/artifact/MBGgKnTkLGtMDb7sUnVZWq (private to the owner).

## Data and method

- **Data:** Yahoo Finance daily prices, dividends and splits for 175 TSX listings (about 120 stocks across 12 sectors, plus 53 ETFs for equity, bonds and gold), October 2014 to October 2 2026. 160 cover the full ten years. `scripts/tget.py` downloads it again.
- **Simulation:** C$10,000 start on October 3 2016.
  - Shares are tracked daily, and dividends are paid on the ex-date.
  - DRIP view: dividends buy more of the payer. Income view: dividends are paid out in cash.
  - Costs: 0.10% per trade (0.07% for the rotation plan).
  - Companies taken over convert to cash at their last price.
- **Selection:** every rule was chosen on October 2016 – December 2021 only, then run unchanged on January 2022 – October 2026 ("unseen").
- **Limits:**
  - Some companies that were taken over or delisted are missing from the free data, which slightly flatters stock picks.
  - Results are before tax.
  - These are backtests, not advice.

## Results (C$10,000, October 2016 – October 2026)

| Plan | Per year | Selection | Unseen | Worst drop | Worst 12 months | 12-month windows up | $10k with DRIP | Paid out: value + dividends | Last 12 months of dividends |
|---|---|---|---|---|---|---|---|---|---|
| 1 · Dividend Growth & Momentum 24 | 17.0% | 18.6% | 15.2% | -38.5% | -6.3% | 92% | $47,930 | $31,474 + $7,478 | $1,104 |
| 2 · High-Yield Trend 12 | 11.1% | 12.2% | 9.9% | -11.4% | -4.6% | 95% | $28,729 | $17,949 + $6,257 | $891 |
| 3 · All-Weather Core-Satellite | 14.2% | 16.1% | 12.3% | -24.2% | -13.8% | 90% | $37,750 | $30,282 + $3,880 | $575 |
| 4 · Momentum Rotation 50/50 | 17.8% | 20.2% | 15.1% | -32.6% | -7.0% | 91% | $51,497 | $41,777 + $4,514 | $905 |
| XIC (iShares Core S&P/TSX Capped Composite Index ETF) | 12.3% | 10.2% | 14.6% | -37.2% | -14.5% | 83% | $31,849 | $24,024 + $3,802 | $479 |
| VDY (Vanguard FTSE Canadian High Dividend Yield Index ETF) | 14.3% | 11.4% | 17.2% | -39.2% | -13.4% | 80% | $37,842 | $24,802 + $5,665 | $720 |
| VFV (Vanguard S&P 500 Index ETF) | 16.1% | 17.2% | 14.6% | -27.5% | -12.6% | 90% | $44,297 | $38,773 + $2,460 | $322 |
| XEI (iShares S&P/TSX Composite High Dividend Index ETF) | 11.9% | 9.4% | 14.6% | -45.5% | -20.5% | 77% | $30,844 | $19,070 + $5,663 | $671 |

## The four plans

**1 · Dividend Growth & Momentum 24 (long term, high dividend).**
- Universe: TSX dividend payers with no dividend cut over 5% in the past three years.
- Ranking: dividend yield and 6-month momentum, weighted equally.
- Holdings: the top 25, at most 2 per sector (about 24 stocks), equal weight.
- Rebalance: each January.

**2 · High-Yield Trend 12 (short term, high dividend).**
- Universe: payers yielding at least 4% with no recent cut.
- Holdings: the top 12 by yield, at most 2 per sector, checked monthly.
- Trend filter: each holding is kept only while above its 200-day average; otherwise its share goes to ZST (short-term bonds).
- Note: as of October 2026 it is mostly in ZST, because most of the highest yielders are in downtrends.

**3 · All-Weather Core-Satellite (long term, diversified).**
- Core (70%): an ETF mix of XIT 24%, ZQQ 15%, CGL.C 15%, XSB 15%, XUT 12%, VFV 9% and ZAG 9%. The weights are the average of the top 1% of about 30,000 diversity-constrained mixes scored on the selection years.
- Satellite (30%): the Plan 1 stocks.
- Rebalance: quarterly.

**4 · Momentum Rotation 50/50 (short term, diversified).**
- ETF half: each month, the 5 strongest of 17 TSX ETFs by 3-month total return.
- Stock half: each quarter, the 16 strongest TSX stocks by 6-month return, at most 2 per sector.

## What failed

- **Ranking by raw yield.** It was best on the selection years, then made only 12–13% a year on the unseen years.
- **Trend overlays for long-term dividend holders.** They cut drawdowns but cost about a third of the return.
- **A market-level timing switch.** It did not help: the 2020 crash was too fast for it.
- **Cross-market leads** (BTC to TSX financials and the index; gold to miners; oil to energy). They were significant in 2016–2021 but faded after 2022, and trading on them lost to holding.

## Files

- `planN_monthly.csv`: monthly market value with dividends reinvested, market value with dividends paid out, and dividends paid in each month.
- `planN_holdings_<date>.csv`: current holdings and weights.
- `scripts/`: data download (`tget.py`), simulator (`tsxlib.py`), selection rules (`picks.py`), the searches (`p1d`, `p2`, `p3`, `p3b`, `p3c`, `p4`, `p4b`, `p4c`) and the final run (`final.py`).
