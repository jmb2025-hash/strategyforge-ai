package app.strategyforge.engine.strategy

import app.strategyforge.engine.market.CandleData
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** One target of an open position: its price and the share of the original quantity it closes. */
data class PlannedTarget(
    val price: BigDecimal,
    val closeFraction: BigDecimal,
)

/** Stop and targets fixed when the signal fires (D-047); null stop means a percentage stop. */
data class ExitPlan(
    val stop: BigDecimal?,
    val targets: List<PlannedTarget>,
) {
    /** Distance to the stop as a fraction of [entry], used for risk-based sizing. */
    fun riskFraction(entry: BigDecimal): BigDecimal? = stop?.let { entry.subtract(it).abs().divide(entry, MC) }

    companion object {
        private val MC = MathContext(34, RoundingMode.HALF_EVEN)
        private val HUNDRED = BigDecimal(100)

        /**
         * The exit plan of a setup entering at bar [i] with reference price [entry], or a reason it
         * cannot trade: a stop on the wrong side or farther than stopLossPercent, a level target on
         * the wrong side, or a first target paying less than minimumRewardRisk.
         */
        fun build(
            def: StrategyDefinition,
            short: Boolean,
            bars: List<CandleData>,
            ev: RuleEvaluator,
            i: Int,
            entry: BigDecimal,
        ): Pair<ExitPlan?, String?> {
            val exit = def.exit
            if (!exit.structured) return ExitPlan(null, emptyList()) to null
            val sign = if (short) BigDecimal.ONE.negate() else BigDecimal.ONE
            val pctStop = entry.multiply(BigDecimal.ONE.subtract(sign.multiply(exit.stopLossPercent).divide(HUNDRED, MC)), MC)
            val stop =
                exit.stopFor(short)?.let { rule ->
                    val base = rule.at?.let { ev.value(it, i) } ?: if (short) bars[i].high else bars[i].low
                    val buffer = base.multiply(rule.bufferPercent).divide(HUNDRED, MC)
                    val s = if (short) base.add(buffer) else base.subtract(buffer)
                    if (if (short) s <= entry else s >= entry) return null to "the stop level is on the wrong side of the price"
                    // stopLossPercent is the farthest stop allowed.
                    if (s.subtract(entry).abs() > entry.subtract(pctStop).abs()) return null to "the stop is farther than ${exit.stopLossPercent.stripTrailingZeros().toPlainString()}%"
                    s
                } ?: pctStop
            val risk = entry.subtract(stop).abs()
            val rules = exit.targetsFor(short)
            var left = BigDecimal.ONE
            val targets =
                rules.mapIndexed { n, t ->
                    val price =
                        if (t.at != null) {
                            ev.value(t.at, i) ?: return null to "target ${n + 1} has no value yet"
                        } else {
                            entry.add(sign.multiply(risk).multiply(t.rMultiple!!, MC))
                        }
                    if (if (short) price >= entry else price <= entry) return null to "target ${n + 1} is on the wrong side of the price"
                    val fraction = if (n == rules.lastIndex) left else t.closePercent.divide(HUNDRED, MC).min(left)
                    left = left.subtract(fraction)
                    PlannedTarget(price, fraction)
                }
            exit.minimumRewardRisk?.let { min ->
                val first = targets.firstOrNull()?.price ?: return@let
                if (risk.signum() > 0 && first.subtract(entry).abs().divide(risk, MC) < min) return null to "the first target pays less than ${min.stripTrailingZeros().toPlainString()}R"
            }
            return ExitPlan(stop, targets) to null
        }

        /** Latest confirmed swing lows (longs) or highs (shorts) per bar, for trailing stops; causal. */
        fun swings(
            bars: List<CandleData>,
            short: Boolean,
            period: Int,
        ): List<BigDecimal?> = if (short) Indicators.swingHigh(bars, period) else Indicators.swingLow(bars, period)
    }
}

/** Stored form of an exit plan on its entry signal (D-047): exact decimal text. */
object ExitPlanJson {
    private val mapper =
        com.fasterxml.jackson.databind
            .ObjectMapper()

    fun write(p: ExitPlan): String =
        mapper.writeValueAsString(
            mapOf(
                "stop" to p.stop?.toPlainString(),
                "targets" to p.targets.map { mapOf("price" to it.price.toPlainString(), "fraction" to it.closeFraction.toPlainString()) },
            ),
        )

    fun read(s: String): ExitPlan? =
        runCatching {
            val n = mapper.readTree(s)
            ExitPlan(
                n["stop"]?.takeIf { !it.isNull }?.asText()?.let(::BigDecimal),
                n["targets"]?.map { PlannedTarget(BigDecimal(it["price"].asText()), BigDecimal(it["fraction"].asText())) }.orEmpty(),
            )
        }.getOrNull()?.takeIf { it.stop != null }
}
