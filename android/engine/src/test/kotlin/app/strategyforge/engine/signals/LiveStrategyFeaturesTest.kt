package app.strategyforge.engine.signals

import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderStatus
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.Strategies.activate
import app.strategyforge.engine.support.Strategies.eligible
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.math.RoundingMode

/** D-042 in live paper trading: simulated crypto shorts, partial take profit and risk-based sizing. */
class LiveStrategyFeaturesTest {
    private val e = TestEngine.create()
    private val p = e.portfolio(balance = "100000")

    private fun base(
        name: String,
        extra: Map<String, Any?> = emptyMap(),
        exit: Map<String, Any?> = emptyMap(),
    ): Map<String, Any?> {
        val s = Strategies.alwaysLong(name, "1m", quantity = "0.1", maxHoldingBars = 500, stopLossPercent = 20, takeProfitPercent = 50)

        @Suppress("UNCHECKED_CAST")
        val exitRules = (s["exitRules"] as Map<String, Any?>) + exit
        return s + ("exitRules" to exitRules) + extra
    }

    private fun orders() = e.orders.list(p, null, 100)

    @Test
    fun `a crypto strategy that can short needs portfolio shorting on, then opens simulated shorts`() {
        @Suppress("UNCHECKED_CAST")
        val content =
            base("BTC short").let { s ->
                s + ("metadata" to (s["metadata"] as Map<String, Any?>) + ("direction" to "SHORT_ONLY")) +
                    ("riskLimits" to (s["riskLimits"] as Map<String, Any?>) + ("allowShort" to true))
            }
        val id = e.eligible(content)
        val refused = assertThrows<EngineException> { e.activate(id, p, ActivationMode.AUTONOMOUS) }
        assertThat(refused.message).contains("turn on simulated short selling")

        e.auth.confirmed()
        e.portfolios.setShorting(p, true)
        e.activate(id, p, ActivationMode.AUTONOMOUS)
        e.advance(3)
        val shorts = orders().filter { it.side == OrderSide.SELL_SHORT }
        assertThat(shorts).`as`(orders().toString()).isNotEmpty()
        assertThat(shorts.first().status).isEqualTo(OrderStatus.FILLED)
        val side =
            e.db
                .sql("select side from position_lots where portfolio_id = :p and closed_at is null")
                .param("p", p)
                .list { it.string("side") }
        assertThat(side).containsOnly("SHORT")
    }

    @Test
    fun `a partial take profit sells part of the position once and leaves the rest open`() {
        val id = e.eligible(base("BTC partial", exit = mapOf("partialTakeProfit" to mapOf("atPercent" to 0.01, "closePercent" to 50, "moveStopToEntry" to true))))
        e.activate(id, p, ActivationMode.AUTONOMOUS)
        var sells = emptyList<app.strategyforge.engine.execution.PaperOrder>()
        for (k in 0 until 60) {
            e.advance(1)
            sells = orders().filter { it.side == OrderSide.SELL && it.status == OrderStatus.FILLED }
            if (sells.isNotEmpty()) break
        }
        assertThat(sells).`as`("a partial exit within an hour").hasSize(1)
        assertThat(sells.single().quantity).isEqualByComparingTo("0.05")
        val (open, remaining) =
            e.db
                .sql("select quantity_open, quantity_remaining from position_lots where portfolio_id = :p and closed_at is null")
                .param("p", p)
                .single { it.dec("quantity_open") to it.dec("quantity_remaining") }
        assertThat(open).isEqualByComparingTo("0.1")
        assertThat(remaining).isEqualByComparingTo("0.05")
        // At most one partial per position; after it the rest may exit at the entry price (break-even stop).
        e.advance(20)
        val actions =
            e.db
                .sql("select action, triggered_rules from signals where strategy_id = :s order by created_at, id")
                .param("s", id)
                .list { (it.string("action") ?: "") + " " + (it.string("triggered_rules") ?: "") }
        val positions = actions.joinToString("|").split("ENTER_LONG").drop(1)
        assertThat(positions).isNotEmpty()
        positions.forEach { seg -> assertThat(Regex("PARTIAL_TAKE_PROFIT").findAll(seg).count()).`as`(actions.toString()).isLessThanOrEqualTo(1) }
        if (actions.any { "BREAKEVEN_STOP" in it }) {
            assertThat(actions.indexOfFirst { "PARTIAL_TAKE_PROFIT" in it }).isLessThan(actions.indexOfFirst { "BREAKEVEN_STOP" in it })
        }
    }

    @Test
    fun `risk-percent sizing is reduced to fit the risk profile's per-trade limit`() {
        // 1% risk with a 2% stop asks for half of equity; the global profile allows 20% per trade.
        val id = e.eligible(base("BTC risk", extra = mapOf("positionSizing" to mapOf("method" to "RISK_PERCENT", "value" to 1)), exit = mapOf("stopLossPercent" to 2)))
        e.activate(id, p, ActivationMode.AUTONOMOUS)
        e.advance(3)
        val buy = orders().first { it.side == OrderSide.BUY }
        assertThat(buy.status).`as`(buy.rejectionReason ?: "").isEqualTo(OrderStatus.FILLED)
        val notional = buy.quantity.multiply(buy.averageFillPrice!!).setScale(2, RoundingMode.HALF_EVEN)
        assertThat(notional).isBetween(BigDecimal(19_000), BigDecimal(20_100))
    }
}
