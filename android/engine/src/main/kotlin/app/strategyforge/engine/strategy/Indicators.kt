package app.strategyforge.engine.strategy

import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.money.BigMath
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Causal technical indicators over BigDecimal (NFR-002). Every value at index i depends only on
 * bars 0..i, so indicators can never leak future information (FR-050). Warm-up values are null.
 */
object Indicators {
    private val MC = MathContext(34, RoundingMode.HALF_EVEN)
    private val TWO = BigDecimal(2)
    private val HUNDRED = BigDecimal(100)

    fun source(
        bars: List<CandleData>,
        field: PriceField,
    ): List<BigDecimal> =
        bars.map {
            when (field) {
                PriceField.OPEN -> it.open
                PriceField.HIGH -> it.high
                PriceField.LOW -> it.low
                PriceField.CLOSE -> it.close
                PriceField.VOLUME -> it.volume
            }
        }

    fun sma(
        x: List<BigDecimal>,
        period: Int,
    ): List<BigDecimal?> {
        val out = MutableList<BigDecimal?>(x.size) { null }
        var sum = BigDecimal.ZERO
        for (i in x.indices) {
            sum = sum.add(x[i])
            if (i >= period) sum = sum.subtract(x[i - period])
            if (i >= period - 1) out[i] = sum.divide(BigDecimal(period), MC)
        }
        return out
    }

    /** EMA seeded with the SMA of the first [period] values; alpha = 2 / (period + 1). */
    fun ema(
        x: List<BigDecimal?>,
        period: Int,
    ): List<BigDecimal?> {
        val out = MutableList<BigDecimal?>(x.size) { null }
        val alpha = TWO.divide(BigDecimal(period + 1), MC)
        var prev: BigDecimal? = null
        val seed = mutableListOf<BigDecimal>()
        for (i in x.indices) {
            val v = x[i] ?: continue
            if (prev == null) {
                seed += v
                if (seed.size == period) {
                    prev = seed.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(period), MC)
                    out[i] = prev
                }
            } else {
                prev = v.subtract(prev).multiply(alpha, MC).add(prev, MC)
                out[i] = prev
            }
        }
        return out
    }

    /** Wilder's RSI. */
    fun rsi(
        x: List<BigDecimal>,
        period: Int,
    ): List<BigDecimal?> {
        val out = MutableList<BigDecimal?>(x.size) { null }
        if (x.size <= period) return out
        var gain = BigDecimal.ZERO
        var loss = BigDecimal.ZERO
        for (i in 1..period) {
            val d = x[i].subtract(x[i - 1])
            if (d.signum() > 0) gain = gain.add(d) else loss = loss.add(d.negate())
        }
        val p = BigDecimal(period)
        gain = gain.divide(p, MC)
        loss = loss.divide(p, MC)
        out[period] = rsiValue(gain, loss)
        for (i in period + 1 until x.size) {
            val d = x[i].subtract(x[i - 1])
            val g = if (d.signum() > 0) d else BigDecimal.ZERO
            val l = if (d.signum() < 0) d.negate() else BigDecimal.ZERO
            gain = gain.multiply(p.subtract(BigDecimal.ONE)).add(g).divide(p, MC)
            loss = loss.multiply(p.subtract(BigDecimal.ONE)).add(l).divide(p, MC)
            out[i] = rsiValue(gain, loss)
        }
        return out
    }

    private fun rsiValue(
        gain: BigDecimal,
        loss: BigDecimal,
    ): BigDecimal = if (loss.signum() == 0) HUNDRED else HUNDRED.subtract(HUNDRED.divide(BigDecimal.ONE.add(gain.divide(loss, MC)), MC))

    data class Macd(
        val value: List<BigDecimal?>,
        val signal: List<BigDecimal?>,
        val histogram: List<BigDecimal?>,
    )

    fun macd(
        x: List<BigDecimal>,
        fast: Int,
        slow: Int,
        signal: Int,
    ): Macd {
        val f = ema(x, fast)
        val s = ema(x, slow)
        val line = x.indices.map { i -> if (f[i] != null && s[i] != null) f[i]!!.subtract(s[i]) else null }
        val sig = ema(line, signal)
        val hist = x.indices.map { i -> if (line[i] != null && sig[i] != null) line[i]!!.subtract(sig[i]) else null }
        return Macd(line, sig, hist)
    }

    /** Wilder's average true range. */
    fun atr(
        bars: List<CandleData>,
        period: Int,
    ): List<BigDecimal?> {
        val out = MutableList<BigDecimal?>(bars.size) { null }
        if (bars.size <= period) return out
        val tr =
            bars.indices.map { i ->
                val b = bars[i]
                if (i == 0) {
                    b.high.subtract(b.low)
                } else {
                    val pc = bars[i - 1].close
                    b.high
                        .subtract(b.low)
                        .max(b.high.subtract(pc).abs())
                        .max(b.low.subtract(pc).abs())
                }
            }
        val p = BigDecimal(period)
        var atr = tr.subList(1, period + 1).fold(BigDecimal.ZERO, BigDecimal::add).divide(p, MC)
        out[period] = atr
        for (i in period + 1 until bars.size) {
            atr = atr.multiply(p.subtract(BigDecimal.ONE)).add(tr[i]).divide(p, MC)
            out[i] = atr
        }
        return out
    }

    data class Bands(
        val upper: List<BigDecimal?>,
        val middle: List<BigDecimal?>,
        val lower: List<BigDecimal?>,
    )

    /** Bollinger Bands with population standard deviation. */
    fun bollinger(
        x: List<BigDecimal>,
        period: Int,
        k: BigDecimal,
    ): Bands {
        val mid = sma(x, period)
        val up = MutableList<BigDecimal?>(x.size) { null }
        val lo = MutableList<BigDecimal?>(x.size) { null }
        for (i in x.indices) {
            val m = mid[i] ?: continue
            var ss = BigDecimal.ZERO
            for (j in i - period + 1..i) {
                val d = x[j].subtract(m)
                ss = ss.add(d.multiply(d))
            }
            val sd = BigMath.sqrt(ss.divide(BigDecimal(period), MC))
            up[i] = m.add(sd.multiply(k))
            lo[i] = m.subtract(sd.multiply(k))
        }
        return Bands(up, mid, lo)
    }

    /** Computes every declared indicator series keyed by "ID.component". */
    fun compute(
        specs: List<IndicatorSpec>,
        bars: List<CandleData>,
    ): Map<String, List<BigDecimal?>> {
        val out = linkedMapOf<String, List<BigDecimal?>>()
        specs.forEach { s ->
            val src = source(bars, s.source)
            when (s.type) {
                IndicatorType.SMA -> out["${s.id}.value"] = sma(src, s.period!!)
                IndicatorType.EMA -> out["${s.id}.value"] = ema(src, s.period!!)
                IndicatorType.RSI -> out["${s.id}.value"] = rsi(src, s.period!!)
                IndicatorType.ATR -> out["${s.id}.value"] = atr(bars, s.period!!)
                IndicatorType.MACD ->
                    macd(src, s.fastPeriod!!, s.slowPeriod!!, s.signalPeriod!!).let {
                        out["${s.id}.value"] = it.value
                        out["${s.id}.signal"] = it.signal
                        out["${s.id}.histogram"] = it.histogram
                    }
                IndicatorType.BOLLINGER_BANDS ->
                    bollinger(src, s.period!!, s.standardDeviations!!).let {
                        out["${s.id}.upper"] = it.upper
                        out["${s.id}.middle"] = it.middle
                        out["${s.id}.lower"] = it.lower
                    }
            }
        }
        return out
    }
}

/** Evaluates a rule tree at bar index [i] using only values at or before i. */
class RuleEvaluator(
    private val bars: List<CandleData>,
    private val series: Map<String, List<BigDecimal?>>,
) {
    fun value(
        o: Operand,
        i: Int,
    ): BigDecimal? {
        if (i < 0 || i >= bars.size) return null
        return when (o) {
            is Operand.Constant -> o.value
            is Operand.Price -> Indicators.source(listOf(bars[i]), o.field).first()
            is Operand.IndicatorRef -> series["${o.id}.${o.component}"]?.getOrNull(i)
        }
    }

    fun evaluate(
        node: RuleNode,
        i: Int,
    ): Boolean =
        when (node) {
            is RuleGroup -> if (node.operator == GroupOperator.ALL) node.conditions.all { evaluate(it, i) } else node.conditions.any { evaluate(it, i) }
            is Condition -> condition(node, i - node.offsetBars)
        }

    private fun condition(
        c: Condition,
        i: Int,
    ): Boolean {
        val l = value(c.left, i) ?: return false
        val r = value(c.right, i) ?: return false
        return when (c.comparison) {
            Comparison.GT -> l > r
            Comparison.GTE -> l >= r
            Comparison.LT -> l < r
            Comparison.LTE -> l <= r
            Comparison.EQ -> l.compareTo(r) == 0
            Comparison.CROSSES_ABOVE -> {
                val pl = value(c.left, i - 1) ?: return false
                val pr = value(c.right, i - 1) ?: return false
                pl <= pr && l > r
            }
            Comparison.CROSSES_BELOW -> {
                val pl = value(c.left, i - 1) ?: return false
                val pr = value(c.right, i - 1) ?: return false
                pl >= pr && l < r
            }
        }
    }
}
