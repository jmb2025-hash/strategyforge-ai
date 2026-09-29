package app.strategyforge.engine.operations

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineJson
import app.strategyforge.engine.common.Hashing
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.RecentAuth
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

data class BackupFileView(
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Instant,
)

data class BackupResult(
    val file: BackupFileView,
    val sha256: String,
    val schemaVersion: String,
    val tables: Int,
    val rows: Long,
)

data class BackupVerification(
    val name: String,
    val valid: Boolean,
    val schemaVersion: String?,
    val createdAt: Instant?,
    val tables: Int,
    val rows: Long,
    val error: String?,
)

data class RestoreResult(
    val restoredFrom: String,
    val safetyBackup: String,
    val tables: Int,
    val rows: Long,
)

/**
 * Whole-database backups of the on-device engine (FR-112, D-031). A backup is a gzip'd JSON copy
 * of every table with a SHA-256 over the table data, written to the app's private storage.
 * Backups hold no credentials: AI keys live in the Android Keystore, never in the database.
 * Restoring requires a recent device unlock and first writes a safety backup of the current state.
 */
class BackupService(
    private val db: Db,
    private val dir: () -> File?,
    private val audit: AuditService,
    private val auth: RecentAuth,
    private val clock: Clock,
) {
    fun list(): List<BackupFileView> =
        folder(create = false)
            ?.listFiles { f -> f.isFile && NAME.matches(f.name) }
            .orEmpty()
            .sortedByDescending { it.name }
            .map { view(it) }

    fun create(): BackupResult {
        val folder = folder(create = true) ?: throw Problems.unavailable("backups-unavailable", "Backup storage is not available on this device")
        val now = clock.instant()
        val tables = JsonObject(TABLES.associateWith { dump(it) })
        val data = tables.toString()
        val sha = Hashing.sha256Hex(data)
        val doc =
            JsonObject(
                linkedMapOf(
                    "format" to JsonPrimitive(FORMAT),
                    "schemaVersion" to JsonPrimitive(SCHEMA_VERSION),
                    "createdAt" to JsonPrimitive(now.toString()),
                    "sha256" to JsonPrimitive(sha),
                    "tables" to tables,
                ),
            )
        val name = "strategyforge-${STAMP.format(now)}-${UUID.randomUUID().toString().take(6)}.sfbk"
        val tmp = File(folder, "$name.tmp")
        GZIPOutputStream(tmp.outputStream()).use { it.write(doc.toString().toByteArray(Charsets.UTF_8)) }
        val file = File(folder, name)
        check(tmp.renameTo(file)) { "Could not finalise backup $name" }
        val rows =
            tables.values.sumOf {
                it.jsonObject["rows"]!!
                    .jsonArray.size
                    .toLong()
            }
        audit.record(AuditCategory.OPERATIONS, "BACKUP_CREATED", details = mapOf("name" to name, "sha256" to sha, "rows" to rows))
        return BackupResult(view(file), sha, SCHEMA_VERSION, TABLES.size, rows)
    }

    fun verify(name: String): BackupVerification =
        try {
            val doc = read(name)
            BackupVerification(name, true, doc.schemaVersion, doc.createdAt, doc.tables.size, doc.rows, null)
        } catch (e: BackupInvalid) {
            BackupVerification(name, false, null, null, 0, 0, e.message)
        }

    /** Replaces every table with the backup's contents. The engine should be restarted afterwards. */
    fun restore(name: String): RestoreResult {
        auth.require("restore a backup")
        val doc =
            try {
                read(name)
            } catch (e: BackupInvalid) {
                throw Problems.unprocessable("backup-invalid", e.message ?: "The backup is not valid")
            }
        val backupVersion = doc.schemaVersion.removePrefix("engine-").toIntOrNull()
        if (backupVersion == null || backupVersion > Db.SCHEMA_VERSION) {
            throw Problems.unprocessable("backup-schema-mismatch", "This backup was made by a newer app version (${doc.schemaVersion}); update the app first")
        }
        val safety = create().file.name
        db.tx {
            // Foreign keys are checked once at commit, after every table is back in place.
            db.sql("PRAGMA defer_foreign_keys = ON").update()
            // Append-only guards (ledger, audit) are lifted only inside this transaction.
            val triggers = db.sql("select name from sqlite_master where type = 'trigger'").list { it.str("name") }
            triggers.forEach { db.sql("drop trigger $it").update() }
            TABLES.asReversed().forEach { db.sql("delete from $it").update() }
            TABLES.forEach { load(it, doc.tables.getValue(it)) }
            Db.schemaStatements().filter { it.trimStart().startsWith("create trigger", ignoreCase = true) }.forEach { db.sql(it).update() }
            // Older backups load into the current tables (new columns stay empty); keep the current version.
            db.setSchemaVersion(Db.SCHEMA_VERSION)
        }
        audit.record(AuditCategory.OPERATIONS, "BACKUP_RESTORED", AuditOutcome.SUCCESS, details = mapOf("name" to name, "safetyBackup" to safety, "rows" to doc.rows))
        return RestoreResult(name, safety, doc.tables.size, doc.rows)
    }

    // ------------------------------------------------------------------ internals

    private class BackupInvalid(
        message: String,
    ) : Exception(message)

    private class Parsed(
        val schemaVersion: String,
        val createdAt: Instant,
        val tables: Map<String, JsonObject>,
        val rows: Long,
    )

    private fun folder(create: Boolean): File? = dir()?.also { if (create) it.mkdirs() }?.takeIf { it.isDirectory }

    private fun file(name: String): File {
        if (!NAME.matches(name)) throw Problems.badRequest("invalid-backup-name", "Invalid backup name")
        val f = folder(create = false)?.let { File(it, name) }
        if (f == null || !f.isFile) throw Problems.notFound("Backup", name)
        return f
    }

    private fun view(f: File) = BackupFileView(f.name, f.length(), Instant.ofEpochMilli(f.lastModified()))

    private fun read(name: String): Parsed {
        val f = file(name)
        val text =
            try {
                GZIPInputStream(f.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
            } catch (e: java.io.IOException) {
                throw BackupInvalid("The file is damaged or is not a StrategyForge backup")
            }
        val doc = runCatching { EngineJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: throw BackupInvalid("The backup is not readable")
        if (doc["format"]?.jsonPrimitive?.content != FORMAT) throw BackupInvalid("Not a StrategyForge backup")
        val tables = doc["tables"] as? JsonObject ?: throw BackupInvalid("The backup has no data")
        if (Hashing.sha256Hex(tables.toString()) != doc["sha256"]?.jsonPrimitive?.content) throw BackupInvalid("Checksum mismatch: the backup was modified or damaged")
        val missing = TABLES.filter { tables[it] !is JsonObject }
        if (missing.isNotEmpty()) throw BackupInvalid("The backup is missing tables: ${missing.joinToString()}")
        val parsed = TABLES.associateWith { tables.getValue(it).jsonObject }
        return Parsed(
            doc["schemaVersion"]?.jsonPrimitive?.content ?: "",
            runCatching { Instant.parse(doc["createdAt"]!!.jsonPrimitive.content) }.getOrElse { throw BackupInvalid("The backup has no creation time") },
            parsed,
            parsed.values.sumOf { it["rows"]!!.jsonArray.size.toLong() },
        )
    }

    private fun dump(table: String): JsonObject {
        val columns = tableColumns(table)
        val rows =
            db.sql("select ${columns.joinToString()} from $table").list { r ->
                JsonArray(
                    columns.indices.map { i ->
                        when (val v = r.valueAt(i)) {
                            null -> JsonNull
                            is Long -> JsonPrimitive(v)
                            else -> JsonPrimitive(v.toString())
                        }
                    },
                )
            }
        return JsonObject(mapOf("columns" to JsonArray(columns.map { JsonPrimitive(it) }), "rows" to JsonArray(rows)))
    }

    private fun tableColumns(table: String): List<String> = db.sql("pragma table_info($table)").list { it.string("name")!! }

    private fun load(
        table: String,
        data: JsonObject,
    ) {
        val columns = data["columns"]!!.jsonArray.map { it.jsonPrimitive.content }
        val known = tableColumns(table).toSet()
        val unknown = columns.filterNot { it in known }
        if (unknown.isNotEmpty()) throw Problems.unprocessable("backup-schema-mismatch", "Backup table $table has unknown columns: ${unknown.joinToString()}")
        if (columns.isEmpty()) return
        val sql = "insert into $table(${columns.joinToString()}) values (${columns.indices.joinToString { ":p$it" }})"
        data["rows"]!!.jsonArray.forEach { row ->
            val stmt = db.sql(sql)
            row.jsonArray.forEachIndexed { i, v -> stmt.param("p$i", value(v)) }
            stmt.update()
        }
    }

    private fun value(v: JsonElement): Any? =
        when {
            v is JsonNull -> null
            v is JsonPrimitive && v.isString -> v.content
            v is JsonPrimitive -> v.longOrNull ?: v.content
            else -> v.toString()
        }

    companion object {
        const val FORMAT = "strategyforge-backup"

        /** Written into every backup; restore accepts this or any older version (D-034). */
        val SCHEMA_VERSION: String get() = "engine-${Db.SCHEMA_VERSION}"

        private val NAME = Regex("^strategyforge-[0-9]{8}-[0-9]{6}-[a-z0-9]{1,8}\\.sfbk$")
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

        /** Every table in schema.sql, parents before children (restore order). */
        val TABLES: List<String> by lazy {
            Db.schemaStatements().mapNotNull {
                Regex("^create table (?:if not exists )?([a-z_]+)", RegexOption.IGNORE_CASE)
                    .find(it.trim())
                    ?.groupValues
                    ?.get(1)
                    ?.lowercase()
            }
        }
    }
}
