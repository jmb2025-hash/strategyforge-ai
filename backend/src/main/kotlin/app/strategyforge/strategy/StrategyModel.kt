package app.strategyforge.strategy

import app.strategyforge.market.AssetClass
import app.strategyforge.market.Timeframe
import com.fasterxml.jackson.databind.JsonNode
import java.math.BigDecimal

enum class IndicatorType { SMA, EMA, RSI, MACD, ATR, BOLLINGER_BANDS }

enum class Comparison { GT, GTE, LT, LTE, EQ, CROSSES_ABOVE, CROSSES_BELOW }

enum class GroupOperator { ALL, ANY }

enum class SizingMethod { FIXED_CASH, PERCENT_OF_EQUITY, FIXED_QUANTITY }

enum class Direction { LONG_ONLY, SHORT_ONLY }

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
) {
    /** Bars required before the indicator produces a stable value. */
    fun lookback(): Int =
        when (type) {
            IndicatorType.SMA, IndicatorType.BOLLINGER_BANDS -> period!!
            IndicatorType.EMA -> period!! * 3
            IndicatorType.RSI, IndicatorType.ATR -> period!! * 3 + 1
            IndicatorType.MACD -> slowPeriod!! * 3 + signalPeriod!!
        }

    fun components(): Set<String> =
        when (type) {
            IndicatorType.MACD -> setOf("value", "signal", "histogram")
            IndicatorType.BOLLINGER_BANDS -> setOf("upper", "middle", "lower")
            else -> setOf("value")
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

data class ExitRules(
    val stopLossPercent: BigDecimal,
    val takeProfitPercent: BigDecimal,
    val trailingStopPercent: BigDecimal?,
    val maximumHoldingBars: Int,
    val conditions: RuleGroup?,
)

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
    val entry: RuleGroup,
    val exit: ExitRules,
    val sizing: Sizing,
    val order: OrderInstructions,
    val risk: StrategyRiskLimits,
    val inactivityConditions: Set<String>,
) {
    companion object {
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
                        )
                    },
                entry = group(doc["entryRules"]),
                exit = ExitRules(dec(e["stopLossPercent"])!!, dec(e["takeProfitPercent"])!!, dec(e["trailingStopPercent"]), e["maximumHoldingBars"].intValue(), e["conditions"]?.let { group(it) }),
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
                    ),
                inactivityConditions = doc["inactivityConditions"].map { it.asText() }.toSet(),
            )
        }
    }
}
