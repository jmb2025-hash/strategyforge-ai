package app.strategyforge.engine.signals

import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderStatus
import app.strategyforge.engine.strategy.StrategyStatus
import app.strategyforge.engine.support.Strategies
import app.strategyforge.engine.support.Strategies.activate
import app.strategyforge.engine.support.Strategies.eligible
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** D-045 trading plans in live paper trading: setups own their positions under the plan's policies. */
class TradingPlanLiveTest {
    private val e = TestEngine.create()
    private val p = e.portfolio(balance = "100000")

    /** Two setups that both want to be long every bar; B exits after two bars, A holds. */
    private fun plan(conflict: String): Map<String, Any?> {
        val json =
            """
            {"schemaVersion":"2.0","metadata":{"name":"Two setups $conflict","assetClass":"CRYPTO","timeframe":"1m","createdBy":"OWNER"},
             "universe":{"symbols":["BTC-USD"]},
             "dataRequirements":{"minimumHistoryBars":30,"maximumQuoteAgeSeconds":120,"indicators":[{"id":"EMA_5","type":"EMA","period":5}]},
             "planRules":{"conflictPolicy":"$conflict"},
             "setups":[
               {"id":"HOLDER","name":"Holder","priority":1,
                "entryRules":{"operator":"ALL","conditions":[{"left":"CLOSE","comparison":"GT","right":1}]},
                "exitRules":{"stopLossPercent":20,"takeProfitPercent":50,"maximumHoldingBars":500},"positionSizing":{"method":"FIXED_QUANTITY","value":0.1}},
               {"id":"SCALPER","name":"Scalper","priority":2,
                "entryRules":{"operator":"ALL","conditions":[{"left":"CLOSE","comparison":"GT","right":1}]},
                "exitRules":{"stopLossPercent":20,"takeProfitPercent":50,"maximumHoldingBars":2},"positionSizing":{"method":"FIXED_QUANTITY","value":0.05}}],
             "orderInstructions":{"orderType":"MARKET","timeInForce":"GTC"},
             "riskLimits":{"maximumOpenPositions":3,"maximumDailyTrades":200,"maximumDailyLossPercent":5,"maximumDrawdownPercent":20,"allowShort":false},
             "inactivityConditions":["STALE_MARKET_DATA"]}
            """.trimIndent()
        @Suppress("UNCHECKED_CAST")
        return JacksonCanonical.mapper.readValue(json, Map::class.java) as Map<String, Any?>
    }

    private fun signals(id: java.util.UUID) =
        e.db
            .sql("select action, setup_id from signals where strategy_id = :s order by created_at, id")
            .param("s", id)
            .list { (it.string("action") ?: "") + ":" + (it.string("setup_id") ?: "") }

    private fun openLotSetups() =
        e.db
            .sql(
                """
                select sg.setup_id, l.quantity_remaining from position_lots l join paper_executions x on x.id = l.open_execution_id
                join paper_orders o on o.id = x.order_id join signals sg on sg.id = o.signal_id
                where l.portfolio_id = :p and l.closed_at is null
                """.trimIndent(),
            ).param("p", p)
            .list { it.string("setup_id") + "=" + it.string("quantity_remaining") }

    @Test
    fun `with one position per symbol only the highest-priority setup trades`() {
        val id = e.eligible(plan("ONE_PER_SYMBOL"))
        e.activate(id, p, ActivationMode.AUTONOMOUS)
        e.advance(10)
        assertThat(signals(id)).isNotEmpty().allMatch { it.endsWith(":HOLDER") }
        val buys = e.orders.list(p, null, 50).filter { it.side == OrderSide.BUY && it.status == OrderStatus.FILLED }
        assertThat(buys).hasSize(1)
    }

    @Test
    fun `stacked setups each hold a position, and a setup's exit closes its own lots`() {
        val id = e.eligible(plan("STACK"))
        e.activate(id, p, ActivationMode.AUTONOMOUS)
        e.advance(12)
        val s = signals(id)
        assertThat(s).contains("ENTER_LONG:HOLDER", "ENTER_LONG:SCALPER", "EXIT_LONG:SCALPER")
        assertThat(s.filter { it.endsWith(":HOLDER") }).`as`("the holder never exits").containsOnly("ENTER_LONG:HOLDER")
        // The scalper's exit sold the scalper's lots, not the holder's older ones.
        assertThat(openLotSetups()).anyMatch { it.startsWith("HOLDER=0.1") }
        assertThat(openLotSetups().filter { it.startsWith("HOLDER") }).allMatch { it.startsWith("HOLDER=0.1") }
    }

    @Test
    fun `upgrading retires the single strategies of earlier versions and keeps their history`() {
        val single = e.eligible(Strategies.alwaysLong("Old single", "1m", quantity = "0.01", maxHoldingBars = 500))
        e.activate(single, p, ActivationMode.AUTONOMOUS)
        e.advance(3)
        val ordersBefore = e.orders.list(p, null, 50).size
        assertThat(ordersBefore).isGreaterThan(0)
        // Pretend the phone is still on schema 6, then upgrade.
        e.db.setSchemaVersion(6)
        e.db.migrate()
        assertThat(e.db.schemaVersion()).isEqualTo(Db.SCHEMA_VERSION)
        val s = e.strategies.get(single)
        assertThat(s.status).isEqualTo(StrategyStatus.ARCHIVED)
        assertThat(s.statusReason).contains("trading plans")
        assertThat(e.activations.active(single)).isNull()
        assertThat(e.strategies.list(false).map { it.id }).doesNotContain(single)
        assertThat(e.orders.list(p, null, 50)).hasSize(ordersBefore)
    }
}
