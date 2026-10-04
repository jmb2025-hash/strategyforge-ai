package app.strategyforge.engine.tsx

import app.strategyforge.engine.Engine
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.support.MutableClock
import app.strategyforge.engine.support.TestEngine
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate

class TsxServiceTest {
    private val clock = MutableClock(Instant.parse("2026-08-03T21:00:00Z"))
    private val engine =
        Engine(
            JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
            clock,
            fixtureReader = { rel -> TestEngine::class.java.getResource("/replay/$rel")!!.readText() },
            tsxProvider = { SyntheticTsx.provider },
        ).also { it.tsx.pauseMs = 0 }
    private val tsx = engine.tsx

    private fun code(block: () -> Unit) = assertThrows<EngineException> { block() }.code

    private fun days(n: Long) = clock.advanceSeconds(n * 86_400)

    @Test
    fun `plans need data, and a refresh stores every listing`() {
        assertThat(tsx.status().latestDay).isNull()
        assertThat(code { tsx.create("tsx-momentum-rotation", null, 10_000.0, true, TsxMode.NOTIFY) }).isEqualTo("no-tsx-data")
        assertThat(tsx.refresh()).isTrue()
        val s = tsx.status()
        assertThat(s.latestDay).isEqualTo(LocalDate.parse("2026-08-03"))
        assertThat(s.cached).isGreaterThanOrEqualTo(s.listings)
        assertThat(s.refreshing).isFalse()
        assertThat(s.error).isNull()
        days(20)
        assertThat(code { tsx.create("tsx-momentum-rotation", null, 10_000.0, true, TsxMode.NOTIFY) }).isEqualTo("stale-tsx-data")
    }

    @Test
    fun `a notify run proposes its holdings and invests only when approved`() {
        tsx.refresh()
        val r = tsx.create("tsx-dividend-growth-momentum", null, 10_000.0, true, TsxMode.NOTIFY)
        assertThat(r.slot).isEqualTo(1)
        assertThat(r.holdings).isEmpty()
        assertThat(r.pending!!.size).isBetween(10, 25)
        assertThat(r.pending!!.values.sum()).isCloseTo(1.0, within(1e-6))
        assertThat(engine.notifications.list(20, false).map { it.title }).contains("TSX slot 1: rebalance ready")
        val a = tsx.approve(r.id)
        assertThat(a.pending).isNull()
        assertThat(a.holdings.map { it.symbol }).containsExactlyInAnyOrderElementsOf(r.pending!!.keys)
        assertThat(a.value).isCloseTo(9_990.0, within(1.0))
        assertThat(tsx.events(r.id).map { it.kind }).contains("BUY", "REBALANCE_APPROVED")
        assertThat(code { tsx.decline(r.id) }).isEqualTo("nothing-pending")
    }

    @Test
    fun `autonomous runs follow the days, reinvesting or paying out dividends`() {
        tsx.refresh()
        val drip = tsx.create("tsx-high-yield-trend", null, 10_000.0, true, TsxMode.AUTONOMOUS)
        val paid = tsx.create("tsx-high-yield-trend", null, 10_000.0, false, TsxMode.AUTONOMOUS)
        assertThat(drip.holdings).isNotEmpty()
        assertThat(paid.slot).isEqualTo(2)
        days(60)
        tsx.refresh()
        val d = tsx.get(drip.id)
        val p = tsx.get(paid.id)
        assertThat(d.lastDay).isEqualTo(LocalDate.parse("2026-10-02"))
        val dk = tsx.events(drip.id).map { it.kind }
        val pk = tsx.events(paid.id).map { it.kind }
        assertThat(dk).contains("DIVIDEND_REINVESTED", "SELL").doesNotContain("DIVIDEND_PAID")
        assertThat(pk).contains("DIVIDEND_PAID").doesNotContain("DIVIDEND_REINVESTED")
        assertThat(tsx.monthlyDividends(drip.id)).containsKey("2026-09")
        assertThat(d.dividendsReceived).isGreaterThan(0.0)
        // Paid-out dividends leave the portfolio, so the DRIP run is worth more.
        assertThat(d.value).isGreaterThan(p.value)
        assertThat(tsx.values(drip.id).size).isGreaterThan(40)
        assertThat(tsx.values(drip.id).last().value).isCloseTo(d.value, within(0.01))
    }

    @Test
    fun `slots are limited to ten and freed when a plan stops`() {
        tsx.refresh()
        val first = tsx.create("tsx-all-weather-core-satellite", 3, 5_000.0, true, TsxMode.NOTIFY)
        assertThat(code { tsx.create("tsx-all-weather-core-satellite", 3, 5_000.0, true, TsxMode.NOTIFY) }).isEqualTo("slot-occupied")
        repeat(9) { tsx.create("tsx-momentum-rotation", null, 5_000.0, true, TsxMode.NOTIFY) }
        assertThat(code { tsx.create("tsx-momentum-rotation", null, 5_000.0, true, TsxMode.NOTIFY) }).isEqualTo("slots-full")
        assertThat(tsx.stop(first.id).status).isEqualTo("STOPPED")
        assertThat(tsx.create("tsx-momentum-rotation", null, 5_000.0, true, TsxMode.NOTIFY).slot).isEqualTo(3)
        assertThat(code { tsx.create("tsx-momentum-rotation", null, 50.0, true, TsxMode.NOTIFY) }).isEqualTo("invalid-amount")
        assertThat(code { tsx.create("no-such-plan", null, 5_000.0, true, TsxMode.NOTIFY) }).isEqualTo("not-found")
    }

    @Test
    fun `a backtest reports both dividend views`() {
        tsx.refresh()
        val id = tsx.backtest("tsx-dividend-growth-momentum", LocalDate.parse("2024-01-01"), LocalDate.parse("2026-08-03"), 10_000.0)
        val v = tsx.backtestView(id)
        assertThat(v.path("status").asText()).isEqualTo("COMPLETED")
        val r = v.path("result")
        assertThat(r.path("months").size()).isEqualTo(32)
        assertThat(r.path("dividendsPaid").size()).isEqualTo(32)
        assertThat(r.path("dividendsTotal").asDouble()).isGreaterThan(0.0)
        assertThat(r.path("endDrip").asDouble()).isGreaterThan(r.path("endPaidOut").asDouble())
        assertThat(tsx.backtests("tsx-dividend-growth-momentum")).hasSize(1)
    }
}
