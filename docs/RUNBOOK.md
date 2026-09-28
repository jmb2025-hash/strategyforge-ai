# Operations runbook

StrategyForge is a private, single-owner deployment: one backend with PostgreSQL in Docker
Compose on a machine you control, plus the Android app. **Paper trading only.** No setting,
provider or strategy can place a real-money order. The backend refuses to start if
`REAL_MONEY_TRADING_ENABLED` is anything other than `false`.

Related documents: [BACKUP_RESTORE.md](BACKUP_RESTORE.md), [ANDROID.md](ANDROID.md),
[TOOLCHAIN.md](TOOLCHAIN.md), [DECISION_LOG.md](DECISION_LOG.md).

## 1. Install and first start

Prerequisites: Docker Engine 24+ with Compose v2, 2 CPU cores, 2 GB RAM, 10 GB disk.

```bash
git clone <repo> strategyforge && cd strategyforge
cp .env.example .env
scripts/generate-secrets.sh          # prints DATABASE_PASSWORD, MASTER_ENCRYPTION_KEY, JWT_SIGNING_KEY, SF_BOOTSTRAP_TOKEN
$EDITOR .env                          # paste the values; set DATABASE_USER (e.g. strategyforge)
chmod 600 .env
(cd backend && ./gradlew bootJar)     # or use a CI-built jar in backend/build/libs/
docker compose up -d --build
docker compose ps                     # backend becomes "healthy" within about a minute
curl -s http://127.0.0.1:8080/actuator/health   # {"status":"UP"}
```

Store `MASTER_ENCRYPTION_KEY` and `JWT_SIGNING_KEY` in your password manager. Stored provider
credentials, and every backup, are unreadable without the master key.

Flyway applies all database migrations automatically at startup, and the service does not start if a
migration fails. `GET /v1/diagnostics`, under the `database` component, shows the current
schema version.

## 2. Private network and TLS

The backend listens on `127.0.0.1:8080` only. Give the phone access over a private network:

- **Tailscale (recommended):** install Tailscale on the host and the phone, then
  `tailscale serve --bg --https=443 http://127.0.0.1:8080`. Use `https://<host>.<tailnet>.ts.net`
  as the server URL in the app. The certificate is publicly trusted.
- **Caddy TLS profile:** set `SF_TLS_HOSTNAME` and `SF_TLS_BIND` (a private interface address),
  then run `docker compose --profile tls up -d`. Install Caddy's local root CA
  (`docker compose exec tls-proxy cat /data/caddy/pki/authorities/local/root.crt`) on the phone.

Release builds of the app accept only HTTPS server URLs. Never expose port 8080 to the internet.

## 3. Create the owner account

Open the app, enter the server URL, and complete **Create owner**. Enter the username,
password (12+ characters) and the `SF_BOOTSTRAP_TOKEN` from `.env`. Registration closes
permanently once the owner exists. Write the recovery codes down offline; they are shown
once. Enable TOTP with `POST /v1/auth/totp/setup` and `/v1/auth/totp/confirm` (API; not yet in the app).

## 4. Providers

All providers are optional. Replay mode needs none.

| Provider | Where | Credential |
|---|---|---|
| Market data: Replay (default) | automatic | none |
| Market data: Twelve Data | App: More → Settings, providers and privacy; or `POST /v1/providers` | `MARKET_API_KEY` in `.env`, or enter it in the app |
| AI: Anthropic / OpenAI / Gemini / OpenRouter | App: More → Settings, providers and privacy; or `POST /v1/providers` | `ANTHROPIC_API_KEY` etc. in `.env`, or enter it in the app |
| Push: Firebase Cloud Messaging | `POST /v1/providers` | service-account JSON; `FCM_CREDENTIALS_PATH` (file mounted read-only) or pasted in the app |

Credentials are encrypted with AES-256-GCM before storage and never returned by the API. Only a
short fingerprint is shown. Run **Test** on each provider. The detected capabilities are
stored and shown, and anything not detected is shown as unsupported, never assumed.
AI spending is capped by the budgets at `/v1/research/budget` (API). Raising a budget requires
recent authentication.

To use a live market data provider, create and test the provider in the app, then activate it.
If a live provider fails, market data is marked stale and trading pauses (fail closed).
Switch back to Replay at any time.

## 5. Replay and demo mode

With `MARKET_PROVIDER=REPLAY`, the backend serves the deterministic synthetic data in
`fixtures/replay/`. The replay clock only moves when advanced:

- API: `POST /v1/market-data/replay/advance` with `{"minutes": N, "stepMinutes": 1}`.
- Automatic: set `SF_REPLAY_AUTO_ADVANCE=true` (one step every 5 seconds).

Replay results are reproducible. Two runs from the same state produce identical signals, orders and ledgers.

## 6. Daily operation and monitoring

- **Diagnostics** (app: More → Diagnostics, API: `GET /v1/diagnostics`) reports each
  component as OK, DEGRADED, FAILED, NOT_CONFIGURED or UNKNOWN. The components are backend,
  database (latency, schema version), clock drift, market data (freshness, lag,
  provider state), reconciliation, strategy scheduler (active strategies, last evaluation,
  failures, stuck claims), push queue (depth, oldest item, failures), AI and push providers,
  backups (age), audit chain integrity, and recent errors.
- **History:** `GET /v1/diagnostics/events` lists the most recent health events.
- **Logs:** `docker compose logs -f backend`. Logs are structured JSON with correlation ids.
  Secrets are redacted.
- **Inbox:** every notification is kept in the in-app inbox, whether or not push delivery works.

## 7. Incidents

| Symptom | Meaning | Action |
|---|---|---|
| Market data DEGRADED/FAILED | Quotes are stale or the provider is failing. Autonomous strategies pause and new orders are rejected. | Check the provider test and your API quota. Switch to Replay if needed. Strategies stay paused until you resume them. |
| Reconciliation FAILED | The ledger and positions disagree for a portfolio. New orders for it are blocked. | Open the portfolio's reconciliation report. Export the ledger (`/v1/exports/ledger`) and inspect it. Do not resume strategies until it is OK. |
| Strategy Suspended | Repeated evaluation errors, or its consecutive-loss limit was reached. | Review the strategy in the app. Re-activation requires owner review. |
| Audit chain FAILED | Audit history was modified outside the application. | Treat as a security incident. Stop the backend, preserve the database, and restore from the last good backup (BACKUP_RESTORE.md). |
| Backup DEGRADED | No backup in the last 36 hours. | Check `docker compose logs backend` for `Scheduled backup failed` and the free space in the backups volume. |
| Push queue lagging | FCM is unreachable or rejecting requests. | The inbox is complete regardless. Check the FCM provider test. Unregistered tokens disable push for that device automatically. |
| Lost phone | | From a trusted device: `GET /v1/sessions`, then `POST /v1/sessions/revoke-others` (API), then change the password. |

**Emergency controls** (app: More → Emergency controls; API: `/v1/emergency/*`): **Pause all** stops every strategy
and blocks new orders. **Prevent new positions** allows only reductions. **Close all
simulated positions** requires recent authentication and typing
`CLOSE ALL SIMULATED POSITIONS`. Releasing a control also requires recent authentication.

## 8. Upgrades

```bash
# 1. Take a backup first: POST /v1/backups (recent authentication), or wait for the daily one.
git fetch && git checkout <new-tag>
(cd backend && ./gradlew bootJar)
docker compose up -d --build backend   # Flyway migrates on start; a failed migration keeps the service down
```

If the new version fails to start, check out the previous tag and restore the pre-upgrade
backup (BACKUP_RESTORE.md). Migrations are forward-only.

## 9. Secrets rotation

- `DATABASE_PASSWORD`: change it in PostgreSQL (`alter user ...`) and in `.env`, then restart.
- `JWT_SIGNING_KEY`: change it in `.env` and restart. Pending single-use action tokens become invalid.
- Provider credentials: replace them through `PUT /v1/providers/{id}/credential`; the old ciphertext is overwritten.
- `MASTER_ENCRYPTION_KEY`: Version 1 has no in-place re-encryption. To rotate it: take a backup,
  record every provider credential, deploy with a new key onto an empty database, then
  re-enter the credentials. You cannot restore the old backup under the new key.
  See the limitations in the release notes.

## 10. Uninstall

`docker compose down` keeps the data volumes. `docker compose down -v` deletes the database and
backups permanently. Copy the backups elsewhere first.
