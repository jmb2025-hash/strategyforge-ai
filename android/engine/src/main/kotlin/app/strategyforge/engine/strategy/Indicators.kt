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

    // ------------------------------------------------------------------ chart structure and volume (D-036)

    /** Highest high of the [period] bars before bar i (the current bar is excluded, so a close above it is a breakout). */
    fun highest(
        bars: List<CandleData>,
        period: Int,
    ): List<BigDecimal?> = bars.indices.map { i -> if (i < period) null else (i - period until i).maxOf { bars[it].high } }

    /** Lowest low of the [period] bars before bar i. */
    fun lowest(
        bars: List<CandleData>,
        period: Int,
    ): List<BigDecimal?> = bars.indices.map { i -> if (i < period) null else (i - period until i).minOf { bars[it].low } }

    /**
     * The most recent confirmed swing high (resistance): a bar whose high is above the highs of the
     * [k] bars on each side. It is only known [k] bars later, and is used from then on.
     */
    fun swingHigh(
        bars: List<CandleData>,
        k: Int,
    ): List<BigDecimal?> = swing(bars, k) { a, b -> a.high > b.high }.map { j -> j?.let { bars[it].high } }

    /** The most recent confirmed swing low (support), confirmed [k] bars after it. */
    fun swingLow(
        bars: List<CandleData>,
        k: Int,
    ): List<BigDecimal?> = swing(bars, k) { a, b -> a.low < b.low }.map { j -> j?.let { bars[it].low } }

    private fun swing(
        bars: List<CandleData>,
        k: Int,
        beats: (CandleData, CandleData) -> Boolean,
    ): List<Int?> {
        val out = MutableList<Int?>(bars.size) { null }
        var latest: Int? = null
        for (i in bars.indices) {
            // At bar i the candidate j = i - k has k bars on its right, all known by now.
            val j = i - k
            if (j >= k && (j - k until j).all { beats(bars[j], bars[it]) } && (j + 1..i).all { beats(bars[j], bars[it]) }) latest = j
            out[i] = latest
        }
        return out
    }

    /** This bar's volume divided by the average volume of the [period] bars before it. */
    fun relativeVolume(
        bars: List<CandleData>,
        period: Int,
    ): List<BigDecimal?> =
        bars.indices.map { i ->
            if (i < period) {
                null
            } else {
                val avg = (i - period until i).fold(BigDecimal.ZERO) { a, j -> a.add(bars[j].volume) }.divide(BigDecimal(period), MC)
                if (avg.signum() == 0) null else bars[i].volume.divide(avg, MC)
            }
        }

    // ------------------------------------------------------------------ candlestick patterns (D-036)

    private fun body(b: CandleData) = b.close.subtract(b.open).abs()

    private fun range(b: CandleData) = b.high.subtract(b.low)

    private fun upperShadow(b: CandleData) = b.high.subtract(b.open.max(b.close))

    private fun lowerShadow(b: CandleData) = b.open.min(b.close).subtract(b.low)

    private fun bullish(b: CandleData) = b.close > b.open

    private fun bearish(b: CandleData) = b.close < b.open

    private fun frac(
        x: BigDecimal,
        f: String,
    ) = x.multiply(BigDecimal(f))

    /** 1 on bars where [test] holds for the bar and its [lookback] predecessors, 0 otherwise; null before enough bars. */
    private fun pattern(
        bars: List<CandleData>,
        lookback: Int,
        test: (Int) -> Boolean,
    ): List<BigDecimal?> =
        bars.indices.map { i ->
            if (i < lookback) {
                null
            } else if (test(i)) {
                BigDecimal.ONE
            } else {
                BigDecimal.ZERO
            }
        }

    fun candlestick(
        type: IndicatorType,
        bars: List<CandleData>,
    ): List<BigDecimal?> =
        when (type) {
            // Body at most a tenth of the bar's range: indecision.
            IndicatorType.DOJI -> pattern(bars, 0) { i -> range(bars[i]).signum() > 0 && body(bars[i]) <= frac(range(bars[i]), "0.1") }
            // Small body near the top, long lower shadow (at least twice the body), little upper shadow.
            IndicatorType.HAMMER ->
                pattern(bars, 0) { i ->
                    val b = bars[i]
                    val r = range(b)
                    r.signum() > 0 && body(b) <= frac(r, "0.35") && lowerShadow(b) >= body(b).multiply(TWO) && lowerShadow(b) >= frac(r, "0.5") && upperShadow(b) <= frac(r, "0.15")
                }
            // The mirror image: long upper shadow, small body near the bottom.
            IndicatorType.SHOOTING_STAR ->
                pattern(bars, 0) { i ->
                    val b = bars[i]
                    val r = range(b)
                    r.signum() > 0 && body(b) <= frac(r, "0.35") && upperShadow(b) >= body(b).multiply(TWO) && upperShadow(b) >= frac(r, "0.5") && lowerShadow(b) <= frac(r, "0.15")
                }
            // A rising bar whose body covers the previous falling bar's body.
            IndicatorType.BULLISH_ENGULFING ->
                pattern(bars, 1) { i ->
                    val p = bars[i - 1]
                    val c = bars[i]
                    bearish(p) && bullish(c) && c.open <= p.close && c.close >= p.open && body(c) > body(p)
                }
            IndicatorType.BEARISH_ENGULFING ->
                pattern(bars, 1) { i ->
                    val p = bars[i - 1]
                    val c = bars[i]
                    bullish(p) && bearish(c) && c.open >= p.close && c.close <= p.open && body(c) > body(p)
                }
            // Long falling bar, small-bodied bar, then a rising bar closing above the middle of the first body.
            IndicatorType.MORNING_STAR ->
                pattern(bars, 2) { i ->
                    val a = bars[i - 2]
                    val b = bars[i - 1]
                    val c = bars[i]
                    bearish(a) && body(a) >= frac(range(a), "0.5") && body(b) <= frac(body(a), "0.3") && bullish(c) &&
                        c.close > a.close.add(a.open).divide(TWO, MC)
                }
            IndicatorType.EVENING_STAR ->
                pattern(bars, 2) { i ->
                    val a = bars[i - 2]
                    val b = bars[i - 1]
                    val c = bars[i]
                    bullish(a) && body(a) >= frac(range(a), "0.5") && body(b) <= frac(body(a), "0.3") && bearish(c) &&
                        c.close < a.close.add(a.open).divide(TWO, MC)
                }
            else -> error("$type is not a candlestick pattern")
        }

    /** Computes every declared indicator series keyed by "ID.component". */
    fun compute(
        specs: List<IndicatorSpec>,
        bars: List<CandleData>,
        ctx: SeriesContext = SeriesContext.infer(bars),
    ): Map<String, List<BigDecimal?>> {
        val out = linkedMapOf<String, List<BigDecimal?>>()
        specs.forEach { s ->
            if (s.timeframe != null) {
                out.putAll(higherTimeframe(s, bars, ctx))
                return@forEach
            }
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
                IndicatorType.HIGHEST -> out["${s.id}.value"] = highest(bars, s.period!!)
                IndicatorType.LOWEST -> out["${s.id}.value"] = lowest(bars, s.period!!)
                IndicatorType.SWING_HIGH -> out["${s.id}.value"] = swingHigh(bars, s.period!!)
                IndicatorType.SWING_LOW -> out["${s.id}.value"] = swingLow(bars, s.period!!)
                IndicatorType.RELATIVE_VOLUME -> out["${s.id}.value"] = relativeVolume(bars, s.period!!)
                IndicatorType.PERIOD_LEVELS -> periodLevels(bars, s.anchor!!, ctx).forEach { (k, v) -> out["${s.id}.$k"] = v }
                IndicatorType.VWAP -> out["${s.id}.value"] = vwap(bars, s.anchor!!, ctx)
                IndicatorType.ANCHORED_VWAP -> out["${s.id}.value"] = anchoredVwap(bars, s.period!!, s.anchorPoint ?: AnchorPoint.LOWEST_LOW)
                IndicatorType.FIBONACCI -> fibonacci(bars, s.period!!).forEach { (k, v) -> out["${s.id}.$k"] = v }
                IndicatorType.VOLUME_PROFILE ->
                    (if (s.anchor != null) volumeProfileByPeriod(bars, s.anchor, ctx) else volumeProfile(bars, s.period!!)).forEach { (k, v) -> out["${s.id}.$k"] = v }
                else -> out["${s.id}.value"] = candlestick(s.type, bars)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ higher timeframes and calendar levels (D-042)

    /**
     * The indicator computed on completed daily/weekly/monthly bars; strategy bar i sees the value
     * of the latest period that was complete at i's close.
     */
    private fun higherTimeframe(
        s: IndicatorSpec,
        bars: List<CandleData>,
        ctx: SeriesContext,
    ): Map<String, List<BigDecimal?>> {
        val agg = Periods.aggregate(bars, s.timeframe!!, ctx)
        val complete = agg.completedAt.lastOrNull() ?: 0
        val htf = agg.periods.take(complete).map { it.toCandle() }
        val inner = compute(listOf(s.copy(timeframe = null)), htf, ctx)
        return inner.mapValues { (_, series) -> bars.indices.map { i -> agg.completedAt[i].takeIf { it > 0 }?.let { series.getOrNull(it - 1) } } }
    }

    /** Current period open/high/low so far, and the previous period's open, high, low, close and midpoint. */
    fun periodLevels(
        bars: List<CandleData>,
        anchor: Anchor,
        ctx: SeriesContext,
    ): Map<String, List<BigDecimal?>> {
        val agg = Periods.aggregate(bars, anchor, ctx)
        val out = IndicatorSpec.PERIOD_COMPONENTS.associateWith { MutableList<BigDecimal?>(bars.size) { null } }
        var high: BigDecimal? = null
        var low: BigDecimal? = null
        for (i in bars.indices) {
            val p = agg.periodOf[i]
            if (i == 0 || agg.periodOf[i - 1] != p) {
                high = null
                low = null
            }
            high = high?.max(bars[i].high) ?: bars[i].high
            low = low?.min(bars[i].low) ?: bars[i].low
            out.getValue("open")[i] = agg.periods[p].open
            out.getValue("high")[i] = high
            out.getValue("low")[i] = low
            // The previous period is complete: bar i already belongs to a later one.
            val prev = agg.periods.getOrNull(p - 1) ?: continue
            out.getValue("prevOpen")[i] = prev.open
            out.getValue("prevHigh")[i] = prev.high
            out.getValue("prevLow")[i] = prev.low
            out.getValue("prevClose")[i] = prev.close
            out.getValue("prevEq")[i] = prev.high.add(prev.low).divide(TWO, MC)
        }
        return out
    }

    private fun typical(b: CandleData) =
        b.high
            .add(b.low)
            .add(b.close)
            .divide(BigDecimal(3), MC)

    /** Volume-weighted average price since the start of the current day, week or month, including bar i. */
    fun vwap(
        bars: List<CandleData>,
        anchor: Anchor,
        ctx: SeriesContext,
    ): List<BigDecimal?> {
        val agg = Periods.aggregate(bars, anchor, ctx)
        val out = MutableList<BigDecimal?>(bars.size) { null }
        var pv = BigDecimal.ZERO
        var v = BigDecimal.ZERO
        for (i in bars.indices) {
            if (i == 0 || agg.periodOf[i - 1] != agg.periodOf[i]) {
                pv = BigDecimal.ZERO
                v = BigDecimal.ZERO
            }
            pv = pv.add(typical(bars[i]).multiply(bars[i].volume, MC))
            v = v.add(bars[i].volume)
            out[i] = if (v.signum() > 0) pv.divide(v, MC) else null
        }
        return out
    }

    /**
     * VWAP anchored at the lowest low (or highest high) of the previous [period] bars, accumulated
     * from that bar through bar i.
     */
    fun anchoredVwap(
        bars: List<CandleData>,
        period: Int,
        point: AnchorPoint,
    ): List<BigDecimal?> {
        val out = MutableList<BigDecimal?>(bars.size) { null }
        val pv = ArrayList<BigDecimal>(bars.size)
        val vol = ArrayList<BigDecimal>(bars.size)
        var a = BigDecimal.ZERO
        var b = BigDecimal.ZERO
        bars.forEach {
            a = a.add(typical(it).multiply(it.volume, MC))
            b = b.add(it.volume)
            pv += a
            vol += b
        }
        for (i in period until bars.size) {
            var j = i - period
            for (k in i - period until i) {
                val better = if (point == AnchorPoint.LOWEST_LOW) bars[k].low <= bars[j].low else bars[k].high >= bars[j].high
                if (better) j = k
            }
            val sumPv = pv[i].subtract(if (j > 0) pv[j - 1] else BigDecimal.ZERO)
            val sumV = vol[i].subtract(if (j > 0) vol[j - 1] else BigDecimal.ZERO)
            out[i] = if (sumV.signum() > 0) sumPv.divide(sumV, MC) else null
        }
        return out
    }

    /**
     * Fibonacci retracements of the high-low range of the previous [period] bars. When the high came
     * after the low (an up move) levels are measured down from the high; otherwise up from the low.
     */
    fun fibonacci(
        bars: List<CandleData>,
        period: Int,
    ): Map<String, List<BigDecimal?>> {
        val keys = FIB_LEVELS.keys + setOf("high", "low", "trend")
        val out = keys.associateWith { MutableList<BigDecimal?>(bars.size) { null } }
        for (i in period until bars.size) {
            var hi = i - period
            var lo = i - period
            for (k in i - period until i) {
                if (bars[k].high >= bars[hi].high) hi = k
                if (bars[k].low <= bars[lo].low) lo = k
            }
            val h = bars[hi].high
            val l = bars[lo].low
            val range = h.subtract(l)
            val up = hi > lo
            FIB_LEVELS.forEach { (k, r) -> out.getValue(k)[i] = if (up) h.subtract(range.multiply(r, MC)) else l.add(range.multiply(r, MC)) }
            out.getValue("high")[i] = h
            out.getValue("low")[i] = l
            out.getValue("trend")[i] = if (up) BigDecimal.ONE else BigDecimal.ONE.negate()
        }
        return out
    }

    /** Point of control and 70% value area of the previous [period] bars. */
    fun volumeProfile(
        bars: List<CandleData>,
        period: Int,
    ): Map<String, List<BigDecimal?>> {
        val out = PROFILE_KEYS.associateWith { MutableList<BigDecimal?>(bars.size) { null } }
        for (i in period until bars.size) {
            val p = profile(bars, i - period, i - 1) ?: continue
            out.getValue("poc")[i] = p[0]
            out.getValue("vah")[i] = p[1]
            out.getValue("val")[i] = p[2]
        }
        return out
    }

    /** Point of control and value area of the previous complete day, week or month. */
    fun volumeProfileByPeriod(
        bars: List<CandleData>,
        anchor: Anchor,
        ctx: SeriesContext,
    ): Map<String, List<BigDecimal?>> {
        val agg = Periods.aggregate(bars, anchor, ctx)
        val out = PROFILE_KEYS.associateWith { MutableList<BigDecimal?>(bars.size) { null } }
        val cache = mutableMapOf<Int, List<BigDecimal>?>()
        for (i in bars.indices) {
            val prev = agg.periodOf[i] - 1
            if (prev < 0) continue
            val p = cache.getOrPut(prev) { agg.periods[prev].let { profile(bars, it.firstIndex, it.lastIndex) } } ?: continue
            out.getValue("poc")[i] = p[0]
            out.getValue("vah")[i] = p[1]
            out.getValue("val")[i] = p[2]
        }
        return out
    }

    private val PROFILE_KEYS = listOf("poc", "vah", "val")
    private const val PROFILE_ROWS = 50
    private const val VALUE_AREA = 0.70

    /**
     * Volume profile estimated from candles: each bar's volume is spread evenly over its high-low
     * range in [PROFILE_ROWS] price rows. Returns [poc, vah, val]. Tick-level profiles differ slightly.
     */
    private fun profile(
        bars: List<CandleData>,
        from: Int,
        to: Int,
    ): List<BigDecimal>? {
        if (from < 0 || to < from) return null
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (k in from..to) {
            lo = minOf(lo, bars[k].low.toDouble())
            hi = maxOf(hi, bars[k].high.toDouble())
        }
        if (hi <= lo) return List(3) { BigDecimal.valueOf(hi) }
        val step = (hi - lo) / PROFILE_ROWS
        val vol = DoubleArray(PROFILE_ROWS)

        fun row(x: Double) = ((x - lo) / step).toInt().coerceIn(0, PROFILE_ROWS - 1)
        for (k in from..to) {
            val b = bars[k]
            val v = b.volume.toDouble()
            if (v <= 0) continue
            val l = b.low.toDouble()
            val h = b.high.toDouble()
            if (h <= l) {
                vol[row(l)] += v
                continue
            }
            for (r in row(l)..row(h)) {
                val overlap = minOf(h, lo + (r + 1) * step) - maxOf(l, lo + r * step)
                if (overlap > 0) vol[r] += v * overlap / (h - l)
            }
        }
        val total = vol.sum()
        if (total <= 0) return null
        val poc = vol.indices.maxByOrNull { vol[it] }!!
        var up = poc
        var dn = poc
        var included = vol[poc]
        while (included < VALUE_AREA * total && (up < PROFILE_ROWS - 1 || dn > 0)) {
            val above = if (up < PROFILE_ROWS - 1) vol[up + 1] else -1.0
            val below = if (dn > 0) vol[dn - 1] else -1.0
            if (above >= below) {
                up++
                included += vol[up]
            } else {
                dn--
                included += vol[dn]
            }
        }
        return listOf(lo + (poc + 0.5) * step, lo + (up + 1) * step, lo + dn * step).map { BigDecimal.valueOf(it) }
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
