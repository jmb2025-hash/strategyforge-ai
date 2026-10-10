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
    fun `D-078 a notify run switched to autonomous applies its waiting rebalance, and can switch back`() {
        tsx.refresh()
        val r = tsx.create("tsx-dividend-growth-momentum", null, 10_000.0, true, TsxMode.NOTIFY)
        assertThat(r.pending).isNotNull()
        val auto = tsx.setMode(r.id, TsxMode.AUTONOMOUS)
        assertThat(auto.mode).isEqualTo(TsxMode.AUTONOMOUS)
        assertThat(auto.pending).isNull()
        assertThat(auto.holdings.map { it.symbol }).containsExactlyInAnyOrderElementsOf(r.pending!!.keys)
        // Same mode again changes nothing.
        assertThat(tsx.setMode(r.id, TsxMode.AUTONOMOUS).holdings).hasSameSizeAs(auto.holdings)
        val back = tsx.setMode(r.id, TsxMode.NOTIFY)
        assertThat(back.mode).isEqualTo(TsxMode.NOTIFY)
        assertThat(back.holdings).hasSameSizeAs(auto.holdings)
        tsx.stop(r.id)
        assertThat(code { tsx.setMode(r.id, TsxMode.AUTONOMOUS) }).isEqualTo("run-stopped")
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

    @Test
    fun `intraday prices show a live value during the session without touching the closes`() {
        val live =
            object : TsxHistoryProvider {
                override fun history(
                    symbol: String,
                    from: LocalDate,
                    to: LocalDate,
                ) = SyntheticTsx.provider.history(symbol, from, to)

                override fun latest(symbol: String) = TsxQuote(SyntheticTsx.close(symbol, LocalDate.parse("2026-08-03")) * 1.02, clock.instant(), null)
            }
        val e =
            Engine(
                JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
                clock,
                fixtureReader = { rel -> TestEngine::class.java.getResource("/replay/$rel")!!.readText() },
                tsxProvider = { live },
            ).also { it.tsx.pauseMs = 0 }
        e.tsx.refresh()
        val run = e.tsx.create("tsx-high-yield-trend", null, 10_000.0, true, TsxMode.AUTONOMOUS)
        assertThat(run.liveValue).isNull()
        // Tuesday 11:00 in Toronto: the session is open.
        clock.advanceSeconds(18 * 3_600)
        assertThat(e.tsx.sessionOpen()).isTrue()
        e.tsx.tick()
        val r = e.tsx.get(run.id)
        val invested = r.holdings.sumOf { it.shares * it.price }
        assertThat(r.liveValue!!).isCloseTo(r.cash + invested * 1.02, within(0.01))
        assertThat(r.holdings).allSatisfy { assertThat(it.livePrice).isCloseTo(it.price * 1.02, within(1e-6)) }
        assertThat(r.lastDay).isEqualTo(LocalDate.parse("2026-08-03"))
        // A data refresh during the session stops at the last completed session.
        e.tsx.refresh()
        assertThat(e.tsx.status().latestDay).isEqualTo(LocalDate.parse("2026-08-03"))
        // Outside the session nothing is fetched.
        clock.advanceSeconds(12 * 3_600)
        assertThat(e.tsx.sessionOpen()).isFalse()
        assertThat(e.tsx.refreshIntraday()).isFalse()
    }

    @Test
    fun `during the session the held listings stream, in demo mode too, and price the live value`() {
        val synced = mutableListOf<Set<String>>()
        val stream =
            object : app.strategyforge.engine.market.QuoteStream {
                override val name = "Fake"
                override val assetClass = app.strategyforge.engine.market.AssetClass.US_EQUITY

                override fun sync(symbols: Set<String>) {
                    synced += symbols
                }

                override fun latest(
                    symbol: String,
                    maxAge: java.time.Duration,
                ) = symbol.removeSuffix(".TO").takeIf { symbol.endsWith(".TO") }?.let { s ->
                    app.strategyforge.engine.market
                        .StreamTick(s, null, null, java.math.BigDecimal(SyntheticTsx.close(s, LocalDate.parse("2026-08-03")) * 1.05), clock.instant(), clock.instant())
                }

                override fun status() =
                    app.strategyforge.engine.market
                        .StreamStatus(name, assetClass, app.strategyforge.engine.market.StreamState.LIVE, 0, 0, null, null, null, 0)

                override fun reset() {}
            }
        val e =
            Engine(
                JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
                clock,
                fixtureReader = { rel -> TestEngine::class.java.getResource("/replay/$rel")!!.readText() },
                tsxProvider = { SyntheticTsx.provider },
                stockStream = { stream },
            ).also { it.tsx.pauseMs = 0 }
        e.tsx.refresh()
        val run = e.tsx.create("tsx-high-yield-trend", null, 10_000.0, true, TsxMode.AUTONOMOUS)
        val s = app.strategyforge.engine.EngineScheduler(e)
        s.tick()
        assertThat(synced.last()).`as`("market closed: nothing to stream").isEmpty()
        clock.advanceSeconds(18 * 3_600)
        s.tick()
        assertThat(synced.last()).containsExactlyInAnyOrderElementsOf(run.holdings.map { "${it.symbol}.TO" })
        val r = e.tsx.get(run.id)
        assertThat(r.liveValue!!).isCloseTo(r.cash + r.holdings.sumOf { it.shares * it.price } * 1.05, within(0.01))
        // Streamed listings need no polling.
        assertThat(e.tsx.refreshIntraday()).isFalse()
    }
}
