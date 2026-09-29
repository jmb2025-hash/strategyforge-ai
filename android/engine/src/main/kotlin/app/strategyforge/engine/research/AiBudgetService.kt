package app.strategyforge.engine.research

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.RecentAuth
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.decOrNull
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.int
import app.strategyforge.engine.money.Decimals
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

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

data class Reservation(
    val maxOutputTokens: Int,
    val worstCaseUsd: BigDecimal,
)

/**
 * Token, request and cost ceilings (FR-037). The worst-case cost of a call (every input character
 * counted as half a token, the full output ceiling, every allowed search) is reserved before the
 * network call; a call that could exceed any ceiling is refused (D-010, fail closed). Free-tier
 * providers are priced at 0, so only the request and token ceilings bind for them.
 */
class AiBudgetService(
    private val db: Db,
    private val audit: AuditService,
    private val auth: RecentAuth,
    private val clock: Clock,
) {
    fun get(): AiBudget {
        val now = clock.instant()
        return db
            .sql("select * from ai_budget")
            .single { rs ->
                AiBudget(
                    rs.dec("monthly_cost_limit_usd"),
                    rs.int("daily_request_limit"),
                    rs.int("max_output_tokens"),
                    rs.int("max_input_chars"),
                    monthCost(now),
                    todayRequests(now),
                    rs.instant("updated_at"),
                    rs.long("version") ?: 0L,
                )
            }
    }

    fun update(
        req: AiBudgetUpdate,
        expectedVersion: Long,
    ): AiBudget {
        val cur = get()
        if (cur.version != expectedVersion) throw Problems.preconditionFailed("The AI budget changed; reload and retry")
        if (req.monthlyCostLimitUsd < BigDecimal.ZERO || req.monthlyCostLimitUsd > MAX_MONTHLY) throw Problems.badRequest("invalid-budget", "monthlyCostLimitUsd must be 0-$MAX_MONTHLY")
        if (req.dailyRequestLimit !in 0..MAX_DAILY) throw Problems.badRequest("invalid-budget", "dailyRequestLimit must be 0-$MAX_DAILY")
        if (req.maxOutputTokens !in MIN_OUTPUT..MAX_OUTPUT) throw Problems.badRequest("invalid-budget", "maxOutputTokens must be $MIN_OUTPUT-$MAX_OUTPUT")
        if (req.maxInputChars !in MIN_INPUT..MAX_INPUT) throw Problems.badRequest("invalid-budget", "maxInputChars must be $MIN_INPUT-$MAX_INPUT")
        val loosened =
            req.monthlyCostLimitUsd > cur.monthlyCostLimitUsd || req.dailyRequestLimit > cur.dailyRequestLimit ||
                req.maxOutputTokens > cur.maxOutputTokens || req.maxInputChars > cur.maxInputChars
        if (loosened) auth.require("raise the AI budget")
        db
            .sql(
                "update ai_budget set monthly_cost_limit_usd = :m, daily_request_limit = :d, max_output_tokens = :o, max_input_chars = :i, updated_at = :now, version = version + 1",
            ).param("m", Decimals.money(req.monthlyCostLimitUsd))
            .param("d", req.dailyRequestLimit)
            .param("o", req.maxOutputTokens)
            .param("i", req.maxInputChars)
            .param("now", clock.instant())
            .update()
        audit.record(
            AuditCategory.SETTINGS,
            "AI_BUDGET_UPDATED",
            details = mapOf("from" to mapOf("monthlyCostLimitUsd" to cur.monthlyCostLimitUsd, "dailyRequestLimit" to cur.dailyRequestLimit, "maxOutputTokens" to cur.maxOutputTokens, "maxInputChars" to cur.maxInputChars), "to" to mapOf("monthlyCostLimitUsd" to req.monthlyCostLimitUsd, "dailyRequestLimit" to req.dailyRequestLimit, "maxOutputTokens" to req.maxOutputTokens, "maxInputChars" to req.maxInputChars), "loosened" to loosened),
        )
        return get()
    }

    /** Runs inside the transaction that records the run (the engine is single-threaded, so reservations never race). */
    fun reserve(
        s: ResearchSession,
        p: AiProviderConfig,
        request: AiRequest,
    ): Reservation {
        val b = get()
        val maxOut = minOf(b.maxOutputTokens, p.int("maxOutputTokens", b.maxOutputTokens))
        val chars = request.system.length + request.prompt.length
        val worst = worstCase(p, chars, maxOut, if (request.retrieval) request.maxSearches else 0)
        val sessionRuns = runsCost("session_id = :s") { it.param("s", s.id) }
        val sessionCost = sessionRuns.fold(BigDecimal.ZERO, BigDecimal::add)
        val refusal =
            when {
                chars > b.maxInputChars -> "The request is $chars characters; the ceiling is ${b.maxInputChars}"
                sessionRuns.size >= s.maxRequests -> "This session already used its ${s.maxRequests} request(s)"
                sessionCost.add(worst) > s.maxCostUsd -> "Worst-case cost ${fmt(worst)} USD would exceed the session ceiling (${fmt(sessionCost)} of ${fmt(s.maxCostUsd)} USD used)"
                b.todayRequests >= b.dailyRequestLimit -> "The daily AI request limit (${b.dailyRequestLimit}) has been reached"
                b.monthCostUsd.add(worst) > b.monthlyCostLimitUsd -> "Worst-case cost ${fmt(worst)} USD would exceed the monthly AI budget (${fmt(b.monthCostUsd)} of ${fmt(b.monthlyCostLimitUsd)} USD used)"
                else -> null
            }
        if (refusal != null) throw Problems.unprocessable(REFUSED, refusal, mapOf("worstCaseUsd" to worst, "sessionId" to s.id))
        return Reservation(maxOut, worst)
    }

    /** Records a refusal after the reserving transaction rolled back, so the audit trail keeps it. */
    fun recordRefusal(e: EngineException) {
        audit.record(AuditCategory.RESEARCH, "AI_BUDGET_REFUSED", AuditOutcome.BLOCKED, "ResearchSession", e.properties["sessionId"], mapOf("reason" to e.message, "worstCaseUsd" to e.properties["worstCaseUsd"]))
    }

    fun worstCase(
        p: AiProviderConfig,
        chars: Int,
        maxOutputTokens: Int,
        searches: Int,
    ): BigDecimal = cost(p, ((chars + 1) / 2).toLong(), maxOutputTokens.toLong(), searches.toLong())

    /** Estimated USD cost from recorded usage and the configured prices (D-010). */
    fun cost(
        p: AiProviderConfig,
        inputTokens: Long,
        outputTokens: Long,
        searches: Long,
    ): BigDecimal {
        val inPrice = p.decimal("inputPricePerMillionTokensUsd") ?: throw Problems.unprocessable("ai-price-missing", "Provider pricing is not configured")
        val outPrice = p.decimal("outputPricePerMillionTokensUsd") ?: throw Problems.unprocessable("ai-price-missing", "Provider pricing is not configured")
        val searchPrice =
            if (searches > 0) {
                p.webSearchPrice() ?: throw Problems.unprocessable("ai-price-missing", "Web search pricing is not configured")
            } else {
                BigDecimal.ZERO
            }
        val tokens = BigDecimal(inputTokens).multiply(inPrice).add(BigDecimal(outputTokens).multiply(outPrice)).divide(MILLION, Decimals.MC)
        return tokens.add(BigDecimal(searches).multiply(searchPrice).divide(THOUSAND, Decimals.MC)).setScale(SCALE, RoundingMode.UP)
    }

    /** Charge per run: the estimate when known, else the (conservative) reservation. Summed in Kotlin. */
    private fun runsCost(
        where: String,
        bind: (app.strategyforge.engine.db.Statement) -> app.strategyforge.engine.db.Statement,
    ): List<BigDecimal> = bind(db.sql("select estimated_cost_usd, reserved_cost_usd from research_runs where $where")).list { it.decOrNull("estimated_cost_usd") ?: it.dec("reserved_cost_usd") }

    private fun monthCost(now: Instant): BigDecimal = runsCost("started_at >= :m") { it.param("m", monthStart(now)) }.fold(BigDecimal.ZERO, BigDecimal::add)

    private fun todayRequests(now: Instant): Int = db.sql("select count(*) from research_runs where started_at >= :d").param("d", dayStart(now)).int()

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
        const val REFUSED = "ai-budget-exceeded"

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
