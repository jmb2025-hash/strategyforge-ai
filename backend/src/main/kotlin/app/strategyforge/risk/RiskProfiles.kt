package app.strategyforge.risk

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.db.uuidOrNull
import app.strategyforge.common.web.ETags
import app.strategyforge.common.web.Problems
import app.strategyforge.common.web.parseUuid
import app.strategyforge.identity.RecentAuth
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Limits configurable at any level. Null means "not set at this level". */
data class RiskLimits(
    val maxTradeValue: BigDecimal? = null,
    val maxTradePercentOfEquity: BigDecimal? = null,
    val maxInstrumentAllocationPercent: BigDecimal? = null,
    val maxAssetClassAllocationPercent: Map<String, BigDecimal>? = null,
    val maxStrategyAllocationPercent: BigDecimal? = null,
    val maxOpenPositions: Int? = null,
    val maxTradesPerMinute: Int? = null,
    val maxTradesPerHour: Int? = null,
    val maxTradesPerDay: Int? = null,
    val cooldownSeconds: Long? = null,
    val maxDailyLossPercent: BigDecimal? = null,
    val maxDrawdownPercent: BigDecimal? = null,
    val maxConsecutiveLosses: Int? = null,
    val maxShortExposurePercent: BigDecimal? = null,
    val shortingAllowed: Boolean? = null,
    val maxQuoteAgeSeconds: Long? = null,
    val maxSpreadPercent: BigDecimal? = null,
    val maxParticipationPercent: BigDecimal? = null,
    val maxPriceDeviationPercent: BigDecimal? = null,
    val maxConsecutiveErrors: Int? = null,
    val allowSymbols: List<String>? = null,
    val denySymbols: List<String>? = null,
)

/** Effective value plus the level that supplied it, for explainable decisions. */
data class Limit<T>(
    val value: T,
    val level: RiskLevel,
)

/** Strictest combination of all applicable levels (FR-091). */
data class EffectiveLimits(
    val maxTradeValue: Limit<BigDecimal>?,
    val maxTradePercentOfEquity: Limit<BigDecimal>?,
    val maxInstrumentAllocationPercent: Limit<BigDecimal>?,
    val maxAssetClassAllocationPercent: Map<String, Limit<BigDecimal>>,
    val maxStrategyAllocationPercent: Limit<BigDecimal>?,
    val maxOpenPositions: Limit<Int>?,
    val maxTradesPerMinute: Limit<Int>?,
    val maxTradesPerHour: Limit<Int>?,
    val maxTradesPerDay: Limit<Int>?,
    val cooldownSeconds: Limit<Long>?,
    val maxDailyLossPercent: Limit<BigDecimal>?,
    val maxDrawdownPercent: Limit<BigDecimal>?,
    val maxConsecutiveLosses: Limit<Int>?,
    val maxShortExposurePercent: Limit<BigDecimal>?,
    val shortingAllowed: Limit<Boolean>?,
    val maxQuoteAgeSeconds: Limit<Long>?,
    val maxSpreadPercent: Limit<BigDecimal>?,
    val maxParticipationPercent: Limit<BigDecimal>?,
    val maxPriceDeviationPercent: Limit<BigDecimal>?,
    val maxConsecutiveErrors: Limit<Int>?,
    val allowSymbols: Limit<Set<String>>?,
    val denySymbols: Set<String>,
) {
    companion object {
        private fun <T : Comparable<T>> min(xs: List<Pair<T?, RiskLevel>>): Limit<T>? = xs.mapNotNull { (v, l) -> v?.let { Limit(it, l) } }.minByOrNull { it.value }

        /** Merges levels ordered from broadest to narrowest; the strictest value wins, ties keep the broader level. */
        fun merge(levels: List<Pair<RiskLevel, RiskLimits>>): EffectiveLimits {
            fun <T : Comparable<T>> pick(f: (RiskLimits) -> T?) = min(levels.map { f(it.second) to it.first })
            val assetKeys =
                levels
                    .flatMap {
                        it.second.maxAssetClassAllocationPercent
                            ?.keys
                            .orEmpty()
                    }.toSet()
            val allow =
                levels
                    .mapNotNull { (l, r) -> r.allowSymbols?.let { l to it.toSet() } }
                    .reduceOrNull { a, b -> b.first to a.second.intersect(b.second) }
                    ?.let { Limit(it.second, it.first) }
            return EffectiveLimits(
                pick { it.maxTradeValue },
                pick { it.maxTradePercentOfEquity },
                pick { it.maxInstrumentAllocationPercent },
                assetKeys.associateWith { k -> min(levels.map { it.second.maxAssetClassAllocationPercent?.get(k) to it.first })!! },
                pick { it.maxStrategyAllocationPercent },
                pick { it.maxOpenPositions },
                pick { it.maxTradesPerMinute },
                pick { it.maxTradesPerHour },
                pick { it.maxTradesPerDay },
                pick { it.cooldownSeconds },
                pick { it.maxDailyLossPercent },
                pick { it.maxDrawdownPercent },
                pick { it.maxConsecutiveLosses },
                pick { it.maxShortExposurePercent },
                // Boolean permissions: any level saying "no" wins.
                levels.mapNotNull { (l, r) -> r.shortingAllowed?.let { Limit(it, l) } }.let { xs -> xs.firstOrNull { !it.value } ?: xs.lastOrNull() },
                pick { it.maxQuoteAgeSeconds },
                pick { it.maxSpreadPercent },
                pick { it.maxParticipationPercent },
                pick { it.maxPriceDeviationPercent },
                pick { it.maxConsecutiveErrors },
                allow,
                levels.flatMap { it.second.denySymbols.orEmpty() }.toSet(),
            )
        }
    }
}

data class RiskProfileView(
    val id: UUID,
    val scope: String,
    val scopeId: UUID?,
    val limits: RiskLimits,
    val updatedAt: Instant,
    val version: Long,
)

/** Published when a profile changes so autonomy authorizations can be invalidated (FR-072). */
data class RiskProfileChanged(
    val scope: String,
    val scopeId: UUID?,
    val loosened: Boolean,
)

@Service
class RiskProfileService(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val audit: AuditService,
    private val clock: Clock,
    private val events: ApplicationEventPublisher,
) {
    fun global(): RiskProfileView = find("GLOBAL", null) ?: error("Global risk profile missing")

    companion object {
        /** Flattens merged limits back into a profile (used to record exactly what a backtest applied). */
        fun toLimits(e: EffectiveLimits) =
            RiskLimits(
                e.maxTradeValue?.value,
                e.maxTradePercentOfEquity?.value,
                e.maxInstrumentAllocationPercent?.value,
                e.maxAssetClassAllocationPercent.mapValues { it.value.value }.ifEmpty { null },
                e.maxStrategyAllocationPercent?.value,
                e.maxOpenPositions?.value,
                e.maxTradesPerMinute?.value,
                e.maxTradesPerHour?.value,
                e.maxTradesPerDay?.value,
                e.cooldownSeconds?.value,
                e.maxDailyLossPercent?.value,
                e.maxDrawdownPercent?.value,
                e.maxConsecutiveLosses?.value,
                e.maxShortExposurePercent?.value,
                e.shortingAllowed?.value,
                e.maxQuoteAgeSeconds?.value,
                e.maxSpreadPercent?.value,
                e.maxParticipationPercent?.value,
                e.maxPriceDeviationPercent?.value,
                e.maxConsecutiveErrors?.value,
                e.allowSymbols?.value?.sorted(),
                e.denySymbols.sorted().ifEmpty { null },
            )
    }

    fun find(
        scope: String,
        scopeId: UUID?,
    ): RiskProfileView? =
        jdbc
            .sql("select * from risk_profiles where scope = :s and scope_id is not distinct from :id")
            .param("s", scope)
            .param("id", scopeId)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    fun limitsFor(
        scope: String,
        scopeId: UUID?,
    ): RiskLimits? = find(scope, scopeId)?.limits

    /** Strictest-wins limits for a portfolio and/or strategy (global always applies). */
    fun effectiveFor(
        portfolioId: UUID?,
        strategyId: UUID?,
    ): EffectiveLimits {
        val levels = mutableListOf(RiskLevel.GLOBAL to global().limits)
        portfolioId?.let { id -> limitsFor("PORTFOLIO", id)?.let { levels += RiskLevel.PORTFOLIO to it } }
        strategyId?.let { id -> limitsFor("STRATEGY", id)?.let { levels += RiskLevel.STRATEGY to it } }
        return EffectiveLimits.merge(levels)
    }

    @Transactional
    fun upsert(
        scope: String,
        scopeId: UUID?,
        limits: RiskLimits,
        ifMatch: String?,
    ): RiskProfileView {
        if (scope !in setOf("GLOBAL", "PORTFOLIO", "STRATEGY")) throw Problems.badRequest("invalid-scope", "scope must be GLOBAL, PORTFOLIO or STRATEGY")
        validate(limits)
        val existing = find(scope, scopeId)
        if (existing != null) ETags.require(ifMatch, existing.version)
        val loosened = existing == null && scope == "GLOBAL" || existing != null && loosens(existing.limits, limits)
        // Risk increases require recent authentication (section 15).
        if (loosened || (existing == null && scope != "GLOBAL" && limits.shortingAllowed == true)) RecentAuth.require(clock.instant(), "risk-increase")
        val now = ts(clock.instant())
        if (existing == null) {
            jdbc
                .sql("insert into risk_profiles(id, scope, scope_id, limits, created_at, updated_at) values (:id, :s, :sid, cast(:l as jsonb), :now, :now)")
                .param("id", UUID.randomUUID())
                .param("s", scope)
                .param("sid", scopeId)
                .param("l", mapper.writeValueAsString(limits))
                .param("now", now)
                .update()
        } else {
            val n =
                jdbc
                    .sql("update risk_profiles set limits = cast(:l as jsonb), updated_at = :now, version = version + 1 where id = :id and version = :v")
                    .param("l", mapper.writeValueAsString(limits))
                    .param("now", now)
                    .param("id", existing.id)
                    .param("v", existing.version)
                    .update()
            if (n == 0) throw Problems.preconditionFailed("Risk profile changed concurrently")
        }
        audit.record(AuditCategory.RISK, "RISK_PROFILE_UPDATED", entityType = "RiskProfile", entityId = "$scope:${scopeId ?: "global"}", details = mapOf("before" to existing?.limits, "after" to limits, "loosened" to loosened))
        events.publishEvent(RiskProfileChanged(scope, scopeId, loosened))
        return find(scope, scopeId)!!
    }

    /** True when any limit becomes less strict or a permission is added. */
    fun loosens(
        old: RiskLimits,
        new: RiskLimits,
    ): Boolean {
        fun <T : Comparable<T>> looser(
            a: T?,
            b: T?,
        ) = (a != null && (b == null || b > a))
        val numeric =
            listOf(
                looser(old.maxTradeValue, new.maxTradeValue),
                looser(old.maxTradePercentOfEquity, new.maxTradePercentOfEquity),
                looser(old.maxInstrumentAllocationPercent, new.maxInstrumentAllocationPercent),
                looser(old.maxStrategyAllocationPercent, new.maxStrategyAllocationPercent),
                looser(old.maxOpenPositions, new.maxOpenPositions),
                looser(old.maxTradesPerMinute, new.maxTradesPerMinute),
                looser(old.maxTradesPerHour, new.maxTradesPerHour),
                looser(old.maxTradesPerDay, new.maxTradesPerDay),
                looser(old.maxDailyLossPercent, new.maxDailyLossPercent),
                looser(old.maxDrawdownPercent, new.maxDrawdownPercent),
                looser(old.maxConsecutiveLosses, new.maxConsecutiveLosses),
                looser(old.maxShortExposurePercent, new.maxShortExposurePercent),
                looser(old.maxQuoteAgeSeconds, new.maxQuoteAgeSeconds),
                looser(old.maxSpreadPercent, new.maxSpreadPercent),
                looser(old.maxParticipationPercent, new.maxParticipationPercent),
                looser(old.maxPriceDeviationPercent, new.maxPriceDeviationPercent),
                looser(old.maxConsecutiveErrors, new.maxConsecutiveErrors),
            ).any { it }
        val cooldown = old.cooldownSeconds != null && (new.cooldownSeconds == null || new.cooldownSeconds < old.cooldownSeconds)
        val shorting = old.shortingAllowed != true && new.shortingAllowed == true
        val assets = old.maxAssetClassAllocationPercent.orEmpty().any { (k, v) -> new.maxAssetClassAllocationPercent?.get(k)?.let { it > v } ?: true }
        val deny =
            !(
                new.denySymbols
                    .orEmpty()
                    .toSet()
                    .containsAll(old.denySymbols.orEmpty())
            )
        val allow = old.allowSymbols != null && (new.allowSymbols == null || !old.allowSymbols.toSet().containsAll(new.allowSymbols))
        return numeric || cooldown || shorting || assets || deny || allow
    }

    fun validate(l: RiskLimits) {
        fun pct(
            name: String,
            v: BigDecimal?,
        ) {
            if (v != null && (v <= BigDecimal.ZERO || v > BigDecimal(100))) throw Problems.badRequest("invalid-limit", "$name must be > 0 and <= 100")
        }
        pct("maxTradePercentOfEquity", l.maxTradePercentOfEquity)
        pct("maxInstrumentAllocationPercent", l.maxInstrumentAllocationPercent)
        pct("maxStrategyAllocationPercent", l.maxStrategyAllocationPercent)
        pct("maxDailyLossPercent", l.maxDailyLossPercent)
        pct("maxDrawdownPercent", l.maxDrawdownPercent)
        pct("maxShortExposurePercent", l.maxShortExposurePercent)
        pct("maxSpreadPercent", l.maxSpreadPercent)
        pct("maxParticipationPercent", l.maxParticipationPercent)
        pct("maxPriceDeviationPercent", l.maxPriceDeviationPercent)
        l.maxAssetClassAllocationPercent?.forEach { (k, v) ->
            if (k !in setOf("US_EQUITY", "CRYPTO")) throw Problems.badRequest("invalid-limit", "Unknown asset class $k")
            pct("maxAssetClassAllocationPercent.$k", v)
        }
        listOf(l.maxOpenPositions, l.maxTradesPerMinute, l.maxTradesPerHour, l.maxTradesPerDay, l.maxConsecutiveLosses, l.maxConsecutiveErrors).forEach {
            if (it != null && (it < 1 || it > 100_000)) throw Problems.badRequest("invalid-limit", "Count limits must be 1-100000")
        }
        if (l.maxTradeValue != null && l.maxTradeValue <= BigDecimal.ZERO) throw Problems.badRequest("invalid-limit", "maxTradeValue must be positive")
        if (l.cooldownSeconds != null && l.cooldownSeconds !in 0..86_400) throw Problems.badRequest("invalid-limit", "cooldownSeconds must be 0-86400")
        if (l.maxQuoteAgeSeconds != null && l.maxQuoteAgeSeconds !in 1..3600) throw Problems.badRequest("invalid-limit", "maxQuoteAgeSeconds must be 1-3600")
        (l.allowSymbols.orEmpty() + l.denySymbols.orEmpty()).forEach { if (!Regex("^[A-Z][A-Z0-9.]{0,9}(-USD)?$").matches(it)) throw Problems.badRequest("invalid-symbol", "Invalid symbol $it") }
    }

    private fun map(rs: java.sql.ResultSet) = RiskProfileView(rs.uuid("id"), rs.getString("scope"), rs.uuidOrNull("scope_id"), mapper.readValue(rs.getString("limits"), RiskLimits::class.java), rs.instant("updated_at"), rs.getLong("version"))
}

@RestController
@RequestMapping("/v1/risk")
@Tag(name = "Risk")
class RiskController(
    private val profiles: RiskProfileService,
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
) {
    @GetMapping("/profiles/global")
    fun global(): ResponseEntity<RiskProfileView> = profiles.global().let { ETags.ok(it, it.version) }

    @PutMapping("/profiles/global")
    fun putGlobal(
        @RequestBody limits: RiskLimits,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ) = profiles.upsert("GLOBAL", null, limits, ifMatch).let { ETags.ok(it, it.version) }

    @GetMapping("/profiles/{scope}/{id}")
    fun get(
        @PathVariable scope: String,
        @PathVariable id: String,
    ): ResponseEntity<RiskProfileView> = (profiles.find(scope.uppercase(), parseUuid(id)) ?: throw Problems.notFound("Risk profile", "$scope/$id")).let { ETags.ok(it, it.version) }

    @PutMapping("/profiles/{scope}/{id}")
    fun put(
        @PathVariable scope: String,
        @PathVariable id: String,
        @RequestBody limits: RiskLimits,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ) = profiles.upsert(scope.uppercase(), parseUuid(id), limits, ifMatch).let { ETags.ok(it, it.version) }

    /** Recorded risk evaluations with inputs, rule results and decisions (FR-093). */
    @GetMapping("/evaluations/{id}")
    fun evaluation(
        @PathVariable id: String,
    ): Map<String, Any?> =
        jdbc
            .sql("select * from risk_evaluations where id = :id")
            .param("id", parseUuid(id))
            .query { rs, _ ->
                mapOf(
                    "id" to rs.uuid("id"),
                    "portfolioId" to rs.uuid("portfolio_id"),
                    "instrumentId" to rs.uuid("instrument_id"),
                    "strategyId" to rs.uuidOrNull("strategy_id"),
                    "source" to rs.getString("source"),
                    "intent" to mapper.readTree(rs.getString("intent")),
                    "inputs" to mapper.readTree(rs.getString("inputs")),
                    "ruleResults" to mapper.readTree(rs.getString("rule_results")),
                    "decision" to rs.getString("decision"),
                    "marketTime" to rs.instant("market_time"),
                    "createdAt" to rs.instant("created_at"),
                    "durationMs" to rs.getLong("duration_ms"),
                )
            }.optional()
            .orElseThrow { Problems.notFound("Risk evaluation", id) }

    @GetMapping("/evaluations")
    fun evaluations(
        @org.springframework.web.bind.annotation.RequestParam portfolioId: String,
    ): List<Map<String, Any?>> =
        jdbc
            .sql("select id, source, decision, blocking_rules, market_time, created_at from risk_evaluations where portfolio_id = :p order by created_at desc limit 200")
            .param("p", parseUuid(portfolioId))
            .query { rs, _ ->
                mapOf("id" to rs.uuid("id"), "source" to rs.getString("source"), "decision" to rs.getString("decision"), "blockingRules" to (rs.getArray("blocking_rules").array as Array<*>).toList(), "marketTime" to rs.instant("market_time"), "createdAt" to rs.instant("created_at"))
            }.list()
}
