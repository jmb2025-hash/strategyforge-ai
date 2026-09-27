#!/usr/bin/env python3
"""Deterministic generator for StrategyForge replay fixtures.

The generated data is SYNTHETIC. It exists so that core CI, demo mode and
end-to-end tests never depend on a live market-data provider (NFR-012).
Every file is labelled synthetic in fixtures/replay/manifest.json and the
backend reports the replay feed as REPLAY_SYNTHETIC.

All arithmetic is integer arithmetic on minor units (cents, volume units,
FX micro-units). No floating point is used, so output is bit-for-bit
reproducible on every platform. Run with --check to verify committed
fixtures match the generator.
"""
from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import os
import sys
from zoneinfo import ZoneInfo

GENERATOR_VERSION = "1.0.0"
SEED = 20260927
ET = ZoneInfo("America/New_York")
UTC = dt.timezone.utc

DAILY_START = dt.date(2023, 1, 3)
HOURLY_START = dt.date(2026, 1, 2)
MINUTE_START = dt.date(2026, 6, 22)
END = dt.date(2026, 6, 26)

# NYSE full-day holidays and early closes (13:00 ET). Must match MarketCalendar.kt.
HOLIDAYS = {
    dt.date(2023, 1, 2), dt.date(2023, 1, 16), dt.date(2023, 2, 20), dt.date(2023, 4, 7),
    dt.date(2023, 5, 29), dt.date(2023, 6, 19), dt.date(2023, 7, 4), dt.date(2023, 9, 4),
    dt.date(2023, 11, 23), dt.date(2023, 12, 25),
    dt.date(2024, 1, 1), dt.date(2024, 1, 15), dt.date(2024, 2, 19), dt.date(2024, 3, 29),
    dt.date(2024, 5, 27), dt.date(2024, 6, 19), dt.date(2024, 7, 4), dt.date(2024, 9, 2),
    dt.date(2024, 11, 28), dt.date(2024, 12, 25),
    dt.date(2025, 1, 1), dt.date(2025, 1, 9), dt.date(2025, 1, 20), dt.date(2025, 2, 17),
    dt.date(2025, 4, 18), dt.date(2025, 5, 26), dt.date(2025, 6, 19), dt.date(2025, 7, 4),
    dt.date(2025, 9, 1), dt.date(2025, 11, 27), dt.date(2025, 12, 25),
    dt.date(2026, 1, 1), dt.date(2026, 1, 19), dt.date(2026, 2, 16), dt.date(2026, 4, 3),
    dt.date(2026, 5, 25), dt.date(2026, 6, 19), dt.date(2026, 7, 3), dt.date(2026, 9, 7),
    dt.date(2026, 11, 26), dt.date(2026, 12, 25),
}
EARLY_CLOSES = {
    dt.date(2023, 7, 3), dt.date(2023, 11, 24),
    dt.date(2024, 7, 3), dt.date(2024, 11, 29), dt.date(2024, 12, 24),
    dt.date(2025, 7, 3), dt.date(2025, 11, 28), dt.date(2025, 12, 24),
    dt.date(2026, 11, 27), dt.date(2026, 12, 24),
}

# symbol, asset class, start price (cents, e.g. 12_500 = 125.00 USD), daily sigma (bp), drift (bp/day), volume base, spread (bp)
INSTRUMENTS = [
    ("AAPL", "US_EQUITY", 12_500, 120, 4, 55_000_000, 2),
    ("MSFT", "US_EQUITY", 24_000, 110, 4, 25_000_000, 2),
    ("NVDA", "US_EQUITY", 14_500, 260, 9, 45_000_000, 3),
    ("AMZN", "US_EQUITY", 8_500, 150, 5, 40_000_000, 2),
    ("SPY", "US_EQUITY", 38_000, 80, 3, 70_000_000, 1),
    ("QQQ", "US_EQUITY", 26_500, 100, 4, 40_000_000, 1),
    ("JPM", "US_EQUITY", 13_500, 110, 3, 9_000_000, 2),
    ("XOM", "US_EQUITY", 11_000, 130, 1, 16_000_000, 2),
    ("BTC-USD", "CRYPTO", 1_680_000, 280, 8, 25_000, 2),
    ("ETH-USD", "CRYPTO", 120_000, 330, 6, 300_000, 3),
    ("SOL-USD", "CRYPTO", 1_000, 450, 12, 2_500_000, 5),
]

# Synthetic corporate actions (not real events). ratio is new:old for splits.
SPLITS = [("NVDA", dt.date(2025, 6, 10), 4, 1)]
DIVIDENDS = {  # symbol: (cents per share, months of ex-dates, day of month)
    "AAPL": (25, (2, 5, 8, 11), 9),
    "MSFT": (83, (2, 5, 8, 11), 15),
    "JPM": (115, (1, 4, 7, 10), 5),
    "XOM": (95, (2, 5, 8, 11), 13),
    "SPY": (170, (3, 6, 9, 12), 20),
}
# Deliberate, documented data gap to exercise gap reporting: XOM 1h bars removed.
GAPS_1H = {("XOM", dt.date(2026, 3, 11)): {1, 2, 3}}
# Scripted stress regime: a synthetic drawdown used by risk/drawdown scenarios.
CRASH = (dt.date(2025, 4, 1), dt.date(2025, 4, 22), -190)  # bp per day added


class Rng:
    """SplitMix64: tiny, well-known, deterministic integer PRNG."""

    def __init__(self, seed: int) -> None:
        self.state = seed & 0xFFFFFFFFFFFFFFFF

    def next(self) -> int:
        self.state = (self.state + 0x9E3779B97F4A7C15) & 0xFFFFFFFFFFFFFFFF
        z = self.state
        z = ((z ^ (z >> 30)) * 0xBF58476D1CE4E5B9) & 0xFFFFFFFFFFFFFFFF
        z = ((z ^ (z >> 27)) * 0x94D049BB133111EB) & 0xFFFFFFFFFFFFFFFF
        return z ^ (z >> 31)

    def uniform(self, lo: int, hi: int) -> int:
        return lo + self.next() % (hi - lo + 1)

    def bell(self, sigma: int) -> int:
        # Sum of three uniforms approximates a normal distribution.
        return sum(self.uniform(-sigma, sigma) for _ in range(3)) // 2


def trading_day(d: dt.date) -> bool:
    return d.weekday() < 5 and d not in HOLIDAYS


def days(start: dt.date, end: dt.date, crypto: bool):
    d = start
    while d <= end:
        if crypto or trading_day(d):
            yield d
        d += dt.timedelta(days=1)


def session_bounds(d: dt.date):
    open_et = dt.datetime.combine(d, dt.time(9, 30), ET)
    close_et = dt.datetime.combine(d, dt.time(13, 0) if d in EARLY_CLOSES else dt.time(16, 0), ET)
    return open_et.astimezone(UTC), close_et.astimezone(UTC)


def bridge(rng: Rng, start: int, end: int, n: int, sigma_bp: int) -> list[int]:
    """Integer random walk of n steps from start that ends exactly at end."""
    walk = [start]
    for _ in range(n):
        step = walk[-1] * rng.bell(sigma_bp) // 10000
        walk.append(max(1, walk[-1] + step))
    drift = end - walk[-1]
    out = [start]
    for i in range(1, n + 1):
        out.append(max(1, walk[i] + drift * i // n))
    out[-1] = end
    return out


def bar_from_path(rng: Rng, o: int, c: int, wick_bp: int) -> tuple[int, int, int, int]:
    hi = max(o, c)
    lo = min(o, c)
    hi += hi * rng.uniform(0, wick_bp) // 10000
    lo -= lo * rng.uniform(0, wick_bp) // 10000
    return o, max(hi, o, c), max(1, min(lo, o, c)), c


def fmt_cents(v: int) -> str:
    sign = "-" if v < 0 else ""
    v = abs(v)
    return f"{sign}{v // 100}.{v % 100:02d}"


def generate(root: str) -> dict:
    files: dict[str, str] = {}
    rng = Rng(SEED)
    split_map = {(s, d): (n, o) for s, d, n, o in SPLITS}

    for symbol, asset_class, start_price, sigma, drift, vol_base, _spread in INSTRUMENTS:
        crypto = asset_class == "CRYPTO"
        srng = Rng(SEED ^ int(hashlib.sha256(symbol.encode()).hexdigest()[:12], 16))
        # 1) daily close path
        daily_days = list(days(DAILY_START, END, crypto))
        closes: dict[dt.date, int] = {}
        prev = start_price
        for d in daily_days:
            if (symbol, d) in split_map:
                n, o = split_map[(symbol, d)]
                prev = prev * o // n  # unadjusted price series drops on ex-date
            r = srng.bell(sigma) + drift
            if CRASH[0] <= d <= CRASH[1]:
                r += CRASH[2]
            prev = max(100, prev + prev * r // 10000)
            closes[d] = prev

        # 2) hourly paths (bridge between consecutive daily closes)
        hourly: list[tuple[dt.datetime, int, int]] = []  # (open_time, open, close)
        minute: list[tuple[dt.datetime, int, int]] = []
        prev_close = None
        for d in daily_days:
            day_close = closes[d]
            day_open = prev_close if prev_close is not None else day_close
            if (symbol, d) in split_map:
                n, o = split_map[(symbol, d)]
                day_open = day_open * o // n
            prev_close = day_close
            if d < HOURLY_START:
                continue
            if crypto:
                starts = [dt.datetime.combine(d, dt.time(h), UTC) for h in range(24)]
                sess_end = dt.datetime.combine(d + dt.timedelta(days=1), dt.time(0), UTC)
            else:
                s_open, s_close = session_bounds(d)
                starts = []
                t = s_open
                while t < s_close:
                    starts.append(t)
                    t += dt.timedelta(hours=1)
                sess_end = s_close
            path = bridge(srng, day_open, day_close, len(starts), max(5, sigma // 5))
            for i, t in enumerate(starts):
                hourly.append((t, path[i], path[i + 1]))
                if d >= MINUTE_START:
                    t_end = starts[i + 1] if i + 1 < len(starts) else sess_end
                    mins = int((t_end - t).total_seconds() // 60)
                    mpath = bridge(srng, path[i], path[i + 1], mins, max(2, sigma // 40))
                    for m in range(mins):
                        minute.append((t + dt.timedelta(minutes=m), mpath[m], mpath[m + 1]))

        # 3) Build bars bottom-up so every timeframe is mutually consistent.
        minute_bars = []
        vol_1m = max(1, vol_base // (1440 if crypto else 390))
        for t, o, c in minute:
            o_, h, l_, c_ = bar_from_path(srng, o, c, max(1, sigma // 60))
            minute_bars.append((t, o_, h, l_, c_, vol_1m * srng.uniform(50, 150) // 100))
        by_hour: dict[dt.datetime, list] = {}
        for b in minute_bars:
            by_hour.setdefault(hour_bucket(b[0], crypto), []).append(b)

        hourly_bars = []
        vol_1h = max(1, vol_base // (24 if crypto else 7))
        for t, o, c in hourly:
            if t in by_hour:
                ms = by_hour[t]
                hourly_bars.append((t, ms[0][1], max(x[2] for x in ms), min(x[3] for x in ms), ms[-1][4], sum(x[5] for x in ms)))
            else:
                o_, h, l_, c_ = bar_from_path(srng, o, c, max(2, sigma // 8))
                hourly_bars.append((t, o_, h, l_, c_, vol_1h * srng.uniform(50, 150) // 100))
        by_day: dict[dt.date, list] = {}
        for b in hourly_bars:
            by_day.setdefault(b[0].astimezone(ET).date() if not crypto else b[0].date(), []).append(b)

        daily_bars = []
        prev_close = None
        for d in daily_days:
            t = dt.datetime.combine(d, dt.time(0), UTC) if crypto else session_bounds(d)[0]
            if d in by_day:
                hs = by_day[d]
                daily_bars.append((t, hs[0][1], max(x[2] for x in hs), min(x[3] for x in hs), hs[-1][4], sum(x[5] for x in hs)))
            else:
                o = prev_close if prev_close is not None else closes[d]
                if (symbol, d) in split_map:
                    n, od = split_map[(symbol, d)]
                    o = o * od // n
                o_, h, l_, c_ = bar_from_path(srng, o, closes[d], max(5, sigma // 2))
                daily_bars.append((t, o_, h, l_, c_, vol_base * srng.uniform(60, 140) // 100))
            prev_close = closes[d]

        # Apply the documented deliberate gap after aggregation (source-side gap).
        gapped = []
        for b in hourly_bars:
            key = (symbol, b[0].astimezone(ET).date())
            if key in GAPS_1H:
                idx = [x[0] for x in hourly_bars if (symbol, x[0].astimezone(ET).date()) == key].index(b[0])
                if idx in GAPS_1H[key]:
                    continue
            gapped.append(b)
        hourly_bars = gapped

        for tf, bars in (("1d", daily_bars), ("1h", hourly_bars), ("1m", minute_bars)):
            lines = ["open_time_utc,open,high,low,close,volume"]
            for t, o, h, l_, c, v in bars:
                lines.append(f"{t.strftime('%Y-%m-%dT%H:%M:%SZ')},{fmt_cents(o)},{fmt_cents(h)},{fmt_cents(l_)},{fmt_cents(c)},{v}")
            files[f"candles/{tf}/{symbol}.csv"] = "\n".join(lines) + "\n"

    # Instruments
    lines = ["symbol,asset_class,replay_spread_bps"]
    for symbol, asset_class, *_rest in INSTRUMENTS:
        lines.append(f"{symbol},{asset_class},{_rest[-1]}")
    files["instruments.csv"] = "\n".join(lines) + "\n"

    # Corporate actions
    lines = ["symbol,type,ex_date,pay_date,ratio_new,ratio_old,cash_amount"]
    for s, d, n, o in SPLITS:
        lines.append(f"{s},SPLIT,{d.isoformat()},{d.isoformat()},{n},{o},")
    for s, (cents, months, dom) in DIVIDENDS.items():
        for year in (2023, 2024, 2025, 2026):
            for m in months:
                ex = dt.date(year, m, dom)
                while not trading_day(ex):
                    ex += dt.timedelta(days=1)
                if DAILY_START <= ex <= END:
                    pay = ex + dt.timedelta(days=14)
                    lines.append(f"{s},CASH_DIVIDEND,{ex.isoformat()},{pay.isoformat()},,,{fmt_cents(cents)}")
    header, body = lines[0], sorted(lines[1:], key=lambda x: (x.split(",")[2], x.split(",")[0]))
    files["corporate_actions.csv"] = "\n".join([header, *body]) + "\n"

    # FX USD/CAD daily (micro-units: 1.365000 -> 1365000)
    lines = ["as_of_utc,base,quote,rate"]
    rate = 1_352_000
    for d in days(DAILY_START, END, True):
        rate = max(1_250_000, min(1_450_000, rate + rng.bell(1500)))
        lines.append(f"{d.isoformat()}T21:00:00Z,USD,CAD,{rate // 1_0000}.{rate % 1_0000:06d}")
    files["fx/USD_CAD.csv"] = "\n".join(lines) + "\n"

    manifest = {
        "synthetic": True,
        "description": "SYNTHETIC deterministic replay data for tests and demo mode. Not real market prices.",
        "generatorVersion": GENERATOR_VERSION,
        "seed": SEED,
        "ranges": {"1d": [DAILY_START.isoformat(), END.isoformat()], "1h": [HOURLY_START.isoformat(), END.isoformat()], "1m": [MINUTE_START.isoformat(), END.isoformat()]},
        "replayStartUtc": session_bounds(MINUTE_START)[0].strftime("%Y-%m-%dT%H:%M:%SZ"),
        "replayEndUtc": dt.datetime.combine(END + dt.timedelta(days=1), dt.time(0), UTC).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "documentedGaps": [{"symbol": s, "timeframe": "1h", "sessionDate": d.isoformat(), "missingBarIndexes": sorted(v)} for (s, d), v in GAPS_1H.items()],
        "files": {k: hashlib.sha256(v.encode()).hexdigest() for k, v in sorted(files.items())},
    }
    files["manifest.json"] = json.dumps(manifest, indent=2, sort_keys=True) + "\n"
    return files


def hour_bucket(t: dt.datetime, crypto: bool) -> dt.datetime:
    if crypto:
        return t.replace(minute=0)
    s_open, _ = session_bounds(t.astimezone(ET).date())
    elapsed = int((t - s_open).total_seconds() // 3600)
    return s_open + dt.timedelta(hours=elapsed)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=os.path.join(os.path.dirname(__file__), "..", "fixtures", "replay"))
    ap.add_argument("--check", action="store_true", help="verify committed fixtures match")
    args = ap.parse_args()
    files = generate(args.out)
    bad = []
    for rel, content in files.items():
        path = os.path.join(args.out, rel)
        if args.check:
            try:
                with open(path, encoding="utf-8") as f:
                    if f.read() != content:
                        bad.append(rel)
            except FileNotFoundError:
                bad.append(rel)
        else:
            os.makedirs(os.path.dirname(path), exist_ok=True)
            with open(path, "w", encoding="utf-8", newline="\n") as f:
                f.write(content)
    if args.check:
        if bad:
            print("Replay fixtures differ from generator output:", *bad, sep="\n  ")
            return 1
        print(f"Replay fixtures verified ({len(files)} files).")
    else:
        print(f"Wrote {len(files)} fixture files to {os.path.abspath(args.out)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
