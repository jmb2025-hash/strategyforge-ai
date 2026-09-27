package app.strategyforge.strategy

import java.math.BigDecimal

/** Plain-English explanation of a validated strategy (FR-046). Deterministic; no AI involved. */
object StrategyExplainer {
    fun explain(d: StrategyDefinition): String {
        val sb = StringBuilder()
        val side = if (d.direction == Direction.SHORT_ONLY) "Open simulated short positions in" else "Buy"
        val universe = if (d.symbols.size <= 8) d.symbols.joinToString(", ") else "${d.symbols.take(8).joinToString(", ")} and ${d.symbols.size - 8} more"
        sb.append("$side $universe (${d.assetClass.name.replace('_', ' ').lowercase()}) on ${label(d.timeframe.code)} bars when ${group(d.entry, d)}. ")
        sb.append("Exit at a ${pct(d.exit.stopLossPercent)} stop loss, a ${pct(d.exit.takeProfitPercent)} profit target")
        d.exit.trailingStopPercent?.let { sb.append(", a ${pct(it)} trailing stop") }
        sb.append(", or after ${d.exit.maximumHoldingBars} bars")
        d.exit.conditions?.let { sb.append(", or when ${group(it, d)}") }
        sb.append(". ")
        sb.append(
            when (d.sizing.method) {
                SizingMethod.PERCENT_OF_EQUITY -> "Each position uses ${pct(d.sizing.value)} of portfolio equity"
                SizingMethod.FIXED_CASH -> "Each position uses ${d.sizing.value.stripTrailingZeros().toPlainString()} USD"
                SizingMethod.FIXED_QUANTITY -> "Each position is ${d.sizing.value.stripTrailingZeros().toPlainString()} units"
            },
        )
        sb.append(", placed as ${d.order.orderType.lowercase()} orders (${d.order.timeInForce})")
        d.order.limitOffsetPercent?.let { sb.append(" with a ${pct(it)} limit offset") }
        sb.append(". ")
        sb.append("At most ${d.risk.maximumOpenPositions} open positions and ${d.risk.maximumDailyTrades} trades per day; stop for the day after a ${pct(d.risk.maximumDailyLossPercent)} loss")
        d.risk.maximumDrawdownPercent?.let { sb.append("; suspend after a ${pct(it)} drawdown") }
        sb.append(". Requires ${d.minimumHistoryBars} bars of history and quotes no older than ${d.maximumQuoteAgeSeconds} seconds. ")
        sb.append("Global, portfolio and order risk limits also apply; the strictest limit always wins. Paper trading only.")
        return sb.toString()
    }

    private fun group(
        g: RuleGroup,
        d: StrategyDefinition,
    ): String {
        val parts = g.conditions.map { n -> if (n is RuleGroup) "(${group(n, d)})" else condition(n as Condition, d) }
        if (parts.size == 1) return parts.single()
        return (if (g.operator == GroupOperator.ALL) "all of: " else "any of: ") + parts.joinToString("; ")
    }

    private fun condition(
        c: Condition,
        d: StrategyDefinition,
    ): String {
        val cmp =
            when (c.comparison) {
                Comparison.GT -> "is above"
                Comparison.GTE -> "is at or above"
                Comparison.LT -> "is below"
                Comparison.LTE -> "is at or below"
                Comparison.EQ -> "equals"
                Comparison.CROSSES_ABOVE -> "crosses above"
                Comparison.CROSSES_BELOW -> "crosses below"
            }
        val offset = if (c.offsetBars > 0) " (${c.offsetBars} bars ago)" else ""
        return "${operand(c.left, d)} $cmp ${operand(c.right, d)}$offset"
    }

    private fun operand(
        o: Operand,
        d: StrategyDefinition,
    ): String =
        when (o) {
            is Operand.Constant -> o.value.stripTrailingZeros().toPlainString()
            is Operand.Price -> "the ${o.field.name.lowercase()} price"
            is Operand.IndicatorRef -> {
                val spec = d.indicators.firstOrNull { it.id == o.id }
                val base =
                    when (spec?.type) {
                        IndicatorType.SMA -> "the ${spec.period}-bar simple moving average"
                        IndicatorType.EMA -> "the ${spec.period}-bar exponential moving average"
                        IndicatorType.RSI -> "RSI(${spec.period})"
                        IndicatorType.ATR -> "ATR(${spec.period})"
                        IndicatorType.MACD -> "MACD(${spec.fastPeriod},${spec.slowPeriod},${spec.signalPeriod})"
                        IndicatorType.BOLLINGER_BANDS -> "Bollinger Bands(${spec.period}, ${spec.standardDeviations?.stripTrailingZeros()?.toPlainString()})"
                        null -> o.id
                    }
                if (o.component == "value") base else "$base ${o.component} line"
            }
        }

    private fun pct(v: BigDecimal) = v.stripTrailingZeros().toPlainString() + "%"

    private fun label(tf: String) = mapOf("1m" to "1-minute", "5m" to "5-minute", "15m" to "15-minute", "1h" to "1-hour", "4h" to "4-hour", "1d" to "daily")[tf] ?: tf
}
