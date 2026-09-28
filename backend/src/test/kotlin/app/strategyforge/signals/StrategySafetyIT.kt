package app.strategyforge.signals

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.Strategies
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationEventPublisher
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** MS-13 scheduler retries, FR-094 suspension rules and the FR-047 active-strategy cap. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class StrategySafetyIT : FreshDatabaseTest() {
    @Autowired
    lateinit var evaluation: EvaluationService

    @Autowired
    lateinit var events: ApplicationEventPublisher

    @Test
    @Order(1)
    fun `MS-13 NFR-007 concurrent and repeated scheduler runs never duplicate evaluations or signals`() {
        val h = TestOwner.client(baseUrl)
        val p = Replay.portfolio(h)
        val sid = Strategies.eligible(h, Strategies.alwaysLong("Scheduler Retry", "1m", symbol = "SOL-USD"), "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z")
        assertThat(Strategies.activate(h, sid, p).status).isEqualTo(201)
        Replay.advance(h, 1)
        // Move the clock one bar without running the pipeline, then race four scheduler workers on the new bucket.
        val now = java.time.Instant.parse(h.get("/v1/market-data/clock").json["now"].asText())
        assertThat(h.post("/v1/market-data/replay/set", mapOf("to" to now.plusSeconds(60).toString())).status).isEqualTo(200)
        val pool = Executors.newFixedThreadPool(4)
        val gate = CountDownLatch(1)
        val runs =
            (1..4).map {
                pool.submit(
                    Callable {
                        gate.await()
                        evaluation.evaluateAll()
                    },
                )
            }
        gate.countDown()
        val summaries = runs.flatMap { it.get() }.filter { it.strategyId.toString() == sid }
        pool.shutdown()
        assertThat(summaries.map { it.status }.sorted()).`as`(summaries.toString()).containsExactly("COMPLETED", "DUPLICATE", "DUPLICATE", "DUPLICATE")
        // A retry after a restart finds the persisted claim.
        assertThat(evaluation.evaluateAll().single { it.strategyId.toString() == sid }.status).isEqualTo("DUPLICATE")
        val s = UUID.fromString(sid)
        assertThat(
            jdbc
                .sql("select count(*) from strategy_evaluations where strategy_id = :s")
                .param("s", s)
                .query(Int::class.java)
                .single(),
        ).isEqualTo(2)
        assertThat(
            jdbc
                .sql("select count(*) from signals where strategy_id = :s")
                .param("s", s)
                .query(Int::class.java)
                .single(),
        ).isEqualTo(2)
        assertThat(
            jdbc
                .sql("select count(*) from recommendations where strategy_id = :s")
                .param("s", s)
                .query(Int::class.java)
                .single(),
        ).isEqualTo(2)
        assertThat(
            jdbc
                .sql("select count(*) from recommendations where strategy_id = :s and status = 'PENDING'")
                .param("s", s)
                .query(Int::class.java)
                .single(),
        ).isEqualTo(1)
        h.post("/v1/strategies/$sid/deactivate", mapOf("reason" to "done"))
    }

    @Test
    @Order(2)
    fun `FR-094 consecutive losing trades suspend an autonomous strategy`() {
        val h = TestOwner.client(baseUrl)
        // Heavy slippage guarantees each round trip loses money.
        val p = Replay.portfolio(h, costModel = mapOf("slippageBps" to "300"))
        val content = Strategies.alwaysLong("Loss Streak", "1m", symbol = "ETH-USD", maxHoldingBars = 1, maxConsecutiveLosses = 2)
        val sid = Strategies.eligible(h, content, "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z")
        val version = h.get("/v1/autonomy/disclosure").json["version"].asText()
        val act = Strategies.activate(h, sid, p, mapOf("mode" to "AUTONOMOUS", "disclosureAccepted" to true, "disclosureVersion" to version))
        assertThat(act.status).`as`(act.toString()).isEqualTo(201)
        var status = "ACTIVE_AUTONOMOUS"
        var minutes = 0
        while (status == "ACTIVE_AUTONOMOUS" && minutes++ < 20) {
            Replay.advance(h, 1)
            status = h.get("/v1/strategies/$sid").json["strategy"]["status"].asText()
        }
        assertThat(status).isEqualTo("SUSPENDED")
        val losses =
            jdbc
                .sql("select count(*) from paper_executions e join paper_orders o on o.id = e.order_id where o.strategy_id = cast(:s as uuid) and e.side = 'SELL' and e.realized_pnl < 0")
                .param("s", sid)
                .query(Int::class.java)
                .single()
        assertThat(losses).isEqualTo(2)
        val actions =
            jdbc
                .sql("select action from audit_events where entity_id = :s")
                .param("s", sid)
                .query(String::class.java)
                .list()
        assertThat(actions).contains("STRATEGY_CONSECUTIVE_LOSSES")
        // A suspended strategy cannot be re-activated directly.
        assertThat(Strategies.activate(h, sid, p).status).isEqualTo(422)
    }

    @Test
    @Order(3)
    fun `FR-094 repeated evaluation errors suspend a strategy`() {
        val h = TestOwner.client(baseUrl)
        val p = Replay.portfolio(h)
        val sid = Strategies.eligible(h, Strategies.alwaysLong("Error Streak", "1m", symbol = "BTC-USD"), "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z")
        assertThat(Strategies.activate(h, sid, p).status).isEqualTo(201)
        events.publishEvent(EvaluationFailed(UUID.fromString(sid), 2, "IllegalStateException"))
        assertThat(h.get("/v1/strategies/$sid").json["strategy"]["status"].asText()).isEqualTo("ACTIVE_RECOMMENDATION")
        events.publishEvent(EvaluationFailed(UUID.fromString(sid), 3, "IllegalStateException"))
        assertThat(h.get("/v1/strategies/$sid").json["strategy"]["status"].asText()).isEqualTo("SUSPENDED")
        assertThat(h.get("/v1/notifications?limit=100").json.toString()).contains("consecutive evaluation errors")
    }

    @Test
    @Order(4)
    fun `FR-047 at most 25 strategies can be active`() {
        val h = TestOwner.client(baseUrl)
        val p = Replay.portfolio(h)
        val sid = Strategies.eligible(h, Strategies.alwaysLong("Twenty Sixth", "1m", symbol = "BTC-USD"), "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z")
        val active = jdbc.sql("select count(*) from strategies where status in ('ACTIVE_RECOMMENDATION', 'ACTIVE_AUTONOMOUS')").query(Int::class.java).single()
        repeat(25 - active) { n ->
            val r = h.post("/v1/strategies", mapOf("content" to Strategies.alwaysLong("Filler $n", "1h")))
            jdbc.sql("update strategies set status = 'ACTIVE_RECOMMENDATION' where id = cast(:id as uuid)").param("id", r.json["strategy"]["id"].asText()).update()
        }
        val r = Strategies.activate(h, sid, p, mapOf("allocationPercent" to "1"))
        assertThat(r.status).isEqualTo(422)
        assertThat(r.json["gates"].toString()).contains("At most 25 strategies")
    }
}
