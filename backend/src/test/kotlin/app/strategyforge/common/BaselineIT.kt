package app.strategyforge.common

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.idempotency.IdempotencyService
import app.strategyforge.common.web.Problems
import app.strategyforge.support.IntegrationTest
import app.strategyforge.support.TestHttp
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class BaselineIT : IntegrationTest() {
    @Autowired lateinit var audit: AuditService

    @Autowired lateinit var idempotency: IdempotencyService

    @Test
    fun `WP0 health endpoint is up and correlation id is echoed`() {
        val r = TestHttp(baseUrl).get("/actuator/health", mapOf("X-Correlation-Id" to "test-correlation-0001"))
        assertThat(r.status).isEqualTo(200)
        assertThat(r.json["status"].asText()).isEqualTo("UP")
        assertThat(r.header("X-Correlation-Id")).isEqualTo("test-correlation-0001")
    }

    @Test
    fun `NFR-001 flyway migrations applied and timestamps are timestamptz`() {
        val applied = jdbc.sql("select count(*) from flyway_schema_history where success").query(Int::class.java).single()
        assertThat(applied).isGreaterThanOrEqualTo(1)
        val nonTz =
            jdbc
                .sql(
                    """select count(*) from information_schema.columns where table_schema='public'
                   and data_type = 'timestamp without time zone' and table_name <> 'flyway_schema_history'""",
                ).query(Int::class.java)
                .single()
        assertThat(nonTz).`as`("all timestamp columns must be timestamptz (UTC)").isZero()
    }

    @Test
    fun `NFR-002 no floating point columns exist in the schema`() {
        val floats =
            jdbc
                .sql(
                    """select table_name || '.' || column_name from information_schema.columns
                   where table_schema='public' and data_type in ('real','double precision')""",
                ).query(String::class.java)
                .list()
        assertThat(floats).isEmpty()
    }

    @Test
    fun `FR-110 audit events are append-only and hash chained`() {
        val id = audit.recordIndependently(AuditCategory.OPERATIONS, "TEST_EVENT", app.strategyforge.common.audit.AuditOutcome.SUCCESS, details = mapOf("apiKey" to "sk-ant-abcdefghijklmnop", "n" to 1))
        assertThatThrownBy { jdbc.sql("update audit_events set action='X' where event_id=:id").param("id", id).update() }
            .hasMessageContaining("append-only")
        assertThatThrownBy { jdbc.sql("delete from audit_events where event_id=:id").param("id", id).update() }
            .hasMessageContaining("append-only")
        val details =
            jdbc
                .sql("select details::text from audit_events where event_id=:id")
                .param("id", id)
                .query(String::class.java)
                .single()
        assertThat(details).doesNotContain("sk-ant-abcdefghijklmnop").contains("[REDACTED]")
        assertThat(audit.verifyChain()).isNull()
    }

    @Test
    fun `NFR-004 idempotent mutation executes once under concurrent retries`() {
        val key = "idem-" + UUID.randomUUID()
        val executions = AtomicInteger()
        val pool = Executors.newFixedThreadPool(8)
        val results =
            (1..8)
                .map {
                    pool.submit(
                        Callable {
                            idempotency.execute("test-scope", key, mapOf("a" to 1)) {
                                executions.incrementAndGet()
                                Thread.sleep(50)
                                mapOf("result" to "created")
                            }
                        },
                    )
                }.map { it.get() }
        pool.shutdown()
        assertThat(executions.get()).isEqualTo(1)
        assertThat(results.map { it.statusCode.value() }.toSet()).containsExactly(200)
        assertThat(results.count { it.headers.getFirst(IdempotencyService.REPLAY_HEADER) == "true" }).isEqualTo(7)
    }

    @Test
    fun `NFR-004 idempotency key reuse with different payload is rejected and failures replay`() {
        val key = "idem-" + UUID.randomUUID()
        idempotency.execute("test-scope", key, mapOf("a" to 1)) { mapOf("ok" to true) }
        assertThatThrownBy { idempotency.execute("test-scope", key, mapOf("a" to 2)) { mapOf("ok" to true) } }
            .hasMessageContaining("different request")
        val failKey = "idem-" + UUID.randomUUID()
        var calls = 0
        assertThatThrownBy {
            idempotency.execute("test-scope", failKey, null) {
                calls++
                throw Problems.conflict("x", "business rejection")
            }
        }
        val replay =
            idempotency.execute("test-scope", failKey, null) {
                calls++
                mapOf("never" to true)
            }
        assertThat(calls).isEqualTo(1)
        assertThat(replay.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `NFR-004 missing idempotency key is rejected`() {
        assertThatThrownBy { idempotency.execute("test-scope", null, null) { 1 } }.hasMessageContaining("Idempotency-Key")
    }

    @Test
    fun `FR-113 brokerage shaped routes are rejected and audited`() {
        val http = TestHttp(baseUrl)
        for (path in listOf("/v1/broker/orders", "/v1/live-trading/enable", "/v1/portfolios/x/withdraw", "/v1/real-money")) {
            val r = http.post(path, mapOf("enable" to true))
            assertThat(r.status).`as`(path).isEqualTo(403)
            assertThat(r.json["code"].asText()).isEqualTo("real-money-prohibited")
        }
        val n = jdbc.sql("select count(*) from audit_events where action='REAL_MONEY_ROUTE_REJECTED'").query(Int::class.java).single()
        assertThat(n).isGreaterThanOrEqualTo(4)
    }
}
