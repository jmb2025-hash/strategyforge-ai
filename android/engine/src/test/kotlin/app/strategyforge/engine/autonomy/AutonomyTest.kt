package app.strategyforge.engine.autonomy

import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.db.str
import app.strategyforge.engine.risk.RiskLimits
import app.strategyforge.engine.strategy.StrategyStatus
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.Strategies.activate
import app.strategyforge.engine.support.Strategies.eligible
import app.strategyforge.engine.support.Strategies.recs
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Duration

/** MS-11, ported from the Version 1 AutonomyIT. */
class AutonomyTest {
    private val e = TestEngine.create()

    private fun failure(block: () -> Unit): EngineException =
        try {
            block()
            error("expected a failure")
        } catch (x: EngineException) {
            x
        }

    @Test
    fun `MS-11 FR-071 FR-072 FR-073 FR-074 autonomous activation, reauthorization and automatic pause`() {
        val p = e.portfolio()
        val sid = e.eligible(Strategies.alwaysLong("Autonomous Long", "1m", maxHoldingBars = 50))
        assertThat(AutonomyDisclosure.TEXT).contains("SIMULATED").contains("No real money")

        // FR-071: the disclosure is mandatory.
        val noDisclosure = failure { e.activations.activate(sid, ActivationRequest(ActivationMode.AUTONOMOUS, p, BigDecimal("50"))) }
        assertThat(noDisclosure.code).isEqualTo("activation-gates-failed")
        assertThat(noDisclosure.properties["gates"].toString()).contains("disclosure")
        assertThat(failure { e.activations.activate(sid, ActivationRequest(ActivationMode.AUTONOMOUS, p, BigDecimal("50"), true, "old")) }.code).isEqualTo("activation-gates-failed")

        // FR-071: recent device-lock confirmation is mandatory.
        e.auth.forget()
        val noAuth = failure { e.activations.activate(sid, ActivationRequest(ActivationMode.AUTONOMOUS, p, BigDecimal("50"), true, AutonomyDisclosure.VERSION)) }
        assertThat(noAuth.code).isEqualTo("recent-authentication-required")
        e.auth.confirmed()
        e.auth.window = Duration.ZERO
        assertThat(failure { e.activations.activate(sid, ActivationRequest(ActivationMode.AUTONOMOUS, p, BigDecimal("50"), true, AutonomyDisclosure.VERSION)) }.code).isEqualTo("recent-authentication-required")
        e.auth.window = Duration.ofMinutes(5)
        assertThat(e.strategies.get(sid).status).isEqualTo(StrategyStatus.PAPER_ELIGIBLE)

        val ok = e.activate(sid, p, ActivationMode.AUTONOMOUS)
        assertThat(ok.fingerprint).hasSize(64)
        assertThat(ok.disclosureVersion).isEqualTo(AutonomyDisclosure.VERSION)
        assertThat(e.strategies.get(sid).status).isEqualTo(StrategyStatus.ACTIVE_AUTONOMOUS)

        // FR-073: the signal executes as an AUTONOMOUS order only after a passing risk evaluation; no recommendation.
        e.advance(2)
        val signal = e.signals.list(sid).first()
        assertThat(signal.disposition).isEqualTo("AUTO_EXECUTED")
        assertThat(e.riskEvaluations.evaluation(signal.riskEvaluationId!!)["decision"]).isEqualTo("ALLOW")
        assertThat(e.recs(sid)).isEmpty()
        val orders =
            e.db
                .sql("select source || ':' || status s from paper_orders where strategy_id = :s")
                .param("s", sid)
                .list { it.str("s") }
        assertThat(orders).containsExactly("AUTONOMOUS:FILLED")

        // FR-072: a material change (a new strategy-level risk profile) pauses autonomy until re-authorized.
        e.riskProfiles.upsert("STRATEGY", sid, RiskLimits(maxOpenPositions = 2), null)
        assertThat(e.strategies.get(sid).status).isEqualTo(StrategyStatus.PAUSED)
        val ended = e.activations.history(sid).first()
        assertThat(ended.status).isEqualTo("ENDED")
        assertThat(ended.endReason).contains("re-authorized")
        e.advance(1)
        assertThat(
            e.db
                .sql("select count(*) from paper_orders where strategy_id = :s")
                .param("s", sid)
                .long(),
        ).isEqualTo(1)

        val again = e.activate(sid, p, ActivationMode.AUTONOMOUS)
        assertThat(again.fingerprint).isNotEqualTo(ok.fingerprint)
        assertThat(e.strategies.get(sid).status).isEqualTo(StrategyStatus.ACTIVE_AUTONOMOUS)

        // FR-074: stale market data pauses autonomy (the feed for BTC-USD stops refreshing).
        e.db.sql("update instruments set active = 0 where symbol = 'BTC-USD'").update()
        try {
            e.advance(3)
        } finally {
            e.db.sql("update instruments set active = 1 where symbol = 'BTC-USD'").update()
        }
        assertThat(e.strategies.get(sid).status).isEqualTo(StrategyStatus.PAUSED)
        val actions =
            e.db
                .sql("select action from audit_events where entity_id = :s order by id")
                .param("s", sid.toString())
                .list { it.str("action") }
        assertThat(actions).contains("AUTONOMY_AUTHORIZED", "STRATEGY_REAUTHORIZATION_REQUIRED", "EVALUATION_BLOCKED", "STRATEGY_AUTONOMY_AUTO_PAUSE")
        assertThat(e.notifications.list(100, false).map { it.category }).contains("STRATEGY_SUSPENSION")
    }

    @Test
    fun `FR-101 a protective exit is announced as a stop or target event and closes the position`() {
        val p = e.portfolio()
        // Stops so tight that the first move of the replay series triggers one of them.
        val content = Strategies.alwaysLong("Tight Stops", "1m", symbol = "ETH-USD", quantity = "0.1", maxHoldingBars = 500, stopLossPercent = BigDecimal("0.01"), takeProfitPercent = BigDecimal("0.01"))
        val sid = e.eligible(content)
        e.activate(sid, p, ActivationMode.AUTONOMOUS)
        var exits = emptyList<String>()
        var steps = 0
        while (exits.isEmpty() && steps++ < 8) {
            e.advance(1)
            exits =
                e.signals
                    .list(sid)
                    .map { it.action }
                    .filter { it == "EXIT_LONG" }
        }
        assertThat(exits).`as`("an exit signal within 8 bars").isNotEmpty()
        val notes = e.db.sql("select title from notification_events where category = 'STOP_TARGET'").list { it.str("title") }
        assertThat(notes).anyMatch { it.startsWith("Exit triggered (stop loss)") || it.startsWith("Exit triggered (take profit)") }
        assertThat(notes.single { it.startsWith("Exit triggered") }).contains("ETH-USD")
    }
}
