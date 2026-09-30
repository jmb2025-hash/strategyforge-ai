package app.strategyforge.engine.research

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.toJsonElement
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.bool
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.decOrNull
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.int
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.db.uuidOrNull
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.InstrumentService
import app.strategyforge.engine.market.Timeframe
import app.strategyforge.engine.money.Decimals
import app.strategyforge.engine.strategy.StrategyService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
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

/**
 * Starts a research conversation (D-034): just the owner's first message and whether it is for crypto
 * or stocks. The AI proposes symbols, timeframe and rules; the provider defaults to the first usable one.
 */
data class ConversationStart(
    val message: String,
    val assetClass: String = "CRYPTO",
    val providerId: UUID? = null,
    val title: String? = null,
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
    /** The owner's message this research turn answers (conversations); null for form-based runs. */
    val ownerMessage: String? = null,
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

/**
 * AI research workflow (section 13): sessions with request/cost ceilings, provider calls with full
 * provenance, owner edits and review, and controlled compilation into the strategy schema through
 * the regular strategy validator. AI output never executes and never bypasses validation or risk.
 */
class ResearchService(
    private val db: Db,
    private val providers: AiProviderService,
    private val clients: AiClients,
    private val budgets: AiBudgetService,
    private val instruments: InstrumentService,
    private val strategies: StrategyService,
    private val audit: AuditService,
    private val clock: Clock,
    /** Runs the provider call off the engine thread (network may take minutes). Inline by default. */
    private val background: (() -> Unit) -> Unit = { it() },
    /** Brings the result back to the engine thread, where all database work happens. Inline by default. */
    private val engineThread: (() -> Unit) -> Unit = { it() },
) {
    private val log = EngineLog.of(javaClass)
    private val mapper: ObjectMapper = JacksonCanonical.mapper
    private val schema = javaClass.getResource("/strategy/strategy-schema-1.0.json")!!.readText()

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
        db.tx {
            db
                .sql(
                    """
                    insert into research_sessions(id, title, provider_id, provider_type, model, asset_class, universe, horizon, timeframe, approach, prompt, retrieval,
                      max_requests, max_cost_usd, status, review_status, created_at, updated_at)
                    values (:id, :t, :p, :pt, :m, :a, :u, :h, :tf, :ap, :pr, :r, :mr, :mc, 'DRAFT', 'UNVERIFIED', :now, :now)
                    """.trimIndent(),
                ).param("id", id)
                .param("t", title)
                .param("p", p.id)
                .param("pt", p.type.name)
                .param("m", p.string("model"))
                .param("a", asset.name)
                .param("u", symbols.toJsonElement().toString())
                .param("h", req.horizon.trim())
                .param("tf", req.timeframe)
                .param("ap", req.approach.trim())
                .param("pr", req.prompt)
                .param("r", req.retrieval)
                .param("mr", req.maxRequests)
                .param("mc", Decimals.money(req.maxCostUsd))
                .param("now", now)
                .update()
            audit.record(AuditCategory.RESEARCH, "RESEARCH_SESSION_CREATED", entityType = "ResearchSession", entityId = id, details = mapOf("provider" to p.type, "model" to p.string("model"), "retrieval" to req.retrieval))
        }
        return get(id)
    }

    // ------------------------------------------------------------------ conversations (D-034)

    /** Creates a conversation from the owner's first message and sends it to the AI. */
    fun startConversation(req: ConversationStart): ResearchDetail {
        val asset = runCatching { AssetClass.valueOf(req.assetClass) }.getOrElse { throw Problems.badRequest("invalid-asset-class", "assetClass must be US_EQUITY or CRYPTO") }
        val message = checkedMessage(req.message, messageLimit(asset.name, null))
        val p = req.providerId?.let { aiProvider(it) } ?: defaultProvider()
        val title = (req.title?.trim()?.takeIf { it.isNotEmpty() } ?: message.lineSequence().first().trim()).let { if (it.length > MAX_TITLE) it.take(MAX_TITLE - 1) + "…" else it }
        val id = UUID.randomUUID()
        val now = clock.instant()
        val retrieval = clients.retrievalAvailable(p) && p.webSearchPrice() != null
        db.tx {
            db
                .sql(
                    """
                    insert into research_sessions(id, title, provider_id, provider_type, model, asset_class, universe, horizon, timeframe, approach, prompt, retrieval,
                      max_requests, max_cost_usd, status, review_status, created_at, updated_at)
                    values (:id, :t, :p, :pt, :m, :a, '[]', '', '', '', :pr, :r, :mr, :mc, 'DRAFT', 'UNVERIFIED', :now, :now)
                    """.trimIndent(),
                ).param("id", id)
                .param("t", title)
                .param("p", p.id)
                .param("pt", p.type.name)
                .param("m", p.string("model"))
                .param("a", asset.name)
                .param("pr", message)
                .param("r", retrieval)
                .param("mr", CONVERSATION_MAX_REQUESTS)
                // The monthly AI budget is the ceiling; a conversation has no separate cost cap.
                .param("mc", Decimals.money(budgets.get().monthlyCostLimitUsd))
                .param("now", now)
                .update()
            audit.record(AuditCategory.RESEARCH, "RESEARCH_SESSION_CREATED", entityType = "ResearchSession", entityId = id, details = mapOf("provider" to p.type, "model" to p.string("model"), "retrieval" to retrieval, "conversation" to true))
        }
        return turn(id, message)
    }

    /** Sends the owner's next message in a conversation; the AI sees the earlier turns. */
    fun message(
        id: UUID,
        text: String,
    ): ResearchDetail = turn(id, checkedMessage(text, null))

    /**
     * The longest message that fits the AI budget's input ceiling together with the app's
     * instructions and context (D-040); earlier turns are dropped to make room, the message is not.
     */
    fun messageLimit(
        assetClass: String,
        legacyContext: String?,
    ): Int {
        val fixed = ResearchPrompts.conversationPrompt(assetClass, tradable(assetClass), legacyContext, emptyList(), "").length
        return (budgets.get().maxInputChars - ResearchPrompts.CONVERSATION_SYSTEM.length - fixed - PROMPT_MARGIN).coerceAtLeast(0)
    }

    /** The limit shown in the app: the smaller of the crypto and stock limits for a new conversation. */
    fun messageLimit(): Int = minOf(messageLimit(AssetClass.CRYPTO.name, null), messageLimit(AssetClass.US_EQUITY.name, null))

    private fun checkedMessage(
        text: String,
        limit: Int?,
    ): String {
        val m = text.trim()
        if (m.isEmpty()) throw Problems.badRequest("invalid-message", "Write a message first")
        if (limit != null) tooLong(m, limit)
        return m
    }

    private fun tooLong(
        m: String,
        limit: Int,
    ) {
        if (m.length > limit) {
            throw Problems.badRequest(
                "message-too-long",
                "Your message is ${m.length} characters; it can be at most $limit with the current AI budget. " +
                    "Shorten it, or raise the maximum input characters in More → AI budget.",
            )
        }
    }

    /** The first active provider with a key, Gemini first. */
    private fun defaultProvider(): AiProviderConfig {
        val candidates = providers.list().sortedBy { if (it.providerType == AiProviderType.GEMINI) 0 else 1 }
        val usable = candidates.firstOrNull { it.active && it.keyConfigured } ?: throw Problems.unprocessable("no-ai-provider", "Add an AI provider and its key first (More → AI providers and keys)")
        return aiProvider(usable.id)
    }

    private fun turn(
        id: UUID,
        message: String,
    ): ResearchDetail {
        val runId =
            reserving {
                val s = lock(id)
                if (s.status == "RUNNING") throw Problems.conflict("research-running", "The AI is still answering the previous message")
                val p = aiProvider(s.providerId)
                val retrieval = clients.retrievalAvailable(p) && p.webSearchPrice() != null
                val context = legacyContext(s)
                tooLong(message, messageLimit(s.assetClass, context))
                val base = ResearchPrompts.conversationPrompt(s.assetClass, tradable(s.assetClass), context, emptyList(), message)
                val room = budgets.get().maxInputChars - ResearchPrompts.CONVERSATION_SYSTEM.length - base.length - PROMPT_MARGIN
                val prompt = ResearchPrompts.conversationPrompt(s.assetClass, tradable(s.assetClass), context, fittingHistory(id, s, room), message)
                val request = AiRequest(ResearchPrompts.CONVERSATION_SYSTEM, prompt, 0, retrieval, if (retrieval) p.int("maxSearchesPerRequest", DEFAULT_SEARCHES) else 0)
                start(s, p, "RESEARCH", ResearchPrompts.CONVERSATION_VERSION, request, ownerMessage = message)
            }
        launch(runId)
        return detail(id)
    }

    /** Earlier successful turns, newest kept first when they do not all fit, returned oldest first. */
    private fun fittingHistory(
        id: UUID,
        s: ResearchSession,
        room: Int,
    ): List<Pair<String, String>> {
        val all = conversation(id, s)
        val kept = ArrayDeque<Pair<String, String>>()
        var used = 0
        for (turn in all.asReversed()) {
            val size = turn.first.length + turn.second.length + TURN_OVERHEAD
            if (used + size > room) break
            kept.addFirst(turn)
            used += size
        }
        return kept.toList()
    }

    /** (owner message, AI answer) for every successful research turn, oldest first. */
    private fun conversation(
        id: UUID,
        s: ResearchSession,
    ): List<Pair<String, String>> =
        db
            .sql("select owner_message, response_text from research_runs where session_id = :s and purpose = 'RESEARCH' and status = 'SUCCEEDED' order by started_at, id")
            .param("s", id)
            .list { rs -> (rs.string("owner_message") ?: s.prompt) to rs.str("response_text") }

    /** Form-based sessions from before conversations keep their original parameters as context. */
    private fun legacyContext(s: ResearchSession): String? =
        if (s.timeframe.isBlank()) {
            null
        } else {
            "Owner's original parameters: symbols ${s.universe.joinToString(", ")}; horizon ${s.horizon}; timeframe ${s.timeframe}; approach ${s.approach}"
        }

    private fun tradable(assetClass: String): List<String> = instruments.list(null, assetClass, true).map { it.symbol }

    /** Runs the reservation transaction; a budget refusal is audited after the rollback so it is never lost. */
    private fun reserving(block: () -> UUID): UUID =
        try {
            db.tx(block)
        } catch (e: EngineException) {
            if (e.code == AiBudgetService.REFUSED) budgets.recordRefusal(e)
            throw e
        }

    /**
     * Runs research. Form-based sessions (from before conversations) keep their one-shot prompt until
     * their first answer; after that, and for conversations, it re-sends the owner's latest message
     * (for example after a failed or interrupted turn).
     */
    fun run(id: UUID): ResearchDetail {
        val s = get(id)
        if (s.timeframe.isBlank() || latestResearchRun(id) != null) return turn(id, lastOwnerMessage(id) ?: s.prompt)
        val runId =
            reserving {
                val locked = lock(id)
                if (locked.status == "RUNNING") throw Problems.conflict("research-running", "A request for this session is already running")
                val p = aiProvider(locked.providerId)
                val prompt = ResearchPrompts.researchPrompt(locked.assetClass, locked.universe, locked.horizon, locked.timeframe, locked.approach, locked.prompt)
                val request = AiRequest(ResearchPrompts.RESEARCH_SYSTEM, prompt, 0, locked.retrieval, if (locked.retrieval) p.int("maxSearchesPerRequest", DEFAULT_SEARCHES) else 0)
                start(locked, p, "RESEARCH", ResearchPrompts.RESEARCH_VERSION, request)
            }
        launch(runId)
        return detail(id)
    }

    private fun lastOwnerMessage(id: UUID): String? =
        db
            .sql("select owner_message from research_runs where session_id = :s and purpose = 'RESEARCH' and owner_message is not null order by started_at desc, id limit 1")
            .param("s", id)
            .firstOrNull { it.string("owner_message") }

    /** Owner edit of the research text; any edit returns the session to UNVERIFIED. */
    fun edit(
        id: UUID,
        req: EditRequest,
    ): ResearchDetail {
        if (req.content.isBlank() || req.content.length > MAX_EDIT) throw Problems.badRequest("invalid-content", "content must be 1-$MAX_EDIT characters")
        db.tx {
            val s = lock(id)
            val base = latestResearchRun(id) ?: throw Problems.conflict("no-research", "Run research successfully before editing it")
            val editId = UUID.randomUUID()
            db
                .sql("insert into research_edits(id, session_id, base_run_id, content, note, created_at, actor) values (:id, :s, :r, :c, :n, :now, :a)")
                .param("id", editId)
                .param("s", id)
                .param("r", base)
                .param("c", req.content)
                .param("n", req.note?.take(MAX_FIELD))
                .param("now", clock.instant())
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
        db.tx {
            val s = lock(id)
            val run = latestResearchRun(id) ?: throw Problems.conflict("no-research", "There is no successful research to review")
            // Form-based research that required retrieval must cite; conversation turns show whether they cited instead.
            if (target == "REVIEWED" && runSources(run).isEmpty() && s.retrieval && sourcesRequired(run)) {
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
            reserving {
                val s = lock(id)
                if (s.reviewStatus != "REVIEWED") throw Problems.conflict("research-not-reviewed", "Only research reviewed and approved by the owner can be compiled")
                if (s.status == "RUNNING") throw Problems.conflict("research-running", "A request for this session is already running")
                val (text, editId, _) = compileSource(id)
                val p = aiProvider(s.providerId)
                if (s.timeframe.isBlank()) {
                    val system = ResearchPrompts.conversationCompileSystem(schema)
                    val empty = ResearchPrompts.conversationCompilePrompt(s.assetClass, tradable(s.assetClass), "")
                    val room = budgets.get().maxInputChars - system.length - empty.length - PROMPT_MARGIN
                    // The most recent turns that fit; the owner's latest corrections matter most.
                    val research =
                        if (editId != null || text.length <= room) {
                            text
                        } else {
                            fittingHistory(id, s, room).joinToString("\n\n") { (owner, ai) -> "Owner:\n$owner\n\nResearch assistant:\n$ai" }
                        }
                    val request = AiRequest(system, ResearchPrompts.conversationCompilePrompt(s.assetClass, tradable(s.assetClass), research), 0, false)
                    start(s, p, "COMPILE", ResearchPrompts.CONVERSATION_COMPILE_VERSION, request)
                } else {
                    val request = AiRequest(ResearchPrompts.compileSystem(schema), ResearchPrompts.compilePrompt(s.assetClass, s.timeframe, s.universe, text), 0, false)
                    start(s, p, "COMPILE", ResearchPrompts.COMPILE_VERSION, request)
                }
            }
        launch(runId)
        return detail(id)
    }

    /** Reserves budget and records the run as RUNNING; fails closed before any network call (FR-037). */
    private fun start(
        s: ResearchSession,
        p: AiProviderConfig,
        purpose: String,
        promptVersion: String,
        base: AiRequest,
        ownerMessage: String? = null,
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
        db
            .sql(
                """
                insert into research_runs(id, session_id, purpose, provider_id, provider_type, model, prompt_version, system_prompt, user_prompt, parameters, status,
                  reserved_cost_usd, sources_required, started_at, owner_message)
                values (:id, :s, :pu, :p, :pt, :m, :pv, :sys, :usr, :par, 'RUNNING', :res, :src, :now, :om)
                """.trimIndent(),
            ).param("id", runId)
            .param("om", ownerMessage)
            .param("s", s.id)
            .param("pu", purpose)
            .param("p", p.id)
            .param("pt", p.type.name)
            .param("m", p.string("model"))
            .param("pv", promptVersion)
            .param("sys", request.system)
            .param("usr", request.prompt)
            .param("par", params.toJsonElement().toString())
            .param("res", reservation.worstCaseUsd)
            // Only form-based research requires citations per run; conversation turns show their sources instead.
            .param("src", purpose == "RESEARCH" && request.retrieval && ownerMessage == null)
            .param("now", clock.instant())
            .update()
        db
            .sql("update research_sessions set status = 'RUNNING', updated_at = :now, version = version + 1 where id = :id")
            .param("now", clock.instant())
            .param("id", s.id)
            .update()
        audit.record(AuditCategory.RESEARCH, "AI_REQUEST_STARTED", entityType = "ResearchRun", entityId = runId, details = mapOf("sessionId" to s.id, "purpose" to purpose, "provider" to p.type, "model" to p.string("model"), "reservedUsd" to reservation.worstCaseUsd))
        return runId
    }

    private class Job(
        val runId: UUID,
        val sessionId: UUID,
        val purpose: String,
        val sourcesRequired: Boolean,
        val provider: AiProviderConfig?,
        val request: AiRequest,
    )

    /** Reads the RUNNING run on the engine thread, calls the provider in the background, records the outcome back on the engine thread. */
    private fun launch(runId: UUID) {
        val job =
            db
                .sql("select * from research_runs where id = :id and status = 'RUNNING'")
                .param("id", runId)
                .firstOrNull { rs ->
                    val params = mapper.readTree(rs.str("parameters"))
                    Job(
                        runId,
                        rs.uuid("session_id"),
                        rs.str("purpose"),
                        rs.bool("sources_required"),
                        runCatching { providers.resolve(rs.uuid("provider_id")) }.getOrNull(),
                        AiRequest(rs.str("system_prompt"), rs.str("user_prompt"), params["maxOutputTokens"].asInt(), params["retrieval"].asBoolean(), params["maxSearches"].asInt()),
                    )
                } ?: return
        background {
            val result =
                try {
                    val p = job.provider ?: throw AiException(AiFailure.AUTHENTICATION, "The provider or its key is no longer available")
                    Result.success(clients.forType(p.type).complete(p, job.request) to p)
                } catch (e: AiException) {
                    Result.failure(e)
                } catch (e: RuntimeException) {
                    log.error("AI request {} failed unexpectedly", job.runId, e)
                    Result.failure(AiException(AiFailure.PROVIDER_ERROR, "Unexpected adapter failure (${e.javaClass.simpleName})"))
                }
            engineThread { record(job, result) }
        }
    }

    private fun record(
        job: Job,
        result: Result<Pair<AiResponse, AiProviderConfig>>,
    ) {
        val runId = job.runId
        val sessionId = job.sessionId
        val response = result.getOrNull()
        if (response == null) {
            val e = result.exceptionOrNull() as AiException
            finishFailed(runId, sessionId, e.failure.name, e.message ?: e.failure.name, e.raw)
            return
        }
        val (r, p) = response
        val cost = r.reportedCostUsd ?: budgets.cost(p, r.inputTokens, r.outputTokens, r.searchRequests)
        if (job.sourcesRequired && r.sources.isEmpty()) {
            // FR-033: retrieval research without citations is recorded but cannot be approved (MS-18).
            finishFailed(runId, sessionId, "MISSING_SOURCES", "The response contains no cited sources although retrieval was required", r.raw, r, cost)
            return
        }
        if (job.purpose == "COMPILE") {
            try {
                completeCompilation(runId, sessionId, r, cost)
            } catch (e: RuntimeException) {
                log.error("Compilation for run {} failed", runId, e)
                db
                    .sql("update research_sessions set status = 'FAILED', updated_at = :now, version = version + 1 where id = :id")
                    .param("now", clock.instant())
                    .param("id", sessionId)
                    .update()
                audit.record(AuditCategory.RESEARCH, "STRATEGY_COMPILATION_FAILED", AuditOutcome.FAILURE, "ResearchRun", runId, mapOf("error" to e.javaClass.simpleName))
            }
        } else {
            db.tx {
                finishSucceeded(runId, r, cost)
                db
                    .sql("update research_sessions set status = 'COMPLETED', review_status = 'UNVERIFIED', reviewed_at = null, updated_at = :now, version = version + 1 where id = :id")
                    .param("now", clock.instant())
                    .param("id", sessionId)
                    .update()
                audit.record(AuditCategory.RESEARCH, "AI_RESEARCH_COMPLETED", entityType = "ResearchRun", entityId = runId, details = mapOf("sources" to r.sources.size, "costUsd" to cost, "label" to "UNVERIFIED", "provider" to p.type))
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
        db.tx { finishSucceeded(runId, r, cost) }
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
                    issues = result.validation.issues.map { mapOf("code" to it.code, "category" to it.category.name, "severity" to it.severity.name, "path" to it.path, "message" to it.message) }
                    status =
                        when (result.strategy.status.name) {
                            "VALIDATED" -> "COMPILED"
                            "MANUAL_REVIEW_REQUIRED" -> "MANUAL_REVIEW_REQUIRED"
                            else -> "VALIDATION_FAILED"
                        }
                } catch (e: EngineException) {
                    status = "REJECTED"
                    @Suppress("UNCHECKED_CAST")
                    val rejected = e.properties["issues"] as? List<app.strategyforge.engine.strategy.ValidationIssue>
                    issues = rejected?.map { mapOf("code" to it.code, "message" to it.message) } ?: listOf(mapOf("code" to e.code, "message" to e.message))
                }
            }
        }
        db.tx {
            db
                .sql(
                    """
                    insert into strategy_compilations(id, session_id, run_id, source_edit_id, source_run_id, compiler_version, status, candidate, issues, strategy_id, version_id, content_hash, created_at)
                    values (:id, :s, :r, :e, :sr, :cv, :st, :c, :i, :sid, :vid, :h, :now)
                    """.trimIndent(),
                ).param("id", compilationId)
                .param("s", sessionId)
                .param("r", runId)
                .param("e", editId)
                .param("sr", sourceRun)
                .param("cv", ResearchPrompts.COMPILE_VERSION)
                .param("st", status)
                .param("c", r.text.take(MAX_CANDIDATE))
                .param("i", issues.toJsonElement().toString())
                .param("sid", strategyId)
                .param("vid", versionId)
                .param("h", hash)
                .param("now", clock.instant())
                .update()
            db
                .sql("update research_sessions set status = 'COMPLETED', updated_at = :now, version = version + 1 where id = :id")
                .param("now", clock.instant())
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
        db
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
            .param("now", clock.instant())
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
        db.tx {
            db
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
                .param("now", clock.instant())
                .param("id", runId)
                .update()
            r?.let { insertSources(runId, it.sources) }
            db
                .sql("update research_sessions set status = 'FAILED', updated_at = :now, version = version + 1 where id = :id")
                .param("now", clock.instant())
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
            db
                .sql("insert into research_sources(id, run_id, position, url, title, cited_text, page_age, created_at) values (:id, :r, :p, :u, :t, :c, :a, :now)")
                .param("id", UUID.randomUUID())
                .param("r", runId)
                .param("p", i)
                .param("u", s.url.take(MAX_URL))
                .param("t", s.title?.take(MAX_FIELD))
                .param("c", s.citedText?.take(MAX_FIELD))
                .param("a", s.pageAge)
                .param("now", clock.instant())
                .update()
        }
    }

    /** The text to compile: the latest owner edit of the latest research run, else that run's response. */
    private fun compileSource(sessionId: UUID): Triple<String, UUID?, UUID> {
        val run = latestResearchRun(sessionId) ?: throw Problems.conflict("no-research", "There is no successful research to compile")
        val edit =
            db
                .sql("select id, content from research_edits where session_id = :s and base_run_id = :r order by created_at desc, id limit 1")
                .param("s", sessionId)
                .param("r", run)
                .firstOrNull { rs -> rs.uuid("id") to rs.str("content") }
        if (edit != null) return Triple(edit.second, edit.first, run)
        val s = get(sessionId)
        if (s.timeframe.isBlank()) {
            // A conversation compiles from every turn, so the owner's corrections are included.
            val transcript = conversation(sessionId, s).joinToString("\n\n") { (owner, ai) -> "Owner:\n$owner\n\nResearch assistant:\n$ai" }
            return Triple(transcript, null, run)
        }
        val text =
            db
                .sql("select response_text from research_runs where id = :id")
                .param("id", run)
                .single { it.str("response_text") }
        return Triple(text, null, run)
    }

    private fun sourcesRequired(runId: UUID): Boolean = db.sql("select sources_required from research_runs where id = :id").param("id", runId).single { it.bool("sources_required") }

    private fun latestResearchRun(sessionId: UUID): UUID? =
        db
            .sql("select id from research_runs where session_id = :s and purpose = 'RESEARCH' and status = 'SUCCEEDED' order by started_at desc, id limit 1")
            .param("s", sessionId)
            .firstOrNull { it.uuid("id") }

    private fun setReview(
        s: ResearchSession,
        status: String,
        note: String?,
    ) {
        db
            .sql("update research_sessions set review_status = :r, reviewed_at = :at, review_note = :n, updated_at = :now, version = version + 1 where id = :id")
            .param("r", status)
            .param("at", if (status == "UNVERIFIED") null else clock.instant())
            .param("n", note)
            .param("now", clock.instant())
            .param("id", s.id)
            .update()
    }

    private fun aiProvider(id: UUID): AiProviderConfig {
        val view = providers.get(id)
        if (!view.active) throw Problems.conflict("provider-archived", "Provider ${view.displayName} is turned off")
        val p = providers.resolve(id)
        if (p.key == null) throw Problems.unprocessable("provider-credential-missing", "Provider ${view.displayName} has no API key configured")
        if (p.string("model").isNullOrBlank()) throw Problems.unprocessable("provider-model-missing", "Provider ${view.displayName} has no model configured")
        return p
    }

    private fun actor() = AuditService.ACTOR_OWNER

    // ------------------------------------------------------------------ reads

    fun list(): List<ResearchSession> = db.sql("$SESSION_VIEW order by s.created_at desc").list { rs -> mapSession(rs) }

    fun get(id: UUID): ResearchSession =
        db
            .sql("$SESSION_VIEW where s.id = :id")
            .param("id", id)
            .firstOrNull { rs -> mapSession(rs) } ?: throw Problems.notFound("Research session", id)

    private fun lock(id: UUID): ResearchSession = get(id)

    fun detail(id: UUID): ResearchDetail {
        val s = get(id)
        val runs =
            db
                .sql("select * from research_runs where session_id = :s order by started_at, id")
                .param("s", id)
                .list { rs -> mapRun(rs, runSources(rs.uuid("id"))) }
        val edits =
            db
                .sql("select * from research_edits where session_id = :s order by created_at, id")
                .param("s", id)
                .list { rs -> ResearchEditView(rs.uuid("id"), rs.uuid("base_run_id"), rs.str("content"), rs.string("note"), rs.instant("created_at"), rs.str("actor")) }
        return ResearchDetail(s, runs, edits, compilations("session_id", id), DISCLAIMER)
    }

    fun compilations(
        column: String,
        value: UUID,
    ): List<CompilationView> {
        require(column in setOf("session_id", "version_id", "strategy_id"))
        return db
            .sql("select * from strategy_compilations where $column = :v order by created_at, id")
            .param("v", value)
            .list { rs ->
                CompilationView(
                    rs.uuid("id"),
                    rs.uuid("session_id"),
                    rs.uuidOrNull("run_id"),
                    rs.uuidOrNull("source_edit_id"),
                    rs.uuid("source_run_id"),
                    rs.str("compiler_version"),
                    rs.str("status"),
                    rs.string("candidate"),
                    mapper.readTree(rs.str("issues")),
                    rs.uuidOrNull("strategy_id"),
                    rs.uuidOrNull("version_id"),
                    rs.string("content_hash"),
                    rs.instant("created_at"),
                )
            }
    }

    /** Full AI provenance of a strategy version (FR-036): compilation, session, runs, sources and edits. */
    fun provenance(versionId: UUID): Map<String, Any?> {
        val c = compilations("version_id", versionId).firstOrNull() ?: throw Problems.notFound("AI provenance for version", versionId)
        return mapOf("compilation" to c, "research" to detail(c.sessionId))
    }

    private fun runSources(runId: UUID): List<AiSource> =
        db
            .sql("select * from research_sources where run_id = :r order by position")
            .param("r", runId)
            .list { rs -> AiSource(rs.str("url"), rs.string("title"), rs.string("cited_text"), rs.string("page_age")) }

    private fun mapRun(
        rs: Row,
        sources: List<AiSource>,
    ): ResearchRunView {
        val status = rs.str("status")
        return ResearchRunView(
            rs.uuid("id"),
            rs.str("purpose"),
            rs.str("provider_type"),
            rs.str("model"),
            rs.str("prompt_version"),
            rs.str("system_prompt"),
            rs.str("user_prompt"),
            mapper.readTree(rs.str("parameters")),
            status,
            rs.string("failure_code"),
            rs.string("failure_detail"),
            rs.string("response_text"),
            if (status == "SUCCEEDED") "UNVERIFIED AI OUTPUT" else status,
            rs.string("stop_reason"),
            rs.long("input_tokens"),
            rs.long("output_tokens"),
            rs.long("search_requests"),
            rs.dec("reserved_cost_usd"),
            rs.decOrNull("estimated_cost_usd"),
            rs.bool("sources_required"),
            sources,
            rs.instant("started_at"),
            rs.instantOrNull("completed_at"),
            rs.string("owner_message"),
        )
    }

    private fun mapSession(rs: Row): ResearchSession {
        val id = rs.uuid("id")
        // Charged cost per run: the estimate when known, else the reservation; summed in Kotlin.
        val costs =
            db
                .sql("select estimated_cost_usd, reserved_cost_usd from research_runs where session_id = :s")
                .param("s", id)
                .list { it.decOrNull("estimated_cost_usd") ?: it.dec("reserved_cost_usd") }
        return ResearchSession(
            id,
            rs.str("title"),
            rs.uuid("provider_id"),
            rs.str("provider_type"),
            rs.str("model"),
            rs.str("asset_class"),
            mapper.readTree(rs.str("universe")).map { it.asText() },
            rs.str("horizon"),
            rs.str("timeframe"),
            rs.str("approach"),
            rs.str("prompt"),
            rs.bool("retrieval"),
            rs.int("max_requests"),
            rs.dec("max_cost_usd"),
            rs.str("status"),
            rs.str("review_status"),
            rs.instantOrNull("reviewed_at"),
            rs.string("review_note"),
            costs.size,
            costs.fold(BigDecimal.ZERO, BigDecimal::add).setScale(6, RoundingMode.HALF_EVEN),
            rs.instant("created_at"),
            rs.instant("updated_at"),
            rs.long("version") ?: 0L,
        )
    }

    /** Runs interrupted by a restart never resume silently; they are recorded as failed. */
    fun recoverInterrupted() {
        val interrupted = db.sql("select id, session_id from research_runs where status = 'RUNNING'").list { rs -> rs.uuid("id") to rs.uuid("session_id") }
        interrupted.forEach { (run, session) -> finishFailed(run, session, "INTERRUPTED", "The app stopped while the request was running", null) }
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

        /** Messages plus compiles in one conversation; the monthly AI budget still applies. */
        const val CONVERSATION_MAX_REQUESTS = 100

        /** Characters kept free for delimiters when fitting the conversation into the input ceiling. */
        private const val PROMPT_MARGIN = 200
        private const val TURN_OVERHEAD = 60
        const val DISCLAIMER =
            "AI research is unverified until you review it. It is informational only, cannot place orders or change risk limits, " +
                "and compiled strategies must pass validation and a backtest before paper trading. All trading is simulated."
        private const val SESSION_VIEW = "select s.* from research_sessions s"
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
