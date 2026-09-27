package app.strategyforge.identity

import app.strategyforge.common.audit.ActorProvider
import app.strategyforge.common.web.ApiException
import org.springframework.http.HttpStatus
import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** The authenticated owner for one request. */
data class OwnerPrincipal(
    val ownerId: UUID,
    val sessionId: UUID,
    val deviceId: UUID?,
    val lastAuthenticatedAt: Instant,
) : ActorProvider {
    override fun currentActor(): String = "OWNER:session:$sessionId"
}

class OwnerAuthentication(
    private val principal: OwnerPrincipal,
) : Authentication {
    override fun getName(): String = principal.ownerId.toString()

    override fun getAuthorities(): Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_OWNER"))

    override fun getCredentials(): Any? = null

    override fun getDetails(): Any? = null

    override fun getPrincipal(): OwnerPrincipal = principal

    override fun isAuthenticated(): Boolean = true

    override fun setAuthenticated(isAuthenticated: Boolean) = throw IllegalArgumentException("immutable")
}

object CurrentOwner {
    fun get(): OwnerPrincipal =
        (SecurityContextHolder.getContext().authentication?.principal as? OwnerPrincipal)
            ?: throw ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required")

    fun orNull(): OwnerPrincipal? = SecurityContextHolder.getContext().authentication?.principal as? OwnerPrincipal
}

/**
 * Step-up authentication for sensitive operations (section 15): autonomy, shorting,
 * risk increases, key changes, emergency close-all, backups, recovery-code regeneration.
 */
object RecentAuth {
    @Volatile
    var window: Duration = Duration.ofMinutes(5)

    fun require(
        now: Instant,
        operation: String,
    ): OwnerPrincipal {
        val p = CurrentOwner.get()
        if (p.lastAuthenticatedAt.plus(window).isBefore(now)) {
            throw ApiException(
                HttpStatus.FORBIDDEN,
                "recent-authentication-required",
                "Operation '$operation' requires authentication within the last ${window.toMinutes()} minutes",
                mapOf("reauthenticate" to "/v1/auth/reauthenticate", "operation" to operation),
            )
        }
        return p
    }
}
