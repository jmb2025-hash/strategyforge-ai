package app.strategyforge.engine.autonomy

import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.execution.OrderStatus
import app.strategyforge.engine.risk.OrderSource
import app.strategyforge.engine.strategy.StrategyStatus
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.Strategies.eligible
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

/** D-035 one crypto and one stock strategy at a time; replacing asks what to do with open positions. */
class StrategySlotsTest {
    private val e = TestEngine.create()
    private val p = e.portfolio(balance = "1000000")

    private fun auto(allocation: String = "40") = ActivationRequest(ActivationMode.AUTONOMOUS, p, BigDecimal(allocation), true, AutonomyDisclosure.VERSION)

    private fun strategy(
        name: String,
        symbol: String = "BTC-USD",
    ): UUID = e.eligible(Strategies.alwaysLong(name, "1m", symbol = symbol, quantity = "0.1", maxHoldingBars = 500, stopLossPercent = 50, takeProfitPercent = 50))

    /** Activates [id] in autonomous mode and lets it open a position. */
    private fun running(id: UUID): UUID {
        e.auth.confirmed()
        e.slots.activate(id, auto(), null)
        e.advance(3)
        assertThat(e.slots.holdings(id)).`as`("the strategy opened a position").isNotEmpty()
        return id
    }

    @Test
    fun `replacing the active crypto strategy asks first, then KEEP hands its positions to the new strategy`() {
        val a = running(strategy("Slot A"))
        val held = e.slots.holdings(a).single()
        assertThat(held.symbol).isEqualTo("BTC-USD")

        val b = strategy("Slot B")
        val asked = assertThrows<EngineException> { e.slots.activate(b, auto(), null) }
        assertThat(asked.code).isEqualTo("slot-occupied")
        assertThat(asked.properties["currentStrategyName"]).isEqualTo("Slot A")
        assertThat(
            e.slots
                .slot(app.strategyforge.engine.market.AssetClass.CRYPTO)
                .strategy!!
                .id,
        ).`as`("nothing changed yet").isEqualTo(a)

        e.auth.confirmed()
        val r = e.slots.activate(b, auto(), PositionHandling.KEEP)
        assertThat(r.replaced!!.id).isEqualTo(a)
        assertThat(r.handedOver.map { it.symbol }).containsExactly("BTC-USD")
        assertThat(r.closingOrders).isEmpty()
        assertThat(e.strategies.get(a).status).isEqualTo(StrategyStatus.PAUSED)
        assertThat(
            e.slots
                .slot(app.strategyforge.engine.market.AssetClass.CRYPTO)
                .strategy!!
                .id,
        ).isEqualTo(b)
        assertThat(e.slots.holdings(a)).isEmpty()
        assertThat(
            e.slots
                .holdings(b)
                .single()
                .quantity,
        ).isEqualByComparingTo(held.quantity)
        assertThat(e.db.sql("select count(*) from audit_events where action = 'STRATEGY_SLOT_SWITCHED'").long()).isEqualTo(1)
    }

    @Test
    fun `KEEP leaves positions in symbols the new strategy does not trade open, and says so`() {
        val a = running(strategy("BTC strategy"))
        e.auth.confirmed()
        val r = e.slots.activate(strategy("ETH strategy", symbol = "ETH-USD"), auto(), PositionHandling.KEEP)
        assertThat(r.handedOver).isEmpty()
        assertThat(r.leftOpen.map { it.symbol }).containsExactly("BTC-USD")
        assertThat(e.slots.holdings(a).map { it.symbol }).containsExactly("BTC-USD")
    }

    @Test
    fun `CLOSE sells the replaced strategy's positions with market orders`() {
        val a = running(strategy("Closer A"))
        e.auth.confirmed()
        val r = e.slots.activate(strategy("Closer B", symbol = "ETH-USD"), auto(), PositionHandling.CLOSE)
        val order = e.orders.get(r.closingOrders.single())
        assertThat(order.source).isEqualTo(OrderSource.SYSTEM)
        assertThat(order.strategyId).isEqualTo(a)
        e.advance(2)
        assertThat(e.orders.get(order.id).status).isEqualTo(OrderStatus.FILLED)
        assertThat(e.slots.holdings(a)).isEmpty()
    }

    @Test
    fun `if the new strategy cannot be activated nothing changes`() {
        val a = running(strategy("Stays"))
        val b = strategy("Needs unlock")
        e.auth.forget()
        assertThrows<EngineException> { e.slots.activate(b, auto(), PositionHandling.CLOSE) }
        assertThat(
            e.slots
                .slot(app.strategyforge.engine.market.AssetClass.CRYPTO)
                .strategy!!
                .id,
        ).isEqualTo(a)
        assertThat(e.strategies.get(a).status).isEqualTo(StrategyStatus.ACTIVE_AUTONOMOUS)
        assertThat(e.orders.list(p, null, 100).none { it.source == OrderSource.SYSTEM }).`as`("no closing order survived the rollback").isTrue()
    }

    @Test
    fun `reactivating the same strategy never asks about positions`() {
        val a = running(strategy("Same"))
        e.auth.confirmed()
        val r = e.slots.activate(a, auto("30"), null)
        assertThat(r.replaced).isNull()
        assertThat(r.slot.activation!!.allocationPercent).isEqualByComparingTo("30")
    }
}
