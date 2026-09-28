package app.strategyforge.research

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.db.uuidOrNull
import app.strategyforge.common.money.Decimals
import app.strategyforge.common.web.ApiException
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.common.web.Problems
import app.strategyforge.identity.CurrentOwner
import app.strategyforge.market.AssetClass
import app.strategyforge.market.InstrumentService
import app.strategyforge.market.Timeframe
import app.strategyforge.providers.ProviderKind
import app.strategyforge.providers.ProviderService
import app.strategyforge.providers.ResolvedProvider
import app.strategyforge.strategy.StrategyService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

data class ResearchCreate(
    val title: String,
    val providerId: UUID,
    val assetClass: String,
    val universe: List<String>,
    val horizon: String,
    val timeframe: String,
    val approach: String,
    val prompt: String,
    val retrieval: Boolean = false,
    val maxRequests: Int = 3,
    val maxCostUsd: BigDecimal = BigDecimal("2"),
)

data class ResearchSession(
    val id: UUID,
    val title: String,
    val providerId: UUID,
    val providerType: String,
    val model: String,
    val assetClass: String,
    val universe: List<String>,
    val horizon: String,
    val timeframe: String,
    val approach: String,
    val prompt: String,
    val retrieval: Boolean,
    val maxRequests: Int,
    val maxCostUsd: BigDecimal,
    val status: String,
    /** UNVERIFIED until the owner reviews the research (FR-034). */
    val reviewStatus: String,
    val reviewedAt: Instant?,
    val reviewNote: String?,
    val requestsUsed: Int,
    val costUsd: BigDecimal,
    val createdAt: Instant,
    val updatedAt: Instant,
    val version: Long,
)

data class ResearchRunView(
    val id: UUID,
    val purpose: String,
    val providerType: String,
    val model: String,
    val promptVersion: String,
    val systemPrompt: String,
    val userPrompt: String,
    val parameters: JsonNode,
    val status: String,
    val failureCode: String?,
    val failureDetail: String?,
    val responseText: String?,
    val label: String,
    val stopReason: String?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val searchRequests: Long?,
    val reservedCostUsd: BigDecimal,
    val estimatedCostUsd: BigDecimal?,
    val sourcesRequired: Boolean,
    val sources: List<AiSource>,
    val startedAt: Instant,
    val completedAt: Instant?,
)

data class ResearchEditView(
    val id: UUID,
    val baseRunId: UUID,
    val content: String,
    val note: String?,
    val createdAt: Instant,
    val actor: String,
)

data class CompilationView(
    val id: UUID,
    val sessionId: UUID,
    val runId: UUID?,
    val sourceEditId: UUID?,
    val sourceRunId: UUID,
    val compilerVersion: String,
    val status: String,
    val candidate: String?,
    val issues: JsonNode,
    val strategyId: UUID?,
    val versionId: UUID?,
    val contentHash: String?,
    val createdAt: Instant,
)

data class ResearchDetail(
    val session: ResearchSession,
    val runs: List<ResearchRunView>,
    val edits: List<ResearchEditView>,
    val compilations: List<CompilationView>,
    val disclaimer: String,
)

data class EditRequest(
    val content: String,
    val note: String? = null,
)

data class ReviewRequest(
    val decision: String,
    val note: String? = null,
)

data class AiBudget(
    val monthlyCostLimitUsd: BigDecimal,
    val dailyRequestLimit: Int,
    val maxOutputTokens: Int,
    val maxInputChars: Int,
    val monthCostUsd: BigDecimal,
    val todayRequests: Int,
    val updatedAt: Instant,
    val version: Long,
)

data class AiBudgetUpdate(
    val monthlyCostLimitUsd: BigDecimal,
    val dailyRequestLimit: Int,
    val maxOutputTokens: Int,
    val maxInputChars: Int,
)

/**
 * AI research workflow (section 13): sessions with request/cost ceilings, provider calls with full
 * provenance, owner edits and review, and controlled compilation into the strategy schema through
 * the regular strategy validator. AI output never executes and never bypasses validation or risk.
 */
@Service
class ResearchService(
    private val jdbc: JdbcClient,
    private val providers: ProviderService,
    private val clients: AiClients,
    private val budgets: AiBudgetService,
    private val instruments: InstrumentService,
    private val strategies: StrategyService,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val clock: Clock,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)
    private val schema = javaClass.getResource("/strategy/strategy-schema-1.0.json")!!.readText()
    private val executor =
        ThreadPoolTaskExecutor().apply {
            corePoolSize = 2
            maxPoolSize = 2
            queueCapacity = 20
            setThreadNamePrefix("research-")
            initialize()
        }

    fun create(req: ResearchCreate): ResearchSession {
        val p = aiProvider(req.providerId)
        val title = req.title.trim()
        if (title.isEmpty() || title.length > MAX_TITLE) throw Problems.badRequest("invalid-title", "Title must be 1-$MAX_TITLE characters")
        val asset = runCatching { AssetClass.valueOf(req.assetClass) }.getOrElse { throw Problems.badRequest("invalid-asset-class", "assetClass must be US_EQUITY or CRYPTO") }
        runCatching { Timeframe.of(req.timeframe) }.getOrElse { throw Problems.badRequest("invalid-timeframe", "Unsupported timeframe '${req.timeframe}'") }
        if (req.universe.isEmpty() || req.universe.size > MAX_UNIVERSE) throw Problems.badRequest("invalid-universe", "Universe must list 1-$MAX_UNIVERSE symbols")
        val symbols = req.universe.map { it.trim().uppercase() }.distinct()
        symbols.forEach { s ->
            val i = instruments.findBySymbol(s) ?: throw Problems.badRequest("unknown-symbol", "Unknown or unsupported symbol '$s'")
            if (i.assetClass != asset) throw Problems.badRequest("asset-class-mismatch", "$s is not a $asset instrument")
        }
        listOf("horizon" to req.horizon, "approach" to req.approach).forEach { (k, v) ->
            if (v.isBlank() || v.length > MAX_FIELD) throw Problems.badRequest("invalid-$k", "$k must be 1-$MAX_FIELD characters")
        }
        if (req.prompt.isBlank() || req.prompt.length > MAX_PROMPT) throw Problems.badRequest("invalid-prompt", "prompt must be 1-$MAX_PROMPT characters")
        if (req.maxRequests !in 1..MAX_REQUESTS) throw Problems.badRequest("invalid-max-requests", "maxRequests must be 1-$MAX_REQUESTS")
        if (req.maxCostUsd <= BigDecimal.ZERO || req.maxCostUsd > budgets.get().monthlyCostLimitUsd) {
            throw Problems.badRequest("invalid-max-cost", "maxCostUsd must be greater than 0 and at most the monthly AI budget")
        }
        if (req.retrieval) {
            if (!clients.retrievalAvailable(p)) throw Problems.unprocessable("retrieval-unavailable", "${p.type} with this configuration does not offer cited web retrieval")
            if (p.decimal("webSearchPricePerThousandUsd") == null) throw Problems.unprocessable("retrieval-price-missing", "Configure webSearchPricePerThousandUsd before using retrieval (cost ceilings must be verifiable)")
        }
        val id = UUID.randomUUID()
        val now = clock.instant()
        tx.executeWithoutResult {
            jdbc
                .sql(
                    """
                    insert into research_sessions(id, title, provider_id, provider_type, model, asset_class, universe, horizon, timeframe, approach, prompt, retrieval,
                      max_requests, max_cost_usd, status, review_status, created_at, updated_at)
                    values (:id, :t, :p, :pt, :m, :a, cast(:u as jsonb), :h, :tf, :ap, :pr, :r, :mr, :mc, 'DRAFT', 'UNVERIFIED', :now, :now)
                    """.trimIndent(),
                ).param("id", id)
                .param("t", title)
                .param("p", p.id)
                .param("pt", p.type.name)
                .param("m", p.string("model"))
                .param("a", asset.name)
                .param("u", mapper.writeValueAsString(symbols))
                .param("h", req.horizon.trim())
                .param("tf", req.timeframe)
                .param("ap", req.approach.trim())
                .param("pr", req.prompt)
                .param("r", req.retrieval)
                .param("mr", req.maxRequests)
                .param("mc", Decimals.money(req.maxCostUsd))
                .param("now", ts(now))
                .update()
            audit.record(AuditCategory.RESEARCH, "RESEARCH_SESSION_CREATED", entityType = "ResearchSession", entityId = id, details = mapOf("provider" to p.type, "model" to p.string("model"), "retrieval" to req.retrieval))
        }
        return get(id)
    }

    /** Starts a research run; the provider call happens asynchronously after the reservation commits. */
    fun run(id: UUID): ResearchDetail {
        val runId =
            tx.execute {
                val s = lock(id)
                if (s.status == "RUNNING") throw Problems.conflict("research-running", "A request for this session is already running")
                val p = aiProvider(s.providerId)
                val prompt = ResearchPrompts.researchPrompt(s.assetClass, s.universe, s.horizon, s.timeframe, s.approach, s.prompt)
                val request = AiRequest(ResearchPrompts.RESEARCH_SYSTEM, prompt, 0, s.retrieval, if (s.retrieval) p.int("maxSearchesPerRequest", DEFAULT_SEARCHES) else 0)
                start(s, p, "RESEARCH", ResearchPrompts.RESEARCH_VERSION, request)
            }!!
        dispatch { execute(runId) }
        return detail(id)
    }

    /** Owner edit of the research text; any edit returns the session to UNVERIFIED. */
    fun edit(
        id: UUID,
        req: EditRequest,
    ): ResearchDetail {
        if (req.content.isBlank() || req.content.length > MAX_EDIT) throw Problems.badRequest("invalid-content", "content must be 1-$MAX_EDIT characters")
        tx.executeWithoutResult {
            val s = lock(id)
            val base = latestResearchRun(id) ?: throw Problems.conflict("no-research", "Run research successfully before editing it")
            val editId = UUID.randomUUID()
            jdbc
                .sql("insert into research_edits(id, session_id, base_run_id, content, note, created_at, actor) values (:id, :s, :r, :c, :n, :now, :a)")
                .param("id", editId)
                .param("s", id)
                .param("r", base)
                .param("c", req.content)
                .param("n", req.note?.take(MAX_FIELD))
                .param("now", ts(clock.instant()))
                .param("a", actor())
                .update()
            setReview(s, "UNVERIFIED", "Edited; review required")
            audit.record(AuditCategory.RESEARCH, "RESEARCH_EDITED", entityType = "ResearchSession", entityId = id, details = mapOf("editId" to editId, "baseRunId" to base, "chars" to req.content.length))
        }
        return detail(id)
    }

    fun review(
        id: UUID,
        req: ReviewRequest,
    ): ResearchDetail {
        val target =
            when (req.decision) {
                "APPROVE" -> "REVIEWED"
                "REJECT" -> "REJECTED"
                else -> throw Problems.badRequest("invalid-decision", "decision must be APPROVE or REJECT")
            }
        tx.executeWithoutResult {
            val s = lock(id)
            val run = latestResearchRun(id) ?: throw Problems.conflict("no-research", "There is no successful research to review")
            if (target == "REVIEWED" && runSources(run).isEmpty() && s.retrieval) {
                throw Problems.conflict("missing-sources", "Research that required retrieval has no cited sources and cannot be approved")
            }
            setReview(s, target, req.note?.take(MAX_FIELD))
            audit.record(AuditCategory.RESEARCH, "RESEARCH_$target", entityType = "ResearchSession", entityId = id, details = mapOf("runId" to run, "note" to req.note))
        }
        return detail(id)
    }

    /** Compiles reviewed research into a candidate strategy through the model, then the regular validator. */
    fun compile(id: UUID): ResearchDetail {
        val runId =
            tx.execute {
                val s = lock(id)
                if (s.reviewStatus != "REVIEWED") throw Problems.conflict("research-not-reviewed", "Only research reviewed and approved by the owner can be compiled")
                if (s.status == "RUNNING") throw Problems.conflict("research-running", "A request for this session is already running")
                val (text, _, _) = compileSource(id)
                val p = aiProvider(s.providerId)
                val request = AiRequest(ResearchPrompts.compileSystem(schema), ResearchPrompts.compilePrompt(s.assetClass, s.timeframe, s.universe, text), 0, false)
                start(s, p, "COMPILE", ResearchPrompts.COMPILE_VERSION, request)
            }!!
        dispatch { execute(runId) }
        return detail(id)
    }

    /** Reserves budget and records the run as RUNNING; fails closed before any network call (FR-037). */
    private fun start(
        s: ResearchSession,
        p: ResolvedProvider,
        purpose: String,
        promptVersion: String,
        base: AiRequest,
    ): UUID {
        val reservation = budgets.reserve(s, p, base)
        val request = base.copy(maxOutputTokens = reservation.maxOutputTokens)
        val runId = UUID.randomUUID()
        val params =
            mapOf(
                "maxOutputTokens" to request.maxOutputTokens,
                "retrieval" to request.retrieval,
                "maxSearches" to request.maxSearches,
                "timeoutSeconds" to AiEndpoints.timeout(p).seconds,
                "inputPricePerMillionTokensUsd" to p.decimal("inputPricePerMillionTokensUsd"),
                "outputPricePerMillionTokensUsd" to p.decimal("outputPricePerMillionTokensUsd"),
            )
        jdbc
            .sql(
                """
                insert into research_runs(id, session_id, purpose, provider_id, provider_type, model, prompt_version, system_prompt, user_prompt, parameters, status,
                  reserved_cost_usd, sources_required, started_at)
                values (:id, :s, :pu, :p, :pt, :m, :pv, :sys, :usr, cast(:par as jsonb), 'RUNNING', :res, :src, :now)
                """.trimIndent(),
            ).param("id", runId)
            .param("s", s.id)
            .param("pu", purpose)
            .param("p", p.id)
            .param("pt", p.type.name)
            .param("m", p.string("model"))
            .param("pv", promptVersion)
            .param("sys", request.system)
            .param("usr", request.prompt)
            .param("par", mapper.writeValueAsString(params))
            .param("res", reservation.worstCaseUsd)
            .param("src", purpose == "RESEARCH" && request.retrieval)
            .param("now", ts(clock.instant()))
            .update()
        jdbc
            .sql("update research_sessions set status = 'RUNNING', updated_at = :now, version = version + 1 where id = :id")
            .param("now", ts(clock.instant()))
            .param("id", s.id)
            .update()
        audit.record(AuditCategory.RESEARCH, "AI_REQUEST_STARTED", entityType = "ResearchRun", entityId = runId, details = mapOf("sessionId" to s.id, "purpose" to purpose, "provider" to p.type, "model" to p.string("model"), "reservedUsd" to reservation.worstCaseUsd))
        return runId
    }

    private fun dispatch(task: () -> Unit) {
        val correlation = CorrelationIdFilter.current()
        val r =
            Runnable {
                org.slf4j.MDC.put(CorrelationIdFilter.MDC_KEY, correlation)
                try {
                    task()
                } finally {
                    org.slf4j.MDC.remove(CorrelationIdFilter.MDC_KEY)
                }
            }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() = executor.execute(r)
                },
            )
        } else {
            executor.execute(r)
        }
    }

    /** Performs the provider call for a RUNNING run and records the outcome. */
    fun execute(runId: UUID) {
        val row =
            jdbc
                .sql("select * from research_runs where id = :id")
                .param("id", runId)
                .query { rs, _ -> mapRun(rs, emptyList()) }
                .single()
        if (row.status != "RUNNING") return
        val sessionId =
            jdbc
                .sql("select session_id from research_runs where id = :id")
                .param("id", runId)
                .query(UUID::class.java)
                .single()
        val providerId =
            jdbc
                .sql("select provider_id from research_runs where id = :id")
                .param("id", runId)
                .query(UUID::class.java)
                .single()
        val result =
            try {
                val p = providers.resolve(providerId)
                val request = AiRequest(row.systemPrompt, row.userPrompt, row.parameters["maxOutputTokens"].asInt(), row.parameters["retrieval"].asBoolean(), row.parameters["maxSearches"].asInt())
                Result.success(clients.forType(p.type).complete(p, request) to p)
            } catch (e: AiException) {
                Result.failure(e)
            } catch (e: RuntimeException) {
                log.error("AI request {} failed unexpectedly", runId, e)
                Result.failure(AiException(AiFailure.PROVIDER_ERROR, "Unexpected adapter failure (${e.javaClass.simpleName})"))
            }
        val response = result.getOrNull()
        if (response == null) {
            val e = result.exceptionOrNull() as AiException
            finishFailed(runId, sessionId, e.failure.name, e.message ?: e.failure.name, e.raw)
            return
        }
        val (r, p) = response
        val cost = r.reportedCostUsd ?: budgets.cost(p, r.inputTokens, r.outputTokens, r.searchRequests)
        if (row.sourcesRequired && r.sources.isEmpty()) {
            // FR-033: retrieval research without citations is recorded but cannot be approved (MS-18).
            finishFailed(runId, sessionId, "MISSING_SOURCES", "The response contains no cited sources although retrieval was required", r.raw, r, cost)
            return
        }
        if (row.purpose == "COMPILE") {
            try {
                completeCompilation(runId, sessionId, r, cost)
            } catch (e: RuntimeException) {
                log.error("Compilation for run {} failed", runId, e)
                jdbc
                    .sql("update research_sessions set status = 'FAILED', updated_at = :now, version = version + 1 where id = :id")
                    .param("now", ts(clock.instant()))
                    .param("id", sessionId)
                    .update()
                audit.recordIndependently(AuditCategory.RESEARCH, "STRATEGY_COMPILATION_FAILED", AuditOutcome.FAILURE, "ResearchRun", runId, mapOf("error" to e.javaClass.simpleName))
            }
        } else {
            tx.executeWithoutResult {
                finishSucceeded(runId, r, cost)
                jdbc
                    .sql("update research_sessions set status = 'COMPLETED', review_status = 'UNVERIFIED', reviewed_at = null, updated_at = :now, version = version + 1 where id = :id")
                    .param("now", ts(clock.instant()))
                    .param("id", sessionId)
                    .update()
                if (r.sources.isNotEmpty()) providers.markCapabilityVerified(p.id, "WEB_SEARCH_CITATIONS", "Cited sources returned by research run $runId")
                audit.record(AuditCategory.RESEARCH, "AI_RESEARCH_COMPLETED", entityType = "ResearchRun", entityId = runId, details = mapOf("sources" to r.sources.size, "costUsd" to cost, "label" to "UNVERIFIED"))
            }
        }
    }

    private fun completeCompilation(
        runId: UUID,
        sessionId: UUID,
        r: AiResponse,
        cost: BigDecimal,
    ) {
        // The session stays RUNNING until the compilation record exists, so readers never see a half-finished compile.
        tx.executeWithoutResult { finishSucceeded(runId, r, cost) }
        val (_, editId, sourceRun) = compileSource(sessionId)
        val compilationId = UUID.randomUUID()
        val candidate = StrategyCompiler.extract(r.text)
        var status: String
        var issues: Any = emptyList<Any>()
        var strategyId: UUID? = null
        var versionId: UUID? = null
        var hash: String? = null
        when (candidate) {
            is StrategyCompiler.Candidate.Failed -> {
                status = if (candidate.declined) "FAILED" else "REJECTED"
                issues = listOf(mapOf("code" to candidate.code, "message" to candidate.message))
            }
            is StrategyCompiler.Candidate.Json -> {
                // The regular import pipeline decides: security rejection, validation failure, Manual Review Required or Validated.
                try {
                    val result = strategies.compileImport(StrategyCompiler.withProvenance(candidate.node, mapper), compilationId)
                    strategyId = result.strategy.id
                    versionId = result.version?.id
                    hash = result.version?.contentHash
                    issues = result.validation?.issues ?: emptyList<Any>()
                    status =
                        when (result.strategy.status.name) {
                            "VALIDATED" -> "COMPILED"
                            "MANUAL_REVIEW_REQUIRED" -> "MANUAL_REVIEW_REQUIRED"
                            else -> "VALIDATION_FAILED"
                        }
                } catch (e: ApiException) {
                    status = "REJECTED"
                    issues = e.properties["issues"] ?: listOf(mapOf("code" to e.code, "message" to e.message))
                }
            }
        }
        tx.executeWithoutResult {
            jdbc
                .sql(
                    """
                    insert into strategy_compilations(id, session_id, run_id, source_edit_id, source_run_id, compiler_version, status, candidate, issues, strategy_id, version_id, content_hash, created_at)
                    values (:id, :s, :r, :e, :sr, :cv, :st, :c, cast(:i as jsonb), :sid, :vid, :h, :now)
                    """.trimIndent(),
                ).param("id", compilationId)
                .param("s", sessionId)
                .param("r", runId)
                .param("e", editId)
                .param("sr", sourceRun)
                .param("cv", ResearchPrompts.COMPILE_VERSION)
                .param("st", status)
                .param("c", r.text.take(MAX_CANDIDATE))
                .param("i", mapper.writeValueAsString(issues))
                .param("sid", strategyId)
                .param("vid", versionId)
                .param("h", hash)
                .param("now", ts(clock.instant()))
                .update()
            jdbc
                .sql("update research_sessions set status = 'COMPLETED', updated_at = :now, version = version + 1 where id = :id")
                .param("now", ts(clock.instant()))
                .param("id", sessionId)
                .update()
            audit.record(
                AuditCategory.RESEARCH,
                "STRATEGY_COMPILED",
                if (status == "COMPILED") AuditOutcome.SUCCESS else AuditOutcome.BLOCKED,
                "StrategyCompilation",
                compilationId,
                mapOf("sessionId" to sessionId, "runId" to runId, "status" to status, "strategyId" to strategyId, "hash" to hash),
            )
        }
    }

    private fun finishSucceeded(
        runId: UUID,
        r: AiResponse,
        cost: BigDecimal,
    ) {
        jdbc
            .sql(
                """
                update research_runs set status = 'SUCCEEDED', response_text = :t, response_raw = :raw, stop_reason = :sr, input_tokens = :in, output_tokens = :out,
                  search_requests = :sq, estimated_cost_usd = :c, completed_at = :now where id = :id
                """.trimIndent(),
            ).param("t", r.text)
            .param("raw", r.raw)
            .param("sr", r.stopReason)
            .param("in", r.inputTokens)
            .param("out", r.outputTokens)
            .param("sq", r.searchRequests)
            .param("c", Decimals.money(cost))
            .param("now", ts(clock.instant()))
            .param("id", runId)
            .update()
        insertSources(runId, r.sources)
    }

    private fun finishFailed(
        runId: UUID,
        sessionId: UUID,
        code: String,
        detail: String,
        raw: String?,
        r: AiResponse? = null,
        cost: BigDecimal? = null,
    ) {
        tx.executeWithoutResult {
            jdbc
                .sql(
                    """
                    update research_runs set status = 'FAILED', failure_code = :fc, failure_detail = :fd, response_text = :t, response_raw = :raw, stop_reason = :sr,
                      input_tokens = :in, output_tokens = :out, search_requests = :sq, estimated_cost_usd = :c, completed_at = :now where id = :id
                    """.trimIndent(),
                ).param("fc", code)
                .param("fd", detail.take(MAX_FIELD))
                .param("t", r?.text)
                .param("raw", raw?.take(HttpAiClient.RAW_LIMIT))
                .param("sr", r?.stopReason)
                .param("in", r?.inputTokens)
                .param("out", r?.outputTokens)
                .param("sq", r?.searchRequests)
                // Unknown usage on failure: the reservation remains the charge estimate (conservative).
                .param("c", cost?.let { Decimals.money(it) })
                .param("now", ts(clock.instant()))
                .param("id", runId)
                .update()
            r?.let { insertSources(runId, it.sources) }
            jdbc
                .sql("update research_sessions set status = 'FAILED', updated_at = :now, version = version + 1 where id = :id")
                .param("now", ts(clock.instant()))
                .param("id", sessionId)
                .update()
            audit.record(AuditCategory.RESEARCH, "AI_REQUEST_FAILED", AuditOutcome.FAILURE, "ResearchRun", runId, mapOf("sessionId" to sessionId, "code" to code, "detail" to detail.take(300)))
        }
    }

    private fun insertSources(
        runId: UUID,
        sources: List<AiSource>,
    ) {
        sources.take(MAX_SOURCES).forEachIndexed { i, s ->
            jdbc
                .sql("insert into research_sources(id, run_id, position, url, title, cited_text, page_age, created_at) values (:id, :r, :p, :u, :t, :c, :a, :now)")
                .param("id", UUID.randomUUID())
                .param("r", runId)
                .param("p", i)
                .param("u", s.url.take(MAX_URL))
                .param("t", s.title?.take(MAX_FIELD))
                .param("c", s.citedText?.take(MAX_FIELD))
                .param("a", s.pageAge)
                .param("now", ts(clock.instant()))
                .update()
        }
    }

    /** The text to compile: the latest owner edit of the latest research run, else that run's response. */
    private fun compileSource(sessionId: UUID): Triple<String, UUID?, UUID> {
        val run = latestResearchRun(sessionId) ?: throw Problems.conflict("no-research", "There is no successful research to compile")
        val edit =
            jdbc
                .sql("select id, content from research_edits where session_id = :s and base_run_id = :r order by created_at desc, id limit 1")
                .param("s", sessionId)
                .param("r", run)
                .query { rs, _ -> rs.uuid("id") to rs.getString("content") }
                .optional()
                .orElse(null)
        if (edit != null) return Triple(edit.second, edit.first, run)
        val text =
            jdbc
                .sql("select response_text from research_runs where id = :id")
                .param("id", run)
                .query(String::class.java)
                .single()
        return Triple(text, null, run)
    }

    private fun latestResearchRun(sessionId: UUID): UUID? =
        jdbc
            .sql("select id from research_runs where session_id = :s and purpose = 'RESEARCH' and status = 'SUCCEEDED' order by started_at desc, id limit 1")
            .param("s", sessionId)
            .query(UUID::class.java)
            .optional()
            .orElse(null)

    private fun setReview(
        s: ResearchSession,
        status: String,
        note: String?,
    ) {
        jdbc
            .sql("update research_sessions set review_status = :r, reviewed_at = :at, review_note = :n, updated_at = :now, version = version + 1 where id = :id")
            .param("r", status)
            .param("at", if (status == "UNVERIFIED") null else ts(clock.instant()))
            .param("n", note)
            .param("now", ts(clock.instant()))
            .param("id", s.id)
            .update()
    }

    private fun aiProvider(id: UUID): ResolvedProvider {
        val view = providers.get(id)
        if (view.kind != ProviderKind.AI.name) throw Problems.badRequest("not-ai-provider", "Provider ${view.displayName} is not an AI provider")
        if (view.archivedAt != null) throw Problems.conflict("provider-archived", "Provider ${view.displayName} is archived")
        val p = providers.resolve(id)
        if (p.credential == null) throw Problems.unprocessable("provider-credential-missing", "Provider ${view.displayName} has no credential configured")
        if (p.string("model").isNullOrBlank()) throw Problems.unprocessable("provider-model-missing", "Provider ${view.displayName} has no model configured")
        return p
    }

    private fun actor() = runCatching { CurrentOwner.get().currentActor() }.getOrDefault("SYSTEM")

    // ------------------------------------------------------------------ reads

    fun list(): List<ResearchSession> = jdbc.sql("$SESSION_VIEW order by s.created_at desc").query { rs, _ -> mapSession(rs) }.list()

    fun get(id: UUID): ResearchSession =
        jdbc
            .sql("$SESSION_VIEW where s.id = :id")
            .param("id", id)
            .query { rs, _ -> mapSession(rs) }
            .optional()
            .orElseThrow { Problems.notFound("Research session", id) }

    private fun lock(id: UUID): ResearchSession {
        jdbc
            .sql("select id from research_sessions where id = :id for update")
            .param("id", id)
            .query(UUID::class.java)
            .optional()
            .orElseThrow { Problems.notFound("Research session", id) }
        return get(id)
    }

    fun detail(id: UUID): ResearchDetail {
        val s = get(id)
        val runs =
            jdbc
                .sql("select * from research_runs where session_id = :s order by started_at, id")
                .param("s", id)
                .query { rs, _ -> mapRun(rs, runSources(rs.uuid("id"))) }
                .list()
        val edits =
            jdbc
                .sql("select * from research_edits where session_id = :s order by created_at, id")
                .param("s", id)
                .query { rs, _ -> ResearchEditView(rs.uuid("id"), rs.uuid("base_run_id"), rs.getString("content"), rs.getString("note"), rs.instant("created_at"), rs.getString("actor")) }
                .list()
        return ResearchDetail(s, runs, edits, compilations("session_id", id), DISCLAIMER)
    }

    fun compilations(
        column: String,
        value: UUID,
    ): List<CompilationView> {
        require(column in setOf("session_id", "version_id", "strategy_id"))
        return jdbc
            .sql("select * from strategy_compilations where $column = :v order by created_at, id")
            .param("v", value)
            .query { rs, _ ->
                CompilationView(
                    rs.uuid("id"),
                    rs.uuid("session_id"),
                    rs.uuidOrNull("run_id"),
                    rs.uuidOrNull("source_edit_id"),
                    rs.uuid("source_run_id"),
                    rs.getString("compiler_version"),
                    rs.getString("status"),
                    rs.getString("candidate"),
                    mapper.readTree(rs.getString("issues")),
                    rs.uuidOrNull("strategy_id"),
                    rs.uuidOrNull("version_id"),
                    rs.getString("content_hash"),
                    rs.instant("created_at"),
                )
            }.list()
    }

    /** Full AI provenance of a strategy version (FR-036): compilation, session, runs, sources and edits. */
    fun provenance(versionId: UUID): Map<String, Any?> {
        val c = compilations("version_id", versionId).firstOrNull() ?: throw Problems.notFound("AI provenance for version", versionId)
        return mapOf("compilation" to c, "research" to detail(c.sessionId))
    }

    private fun runSources(runId: UUID): List<AiSource> =
        jdbc
            .sql("select * from research_sources where run_id = :r order by position")
            .param("r", runId)
            .query { rs, _ -> AiSource(rs.getString("url"), rs.getString("title"), rs.getString("cited_text"), rs.getString("page_age")) }
            .list()

    private fun mapRun(
        rs: java.sql.ResultSet,
        sources: List<AiSource>,
    ): ResearchRunView {
        val status = rs.getString("status")
        return ResearchRunView(
            rs.uuid("id"),
            rs.getString("purpose"),
            rs.getString("provider_type"),
            rs.getString("model"),
            rs.getString("prompt_version"),
            rs.getString("system_prompt"),
            rs.getString("user_prompt"),
            mapper.readTree(rs.getString("parameters")),
            status,
            rs.getString("failure_code"),
            rs.getString("failure_detail"),
            rs.getString("response_text"),
            if (status == "SUCCEEDED") "UNVERIFIED AI OUTPUT" else status,
            rs.getString("stop_reason"),
            rs.getObject("input_tokens") as Long?,
            rs.getObject("output_tokens") as Long?,
            rs.getObject("search_requests") as Long?,
            rs.getBigDecimal("reserved_cost_usd"),
            rs.getBigDecimal("estimated_cost_usd"),
            rs.getBoolean("sources_required"),
            sources,
            rs.instant("started_at"),
            rs.instantOrNull("completed_at"),
        )
    }

    private fun mapSession(rs: java.sql.ResultSet) =
        ResearchSession(
            rs.uuid("id"),
            rs.getString("title"),
            rs.uuid("provider_id"),
            rs.getString("provider_type"),
            rs.getString("model"),
            rs.getString("asset_class"),
            mapper.readValue(rs.getString("universe"), mapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
            rs.getString("horizon"),
            rs.getString("timeframe"),
            rs.getString("approach"),
            rs.getString("prompt"),
            rs.getBoolean("retrieval"),
            rs.getInt("max_requests"),
            rs.getBigDecimal("max_cost_usd"),
            rs.getString("status"),
            rs.getString("review_status"),
            rs.instantOrNull("reviewed_at"),
            rs.getString("review_note"),
            rs.getInt("requests_used"),
            rs.getBigDecimal("cost_usd").setScale(6, RoundingMode.HALF_EVEN),
            rs.instant("created_at"),
            rs.instant("updated_at"),
            rs.getLong("version"),
        )

    /** Runs interrupted by a restart never resume silently; they are recorded as failed. */
    @EventListener(ApplicationReadyEvent::class)
    fun recoverInterrupted() {
        val interrupted = jdbc.sql("select id, session_id from research_runs where status = 'RUNNING'").query { rs, _ -> rs.uuid("id") to rs.uuid("session_id") }.list()
        interrupted.forEach { (run, session) -> finishFailed(run, session, "INTERRUPTED", "The backend restarted while the request was running", null) }
    }

    companion object {
        const val MAX_TITLE = 120
        const val MAX_FIELD = 2000
        const val MAX_PROMPT = 8000
        const val MAX_EDIT = 100_000
        const val MAX_UNIVERSE = 500
        const val MAX_REQUESTS = 20
        const val MAX_SOURCES = 100
        const val MAX_URL = 2000
        const val MAX_CANDIDATE = 200_000
        const val DEFAULT_SEARCHES = 5
        const val DISCLAIMER =
            "AI research is unverified until you review it. It is informational only, cannot place orders or change risk limits, " +
                "and compiled strategies must pass validation and a backtest before paper trading. All trading is simulated."
        private const val SESSION_VIEW =
            """
            select s.*, (select count(*) from research_runs r where r.session_id = s.id) requests_used,
              (select coalesce(sum(coalesce(r.estimated_cost_usd, r.reserved_cost_usd)), 0) from research_runs r where r.session_id = s.id) cost_usd
            from research_sessions s
            """

        fun monthStart(now: Instant): Instant =
            now
                .atZone(ZoneOffset.UTC)
                .withDayOfMonth(1)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()

        fun dayStart(now: Instant): Instant =
            now
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
    }
}

/** Parses untrusted compiler output into a single JSON object candidate; anything else is rejected. */
object StrategyCompiler {
    sealed interface Candidate {
        data class Json(
            val node: ObjectNode,
        ) : Candidate

        data class Failed(
            val code: String,
            val message: String,
            val declined: Boolean = false,
        ) : Candidate
    }

    private val fence = Regex("^```(?:json)?\\s*\\n(.*)\\n```$", RegexOption.DOT_MATCHES_ALL)
    private val strict =
        ObjectMapper().apply {
            enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        }

    fun extract(text: String): Candidate {
        val trimmed = text.trim()
        // A single surrounding Markdown fence is tolerated; any other prose is not.
        val body =
            fence
                .matchEntire(trimmed)
                ?.groupValues
                ?.get(1)
                ?.trim() ?: trimmed
        val node =
            try {
                strict.readTree(body)
            } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                return Candidate.Failed("MALFORMED_JSON", "The compiler output is not a single valid JSON object")
            }
        if (node !is ObjectNode) return Candidate.Failed("MALFORMED_JSON", "The compiler output is not a JSON object")
        if (node.size() == 1 && node.has("error")) return Candidate.Failed("NOT_EXPRESSIBLE", "The model could not express the research in the schema: ${node["error"].asText().take(300)}", declined = true)
        return Candidate.Json(node)
    }

    /** Provenance is server-assigned: the model cannot claim the owner authored the strategy. */
    fun withProvenance(
        node: ObjectNode,
        mapper: ObjectMapper,
    ): ByteArray {
        val copy = node.deepCopy()
        (copy["metadata"] as? ObjectNode)?.put("createdBy", "AI_COMPILED")
        return mapper.writeValueAsBytes(copy)
    }
}
