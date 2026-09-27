package app.strategyforge.identity

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.security.Crypto
import app.strategyforge.common.security.SecretCipher
import app.strategyforge.common.web.Problems
import app.strategyforge.common.web.parseUuid
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class DeviceRegistration(
    @field:NotBlank @field:Size(max = 100) val name: String,
    val platform: String = "ANDROID",
    val pushToken: String? = null,
)

data class PushTokenUpdate(
    val pushToken: String?,
    val pushEnabled: Boolean,
)

data class DeviceView(
    val id: UUID,
    val name: String,
    val platform: String,
    val pushEnabled: Boolean,
    val pushTokenRegistered: Boolean,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val revokedAt: Instant?,
)

/** A push target decrypted for delivery only. */
data class PushTarget(
    val deviceId: UUID,
    val token: String,
)

@Service
class DeviceService(
    private val jdbc: JdbcClient,
    private val cipher: SecretCipher,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional
    fun register(req: DeviceRegistration): DeviceView {
        if (req.platform !in setOf("ANDROID", "OTHER")) throw Problems.badRequest("invalid-platform", "Unsupported platform")
        val id = UUID.randomUUID()
        val now = clock.instant()
        val enc = req.pushToken?.takeIf { it.isNotBlank() }?.let { cipher.encryptString(it, "device-push:$id") }
        jdbc
            .sql(
                """
                insert into devices(id, name, platform, push_token_enc, push_token_fingerprint, push_enabled, created_at, last_seen_at)
                values (:id, :n, :p, :enc, :fp, :pe, :now, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("n", req.name)
            .param("p", req.platform)
            .param("enc", enc)
            .param("fp", req.pushToken?.let { Crypto.fingerprint(it) })
            .param("pe", enc != null)
            .param("now", ts(now))
            .update()
        jdbc
            .sql("update sessions set device_id = :d where id = :s")
            .param("d", id)
            .param("s", CurrentOwner.get().sessionId)
            .update()
        audit.record(AuditCategory.SETTINGS, "DEVICE_REGISTERED", entityType = "Device", entityId = id, details = mapOf("name" to req.name, "push" to (enc != null)))
        return get(id)
    }

    @Transactional
    fun updatePush(
        id: UUID,
        req: PushTokenUpdate,
    ): DeviceView {
        get(id)
        val enc = req.pushToken?.takeIf { it.isNotBlank() }?.let { cipher.encryptString(it, "device-push:$id") }
        jdbc
            .sql("update devices set push_token_enc = :enc, push_token_fingerprint = :fp, push_enabled = :pe, last_seen_at = :now where id = :id")
            .param("enc", enc)
            .param("fp", req.pushToken?.let { Crypto.fingerprint(it) })
            .param("pe", req.pushEnabled && enc != null)
            .param("now", ts(clock.instant()))
            .param("id", id)
            .update()
        audit.record(AuditCategory.SETTINGS, "DEVICE_PUSH_UPDATED", entityType = "Device", entityId = id, details = mapOf("pushEnabled" to (req.pushEnabled && enc != null)))
        return get(id)
    }

    @Transactional
    fun revoke(id: UUID) {
        val n =
            jdbc
                .sql("update devices set revoked_at = :now, push_enabled = false, push_token_enc = null where id = :id and revoked_at is null")
                .param("now", ts(clock.instant()))
                .param("id", id)
                .update()
        if (n == 0) throw Problems.notFound("Device", id)
        audit.record(AuditCategory.SETTINGS, "DEVICE_REVOKED", entityType = "Device", entityId = id)
    }

    fun list(): List<DeviceView> = jdbc.sql("select * from devices order by created_at desc").query { rs, _ -> map(rs) }.list()

    fun get(id: UUID): DeviceView =
        jdbc
            .sql("select * from devices where id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElseThrow { Problems.notFound("Device", id) }

    fun pushTargets(): List<PushTarget> =
        jdbc
            .sql("select id, push_token_enc from devices where push_enabled and revoked_at is null and push_token_enc is not null")
            .query { rs, _ ->
                val id = rs.uuid("id")
                PushTarget(id, cipher.decryptString(rs.getBytes("push_token_enc"), "device-push:$id"))
            }.list()

    private fun map(rs: java.sql.ResultSet) =
        DeviceView(
            rs.uuid("id"),
            rs.getString("name"),
            rs.getString("platform"),
            rs.getBoolean("push_enabled"),
            rs.getBytes("push_token_enc") != null,
            rs.instant("created_at"),
            rs.instant("last_seen_at"),
            rs.instantOrNull("revoked_at"),
        )
}

@RestController
@RequestMapping("/v1/devices")
@Tag(name = "Identity")
class DevicesController(
    private val devices: DeviceService,
) {
    @GetMapping
    fun list() = devices.list()

    @PostMapping
    fun register(
        @Valid @RequestBody req: DeviceRegistration,
    ): ResponseEntity<DeviceView> = ResponseEntity.status(HttpStatus.CREATED).body(devices.register(req))

    @PutMapping("/{id}/push")
    fun push(
        @PathVariable id: String,
        @RequestBody req: PushTokenUpdate,
    ) = devices.updatePush(parseUuid(id), req)

    @DeleteMapping("/{id}")
    fun revoke(
        @PathVariable id: String,
    ): ResponseEntity<Unit> {
        devices.revoke(parseUuid(id))
        return ResponseEntity.noContent().build()
    }
}
