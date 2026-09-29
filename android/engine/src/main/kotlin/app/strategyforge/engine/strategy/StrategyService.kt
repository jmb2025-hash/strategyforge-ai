package app.strategyforge.engine.strategy

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineEvents
import app.strategyforge.engine.common.EngineJson
import app.strategyforge.engine.common.Hashing
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.int
import app.strategyforge.engine.db.intOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.db.uuidOrNull
import app.strategyforge.engine.market.MarketSources
import app.strategyforge.engine.risk.StrategyDefinitionLookup
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class StrategyStatus(
    val active: Boolean,
) {
    DRAFT(false),
    VALIDATION_FAILED(false),
    MANUAL_REVIEW_REQUIRED(false),
    VALIDATED(false),
    BACKTESTED(false),
    PAPER_ELIGIBLE(false),
    ACTIVE_RECOMMENDATION(true),
    ACTIVE_AUTONOMOUS(true),
    PAUSED(false),
    SUSPENDED(false),
    ARCHIVED(false),
}

/** Strategy lifecycle (section 8). */
object StrategyStateMachine {
    private val S = StrategyStatus.entries.associateBy { it.name }
    private val allowed: Map<StrategyStatus, Set<StrategyStatus>> =
        mapOf(
            "DRAFT" to "VALIDATION_FAILED MANUAL_REVIEW_REQUIRED VALIDATED ARCHIVED",
            "VALIDATION_FAILED" to "DRAFT VALIDATION_FAILED MANUAL_REVIEW_REQUIRED VALIDATED ARCHIVED",
            "MANUAL_REVIEW_REQUIRED" to "DRAFT VALIDATION_FAILED MANUAL_REVIEW_REQUIRED VALIDATED ARCHIVED",
            "VALIDATED" to "BACKTESTED DRAFT VALIDATED VALIDATION_FAILED MANUAL_REVIEW_REQUIRED ARCHIVED",
            "BACKTESTED" to "PAPER_ELIGIBLE DRAFT VALIDATED ARCHIVED",
            "PAPER_ELIGIBLE" to "ACTIVE_RECOMMENDATION ACTIVE_AUTONOMOUS DRAFT VALIDATED ARCHIVED",
            "ACTIVE_RECOMMENDATION" to "ACTIVE_AUTONOMOUS PAUSED SUSPENDED",
            "ACTIVE_AUTONOMOUS" to "ACTIVE_RECOMMENDATION PAUSED SUSPENDED",
            "PAUSED" to "ACTIVE_RECOMMENDATION ACTIVE_AUTONOMOUS PAPER_ELIGIBLE SUSPENDED DRAFT ARCHIVED",
            "SUSPENDED" to "PAUSED DRAFT ARCHIVED",
        ).map { (k, v) -> S.getValue(k) to v.split(' ').map { S.getValue(it) }.toSet() }.toMap()

    fun can(
        from: StrategyStatus,
        to: StrategyStatus,
    ) = allowed[from]?.contains(to) == true
}

data class StrategyView(
    val id: UUID,
    val name: String,
    val assetClass: String,
    val status: StrategyStatus,
    val statusReason: String?,
    val currentVersionId: UUID?,
    val currentVersion: Int?,
    val contentHash: String?,
    val validationStatus: String?,
    val clonedFrom: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val archivedAt: Instant?,
    val version: Long,
)

data class StrategyVersionView(
    val id: UUID,
    val strategyId: UUID,
    val versionNumber: Int,
    val schemaVersion: String,
    val content: JsonNode,
    val contentHash: String,
    val source: String,
    val sourceRef: String?,
    val validationStatus: String,
    val createdAt: Instant,
)

data class ValidationView(
    val id: UUID,
    val versionId: UUID,
    val status: String,
    val issues: List<ValidationIssue>,
    val unknownFields: List<String>,
    val provider: String?,
    val validatorVersion: String,
    val validatedAt: Instant,
)

data class StrategyResult(
    val strategy: StrategyView,
    val version: StrategyVersionView?,
    val validation: ValidationView,
    val explanation: String?,
)

/** Published when a strategy's version or status changes (drives autonomy reauthorization). */
data class StrategyChanged(
    val strategyId: UUID,
    val from: StrategyStatus?,
    val to: StrategyStatus,
    val reason: String,
)

class StrategyService(
    private val db: Db,
    private val validator: StrategyValidator,
    private val audit: AuditService,
    private val clock: Clock,
    private val registry: MarketSources,
    private val events: EngineEvents,
) : StrategyDefinitionLookup {
    private val mapper = JacksonCanonical.mapper
    private val issuesSerializer = ListSerializer(ValidationIssue.serializer())

    // ------------------------------------------------------------------ creation

    fun create(
        content: JsonNode,
        source: String = "OWNER",
        sourceRef: String? = null,
    ): StrategyResult {
        if (content !is ObjectNode) throw Problems.badRequest("invalid-strategy", "Strategy content must be a JSON object")
        return createFromBytes(mapper.writeValueAsBytes(content), source, sourceRef, null)
    }

    /** Import of an owner-supplied file: every byte is validated before anything is stored as a strategy. */
    fun import(
        bytes: ByteArray,
        filename: String?,
    ): StrategyResult = createFromBytes(bytes, "IMPORT", null, filename?.take(200))

    /** Controlled AI compilation output (FR-035): the same byte-level validation as any import, linked to its compilation (FR-036). */
    fun compileImport(
        bytes: ByteArray,
        compilationId: UUID,
    ): StrategyResult = createFromBytes(bytes, "AI_COMPILED", compilationId.toString(), "ai-compilation-$compilationId.json")

    private fun createFromBytes(
        bytes: ByteArray,
        source: String,
        sourceRef: String?,
        filename: String?,
    ): StrategyResult {
        val report = validator.validateBytes(bytes)
        val importId = UUID.randomUUID()
        val doc = report.document
        if (doc == null || report.issues.any { it.category == IssueCategory.SECURITY }) {
            // Parse or security failures never become strategies; the rejection is recorded (before anything else is written) and the request fails.
            recordImport(importId, bytes, filename, "REJECTED", null, null, report.issues)
            audit.record(AuditCategory.STRATEGY, "STRATEGY_IMPORT_REJECTED", AuditOutcome.BLOCKED, "StrategyImport", importId, mapOf("source" to source, "codes" to report.issues.map { it.code }.distinct()))
            throw Problems.unprocessable(
                "strategy-rejected",
                "Strategy content was rejected: ${report.issues.first().message}",
                mapOf("importId" to importId, "issues" to report.issues),
            )
        }
        val name =
            doc["metadata"]
                ?.get("name")
                ?.asText()
                ?.take(80)
                ?.takeIf { it.isNotBlank() } ?: "Untitled strategy"
        val assetClass = doc["metadata"]?.get("assetClass")?.asText()?.takeIf { it == "US_EQUITY" || it == "CRYPTO" } ?: "US_EQUITY"
        return db.tx { store(doc, name, assetClass, bytes, filename, source, sourceRef, importId, report) }
    }

    private fun store(
        doc: ObjectNode,
        name: String,
        assetClass: String,
        bytes: ByteArray,
        filename: String?,
        source: String,
        sourceRef: String?,
        importId: UUID,
        report: ValidationReport,
    ): StrategyResult {
        val id = UUID.randomUUID()
        val now = clock.instant()
        db
            .sql("insert into strategies(id, name, asset_class, status, created_at, updated_at) values (:id, :n, :a, 'DRAFT', :now, :now)")
            .param("id", id)
            .param("n", name)
            .param("a", assetClass)
            .param("now", (now))
            .update()
        statusHistory(id, null, StrategyStatus.DRAFT, "Created from $source")
        val result = addVersion(id, doc, source, sourceRef, report)
        recordImport(importId, bytes, filename, report.outcome.name, id, result.version?.id, report.issues)
        audit.record(
            AuditCategory.STRATEGY,
            "STRATEGY_CREATED",
            entityType = "Strategy",
            entityId = id,
            details = mapOf("source" to source, "sourceRef" to sourceRef, "outcome" to report.outcome, "hash" to result.version?.contentHash, "unknownFields" to report.unknownFields),
        )
        return result
    }

    /** Edits create a new immutable version; active strategies must be paused first. */
    fun update(
        id: UUID,
        content: JsonNode,
        expectedVersion: Long,
    ): StrategyResult {
        val s = lock(id)
        if (s.version != expectedVersion) throw Problems.preconditionFailed("Strategy changed; reload and retry")
        if (s.status.active) throw Problems.conflict("strategy-active", "Pause the strategy before editing it")
        if (s.status == StrategyStatus.ARCHIVED) throw Problems.conflict("strategy-archived", "Archived strategies cannot be edited")
        if (content !is ObjectNode) throw Problems.badRequest("invalid-strategy", "Strategy content must be a JSON object")
        val report = validator.validateBytes(mapper.writeValueAsBytes(content))
        if (report.document == null || report.issues.any { it.category == IssueCategory.SECURITY }) {
            audit.record(AuditCategory.STRATEGY, "STRATEGY_UPDATE_REJECTED", AuditOutcome.BLOCKED, "Strategy", id, mapOf("codes" to report.issues.map { it.code }.distinct()))
            throw Problems.unprocessable("strategy-rejected", "Strategy content was rejected: ${report.issues.first().message}", mapOf("issues" to report.issues))
        }
        return db.tx {
            if (s.status != StrategyStatus.DRAFT) transition(id, s.status, StrategyStatus.DRAFT, "New version created")
            val r = addVersion(id, report.document, "OWNER", null, report)
            audit.record(AuditCategory.STRATEGY, "STRATEGY_VERSION_CREATED", entityType = "Strategy", entityId = id, details = mapOf("version" to r.version?.versionNumber, "hash" to r.version?.contentHash, "outcome" to report.outcome))
            r
        }
    }

    fun clone(
        id: UUID,
        name: String?,
    ): StrategyResult {
        val src = get(id)
        val v = currentVersion(id) ?: throw Problems.conflict("no-version", "Strategy has no version to clone")
        val content = v.content.deepCopy<JsonNode>() as ObjectNode
        (content["metadata"] as? ObjectNode)?.put("name", (name ?: "${src.name} (copy)").take(80))
        return db.tx {
            val r = createFromBytes(mapper.writeValueAsBytes(content), "CLONE", v.id.toString(), null)
            db
                .sql("update strategies set cloned_from = :c where id = :id")
                .param("c", id)
                .param("id", r.strategy.id)
                .update()
            r.copy(strategy = get(r.strategy.id))
        }
    }

    /** Re-runs validation of the current version (e.g. after instruments or providers change). */
    fun revalidate(id: UUID): StrategyResult = db.tx { revalidateInTx(id) }

    private fun revalidateInTx(id: UUID): StrategyResult {
        val s = lock(id)
        if (s.status !in setOf(StrategyStatus.DRAFT, StrategyStatus.VALIDATION_FAILED, StrategyStatus.MANUAL_REVIEW_REQUIRED, StrategyStatus.VALIDATED)) {
            throw Problems.conflict("strategy-state", "Validation can be re-run only before backtesting (status ${s.status})")
        }
        val v = currentVersion(id)!!
        val report = validator.validateDocument(v.content as ObjectNode)
        val validation = recordValidation(v.id, report)
        applyValidationOutcome(id, s.status, report.outcome, v.id)
        return StrategyResult(get(id), currentVersion(id), validation, report.definition?.let(StrategyExplainer::explain))
    }

    private fun addVersion(
        strategyId: UUID,
        docIn: ObjectNode,
        source: String,
        sourceRef: String?,
        report: ValidationReport,
    ): StrategyResult {
        val next =
            db
                .sql("select coalesce(max(version_number), 0) + 1 n from strategy_versions where strategy_id = :s")
                .param("s", strategyId)
                .int()
        val doc = docIn.deepCopy()
        // Identity fields are server-normalized and covered by the content hash.
        doc.put("strategyId", strategyId.toString())
        doc.put("version", next)
        val canonical = JacksonCanonical.canonical(doc)
        val hash = Hashing.sha256Hex(canonical)
        val versionId = UUID.randomUUID()
        db
            .sql(
                """
                insert into strategy_versions(id, strategy_id, version_number, schema_version, content, canonical_content, content_hash, source, source_ref, validation_status, created_at)
                values (:id, :s, :n, :sv, :c, :canon, :h, :src, :ref, :vs, :now)
                """.trimIndent(),
            ).param("id", versionId)
            .param("s", strategyId)
            .param("n", next)
            .param("sv", doc["schemaVersion"]?.asText() ?: "unknown")
            .param("c", canonical)
            .param("canon", canonical)
            .param("h", hash)
            .param("src", source)
            .param("ref", sourceRef)
            .param("vs", report.outcome.name)
            .param("now", (clock.instant()))
            .update()
        val name = doc["metadata"]?.get("name")?.asText()?.take(80)
        db
            .sql("update strategies set current_version_id = :v, name = coalesce(:n, name), updated_at = :now, version = version + 1 where id = :id")
            .param("v", versionId)
            .param("n", name)
            .param("now", (clock.instant()))
            .param("id", strategyId)
            .update()
        val validation = recordValidation(versionId, report)
        applyValidationOutcome(strategyId, StrategyStatus.DRAFT, report.outcome, versionId)
        events.publish(StrategyChanged(strategyId, null, get(strategyId).status, "NEW_VERSION"))
        return StrategyResult(get(strategyId), version(versionId), validation, report.definition?.let(StrategyExplainer::explain))
    }

    private fun applyValidationOutcome(
        id: UUID,
        from: StrategyStatus,
        outcome: ValidationOutcome,
        versionId: UUID,
    ) {
        val to = StrategyStatus.valueOf(outcome.name)
        db
            .sql("update strategy_versions set validation_status = :s where id = :v")
            .param("s", outcome.name)
            .param("v", versionId)
            .update()
        if (from != to || from == StrategyStatus.VALIDATED) transition(id, from, to, "Validation outcome $outcome")
    }

    private fun recordValidation(
        versionId: UUID,
        report: ValidationReport,
    ): ValidationView {
        val id = UUID.randomUUID()
        val provider = runCatching { report.definition?.let { registry.nameFor(it.assetClass) } ?: registry.active().name }.getOrNull()
        db
            .sql(
                """
                insert into strategy_validations(id, version_id, status, issues, unknown_fields, provider, validator_version, validated_at)
                values (:id, :v, :s, :i, :u, :p, :vv, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("v", versionId)
            .param("s", report.outcome.name)
            .param("i", EngineJson.encodeToString(issuesSerializer, report.issues))
            .param("u", EngineJson.encodeToString(ListSerializer(String.serializer()), report.unknownFields))
            .param("p", provider)
            .param("vv", StrategyValidator.VALIDATOR_VERSION)
            .param("now", (clock.instant()))
            .update()
        audit.record(
            AuditCategory.STRATEGY,
            "STRATEGY_VALIDATED",
            if (report.outcome == ValidationOutcome.VALIDATED) AuditOutcome.SUCCESS else AuditOutcome.BLOCKED,
            "StrategyVersion",
            versionId,
            mapOf("outcome" to report.outcome, "codes" to report.issues.map { it.code }.distinct(), "unknownFields" to report.unknownFields),
        )
        return validation(id)
    }

    private fun recordImport(
        id: UUID,
        bytes: ByteArray,
        filename: String?,
        outcome: String,
        strategyId: UUID?,
        versionId: UUID?,
        issues: List<ValidationIssue>,
    ) {
        val text = runCatching { String(bytes, Charsets.UTF_8) }.getOrNull()?.take(StrategyValidator.MAX_BYTES)
        db
            .sql(
                """
                insert into strategy_imports(id, received_at, size_bytes, sha256, filename, outcome, strategy_id, version_id, issues, original)
                values (:id, :now, :size, :sha, :f, :o, :s, :v, :i, :orig)
                """.trimIndent(),
            ).param("id", id)
            .param("now", (clock.instant()))
            .param("size", bytes.size)
            .param("sha", Hashing.sha256Hex(bytes))
            .param("f", filename)
            .param("o", outcome)
            .param("s", strategyId)
            .param("v", versionId)
            .param("i", EngineJson.encodeToString(issuesSerializer, issues))
            .param("orig", text?.replace("\u0000", ""))
            .update()
    }

    // ------------------------------------------------------------------ lifecycle

    fun transition(
        id: UUID,
        from: StrategyStatus,
        to: StrategyStatus,
        reason: String,
    ) {
        if (from == to && to != StrategyStatus.VALIDATED && to != StrategyStatus.VALIDATION_FAILED && to != StrategyStatus.MANUAL_REVIEW_REQUIRED) return
        if (from != to && !StrategyStateMachine.can(from, to)) throw Problems.conflict("invalid-transition", "Strategy cannot move from $from to $to")
        val n =
            db
                .sql("update strategies set status = :to, status_reason = :r, updated_at = :now, version = version + 1, archived_at = case when :to = 'ARCHIVED' then :now else archived_at end where id = :id and status = :from")
                .param("to", to.name)
                .param("r", reason.take(500))
                .param("now", (clock.instant()))
                .param("id", id)
                .param("from", from.name)
                .update()
        if (n != 1) throw Problems.conflict("strategy-changed", "Strategy status changed concurrently")
        statusHistory(id, from, to, reason)
        if (from != to) {
            audit.record(AuditCategory.STRATEGY, "STRATEGY_STATUS_CHANGED", entityType = "Strategy", entityId = id, details = mapOf("from" to from, "to" to to, "reason" to reason))
            events.publish(StrategyChanged(id, from, to, reason))
        }
    }

    fun pause(
        id: UUID,
        reason: String,
    ): StrategyView = db.tx { pauseInTx(id, reason) }

    private fun pauseInTx(
        id: UUID,
        reason: String,
    ): StrategyView {
        val s = lock(id)
        if (!s.status.active) throw Problems.conflict("strategy-not-active", "Only active strategies can be paused (status ${s.status})")
        transition(id, s.status, StrategyStatus.PAUSED, reason)
        return get(id)
    }

    fun suspend(
        id: UUID,
        reason: String,
    ): StrategyView = db.tx { suspendInTx(id, reason) }

    private fun suspendInTx(
        id: UUID,
        reason: String,
    ): StrategyView {
        val s = lock(id)
        if (s.status != StrategyStatus.SUSPENDED) {
            if (!StrategyStateMachine.can(s.status, StrategyStatus.SUSPENDED)) throw Problems.conflict("invalid-transition", "Strategy in ${s.status} cannot be suspended")
            transition(id, s.status, StrategyStatus.SUSPENDED, reason)
        }
        return get(id)
    }

    fun archive(id: UUID): StrategyView = db.tx { archiveInTx(id) }

    private fun archiveInTx(id: UUID): StrategyView {
        val s = lock(id)
        if (s.status.active) throw Problems.conflict("strategy-active", "Pause the strategy before archiving it")
        transition(id, s.status, StrategyStatus.ARCHIVED, "Archived by owner")
        return get(id)
    }

    fun activeCount(): Int = db.sql("select count(*) n from strategies where status in ('ACTIVE_RECOMMENDATION', 'ACTIVE_AUTONOMOUS')").int()

    // ------------------------------------------------------------------ queries

    fun list(includeArchived: Boolean): List<StrategyView> =
        db
            .sql("$VIEW_SQL where (:all = 1 or s.status <> 'ARCHIVED') order by s.created_at")
            .param("all", includeArchived)
            .list { rs -> map(rs) }

    fun get(id: UUID): StrategyView =
        db
            .sql("$VIEW_SQL where s.id = :id")
            .param("id", id)
            .firstOrNull { rs -> map(rs) } ?: throw Problems.notFound("Strategy", id)

    fun lock(id: UUID): StrategyView {
        db
            .sql("select id from strategies where id = :id")
            .param("id", id)
            .firstOrNull { it.uuid("id") } ?: throw Problems.notFound("Strategy", id)
        return get(id)
    }

    fun versions(id: UUID): List<StrategyVersionView> =
        db
            .sql("select * from strategy_versions where strategy_id = :s order by version_number desc")
            .param("s", id)
            .list { rs -> mapVersion(rs) }

    fun version(versionId: UUID): StrategyVersionView =
        db
            .sql("select * from strategy_versions where id = :id")
            .param("id", versionId)
            .firstOrNull { rs -> mapVersion(rs) } ?: throw Problems.notFound("Strategy version", versionId)

    fun versionByNumber(
        id: UUID,
        n: Int,
    ): StrategyVersionView =
        db
            .sql("select * from strategy_versions where strategy_id = :s and version_number = :n")
            .param("s", id)
            .param("n", n)
            .firstOrNull { rs -> mapVersion(rs) } ?: throw Problems.notFound("Strategy version", n)

    fun currentVersion(id: UUID): StrategyVersionView? = get(id).currentVersionId?.let { version(it) }

    fun byHash(hash: String): List<StrategyVersionView> =
        db
            .sql("select * from strategy_versions where content_hash = :h")
            .param("h", hash)
            .list { rs -> mapVersion(rs) }

    fun validation(id: UUID): ValidationView =
        db
            .sql("select * from strategy_validations where id = :id")
            .param("id", id)
            .firstOrNull { rs -> mapValidation(rs) } ?: throw Problems.notFound("Validation", id)

    fun latestValidation(versionId: UUID): ValidationView? =
        db
            .sql("select * from strategy_validations where version_id = :v order by validated_at desc limit 1")
            .param("v", versionId)
            .firstOrNull { rs -> mapValidation(rs) }

    /** Parsed definition of a stored version; only valid versions can be parsed. */
    override fun definition(versionId: UUID): StrategyDefinition {
        val v = version(versionId)
        if (v.validationStatus != "VALIDATED") throw Problems.conflict("strategy-not-validated", "Version ${v.versionNumber} is ${v.validationStatus}")
        return StrategyDefinition.from(v.content)
    }

    fun statusHistoryOf(id: UUID): List<Map<String, Any?>> =
        db
            .sql("select * from strategy_status_history where strategy_id = :s order by id")
            .param("s", id)
            .list { rs -> mapOf("from" to rs.string("from_status"), "to" to rs.string("to_status"), "reason" to rs.string("reason"), "at" to rs.instant("at")) }

    private fun statusHistory(
        id: UUID,
        from: StrategyStatus?,
        to: StrategyStatus,
        reason: String,
    ) {
        db
            .sql("insert into strategy_status_history(strategy_id, from_status, to_status, reason, at) values (:s, :f, :t, :r, :now)")
            .param("s", id)
            .param("f", from?.name)
            .param("t", to.name)
            .param("r", reason.take(500))
            .param("now", (clock.instant()))
            .update()
    }

    private fun map(rs: Row) =
        StrategyView(
            rs.uuid("id"),
            rs.str("name"),
            rs.str("asset_class"),
            StrategyStatus.valueOf(rs.str("status")),
            rs.string("status_reason"),
            rs.uuidOrNull("current_version_id"),
            rs.intOrNull("version_number"),
            rs.string("content_hash"),
            rs.string("validation_status"),
            rs.uuidOrNull("cloned_from"),
            rs.instant("created_at"),
            rs.instant("updated_at"),
            rs.instantOrNull("archived_at"),
            rs.long("version") ?: 0L,
        )

    private fun mapVersion(rs: Row) =
        StrategyVersionView(
            rs.uuid("id"),
            rs.uuid("strategy_id"),
            rs.int("version_number"),
            rs.str("schema_version"),
            mapper.readTree(rs.str("canonical_content")),
            rs.str("content_hash"),
            rs.str("source"),
            rs.string("source_ref"),
            rs.str("validation_status"),
            rs.instant("created_at"),
        )

    private fun mapValidation(rs: Row) =
        ValidationView(
            rs.uuid("id"),
            rs.uuid("version_id"),
            rs.str("status"),
            EngineJson.decodeFromString(issuesSerializer, rs.str("issues")),
            EngineJson.decodeFromString(ListSerializer(String.serializer()), rs.str("unknown_fields")),
            rs.string("provider"),
            rs.str("validator_version"),
            rs.instant("validated_at"),
        )

    companion object {
        const val MAX_ACTIVE = 25
        private const val VIEW_SQL =
            "select s.*, v.version_number, v.content_hash, v.validation_status from strategies s left join strategy_versions v on v.id = s.current_version_id"
    }
}
