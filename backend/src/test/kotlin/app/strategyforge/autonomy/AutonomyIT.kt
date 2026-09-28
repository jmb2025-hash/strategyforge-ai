package app.strategyforge.autonomy

import app.strategyforge.identity.RecentAuth
import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.Strategies
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration

/** MS-11: autonomous activation gates, execution after risk checks, re-authorization and auto-pause. */
class AutonomyIT : FreshDatabaseTest() {
    private fun status(
        h: app.strategyforge.support.TestHttp,
        sid: String,
    ) = h.get("/v1/strategies/$sid").json["strategy"]["status"].asText()

    @Test
    fun `MS-11 FR-071 FR-072 FR-073 FR-074 autonomous activation, reauthorization and automatic pause`() {
        val h = TestOwner.client(baseUrl)
        val p = Replay.portfolio(h)
        val sid = Strategies.eligible(h, Strategies.alwaysLong("Autonomous Long", "1m", maxHoldingBars = 50), "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z")
        val disclosure = h.get("/v1/autonomy/disclosure").json
        assertThat(disclosure["text"].asText()).contains("SIMULATED").contains("No real money")
        val version = disclosure["version"].asText()
        val auto = mapOf("mode" to "AUTONOMOUS", "disclosureAccepted" to true, "disclosureVersion" to version)

        // FR-071: the disclosure is mandatory.
        val noDisclosure = Strategies.activate(h, sid, p, mapOf("mode" to "AUTONOMOUS"))
        assertThat(noDisclosure.status).isEqualTo(422)
        assertThat(noDisclosure.json["gates"].toString()).contains("disclosure")
        val stale = Strategies.activate(h, sid, p, auto + ("disclosureVersion" to "old"))
        assertThat(stale.status).isEqualTo(422)

        // FR-071: recent authentication is mandatory.
        val window = RecentAuth.window
        try {
            RecentAuth.window = Duration.ofMillis(1)
            Thread.sleep(20)
            val r = Strategies.activate(h, sid, p, auto)
            assertThat(r.status).`as`(r.toString()).isEqualTo(403)
            assertThat(r.json["code"].asText()).isEqualTo("recent-authentication-required")
        } finally {
            RecentAuth.window = window
        }
        assertThat(status(h, sid)).isEqualTo("PAPER_ELIGIBLE")

        val ok = Strategies.activate(h, sid, p, auto)
        assertThat(ok.status).`as`(ok.toString()).isEqualTo(201)
        assertThat(ok.json["fingerprint"].asText()).hasSize(64)
        assertThat(ok.json["disclosureVersion"].asText()).isEqualTo(version)
        assertThat(status(h, sid)).isEqualTo("ACTIVE_AUTONOMOUS")

        // FR-073: the signal executes as an AUTONOMOUS order only after a passing risk evaluation; no recommendation.
        Replay.advance(h, 2)
        val signal =
            h
                .get("/v1/signals?strategyId=$sid")
                .json
                .toList()
                .last()
        assertThat(signal["disposition"].asText()).isEqualTo("AUTO_EXECUTED")
        assertThat(h.get("/v1/risk/evaluations/${signal["riskEvaluationId"].asText()}").json["decision"].asText()).isEqualTo("ALLOW")
        assertThat(Strategies.recommendations(h, sid)).isEmpty()
        val orders =
            jdbc
                .sql("select source || ':' || status from paper_orders where strategy_id = cast(:s as uuid)")
                .param("s", sid)
                .query(String::class.java)
                .list()
        assertThat(orders).containsExactly("AUTONOMOUS:FILLED")

        // FR-072: a material change (a new strategy-level risk profile) pauses autonomy until re-authorized.
        val tighten = h.put("/v1/risk/profiles/STRATEGY/$sid", mapOf("maxOpenPositions" to 2))
        assertThat(tighten.status).`as`(tighten.toString()).isIn(200, 201)
        assertThat(status(h, sid)).isEqualTo("PAUSED")
        val ended = h.get("/v1/strategies/$sid/activations").json.first()
        assertThat(ended["status"].asText()).isEqualTo("ENDED")
        assertThat(ended["endReason"].asText()).contains("re-authorized")
        Replay.advance(h, 1)
        assertThat(
            jdbc
                .sql("select count(*) from paper_orders where strategy_id = cast(:s as uuid)")
                .param("s", sid)
                .query(Int::class.java)
                .single(),
        ).isEqualTo(1)

        val again = Strategies.activate(h, sid, p, auto)
        assertThat(again.status).`as`(again.toString()).isEqualTo(201)
        assertThat(again.json["fingerprint"].asText()).isNotEqualTo(ok.json["fingerprint"].asText())
        assertThat(status(h, sid)).isEqualTo("ACTIVE_AUTONOMOUS")

        // FR-074: stale market data pauses autonomy (the feed for BTC-USD stops refreshing).
        jdbc.sql("update instruments set active = false where symbol = 'BTC-USD'").update()
        try {
            Replay.advance(h, 3)
        } finally {
            jdbc.sql("update instruments set active = true where symbol = 'BTC-USD'").update()
        }
        assertThat(status(h, sid)).isEqualTo("PAUSED")
        val actions =
            jdbc
                .sql("select action from audit_events where entity_id = :s order by id")
                .param("s", sid)
                .query(String::class.java)
                .list()
        assertThat(actions).contains("AUTONOMY_AUTHORIZED", "STRATEGY_REAUTHORIZATION_REQUIRED", "EVALUATION_BLOCKED", "STRATEGY_AUTONOMY_AUTO_PAUSE")
        val notes = h.get("/v1/notifications?limit=100").json
        assertThat(notes.toString()).contains("STRATEGY_SUSPENSION")
    }

    @Test
    fun `FR-101 a protective exit is announced as a stop or target event and closes the position`() {
        val h = TestOwner.client(baseUrl)
        val p = Replay.portfolio(h)
        // Stops so tight that the first move of the replay series triggers one of them.
        val content = Strategies.alwaysLong("Tight Stops", "1m", symbol = "ETH-USD", quantity = "0.1", maxHoldingBars = 500, stopLossPercent = java.math.BigDecimal("0.01"), takeProfitPercent = java.math.BigDecimal("0.01"))
        val sid = Strategies.eligible(h, content, "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z")
        val version = h.get("/v1/autonomy/disclosure").json["version"].asText()
        val ok = Strategies.activate(h, sid, p, mapOf("mode" to "AUTONOMOUS", "disclosureAccepted" to true, "disclosureVersion" to version))
        assertThat(ok.status).`as`(ok.toString()).isEqualTo(201)
        var exits = emptyList<String>()
        var steps = 0
        while (exits.isEmpty() && steps++ < 8) {
            Replay.advance(h, 1)
            exits =
                jdbc
                    .sql("select s.action from signals s where s.strategy_id = cast(:s as uuid) and s.action = 'EXIT_LONG'")
                    .param("s", sid)
                    .query(String::class.java)
                    .list()
        }
        assertThat(exits).`as`("an exit signal within 8 bars").isNotEmpty()
        val notes =
            jdbc
                .sql("select title from notification_events where category = 'STOP_TARGET'")
                .query(String::class.java)
                .list()
        assertThat(notes).anyMatch { it.startsWith("Exit triggered (stop loss)") || it.startsWith("Exit triggered (take profit)") }
        assertThat(notes.single { it.startsWith("Exit triggered") }).contains("ETH-USD")
    }
}
