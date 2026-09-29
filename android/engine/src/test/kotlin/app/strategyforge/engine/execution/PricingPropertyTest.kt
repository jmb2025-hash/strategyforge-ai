package app.strategyforge.engine.execution

import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.portfolio.CostModel
import net.jqwik.api.Assume
import net.jqwik.api.ForAll
import net.jqwik.api.Property
import net.jqwik.api.constraints.BigRange
import net.jqwik.api.constraints.Scale
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

class PricingPropertyTest {
    private val model = CostModel(slippageBps = BigDecimal("5"), commissionPercent = BigDecimal("0.01"), commissionPerOrder = BigDecimal("1"))
    private val inc = BigDecimal("0.01")

    @Property(tries = 500)
    fun `FR-082 NFR-002 buys never fill below the ask and sells never above the bid`(
        @ForAll @BigRange(min = "1", max = "100000") @Scale(2) mid: BigDecimal,
        @ForAll @BigRange(min = "0", max = "2") @Scale(2) halfSpread: BigDecimal,
    ) {
        Assume.that(mid >= halfSpread.multiply(BigDecimal(10)).max(BigDecimal.ONE))
        val q = QuoteInput(mid.subtract(halfSpread), mid.add(halfSpread), mid)
        val buy = Pricing.fillPrice(q, true, AssetClass.US_EQUITY, model, null, inc)!!
        val sell = Pricing.fillPrice(q, false, AssetClass.US_EQUITY, model, null, inc)!!
        assertThat(buy.price).isGreaterThanOrEqualTo(q.ask)
        assertThat(sell.price).isLessThanOrEqualTo(q.bid)
        assertThat(buy.price.remainder(inc).signum()).isZero()
        assertThat(sell.price.remainder(inc).signum()).isZero()
        assertThat(buy.spreadCostPerUnit.signum()).isGreaterThanOrEqualTo(0)
        assertThat(buy.slippagePerUnit.signum()).isGreaterThanOrEqualTo(0)
    }

    @Property(tries = 500)
    fun `FR-080 limit orders never execute through their limit`(
        @ForAll @BigRange(min = "1", max = "10000") @Scale(2) last: BigDecimal,
        @ForAll @BigRange(min = "-5", max = "5") @Scale(2) offsetPercent: BigDecimal,
    ) {
        val limit = last.multiply(BigDecimal.ONE.add(offsetPercent.movePointLeft(2))).setScale(2, RoundingMode.HALF_EVEN).max(BigDecimal("0.01"))
        val q = QuoteInput(null, null, last)
        Pricing.fillPrice(q, true, AssetClass.CRYPTO, model, limit, inc)?.let { assertThat(it.price).isLessThanOrEqualTo(limit) }
        Pricing.fillPrice(q, false, AssetClass.CRYPTO, model, limit, inc)?.let { assertThat(it.price).isGreaterThanOrEqualTo(limit) }
    }

    @Property(tries = 300)
    fun `NFR-002 buy reservation always covers notional plus commission at the reservation price`(
        @ForAll @BigRange(min = "0.0001", max = "5000") @Scale(4) qty: BigDecimal,
        @ForAll @BigRange(min = "0.01", max = "5000") @Scale(2) price: BigDecimal,
    ) {
        val reserve = Pricing.buyReservation(model, qty, price)
        val notional = qty.multiply(price)
        assertThat(reserve).isGreaterThanOrEqualTo(notional.add(Pricing.commission(model, qty, notional, true)))
    }

    @Test
    fun `FR-082 fallback spread is 0 10 percent for equities and 0 20 percent for crypto`() {
        val m = CostModel(slippageBps = BigDecimal.ZERO)
        val eq = Pricing.touch(QuoteInput(null, null, BigDecimal("100")), true, AssetClass.US_EQUITY, m)
        val cr = Pricing.touch(QuoteInput(null, null, BigDecimal("100")), true, AssetClass.CRYPTO, m)
        assertThat(eq.first).isEqualByComparingTo("100.05")
        assertThat(cr.first).isEqualByComparingTo("100.10")
        assertThat(eq.second).startsWith("FALLBACK")
        assertThat(Pricing.touch(QuoteInput(BigDecimal("99"), BigDecimal("101"), BigDecimal("100")), true, AssetClass.US_EQUITY, m).second).isEqualTo("QUOTED")
    }

    @Test
    fun `FR-080 stop triggers and order state machine transitions`() {
        assertThat(Pricing.stopTriggered(OrderSide.BUY, BigDecimal("10"), BigDecimal("10"))).isTrue()
        assertThat(Pricing.stopTriggered(OrderSide.SELL, BigDecimal("10"), BigDecimal("10.01"))).isFalse()
        assertThat(OrderStateMachine.canTransition(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED)).isTrue()
        assertThat(OrderStateMachine.canTransition(OrderStatus.FILLED, OrderStatus.CANCELLED)).isFalse()
        assertThat(OrderStateMachine.canTransition(OrderStatus.REJECTED, OrderStatus.PENDING)).isFalse()
        assertThat(OrderStateMachine.canTransition(OrderStatus.CREATED, OrderStatus.VALIDATED)).isTrue()
    }
}
