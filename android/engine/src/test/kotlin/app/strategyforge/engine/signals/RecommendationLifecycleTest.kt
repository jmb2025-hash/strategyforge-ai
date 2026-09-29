package app.strategyforge.engine.signals

import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.db.str
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.risk.OrderSource
import app.strategyforge.engine.strategy.StrategyStatus
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.Strategies.activate
import app.strategyforge.engine.support.Strategies.eligible
import app.strategyforge.engine.support.Strategies.recs
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.MathContext
import java.time.Instant

/** MS-10, ported from the Version 1 RecommendationLifecycleIT. */
class RecommendationLifecycleTest {
    private val e = TestEngine.create()

    private fun code(block: () -> Unit): String =
        try {
            block()
            "none"
        } catch (x: EngineException) {
            x.code
        }

    @Test
    fun `MS-10 FR-060 FR-061 FR-062 FR-063 FR-065 FR-066 FR-070 recommendation lifecycle end to end`() {
        val p = e.portfolio()
        val sid = e.eligible(Strategies.alwaysLong("Always Long 1m", "1m"))

        // FR-070: without a mode the activation is Recommendation Mode.
        val act = e.activate(sid, p)
        assertThat(act.mode).isEqualTo(ActivationMode.RECOMMENDATION)
        assertThat(e.strategies.get(sid).status).isEqualTo(StrategyStatus.ACTIVE_RECOMMENDATION)

        // FR-060/FR-061: the engine evaluates on the replay schedule and records a complete signal.
        e.advance(1)
        val first = e.recs(sid, "PENDING")
        assertThat(first).hasSize(1)
        val sig = e.signals.list(sid).single()
        assertThat(sig.action).isEqualTo("ENTER_LONG")
        assertThat(sig.triggeredRules).containsExactly("CLOSE GT 1")
        assertThat(sig.rationale).contains("CLOSE GT 1")
        assertThat(sig.riskEvaluationId).isNotNull()
        // FR-062: a recommendation exists only after a recorded deterministic risk evaluation.
        assertThat(e.riskEvaluations.evaluation(first.single().riskEvaluationId!!)["decision"]).isEqualTo("ALLOW")

        // Supersede: the next bar's signal replaces the pending recommendation.
        e.advance(1)
        assertThat(e.recommendations.get(first.single().id).status).isEqualTo(RecommendationStatus.SUPERSEDED)
        val second = e.recs(sid, "PENDING").single().id

        // Decline; a decided recommendation cannot be decided again.
        assertThat(e.recommendations.decline(second, "not now").status).isEqualTo(RecommendationStatus.DECLINED)
        assertThat(code { e.recommendations.decline(second, "again") }).isEqualTo("recommendation-not-pending")

        // Snooze, then accept with a permitted (smaller) modification.
        e.advance(1)
        val recId = e.recs(sid, "PENDING").single().id
        assertThat(e.recommendations.snooze(recId, 2).snoozedUntil).isNotNull()
        assertThat(code { e.recommendations.accept(recId, AcceptRequest(Modification(quantity = BigDecimal("1")))) }).isEqualTo("modification-not-permitted")
        val won = e.recommendations.accept(recId, AcceptRequest(Modification(quantity = BigDecimal("0.005"))))
        assertThat(won.recommendation.status).isEqualTo(RecommendationStatus.MODIFIED)
        // A second tap (or a second screen) cannot create another order.
        assertThat(code { e.recommendations.accept(recId, AcceptRequest()) }).isEqualTo("recommendation-not-pending")

        // FR-065: exactly one order for the recommendation, linked back to strategy, signal and recommendation.
        assertThat(
            e.db
                .sql("select count(*) from paper_orders where recommendation_id = :r")
                .param("r", recId)
                .long(),
        ).isEqualTo(1)
        val order = e.orders.get(won.orderId!!)
        assertThat(order.source).isEqualTo(OrderSource.RECOMMENDATION)
        assertThat(order.strategyId).isEqualTo(sid)
        assertThat(order.quantity).isEqualByComparingTo("0.005")

        // FR-066: every outcome is recorded as a decision.
        val decisions =
            e.db
                .sql("select decision from recommendation_decisions d join recommendations r on r.id = d.recommendation_id where r.strategy_id = :s")
                .param("s", sid)
                .list { it.str("decision") }
        assertThat(decisions).contains("CREATE", "SUPERSEDE", "DECLINE", "SNOOZE", "MODIFY")

        // After the fill the strategy holds the position; the holding-period exit becomes a recommendation.
        e.advance(5)
        val exits = e.recs(sid, "PENDING")
        assertThat(exits).hasSize(1)
        assertThat(exits.single().side).isEqualTo(OrderSide.SELL)
        assertThat(exits.single().quantity).isEqualByComparingTo("0.005")

        // Pause from a recommendation declines it and pauses the strategy.
        e.recommendations.pauseStrategy(exits.single().id) { s, r -> e.strategyControl.deactivate(s, r) }
        assertThat(e.strategies.get(sid).status).isEqualTo(StrategyStatus.PAUSED)
        e.advance(2)
        assertThat(e.recs(sid, "PENDING")).isEmpty()
    }

    @Test
    fun `FR-064 FR-066 MS-10 acceptance fails after price deviation and recommendations expire`() {
        val p = e.portfolio(costModel = CostModel(maxPriceDeviationPercent = BigDecimal("0.1")))
        val sid = e.eligible(Strategies.alwaysLong("Always Long 4h", "4h"), "2026-03-01T00:00:00Z", "2026-06-20T00:00:00Z")
        e.activate(sid, p)

        // 13:31: the 08:00 4h bar is the latest closed bucket; the recommendation expires after 30 minutes.
        e.advance(1)
        val rec = e.recs(sid, "PENDING").single()
        assertThat(rec.expiresAt).isEqualTo(Instant.parse("2026-06-22T14:01:00Z"))

        // The synthetic series is too smooth to move 0.1% within the window, so the market move is
        // simulated by shifting the recommendation's reference price by 1%.
        e.db
            .sql("update recommendations set reference_price = :r where id = :id")
            .param("r", rec.referencePrice.multiply(BigDecimal("0.99"), MathContext.DECIMAL64))
            .param("id", rec.id)
            .update()
        e.advance(1)
        assertThat(code { e.recommendations.accept(rec.id, AcceptRequest()) }).isEqualTo("price-deviation")
        val failed = e.recommendations.get(rec.id)
        assertThat(failed.status).isEqualTo(RecommendationStatus.FAILED)
        assertThat(failed.orderId).isNull()

        // 16:00: the next 4h bucket produces a new recommendation, which then expires unanswered.
        e.replay.setTime(Instant.parse("2026-06-22T15:59:00Z"))
        e.advance(1)
        val next = e.recs(sid, "PENDING").single()
        assertThat(next.expiresAt).isEqualTo(Instant.parse("2026-06-22T16:30:00Z"))
        e.advance(31, 31)
        val expired = e.recommendations.detail(next.id)
        assertThat(expired.recommendation.status).isEqualTo(RecommendationStatus.EXPIRED)
        assertThat(expired.decisions.map { it["decision"] }).contains("CREATE", "EXPIRE")
        assertThat(e.db.sql("select count(*) from paper_orders").long()).isZero()
    }
}
