package app.strategyforge.engine.common

import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.time.Clock
import java.time.Instant
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
    val details: JsonObject,
    val hash: String,
    val prevHash: String?,
)

/**
 * Append-only, hash-chained audit trail (FR-110). The database rejects UPDATE and DELETE on
 * audit_events; the chain makes tampering with history detectable. Records join the caller's
 * transaction so the audit commits atomically with the change it describes.
 */
class AuditService(
    private val db: Db,
    private val clock: Clock,
) {
    fun record(
        category: AuditCategory,
        action: String,
        outcome: AuditOutcome = AuditOutcome.SUCCESS,
        entityType: String? = null,
        entityId: Any? = null,
        details: Map<String, Any?> = emptyMap(),
        actor: String = ACTOR_OWNER,
    ): UUID =
        db.tx {
            val eventId = UUID.randomUUID()
            val now = clock.instant()
            val safe = SecretRedactor.redactMap(details).toJsonElement() as JsonObject
            val prev = db.sql("select hash from audit_events order by id desc limit 1").firstOrNull { it.str("hash") }
            val material = listOf(prev ?: "GENESIS", eventId, now.toEpochMilli(), actor, category, action, outcome, entityType, entityId?.toString(), safe.canonical()).joinToString("|")
            db
                .sql(
                    """
                    insert into audit_events(event_id, occurred_at, actor, category, action, outcome, entity_type, entity_id, details, prev_hash, hash)
                    values (:e, :t, :a, :c, :ac, :o, :et, :eid, :d, :p, :h)
                    """.trimIndent(),
                ).param("e", eventId)
                .param("t", now)
                .param("a", actor)
                .param("c", category)
                .param("ac", action)
                .param("o", outcome)
                .param("et", entityType)
                .param("eid", entityId?.toString())
                .param("d", safe.toString())
                .param("p", prev)
                .param("h", sha256(material))
                .update()
            eventId
        }

    fun list(
        limit: Int,
        beforeId: Long? = null,
        category: String? = null,
    ): List<AuditEvent> =
        db
            .sql(
                """
                select * from audit_events where (:b is null or id < :b) and (:c is null or category = :c)
                order by id desc limit :l
                """.trimIndent(),
            ).param("b", beforeId)
            .param("c", category)
            .param("l", limit)
            .list { r ->
                AuditEvent(
                    r.long("id")!!,
                    r.uuid("event_id"),
                    r.instant("occurred_at"),
                    r.str("actor"),
                    r.str("category"),
                    r.str("action"),
                    r.str("outcome"),
                    r.string("entity_type"),
                    r.string("entity_id"),
                    EngineJson.parseToJsonElement(r.str("details")).jsonObject,
                    r.str("hash"),
                    r.string("prev_hash"),
                )
            }

    /** Recomputes the hash chain; returns the first broken event id, or null when intact. */
    fun verifyChain(): Long? {
        var prev: String? = null
        var broken: Long? = null
        db.sql("select * from audit_events order by id").list { r ->
            if (broken == null) {
                val details = EngineJson.parseToJsonElement(r.str("details")).canonical()
                val material =
                    listOf(prev ?: "GENESIS", r.str("event_id"), r.long("occurred_at"), r.str("actor"), r.str("category"), r.str("action"), r.str("outcome"), r.string("entity_type"), r.string("entity_id"), details)
                        .joinToString("|")
                if (sha256(material) != r.str("hash") || r.string("prev_hash") != prev) broken = r.long("id")
                prev = r.str("hash")
            }
        }
        return broken
    }

    companion object {
        const val ACTOR_OWNER = "OWNER"
        const val ACTOR_SYSTEM = "SYSTEM"

        fun sha256(s: String): String = Hashing.sha256Hex(s)
    }
}
