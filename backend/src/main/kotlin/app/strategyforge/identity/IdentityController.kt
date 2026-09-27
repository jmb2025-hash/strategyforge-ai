package app.strategyforge.identity

import app.strategyforge.common.web.parseUuid
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class BootstrapStatus(
    val bootstrapped: Boolean,
    val bootstrapTokenRequired: Boolean,
)

data class BootstrapRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
    val bootstrapToken: String? = null,
    @field:NotBlank @field:Size(max = 100) val deviceName: String,
    val timezone: String? = null,
)

data class SessionResponse(
    val sessionId: UUID,
    val accessToken: String,
    val expiresAt: Instant,
    val lastAuthenticatedAt: Instant,
)

data class BootstrapResponse(
    val session: SessionResponse,
    val recoveryCodes: List<String>,
    val notice: String,
)

data class LoginRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val password: String,
    val totpCode: String? = null,
    @field:NotBlank @field:Size(max = 100) val deviceName: String,
)

data class ReauthRequest(
    @field:NotBlank val password: String,
    val totpCode: String? = null,
)

data class ReauthResponse(
    val lastAuthenticatedAt: Instant,
    val validUntil: Instant,
)

data class RecoverRequest(
    @field:NotBlank val username: String,
    @field:NotBlank val recoveryCode: String,
    @field:NotBlank val newPassword: String,
    @field:NotBlank @field:Size(max = 100) val deviceName: String,
)

data class RecoverResponse(
    val session: SessionResponse,
    val remainingRecoveryCodes: Int,
)

data class RecoveryCodesResponse(
    val recoveryCodes: List<String>,
    val notice: String,
)

data class PasswordChangeRequest(
    @field:NotBlank val currentPassword: String,
    @field:NotBlank val newPassword: String,
)

data class TotpSetupResponse(
    val secret: String,
    val otpauthUri: String,
)

data class TotpCodeRequest(
    @field:NotBlank val code: String,
)

data class MeResponse(
    val ownerId: UUID,
    val username: String,
    val totpEnabled: Boolean,
    val sessionId: UUID,
    val lastAuthenticatedAt: Instant,
    val recentAuthValidUntil: Instant,
    val remainingRecoveryCodes: Int,
)

data class CountResponse(
    val count: Int,
)

private fun SessionIssued.toResponse() = SessionResponse(sessionId, accessToken, expiresAt, lastAuthenticatedAt)

private const val RECOVERY_NOTICE = "Store these single-use recovery codes offline. They are shown only once."

@RestController
@RequestMapping("/v1/bootstrap")
@Tag(name = "Identity")
class BootstrapController(
    private val identity: IdentityService,
    private val props: app.strategyforge.common.config.StrategyForgeProperties,
) {
    @GetMapping
    fun status() = BootstrapStatus(identity.isBootstrapped(), props.bootstrapToken.isNotBlank())

    /** First-run owner creation (FR-001). A second call is rejected with 409 registration-disabled. */
    @PostMapping
    fun bootstrap(
        @Valid @RequestBody req: BootstrapRequest,
    ): ResponseEntity<BootstrapResponse> {
        val (session, codes) = identity.bootstrap(req.username, req.password, req.bootstrapToken, req.deviceName, req.timezone)
        return ResponseEntity.status(HttpStatus.CREATED).body(BootstrapResponse(session.toResponse(), codes, RECOVERY_NOTICE))
    }
}

@RestController
@RequestMapping("/v1/auth")
@Tag(name = "Identity")
class AuthController(
    private val identity: IdentityService,
    private val clock: Clock,
    private val jdbc: org.springframework.jdbc.core.simple.JdbcClient,
) {
    @PostMapping("/login")
    fun login(
        @Valid @RequestBody req: LoginRequest,
        http: HttpServletRequest,
    ): SessionResponse = identity.login(req.username, req.password, req.totpCode, req.deviceName, http.getHeader("User-Agent")).toResponse()

    @PostMapping("/logout")
    fun logout(): ResponseEntity<Unit> {
        identity.logout()
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/reauthenticate")
    fun reauthenticate(
        @Valid @RequestBody req: ReauthRequest,
    ): ReauthResponse {
        val at = identity.reauthenticate(req.password, req.totpCode)
        return ReauthResponse(at, at.plus(RecentAuth.window))
    }

    @PostMapping("/recover")
    fun recover(
        @Valid @RequestBody req: RecoverRequest,
    ): RecoverResponse {
        val (session, remaining) = identity.recover(req.username, req.recoveryCode, req.newPassword, req.deviceName)
        return RecoverResponse(session.toResponse(), remaining)
    }

    @PostMapping("/recovery-codes/regenerate")
    fun regenerate() = RecoveryCodesResponse(identity.regenerateRecoveryCodes(), RECOVERY_NOTICE)

    @PostMapping("/password")
    fun changePassword(
        @Valid @RequestBody req: PasswordChangeRequest,
    ): ResponseEntity<Unit> {
        identity.changePassword(req.currentPassword, req.newPassword)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/totp/setup")
    fun totpSetup(): TotpSetupResponse = identity.beginTotpSetup().let { TotpSetupResponse(it.first, it.second) }

    @PostMapping("/totp/confirm")
    fun totpConfirm(
        @Valid @RequestBody req: TotpCodeRequest,
    ): ResponseEntity<Unit> {
        identity.confirmTotp(req.code)
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/totp/disable")
    fun totpDisable(
        @Valid @RequestBody req: TotpCodeRequest,
    ): ResponseEntity<Unit> {
        identity.disableTotp(req.code)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/me")
    fun me(): MeResponse {
        val p = CurrentOwner.get()
        val o = identity.owner()!!
        val remaining = jdbc.sql("select count(*) from recovery_codes where used_at is null").query(Int::class.java).single()
        return MeResponse(o.id, o.username, o.totpEnabled, p.sessionId, p.lastAuthenticatedAt, p.lastAuthenticatedAt.plus(RecentAuth.window), remaining)
    }

    @Suppress("unused")
    private fun now() = clock.instant()
}

@RestController
@RequestMapping("/v1/sessions")
@Tag(name = "Identity")
class SessionsController(
    private val identity: IdentityService,
) {
    @GetMapping
    fun list(): List<SessionInfo> = identity.sessions()

    /** Session revocation (FR-002). */
    @DeleteMapping("/{id}")
    fun revoke(
        @PathVariable id: String,
    ): ResponseEntity<Unit> {
        identity.revoke(parseUuid(id), "OWNER_REVOKED")
        return ResponseEntity.noContent().build()
    }

    @PostMapping("/revoke-others")
    fun revokeOthers() = CountResponse(identity.revokeOthers())
}
