package app.strategyforge.research

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import app.strategyforge.common.money.Decimals
import app.strategyforge.common.web.ETags
import app.strategyforge.common.web.Problems
import app.strategyforge.identity.RecentAuth
import app.strategyforge.providers.CapabilityResult
import app.strategyforge.providers.ProviderTestResult
import app.strategyforge.providers.ProviderTester
import app.strategyforge.providers.ProviderType
import app.strategyforge.providers.ResolvedProvider
import app.strategyforge.providers.TestStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock

data class Reservation(
    val maxOutputTokens: Int,
    val worstCaseUsd: BigDecimal,
)

/**
 * Token, request and cost ceilings (FR-037). The worst-case cost of a call (every input character
 * counted as half a token, the full output ceiling, every allowed search) is reserved before the
 * network call; a call that could exceed any ceiling is refused (D-010, fail closed).
 */
@Service
class AiBudgetService(
    private val jdbc: JdbcClient,
    private val audit: AuditService,
    private val clock: Clock,
) {
    fun get(): AiBudget {
        val now = clock.instant()
        return jdbc
            .sql("select * from ai_budget")
            .query { rs, _ ->
                AiBudget(
                    rs.getBigDecimal("monthly_cost_limit_usd"),
                    rs.getInt("daily_request_limit"),
                    rs.getInt("max_output_tokens"),
                    rs.getInt("max_input_chars"),
                    monthCost(now),
                    todayRequests(now),
                    rs.instant("updated_at"),
                    rs.getLong("version"),
                )
            }.single()
    }

    @Transactional
    fun update(
        req: AiBudgetUpdate,
        ifMatch: String?,
    ): AiBudget {
        val cur = get()
        ETags.require(ifMatch, cur.version)
        if (req.monthlyCostLimitUsd < BigDecimal.ZERO || req.monthlyCostLimitUsd > MAX_MONTHLY) throw Problems.badRequest("invalid-budget", "monthlyCostLimitUsd must be 0-$MAX_MONTHLY")
        if (req.dailyRequestLimit !in 0..MAX_DAILY) throw Problems.badRequest("invalid-budget", "dailyRequestLimit must be 0-$MAX_DAILY")
        if (req.maxOutputTokens !in MIN_OUTPUT..MAX_OUTPUT) throw Problems.badRequest("invalid-budget", "maxOutputTokens must be $MIN_OUTPUT-$MAX_OUTPUT")
        if (req.maxInputChars !in MIN_INPUT..MAX_INPUT) throw Problems.badRequest("invalid-budget", "maxInputChars must be $MIN_INPUT-$MAX_INPUT")
        val loosened =
            req.monthlyCostLimitUsd > cur.monthlyCostLimitUsd || req.dailyRequestLimit > cur.dailyRequestLimit ||
                req.maxOutputTokens > cur.maxOutputTokens || req.maxInputChars > cur.maxInputChars
        if (loosened) RecentAuth.require(clock.instant(), "raise-ai-budget")
        jdbc
            .sql(
                "update ai_budget set monthly_cost_limit_usd = :m, daily_request_limit = :d, max_output_tokens = :o, max_input_chars = :i, updated_at = :now, version = version + 1",
            ).param("m", Decimals.money(req.monthlyCostLimitUsd))
            .param("d", req.dailyRequestLimit)
            .param("o", req.maxOutputTokens)
            .param("i", req.maxInputChars)
            .param("now", ts(clock.instant()))
            .update()
        audit.record(AuditCategory.SETTINGS, "AI_BUDGET_UPDATED", details = mapOf("from" to cur.copy(monthCostUsd = BigDecimal.ZERO, todayRequests = 0), "to" to req, "loosened" to loosened))
        return get()
    }

    /** Must run inside the transaction that records the run, so concurrent requests serialize on the budget row. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun reserve(
        s: ResearchSession,
        p: ResolvedProvider,
        request: AiRequest,
    ): Reservation {
        jdbc.sql("select singleton from ai_budget for update").query(Boolean::class.java).single()
        val b = get()
        val now = clock.instant()
        val maxOut = minOf(b.maxOutputTokens, p.int("maxOutputTokens", b.maxOutputTokens))
        val chars = request.system.length + request.prompt.length
        val worst = worstCase(p, chars, maxOut, if (request.retrieval) request.maxSearches else 0)
        val sessionCost =
            jdbc
                .sql("select coalesce(sum(coalesce(estimated_cost_usd, reserved_cost_usd)), 0) from research_runs where session_id = :s")
                .param("s", s.id)
                .query(BigDecimal::class.java)
                .single()
        val sessionRequests =
            jdbc
                .sql("select count(*) from research_runs where session_id = :s")
                .param("s", s.id)
                .query(Int::class.java)
                .single()
        val refusal =
            when {
                chars > b.maxInputChars -> "The request is $chars characters; the ceiling is ${b.maxInputChars}"
                sessionRequests >= s.maxRequests -> "This session already used its ${s.maxRequests} request(s)"
                sessionCost.add(worst) > s.maxCostUsd -> "Worst-case cost ${fmt(worst)} USD would exceed the session ceiling (${fmt(sessionCost)} of ${fmt(s.maxCostUsd)} USD used)"
                b.todayRequests >= b.dailyRequestLimit -> "The daily AI request limit (${b.dailyRequestLimit}) has been reached"
                b.monthCostUsd.add(worst) > b.monthlyCostLimitUsd -> "Worst-case cost ${fmt(worst)} USD would exceed the monthly AI budget (${fmt(b.monthCostUsd)} of ${fmt(b.monthlyCostLimitUsd)} USD used)"
                else -> null
            }
        if (refusal != null) {
            audit.recordIndependently(AuditCategory.RESEARCH, "AI_BUDGET_REFUSED", AuditOutcome.BLOCKED, "ResearchSession", s.id, mapOf("reason" to refusal, "worstCaseUsd" to worst))
            throw Problems.unprocessable("ai-budget-exceeded", refusal, mapOf("worstCaseUsd" to worst))
        }
        return Reservation(maxOut, worst)
    }

    fun worstCase(
        p: ResolvedProvider,
        chars: Int,
        maxOutputTokens: Int,
        searches: Int,
    ): BigDecimal = cost(p, ((chars + 1) / 2).toLong(), maxOutputTokens.toLong(), searches.toLong())

    /** Estimated USD cost from recorded usage and the configured prices (D-010). */
    fun cost(
        p: ResolvedProvider,
        inputTokens: Long,
        outputTokens: Long,
        searches: Long,
    ): BigDecimal {
        val inPrice = p.decimal("inputPricePerMillionTokensUsd") ?: throw Problems.unprocessable("ai-price-missing", "Provider pricing is not configured")
        val outPrice = p.decimal("outputPricePerMillionTokensUsd") ?: throw Problems.unprocessable("ai-price-missing", "Provider pricing is not configured")
        val searchPrice =
            if (searches > 0) {
                p.decimal("webSearchPricePerThousandUsd") ?: throw Problems.unprocessable("ai-price-missing", "Web search pricing is not configured")
            } else {
                BigDecimal.ZERO
            }
        val tokens = BigDecimal(inputTokens).multiply(inPrice).add(BigDecimal(outputTokens).multiply(outPrice)).divide(MILLION, Decimals.MC)
        return tokens.add(BigDecimal(searches).multiply(searchPrice).divide(THOUSAND, Decimals.MC)).setScale(SCALE, RoundingMode.UP)
    }

    private fun monthCost(now: java.time.Instant): BigDecimal =
        jdbc
            .sql("select coalesce(sum(coalesce(estimated_cost_usd, reserved_cost_usd)), 0) from research_runs where started_at >= :m")
            .param("m", ts(ResearchService.monthStart(now)))
            .query(BigDecimal::class.java)
            .single()

    private fun todayRequests(now: java.time.Instant): Int =
        jdbc
            .sql("select count(*) from research_runs where started_at >= :d")
            .param("d", ts(ResearchService.dayStart(now)))
            .query(Int::class.java)
            .single()

    private fun fmt(v: BigDecimal) = v.setScale(4, RoundingMode.HALF_EVEN).toPlainString()

    companion object {
        val MILLION = BigDecimal(1_000_000)
        val THOUSAND = BigDecimal(1000)
        val MAX_MONTHLY = BigDecimal(10_000)
        const val MAX_DAILY = 10_000
        const val MIN_OUTPUT = 64
        const val MAX_OUTPUT = 64_000
        const val MIN_INPUT = 1000
        const val MAX_INPUT = 400_000
        const val SCALE = 8
    }
}

/**
 * AI provider diagnostics (FR-004): one minimal text request proves the credential and model.
 * Cited web retrieval is reported UNVERIFIED until a research run actually returns citations.
 */
@Component
class AiProviderTester(
    private val clients: AiClients,
) : ProviderTester {
    override fun supports(type: ProviderType) = type in setOf(ProviderType.ANTHROPIC, ProviderType.OPENAI, ProviderType.GEMINI, ProviderType.OPENROUTER)

    override fun test(provider: ResolvedProvider): ProviderTestResult {
        val retrieval =
            when {
                !clients.retrievalAvailable(provider) -> CapabilityResult("WEB_SEARCH_CITATIONS", "UNSUPPORTED", "Not offered by this adapter or disabled")
                else -> CapabilityResult("WEB_SEARCH_CITATIONS", "UNVERIFIED", "Verified when a research run returns cited sources")
            }
        return try {
            val r = clients.forType(provider.type).complete(provider, AiRequest("Reply with the single word OK.", "Connectivity check.", TEST_TOKENS, false))
            ProviderTestResult(
                TestStatus.OK,
                "Model ${r.model} answered (${r.inputTokens} input / ${r.outputTokens} output tokens)",
                listOf(CapabilityResult("TEXT_GENERATION", "SUPPORTED", "Model ${r.model}"), retrieval),
            )
        } catch (e: AiException) {
            ProviderTestResult(TestStatus.FAILED, "${e.failure}: ${e.message}", listOf(CapabilityResult("TEXT_GENERATION", "UNVERIFIED", e.message), retrieval))
        }
    }

    companion object {
        const val TEST_TOKENS = 64
    }
}
