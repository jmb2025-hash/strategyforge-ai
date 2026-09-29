@file:UseSerializers(BigDecimalSerializer::class, UUIDSerializer::class)

package app.strategyforge.engine.risk

import app.strategyforge.engine.common.BigDecimalSerializer
import app.strategyforge.engine.common.UUIDSerializer
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderType
import app.strategyforge.engine.execution.TimeInForce
import app.strategyforge.engine.market.Instrument
import app.strategyforge.engine.market.QuoteVerification
import app.strategyforge.engine.portfolio.Portfolio
import app.strategyforge.engine.portfolio.PortfolioSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class OrderSource { MANUAL, RECOMMENDATION, AUTONOMOUS, EMERGENCY_CLOSE, FORCED_COVER, SYSTEM }

@Serializable
data class OrderIntent(
    val portfolioId: UUID,
    val instrumentId: UUID,
    val side: OrderSide,
    val orderType: OrderType,
    val quantity: BigDecimal,
    val limitPrice: BigDecimal?,
    val stopPrice: BigDecimal?,
    val timeInForce: TimeInForce,
    val source: OrderSource,
    val strategyId: UUID? = null,
    val strategyVersionId: UUID? = null,
)

enum class RuleOutcome { PASS, FAIL, UNVERIFIED, NOT_APPLICABLE }

/** Level at which a limit was defined; the strictest applicable level wins (FR-091). */
enum class RiskLevel { SYSTEM, GLOBAL, PORTFOLIO, STRATEGY, ORDER }

@Serializable
data class RuleResult(
    val rule: String,
    val family: String,
    val outcome: RuleOutcome,
    val level: RiskLevel,
    val limit: String? = null,
    val actual: String? = null,
    val detail: String,
)

data class OpenOrderInfo(
    val id: UUID,
    val instrumentId: UUID,
    val side: OrderSide,
    val remaining: BigDecimal,
    val reservedRemaining: BigDecimal,
)

/** Everything a rule may consult. Built once per evaluation from verified state only. */
class RiskContext(
    val intent: OrderIntent,
    val portfolio: Portfolio,
    val summary: PortfolioSummary,
    val instrument: Instrument,
    val quote: QuoteVerification,
    val estimatedPrice: BigDecimal?,
    val openOrders: List<OpenOrderInfo>,
    val now: Instant,
    val attributes: MutableMap<String, Any?> = mutableMapOf(),
    val limits: EffectiveLimits? = null,
    val metrics: RiskMetrics? = null,
)

/** Point-in-time measurements used by limit rules; all derived from the ledger, orders and verified data. */
data class RiskMetrics(
    val equity: BigDecimal,
    val dayStartEquity: BigDecimal,
    val peakEquity: BigDecimal,
    val ordersLastMinute: Int,
    val ordersLastHour: Int,
    val ordersToday: Int,
    val lastOrderForInstrumentAt: Instant?,
    val consecutiveLosses: Int,
    val openPositions: Int,
    val instrumentValue: BigDecimal,
    val assetClassValue: BigDecimal,
    val shortExposure: BigDecimal,
    val averageDailyVolume: BigDecimal?,
    val previousClose: BigDecimal?,
    val pauseAll: Boolean,
    val preventNewPositions: Boolean,
    val strategyStatus: String?,
    val strategyAllocationPercent: BigDecimal?,
    val strategyExposure: BigDecimal,
    val strategyOpenPositions: Int,
    val strategyOrdersToday: Int,
    val strategyConsecutiveLosses: Int,
    val strategyLimits: StrategyLimitView?,
    val clockDriftMs: Long,
)

data class StrategyLimitView(
    val maxOpenPositions: Int,
    val maxDailyTrades: Int,
    val maxConsecutiveLosses: Int?,
    val allowShort: Boolean,
)

/**
 * A deterministic risk rule. [blocksReducing] marks rules that also apply to position-reducing
 * orders (integrity rules such as holdings). Any other rule only guards risk-increasing orders.
 */
interface RiskRule {
    val name: String
    val family: String
    val blocksReducing: Boolean get() = false

    fun evaluate(ctx: RiskContext): RuleResult

    fun pass(
        detail: String,
        level: RiskLevel = RiskLevel.PORTFOLIO,
        limit: Any? = null,
        actual: Any? = null,
    ) = RuleResult(name, family, RuleOutcome.PASS, level, limit?.toString(), actual?.toString(), detail)

    fun fail(
        detail: String,
        level: RiskLevel = RiskLevel.PORTFOLIO,
        limit: Any? = null,
        actual: Any? = null,
    ) = RuleResult(name, family, RuleOutcome.FAIL, level, limit?.toString(), actual?.toString(), detail)

    fun unverified(
        detail: String,
        level: RiskLevel = RiskLevel.SYSTEM,
    ) = RuleResult(name, family, RuleOutcome.UNVERIFIED, level, null, null, detail)

    fun na(detail: String = "Not applicable") = RuleResult(name, family, RuleOutcome.NOT_APPLICABLE, RiskLevel.ORDER, null, null, detail)
}

data class RiskDecision(
    val id: UUID,
    val allowed: Boolean,
    val results: List<RuleResult>,
    val blocking: List<String>,
) {
    val reasons: List<String> get() = results.filter { it.rule in blocking }.map { "${it.rule}: ${it.detail}" }
}

/** Limits configurable at any level. Null means "not set at this level". */
@Serializable
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
