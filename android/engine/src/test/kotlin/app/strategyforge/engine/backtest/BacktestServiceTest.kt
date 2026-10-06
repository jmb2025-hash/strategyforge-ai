package app.strategyforge.engine.backtest

import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.risk.RiskLimits
import app.strategyforge.engine.strategy.StrategyStatus
import app.strategyforge.engine.support.TestEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Ported from the Version 1 BacktestIT (FR-050..FR-055, MS-08). */
class BacktestServiceTest {
    private val e = TestEngine.create()

    private fun create(name: String): UUID =
        e.strategies
            .create(JacksonCanonical.mapper.readTree(javaClass.getResource("/strategies/$name")))
            .strategy.id

    private fun run(
        id: UUID,
        from: String,
        to: String,
        risk: RiskLimits? = null,
    ) = e.backtests.submit(BacktestRequest(id, from = Instant.parse(from), to = Instant.parse(to), startingCapital = BigDecimal("100000"), riskProfile = risk))

    @Test
    fun `FR-050 FR-051 FR-052 FR-053 FR-054 backtest reports metrics, series, benchmark and dataset provenance then promotes the strategy`() {
        val id = create("valid_crypto_rsi.json")
        val b = run(id, "2026-02-01T00:00:00Z", "2026-06-20T00:00:00Z")
        assertThat(b.status).`as`(b.error).isEqualTo("COMPLETED")
        assertThat(b.resultStatus).isIn("OK", "WARNINGS")
        val m = b.metrics!!
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
        assertThat(b.benchmark!!["symbol"].asText()).isEqualTo("BTC-USD")
        val ds = b.dataset!!
        assertThat(ds["provider"].asText()).isEqualTo("REPLAY")
        assertThat(ds["synthetic"].asBoolean()).isTrue()
        assertThat(ds["symbols"].map { it["symbol"].asText() }).containsExactly("BTC-USD", "ETH-USD", "SOL-USD")
        assertThat(ds["assumptions"].size()).isGreaterThan(3)
        val series = e.backtests.series(b.id)
        val equity = series["equity"] as com.fasterxml.jackson.databind.JsonNode
        assertThat(equity.size()).isGreaterThan(100)
        assertThat((series["drawdown"] as com.fasterxml.jackson.databind.JsonNode).size()).isEqualTo(equity.size())
        val trades = e.backtests.trades(b.id)
        assertThat(trades.size).isEqualTo(m["trades"].asInt())
        assertThat(trades[0]["exitReason"] as String).isNotBlank()
        assertThat(e.strategies.get(id).status).isEqualTo(StrategyStatus.PAPER_ELIGIBLE)
    }

    @Test
    fun `FR-055 MS-08 missing data is a critical integrity error that blocks paper eligibility`() {
        val id = create("valid_momentum.json")
        // Hourly replay data starts in 2026; a 2025 window has no bars.
        val b = run(id, "2025-03-01T00:00:00Z", "2025-06-01T00:00:00Z")
        assertThat(b.resultStatus).isEqualTo("CRITICAL")
        assertThat(b.integrity!!["issues"].map { it["code"].asText() }).contains("MISSING_DATA")
        assertThat(e.strategies.get(id).status).isEqualTo(StrategyStatus.VALIDATED)
        assertThat(e.db.sql("select count(*) from audit_events where action = 'PAPER_ELIGIBILITY_DENIED'").long()).isGreaterThanOrEqualTo(1)
    }

    @Test
    fun `FR-051 FR-091 a backtest risk profile is merged strictest-wins and recorded with the run`() {
        val id = create("valid_crypto_rsi.json")
        // The strategy buys 5,000 per entry; a 1,000 trade-value cap blocks every entry.
        val b = run(id, "2026-02-01T00:00:00Z", "2026-06-20T00:00:00Z", RiskLimits(maxTradeValue = BigDecimal("1000"), maxOpenPositions = 50))
        assertThat(b.status).isEqualTo("COMPLETED")
        assertThat(b.metrics!!["trades"].asInt()).isZero()
        assertThat(b.metrics!!["riskBlockedEntries"].asInt()).isGreaterThan(0)
        val applied = b.params["riskProfile"]
        assertThat(BigDecimal(applied["maxTradeValue"].asText())).isEqualByComparingTo("1000")
        // The plan's own open-position cap (3) replaces the global 25 (D-063) and is stricter than the requested 50.
        assertThat(applied["maxOpenPositions"].asInt()).isEqualTo(3)
        val invalid = runCatching { run(id, "2026-02-01T00:00:00Z", "2026-06-20T00:00:00Z", RiskLimits(maxTradeValue = BigDecimal("-1"))) }.exceptionOrNull() as EngineException
        assertThat(invalid.status).isEqualTo(400)
    }

    @Test
    fun `FR-050 backtests cannot read data after the current market time`() {
        val id = create("valid_crypto_rsi.json")
        val x = runCatching { run(id, "2026-06-01T00:00:00Z", "2026-12-01T00:00:00Z") }.exceptionOrNull() as EngineException
        assertThat(x.code).isEqualTo("future-range")
    }

    @Test
    fun `FR-025 FR-054 equities without verified corporate-action data are Manual Review Required`() {
        val id = create("valid_momentum.json")
        val msft = e.instruments.bySymbol("MSFT").id
        e.db
            .sql("insert or replace into corporate_action_coverage(instrument_id, status, provider, detail, updated_at) values (:i, 'UNAVAILABLE', 'REPLAY', 'simulated outage', 0)")
            .param("i", msft)
            .update()
        try {
            val b = run(id, "2026-02-01T00:00:00Z", "2026-06-19T00:00:00Z")
            assertThat(b.resultStatus).isEqualTo("MANUAL_REVIEW_REQUIRED")
            assertThat(b.integrity!!["issues"].map { it["code"].asText() }).contains("CORPORATE_ACTIONS_UNAVAILABLE")
            assertThat(e.strategies.get(id).status).isEqualTo(StrategyStatus.VALIDATED)
        } finally {
            e.db
                .sql("delete from corporate_action_coverage where instrument_id = :i")
                .param("i", msft)
                .update()
        }
        val ok = run(id, "2026-02-01T00:00:00Z", "2026-06-19T00:00:00Z")
        assertThat(ok.resultStatus).`as`(ok.integrity.toString()).isIn("OK", "WARNINGS")
        assertThat(e.strategies.get(id).status).isEqualTo(StrategyStatus.PAPER_ELIGIBLE)
    }
}
