package app.strategyforge.execution

import app.strategyforge.portfolio.ReconciliationService
import app.strategyforge.support.IntegrationTest
import app.strategyforge.support.Replay
import app.strategyforge.support.TestHttp
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors

class OrderExecutionIT : IntegrationTest() {
    @Autowired lateinit var engine: ExecutionEngine

    @Autowired lateinit var reconciliation: ReconciliationService

    private fun reconcileOk(
        h: TestHttp,
        pid: String,
    ) {
        val r = h.post("/v1/portfolios/$pid/reconciliation")
        assertThat(r.json["status"].asText()).`as`(r.body).isEqualTo("OK")
    }

    @Test
    fun `FR-080 FR-081 FR-085 market buy fills on the next replay step with linked snapshot, risk decision and balanced journal`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h)
        val r = Replay.order(h, pid, "ETH-USD", "BUY", "2")
        assertThat(r.status).`as`(r.body).isEqualTo(201)
        val order = r.json["order"]
        assertThat(order["status"].asText()).isEqualTo("PENDING")
        assertThat(order["venue"].asText()).isEqualTo("PAPER_SIMULATOR")
        assertThat(order["riskEvaluationId"].asText()).isNotBlank()
        assertThat(order["marketSnapshotId"].asText()).isNotBlank()
        assertThat(BigDecimal(order["reservedAmount"].asText())).isPositive()
        val id = order["id"].asText()

        Replay.advance(h, 1)
        val detail = h.get("/v1/orders/$id").json
        assertThat(detail["order"]["status"].asText()).isEqualTo("FILLED")
        assertThat(detail["history"].map { it["to"].asText() }).containsExactly("CREATED", "VALIDATED", "PENDING", "FILLED")
        val exec = detail["executions"][0]
        assertThat(BigDecimal(exec["price"].asText())).isGreaterThanOrEqualTo(BigDecimal(exec["referencePrice"].asText()))
        assertThat(exec["marketSnapshotId"].asText()).isNotBlank()
        assertThat(exec["journalId"].asText()).isNotBlank()

        val summary = h.get("/v1/portfolios/$pid/summary").json
        assertThat(BigDecimal(summary["reservedCash"].asText())).isZero()
        val pos = summary["positions"].single()
        assertThat(pos["symbol"].asText()).isEqualTo("ETH-USD")
        assertThat(BigDecimal(pos["quantity"].asText())).isEqualByComparingTo("2")
        // cash + cost basis == starting balance (no fees configured) - equity conservation at the fill.
        assertThat(BigDecimal(summary["cash"].asText()).add(BigDecimal(pos["costBasis"].asText()))).isEqualByComparingTo("100000")
        reconcileOk(h, pid)
        val risk =
            jdbc
                .sql("select decision from risk_evaluations where id = cast(:id as uuid)")
                .param("id", order["riskEvaluationId"].asText())
                .query(String::class.java)
                .single()
        assertThat(risk).isEqualTo("ALLOW")
    }

    @Test
    fun `FR-082 large orders fill partially according to observed volume and participation rate`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h, balance = "5000000")
        val r = Replay.order(h, pid, "BTC-USD", "BUY", "6")
        val id = r.json["order"]["id"].asText()
        Replay.advance(h, 1)
        val first = h.get("/v1/orders/$id").json
        assertThat(first["order"]["status"].asText()).isEqualTo("PARTIALLY_FILLED")
        val execs = first["executions"]
        assertThat(execs[0]["liquidityModel"].asText()).startsWith("PARTICIPATION_10")
        assertThat(BigDecimal(execs[0]["quantity"].asText())).isLessThan(BigDecimal("6"))
        Replay.advance(h, 8)
        val later = h.get("/v1/orders/$id").json
        assertThat(later["executions"].size()).isGreaterThan(1)
        val total = later["executions"].fold(BigDecimal.ZERO) { a, e -> a.add(BigDecimal(e["quantity"].asText())) }
        assertThat(total).isEqualByComparingTo(later["order"]["filledQuantity"].asText())
        if (later["order"]["status"].asText() == "PARTIALLY_FILLED") h.post("/v1/orders/$id/cancel")
        reconcileOk(h, pid)
    }

    @Test
    fun `FR-084 sells beyond holdings and shorts without enablement are rejected and recorded`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h)
        val sell = Replay.order(h, pid, "SOL-USD", "SELL", "5")
        assertThat(sell.status).isEqualTo(201)
        assertThat(sell.json["order"]["status"].asText()).isEqualTo("REJECTED")
        assertThat(sell.json["order"]["rejectionReason"].asText()).contains("HOLDINGS")
        val short = Replay.order(h, pid, "AAPL", "SELL_SHORT", "5")
        assertThat(short.json["order"]["status"].asText()).isEqualTo("REJECTED")
        assertThat(short.json["order"]["rejectionReason"].asText()).contains("SHORTING_PERMITTED")
        val tooBig = Replay.order(h, pid, "BTC-USD", "BUY", "100")
        assertThat(tooBig.json["order"]["rejectionReason"].asText()).contains("BUYING_POWER")
        val badQty = Replay.order(h, pid, "AAPL", "BUY", "0.00001")
        assertThat(badQty.json["order"]["rejectionReason"].asText()).contains("QUANTITY_VALID")
        val inbox = h.get("/v1/notifications").json["items"].count { it["category"].asText() == "ORDER_REJECTION" }
        assertThat(inbox).isGreaterThanOrEqualTo(4)
        reconcileOk(h, pid)
    }

    @Test
    fun `FR-083 FR-013 simulated short then cover realizes P and L with fees and requires recent auth to enable`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h, costModel = mapOf("commissionPerOrder" to "1.00", "commissionPerShare" to "0.005", "slippageBps" to "2"))
        jdbc.sql("update sessions set last_authenticated_at = now() - interval '1 hour'").update()
        assertThat(h.put("/v1/portfolios/$pid/shorting", mapOf("enabled" to true)).status).isEqualTo(403)
        h.post("/v1/auth/reauthenticate", mapOf("password" to TestOwner.PASSWORD))
        assertThat(h.put("/v1/portfolios/$pid/shorting", mapOf("enabled" to true)).json["shortingEnabled"].asBoolean()).isTrue()

        val short = Replay.order(h, pid, "MSFT", "SELL_SHORT", "10")
        assertThat(short.json["order"]["status"].asText()).`as`(short.body).isEqualTo("PENDING")
        Replay.advance(h, 1)
        val pos = h.get("/v1/positions?portfolioId=$pid").json.single()
        assertThat(pos["side"].asText()).isEqualTo("SHORT")
        assertThat(BigDecimal(pos["quantity"].asText())).isEqualByComparingTo("-10")
        // Selling more than held long is still refused while short.
        assertThat(Replay.order(h, pid, "MSFT", "BUY", "1").json["order"]["rejectionReason"].asText()).contains("BUY_TO_COVER")
        val cover = Replay.order(h, pid, "MSFT", "BUY_TO_COVER", "10")
        assertThat(cover.json["order"]["status"].asText()).`as`(cover.body).isEqualTo("PENDING")
        Replay.advance(h, 1)
        assertThat(h.get("/v1/positions?portfolioId=$pid").json.size()).isZero()
        val s = h.get("/v1/portfolios/$pid/summary").json
        assertThat(BigDecimal(s["fees"].asText())).isEqualByComparingTo("2.10") // 2 x (1.00 + 10 x 0.005)
        val balances = h.get("/v1/ledger/balances?portfolioId=$pid").json
        val realized = BigDecimal(s["realizedPnl"].asText())
        // Equity identity: cash = start + realized - fees - borrow + dividends (no open positions).
        val expected =
            BigDecimal("100000")
                .add(realized)
                .subtract(BigDecimal(s["fees"].asText()))
                .subtract(BigDecimal(s["borrowFees"].asText()))
                .add(BigDecimal(s["dividends"].asText()))
        assertThat(BigDecimal(balances["CASH"].asText())).isEqualByComparingTo(expected)
        reconcileOk(h, pid)
    }

    @Test
    fun `FR-080 limit waits until marketable, stop triggers, cancel releases reservation`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h)
        val last = BigDecimal(h.get("/v1/market-data/quotes/SOL-USD?refresh=true").json["last"].asText())
        val lowLimit = last.multiply(BigDecimal("0.70")).setScale(3, java.math.RoundingMode.DOWN)
        val limit = Replay.order(h, pid, "SOL-USD", "BUY", "10", type = "LIMIT", limit = lowLimit.toPlainString())
        assertThat(limit.json["order"]["status"].asText()).`as`(limit.body).isEqualTo("PENDING")
        Replay.advance(h, 2)
        val lid = limit.json["order"]["id"].asText()
        assertThat(h.get("/v1/orders/$lid").json["order"]["status"].asText()).isEqualTo("PENDING")
        val reservedBefore = BigDecimal(h.get("/v1/portfolios/$pid/summary").json["reservedCash"].asText())
        assertThat(reservedBefore).isPositive()
        val cancel = h.post("/v1/orders/$lid/cancel", mapOf("reason" to "test"))
        assertThat(cancel.json["status"].asText()).isEqualTo("CANCELLED")
        assertThat(BigDecimal(h.get("/v1/portfolios/$pid/summary").json["reservedCash"].asText())).isZero()
        assertThat(h.post("/v1/orders/$lid/cancel").status).isEqualTo(409)

        val stopAbove = last.multiply(BigDecimal("1.40")).setScale(3, java.math.RoundingMode.UP)
        val stop = Replay.order(h, pid, "SOL-USD", "BUY", "5", type = "STOP", stop = stopAbove.toPlainString())
        Replay.advance(h, 1)
        assertThat(h.get("/v1/orders/${stop.json["order"]["id"].asText()}").json["order"]["triggered"].asBoolean()).isFalse()
        val stopBelow = last.multiply(BigDecimal("0.80")).setScale(3, java.math.RoundingMode.DOWN)
        val trig = Replay.order(h, pid, "SOL-USD", "BUY", "5", type = "STOP", stop = stopBelow.toPlainString())
        Replay.advance(h, 1)
        val t = h.get("/v1/orders/${trig.json["order"]["id"].asText()}").json["order"]
        assertThat(t["triggered"].asBoolean()).isTrue()
        assertThat(t["status"].asText()).isEqualTo("FILLED")
        h.post("/v1/orders/${stop.json["order"]["id"].asText()}/cancel")
        reconcileOk(h, pid)
    }

    @Test
    fun `NFR-007 duplicate submissions with one idempotency key create one order, concurrently too`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h)
        val key = "order-" + UUID.randomUUID()
        val pool = Executors.newFixedThreadPool(6)
        val results = (1..6).map { pool.submit(Callable { Replay.order(TestHttp(baseUrl, h.token), pid, "ETH-USD", "BUY", "1", key = key) }) }.map { it.get() }
        pool.shutdown()
        assertThat(results.map { it.status }.toSet()).containsExactly(201)
        assertThat(results.map { it.json["order"]["id"].asText() }.toSet()).hasSize(1)
        val count =
            jdbc
                .sql("select count(*) from paper_orders where portfolio_id = cast(:p as uuid)")
                .param("p", pid)
                .query(Int::class.java)
                .single()
        assertThat(count).isEqualTo(1)
        val reuse = Replay.order(h, pid, "ETH-USD", "BUY", "2", key = key)
        assertThat(reuse.status).isEqualTo(422)
    }

    @Test
    fun `NFR-007 concurrent execution workers never double-fill an order`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h)
        val id = Replay.order(h, pid, "ETH-USD", "BUY", "1").json["order"]["id"].asText()
        // Move market time forward without running the pipeline, then race several workers.
        val now = java.time.Instant.parse(h.get("/v1/market-data/clock").json["now"].asText())
        h.post("/v1/market-data/replay/set", mapOf("to" to now.plusSeconds(60).toString()))
        h.get("/v1/market-data/quotes/ETH-USD?refresh=true")
        val pool = Executors.newFixedThreadPool(4)
        (1..4).map { pool.submit { engine.processAll() } }.forEach { it.get() }
        pool.shutdown()
        val fills =
            jdbc
                .sql("select count(*) from paper_executions where order_id = cast(:o as uuid)")
                .param("o", id)
                .query(Int::class.java)
                .single()
        assertThat(fills).isEqualTo(1)
        reconcileOk(h, pid)
    }
}
