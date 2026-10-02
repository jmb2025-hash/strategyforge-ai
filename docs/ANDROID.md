# Android app: install, first run, build

StrategyForge runs entirely on the phone (D-027). It handles market data, strategies, backtests, risk, simulated orders, recommendations, autonomous paper trading and AI research itself. There is no server to run. The app contacts only:

- the Coinbase public market-data API, for crypto (no account or key needed);
- Twelve Data, for US stocks, but only if you add a free key;
- the AI provider you choose (Google Gemini free tier by default), and only when you run research.

**Paper trading only.** There is no real money, brokerage connection or order routing, and none can be enabled.

## Install

1. On the phone, open the repository's **Releases** page and choose **StrategyForge phone app (latest build)**.
2. Download `StrategyForge.apk`. Optionally, compare its SHA-256 with `SHA256SUMS` on the same page.
3. Open the file. When Android asks, allow your browser or file manager to install unknown apps, then tap **Install**.
4. Open StrategyForge. Allow notifications when asked.

The same APK is also in the `android-outputs` artifact of each CI run (`apk/release/app-release.apk`). Android 10 or newer is required.

### Updating to a newer build

APKs signed with different keys cannot update each other.

- **CI with your own signing key:** if you add the signing secrets described under [Release signing](#release-signing), every build uses the same key. Install new builds over the old one and your data stays.
- **CI without those secrets:** each build is signed with a new temporary key. Before updating:
  1. Open **More → Backups**, tap **Back up now**, then **Save a copy** (for example to Google Drive or Downloads).
  2. Uninstall the old app and install the new one.
  3. Open **More → Backups → Restore from a file** and pick the saved copy. Verify it, then tap **Restore**.

  AI and stock-data keys are not part of backups; enter them again.

## First run

1. **Unlock.** The app opens behind your phone's own screen lock (fingerprint, face or PIN). You can turn this off in **More → Settings and privacy**.
2. **Try it safely in demo mode.** The app starts in demo mode, which replays recorded market data. To try the app with that data:
   1. Create a portfolio (**Portfolio** tab).
   2. Place a paper order.
   3. Import a strategy (**Strategies** tab).
   4. Backtest it, then activate it in Notifications or Autonomous mode.

   Demo market time moves forward on every engine tick. You can change the speed in **More → Market data and background running**.
3. **Switch to live data.** In **More → Market data and background running**, choose **Live**:
   - Crypto uses real-time public Coinbase prices at once.
   - For US stocks, add a free Twelve Data key (<https://twelvedata.com/account/api-keys>) on the same screen. On the free plan, quotes refresh about every 5 minutes and may be delayed; the app labels delayed data (D-032).
4. **Add an AI provider.** Open **More → AI providers and keys**. Google Gemini is preselected, with free-tier prices set to 0:
   1. Tap **Get a key**.
   2. Create a key at Google AI Studio.
   3. Paste it into the app and tap **Add provider**.

   OpenRouter, OpenAI and Anthropic are optional.
5. **Research a strategy with the AI.** On the **Strategies** tab, tap **AI research**:
   1. Choose **Crypto** or **Stocks**.
   2. Describe the investor, group or strategy to research, for example "the crypto trading strategies used by Chart Champions". Gemini searches the web and cites its sources.
   3. Reply to refine the research as often as you like.
   4. Tap **Compile research into a strategy**. The AI turns the conversation into a strategy that can use moving averages, RSI, MACD, Bollinger Bands, breakouts, support and resistance, relative volume and common candlestick patterns (D-034, D-036).
   5. The strategy goes through the normal validation. Run the one-tap backtest, then activate it in **Notifications** mode (you approve each trade) or **Autonomous** mode.
6. **One crypto and one stock strategy run at a time.** The **Strategies** tab shows both under "Running now". When you activate another strategy of the same kind, the app asks whether to keep the current strategy's open positions (the new strategy manages them) or close them (D-035).
7. **Compare results and build a better strategy.** Tap **Compare results / build a better strategy** on the **Strategies** tab to see:
   - each strategy's paper profit and loss, win rate and drawdown;
   - its latest backtest;
   - a warning when it has too few trades to judge.

   **Build me a better strategy** starts an AI research conversation with every tested strategy's rules and results. You refine and compile it like any other research, and it must be backtested before it runs (D-037).
8. **Strategies that go short.** Strategies can trade both directions, including simulated crypto shorts. Before activating one, turn on **Simulated short selling** on the Portfolio tab (it asks for your screen lock). Strategies can also use daily/weekly/monthly levels, VWAP, Fibonacci, volume profile, partial profits, 1%-risk sizing and a daily losing-trade cap (D-042).
9. **Read the charts.**
   - Portfolio shows your value over 1D to All, with the change over that period, and an allocation ring.
   - Tap a position to open its candlestick chart; ▲ marks simulated buys and ▼ simulated sells.
   - A strategy's page shows its price chart with its own trades.
   - Scorecards compare strategies with bars and P&L lines.

   Drag across any chart to read values (D-038).
10. **Keep it running in the background.** On the same market-data screen:
   - Leave **Keep paper trading when the app is closed** on. A small persistent notification shows that the engine is running.
   - Tap **Allow unrestricted battery use**.
   - If your phone still closes the app, open the recent-apps screen, press and hold StrategyForge and choose **Lock** or **Keep open**.

   The engine restarts after a reboot (D-033).

"Confirm it's you" prompts (for example before enabling autonomous trading, changing a key or restoring a backup) use the same screen lock. Unlocking the app also counts as a recent confirmation for 5 minutes.

## Where data lives

| Data | Where | Backed up? |
|---|---|---|
| Portfolios, orders, strategies, recommendations, audit log, market-data cache | App-private SQLite database | Yes, in app backups (**More → Backups**) |
| AI provider keys and the Twelve Data key | Encrypted with an Android Keystore key (AES-256-GCM) | No; re-enter them after a restore on a new install |
| Privacy and background choices | App preferences | No |

Android cloud backup and device transfer exclude all app data. App backups are gzip'd JSON copies of the database with a SHA-256 checksum. Restoring one requires a recent unlock and first saves a safety backup of the current state (D-031).

## Modules

| Module | What it contains | Where it builds |
|---|---|---|
| `android/engine` | The on-device trading engine, in pure Kotlin/JVM: market data (Coinbase, Twelve Data, replay), strategies and validator, backtests, risk engine, simulated execution, ledger, recommendations, autonomy, emergency controls, AI research, reports, exports, backups, the in-process API (`LocalApi`) and the runtime (engine thread, scheduler tick, notifications). | Anywhere with Maven Central: `gradle -p android/engine test` |
| `android/core` | The app's data layer, in pure Kotlin/JVM: API client, DTOs, cache policy, formatting, redaction, deep links and presenters. The engine tests also run it against a real engine (`LocalApiAppTest`). | `gradle -p android/core test` |
| `android/app` | The single-activity Jetpack Compose app. It uses Material 3, Hilt and Room (a view cache only). It contains the Android SQLite backend, the Keystore secret store, the foreground service, the boot receiver, the device-lock prompt and local notifications. | Needs Google Maven (`dl.google.com`); GitHub Actions `android` job |

The app's screens talk to the engine through the same `/v1` API contract as Version 1. An OkHttp interceptor answers those calls in-process on the engine thread, and nothing listens on a network port (D-031).

## Build

Requirements: JDK 21 and the Android SDK (platform 35). The Gradle wrapper pins Gradle 8.14.3.

```bash
cd android
./gradlew spotlessCheck :core:test :engine:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease
```

## Release signing

Signing material never lives in the repository.

1. Create a key once and keep it offline:
   ```bash
   keytool -genkeypair -keystore strategyforge-release.jks -storetype PKCS12 -alias strategyforge \
     -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=StrategyForge Owner"
   ```
2. To let CI sign with your key, add repository secrets (**Settings → Secrets and variables → Actions**):
   - `SF_RELEASE_KEYSTORE_B64`: the output of `base64 -w0 strategyforge-release.jks`
   - `SF_RELEASE_KEYSTORE_PASSWORD`
   - `SF_RELEASE_KEY_ALIAS`
   - `SF_RELEASE_KEY_PASSWORD`
3. Or build locally with the same variables, using `SF_RELEASE_KEYSTORE_FILE` in place of the base64 value, then run `./gradlew :app:assembleRelease`.

Without those secrets, CI signs each release build with an ephemeral key generated for that run. `SIGNING.txt` on the release page records which key was used.

## Privacy defaults

- Screenshots and recent-apps previews are blocked (`FLAG_SECURE`); you can turn this off in Settings.
- Lock-screen notifications always show generic text. Details can also be hidden while the phone is unlocked.
- Notifications only open a screen behind the app lock; nothing is executed from a notification.
