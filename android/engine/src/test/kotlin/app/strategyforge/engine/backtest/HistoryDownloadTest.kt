package app.strategyforge.engine.backtest

import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.market.DataStatus
import app.strategyforge.engine.strategy.StrategyStatus
import app.strategyforge.engine.support.TestEngine
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * D-081 a backtest of many stocks no longer fails for good on the free data plan's per-minute limit:
 * the refused symbols download in the background and the backtest runs again by itself.
 */
class HistoryDownloadTest {
    private val e = TestEngine.create()

    /** ETH-USD is refused [refusals] times, as the stock source refuses requests over its per-minute budget. */
    private var refusals = 3
    private var fetches = 0

    private val svc =
        BacktestService(
            e.db,
            e.strategies,
            e.instruments,
            e.market,
            e.corporateActions,
            e.sources,
            e.settings,
            e.marketClock,
            e.wall,
            e.audit,
            e.events,
            e.riskProfiles,
            null,
            e.notifications,
        ) { i, tf, from, to, at ->
            fetches++
            val cs = e.market.candles(i, tf, from, to, at)
            if (i.symbol == "ETH-USD" && refusals > 0) {
                refusals--
                cs.copy(bars = emptyList(), status = DataStatus.PROVIDER_ERROR, detail = "RATE_LIMITED: Per-minute request budget used; retrying shortly")
            } else {
                cs
            }
        }

    @Test
    fun `refused symbols download a few at a time, then the backtest runs again and the owner is told`() {
        val id =
            e.strategies
                .create(JacksonCanonical.mapper.readTree(javaClass.getResource("/strategies/valid_crypto_rsi.json")))
                .strategy.id
        val first = svc.submit(BacktestRequest(id, from = Instant.parse("2026-02-01T00:00:00Z"), to = Instant.parse("2026-06-20T00:00:00Z"), startingCapital = BigDecimal("100000")))
        assertThat(first.resultStatus).isEqualTo("CRITICAL")
        assertThat(first.integrity!!["issues"].map { it["code"].asText() }).contains("DATA_DOWNLOADING")
        assertThat(e.strategies.get(id).status).isEqualTo(StrategyStatus.VALIDATED)
        assertThat(svc.downloading(id)).isEqualTo(1 to 1)

        // Still over the limit: nothing runs yet and the symbol stays queued.
        assertThat(svc.downloadWaiting()).isEmpty()
        assertThat(svc.downloadWaiting()).isEmpty()
        assertThat(svc.downloading(id)).isEqualTo(1 to 1)

        // The limit refills: the history comes in and the backtest runs again by itself.
        val rerun = svc.downloadWaiting()
        assertThat(rerun).hasSize(1)
        assertThat(rerun.single().resultStatus).isIn("OK", "WARNINGS")
        assertThat(svc.downloading(id)).isNull()
        assertThat(e.strategies.get(id).status).isEqualTo(StrategyStatus.PAPER_ELIGIBLE)
        assertThat(e.db.sql("select count(*) from notification_events where title like 'Backtest finished:%'").long()).isEqualTo(1)
    }

    @Test
    fun `at most a few symbols are fetched per call, so the limit is respected`() {
        refusals = 0
        assertThat(svc.downloadWaiting(maxCalls = 3)).isEmpty()
        assertThat(fetches).isZero()
    }
}
