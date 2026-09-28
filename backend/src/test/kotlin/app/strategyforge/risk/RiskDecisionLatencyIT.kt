package app.strategyforge.risk

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** NFR-006 decision latency and FR-093 recorded evaluations. */
class RiskDecisionLatencyIT : FreshDatabaseTest() {
    @Test
    fun `NFR-006 FR-093 p95 risk and paper-order decision is under 2 seconds and every evaluation is recorded`() {
        val h = TestOwner.client(baseUrl)
        val p = Replay.portfolio(h, balance = "1000000")
        Replay.advance(h, 1)
        val symbols = listOf("BTC-USD", "ETH-USD", "SOL-USD")
        // Warm-up so class loading and JIT do not dominate the measurement.
        repeat(5) { Replay.order(h, p, symbols[it % 3], "BUY", "0.001") }
        val latencies =
            (1..60)
                .map { n ->
                    val started = System.nanoTime()
                    val r = Replay.order(h, p, symbols[n % 3], "BUY", "0.001")
                    val ms = (System.nanoTime() - started) / 1_000_000
                    assertThat(r.status).`as`(r.toString()).isEqualTo(201)
                    ms
                }.sorted()
        val p95 = latencies[(latencies.size * 95 / 100) - 1]
        assertThat(p95).`as`("p95 order decision latency ms: $latencies").isLessThan(2000)

        val engineP95 =
            jdbc
                .sql("select percentile_cont(0.95) within group (order by duration_ms) from risk_evaluations where portfolio_id = cast(:p as uuid)")
                .param("p", p)
                .query(Double::class.java)
                .single()
        assertThat(engineP95).isLessThan(2000.0)

        // FR-093: each evaluation keeps its intent, inputs, every rule result and the decision.
        val list = h.get("/v1/risk/evaluations?portfolioId=$p").json
        assertThat(list.size()).isGreaterThanOrEqualTo(65)
        val one = h.get("/v1/risk/evaluations/${list[0]["id"].asText()}").json
        assertThat(one["intent"]["side"].asText()).isEqualTo("BUY")
        assertThat(one["inputs"].size()).isGreaterThan(0)
        val rules = one["ruleResults"].map { it["rule"].asText() }
        assertThat(rules).contains(
            "EMERGENCY_CONTROLS",
            "TRADE_VALUE",
            "ALLOCATION",
            "OPEN_POSITIONS",
            "TRADE_FREQUENCY",
            "LOSS_LIMITS",
            "SHORT_EXPOSURE",
            "MARKET_QUALITY",
            "SYMBOL_LISTS",
            "BUYING_POWER",
            "MARKET_DATA_VERIFIED",
        )
        assertThat(one["decision"].asText()).isIn("ALLOW", "BLOCK")
    }
}
