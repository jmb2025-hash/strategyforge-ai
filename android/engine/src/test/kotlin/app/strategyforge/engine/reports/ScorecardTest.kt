package app.strategyforge.engine.reports

import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.autonomy.ActivationRequest
import app.strategyforge.engine.autonomy.AutonomyDisclosure
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.Strategies.eligible
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/** D-037 scorecards: each strategy's paper results and latest backtest, and the brief for a combined strategy. */
class ScorecardTest {
    private val e = TestEngine.create()
    private val p = e.portfolio(balance = "1000000")

    private fun strategy(
        name: String,
        holdBars: Int = 2,
    ) = e.eligible(Strategies.alwaysLong(name, "1m", quantity = "0.1", maxHoldingBars = holdBars, stopLossPercent = 50, takeProfitPercent = 50))

    @Test
    fun `paper trades closed by a strategy's orders are counted, summed exactly and flagged when too few`() {
        val id = strategy("Quick flips")
        e.auth.confirmed()
        e.slots.activate(id, ActivationRequest(ActivationMode.AUTONOMOUS, p, BigDecimal("40"), true, AutonomyDisclosure.VERSION), null)
        e.advance(30)

        val pnls =
            e.db
                .sql(
                    "select e.realized_pnl from paper_executions e join paper_orders o on o.id = e.order_id where o.strategy_id = :s and e.side in ('SELL', 'BUY_TO_COVER')",
                ).param("s", id)
                .list { it.dec("realized_pnl") }
        assertThat(pnls).`as`("the strategy closed trades").isNotEmpty()

        val c = e.scorecards.scorecard(id)
        assertThat(c.live.closedTrades).isEqualTo(pnls.size)
        assertThat(c.live.realizedPnl).isEqualByComparingTo(pnls.fold(BigDecimal.ZERO, BigDecimal::add))
        assertThat(c.live.wins + c.live.losses).isLessThanOrEqualTo(c.live.closedTrades)
        assertThat(c.live.maxDrawdown.signum()).isGreaterThanOrEqualTo(0)
        assertThat(c.live.firstTradeAt).isNotNull()
        assertThat(c.backtest).`as`("the eligibility backtest is shown").isNotNull()
        assertThat(c.backtest!!.trades).isNotNull()
        assertThat(c.sampleWarning).contains("not yet meaningful").contains("${pnls.size} paper trade")
    }

    @Test
    fun `untested strategies have no numbers and drafts are left out`() {
        val a = strategy("Tested")
        val draft = e.strategies.create(JacksonCanonical.mapper.valueToTree(Strategies.alwaysLong("Draft", "1m") + mapOf("positionSizing" to mapOf("method" to "NOPE", "value" to 1))))
        val cards = e.scorecards.all("CRYPTO")
        assertThat(cards.map { it.strategy.id }).contains(a).doesNotContain(draft.strategy.id)
        assertThat(e.scorecards.all("US_EQUITY")).isEmpty()
        val c = cards.single { it.strategy.id == a }
        assertThat(c.live.closedTrades).isZero()
        assertThat(c.live.winRatePercent).isNull()
        assertThat(c.live.realizedPnl).isEqualByComparingTo("0")
    }

    @Test
    fun `the combined-strategy brief needs two strategies and carries each one's rules and results`() {
        strategy("Only one")
        assertThat(assertThrows<EngineException> { e.scorecards.combineBrief("CRYPTO") }.code).isEqualTo("not-enough-strategies")
        assertThat(assertThrows<EngineException> { e.scorecards.combineBrief("FOREX") }.code).isEqualTo("invalid-asset-class")

        strategy("Second idea", holdBars = 5)
        val brief = e.scorecards.combineBrief("CRYPTO")
        assertThat(brief)
            .contains("Build me a better crypto strategy")
            .contains("1. ")
            .contains("2. ")
            .contains("Only one")
            .contains("Second idea")
            .contains("no closed paper trades")
            .contains("backtest:")
            .contains("Rules: Buy BTC-USD")
        assertThat(brief.length).isLessThanOrEqualTo(7_800)
    }
}
