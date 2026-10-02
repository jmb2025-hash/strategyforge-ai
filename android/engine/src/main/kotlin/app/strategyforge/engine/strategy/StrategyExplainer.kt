package app.strategyforge.engine.strategy

import java.math.BigDecimal

/** Plain-English explanation of a validated strategy (FR-046). Deterministic; no AI involved. */
object StrategyExplainer {
    fun explain(d: StrategyDefinition): String {
        val sb = StringBuilder()
        val universe = if (d.symbols.size <= 8) d.symbols.joinToString(", ") else "${d.symbols.take(8).joinToString(", ")} and ${d.symbols.size - 8} more"
        val market = "$universe (${d.assetClass.name.replace('_', ' ').lowercase()}) on ${label(d.timeframe.code)} bars"
        when (d.direction) {
            Direction.LONG_ONLY -> sb.append("Buy $market when ${group(d.entry, d)}. ")
            Direction.SHORT_ONLY -> sb.append("Open simulated short positions in $market when ${group(d.entry, d)}. ")
            Direction.BOTH -> {
                sb.append("Trades $market in both directions. Buy when ${group(d.entry, d)}. ")
                d.shortEntry?.let { sb.append("Open a simulated short when ${group(it, d)}. ") }
            }
        }
        sb.append("Exit at a ${pct(d.exit.stopLossPercent)} stop loss, a ${pct(d.exit.takeProfitPercent)} profit target")
        d.exit.trailingStopPercent?.let { sb.append(", a ${pct(it)} trailing stop") }
        sb.append(", or after ${d.exit.maximumHoldingBars} bars")
        d.exit.conditions?.let { sb.append(if (d.direction == Direction.BOTH) ", or (longs) when ${group(it, d)}" else ", or when ${group(it, d)}") }
        d.exit.shortConditions?.let { sb.append(", or (shorts) when ${group(it, d)}") }
        sb.append(". ")
        d.exit.partialTakeProfit?.let {
            sb.append("Take ${pct(it.closePercent)} of the position off at a ${pct(it.atPercent)} gain")
            sb.append(if (it.moveStopToEntry) " and then move the stop to the entry price. " else ". ")
        }
        sb.append(
            when (d.sizing.method) {
                SizingMethod.PERCENT_OF_EQUITY -> "Each position uses ${pct(d.sizing.value)} of portfolio equity"
                SizingMethod.FIXED_CASH -> "Each position uses ${d.sizing.value.stripTrailingZeros().toPlainString()} USD"
                SizingMethod.FIXED_QUANTITY -> "Each position is ${d.sizing.value.stripTrailingZeros().toPlainString()} units"
                SizingMethod.RISK_PERCENT ->
                    "Each position is sized so that hitting the stop loses ${pct(d.sizing.value)} of equity" +
                        " (at most ${pct(d.risk.maximumPositionPercent ?: java.math.BigDecimal(100))} of equity in one position)"
            },
        )
        sb.append(", placed as ${d.order.orderType.lowercase()} orders (${d.order.timeInForce})")
        d.order.limitOffsetPercent?.let { sb.append(" with a ${pct(it)} limit offset") }
        sb.append(". ")
        sb.append("At most ${d.risk.maximumOpenPositions} open positions and ${d.risk.maximumDailyTrades} trades per day; stop for the day after a ${pct(d.risk.maximumDailyLossPercent)} loss")
        d.risk.maximumDrawdownPercent?.let { sb.append("; suspend after a ${pct(it)} drawdown") }
        d.risk.maximumDailyLosingTrades?.let { sb.append("; no new trades after $it losing trade(s) in a day") }
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
                        IndicatorType.HIGHEST -> "the highest high of the previous ${spec.period} bars"
                        IndicatorType.LOWEST -> "the lowest low of the previous ${spec.period} bars"
                        IndicatorType.SWING_HIGH -> "the latest swing high (resistance, ${spec.period} bars each side)"
                        IndicatorType.SWING_LOW -> "the latest swing low (support, ${spec.period} bars each side)"
                        IndicatorType.RELATIVE_VOLUME -> "volume relative to its ${spec.period}-bar average"
                        IndicatorType.BULLISH_ENGULFING -> "a bullish engulfing candle (1 = yes)"
                        IndicatorType.BEARISH_ENGULFING -> "a bearish engulfing candle (1 = yes)"
                        IndicatorType.HAMMER -> "a hammer candle (1 = yes)"
                        IndicatorType.SHOOTING_STAR -> "a shooting star candle (1 = yes)"
                        IndicatorType.DOJI -> "a doji candle (1 = yes)"
                        IndicatorType.MORNING_STAR -> "a morning star pattern (1 = yes)"
                        IndicatorType.EVENING_STAR -> "an evening star pattern (1 = yes)"
                        IndicatorType.PERIOD_LEVELS -> return periodLevel(spec.anchor!!, o.component)
                        IndicatorType.VWAP -> "the ${anchorWord(spec.anchor!!)} VWAP"
                        IndicatorType.ANCHORED_VWAP ->
                            "the VWAP anchored at the ${if (spec.anchorPoint == AnchorPoint.HIGHEST_HIGH) "highest high" else "lowest low"} of the previous ${spec.period} bars"
                        IndicatorType.FIBONACCI -> return fib(spec, o.component)
                        IndicatorType.VOLUME_PROFILE -> return profile(spec, o.component)
                        null -> o.id
                    }
                val htf = spec?.timeframe?.let { "${anchorWord(it)} " } ?: ""
                (if (o.component == "value") base else "$base ${o.component} line").let { if (it.startsWith("the ")) "the $htf" + it.removePrefix("the ") else htf + it }
            }
        }

    private fun pct(v: BigDecimal) = v.stripTrailingZeros().toPlainString() + "%"

    private fun anchorWord(a: Anchor) =
        when (a) {
            Anchor.DAY -> "daily"
            Anchor.WEEK -> "weekly"
            Anchor.MONTH -> "monthly"
        }

    private fun periodLevel(
        a: Anchor,
        component: String,
    ): String {
        val (cur, prev) =
            when (a) {
                Anchor.DAY -> "today's" to "the previous day's"
                Anchor.WEEK -> "this week's" to "the previous week's"
                Anchor.MONTH -> "this month's" to "the previous month's"
            }
        return when (component) {
            "open" -> "$cur open"
            "high" -> "$cur high so far"
            "low" -> "$cur low so far"
            "prevOpen" -> "$prev open"
            "prevHigh" -> "$prev high"
            "prevLow" -> "$prev low"
            "prevClose" -> "$prev close"
            else -> "$prev midpoint (EQ)"
        }
    }

    private fun fib(
        s: IndicatorSpec,
        component: String,
    ): String {
        val range = "the ${s.timeframe?.let { anchorWord(it) + " " } ?: ""}range of the previous ${s.period} ${if (s.timeframe != null) "periods" else "bars"}"
        return when (component) {
            "high" -> "the high of $range"
            "low" -> "the low of $range"
            "trend" -> "the direction of $range (1 = up, -1 = down)"
            else -> "the ${FIB_LEVELS[component]!!.stripTrailingZeros().toPlainString()} Fibonacci retracement of $range"
        }
    }

    private fun profile(
        s: IndicatorSpec,
        component: String,
    ): String {
        val name =
            when (component) {
                "poc" -> "point of control"
                "vah" -> "value area high"
                else -> "value area low"
            }
        val scope =
            s.anchor?.let { a ->
                "the previous ${if (a == Anchor.DAY) {
                    "day"
                } else if (a == Anchor.WEEK) {
                    "week"
                } else {
                    "month"
                }}"
            } ?: "the previous ${s.period} bars"
        return "the volume-profile $name of $scope"
    }

    private fun label(tf: String) = mapOf("1m" to "1-minute", "5m" to "5-minute", "15m" to "15-minute", "1h" to "1-hour", "4h" to "4-hour", "1d" to "daily")[tf] ?: tf
}
