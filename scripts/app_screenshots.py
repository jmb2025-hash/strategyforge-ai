#!/usr/bin/env python3
"""Drive the StrategyForge debug build on a running emulator and save screenshots.

Usage: app_screenshots.py <app-debug.apk> <strategy.json> <out-dir>

Every step is best-effort: a step that cannot find its button is logged and skipped so the
remaining screens are still captured. Nothing here touches real money or real accounts; the app
starts in demo mode with recorded market data.
"""

import json
import re
import shlex
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

PKG = "app.strategyforge.android.debug"
ACTIVITY = f"{PKG}/app.strategyforge.android.MainActivity"

apk, strategy_file, out = sys.argv[1], sys.argv[2], Path(sys.argv[3])
out.mkdir(parents=True, exist_ok=True)
debug = out / "debug"
debug.mkdir(exist_ok=True)
log_lines = []
counter = 0


def log(msg):
    print(msg, flush=True)
    log_lines.append(msg)


def adb(*args, check=False):
    r = subprocess.run(["adb", *args], capture_output=True)
    if check and r.returncode != 0:
        raise RuntimeError(f"adb {args} failed: {r.stderr.decode(errors='replace')}")
    return r


def sh(cmd):
    return adb("shell", cmd).stdout.decode(errors="replace")


def screen_size():
    m = re.search(r"(\d+)x(\d+)", sh("wm size"))
    return int(m.group(1)), int(m.group(2))


W, H = 0, 0


def dump():
    for _ in range(3):
        sh("uiautomator dump /sdcard/ui.xml")
        raw = adb("exec-out", "cat", "/sdcard/ui.xml").stdout
        try:
            return ET.fromstring(raw)
        except ET.ParseError:
            time.sleep(1)
    return ET.fromstring(b"<hierarchy/>")


def bounds(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    return x1, y1, x2, y2


def nodes(label, exact=True):
    root = dump()
    found = []
    for n in root.iter("node"):
        for attr in ("text", "content-desc"):
            v = n.get(attr) or ""
            if (v == label) if exact else (label in v):
                found.append(n)
                break
    return found


def swipe_up():
    sh(f"input swipe {W // 2} {int(H * 0.72)} {W // 2} {int(H * 0.32)} 350")
    time.sleep(0.8)


def scroll_top():
    for _ in range(6):
        sh(f"input swipe {W // 2} {int(H * 0.25)} {W // 2} {int(H * 0.85)} 200")
    time.sleep(0.8)


def tap(label, exact=True, scroll=True, lowest=False):
    for _ in range(8 if scroll else 1):
        found = nodes(label, exact) or ([] if not exact else nodes(label, False))
        # Ignore matches hidden under the top bar or bottom navigation.
        visible = [n for n in found if lowest or (H * 0.08 < (bounds(n)[1] + bounds(n)[3]) / 2 < H * 0.88)]
        if visible:
            n = max(visible, key=lambda n: bounds(n)[1]) if lowest else visible[0]
            x1, y1, x2, y2 = bounds(n)
            sh(f"input tap {(x1 + x2) // 2} {(y1 + y2) // 2}")
            time.sleep(1.5)
            return True
        if scroll:
            swipe_up()
    raise RuntimeError(f"could not find '{label}'")


def tab(label):
    tap(label, scroll=False, lowest=True)
    time.sleep(1.5)


def hide_keyboard():
    if "mInputShown=true" in sh("dumpsys input_method"):
        adb("shell", "input", "keyevent", "4")
        time.sleep(0.8)


def back():
    hide_keyboard()
    adb("shell", "input", "keyevent", "4")
    time.sleep(1.5)


def type_text(text):
    # adb "input text" treats %s as a space. Small, paced chunks: larger ones drop characters.
    for i in range(0, len(text), 25):
        chunk = text[i : i + 25].replace(" ", "%s")
        sh("input text " + shlex.quote(chunk))
        time.sleep(0.35)
    time.sleep(0.5)


def focused_text():
    for n in dump().iter("node"):
        if n.get("focused") == "true" and "EditText" in (n.get("class") or ""):
            return n.get("text") or ""
    return None


def clear_focused():
    sh("input keycombination 113 29")  # Ctrl+A
    sh("input keyevent 67")
    time.sleep(0.3)


def fill(label, value, clear=False):
    tap(label, exact=False)
    if clear:
        clear_focused()
    for attempt in range(3):
        type_text(value)
        got = focused_text()
        if got is None or got == value:
            break
        log(f"field '{label}' read back wrong (attempt {attempt + 1}), retyping")
        clear_focused()
    hide_keyboard()


def shot(name, wait=2.0):
    global counter
    time.sleep(wait)
    counter += 1
    path = out / f"{counter:02d}_{name}.png"
    with open(path, "wb") as f:
        f.write(adb("exec-out", "screencap", "-p").stdout)
    log(f"saved {path.name}")


def step(name, fn):
    try:
        fn()
    except Exception as e:  # noqa: BLE001 - keep capturing the remaining screens
        log(f"STEP FAILED: {name}: {e}")
        with open(debug / f"{name}.xml", "wb") as f:
            f.write(adb("exec-out", "cat", "/sdcard/ui.xml").stdout)
        with open(debug / f"{name}.png", "wb") as f:
            f.write(adb("exec-out", "screencap", "-p").stdout)


# ---------------------------------------------------------------------------------------------

adb("install", "-r", "-g", apk, check=True)
W, H = screen_size()
log(f"screen {W}x{H}")
# The app blocks screenshots and asks for the screen lock by default. Turn both off on this
# throwaway emulator only (the owner's phone keeps its own settings).
prefs = (
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>"
    '<boolean name="secureScreen" value="false" /><boolean name="appLock" value="false" /></map>'
)
sh(f"run-as {PKG} sh -c " + shlex.quote(f"mkdir -p shared_prefs && echo {shlex.quote(prefs)} > shared_prefs/sf_config.xml"))
log(sh(f"run-as {PKG} cat shared_prefs/sf_config.xml"))
sh("settings put global window_animation_scale 0.5")
sh(f"am start -W -n {ACTIVITY}")
time.sleep(10)

step("home_first_run", lambda: shot("home_first_run"))


def portfolio():
    tab("Portfolio")
    shot("portfolio_empty")
    fill("Name", "Main paper portfolio")
    tap("Create")
    shot("portfolio_created", 3)


def orders():
    scroll_top()
    for sym, qty in (("BTC-USD", "0.5"), ("ETH-USD", "4"), ("SOL-USD", "60")):
        fill("Symbol", sym, clear=True)
        fill("Quantity", qty, clear=True)
        tap("Submit paper order")
        time.sleep(6)
        scroll_top()
    shot("portfolio_with_positions", 4)
    swipe_up()
    shot("portfolio_positions_scrolled")
    swipe_up()
    swipe_up()
    shot("portfolio_orders")


step("portfolio", portfolio)
step("orders", orders)

strategy = json.dumps(json.loads(Path(strategy_file).read_text()), separators=(",", ":"))


def strategies():
    tab("Strategies")
    shot("strategies_empty")
    tap("Create / import")
    shot("strategy_import")
    fill("Strategy (JSON", strategy)
    tap("Validate and import")
    shot("strategy_detail", 5)
    swipe_up()
    shot("strategy_detail_chart")


def backtest():
    tap("Backtest on recent history")
    shot("strategy_backtest", 20)
    swipe_up()
    shot("strategy_backtest_results")


def activate():
    tap("Activate with notifications")
    shot("strategy_activated", 4)


step("strategies", strategies)
step("backtest", backtest)
step("activate", activate)


def strategies_overview():
    back()
    tab("Strategies")
    scroll_top()
    shot("strategies_running")
    tap("Compare results / build a better strategy")
    shot("scorecards", 3)
    back()
    tap("AI research")
    shot("ai_research", 3)
    back()


step("strategies_overview", strategies_overview)

# Give the demo market time to move so the dashboard and recommendations have something to show.
time.sleep(30)


def home():
    tab("Home")
    scroll_top()
    shot("home", 3)
    swipe_up()
    shot("home_scrolled")


def chart():
    tab("Portfolio")
    scroll_top()
    shot("portfolio_after_trading", 2)
    for sym in ("BTC-USD ·", "ETH-USD ·", "SOL-USD ·"):
        try:
            tap(sym, exact=False, scroll=True)
            break
        except RuntimeError:
            scroll_top()
    else:
        raise RuntimeError("no position to open")
    shot("candlestick_chart", 4)
    back()


def activity():
    tab("Activity")
    shot("activity_recommendations")
    tap("Inbox", scroll=False)
    shot("activity_inbox")


step("home", home)
step("chart", chart)
step("activity", activity)

more_items = [
    "Market data and background running",
    "AI providers and keys",
    "AI budget",
    "Reports",
    "Exports (CSV / JSON)",
    "Backups",
    "Settings and privacy",
    "Diagnostics",
    "Emergency controls",
]


def more_menu():
    tab("More")
    scroll_top()
    shot("more_menu")


step("more_menu", more_menu)
for item in more_items:

    def open_item(item=item):
        tab("More")
        scroll_top()
        tap(item)
        shot("more_" + re.sub(r"[^a-z]+", "_", item.lower()).strip("_"), 3)
        back()

    step("more_" + item, open_item)

(out / "log.txt").write_text("\n".join(log_lines) + "\n")
with open(debug / "logcat.txt", "wb") as f:
    f.write(adb("logcat", "-d", "-t", "3000").stdout)
