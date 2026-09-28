# Android app: build, sign, install

The Android app is a private, sideloaded client. The backend is authoritative for everything. The
app stores only an encrypted session token, a few local preferences and a cache of backend
responses (Room), which is cleared on sign-out.

## Modules

| Module | What it contains | Where it builds |
|---|---|---|
| `android/core` | Pure Kotlin/JVM code with no Android dependency. It holds the HTTP client (idempotency keys reused on retry, problem mapping, HTTPS enforcement), DTOs, the cache-then-network policy, formatting (BigDecimal, timezone, P/L cues that don't rely on colour), lock-screen redaction, deep-link parsing, and the access, recommendation and emergency presenters. | Anywhere with Maven Central: `gradle -p android/core test` |
| `android/app` | A single-activity Jetpack Compose app. It uses Material 3, Hilt, Room (cache only), WorkManager (periodic sync plus local alerts for critical inbox items), optional FCM initialised at runtime from the backend, and an Android Keystore AES-256-GCM session store. | Needs Google Maven (`dl.google.com`); GitHub Actions `android` job |

## Build

Requirements: JDK 21 and the Android SDK (platform 35). The Gradle wrapper pins Gradle 8.14.3.

```bash
cd android
./gradlew spotlessCheck :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Debug builds (`app.strategyforge.android.debug`) may connect to `http://10.0.2.2:8080` (the
emulator's loopback) for local development. Release builds accept HTTPS backends only; the
network security configuration forbids cleartext traffic.

## Release signing

Signing material never lives in the repository.

1. Create a key once and keep it offline:
   ```bash
   keytool -genkeypair -keystore strategyforge-release.jks -storetype PKCS12 -alias strategyforge \
     -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=StrategyForge Owner"
   ```
2. Build locally with environment variables:
   ```bash
   export SF_RELEASE_KEYSTORE_FILE=/secure/path/strategyforge-release.jks
   export SF_RELEASE_KEYSTORE_PASSWORD=... SF_RELEASE_KEY_ALIAS=strategyforge SF_RELEASE_KEY_PASSWORD=...
   ./gradlew :app:assembleRelease
   ```
   The signed APK is `android/app/build/outputs/apk/release/app-release.apk`.
3. To let CI sign with your key, add repository secrets: `SF_RELEASE_KEYSTORE_B64`
   (`base64 -w0 strategyforge-release.jks`), `SF_RELEASE_KEYSTORE_PASSWORD`, `SF_RELEASE_KEY_ALIAS`,
   `SF_RELEASE_KEY_PASSWORD`.

Without those secrets, CI signs release builds with an **ephemeral** key generated for that run, so
the build and signature can be verified. The `android-outputs` artifact records the SHA-256 of each
APK and the signing certificate in `apk/SHA256SUMS` and `apk/release/SIGNING.txt`. APKs signed with
different keys cannot update each other, so install your own signed build for long-term use.

## Install (sideload)

1. On the phone, allow installs from the file manager or browser you use.
2. Check the checksum: `sha256sum app-release.apk` must match `SHA256SUMS`.
3. Install with `adb install app-release.apk`, or open the file on the device.
4. Start the app, enter your backend's HTTPS address (for example a private VPN address fronted by
   the Caddy TLS proxy), then create the owner account on first run, or sign in.
5. Store the recovery codes shown once after bootstrap offline.

## Push notifications (optional)

The in-app inbox is authoritative, and push is only a convenience. To enable FCM, configure an
`FCM` push provider on the backend (project id, application id, API key, sender id and the
service-account credential). The app fetches only the public identifiers from
`GET /v1/devices/push-config`, initialises Firebase at runtime and registers its token. Lock-screen
notifications always show a generic text. Notification payloads carry only ids and redacted text,
and tapping one opens an authenticated screen; nothing is executed from a notification. Without
push, the periodic sync shows local alerts for new critical inbox items.

## Privacy defaults

- Screenshots and recent-apps previews are blocked (`FLAG_SECURE`); this can be turned off in
  Settings.
- Notification text can also be hidden while the phone is unlocked.
- Backups and device transfer exclude all app data.
