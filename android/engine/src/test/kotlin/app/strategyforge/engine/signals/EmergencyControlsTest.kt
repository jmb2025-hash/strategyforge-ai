package app.strategyforge.engine.signals

import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.db.str
import app.strategyforge.engine.execution.OrderStatus
import app.strategyforge.engine.strategy.StrategyStatus
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.Strategies.activate
import app.strategyforge.engine.support.Strategies.eligible
import app.strategyforge.engine.support.Strategies.recs
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.order
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode

/** MS-15, ported from the Version 1 EmergencyControlsIT. */
class EmergencyControlsTest {
    private val e = TestEngine.create()

    private fun code(block: () -> Unit): String =
        try {
            block()
            "none"
        } catch (x: EngineException) {
            x.code
        }

    private fun <T> withoutRecentAuth(block: () -> T): T {
        e.auth.forget()
        try {
            return block()
        } finally {
            e.auth.confirmed()
        }
    }

    @Test
    fun `MS-15 FR-075 pause all, prevent new positions, cancel pending, disable autonomous and close all simulated positions`() {
        val p = e.portfolio()
        val rec = e.eligible(Strategies.alwaysLong("Rec BTC", "1m"))
        val auto = e.eligible(Strategies.alwaysLong("Auto ETH", "1m", symbol = "ETH-USD", maxHoldingBars = 500))
        e.activate(rec, p, allocation = "40")
        e.activate(auto, p, ActivationMode.AUTONOMOUS, allocation = "40")
        assertThat(e.order(p, "SOL-USD", "BUY", "2").order.status).isEqualTo(OrderStatus.PENDING)
        e.advance(2)
        assertThat(e.emergency.state().pauseAll).isFalse()

        // Prevent New Positions: risk-increasing orders are blocked, reducing orders are allowed.
        e.emergency.preventNewPositions(ToggleRequest(true))
        val buy = e.order(p, "SOL-USD", "BUY", "1").order
        assertThat(buy.status).isEqualTo(OrderStatus.REJECTED)
        assertThat(buy.rejectionReason).contains("EMERGENCY_CONTROLS")
        assertThat(e.order(p, "SOL-USD", "SELL", "1").order.status).isEqualTo(OrderStatus.PENDING)
        // Releasing a control loosens safety and needs a recent device-lock confirmation.
        assertThat(withoutRecentAuth { code { e.emergency.preventNewPositions(ToggleRequest(false)) } }).isEqualTo("recent-authentication-required")
        e.emergency.preventNewPositions(ToggleRequest(false))

        // Disable Autonomous Mode pauses autonomous strategies only.
        val dis = e.emergency.disableAutonomous("drill")
        assertThat(dis.affected).containsExactly(auto)
        assertThat(e.strategies.get(auto).status).isEqualTo(StrategyStatus.PAUSED)
        assertThat(e.strategies.get(rec).status).isEqualTo(StrategyStatus.ACTIVE_RECOMMENDATION)

        // Cancel Pending Orders.
        val last = e.market.latestQuote(e.instruments.bySymbol("BTC-USD").id)!!.last
        val far = e.order(p, "BTC-USD", "BUY", "0.01", type = "LIMIT", limit = last.multiply(BigDecimal("0.97")).setScale(2, RoundingMode.DOWN).toPlainString()).order
        assertThat(far.status).isEqualTo(OrderStatus.PENDING)
        assertThat(e.emergency.cancelPending("drill").affected).contains(far.id)
        assertThat(e.orders.get(far.id).status).isEqualTo(OrderStatus.CANCELLED)

        // Pause All: strategies pause, pending recommendations close, no new orders or signals.
        e.advance(1)
        assertThat(e.recs(rec, "PENDING")).hasSize(1)
        val pause = e.emergency.pauseAll(ToggleRequest(true, "drill"))
        assertThat(pause.state.pauseAll).isTrue()
        assertThat(e.strategies.get(rec).status).isEqualTo(StrategyStatus.PAUSED)
        assertThat(e.recs(rec, "PENDING")).isEmpty()
        val signalsBefore = e.db.sql("select count(*) from signals").long()
        e.advance(2)
        assertThat(e.db.sql("select count(*) from signals").long()).isEqualTo(signalsBefore)
        assertThat(e.order(p, "SOL-USD", "SELL", "0.5").order.rejectionReason).contains("EMERGENCY_CONTROLS")

        // Close All Simulated Positions is separate: recent confirmation and a typed confirmation.
        val positions = e.portfolios.summary(p).positions
        assertThat(positions).isNotEmpty()
        assertThat(code { e.emergency.closeAll(CloseAllRequest("yes")) }).isEqualTo("confirmation-required")
        assertThat(withoutRecentAuth { code { e.emergency.closeAll(CloseAllRequest(EmergencyService.CONFIRMATION)) } }).isEqualTo("recent-authentication-required")
        val close = e.emergency.closeAll(CloseAllRequest(EmergencyService.CONFIRMATION))
        assertThat(close.affected).hasSize(positions.size)
        e.advance(2)
        assertThat(
            e.portfolios
                .summary(p)
                .positions
                .filter { it.quantity.signum() != 0 },
        ).isEmpty()
        assertThat(e.db.sql("select distinct source from paper_orders where source = 'EMERGENCY_CLOSE' and status = 'FILLED'").list { it.str("source") }).containsExactly("EMERGENCY_CLOSE")

        val actions = e.db.sql("select action from audit_events where category = 'EMERGENCY' order by id").list { it.str("action") }
        assertThat(actions).contains(
            "PREVENT_NEW_POSITIONS_ENGAGED",
            "PREVENT_NEW_POSITIONS_RELEASED",
            "DISABLE_AUTONOMOUS_MODE",
            "CANCEL_PENDING_ORDERS",
            "PAUSE_ALL_ENGAGED",
            "CLOSE_ALL_SIMULATED_POSITIONS",
        )
        e.emergency.pauseAll(ToggleRequest(false))
        assertThat(e.emergency.state().pauseAll).isFalse()
    }
}
