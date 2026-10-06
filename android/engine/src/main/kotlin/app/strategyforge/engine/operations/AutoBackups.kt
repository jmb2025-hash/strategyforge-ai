package app.strategyforge.engine.operations

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.str
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** The daily backup's settings and its last outcome, for the Backups screen (D-069). */
data class AutoBackupStatus(
    val enabled: Boolean,
    /** True when the phone can copy backups out of the app (to Downloads); false in tests and on the server. */
    val exportAvailable: Boolean,
    val lastAt: Instant?,
    val lastName: String?,
    /** Where the copy was saved, for example "Download/StrategyForge/strategyforge-....sfbk". */
    val lastLocation: String?,
    val lastError: String?,
    val nextDueAt: Instant?,
)

/**
 * Daily automatic backup (D-069). Once a day, when there is something to keep, the engine writes a
 * backup and hands it to [export], which on the phone copies it to Downloads/StrategyForge so it
 * survives the app being uninstalled. Only the newest three backups are kept (the last three days):
 * in the app that counts every backup, manual ones included; in Downloads, the daily copies.
 * Failures are recorded and retried at the next check; they never stop trading.
 */
class AutoBackups(
    private val db: Db,
    private val backups: BackupService,
    private val audit: AuditService,
    private val clock: Clock,
    /** Copies a backup file out of the app and returns where it was saved; null when not available. */
    private val export: ((File) -> String)?,
) {
    private val log = EngineLog.of(javaClass)

    var enabled: Boolean
        get() = setting(ENABLED_KEY)?.toBooleanStrictOrNull() ?: true
        set(v) = put(ENABLED_KEY, v.toString())

    fun status(): AutoBackupStatus {
        val last = setting(LAST_AT_KEY)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return AutoBackupStatus(
            enabled,
            export != null,
            last,
            setting(LAST_NAME_KEY),
            setting(LAST_LOCATION_KEY)?.takeIf { it.isNotBlank() },
            setting(LAST_ERROR_KEY)?.takeIf { it.isNotBlank() },
            if (!enabled) null else last?.plus(EVERY) ?: clock.instant(),
        )
    }

    /** Runs the daily backup if it is due; called by the scheduler. */
    fun runIfDue() {
        if (!enabled || !hasData()) return
        val last = setting(LAST_ATTEMPT_KEY)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val lastOk = setting(LAST_AT_KEY)?.let { runCatching { Instant.parse(it) }.getOrNull() }
        val now = clock.instant()
        if (lastOk != null && now.isBefore(lastOk.plus(EVERY))) return
        if (last != null && now.isBefore(last.plus(RETRY))) return
        run()
    }

    /** Backs up now and copies the file out; returns the status afterwards. */
    fun run(): AutoBackupStatus {
        val now = clock.instant()
        put(LAST_ATTEMPT_KEY, now.toString())
        try {
            val b = backups.create(TAG)
            val location = export?.let { it(backups.fileOf(b.file.name)) }
            // Every backup kind counts towards the three kept in the app (the owner asked for three in total).
            backups.prune("", KEEP)
            put(LAST_AT_KEY, now.toString())
            put(LAST_NAME_KEY, b.file.name)
            put(LAST_LOCATION_KEY, location ?: "")
            put(LAST_ERROR_KEY, "")
            audit.record(AuditCategory.OPERATIONS, "AUTO_BACKUP_CREATED", details = mapOf("name" to b.file.name, "location" to location))
        } catch (e: Exception) {
            log.error("Daily backup failed", e)
            put(LAST_ERROR_KEY, (e.message ?: e.javaClass.simpleName).take(300))
            audit.record(AuditCategory.OPERATIONS, "AUTO_BACKUP_FAILED", AuditOutcome.FAILURE, details = mapOf("error" to e.message))
        }
        return status()
    }

    /** A fresh install has nothing worth keeping, and a backup of it would only crowd out real ones. */
    private fun hasData(): Boolean = db.sql("select count(*) from portfolios").long() > 0 || db.sql("select count(*) from strategies").long() > 0

    private fun setting(key: String): String? =
        db
            .sql("select value from settings where key = :k")
            .param("k", key)
            .firstOrNull { it.str("value") }

    private fun put(
        key: String,
        value: String,
    ) {
        db
            .sql("insert or replace into settings(key, value) values (:k, :v)")
            .param("k", key)
            .param("v", value)
            .update()
    }

    companion object {
        const val TAG = "auto"
        const val KEEP = 3
        val EVERY: Duration = Duration.ofHours(24)
        val RETRY: Duration = Duration.ofHours(1)
        private const val ENABLED_KEY = "auto_backup_enabled"
        private const val LAST_AT_KEY = "auto_backup_last_at"
        private const val LAST_ATTEMPT_KEY = "auto_backup_last_attempt"
        private const val LAST_NAME_KEY = "auto_backup_last_name"
        private const val LAST_LOCATION_KEY = "auto_backup_last_location"
        private const val LAST_ERROR_KEY = "auto_backup_last_error"
    }
}
