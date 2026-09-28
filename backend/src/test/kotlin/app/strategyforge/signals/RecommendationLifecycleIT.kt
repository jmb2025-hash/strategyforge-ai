package app.strategyforge.signals

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.Strategies
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** MS-10: recommendation accept, modify, decline, supersede, duplicate tap and two-device race. */
class RecommendationLifecycleIT : FreshDatabaseTest() {
    @Test
    fun `MS-10 FR-060 FR-061 FR-062 FR-063 FR-065 FR-066 FR-070 recommendation lifecycle end to end`() {
        val h = TestOwner.client(baseUrl)
        val second = TestOwner.secondDevice(baseUrl)
        val p = Replay.portfolio(h)
        val sid = Strategies.eligible(h, Strategies.alwaysLong("Always Long 1m", "1m"), "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z")

        // FR-070: without a mode the activation is Recommendation Mode.
        val act = Strategies.activate(h, sid, p)
        assertThat(act.status).`as`(act.toString()).isEqualTo(201)
        assertThat(act.json["mode"].asText()).isEqualTo("RECOMMENDATION")
        assertThat(h.get("/v1/strategies/$sid").json["strategy"]["status"].asText()).isEqualTo("ACTIVE_RECOMMENDATION")

        // FR-060/FR-061: the backend evaluates on the replay schedule and records a complete signal.
        Replay.advance(h, 1)
        val first = Strategies.recommendations(h, sid, "PENDING")
        assertThat(first).hasSize(1)
        val signals = h.get("/v1/signals?strategyId=$sid").json.toList()
        assertThat(signals).hasSize(1)
        val sig = signals.single()
        listOf("versionId", "contentHash", "marketSnapshotId", "quantity", "orderType", "expiresAt", "rationale", "riskEvaluationId").forEach {
            assertThat(sig.hasNonNull(it)).`as`(it).isTrue()
        }
        assertThat(sig["action"].asText()).isEqualTo("ENTER_LONG")
        assertThat(sig["triggeredRules"].toList().map { it.asText() }).containsExactly("CLOSE GT 1")
        assertThat(sig["rationale"].asText()).contains("CLOSE GT 1")
        // FR-062: a recommendation exists only after a recorded deterministic risk evaluation.
        val risk = h.get("/v1/risk/evaluations/${first.single()["riskEvaluationId"].asText()}")
        assertThat(risk.status).isEqualTo(200)
        assertThat(risk.json["decision"].asText()).isEqualTo("ALLOW")

        // Supersede: the next bar's signal replaces the pending recommendation.
        Replay.advance(h, 1)
        val firstId = first.single()["id"].asText()
        assertThat(h.get("/v1/recommendations/$firstId").json["recommendation"]["status"].asText()).isEqualTo("SUPERSEDED")
        val second1 = Strategies.recommendations(h, sid, "PENDING").single()["id"].asText()

        // Decline.
        val dec = h.post("/v1/recommendations/$second1/decline", mapOf("reason" to "not now"))
        assertThat(dec.status).`as`(dec.toString()).isEqualTo(200)
        assertThat(dec.json["status"].asText()).isEqualTo("DECLINED")
        // A decided recommendation cannot be decided again.
        assertThat(h.post("/v1/recommendations/$second1/decline", mapOf("reason" to "again")).status).isEqualTo(409)

        // Snooze then accept with a permitted (smaller) modification; two devices race and a duplicate tap retries.
        Replay.advance(h, 1)
        val recId = Strategies.recommendations(h, sid, "PENDING").single()["id"].asText()
        val snooze = h.post("/v1/recommendations/$recId/snooze", mapOf("minutes" to 2))
        assertThat(snooze.status).`as`(snooze.toString()).isEqualTo(200)
        assertThat(snooze.json["snoozedUntil"].isNull).isFalse()

        // Accept requires a single-use action token.
        val noToken = h.post("/v1/recommendations/$recId/accept", mapOf("actionToken" to null))
        assertThat(noToken.status).isIn(400, 403)
        // Increasing the quantity is not a permitted modification.
        val t0 = h.get("/v1/recommendations/$recId").json["actionToken"]["token"].asText()
        val bigger = h.post("/v1/recommendations/$recId/accept", mapOf("actionToken" to t0, "modification" to mapOf("quantity" to "1")))
        assertThat(bigger.status).`as`(bigger.toString()).isEqualTo(422)

        val tokenA = h.get("/v1/recommendations/$recId").json["actionToken"]["token"].asText()
        val tokenB = second.get("/v1/recommendations/$recId").json["actionToken"]["token"].asText()
        val keyA = "acc-" + UUID.randomUUID()
        val body = { t: String -> mapOf("actionToken" to t, "modification" to mapOf("quantity" to "0.005")) }
        val pool = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        val results =
            listOf(
                Callable {
                    gate.await()
                    h.post("/v1/recommendations/$recId/accept", body(tokenA), idem = keyA)
                },
                Callable {
                    gate.await()
                    second.post("/v1/recommendations/$recId/accept", body(tokenB), idem = "acc-" + UUID.randomUUID())
                },
            ).map { pool.submit(it) }
        gate.countDown()
        val responses = results.map { it.get() }
        pool.shutdown()
        assertThat(responses.map { it.status }.sorted()).`as`(responses.toString()).containsExactly(200, 409)
        val winner = responses.single { it.status == 200 }
        val orderId = winner.json["orderId"].asText()
        assertThat(winner.json["recommendation"]["status"].asText()).isEqualTo("MODIFIED")

        // Duplicate tap with the winning key replays the stored response (only when device A won).
        if (responses[0].status == 200) {
            val dup = h.post("/v1/recommendations/$recId/accept", body(tokenA), idem = keyA)
            assertThat(dup.status).isEqualTo(200)
            assertThat(dup.json["orderId"].asText()).isEqualTo(orderId)
        }
        // FR-065: exactly one order for the recommendation, linked back to strategy, signal and recommendation.
        val count =
            jdbc
                .sql("select count(*) from paper_orders where recommendation_id = cast(:r as uuid)")
                .param("r", recId)
                .query(Int::class.java)
                .single()
        assertThat(count).isEqualTo(1)
        val order = h.get("/v1/orders/$orderId").json["order"]
        assertThat(order["source"].asText()).isEqualTo("RECOMMENDATION")
        assertThat(order["strategyId"].asText()).isEqualTo(sid)
        assertThat(BigDecimal(order["quantity"].asText())).isEqualByComparingTo("0.005")

        // FR-066: every outcome is recorded as a decision.
        val decisions =
            jdbc
                .sql("select decision from recommendation_decisions d join recommendations r on r.id = d.recommendation_id where r.strategy_id = cast(:s as uuid)")
                .param("s", sid)
                .query(String::class.java)
                .list()
        assertThat(decisions).contains("CREATE", "SUPERSEDE", "DECLINE", "SNOOZE", "MODIFY")

        // After the fill the strategy holds the position; the holding-period exit becomes a recommendation.
        Replay.advance(h, 5)
        val exits = Strategies.recommendations(h, sid, "PENDING")
        assertThat(exits).hasSize(1)
        assertThat(exits.single()["side"].asText()).isEqualTo("SELL")
        assertThat(BigDecimal(exits.single()["quantity"].asText())).isEqualByComparingTo("0.005")

        // Pause from a recommendation declines it and pauses the strategy.
        val pause = h.post("/v1/recommendations/${exits.single()["id"].asText()}/pause-strategy", null)
        assertThat(pause.status).`as`(pause.toString()).isEqualTo(200)
        assertThat(h.get("/v1/strategies/$sid").json["strategy"]["status"].asText()).isEqualTo("PAUSED")
        Replay.advance(h, 2)
        assertThat(Strategies.recommendations(h, sid, "PENDING")).isEmpty()
    }
}
