# Backup and restore

StrategyForge takes encrypted logical backups from inside the backend (decision D-007).
A backup captures the whole application schema from one consistent database snapshot.
Restore is an offline command. It refuses to run unless the ledger, positions, orders,
recommendations and audit head of the restored database match the backup exactly.

Requirements: FR-112, NFR-008, MS-21, RG-08. Evidence: `BackupRestoreIT`.

## What a backup contains

| Item | Detail |
|---|---|
| Data | Every table in the application schema, dumped with PostgreSQL `COPY ... (FORMAT csv, HEADER)` inside one `REPEATABLE READ` read-only transaction. Every table comes from the same snapshot, so orders, the ledger and the audit trail are consistent with each other. |
| Manifest | Format version, creation time, Flyway schema version, row count and SHA-256 for each table, and a **consistency fingerprint**. |
| Fingerprint | Net of the ledger (always 0), the ledger entry count, the CASH balance per portfolio, the quantity and cost of each position per portfolio and instrument, order counts by status, recommendation counts by status, and the audit event count, last id and head hash. |
| Encryption | AES-256-GCM in 1 MiB chunks. Each chunk is authenticated with its index and a final-chunk flag, so any tampering, truncation, reordering or wrong key is detected before data is used. The key is derived (HMAC-SHA256, purpose `backup-v1`) from `MASTER_ENCRYPTION_KEY`. |
| File | `strategyforge-YYYYMMDD-HHMMSS-xxxx.sfbk` in `SF_BACKUP_DIR` (Docker volume `backups`). It is written as `.partial` and moved into place atomically. |

Stored credentials (AI, market data, FCM) remain encrypted inside the backup with the same master key.

> **Keep `MASTER_ENCRYPTION_KEY` safe and separate from the backups.** A backup cannot be
> read or restored without it, and a backup plus the key discloses the stored credentials.
> Record the key in your password manager when you create `.env`.

## Schedule and retention

| Setting | Default | Meaning |
|---|---|---|
| `SF_BACKUP_SCHEDULED` | `true` | Run the daily backup. |
| `SF_BACKUP_CRON` | `0 17 3 * * *` | Spring cron (seconds first), server time (UTC in the container). |
| `SF_BACKUP_RETAIN` | `14` | Newest files kept after each scheduled backup. |
| `SF_BACKUP_DIR` | `/var/lib/strategyforge/backups` | Target directory. |

Diagnostics reports a `backup` component. It is **DEGRADED** when no backup exists or the
newest one is older than 36 hours. Every backup, verification and prune is audited
(`BACKUP_CREATED` with the file SHA-256, `BACKUP_VERIFIED`, `BACKUP_PRUNED`, `BACKUP_FAILED`).

Copy backups off the host regularly, for example with `docker compose cp` or a volume
backup to another disk. A backup stored only on the same machine does not protect against
disk loss.

## Taking and checking a backup on demand

In the app, More → Backups lists the backups on the host, creates one with **Back up now** (asks for your
password), and **Verify** decrypts a file on the host and checks every table hash. Restore is never offered in the app.

API (recent authentication required for creation):

```
POST /v1/backups                 -> 201 {file, sha256, schemaVersion, tables, rows, fingerprint}
GET  /v1/backups                 -> {items: [{name, sizeBytes, modifiedAt}]}
POST /v1/backups/{name}/verify   -> {valid, schemaVersion, createdAt, tables, rows, error}
```

Offline check of a file (decrypts it and checks every table hash; no database needed):

```bash
docker compose run --rm backend verify-backup /var/lib/strategyforge/backups/<file>.sfbk
```

## Restore procedure (drill)

A restore always goes into a **new, empty database**. The command refuses a database that
already contains tables. The procedure below keeps the old database, so it is fully reversible.

```bash
# 0. Take a final backup of the current state if the service still works.
#    (More → Backups → Back up now in the app, or POST /v1/backups; skip if unavailable.)

# 1. Stop the backend.
docker compose stop backend

# 2. Keep the current database aside and create an empty one.
docker compose exec db psql -U "$DATABASE_USER" -d postgres \
  -c "alter database strategyforge rename to strategyforge_before_restore" \
  -c "create database strategyforge"

# 3. Restore. The same .env (DATABASE_*, MASTER_ENCRYPTION_KEY) is used.
docker compose run --rm backend restore /var/lib/strategyforge/backups/<file>.sfbk
#    OK: restored N rows at schema 9; ledger, positions, orders, recommendations and audit head
#        reconcile with the manifest
#    Schema upgraded to 9. ...

# 4. Start the backend and check Diagnostics (audit-chain OK, reconciliation OK, backup).
docker compose up -d backend
```

What the restore command does, in order:

1. Decrypts the file to a private temporary file (mode 0600), authenticating every chunk.
2. Checks that the target database is empty.
3. Runs Flyway up to the **backup's** schema version.
4. In one transaction: drops foreign keys (the schema has FK cycles), clears rows seeded by
   migrations, loads every table with `COPY ... FROM STDIN (HEADER MATCH)`, checks each
   table's SHA-256 and row count, runs the deferred double-entry checks (every journal must
   balance), re-adds the foreign keys (which re-validates every row), and resets the sequences.
5. Recomputes the fingerprint and compares it with the manifest. Any difference fails the restore.
6. Runs Flyway up to the latest schema of this build. Older backups are upgraded here.

Exit codes are 0 for success, 1 for a verification or restore failure (the message names the
cause), and 2 for a usage or configuration error.

**Rollback** if anything fails or looks wrong:

```bash
docker compose stop backend
docker compose exec db psql -U "$DATABASE_USER" -d postgres \
  -c "drop database strategyforge" \
  -c "alter database strategyforge_before_restore rename to strategyforge"
docker compose up -d backend
```

Once the restored system has been checked, drop `strategyforge_before_restore`.

## Disaster recovery (new host)

1. Install Docker, clone the repository at the release tag, and restore `.env` from your
   password manager. It must contain the **same** `MASTER_ENCRYPTION_KEY`.
2. `docker compose up -d db`, then copy the backup into the `backups` volume:
   `docker compose run --rm -v "$PWD:/in:ro" --entrypoint cp backend /in/<file>.sfbk /var/lib/strategyforge/backups/`
3. Run steps 3 and 4 of the restore procedure. The database is new and empty, so step 2 is not needed.
4. Sign in on the phone. Sessions are restored with the database, so an existing app session
   stays valid if the server URL is unchanged. Re-register push in Settings if FCM was configured.

## Limitations

- Each table is loaded into memory during restore. That is appropriate for a single-owner
  dataset (hundreds of MB), not for multi-GB databases.
- A backup produced by a newer build (higher schema version) cannot be restored by an older
  build; the command says so and stops.
- `pg_dump`/`pg_restore` remain usable by the operator as an additional, unencrypted method.
  Protect their output yourself; the reconciliation checks above do not apply to it.
