package app.strategyforge.engine.risk

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineEvents
import app.strategyforge.engine.common.EngineJson
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.RecentAuth
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.db.uuidOrNull
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

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

class RiskProfileService(
    private val db: Db,
    private val audit: AuditService,
    private val clock: Clock,
    private val events: EngineEvents,
    private val auth: RecentAuth,
) {
    fun global(): RiskProfileView = find("GLOBAL", null) ?: error("Global risk profile missing")

    /** Reads a strategy version's definition; set by the engine once strategies exist (D-063). */
    @Volatile var definitions: StrategyDefinitionLookup? = null

    /**
     * The base limits for an order (D-063): the global profile, except for a running plan's own orders
     * in the portfolio it runs in, where the plan's declared sizing and loss limits replace the
     * global ones (see [planBase]). Returns the level to report and the limits.
     */
    fun baseFor(
        portfolioId: UUID?,
        strategyId: UUID?,
    ): Pair<RiskLevel, RiskLimits> {
        val g = global().limits
        if (portfolioId == null || strategyId == null) return RiskLevel.GLOBAL to g
        val version =
            db
                .sql("select version_id from strategy_activations where strategy_id = :s and portfolio_id = :p and status = 'ACTIVE'")
                .param("s", strategyId)
                .param("p", portfolioId)
                .firstOrNull { it.uuidOrNull("version_id") } ?: return RiskLevel.GLOBAL to g
        val def = runCatching { definitions?.definition(version) }.getOrNull() ?: return RiskLevel.GLOBAL to g
        return RiskLevel.STRATEGY to planBase(g, def.risk, def.assetClass)
    }

    companion object {
        private val HUNDRED = BigDecimal(100)

        /**
         * The global limits with a plan's own sizing and loss limits in place of the global ones
         * (D-063): its largest position (trade size and single-instrument share), its whole asset class,
         * its daily loss, drawdown, open-position and losing-streak caps. Data-quality, rate and
         * emergency limits stay global.
         */
        fun planBase(
            global: RiskLimits,
            plan: app.strategyforge.engine.strategy.StrategyRiskLimits,
            assetClass: app.strategyforge.engine.market.AssetClass,
        ): RiskLimits {
            val position = plan.maximumPositionPercent ?: HUNDRED
            return global.copy(
                maxTradePercentOfEquity = position,
                maxInstrumentAllocationPercent = position,
                maxAssetClassAllocationPercent = global.maxAssetClassAllocationPercent.orEmpty() + (assetClass.name to HUNDRED),
                maxDailyLossPercent = plan.maximumDailyLossPercent,
                maxDrawdownPercent = plan.maximumDrawdownPercent ?: global.maxDrawdownPercent,
                maxOpenPositions = plan.maximumOpenPositions,
                maxConsecutiveLosses = plan.maximumConsecutiveLosses ?: global.maxConsecutiveLosses,
            )
        }

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
        db
            .sql("select * from risk_profiles where scope = :s and scope_id is :id")
            .param("s", scope)
            .param("id", scopeId)
            .firstOrNull { rs -> map(rs) }

    fun limitsFor(
        scope: String,
        scopeId: UUID?,
    ): RiskLimits? = find(scope, scopeId)?.limits

    /** Strictest-wins limits for a portfolio and/or strategy (global always applies). */
    fun effectiveFor(
        portfolioId: UUID?,
        strategyId: UUID?,
    ): EffectiveLimits {
        val levels = mutableListOf(baseFor(portfolioId, strategyId))
        portfolioId?.let { id -> limitsFor("PORTFOLIO", id)?.let { levels += RiskLevel.PORTFOLIO to it } }
        strategyId?.let { id -> limitsFor("STRATEGY", id)?.let { levels += RiskLevel.STRATEGY to it } }
        return EffectiveLimits.merge(levels)
    }

    fun upsert(
        scope: String,
        scopeId: UUID?,
        limits: RiskLimits,
        expectedVersion: Long?,
    ): RiskProfileView = db.tx { upsertInTx(scope, scopeId, limits, expectedVersion) }

    private fun upsertInTx(
        scope: String,
        scopeId: UUID?,
        limits: RiskLimits,
        expectedVersion: Long?,
    ): RiskProfileView {
        if (scope !in setOf("GLOBAL", "PORTFOLIO", "STRATEGY")) throw Problems.badRequest("invalid-scope", "scope must be GLOBAL, PORTFOLIO or STRATEGY")
        validate(limits)
        val existing = find(scope, scopeId)
        if (existing != null && expectedVersion != null && existing.version != expectedVersion) throw Problems.preconditionFailed("Risk profile changed; reload and retry")
        val loosened = existing == null && scope == "GLOBAL" || existing != null && loosens(existing.limits, limits)
        // Risk increases require recent authentication (section 15).
        if (loosened || (existing == null && scope != "GLOBAL" && limits.shortingAllowed == true)) auth.require("risk-increase")
        val now = (clock.instant())
        if (existing == null) {
            db
                .sql("insert into risk_profiles(id, scope, scope_id, limits, created_at, updated_at) values (:id, :s, :sid, :l, :now, :now)")
                .param("id", UUID.randomUUID())
                .param("s", scope)
                .param("sid", scopeId)
                .param("l", EngineJson.encodeToString(RiskLimits.serializer(), limits))
                .param("now", now)
                .update()
        } else {
            val n =
                db
                    .sql("update risk_profiles set limits = :l, updated_at = :now, version = version + 1 where id = :id and version = :v")
                    .param("l", EngineJson.encodeToString(RiskLimits.serializer(), limits))
                    .param("now", now)
                    .param("id", existing.id)
                    .param("v", existing.version)
                    .update()
            if (n == 0) throw Problems.preconditionFailed("Risk profile changed concurrently")
        }
        audit.record(AuditCategory.RISK, "RISK_PROFILE_UPDATED", entityType = "RiskProfile", entityId = "$scope:${scopeId ?: "global"}", details = mapOf("before" to existing?.limits?.let { EngineJson.encodeToJsonElement(RiskLimits.serializer(), it) }, "after" to EngineJson.encodeToJsonElement(RiskLimits.serializer(), limits), "loosened" to loosened))
        events.publish(RiskProfileChanged(scope, scopeId, loosened))
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

    private fun map(rs: Row) = RiskProfileView(rs.uuid("id"), rs.str("scope"), rs.uuidOrNull("scope_id"), EngineJson.decodeFromString(RiskLimits.serializer(), rs.str("limits")), rs.instant("updated_at"), rs.long("version")!!)
}

/** Recorded risk evaluations with inputs, rule results and decisions (FR-093). */
class RiskEvaluationQueries(
    private val db: Db,
) {
    fun evaluation(id: UUID): Map<String, Any?> =
        db
            .sql("select * from risk_evaluations where id = :id")
            .param("id", id)
            .firstOrNull { rs ->
                mapOf(
                    "id" to rs.uuid("id"),
                    "portfolioId" to rs.uuid("portfolio_id"),
                    "instrumentId" to rs.uuid("instrument_id"),
                    "strategyId" to rs.uuidOrNull("strategy_id"),
                    "source" to rs.str("source"),
                    "intent" to EngineJson.parseToJsonElement(rs.str("intent")),
                    "inputs" to EngineJson.parseToJsonElement(rs.str("inputs")),
                    "ruleResults" to EngineJson.parseToJsonElement(rs.str("rule_results")),
                    "decision" to rs.str("decision"),
                    "marketTime" to rs.instant("market_time"),
                    "createdAt" to rs.instant("created_at"),
                    "durationMs" to rs.long("duration_ms"),
                )
            } ?: throw Problems.notFound("Risk evaluation", id)

    fun evaluations(portfolioId: UUID): List<Map<String, Any?>> =
        db
            .sql("select id, source, decision, blocking_rules, market_time, created_at from risk_evaluations where portfolio_id = :p order by created_at desc limit 200")
            .param("p", portfolioId)
            .list { rs ->
                mapOf(
                    "id" to rs.uuid("id"),
                    "source" to rs.str("source"),
                    "decision" to rs.str("decision"),
                    "blockingRules" to EngineJson.decodeFromString(ListSerializer(String.serializer()), rs.str("blocking_rules")),
                    "marketTime" to rs.instant("market_time"),
                    "createdAt" to rs.instant("created_at"),
                )
            }
}
