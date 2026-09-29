package app.strategyforge.engine.money

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Transcendental functions implemented on BigDecimal so statistics such as
 * annualized return never pass through floating point (NFR-002).
 */
object BigMath {
    private val MC = MathContext(40, RoundingMode.HALF_EVEN)
    private val TWO = BigDecimal(2)
    private val LN2 = BigDecimal("0.6931471805599453094172321214581765680755")

    /** Natural logarithm for x > 0 using range reduction and the atanh series. */
    fun ln(x: BigDecimal): BigDecimal {
        require(x.signum() > 0) { "ln undefined for non-positive values" }
        var v = x
        var k = 0
        while (v > TWO) {
            v = v.divide(TWO, MC)
            k++
        }
        while (v < BigDecimal.ONE) {
            v = v.multiply(TWO, MC)
            k--
        }
        // ln(v) = 2 * atanh((v-1)/(v+1))
        val y = v.subtract(BigDecimal.ONE).divide(v.add(BigDecimal.ONE), MC)
        val y2 = y.multiply(y, MC)
        var term = y
        var sum = BigDecimal.ZERO
        var n = 1
        while (true) {
            val add = term.divide(BigDecimal(n), MC)
            if (add.abs() < BigDecimal("1E-38")) break
            sum = sum.add(add, MC)
            term = term.multiply(y2, MC)
            n += 2
            if (n > 2000) break
        }
        return sum.multiply(TWO, MC).add(LN2.multiply(BigDecimal(k), MC), MC)
    }

    /** e^x using argument halving and the Taylor series. */
    fun exp(x: BigDecimal): BigDecimal {
        var r = x
        var halvings = 0
        while (r.abs() > BigDecimal("0.5")) {
            r = r.divide(TWO, MC)
            halvings++
        }
        var sum = BigDecimal.ONE
        var term = BigDecimal.ONE
        var n = 1
        while (n < 200) {
            term = term.multiply(r, MC).divide(BigDecimal(n), MC)
            if (term.abs() < BigDecimal("1E-38")) break
            sum = sum.add(term, MC)
            n++
        }
        repeat(halvings) { sum = sum.multiply(sum, MC) }
        return sum
    }

    /** base^exponent for base > 0 and arbitrary decimal exponent. */
    fun pow(
        base: BigDecimal,
        exponent: BigDecimal,
    ): BigDecimal = exp(ln(base).multiply(exponent, MC))

    fun sqrt(x: BigDecimal): BigDecimal = if (x.signum() == 0) BigDecimal.ZERO else x.sqrt(MC)
}
