package app.strategyforge.engine.money

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Financial precision rules (NFR-002, Architecture section "Financial precision").
 * Floating point is never used for financial values.
 */
object Decimals {
    const val MONEY_SCALE = 12
    const val QUANTITY_SCALE = 18
    const val PRICE_SCALE = 12
    const val PERCENT_SCALE = 8
    const val REPORT_SCALE = 2

    /** Precision used for intermediate arithmetic (division, statistics). */
    val MC: MathContext = MathContext(34, RoundingMode.HALF_EVEN)

    val HUNDRED: BigDecimal = BigDecimal("100")
    val BPS: BigDecimal = BigDecimal("10000")

    fun money(v: BigDecimal): BigDecimal = v.setScale(MONEY_SCALE, RoundingMode.HALF_EVEN)

    fun quantity(v: BigDecimal): BigDecimal = v.setScale(QUANTITY_SCALE, RoundingMode.HALF_EVEN)

    fun price(v: BigDecimal): BigDecimal = v.setScale(PRICE_SCALE, RoundingMode.HALF_EVEN)

    /** Reports round HALF_EVEN to cents; source values are preserved separately. */
    fun percent(v: BigDecimal): BigDecimal = v.setScale(PERCENT_SCALE, RoundingMode.HALF_EVEN)

    fun report(v: BigDecimal): BigDecimal = v.setScale(REPORT_SCALE, RoundingMode.HALF_EVEN)

    fun percentOf(
        value: BigDecimal,
        percent: BigDecimal,
    ): BigDecimal = value.multiply(percent).divide(HUNDRED, MC)

    /** Returns (part / whole * 100), or null when whole is zero. */
    fun ratioPercent(
        part: BigDecimal,
        whole: BigDecimal,
    ): BigDecimal? = if (whole.signum() == 0) null else part.multiply(HUNDRED).divide(whole, MC).setScale(PERCENT_SCALE, RoundingMode.HALF_EVEN)

    fun div(
        a: BigDecimal,
        b: BigDecimal,
    ): BigDecimal = a.divide(b, MC)

    fun isZero(v: BigDecimal): Boolean = v.signum() == 0

    /** Rounds a quantity down to a lot/step size (never rounds up into more exposure). */
    fun floorToStep(
        qty: BigDecimal,
        step: BigDecimal,
    ): BigDecimal {
        if (step.signum() <= 0) return qty
        val steps = qty.divide(step, 0, RoundingMode.FLOOR)
        return quantity(steps.multiply(step))
    }

    fun parse(s: String): BigDecimal = BigDecimal(s.trim())

    fun min(
        a: BigDecimal,
        b: BigDecimal,
    ): BigDecimal = if (a <= b) a else b

    fun max(
        a: BigDecimal,
        b: BigDecimal,
    ): BigDecimal = if (a >= b) a else b
}

/** Compares by numeric value, ignoring scale (BigDecimal.equals is scale-sensitive). */
infix fun BigDecimal.eqv(other: BigDecimal): Boolean = this.compareTo(other) == 0

fun Iterable<BigDecimal>.sumDecimal(): BigDecimal = fold(BigDecimal.ZERO) { acc, v -> acc.add(v) }
