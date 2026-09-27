package app.strategyforge.execution

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

/** Equity-session behaviour and corporate actions on a private database (the replay clock moves far). */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class EquitySessionIT : FreshDatabaseTest() {
    @Autowired lateinit var maintenance: PortfolioMaintenance

    @Test
    @Order(1)
    fun `FR-082 MS-04 splits and dividends post to the ledger and lots and reconcile`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h, name = "Dividends")
        assertThat(Replay.order(h, pid, "JPM", "BUY", "10").json["order"]["status"].asText()).isEqualTo("PENDING")
        Replay.advance(h, 1)
        assertThat(
            BigDecimal(
                h
                    .get("/v1/positions?portfolioId=$pid")
                    .json
                    .single()["quantity"]
                    .asText(),
            ),
        ).isEqualByComparingTo("10")
        val jpm = jdbc.sql("select id from instruments where symbol = 'JPM'").query(java.util.UUID::class.java).single()
        val today = "2026-06-22"
        // Provider-sourced corporate actions for the current market date (test fixture rows).
        jdbc
            .sql("insert into corporate_actions(instrument_id, action_type, ex_date, pay_date, ratio_new, ratio_old, provider, received_at) values (:i, 'SPLIT', cast(:d as date), cast(:d as date), 3, 1, 'REPLAY', now())")
            .param("i", jpm)
            .param("d", today)
            .update()
        jdbc
            .sql("insert into corporate_actions(instrument_id, action_type, ex_date, pay_date, cash_amount, provider, received_at) values (:i, 'CASH_DIVIDEND', cast(:d as date), cast(:d as date), 0.50, 'REPLAY', now())")
            .param("i", jpm)
            .param("d", today)
            .update()
        val costBefore =
            BigDecimal(
                h
                    .get("/v1/positions?portfolioId=$pid")
                    .json
                    .single()["costBasis"]
                    .asText(),
            )
        maintenance.runAll(force = true)
        maintenance.runAll(force = true) // idempotent: applied once
        val pos = h.get("/v1/positions?portfolioId=$pid").json.single()
        assertThat(BigDecimal(pos["quantity"].asText())).isEqualByComparingTo("30")
        assertThat(BigDecimal(pos["costBasis"].asText())).isEqualByComparingTo(costBefore)
        val summary = h.get("/v1/portfolios/$pid/summary").json
        // Split applied first (ex-date ordering by insertion); dividend entitlement uses the post-split position of 30 shares.
        assertThat(BigDecimal(summary["dividends"].asText())).isIn(BigDecimal("15.000000000000"), BigDecimal("5.000000000000"))
        val journals = h.get("/v1/ledger?portfolioId=$pid").json["items"].map { it["type"].asText() }
        assertThat(journals).contains("SPLIT", "DIVIDEND", "EXECUTION", "RESERVATION", "FUNDING")
        assertThat(h.post("/v1/portfolios/$pid/reconciliation").json["status"].asText()).isEqualTo("OK")
    }

    @Test
    @Order(2)
    fun `FR-083 forced cover is created when short maintenance is breached and borrow fees accrue daily`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h, name = "Shorts", costModel = mapOf("shortMaintenancePercent" to "200", "shortInitialMarginPercent" to "50"))
        h.post("/v1/auth/reauthenticate", mapOf("password" to TestOwner.PASSWORD))
        h.put("/v1/portfolios/$pid/shorting", mapOf("enabled" to true))
        // Short enough that equity < 200% of short value triggers the forced cover.
        val r = Replay.order(h, pid, "SPY", "SELL_SHORT", "200")
        assertThat(r.json["order"]["status"].asText()).`as`(r.body).isEqualTo("PENDING")
        Replay.advance(h, 1)
        val covers =
            jdbc
                .sql("select count(*) from paper_orders where portfolio_id = cast(:p as uuid) and source = 'FORCED_COVER'")
                .param("p", pid)
                .query(Int::class.java)
                .single()
        assertThat(covers).isEqualTo(1)
        val borrow =
            jdbc
                .sql("select count(*) from borrow_accruals where portfolio_id = cast(:p as uuid)")
                .param("p", pid)
                .query(Int::class.java)
                .single()
        assertThat(borrow).isEqualTo(1)
        Replay.advance(h, 2)
        assertThat(h.get("/v1/positions?portfolioId=$pid").json.size()).isZero()
        val inbox = h.get("/v1/notifications").json["items"].map { it["title"].asText() }
        assertThat(inbox).anyMatch { it.contains("Forced cover") }
        assertThat(h.post("/v1/portfolios/$pid/reconciliation").json["status"].asText()).isEqualTo("OK")
    }

    @Test
    @Order(3)
    fun `FR-082 FR-024 equity DAY orders expire at the session close and stale after-hours data blocks new risk`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h, name = "Session")
        val price = BigDecimal(h.get("/v1/market-data/quotes/AAPL?refresh=true").json["last"].asText())
        val limit = price.multiply(BigDecimal("0.80")).setScale(2, java.math.RoundingMode.DOWN)
        val day = Replay.order(h, pid, "AAPL", "BUY", "1", type = "LIMIT", limit = limit.toPlainString(), tif = "DAY")
        val id = day.json["order"]["id"].asText()
        assertThat(day.json["order"]["expiresAt"].asText()).isEqualTo("2026-06-22T20:00:00Z")
        h.post("/v1/market-data/replay/set", mapOf("to" to "2026-06-22T19:58:00Z"))
        Replay.advance(h, 3)
        val o = h.get("/v1/orders/$id").json["order"]
        assertThat(o["status"].asText()).isEqualTo("EXPIRED")
        assertThat(BigDecimal(h.get("/v1/portfolios/$pid/summary").json["reservedCash"].asText())).isZero()
        // After the close the quote ages beyond the execution limit: new risk is refused (stale data blocks trading).
        Replay.advance(h, 5)
        val mkt = Replay.order(h, pid, "AAPL", "BUY", "1", tif = "GTC")
        assertThat(mkt.json["order"]["status"].asText()).isEqualTo("REJECTED")
        assertThat(mkt.json["order"]["rejectionReason"].asText()).contains("MARKET_DATA_VERIFIED")
        assertThat(h.post("/v1/portfolios/$pid/reconciliation").json["status"].asText()).isEqualTo("OK")
    }
}
