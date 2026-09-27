package app.strategyforge.execution

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal

/**
 * MS-14 / NFR-008: a crash in the middle of an execution leaves no partial state; the pending
 * order survives and is executed exactly once afterwards. The fault is injected at the database
 * (a one-shot trigger driven by a non-transactional sequence) so production code has no test hooks.
 */
class ExecutionRecoveryIT : FreshDatabaseTest() {
    @Autowired lateinit var engine: ExecutionEngine

    @Test
    fun `MS-14 NFR-008 crash during execution rolls back atomically and the order fills exactly once after restart`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h)
        val id = Replay.order(h, pid, "ETH-USD", "BUY", "3").json["order"]["id"].asText()
        val cashBefore = h.get("/v1/ledger/balances?portfolioId=$pid").json
        jdbc.sql("create sequence test_fault_seq").update()
        jdbc
            .sql(
                """
                create function test_fault_once() returns trigger language plpgsql as $$
                begin
                  if nextval('test_fault_seq') = 1 then raise exception 'simulated crash during execution'; end if;
                  return new;
                end $$
                """.trimIndent(),
            ).update()
        jdbc.sql("create trigger test_fault before insert on paper_executions for each row execute function test_fault_once()").update()
        try {
            Replay.advance(h, 1)
            val after = h.get("/v1/orders/$id").json
            assertThat(after["order"]["status"].asText()).isEqualTo("PENDING")
            assertThat(after["executions"].size()).isZero()
            assertThat(h.get("/v1/ledger/balances?portfolioId=$pid").json).isEqualTo(cashBefore)
            assertThat(h.get("/v1/positions?portfolioId=$pid").json.size()).isZero()
            assertThat(
                jdbc
                    .sql("select count(*) from position_lots where portfolio_id = cast(:p as uuid)")
                    .param("p", pid)
                    .query(Int::class.java)
                    .single(),
            ).isZero()
            assertThat(jdbc.sql("select count(*) from audit_events where action = 'EXECUTION_ERROR'").query(Int::class.java).single()).isEqualTo(1)

            // "Restart": a fresh processing pass (the engine holds no in-memory state) completes the order once.
            Replay.advance(h, 1)
            engine.processAll()
            engine.processAll()
            val done = h.get("/v1/orders/$id").json
            assertThat(done["order"]["status"].asText()).isEqualTo("FILLED")
            assertThat(done["executions"].size()).isEqualTo(1)
            assertThat(
                BigDecimal(
                    h
                        .get("/v1/positions?portfolioId=$pid")
                        .json
                        .single()["quantity"]
                        .asText(),
                ),
            ).isEqualByComparingTo("3")
            assertThat(h.post("/v1/portfolios/$pid/reconciliation").json["status"].asText()).isEqualTo("OK")
        } finally {
            jdbc.sql("drop trigger if exists test_fault on paper_executions").update()
            jdbc.sql("drop function if exists test_fault_once()").update()
            jdbc.sql("drop sequence if exists test_fault_seq").update()
        }
    }
}
