package app.strategyforge.backtest

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.TestHttp
import app.strategyforge.support.TestOwner
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant

class BacktestIT : FreshDatabaseTest() {
    private fun fixture(name: String): ObjectNode = TestHttp.mapper.readTree(javaClass.getResource("/strategies/$name")) as ObjectNode

    private fun create(
        h: TestHttp,
        content: JsonNode,
    ): String {
        val r = h.post("/v1/strategies", mapOf("content" to content))
        check(r.status == 201) { r.toString() }
        return r.json["strategy"]["id"].asText()
    }

    private fun await(
        h: TestHttp,
        id: String,
    ): JsonNode {
        val deadline = Instant.now().plus(Duration.ofSeconds(120))
        while (Instant.now().isBefore(deadline)) {
            val b = h.get("/v1/backtests/$id").json
            if (b["status"].asText() in setOf("COMPLETED", "FAILED")) return b
            Thread.sleep(200)
        }
        error("backtest $id did not finish")
    }

    private fun run(
        h: TestHttp,
        strategyId: String,
        from: String,
        to: String,
    ): JsonNode {
        val r = h.post("/v1/backtests", mapOf("strategyId" to strategyId, "from" to from, "to" to to, "startingCapital" to "100000"))
        check(r.status == 202) { r.toString() }
        return await(h, r.json["id"].asText())
    }

    @Test
    fun `FR-050 FR-051 FR-052 FR-053 FR-054 backtest reports metrics, series, benchmark and dataset provenance then promotes the strategy`() {
        val h = TestOwner.client(baseUrl)
        val id = create(h, fixture("valid_crypto_rsi.json"))
        val b = run(h, id, "2026-02-01T00:00:00Z", "2026-06-20T00:00:00Z")
        assertThat(b["status"].asText()).`as`(b.toString()).isEqualTo("COMPLETED")
        assertThat(b["resultStatus"].asText()).isIn("OK", "WARNINGS")
        val m = b["metrics"]
        listOf(
            "startingEquity",
            "endingEquity",
            "netReturnPercent",
            "volatilityAnnualizedPercent",
            "maxDrawdownPercent",
            "winRatePercent",
            "lossRatePercent",
            "profitFactor",
            "expectancy",
            "averageGain",
            "averageLoss",
            "exposurePercent",
            "turnover",
            "fees",
            "slippage",
            "trades",
            "benchmarkReturnPercent",
            "benchmarkDifferencePercent",
        ).forEach { assertThat(m.has(it)).`as`(it).isTrue() }
        assertThat(m["trades"].asInt()).isGreaterThan(0)
        assertThat(b["benchmark"]["symbol"].asText()).isEqualTo("BTC-USD")
        val ds = b["dataset"]
        assertThat(ds["provider"].asText()).isEqualTo("REPLAY")
        assertThat(ds["synthetic"].asBoolean()).isTrue()
        assertThat(ds["symbols"].map { it["symbol"].asText() }).containsExactly("BTC-USD", "ETH-USD", "SOL-USD")
        assertThat(ds["assumptions"].size()).isGreaterThan(3)
        val series = h.get("/v1/backtests/${b["id"].asText()}/series").json
        assertThat(series["equity"].size()).isGreaterThan(100)
        assertThat(series["drawdown"].size()).isEqualTo(series["equity"].size())
        val trades = h.get("/v1/backtests/${b["id"].asText()}/trades").json
        assertThat(trades.size()).isEqualTo(m["trades"].asInt())
        assertThat(trades[0]["exitReason"].asText()).isNotBlank()
        assertThat(h.get("/v1/strategies/$id").json["strategy"]["status"].asText()).isEqualTo("PAPER_ELIGIBLE")
    }

    @Test
    fun `FR-055 MS-08 missing data is a critical integrity error that blocks paper eligibility`() {
        val h = TestOwner.client(baseUrl)
        val id = create(h, fixture("valid_momentum.json"))
        // Hourly replay data starts in 2026; a 2025 window has no bars.
        val b = run(h, id, "2025-03-01T00:00:00Z", "2025-06-01T00:00:00Z")
        assertThat(b["resultStatus"].asText()).isEqualTo("CRITICAL")
        assertThat(b["integrity"]["issues"].map { it["code"].asText() }).contains("MISSING_DATA")
        assertThat(h.get("/v1/strategies/$id").json["strategy"]["status"].asText()).isEqualTo("VALIDATED")
        val denied = jdbc.sql("select count(*) from audit_events where action = 'PAPER_ELIGIBILITY_DENIED'").query(Int::class.java).single()
        assertThat(denied).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `FR-050 backtests cannot read data after the current market time`() {
        val h = TestOwner.client(baseUrl)
        val id = create(h, fixture("valid_crypto_rsi.json"))
        val r = h.post("/v1/backtests", mapOf("strategyId" to id, "from" to "2026-06-01T00:00:00Z", "to" to "2026-12-01T00:00:00Z"))
        assertThat(r.status).isEqualTo(400)
        assertThat(r.json["code"].asText()).isEqualTo("future-range")
    }

    @Test
    fun `FR-025 FR-054 equities without verified corporate-action data are Manual Review Required`() {
        val h = TestOwner.client(baseUrl)
        val id = create(h, fixture("valid_momentum.json"))
        val msft = jdbc.sql("select id from instruments where symbol = 'MSFT'").query(java.util.UUID::class.java).single()
        jdbc
            .sql(
                """
                insert into corporate_action_coverage(instrument_id, status, provider, detail, updated_at) values (:i, 'UNAVAILABLE', 'REPLAY', 'simulated outage', now())
                on conflict (instrument_id) do update set status = 'UNAVAILABLE', covered_from = null, covered_to = null
                """.trimIndent(),
            ).param("i", msft)
            .update()
        try {
            val b = run(h, id, "2026-02-01T00:00:00Z", "2026-06-19T00:00:00Z")
            assertThat(b["resultStatus"].asText()).isEqualTo("MANUAL_REVIEW_REQUIRED")
            assertThat(b["integrity"]["issues"].map { it["code"].asText() }).contains("CORPORATE_ACTIONS_UNAVAILABLE")
            assertThat(h.get("/v1/strategies/$id").json["strategy"]["status"].asText()).isEqualTo("VALIDATED")
        } finally {
            jdbc.sql("delete from corporate_action_coverage where instrument_id = :i").param("i", msft).update()
        }
        val ok = run(h, id, "2026-02-01T00:00:00Z", "2026-06-19T00:00:00Z")
        assertThat(ok["resultStatus"].asText()).`as`(ok["integrity"].toString()).isIn("OK", "WARNINGS")
        assertThat(ok["dataset"]["symbols"].map { it["corporateActions"].asInt() }.sum()).isGreaterThanOrEqualTo(0)
        assertThat(h.get("/v1/strategies/$id").json["strategy"]["status"].asText()).isEqualTo("PAPER_ELIGIBLE")
    }
}
