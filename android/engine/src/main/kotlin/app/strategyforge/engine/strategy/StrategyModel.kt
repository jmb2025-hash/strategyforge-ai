package app.strategyforge.engine.strategy

import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.Timeframe
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

enum class IndicatorType {
    SMA,
    EMA,
    RSI,
    MACD,
    ATR,
    BOLLINGER_BANDS,

    // Chart structure and volume (D-036): all computed from past bars only.
    HIGHEST,
    LOWEST,
    SWING_HIGH,
    SWING_LOW,
    RELATIVE_VOLUME,

    // Candlestick patterns (D-036): 1 on the bar that completes the pattern, otherwise 0.
    BULLISH_ENGULFING,
    BEARISH_ENGULFING,
    HAMMER,
    SHOOTING_STAR,
    DOJI,
    MORNING_STAR,
    EVENING_STAR,

    // Calendar levels, VWAP, Fibonacci and volume profile (D-042): all computed from past bars only.
    PERIOD_LEVELS,
    VWAP,
    ANCHORED_VWAP,
    FIBONACCI,
    VOLUME_PROFILE,

    // Perpetual-futures context for crypto (D-044): from a futures exchange, lined up with the bars.
    OPEN_INTEREST,
    FUNDING_RATE,
    CVD,

    // Levels (D-047): a level between two others, round numbers, and untested volume-profile POCs.
    LEVEL,
    ROUND_NUMBER,
    NAKED_POC,
    ;

    /** Pattern detectors take no period. */
    val pattern: Boolean get() = this in PATTERNS

    companion object {
        val PATTERNS = setOf(BULLISH_ENGULFING, BEARISH_ENGULFING, HAMMER, SHOOTING_STAR, DOJI, MORNING_STAR, EVENING_STAR)

        /** Types that need futures data and are only available to crypto strategies (D-044). */
        val DERIVATIVES = setOf(OPEN_INTEREST, FUNDING_RATE, CVD)

        /** Types that can be computed on daily, weekly or monthly bars inside a faster strategy. */
        val HIGHER_TIMEFRAME_CAPABLE =
            setOf(SMA, EMA, RSI, ATR, MACD, BOLLINGER_BANDS, HIGHEST, LOWEST, SWING_HIGH, SWING_LOW, RELATIVE_VOLUME, FIBONACCI) + PATTERNS
    }
}

/**
 * Periods: fixed 30-minute, 1-hour and 4-hour buckets (D-047; crypto from midnight UTC, US stocks from
 * the 09:30 New York open) and calendar days, weeks and months (D-042; crypto UTC days, US stocks New
 * York days, weeks start on Monday).
 */
enum class Anchor(
    val code: String,
    /** Length of a fixed bucket; null for calendar periods. */
    val fixed: java.time.Duration? = null,
) {
    M30("30m", java.time.Duration.ofMinutes(30)),
    H1("1h", java.time.Duration.ofHours(1)),
    H4("4h", java.time.Duration.ofHours(4)),
    DAY("1d"),
    WEEK("1w"),
    MONTH("1M"),
    ;

    /** Approximate length, for comparing with a strategy's bar length. */
    val nominal: java.time.Duration
        get() =
            fixed ?: when (this) {
                DAY -> java.time.Duration.ofDays(1)
                WEEK -> java.time.Duration.ofDays(7)
                else -> java.time.Duration.ofDays(28)
            }

    companion object {
        fun ofCode(code: String): Anchor? = entries.firstOrNull { it.code == code }
    }
}

enum class AnchorPoint { LOWEST_LOW, HIGHEST_HIGH }

/** Retracement ratios offered by FIBONACCI, by component name. */
val FIB_LEVELS: Map<String, BigDecimal> =
    linkedMapOf(
        "f236" to BigDecimal("0.236"),
        "f382" to BigDecimal("0.382"),
        "f500" to BigDecimal("0.5"),
        "f618" to BigDecimal("0.618"),
        "f660" to BigDecimal("0.66"),
        "f786" to BigDecimal("0.786"),
    )

enum class Comparison { GT, GTE, LT, LTE, EQ, CROSSES_ABOVE, CROSSES_BELOW }

/** ALL, ANY, or AT_LEAST a group's [RuleGroup.count] of its conditions (D-047, confluence). */
enum class GroupOperator { ALL, ANY, AT_LEAST }

enum class SizingMethod { FIXED_CASH, PERCENT_OF_EQUITY, FIXED_QUANTITY, RISK_PERCENT }

enum class Direction { LONG_ONLY, SHORT_ONLY, BOTH }

enum class PriceField { OPEN, HIGH, LOW, CLOSE, VOLUME }

data class IndicatorSpec(
    val id: String,
    val type: IndicatorType,
    val period: Int?,
    val fastPeriod: Int?,
    val slowPeriod: Int?,
    val signalPeriod: Int?,
    val standardDeviations: BigDecimal?,
    val source: PriceField,
    /** Compute on daily, weekly or monthly bars built from the strategy's bars (D-042). */
    val timeframe: Anchor? = null,
    /** The calendar period for PERIOD_LEVELS, VWAP and VOLUME_PROFILE (D-042). */
    val anchor: Anchor? = null,
    val anchorPoint: AnchorPoint? = null,
    /** LEVEL: from + ratio x (to - from) (D-047). */
    val from: Operand? = null,
    val to: Operand? = null,
    val ratio: BigDecimal? = null,
    /** ROUND_NUMBER: spacing of the round numbers. */
    val step: BigDecimal? = null,
) {
    /**
     * Strategy bars required before every value is available, given the strategy's own timeframe
     * and asset class (higher-timeframe and calendar indicators need whole periods of bars).
     */
    fun lookback(
        base: app.strategyforge.engine.market.Timeframe,
        assetClass: app.strategyforge.engine.market.AssetClass,
    ): Int {
        val inner = innerLookback()
        timeframe?.let { return (inner + 1) * Periods.barsPer(it, base, assetClass) + 1 }
        return when (type) {
            IndicatorType.PERIOD_LEVELS -> 2 * Periods.barsPer(anchor!!, base, assetClass) + 1
            IndicatorType.VWAP -> Periods.barsPer(anchor!!, base, assetClass) + 1
            IndicatorType.VOLUME_PROFILE -> if (anchor != null) 2 * Periods.barsPer(anchor, base, assetClass) + 1 else inner
            IndicatorType.NAKED_POC -> (period!! + 1) * Periods.barsPer(anchor!!, base, assetClass) + 1
            else -> inner
        }
    }

    /** Bars required before the indicator produces a stable value, on the bars it is computed on. */
    fun lookback(): Int = innerLookback()

    private fun innerLookback(): Int =
        when (type) {
            IndicatorType.SMA, IndicatorType.BOLLINGER_BANDS -> period!!
            IndicatorType.EMA -> period!! * 3
            IndicatorType.RSI, IndicatorType.ATR -> period!! * 3 + 1
            IndicatorType.MACD -> slowPeriod!! * 3 + signalPeriod!!
            IndicatorType.HIGHEST, IndicatorType.LOWEST, IndicatorType.RELATIVE_VOLUME -> period!! + 1
            // A swing point needs [period] bars on each side; allow room to find one.
            IndicatorType.SWING_HIGH, IndicatorType.SWING_LOW -> period!! * 6 + 1
            IndicatorType.MORNING_STAR, IndicatorType.EVENING_STAR -> 3
            IndicatorType.BULLISH_ENGULFING, IndicatorType.BEARISH_ENGULFING -> 2
            IndicatorType.HAMMER, IndicatorType.SHOOTING_STAR, IndicatorType.DOJI -> 1
            IndicatorType.ANCHORED_VWAP, IndicatorType.FIBONACCI -> period!! + 1
            IndicatorType.VOLUME_PROFILE -> (period ?: 0) + 1
            IndicatorType.PERIOD_LEVELS, IndicatorType.VWAP -> 1
            IndicatorType.OPEN_INTEREST -> period!! + 1
            IndicatorType.CVD -> period!!
            IndicatorType.FUNDING_RATE -> 1
            IndicatorType.LEVEL, IndicatorType.ROUND_NUMBER -> 1
            IndicatorType.NAKED_POC -> 1
        }

    fun components(): Set<String> =
        when (type) {
            IndicatorType.MACD -> setOf("value", "signal", "histogram")
            IndicatorType.BOLLINGER_BANDS -> setOf("upper", "middle", "lower")
            IndicatorType.PERIOD_LEVELS -> PERIOD_COMPONENTS
            IndicatorType.FIBONACCI -> FIB_LEVELS.keys + setOf("high", "low", "trend")
            IndicatorType.VOLUME_PROFILE -> setOf("poc", "vah", "val")
            IndicatorType.OPEN_INTEREST -> setOf("value", "change")
            IndicatorType.FUNDING_RATE -> setOf("value", "annualized")
            IndicatorType.CVD -> setOf("value", "delta")
            IndicatorType.ROUND_NUMBER, IndicatorType.NAKED_POC -> setOf("above", "below")
            else -> setOf("value")
        }

    companion object {
        val PERIOD_COMPONENTS = setOf("open", "high", "low", "prevOpen", "prevHigh", "prevLow", "prevClose", "prevEq")
    }
}

sealed interface Operand {
    data class Constant(
        val value: BigDecimal,
    ) : Operand

    data class Price(
        val field: PriceField,
    ) : Operand

    data class IndicatorRef(
        val id: String,
        val component: String,
    ) : Operand
}

sealed interface RuleNode

data class Condition(
    val left: Operand,
    val comparison: Comparison,
    val right: Operand,
    val offsetBars: Int,
    /** True when the comparison held on at least [minimumBars] of the last [withinBars] bars (D-047). */
    val withinBars: Int = 1,
    val minimumBars: Int = 1,
) : RuleNode

data class RuleGroup(
    val operator: GroupOperator,
    val conditions: List<RuleNode>,
    /** How many conditions must hold for AT_LEAST. */
    val count: Int = 0,
) : RuleNode

/** Where the stop goes (D-047): beyond the signal candle's wick ([at] null) or beyond a level, plus a buffer. */
data class StopRule(
    val at: Operand?,
    val bufferPercent: BigDecimal,
)

/** A take-profit at a level or at a multiple of the risk, closing [closePercent] of the original position (D-047). */
data class TargetRule(
    val at: Operand?,
    val rMultiple: BigDecimal?,
    val closePercent: BigDecimal,
)

/** Raise (longs) or lower (shorts) the stop to each new confirmed swing of [swingPeriod] bars, once target [afterTarget] is taken (0: from entry). */
data class TrailingRule(
    val swingPeriod: Int,
    val afterTarget: Int,
)

/** Take part of a position off at a first target (D-042). */
data class PartialTakeProfit(
    val atPercent: BigDecimal,
    val closePercent: BigDecimal,
    /** After the partial exit, the stop for the rest moves to the entry price. */
    val moveStopToEntry: Boolean,
)

data class ExitRules(
    val stopLossPercent: BigDecimal,
    val takeProfitPercent: BigDecimal,
    val trailingStopPercent: BigDecimal?,
    val maximumHoldingBars: Int,
    val conditions: RuleGroup?,
    /** Exit conditions for short positions when the strategy trades both directions (D-042). */
    val shortConditions: RuleGroup? = null,
    val partialTakeProfit: PartialTakeProfit? = null,
    /** Stop at a chart level or the signal candle's wick (D-047); stopLossPercent is then the farthest allowed stop. */
    val stop: StopRule? = null,
    val shortStop: StopRule? = null,
    /** Up to three targets at levels or multiples of the risk (D-047); takeProfitPercent stays as an outer cap. */
    val targets: List<TargetRule> = emptyList(),
    val shortTargets: List<TargetRule> = emptyList(),
    /** Skip a trade whose first target pays less than this multiple of the risk. */
    val minimumRewardRisk: BigDecimal? = null,
    /** Move the stop to the entry price once this target (1-based) has been taken. */
    val breakevenAfterTarget: Int? = null,
    val trailing: TrailingRule? = null,
) {
    val structured: Boolean get() = stop != null || shortStop != null || targets.isNotEmpty() || shortTargets.isNotEmpty() || trailing != null

    fun stopFor(short: Boolean): StopRule? = if (short && shortStop != null) shortStop else stop

    fun targetsFor(short: Boolean): List<TargetRule> = if (short && shortTargets.isNotEmpty()) shortTargets else targets

    /** The exit rule group that applies to a position on this side. */
    fun conditionsFor(
        short: Boolean,
        direction: Direction,
    ): RuleGroup? = if (short && direction == Direction.BOTH) shortConditions else conditions
}

data class Sizing(
    val method: SizingMethod,
    val value: BigDecimal,
)

data class OrderInstructions(
    val orderType: String,
    val timeInForce: String,
    val limitOffsetPercent: BigDecimal?,
)

data class StrategyRiskLimits(
    val maximumOpenPositions: Int,
    val maximumDailyTrades: Int,
    val maximumDailyLossPercent: BigDecimal,
    val maximumDrawdownPercent: BigDecimal?,
    val maximumPositionPercent: BigDecimal?,
    val maximumConsecutiveLosses: Int?,
    val allowShort: Boolean,
    /** No new entries after this many losing trades in one day (D-042). */
    val maximumDailyLosingTrades: Int? = null,
)

/** Parsed, validated strategy definition used by backtests and live evaluation. */
data class StrategyDefinition(
    val name: String,
    val description: String?,
    val assetClass: AssetClass,
    val timeframe: Timeframe,
    val direction: Direction,
    val symbols: List<String>,
    val minimumHistoryBars: Int,
    val maximumQuoteAgeSeconds: Long,
    val indicators: List<IndicatorSpec>,
    /** Long entries, or short entries for SHORT_ONLY strategies. */
    val entry: RuleGroup,
    val exit: ExitRules,
    val sizing: Sizing,
    val order: OrderInstructions,
    val risk: StrategyRiskLimits,
    val inactivityConditions: Set<String>,
    /** Short entries for strategies that trade both directions (D-042). */
    val shortEntry: RuleGroup? = null,
    /** The setups of a trading plan (D-045); empty for a single strategy, which is its own only setup. */
    val setups: List<Setup> = emptyList(),
    /** Plan-wide context and policies (D-045). */
    val plan: PlanRules = PlanRules.DEFAULT,
) {
    val isPlan: Boolean get() = setups.isNotEmpty()

    /** The setups to evaluate, best priority first: a single strategy is one setup. */
    fun setupsOrSelf(): List<Setup> = setups.ifEmpty { listOf(Setup(Setup.SINGLE, name, description, 1, null, null, null, null, this)) }

    fun setup(id: String?): Setup? = setupsOrSelf().let { all -> all.firstOrNull { it.id == id } ?: all.takeIf { id == null || !isPlan }?.first() }

    /** Whether any rule reads perpetual-futures data (D-044). */
    val usesDerivatives: Boolean get() = indicators.any { it.type in IndicatorType.DERIVATIVES }

    /** Entry rules for one side; null when the strategy does not trade that side. */
    fun entryFor(short: Boolean): RuleGroup? =
        when (direction) {
            Direction.LONG_ONLY -> if (short) null else entry
            Direction.SHORT_ONLY -> if (short) entry else null
            Direction.BOTH -> if (short) shortEntry else entry
        }

    companion object {
        /** Bars every indicator needs, plus the largest rule offset; the effective history requirement (D-042). */
        fun requiredHistory(
            indicators: List<IndicatorSpec>,
            maxOffset: Int,
            base: Timeframe,
            assetClass: AssetClass,
        ): Int = (indicators.maxOfOrNull { it.lookback(base, assetClass) } ?: 1) + maxOffset + 1

        fun maxOffsetOf(n: RuleNode?): Int = maxOffset(n)

        private fun maxOffset(n: RuleNode?): Int =
            when (n) {
                null -> 0
                is Condition -> n.offsetBars + n.withinBars - 1
                is RuleGroup -> n.conditions.maxOfOrNull { maxOffset(it) } ?: 0
            }

        private fun dec(n: JsonNode?): BigDecimal? = n?.takeIf { !it.isNull }?.decimalValue()

        private fun int(n: JsonNode?): Int? = n?.takeIf { !it.isNull }?.intValue()

        fun operand(n: JsonNode): Operand {
            if (n.isNumber) return Operand.Constant(n.decimalValue())
            val s = n.asText()
            PriceField.entries.firstOrNull { it.name == s }?.let { return Operand.Price(it) }
            val parts = s.split('.', limit = 2)
            return Operand.IndicatorRef(parts[0], parts.getOrElse(1) { "value" })
        }

        fun group(n: JsonNode): RuleGroup =
            RuleGroup(
                GroupOperator.valueOf(n["operator"].asText()),
                n["conditions"].map { c ->
                    if (c.has("operator")) {
                        group(c)
                    } else {
                        Condition(
                            operand(c["left"]),
                            Comparison.valueOf(c["comparison"].asText()),
                            operand(c["right"]),
                            int(c["offsetBars"]) ?: 0,
                            int(c["withinBars"]) ?: 1,
                            int(c["minimumBars"]) ?: 1,
                        )
                    }
                },
                int(n["count"]) ?: 0,
            )

        private fun stopRule(n: JsonNode) = StopRule(n["at"]?.asText()?.takeIf { it != SIGNAL_WICK }?.let { operand(n["at"]) }, dec(n["bufferPercent"]) ?: BigDecimal.ZERO)

        private fun targetRule(n: JsonNode) = TargetRule(n["at"]?.let { operand(it) }, dec(n["rMultiple"]), dec(n["closePercent"]) ?: BigDecimal(100))

        /** The stop "at" value meaning the signal candle's own low (longs) or high (shorts). */
        const val SIGNAL_WICK = "SIGNAL_WICK"

        fun indicator(i: JsonNode): IndicatorSpec =
            IndicatorSpec(
                i["id"].asText(),
                IndicatorType.valueOf(i["type"].asText()),
                int(i["period"]),
                int(i["fastPeriod"]),
                int(i["slowPeriod"]),
                int(i["signalPeriod"]),
                dec(i["standardDeviations"]),
                i["source"]?.asText()?.let { PriceField.valueOf(it) } ?: PriceField.CLOSE,
                i["timeframe"]?.asText()?.let { Anchor.ofCode(it) },
                i["anchor"]?.asText()?.let { Anchor.valueOf(it) },
                i["anchorPoint"]?.asText()?.let { AnchorPoint.valueOf(it) },
                i["from"]?.let { operand(it) },
                i["to"]?.let { operand(it) },
                dec(i["ratio"]),
                dec(i["step"]),
            )

        /** Builds the definition from a document (a strategy or a trading plan) that has passed validation. */
        fun from(doc: JsonNode): StrategyDefinition {
            if (TradingPlans.isPlan(doc)) return TradingPlans.definition(doc)
            val m = doc["metadata"]
            val d = doc["dataRequirements"]
            val e = doc["exitRules"]
            val r = doc["riskLimits"]
            val o = doc["orderInstructions"]
            return StrategyDefinition(
                name = m["name"].asText(),
                description = m["description"]?.asText(),
                assetClass = AssetClass.valueOf(m["assetClass"].asText()),
                timeframe = Timeframe.of(m["timeframe"].asText()),
                direction = m["direction"]?.asText()?.let { Direction.valueOf(it) } ?: Direction.LONG_ONLY,
                symbols = doc["universe"]["symbols"].map { it.asText() },
                minimumHistoryBars = d["minimumHistoryBars"].intValue(),
                maximumQuoteAgeSeconds = d["maximumQuoteAgeSeconds"].longValue(),
                indicators = d["indicators"].map(::indicator),
                entry = group(doc["entryRules"]),
                exit =
                    ExitRules(
                        dec(e["stopLossPercent"])!!,
                        dec(e["takeProfitPercent"])!!,
                        dec(e["trailingStopPercent"]),
                        e["maximumHoldingBars"].intValue(),
                        e["conditions"]?.let { group(it) },
                        e["shortConditions"]?.let { group(it) },
                        e["partialTakeProfit"]?.let { p -> PartialTakeProfit(dec(p["atPercent"])!!, dec(p["closePercent"])!!, p["moveStopToEntry"]?.booleanValue() ?: false) },
                        e["stop"]?.let(::stopRule),
                        e["shortStop"]?.let(::stopRule),
                        e["targets"]?.map(::targetRule).orEmpty(),
                        e["shortTargets"]?.map(::targetRule).orEmpty(),
                        dec(e["minimumRewardRisk"]),
                        int(e["breakevenAfterTarget"]),
                        e["trailing"]?.let { t -> TrailingRule(int(t["swingPeriod"]) ?: 3, int(t["afterTarget"]) ?: 0) },
                    ),
                sizing = Sizing(SizingMethod.valueOf(doc["positionSizing"]["method"].asText()), dec(doc["positionSizing"]["value"])!!),
                order = OrderInstructions(o["orderType"].asText(), o["timeInForce"].asText(), dec(o["limitOffsetPercent"])),
                risk =
                    StrategyRiskLimits(
                        r["maximumOpenPositions"].intValue(),
                        r["maximumDailyTrades"].intValue(),
                        dec(r["maximumDailyLossPercent"])!!,
                        dec(r["maximumDrawdownPercent"]),
                        dec(r["maximumPositionPercent"]),
                        int(r["maximumConsecutiveLosses"]),
                        r["allowShort"].booleanValue(),
                        int(r["maximumDailyLosingTrades"]),
                    ),
                inactivityConditions = doc["inactivityConditions"].map { it.asText() }.toSet(),
                shortEntry = doc["shortEntryRules"]?.let { group(it) },
            ).let { d ->
                // The history requirement is never below what the indicators need (D-042).
                val offsets = maxOf(maxOffset(d.entry), maxOffset(d.shortEntry), maxOffset(d.exit.conditions), maxOffset(d.exit.shortConditions))
                d.copy(minimumHistoryBars = maxOf(d.minimumHistoryBars, requiredHistory(d.indicators, offsets, d.timeframe, d.assetClass)))
            }
        }
    }
}
