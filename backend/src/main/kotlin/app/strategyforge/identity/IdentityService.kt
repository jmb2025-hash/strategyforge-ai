package app.strategyforge.identity

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.longOrNull
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.db.uuidOrNull
import app.strategyforge.common.security.Crypto
import app.strategyforge.common.security.SecretCipher
import app.strategyforge.common.security.SigningKey
import app.strategyforge.common.web.ApiException
import app.strategyforge.common.web.Problems
import app.strategyforge.settings.SettingsDefaults
import jakarta.annotation.PostConstruct
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class SessionIssued(
    val sessionId: UUID,
    val accessToken: String,
    val expiresAt: Instant,
    val lastAuthenticatedAt: Instant,
)

data class OwnerRecord(
    val id: UUID,
    val username: String,
    val passwordHash: String,
    val totpSecretEnc: ByteArray?,
    val totpPendingEnc: ByteArray?,
    val totpEnabled: Boolean,
    val totpLastStep: Long?,
    val failedLogins: Int,
    val lockedUntil: Instant?,
    val createdAt: Instant,
)

data class SessionInfo(
    val id: UUID,
    val deviceName: String,
    val deviceId: UUID?,
    val createdAt: Instant,
    val lastSeenAt: Instant,
    val lastAuthenticatedAt: Instant,
    val expiresAt: Instant,
    val revokedAt: Instant?,
    val current: Boolean,
)

/** Published for the notification inbox (security events are critical and cannot be disabled). */
data class SecurityEvent(
    val action: String,
    val detail: String,
)

@Service
class IdentityService(
    private val jdbc: JdbcClient,
    private val audit: AuditService,
    private val clock: Clock,
    private val cipher: SecretCipher,
    private val signing: SigningKey,
    private val props: StrategyForgeProperties,
    private val events: ApplicationEventPublisher,
    private val passwordEncoder: Argon2PasswordEncoder,
) {
    @PostConstruct
    fun configureRecentAuthWindow() {
        RecentAuth.window = props.auth.recentAuthWindow
    }

    fun isBootstrapped(): Boolean = jdbc.sql("select count(*) from owners").query(Int::class.java).single() > 0

    @Transactional
    fun bootstrap(
        username: String,
        password: String,
        bootstrapToken: String?,
        deviceName: String,
        timezone: String?,
    ): Pair<SessionIssued, List<String>> {
        if (props.bootstrapToken.isNotBlank() && (bootstrapToken == null || !Crypto.constantTimeEquals(bootstrapToken, props.bootstrapToken))) {
            audit.recordIndependently(AuditCategory.AUTHENTICATION, "BOOTSTRAP", AuditOutcome.DENIED, details = mapOf("reason" to "invalid bootstrap token"))
            throw Problems.forbidden("bootstrap-token-invalid", "A valid bootstrap token is required")
        }
        validateUsername(username)
        validatePassword(password)
        val now = clock.instant()
        val ownerId = UUID.randomUUID()
        // The singleton primary key guarantees a second owner cannot be inserted even under a race.
        val inserted =
            jdbc
                .sql(
                    """
                    insert into owners(singleton, id, username, password_hash, created_at, password_changed_at)
                    values (true, :id, :username, :hash, :now, :now) on conflict (singleton) do nothing
                    """.trimIndent(),
                ).param("id", ownerId)
                .param("username", username.trim())
                .param("hash", passwordEncoder.encode(password))
                .param("now", ts(now))
                .update()
        if (inserted == 0) {
            audit.recordIndependently(AuditCategory.AUTHENTICATION, "BOOTSTRAP", AuditOutcome.DENIED, details = mapOf("reason" to "owner already exists"))
            throw Problems.conflict("registration-disabled", "The owner account already exists; registration is disabled")
        }
        val zone = timezone?.takeIf { it.isNotBlank() } ?: props.defaultTimezone
        try {
            java.time.ZoneId.of(zone)
        } catch (e: java.time.DateTimeException) {
            throw Problems.badRequest("invalid-timezone", "Unknown timezone '$zone'")
        }
        insertDefaultSettings(zone, now)
        val codes = generateRecoveryCodes(1, now)
        val session = createSession(deviceName, null, now)
        audit.record(AuditCategory.AUTHENTICATION, "BOOTSTRAP", entityType = "Owner", entityId = ownerId, details = mapOf("username" to username.trim(), "sessionId" to session.sessionId))
        return session to codes
    }

    private fun insertDefaultSettings(
        zone: String,
        now: Instant,
    ) {
        jdbc
            .sql(
                """
                insert into owner_settings(singleton, timezone, display_currency, show_cad_equivalent, theme, notifications, privacy, portfolio_defaults, updated_at)
                values (true, :tz, 'USD', false, 'SYSTEM', cast(:n as jsonb), cast(:p as jsonb), cast(:d as jsonb), :now)
                on conflict do nothing
                """.trimIndent(),
            ).param("tz", zone)
            .param("n", SettingsDefaults.NOTIFICATIONS_JSON)
            .param("p", SettingsDefaults.PRIVACY_JSON)
            .param("d", SettingsDefaults.PORTFOLIO_DEFAULTS_JSON)
            .param("now", ts(now))
            .update()
    }

    fun owner(): OwnerRecord? =
        jdbc
            .sql("select * from owners")
            .query { rs, _ ->
                OwnerRecord(
                    rs.uuid("id"),
                    rs.getString("username"),
                    rs.getString("password_hash"),
                    rs.getBytes("totp_secret_enc"),
                    rs.getBytes("totp_pending_enc"),
                    rs.getBoolean("totp_enabled"),
                    rs.longOrNull("totp_last_step"),
                    rs.getInt("failed_logins"),
                    rs.instantOrNull("locked_until"),
                    rs.instant("created_at"),
                )
            }.optional()
            .orElse(null)

    private fun requireOwner(): OwnerRecord = owner() ?: throw Problems.conflict("not-bootstrapped", "The owner account has not been created yet")

    /**
     * Login with lockout after repeated failures. Failures are audited in an independent
     * transaction so they persist although the request fails.
     */
    fun login(
        username: String,
        password: String,
        totpCode: String?,
        deviceName: String,
        userAgent: String?,
    ): SessionIssued {
        val now = clock.instant()
        val owner = requireOwner()
        if (owner.lockedUntil != null && owner.lockedUntil.isAfter(now)) {
            audit.recordIndependently(AuditCategory.AUTHENTICATION, "LOGIN", AuditOutcome.DENIED, details = mapOf("reason" to "locked"))
            throw ApiException(HttpStatus.LOCKED, "account-locked", "Too many failed attempts. Try again after ${owner.lockedUntil}", mapOf("lockedUntil" to owner.lockedUntil.toString()))
        }
        val userOk = Crypto.constantTimeEquals(username.trim(), owner.username)
        val passOk = passwordEncoder.matches(password, owner.passwordHash)
        if (!userOk || !passOk) {
            registerFailure(owner, now, "invalid credentials")
            throw Problems.unauthorized("invalid-credentials", "Invalid username or password")
        }
        if (owner.totpEnabled) {
            if (totpCode.isNullOrBlank()) throw Problems.unauthorized("totp-required", "A TOTP code is required")
            if (!consumeTotp(owner, totpCode, now)) {
                registerFailure(owner, now, "invalid totp")
                throw Problems.unauthorized("invalid-totp", "Invalid TOTP code")
            }
        }
        return completeLogin(deviceName, userAgent, now)
    }

    @Transactional
    fun completeLogin(
        deviceName: String,
        userAgent: String?,
        now: Instant,
    ): SessionIssued {
        jdbc.sql("update owners set failed_logins = 0, locked_until = null").update()
        val s = createSession(deviceName, userAgent, now)
        audit.record(AuditCategory.AUTHENTICATION, "LOGIN", details = mapOf("sessionId" to s.sessionId, "device" to deviceName), actor = "OWNER:session:${s.sessionId}")
        return s
    }

    private fun registerFailure(
        owner: OwnerRecord,
        now: Instant,
        reason: String,
    ) {
        val failures = owner.failedLogins + 1
        val lock = if (failures >= props.auth.maxFailedLogins) now.plus(props.auth.lockoutDuration) else null
        jdbc
            .sql("update owners set failed_logins = :f, locked_until = :l")
            .param("f", if (lock != null) 0 else failures)
            .param("l", ts(lock))
            .update()
        audit.recordIndependently(AuditCategory.AUTHENTICATION, "LOGIN", AuditOutcome.FAILURE, details = mapOf("reason" to reason, "failures" to failures, "locked" to (lock != null)))
        if (lock != null) events.publishEvent(SecurityEvent("ACCOUNT_LOCKED", "Login locked until $lock after repeated failures"))
    }

    private fun consumeTotp(
        owner: OwnerRecord,
        code: String,
        now: Instant,
    ): Boolean {
        val secret = cipher.decryptString(owner.totpSecretEnc ?: return false, "owner-totp:${owner.id}")
        val step = Totp.verify(secret, code, now) ?: return false
        if (owner.totpLastStep != null && step <= owner.totpLastStep) return false // replayed code
        jdbc.sql("update owners set totp_last_step = :s").param("s", step).update()
        return true
    }

    @Transactional
    fun createSession(
        deviceName: String,
        userAgent: String?,
        now: Instant,
    ): SessionIssued {
        val token = Crypto.randomToken(32)
        val id = UUID.randomUUID()
        val expires = now.plus(props.auth.sessionLifetime)
        jdbc
            .sql(
                """
                insert into sessions(id, token_hash, device_name, user_agent, created_at, last_seen_at, last_authenticated_at, expires_at)
                values (:id, :hash, :device, :ua, :now, :now, :now, :expires)
                """.trimIndent(),
            ).param("id", id)
            .param("hash", Crypto.sha256Hex(token))
            .param("device", deviceName.take(100))
            .param("ua", userAgent?.take(300))
            .param("now", ts(now))
            .param("expires", ts(expires))
            .update()
        return SessionIssued(id, token, expires, now)
    }

    /** Resolves a bearer token to a principal; null for unknown, expired or revoked sessions. */
    fun authenticate(token: String): OwnerPrincipal? {
        val now = clock.instant()
        val row =
            jdbc
                .sql(
                    """
                    select s.id, s.device_id, s.last_authenticated_at, s.last_seen_at, o.id as owner_id
                    from sessions s cross join owners o
                    where s.token_hash = :hash and s.revoked_at is null and s.expires_at > :now
                    """.trimIndent(),
                ).param("hash", Crypto.sha256Hex(token))
                .param("now", ts(now))
                .query { rs, _ ->
                    Triple(
                        OwnerPrincipal(rs.uuid("owner_id"), rs.uuid("id"), rs.uuidOrNull("device_id"), rs.instant("last_authenticated_at")),
                        rs.instant("last_seen_at"),
                        Unit,
                    )
                }.optional()
                .orElse(null) ?: return null
        if (row.second.isBefore(now.minusSeconds(60))) {
            jdbc
                .sql("update sessions set last_seen_at = :now where id = :id")
                .param("now", ts(now))
                .param("id", row.first.sessionId)
                .update()
        }
        return row.first
    }

    @Transactional
    fun reauthenticate(
        password: String,
        totpCode: String?,
    ): Instant {
        val p = CurrentOwner.get()
        val now = clock.instant()
        val owner = requireOwner()
        val ok = passwordEncoder.matches(password, owner.passwordHash) && (!owner.totpEnabled || (!totpCode.isNullOrBlank() && consumeTotp(owner, totpCode, now)))
        if (!ok) {
            audit.recordIndependently(AuditCategory.AUTHENTICATION, "REAUTHENTICATE", AuditOutcome.FAILURE)
            throw Problems.unauthorized("invalid-credentials", "Re-authentication failed")
        }
        jdbc
            .sql("update sessions set last_authenticated_at = :now where id = :id")
            .param("now", ts(now))
            .param("id", p.sessionId)
            .update()
        audit.record(AuditCategory.AUTHENTICATION, "REAUTHENTICATE")
        return now
    }

    @Transactional
    fun logout() {
        val p = CurrentOwner.get()
        revoke(p.sessionId, "LOGOUT")
    }

    @Transactional
    fun revoke(
        sessionId: UUID,
        reason: String,
    ) {
        val n =
            jdbc
                .sql("update sessions set revoked_at = :now, revoke_reason = :r where id = :id and revoked_at is null")
                .param("now", ts(clock.instant()))
                .param("r", reason)
                .param("id", sessionId)
                .update()
        if (n == 0) throw Problems.notFound("Active session", sessionId)
        audit.record(AuditCategory.AUTHENTICATION, "SESSION_REVOKED", entityType = "Session", entityId = sessionId, details = mapOf("reason" to reason))
    }

    @Transactional
    fun revokeOthers(): Int {
        val p = CurrentOwner.get()
        val n =
            jdbc
                .sql("update sessions set revoked_at = :now, revoke_reason = 'REVOKE_OTHERS' where id <> :id and revoked_at is null")
                .param("now", ts(clock.instant()))
                .param("id", p.sessionId)
                .update()
        audit.record(AuditCategory.AUTHENTICATION, "SESSIONS_REVOKED", details = mapOf("count" to n))
        return n
    }

    fun sessions(): List<SessionInfo> {
        val current = CurrentOwner.orNull()?.sessionId
        return jdbc
            .sql("select * from sessions where expires_at > :now order by created_at desc limit 100")
            .param("now", ts(clock.instant()))
            .query { rs, _ ->
                val id = rs.uuid("id")
                SessionInfo(
                    id,
                    rs.getString("device_name"),
                    rs.uuidOrNull("device_id"),
                    rs.instant("created_at"),
                    rs.instant("last_seen_at"),
                    rs.instant("last_authenticated_at"),
                    rs.instant("expires_at"),
                    rs.instantOrNull("revoked_at"),
                    id == current,
                )
            }.list()
    }

    /** Offline recovery (FR-002): consumes one recovery code, resets the password, revokes all sessions and disables TOTP. */
    @Transactional(noRollbackFor = [ApiException::class])
    fun recover(
        username: String,
        recoveryCode: String,
        newPassword: String,
        deviceName: String,
    ): Pair<SessionIssued, Int> {
        val now = clock.instant()
        val owner = requireOwner()
        if (owner.lockedUntil != null && owner.lockedUntil.isAfter(now)) {
            throw ApiException(HttpStatus.LOCKED, "account-locked", "Too many failed attempts. Try again after ${owner.lockedUntil}")
        }
        validatePassword(newPassword)
        val hash = signing.hmacHex("recovery:" + normalizeCode(recoveryCode))
        val usernameOk = Crypto.constantTimeEquals(username.trim(), owner.username)
        val used =
            if (!usernameOk) {
                0
            } else {
                jdbc
                    .sql("update recovery_codes set used_at = :now where code_hash = :h and used_at is null")
                    .param("now", ts(now))
                    .param("h", hash)
                    .update()
            }
        if (used == 0) {
            registerFailure(owner, now, "invalid recovery attempt")
            throw Problems.unauthorized("invalid-recovery", "Invalid username or recovery code")
        }
        jdbc
            .sql(
                """
                update owners set password_hash = :h, password_changed_at = :now, totp_enabled = false, totp_secret_enc = null,
                  totp_pending_enc = null, failed_logins = 0, locked_until = null, version = version + 1
                """.trimIndent(),
            ).param("h", passwordEncoder.encode(newPassword))
            .param("now", ts(now))
            .update()
        jdbc.sql("update sessions set revoked_at = :now, revoke_reason = 'RECOVERY' where revoked_at is null").param("now", ts(now)).update()
        val session = createSession(deviceName, null, now)
        val remaining = jdbc.sql("select count(*) from recovery_codes where used_at is null").query(Int::class.java).single()
        audit.record(AuditCategory.AUTHENTICATION, "RECOVERY_CODE_USED", details = mapOf("remainingCodes" to remaining), actor = "OWNER:session:${session.sessionId}")
        events.publishEvent(SecurityEvent("ACCOUNT_RECOVERED", "Account recovered with a recovery code; all sessions were revoked and TOTP disabled"))
        return session to remaining
    }

    @Transactional
    fun regenerateRecoveryCodes(): List<String> {
        RecentAuth.require(clock.instant(), "regenerate-recovery-codes")
        val now = clock.instant()
        jdbc.sql("update recovery_codes set used_at = :now where used_at is null").param("now", ts(now)).update()
        val gen = jdbc.sql("select coalesce(max(generation), 0) from recovery_codes").query(Int::class.java).single() + 1
        val codes = generateRecoveryCodes(gen, now)
        audit.record(AuditCategory.AUTHENTICATION, "RECOVERY_CODES_REGENERATED", details = mapOf("generation" to gen))
        events.publishEvent(SecurityEvent("RECOVERY_CODES_REGENERATED", "Recovery codes were regenerated"))
        return codes
    }

    private fun generateRecoveryCodes(
        generation: Int,
        now: Instant,
    ): List<String> =
        (1..RECOVERY_CODE_COUNT).map {
            val raw = Totp.base32(Crypto.randomBytes(8)).take(12)
            val code = raw.chunked(4).joinToString("-")
            jdbc
                .sql("insert into recovery_codes(id, code_hash, generation, created_at) values (:id, :h, :g, :now)")
                .param("id", UUID.randomUUID())
                .param("h", signing.hmacHex("recovery:" + normalizeCode(code)))
                .param("g", generation)
                .param("now", ts(now))
                .update()
            code
        }

    @Transactional
    fun changePassword(
        current: String,
        newPassword: String,
    ) {
        RecentAuth.require(clock.instant(), "change-password")
        val owner = requireOwner()
        if (!passwordEncoder.matches(current, owner.passwordHash)) throw Problems.unauthorized("invalid-credentials", "Current password is incorrect")
        validatePassword(newPassword)
        val now = clock.instant()
        jdbc
            .sql("update owners set password_hash = :h, password_changed_at = :now, version = version + 1")
            .param("h", passwordEncoder.encode(newPassword))
            .param("now", ts(now))
            .update()
        jdbc
            .sql("update sessions set revoked_at = :now, revoke_reason = 'PASSWORD_CHANGED' where id <> :id and revoked_at is null")
            .param("now", ts(now))
            .param("id", CurrentOwner.get().sessionId)
            .update()
        audit.record(AuditCategory.AUTHENTICATION, "PASSWORD_CHANGED")
        events.publishEvent(SecurityEvent("PASSWORD_CHANGED", "The owner password was changed; other sessions were revoked"))
    }

    @Transactional
    fun beginTotpSetup(): Pair<String, String> {
        RecentAuth.require(clock.instant(), "totp-setup")
        val owner = requireOwner()
        if (owner.totpEnabled) throw Problems.conflict("totp-already-enabled", "TOTP is already enabled")
        val secret = Totp.newSecret()
        jdbc.sql("update owners set totp_pending_enc = :s").param("s", cipher.encryptString(secret, "owner-totp:${owner.id}")).update()
        audit.record(AuditCategory.AUTHENTICATION, "TOTP_SETUP_STARTED")
        return secret to Totp.uri(secret, owner.username)
    }

    @Transactional
    fun confirmTotp(code: String) {
        val owner = requireOwner()
        val pending = owner.totpPendingEnc ?: throw Problems.conflict("totp-setup-not-started", "Start TOTP setup first")
        val secret = cipher.decryptString(pending, "owner-totp:${owner.id}")
        val step = Totp.verify(secret, code, clock.instant()) ?: throw Problems.badRequest("invalid-totp", "Invalid TOTP code")
        jdbc
            .sql("update owners set totp_secret_enc = totp_pending_enc, totp_pending_enc = null, totp_enabled = true, totp_last_step = :s, version = version + 1")
            .param("s", step)
            .update()
        audit.record(AuditCategory.AUTHENTICATION, "TOTP_ENABLED")
        events.publishEvent(SecurityEvent("TOTP_ENABLED", "Two-factor authentication (TOTP) was enabled"))
    }

    @Transactional
    fun disableTotp(code: String) {
        RecentAuth.require(clock.instant(), "totp-disable")
        val owner = requireOwner()
        if (!owner.totpEnabled) throw Problems.conflict("totp-not-enabled", "TOTP is not enabled")
        if (!consumeTotp(owner, code, clock.instant())) throw Problems.badRequest("invalid-totp", "Invalid TOTP code")
        jdbc.sql("update owners set totp_enabled = false, totp_secret_enc = null, version = version + 1").update()
        audit.record(AuditCategory.AUTHENTICATION, "TOTP_DISABLED")
        events.publishEvent(SecurityEvent("TOTP_DISABLED", "Two-factor authentication (TOTP) was disabled"))
    }

    private fun normalizeCode(code: String): String = code.uppercase().filter { it.isLetterOrDigit() }

    private fun validateUsername(u: String) {
        if (!Regex("^[A-Za-z0-9._@-]{3,64}$").matches(u.trim())) throw Problems.badRequest("invalid-username", "Username must be 3-64 characters of letters, digits and . _ @ -")
    }

    private fun validatePassword(p: String) {
        if (p.length < MIN_PASSWORD || p.length > MAX_PASSWORD) {
            throw Problems.badRequest("weak-password", "Password must be $MIN_PASSWORD-$MAX_PASSWORD characters")
        }
        if (p.isBlank() || p.toSet().size < 5) throw Problems.badRequest("weak-password", "Password is too simple")
    }

    companion object {
        const val RECOVERY_CODE_COUNT = 10
        const val MIN_PASSWORD = 12
        const val MAX_PASSWORD = 256
    }
}
