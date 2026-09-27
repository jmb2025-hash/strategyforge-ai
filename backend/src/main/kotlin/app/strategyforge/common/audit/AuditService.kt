package app.strategyforge.common.audit

import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import app.strategyforge.common.json.JsonConfig
import app.strategyforge.common.security.SecretRedactor
import app.strategyforge.common.web.CorrelationIdFilter
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

enum class AuditCategory {
    AUTHENTICATION,
    SETTINGS,
    PROVIDER,
    RESEARCH,
    STRATEGY,
    BACKTEST,
    RISK,
    RECOMMENDATION,
    ORDER,
    EXECUTION,
    PORTFOLIO,
    LEDGER,
    MARKET_DATA,
    AUTONOMY,
    EMERGENCY,
    NOTIFICATION,
    EXPORT,
    OPERATIONS,
    SAFETY,
    FAILURE,
}

enum class AuditOutcome { SUCCESS, FAILURE, BLOCKED, DENIED }

data class AuditEvent(
    val id: Long,
    val eventId: UUID,
    val occurredAt: Instant,
    val actor: String,
    val category: String,
    val action: String,
    val outcome: String,
    val entityType: String?,
    val entityId: String?,
    val correlationId: String?,
    val details: Map<String, Any?>,
    val hash: String,
    val prevHash: String?,
)

/** Anything that can report the acting principal for audit purposes. */
fun interface ActorProvider {
    fun currentActor(): String?
}

/**
 * Append-only, hash-chained audit trail (FR-110). The database rejects UPDATE and
 * DELETE on audit_events; the chain makes tampering with history detectable.
 */
@Service
class AuditService(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    txManager: PlatformTransactionManager,
) {
    private val independentTx =
        TransactionTemplate(txManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    /** Records within the caller's transaction so the audit commits atomically with the change. */
    fun record(
        category: AuditCategory,
        action: String,
        outcome: AuditOutcome = AuditOutcome.SUCCESS,
        entityType: String? = null,
        entityId: Any? = null,
        details: Map<String, Any?> = emptyMap(),
        actor: String? = null,
    ): UUID {
        val eventId = UUID.randomUUID()
        val now = clock.instant()
        val who = actor ?: resolveActor()
        val safeDetails = SecretRedactor.redactMap(details)
        val detailsJson = mapper.writeValueAsString(safeDetails)
        val correlation = CorrelationIdFilter.current()
        jdbc.sql("select pg_advisory_xact_lock(7102026)").query().singleRow()
        val prev =
            jdbc
                .sql("select hash from audit_events order by id desc limit 1")
                .query(String::class.java)
                .optional()
                .orElse(null)
        val material = listOf(prev ?: "GENESIS", eventId, now.toEpochMilli(), who, category, action, outcome, entityType, entityId?.toString(), correlation, JsonConfig.canonical(mapper, mapper.valueToTree(safeDetails))).joinToString("|")
        val hash = sha256(material)
        jdbc
            .sql(
                """
                insert into audit_events(event_id, occurred_at, actor, category, action, outcome, entity_type, entity_id, correlation_id, details, prev_hash, hash)
                values (:eventId, :occurredAt, :actor, :category, :action, :outcome, :entityType, :entityId, :correlationId, cast(:details as jsonb), :prevHash, :hash)
                """.trimIndent(),
            ).param("eventId", eventId)
            .param("occurredAt", ts(now))
            .param("actor", who)
            .param("category", category.name)
            .param("action", action)
            .param("outcome", outcome.name)
            .param("entityType", entityType)
            .param("entityId", entityId?.toString())
            .param("correlationId", correlation)
            .param("details", detailsJson)
            .param("prevHash", prev)
            .param("hash", hash)
            .update()
        return eventId
    }

    /** Records in a separate transaction so failures are audited even when the caller rolls back. */
    fun recordIndependently(
        category: AuditCategory,
        action: String,
        outcome: AuditOutcome,
        entityType: String? = null,
        entityId: Any? = null,
        details: Map<String, Any?> = emptyMap(),
        actor: String? = null,
    ): UUID = independentTx.execute { record(category, action, outcome, entityType, entityId, details, actor) }!!

    fun list(
        limit: Int,
        before: Long?,
        category: String?,
    ): List<AuditEvent> =
        jdbc
            .sql(
                """
                select * from audit_events
                where (cast(:before as bigint) is null or id < :before)
                  and (cast(:category as text) is null or category = :category)
                order by id desc limit :limit
                """.trimIndent(),
            ).param("before", before)
            .param("category", category)
            .param("limit", limit)
            .query { rs, _ ->
                AuditEvent(
                    id = rs.getLong("id"),
                    eventId = rs.getObject("event_id", UUID::class.java),
                    occurredAt = rs.instant("occurred_at"),
                    actor = rs.getString("actor"),
                    category = rs.getString("category"),
                    action = rs.getString("action"),
                    outcome = rs.getString("outcome"),
                    entityType = rs.getString("entity_type"),
                    entityId = rs.getString("entity_id"),
                    correlationId = rs.getString("correlation_id"),
                    details = parseDetails(rs.getString("details")),
                    hash = rs.getString("hash"),
                    prevHash = rs.getString("prev_hash"),
                )
            }.list()

    /** Recomputes the hash chain; returns the first broken event id or null when intact. */
    fun verifyChain(): Long? {
        var prev: String? = null
        var broken: Long? = null
        jdbc.sql("select * from audit_events order by id asc").query { rs ->
            if (broken != null) return@query
            val material =
                listOf(
                    prev ?: "GENESIS",
                    rs.getObject("event_id", UUID::class.java),
                    rs.instant("occurred_at").toEpochMilli(),
                    rs.getString("actor"),
                    rs.getString("category"),
                    rs.getString("action"),
                    rs.getString("outcome"),
                    rs.getString("entity_type"),
                    rs.getString("entity_id"),
                    rs.getString("correlation_id"),
                    canonicalDetails(rs.getString("details")),
                ).joinToString("|")
            val expected = sha256(material)
            if (expected != rs.getString("hash") || rs.getString("prev_hash") != prev) broken = rs.getLong("id")
            prev = rs.getString("hash")
        }
        return broken
    }

    private fun canonicalDetails(json: String): String = JsonConfig.canonical(mapper, mapper.readTree(json))

    @Suppress("UNCHECKED_CAST")
    private fun parseDetails(json: String?): Map<String, Any?> = if (json == null) emptyMap() else mapper.readValue(json, Map::class.java) as Map<String, Any?>

    private fun resolveActor(): String {
        val auth = SecurityContextHolder.getContext().authentication
        val principal = auth?.principal
        if (principal is ActorProvider) principal.currentActor()?.let { return it }
        return "SYSTEM"
    }

    companion object {
        fun sha256(s: String): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)))
    }
}
