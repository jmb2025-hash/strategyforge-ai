package app.strategyforge.engine

import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.execution.OrderStatus
import app.strategyforge.engine.market.MarketMode
import app.strategyforge.engine.market.ReplayFixtures
import app.strategyforge.engine.market.ReplayProvider
import app.strategyforge.engine.support.MutableClock
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.order
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant

/** The foreground-service tick (D-027): demo mode advances replay time; live mode runs each task on its cadence. */
class EngineSchedulerTest {
    @Test
    fun `demo ticks advance replay time and fill orders, and a zero speed pauses the demo`() {
        val e = TestEngine.create()
        val s = EngineScheduler(e)
        val start = e.marketClock.now()
        val pid = e.portfolio()
        val id = e.order(pid, "ETH-USD", "BUY", "1").order.id
        val r = s.tick()
        assertThat(r.mode).isEqualTo(MarketMode.DEMO)
        assertThat(r.marketTime).isEqualTo(start.plusSeconds(60))
        assertThat(e.orders.get(id).status).isEqualTo(OrderStatus.FILLED)
        s.demoStepMinutes = 0
        s.tick()
        assertThat(e.marketClock.now()).isEqualTo(start.plusSeconds(60))
        s.demoStepMinutes = 5
        assertThat(EngineScheduler(e).demoStepMinutes).`as`("persisted").isEqualTo(5)
        s.tick()
        assertThat(e.marketClock.now()).isEqualTo(start.plusSeconds(360))
    }

    @Test
    fun `live ticks run each task on its own cadence`() {
        val clock = MutableClock(Instant.parse("2026-06-22T13:30:00Z"))
        val reader = { rel: String -> javaClass.getResource("/replay/$rel")!!.readText() }
        // A replay-backed source stands in for the live exchange so the test needs no network.
        val live = ReplayProvider(ReplayFixtures(reader))
        val e = Engine(JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")), clock, reader, cryptoProvider = { live })
        e.setMarketMode(MarketMode.LIVE)
        val s = EngineScheduler(e)
        assertThat(s.tick().ran).containsExactly("tsx", "ingestion", "execution", "maintenance", "reconciliation", "reconciliation-all", "evaluation", "expiry", "equity")
        clock.advanceSeconds(5)
        assertThat(s.tick().ran).containsExactly("execution", "reconciliation")
        clock.advanceSeconds(10)
        assertThat(s.tick().ran).containsExactly("ingestion", "execution", "reconciliation", "expiry")
        clock.advanceSeconds(5)
        assertThat(s.tick().ran).containsExactly("execution", "reconciliation", "evaluation")

        // A market order placed in live mode fills on a later tick once its execution delay has passed.
        val pid = e.portfolio()
        val id = e.order(pid, "BTC-USD", "BUY", "0.01").order.id
        clock.advanceSeconds(60)
        s.tick()
        assertThat(e.orders.get(id).status).isEqualTo(OrderStatus.FILLED)
        assertThat(e.reconciliation.run(pid).status).isEqualTo("OK")
    }
}
