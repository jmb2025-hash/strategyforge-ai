package app.strategyforge.android.core

import app.strategyforge.android.core.cache.Resource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** FR-103 / FR-104: the app reads the backend report and outcome comparison, keeping the no-causation disclaimer. */
class ReportsTest {
    private val dispatcher = StandardTestDispatcher()
    private val h = Harness(dispatcher)

    @After
    fun close() = h.close()

    private val report =
        """{"summary":{"portfolio":{"id":"p1","name":"Main","status":"ACTIVE","startingBalance":"100000"},"cash":"95000.00","buyingPower":"95000.00",
        "equity":"100120.50","realizedPnl":"12.00","unrealizedPnl":"108.50","asOf":"2026-06-22T14:00:00Z","positions":[]},
        "from":null,"to":null,"equity":[],"drawdown":[],"maxDrawdownPercent":"0.42","periodReturnPercent":"0.12",
        "costs":{"commissions":"4.50","spread":"1.20","slippage":"0.80","borrow":"0","dividends":"0","total":"6.50"},
        "byAsset":[{"key":"BTC-USD","label":"BTC-USD","realizedPnl":"12.00","unrealizedPnl":"50.00","trades":2}],
        "byStrategy":[{"key":"MANUAL","label":"Manual and emergency orders","realizedPnl":"12.00","unrealizedPnl":null,"trades":3}],
        "benchmarks":[{"symbol":"SPY","startClose":"500","endClose":"505","returnPercent":"1.0","note":null}],
        "closedTrades":[],"disclaimer":"Simulated paper-trading results."}"""
    private val outcomes =
        """{"portfolioId":"p1","bySource":[{"group":"MANUAL","count":3,"realizedPnl":"12.00","averagePnl":"4.00","note":"n"}],
        "byRecommendationDecision":[],"declinedHypothetical":[],"disclaimer":"These comparisons are descriptive and do not show that any decision caused an outcome."}"""

    @Test
    fun `FR-103 FR-104 portfolio report and outcome comparison decode with decimals as text`() =
        runTest(dispatcher) {
            h.server.enqueue(json(report))
            h.server.enqueue(json(outcomes))
            val r =
                h.repo
                    .report("p1")
                    .toList()
                    .last() as Resource.Data
            assertEquals("6.50", r.value.report.costs.total)
            assertEquals(
                "Manual and emergency orders",
                r.value.report.byStrategy
                    .single()
                    .label,
            )
            assertEquals(
                "SPY",
                r.value.report.benchmarks
                    .single()
                    .symbol,
            )
            assertEquals(
                3,
                r.value.outcomes.bySource
                    .single()
                    .count,
            )
            assertTrue(
                r.value.outcomes.disclaimer
                    .contains("do not show"),
            )
            assertEquals("/v1/reports/portfolios/p1", h.server.takeRequest().path)
            assertEquals("/v1/reports/portfolios/p1/outcomes", h.server.takeRequest().path)
        }
}
