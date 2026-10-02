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
    ;

    /** Pattern detectors take no period. */
    val pattern: Boolean get() = this in PATTERNS

    companion object {
        val PATTERNS = setOf(BULLISH_ENGULFING, BEARISH_ENGULFING, HAMMER, SHOOTING_STAR, DOJI, MORNING_STAR, EVENING_STAR)

        /** Types that can be computed on daily, weekly or monthly bars inside a faster strategy. */
        val HIGHER_TIMEFRAME_CAPABLE =
            setOf(SMA, EMA, RSI, ATR, MACD, BOLLINGER_BANDS, HIGHEST, LOWEST, SWING_HIGH, SWING_LOW, RELATIVE_VOLUME, FIBONACCI) + PATTERNS
    }
}

/** Calendar periods: crypto uses UTC days, US stocks New York days; weeks start on Monday (D-042). */
enum class Anchor(
    val code: String,
) {
    DAY("1d"),
    WEEK("1w"),
    MONTH("1M"),
    ;

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

enum class GroupOperator { ALL, ANY }

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
        }

    fun components(): Set<String> =
        when (type) {
            IndicatorType.MACD -> setOf("value", "signal", "histogram")
            IndicatorType.BOLLINGER_BANDS -> setOf("upper", "middle", "lower")
            IndicatorType.PERIOD_LEVELS -> PERIOD_COMPONENTS
            IndicatorType.FIBONACCI -> FIB_LEVELS.keys + setOf("high", "low", "trend")
            IndicatorType.VOLUME_PROFILE -> setOf("poc", "vah", "val")
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
) : RuleNode

data class RuleGroup(
    val operator: GroupOperator,
    val conditions: List<RuleNode>,
) : RuleNode

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
) {
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
) {
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

        private fun maxOffset(n: RuleNode?): Int =
            when (n) {
                null -> 0
                is Condition -> n.offsetBars
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
                        Condition(operand(c["left"]), Comparison.valueOf(c["comparison"].asText()), operand(c["right"]), int(c["offsetBars"]) ?: 0)
                    }
                },
            )

        /** Builds the definition from a document that has passed schema and semantic validation. */
        fun from(doc: JsonNode): StrategyDefinition {
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
                indicators =
                    d["indicators"].map { i ->
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
                        )
                    },
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
