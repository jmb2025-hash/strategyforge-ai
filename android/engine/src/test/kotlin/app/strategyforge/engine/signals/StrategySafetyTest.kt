package app.strategyforge.engine.signals

import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.str
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.strategy.StrategyStatus
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.Strategies.activate
import app.strategyforge.engine.support.Strategies.eligible
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** MS-13 repeated ticks, FR-094 suspension rules and the FR-047 active-strategy cap (from StrategySafetyIT). */
class StrategySafetyTest {
    private val e = TestEngine.create()

    private fun count(
        sql: String,
        s: Any,
    ) = e.db
        .sql(sql)
        .param("s", s)
        .long()

    @Test
    fun `MS-13 NFR-007 repeated evaluation runs never duplicate evaluations or signals`() {
        val p = e.portfolio()
        val sid = e.eligible(Strategies.alwaysLong("Scheduler Retry", "1m", symbol = "SOL-USD"))
        e.activate(sid, p)
        e.advance(1)
        // Move the clock one bar without running the pipeline, then evaluate the new bucket four times.
        e.replay.setTime(e.marketClock.now().plusSeconds(60))
        val summaries = (1..4).flatMap { e.evaluation.evaluateAll() }.filter { it.strategyId == sid }
        assertThat(summaries.map { it.status }.sorted()).`as`(summaries.toString()).containsExactly("COMPLETED", "DUPLICATE", "DUPLICATE", "DUPLICATE")
        assertThat(count("select count(*) from strategy_evaluations where strategy_id = :s", sid)).isEqualTo(2)
        assertThat(count("select count(*) from signals where strategy_id = :s", sid)).isEqualTo(2)
        assertThat(count("select count(*) from recommendations where strategy_id = :s", sid)).isEqualTo(2)
        assertThat(count("select count(*) from recommendations where strategy_id = :s and status = 'PENDING'", sid)).isEqualTo(1)
        e.strategyControl.deactivate(sid, "done")
    }

    @Test
    fun `FR-094 consecutive losing trades suspend an autonomous strategy`() {
        // Heavy slippage guarantees each round trip loses money.
        val p = e.portfolio(costModel = CostModel(slippageBps = BigDecimal("300")))
        val sid = e.eligible(Strategies.alwaysLong("Loss Streak", "1m", symbol = "ETH-USD", maxHoldingBars = 1, maxConsecutiveLosses = 2))
        e.activate(sid, p, ActivationMode.AUTONOMOUS)
        var status = StrategyStatus.ACTIVE_AUTONOMOUS
        var minutes = 0
        while (status == StrategyStatus.ACTIVE_AUTONOMOUS && minutes++ < 20) {
            e.advance(1)
            status = e.strategies.get(sid).status
        }
        assertThat(status).isEqualTo(StrategyStatus.SUSPENDED)
        val losses =
            e.db
                .sql("select e.realized_pnl from paper_executions e join paper_orders o on o.id = e.order_id where o.strategy_id = :s and e.side = 'SELL'")
                .param("s", sid)
                .list { it.dec("realized_pnl") }
                .count { it.signum() < 0 }
        assertThat(losses).isEqualTo(2)
        assertThat(
            e.db
                .sql("select action from audit_events where entity_id = :s")
                .param("s", sid.toString())
                .list { it.str("action") },
        ).contains("STRATEGY_CONSECUTIVE_LOSSES")
        // A suspended strategy cannot be re-activated directly.
        assertThat((runCatching { e.activate(sid, p) }.exceptionOrNull() as EngineException).status).isEqualTo(422)
    }

    @Test
    fun `FR-094 repeated evaluation errors suspend a strategy`() {
        val p = e.portfolio()
        val sid = e.eligible(Strategies.alwaysLong("Error Streak", "1m"))
        e.activate(sid, p)
        e.events.publish(EvaluationFailed(sid, 2, "IllegalStateException"))
        assertThat(e.strategies.get(sid).status).isEqualTo(StrategyStatus.ACTIVE_RECOMMENDATION)
        e.events.publish(EvaluationFailed(sid, 3, "IllegalStateException"))
        assertThat(e.strategies.get(sid).status).isEqualTo(StrategyStatus.SUSPENDED)
        assertThat(e.notifications.list(100, false).map { it.body }).anyMatch { it.contains("consecutive evaluation errors") }
    }

    @Test
    fun `FR-047 at most 25 strategies can be active`() {
        val p = e.portfolio()
        val sid = e.eligible(Strategies.alwaysLong("Twenty Sixth", "1m"))
        repeat(25) { n ->
            val r =
                e.strategies.create(
                    app.strategyforge.engine.common.JacksonCanonical.mapper
                        .valueToTree(Strategies.alwaysLong("Filler $n", "1h")),
                )
            e.db
                .sql("update strategies set status = 'ACTIVE_RECOMMENDATION' where id = :id")
                .param("id", r.strategy.id)
                .update()
        }
        val x = runCatching { e.activate(sid, p, allocation = "1") }.exceptionOrNull() as EngineException
        assertThat(x.status).isEqualTo(422)
        assertThat(x.properties["gates"].toString()).contains("At most 25 strategies")
    }
}
