package app.strategyforge.signals

import app.strategyforge.identity.RecentAuth
import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.Strategies
import app.strategyforge.support.TestHttp
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

/** MS-15: every emergency control (FR-075). */
class EmergencyControlsIT : FreshDatabaseTest() {
    private fun status(
        h: TestHttp,
        sid: String,
    ) = h.get("/v1/strategies/$sid").json["strategy"]["status"].asText()

    private fun <T> withoutRecentAuth(block: () -> T): T {
        val window = RecentAuth.window
        try {
            RecentAuth.window = Duration.ofMillis(1)
            Thread.sleep(20)
            return block()
        } finally {
            RecentAuth.window = window
        }
    }

    @Test
    fun `MS-15 FR-075 pause all, prevent new positions, cancel pending, disable autonomous and close all simulated positions`() {
        val h = TestOwner.client(baseUrl)
        val p = Replay.portfolio(h)
        val rec = Strategies.eligible(h, Strategies.alwaysLong("Rec BTC", "1m"), "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z")
        val auto = Strategies.eligible(h, Strategies.alwaysLong("Auto ETH", "1m", symbol = "ETH-USD", maxHoldingBars = 500), "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z")
        val version = h.get("/v1/autonomy/disclosure").json["version"].asText()
        assertThat(Strategies.activate(h, rec, p, mapOf("allocationPercent" to "40")).status).isEqualTo(201)
        assertThat(Strategies.activate(h, auto, p, mapOf("allocationPercent" to "40", "mode" to "AUTONOMOUS", "disclosureAccepted" to true, "disclosureVersion" to version)).status)
            .isEqualTo(201)
        assertThat(Replay.order(h, p, "SOL-USD", "BUY", "2").json["order"]["status"].asText()).isEqualTo("PENDING")
        Replay.advance(h, 2)
        assertThat(h.get("/v1/emergency").json["pauseAll"].asBoolean()).isFalse()

        // Prevent New Positions: risk-increasing orders are blocked, reducing orders are allowed.
        assertThat(h.post("/v1/emergency/prevent-new-positions", mapOf("enabled" to true)).status).isEqualTo(200)
        val buy = Replay.order(h, p, "SOL-USD", "BUY", "1")
        assertThat(buy.json["order"]["status"].asText()).isEqualTo("REJECTED")
        assertThat(buy.json["order"]["rejectionReason"].asText()).contains("EMERGENCY_CONTROLS")
        assertThat(Replay.order(h, p, "SOL-USD", "SELL", "1").json["order"]["status"].asText()).isEqualTo("PENDING")
        // Releasing a control loosens safety and needs recent authentication.
        val release = withoutRecentAuth { h.post("/v1/emergency/prevent-new-positions", mapOf("enabled" to false)) }
        assertThat(release.status).isEqualTo(403)
        assertThat(h.post("/v1/emergency/prevent-new-positions", mapOf("enabled" to false)).status).isEqualTo(200)

        // Disable Autonomous Mode pauses autonomous strategies only.
        val dis = h.post("/v1/emergency/disable-autonomous", mapOf("reason" to "drill"))
        assertThat(dis.status).`as`(dis.toString()).isEqualTo(200)
        assertThat(dis.json["affected"].map { it.asText() }).containsExactly(auto)
        assertThat(status(h, auto)).isEqualTo("PAUSED")
        assertThat(status(h, rec)).isEqualTo("ACTIVE_RECOMMENDATION")

        // Cancel Pending Orders.
        val last = BigDecimal(h.get("/v1/market-data/quotes/BTC-USD").json["last"].asText())
        val limit = last.multiply(BigDecimal("0.97")).setScale(2, java.math.RoundingMode.DOWN).toPlainString()
        val far = Replay.order(h, p, "BTC-USD", "BUY", "0.01", type = "LIMIT", limit = limit)
        assertThat(far.json["order"]["status"].asText()).`as`(far.toString()).isEqualTo("PENDING")
        val cancel = h.post("/v1/emergency/cancel-pending-orders", mapOf("reason" to "drill"))
        assertThat(cancel.status).`as`(cancel.toString()).isEqualTo(200)
        assertThat(cancel.json["affected"].map { it.asText() }).contains(far.json["order"]["id"].asText())
        assertThat(h.get("/v1/orders/${far.json["order"]["id"].asText()}").json["order"]["status"].asText()).isEqualTo("CANCELLED")

        // Pause All: strategies pause, pending recommendations close, no new orders or signals.
        Replay.advance(h, 1)
        assertThat(Strategies.recommendations(h, rec, "PENDING")).hasSize(1)
        val pause = h.post("/v1/emergency/pause-all", mapOf("enabled" to true, "reason" to "drill"))
        assertThat(pause.status).`as`(pause.toString()).isEqualTo(200)
        assertThat(pause.json["state"]["pauseAll"].asBoolean()).isTrue()
        assertThat(status(h, rec)).isEqualTo("PAUSED")
        assertThat(Strategies.recommendations(h, rec, "PENDING")).isEmpty()
        val signalsBefore = jdbc.sql("select count(*) from signals").query(Int::class.java).single()
        Replay.advance(h, 2)
        assertThat(jdbc.sql("select count(*) from signals").query(Int::class.java).single()).isEqualTo(signalsBefore)
        assertThat(Replay.order(h, p, "SOL-USD", "SELL", "0.5").json["order"]["rejectionReason"].asText()).contains("EMERGENCY_CONTROLS")

        // Close All Simulated Positions is separate: recent authentication and a typed confirmation.
        val positions = h.get("/v1/positions?portfolioId=$p").json.toList()
        assertThat(positions).isNotEmpty()
        val noConfirm = h.post("/v1/emergency/close-all-simulated-positions", mapOf("confirmation" to "yes"))
        assertThat(noConfirm.status).isEqualTo(422)
        val noAuth = withoutRecentAuth { h.post("/v1/emergency/close-all-simulated-positions", mapOf("confirmation" to EmergencyService.CONFIRMATION)) }
        assertThat(noAuth.status).isEqualTo(403)
        val close = h.post("/v1/emergency/close-all-simulated-positions", mapOf("confirmation" to EmergencyService.CONFIRMATION))
        assertThat(close.status).`as`(close.toString()).isEqualTo(200)
        assertThat(close.json["affected"]).hasSize(positions.size)
        Replay.advance(h, 2)
        val remaining =
            h
                .get("/v1/positions?portfolioId=$p")
                .json
                .toList()
                .filter { BigDecimal(it["quantity"].asText()).signum() != 0 }
        assertThat(remaining).isEmpty()
        val sources = jdbc.sql("select distinct source from paper_orders where source = 'EMERGENCY_CLOSE' and status = 'FILLED'").query(String::class.java).list()
        assertThat(sources).containsExactly("EMERGENCY_CLOSE")

        val actions = jdbc.sql("select action from audit_events where category = 'EMERGENCY' order by id").query(String::class.java).list()
        assertThat(actions).contains(
            "PREVENT_NEW_POSITIONS_ENGAGED",
            "PREVENT_NEW_POSITIONS_RELEASED",
            "DISABLE_AUTONOMOUS_MODE",
            "CANCEL_PENDING_ORDERS",
            "PAUSE_ALL_ENGAGED",
            "CLOSE_ALL_SIMULATED_POSITIONS",
        )
        assertThat(h.post("/v1/emergency/pause-all", mapOf("enabled" to false)).status).isEqualTo(200)
    }
}
