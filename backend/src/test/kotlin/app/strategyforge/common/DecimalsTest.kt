package app.strategyforge.common

import app.strategyforge.common.money.BigMath
import app.strategyforge.common.money.Decimals
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.BigRange
import net.jqwik.api.constraints.Scale
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class DecimalsTest {
    @Test
    fun `NFR-002 report rounding is half-even and money scale is preserved`() {
        assertThat(Decimals.report(BigDecimal("2.345"))).isEqualByComparingTo("2.34")
        assertThat(Decimals.report(BigDecimal("2.355"))).isEqualByComparingTo("2.36")
        assertThat(Decimals.money(BigDecimal("1.1")).scale()).isEqualTo(12)
        assertThat(Decimals.floorToStep(BigDecimal("10.987"), BigDecimal("0.01"))).isEqualByComparingTo("10.98")
    }

    @Test
    fun `NFR-002 BigMath matches known constants`() {
        assertThat(BigMath.ln(BigDecimal.ONE)).isEqualByComparingTo("0")
        assertThat(BigMath.exp(BigDecimal.ONE).setScale(15, RoundingMode.HALF_EVEN)).isEqualByComparingTo("2.718281828459045")
        assertThat(BigMath.pow(BigDecimal("1.21"), BigDecimal("0.5")).setScale(20, RoundingMode.HALF_EVEN)).isEqualByComparingTo("1.10000000000000000000")
    }

    @Property(tries = 200)
    fun `NFR-002 exp and ln are inverse`(
        @ForAll @BigRange(min = "0.001", max = "1000000") @Scale(4) x: BigDecimal,
    ) {
        val back = BigMath.exp(BigMath.ln(x))
        assertThat(back.subtract(x).abs()).isLessThan(x.multiply(BigDecimal("1E-25")).max(BigDecimal("1E-25")))
    }
}
