package app.strategyforge.operations.backup

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.common.security.Crypto
import app.strategyforge.common.security.SecretCipher
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.common.web.Problems
import app.strategyforge.identity.RecentAuth
import app.strategyforge.operations.ComponentHealth
import app.strategyforge.operations.DiagnosticsContributor
import app.strategyforge.operations.DiagnosticsService
import app.strategyforge.operations.HealthStatus
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.sql.DataSource
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.name

data class BackupFile(
    val name: String,
    val sizeBytes: Long,
    val modifiedAt: Instant,
)

data class BackupResult(
    val file: BackupFile,
    val sha256: String,
    val schemaVersion: String,
    val tables: Int,
    val rows: Long,
    val fingerprint: Map<String, String>,
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

/**
 * Encrypted logical backups (FR-112, D-007). The backup key is derived from
 * MASTER_ENCRYPTION_KEY, so restoring requires the same key that protects stored credentials.
 */
@Service
class BackupService(
    private val dataSource: DataSource,
    private val cipher: SecretCipher,
    private val audit: AuditService,
    private val diagnostics: DiagnosticsService,
    private val props: StrategyForgeProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val key: ByteArray get() = cipher.deriveKey(KEY_PURPOSE)

    fun directory(): Path = Path.of(props.backup.directory).toAbsolutePath()

    @Synchronized
    fun create(): BackupResult {
        val dir = Files.createDirectories(directory())
        val now = clock.instant()
        val name = "strategyforge-${STAMP.format(now)}-${Crypto.randomToken(4).lowercase().filter(Char::isLetterOrDigit).take(4)}$EXTENSION"
        val target = dir.resolve(name)
        val manifest =
            try {
                dataSource.connection.use { BackupArchive.create(it, target, key, now) }
            } catch (e: Exception) {
                audit.recordIndependently(AuditCategory.OPERATIONS, "BACKUP_FAILED", AuditOutcome.FAILURE, "Backup", name, mapOf("error" to e.javaClass.simpleName))
                diagnostics.recordEvent("backup", HealthStatus.FAILED, "Backup failed: ${e.javaClass.simpleName}")
                throw e
            }
        val sha = Crypto.sha256Hex(Files.readAllBytes(target))
        val result = BackupResult(describe(target), sha, manifest.schemaVersion, manifest.tables.size, manifest.tables.sumOf { it.rows }, manifest.fingerprint)
        audit.record(
            AuditCategory.OPERATIONS,
            "BACKUP_CREATED",
            AuditOutcome.SUCCESS,
            "Backup",
            name,
            mapOf("sha256" to sha, "schemaVersion" to manifest.schemaVersion, "tables" to result.tables, "rows" to result.rows, "sizeBytes" to result.file.sizeBytes),
        )
        return result
    }

    fun list(): List<BackupFile> {
        val dir = directory()
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { s -> s.filter { it.name.endsWith(EXTENSION) }.map(::describe).toList() }.sortedByDescending { it.name }
    }

    fun verify(name: String): BackupVerification {
        val file = resolve(name)
        val v =
            try {
                val m = BackupArchive.verify(file, key)
                BackupVerification(name, true, m.schemaVersion, m.createdAt, m.tables.size, m.tables.sumOf { it.rows }, null)
            } catch (e: BackupException) {
                BackupVerification(name, false, null, null, 0, 0, e.message)
            }
        audit.record(AuditCategory.OPERATIONS, "BACKUP_VERIFIED", if (v.valid) AuditOutcome.SUCCESS else AuditOutcome.FAILURE, "Backup", name, mapOf("valid" to v.valid, "error" to v.error))
        return v
    }

    @Scheduled(cron = "\${strategyforge.backup.cron:0 17 3 * * *}")
    fun scheduled() {
        if (!props.backup.scheduled) return
        CorrelationIdFilter.withCorrelation("backup") {
            runCatching { create() }
                .onSuccess { prune() }
                .onFailure { log.error("Scheduled backup failed: {}", it.javaClass.simpleName) }
        }
    }

    /** Keeps the newest [StrategyForgeProperties.Backup.retain] files. */
    fun prune(): Int {
        val old = list().drop(props.backup.retain.coerceAtLeast(1))
        old.forEach { Files.deleteIfExists(directory().resolve(it.name)) }
        if (old.isNotEmpty()) audit.record(AuditCategory.OPERATIONS, "BACKUP_PRUNED", AuditOutcome.SUCCESS, "Backup", null, mapOf("deleted" to old.map { it.name }))
        return old.size
    }

    private fun resolve(name: String): Path {
        if (!NAME.matches(name)) throw Problems.notFound("Backup", name)
        val file = directory().resolve(name)
        if (!Files.isRegularFile(file)) throw Problems.notFound("Backup", name)
        return file
    }

    private fun describe(p: Path) = BackupFile(p.name, p.fileSize(), p.getLastModifiedTime().toInstant())

    companion object {
        const val KEY_PURPOSE = "backup-v1"
        const val EXTENSION = ".sfbk"
        private val NAME = Regex("strategyforge-[0-9]{8}-[0-9]{6}-[a-z0-9]{1,8}\\.sfbk")
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
    }
}

@Component
class BackupDiagnostics(
    private val backups: BackupService,
    private val props: StrategyForgeProperties,
) : DiagnosticsContributor {
    override fun diagnose(now: Instant): List<ComponentHealth> {
        val latest = runCatching { backups.list().firstOrNull() }.getOrNull()
        val age = latest?.let { Duration.between(it.modifiedAt, now) }
        val status =
            when {
                latest == null -> if (props.backup.scheduled) HealthStatus.DEGRADED else HealthStatus.NOT_CONFIGURED
                age != null && age > MAX_AGE -> HealthStatus.DEGRADED
                else -> HealthStatus.OK
            }
        val detail = if (latest == null) "No backup found in the backup directory" else "Latest backup ${latest.name}"
        return listOf(ComponentHealth("backup", status, detail, mapOf("latest" to latest, "ageHours" to age?.toHours(), "scheduled" to props.backup.scheduled), now))
    }

    companion object {
        val MAX_AGE: Duration = Duration.ofHours(36)
    }
}

@RestController
@RequestMapping("/v1/backups")
@Tag(name = "Configuration")
class BackupController(
    private val backups: BackupService,
    private val clock: Clock,
) {
    @GetMapping
    fun list(): Map<String, Any> = mapOf("items" to backups.list())

    /** Creates an encrypted backup now. Requires recent authentication; restore is offline only. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(): BackupResult {
        RecentAuth.require(clock.instant(), "create-backup")
        return backups.create()
    }

    @PostMapping("/{name}/verify")
    fun verify(
        @PathVariable name: String,
    ): BackupVerification = backups.verify(name)
}
