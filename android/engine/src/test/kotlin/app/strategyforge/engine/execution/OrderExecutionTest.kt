package app.strategyforge.engine.execution

import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.db.str
import app.strategyforge.engine.portfolio.Account
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.order
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID

/** Ported from the Version 1 OrderExecutionIT: the same scenarios, run by the on-device engine. */
class OrderExecutionTest {
    private val e = TestEngine.create()

    private fun reconcileOk(pid: UUID) {
        val r = e.reconciliation.run(pid)
        assertThat(r.status).`as`(r.checks.filter { !it.passed }.toString()).isEqualTo("OK")
    }

    @Test
    fun `FR-080 FR-081 FR-085 market buy fills on the next replay step with linked snapshot, risk decision and balanced journal`() {
        val pid = e.portfolio()
        val r = e.order(pid, "ETH-USD", "BUY", "2")
        val order = r.order
        assertThat(order.status).`as`(r.risk.reasons.toString()).isEqualTo(OrderStatus.PENDING)
        assertThat(order.venue).isEqualTo("PAPER_SIMULATOR")
        assertThat(order.riskEvaluationId).isNotNull()
        assertThat(order.marketSnapshotId).isNotNull()
        assertThat(order.reservedAmount).isPositive()

        e.advance(1)
        assertThat(e.orders.get(order.id).status).isEqualTo(OrderStatus.FILLED)
        assertThat(e.orders.statusHistory(order.id).map { it.to }).containsExactly("CREATED", "VALIDATED", "PENDING", "FILLED")
        val exec = e.execution.executions(null, order.id, 10).single()
        assertThat(exec.price).isGreaterThanOrEqualTo(exec.referencePrice)

        val summary = e.portfolios.summary(pid)
        assertThat(summary.reservedCash).isZero()
        val pos = summary.positions.single()
        assertThat(pos.symbol).isEqualTo("ETH-USD")
        assertThat(pos.quantity).isEqualByComparingTo("2")
        // cash + cost basis == starting balance (no fees configured): equity conservation at the fill.
        assertThat(summary.cash.add(pos.costBasis)).isEqualByComparingTo("100000")
        reconcileOk(pid)
        val decision =
            e.db
                .sql("select decision from risk_evaluations where id = :id")
                .param("id", order.riskEvaluationId)
                .single { it.str("decision") }
        assertThat(decision).isEqualTo("ALLOW")
    }

    @Test
    fun `FR-082 large orders fill partially according to observed volume and participation rate`() {
        val pid = e.portfolio(balance = "5000000")
        val id = e.order(pid, "BTC-USD", "BUY", "6").order.id
        e.advance(1)
        assertThat(e.orders.get(id).status).isEqualTo(OrderStatus.PARTIALLY_FILLED)
        val first = e.execution.executions(null, id, 50)
        assertThat(first[0].liquidityModel).startsWith("PARTICIPATION_10")
        assertThat(first[0].quantity).isLessThan(BigDecimal("6"))
        e.advance(8)
        val later = e.execution.executions(null, id, 50)
        assertThat(later.size).isGreaterThan(1)
        val total = later.fold(BigDecimal.ZERO) { a, x -> a.add(x.quantity) }
        assertThat(total).isEqualByComparingTo(e.orders.get(id).filledQuantity)
        if (e.orders.get(id).status == OrderStatus.PARTIALLY_FILLED) e.orders.cancel(id, "test")
        reconcileOk(pid)
    }

    @Test
    fun `FR-084 sells beyond holdings and shorts without enablement are rejected and recorded`() {
        val pid = e.portfolio()
        val sell = e.order(pid, "SOL-USD", "SELL", "5").order
        assertThat(sell.status).isEqualTo(OrderStatus.REJECTED)
        assertThat(sell.rejectionReason).contains("HOLDINGS")
        assertThat(e.order(pid, "AAPL", "SELL_SHORT", "5").order.rejectionReason).contains("SHORTING_PERMITTED")
        assertThat(e.order(pid, "BTC-USD", "BUY", "100").order.rejectionReason).contains("BUYING_POWER")
        assertThat(e.order(pid, "AAPL", "BUY", "0.00001").order.rejectionReason).contains("QUANTITY_VALID")
        assertThat(e.notifications.list(100, unreadOnly = false).count { it.category == "ORDER_REJECTION" }).isGreaterThanOrEqualTo(4)
        reconcileOk(pid)
    }

    @Test
    fun `FR-083 FR-013 simulated short then cover realizes P and L with fees and requires recent auth to enable`() {
        val pid = e.portfolio(costModel = CostModel(commissionPerOrder = BigDecimal("1.00"), commissionPerShare = BigDecimal("0.005"), slippageBps = BigDecimal("2")))
        assertThatThrownBy { e.portfolios.setShorting(pid, true) }.isInstanceOf(EngineException::class.java).hasMessageContaining("Confirm it's you")
        e.auth.confirmed()
        assertThat(e.portfolios.setShorting(pid, true).shortingEnabled).isTrue()

        val short = e.order(pid, "MSFT", "SELL_SHORT", "10")
        assertThat(short.order.status).`as`(short.risk.reasons.toString()).isEqualTo(OrderStatus.PENDING)
        e.advance(1)
        val pos =
            e.portfolios
                .summary(pid)
                .positions
                .single()
        assertThat(pos.side).isEqualTo("SHORT")
        assertThat(pos.quantity).isEqualByComparingTo("-10")
        assertThat(e.order(pid, "MSFT", "BUY", "1").order.rejectionReason).contains("BUY_TO_COVER")
        val cover = e.order(pid, "MSFT", "BUY_TO_COVER", "10")
        assertThat(cover.order.status).`as`(cover.risk.reasons.toString()).isEqualTo(OrderStatus.PENDING)
        e.advance(1)
        val s = e.portfolios.summary(pid)
        assertThat(s.positions).isEmpty()
        assertThat(s.fees).isEqualByComparingTo("2.10") // 2 x (1.00 + 10 x 0.005)
        // Equity identity: cash = start + realized - fees - borrow + dividends (no open positions).
        val expected =
            BigDecimal("100000")
                .add(s.realizedPnl)
                .subtract(s.fees)
                .subtract(s.borrowFees)
                .add(s.dividends)
        assertThat(e.ledger.balance(pid, Account.CASH)).isEqualByComparingTo(expected)
        reconcileOk(pid)
    }

    @Test
    fun `FR-080 limit waits until marketable, stop triggers, cancel releases reservation`() {
        val pid = e.portfolio()
        val sol = e.instruments.bySymbol("SOL-USD")
        val last =
            e.market
                .refreshQuote(sol)
                .quote!!
                .last
        val lowLimit = last.multiply(BigDecimal("0.70")).setScale(3, RoundingMode.DOWN)
        val limit = e.order(pid, "SOL-USD", "BUY", "10", type = "LIMIT", limit = lowLimit.toPlainString()).order
        assertThat(limit.status).isEqualTo(OrderStatus.PENDING)
        e.advance(2)
        assertThat(e.orders.get(limit.id).status).isEqualTo(OrderStatus.PENDING)
        assertThat(e.portfolios.summary(pid).reservedCash).isPositive()
        assertThat(e.orders.cancel(limit.id, "test").status).isEqualTo(OrderStatus.CANCELLED)
        assertThat(e.portfolios.summary(pid).reservedCash).isZero()
        assertThatThrownBy { e.orders.cancel(limit.id, "again") }.hasMessageContaining("CANCELLED")

        val stopAbove = last.multiply(BigDecimal("1.40")).setScale(3, RoundingMode.UP)
        val stop = e.order(pid, "SOL-USD", "BUY", "5", type = "STOP", stop = stopAbove.toPlainString()).order
        e.advance(1)
        assertThat(e.orders.get(stop.id).triggered).isFalse()
        val stopBelow = last.multiply(BigDecimal("0.80")).setScale(3, RoundingMode.DOWN)
        val trig = e.order(pid, "SOL-USD", "BUY", "5", type = "STOP", stop = stopBelow.toPlainString()).order
        e.advance(1)
        val t = e.orders.get(trig.id)
        assertThat(t.triggered).isTrue()
        assertThat(t.status).isEqualTo(OrderStatus.FILLED)
        // FR-101: a filled stop order is announced as a stop/target event.
        val stopEvents =
            e.db
                .sql("select count(*) n from notification_events where category = 'STOP_TARGET' and entity_id = :id")
                .param("id", t.id.toString())
                .long()
        assertThat(stopEvents).isEqualTo(1)
        e.orders.cancel(stop.id, "test")
        reconcileOk(pid)
    }

    @Test
    fun `NFR-007 repeated processing never double-fills an order`() {
        val pid = e.portfolio()
        val id = e.order(pid, "ETH-USD", "BUY", "1").order.id
        // Move market time without running the pipeline, then process repeatedly.
        e.replay.setTime(e.marketClock.now().plusSeconds(60))
        e.market.refreshQuote(e.instruments.bySymbol("ETH-USD"))
        repeat(4) { e.execution.processAll() }
        val fills =
            e.db
                .sql("select count(*) n from paper_executions where order_id = :o")
                .param("o", id)
                .long()
        assertThat(fills).isEqualTo(1)
        reconcileOk(pid)
    }

    @Test
    fun `FR-113 every order row is a paper order on the simulator`() {
        val pid = e.portfolio()
        e.order(pid, "ETH-USD", "BUY", "1")
        assertThat(e.db.sql("select distinct venue from paper_orders").list { it.str("venue") }).containsExactly("PAPER_SIMULATOR")
        assertThatThrownBy { e.db.sql("update paper_orders set venue = 'BROKER'").update() }.isNotNull()
        assertThat(e.db.sql("select count(*) n from portfolios where account_type <> 'PAPER'").long()).isZero()
    }
}
