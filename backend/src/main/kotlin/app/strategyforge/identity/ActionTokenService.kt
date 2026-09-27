package app.strategyforge.identity

import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.common.db.ts
import app.strategyforge.common.security.Crypto
import app.strategyforge.common.security.SigningKey
import app.strategyforge.common.web.Problems
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class ActionToken(
    val token: String,
    val expiresAt: Instant,
)

/**
 * Single-use server-side action tokens (section 14). A token is issued only to an
 * authenticated session viewing the entity's detail screen and is bound to purpose,
 * entity and session. Notification payloads never carry tokens, so a notification
 * cannot execute a trade.
 */
@Service
class ActionTokenService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val signing: SigningKey,
    private val props: StrategyForgeProperties,
) {
    @Transactional
    fun issue(
        purpose: String,
        entityId: String,
    ): ActionToken {
        val p = CurrentOwner.get()
        val now = clock.instant()
        val token = Crypto.randomToken(24)
        val expires = now.plus(props.auth.actionTokenLifetime)
        jdbc
            .sql(
                """
                insert into action_tokens(id, token_hash, purpose, entity_id, session_id, created_at, expires_at)
                values (:id, :h, :p, :e, :s, :now, :exp)
                """.trimIndent(),
            ).param("id", UUID.randomUUID())
            .param("h", signing.hmacHex("action:$token"))
            .param("p", purpose)
            .param("e", entityId)
            .param("s", p.sessionId)
            .param("now", ts(now))
            .param("exp", ts(expires))
            .update()
        return ActionToken(token, expires)
    }

    /** Consumes a token inside the caller's transaction; throws if invalid, expired, reused or mis-bound. */
    @Transactional
    fun consume(
        token: String?,
        purpose: String,
        entityId: String,
    ) {
        if (token.isNullOrBlank()) throw Problems.badRequest("action-token-required", "A single-use action token from the detail screen is required")
        val p = CurrentOwner.get()
        val n =
            jdbc
                .sql(
                    """
                    update action_tokens set used_at = :now
                    where token_hash = :h and purpose = :p and entity_id = :e and session_id = :s and used_at is null and expires_at > :now
                    """.trimIndent(),
                ).param("now", ts(clock.instant()))
                .param("h", signing.hmacHex("action:$token"))
                .param("p", purpose)
                .param("e", entityId)
                .param("s", p.sessionId)
                .update()
        if (n != 1) throw Problems.forbidden("action-token-invalid", "The action token is invalid, expired, already used or bound to another session")
    }
}
