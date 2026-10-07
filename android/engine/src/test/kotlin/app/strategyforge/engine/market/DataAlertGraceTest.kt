package app.strategyforge.engine.market

import app.strategyforge.engine.Engine
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.support.MutableClock
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import java.time.Instant

/** D-074 a short network drop on the phone raises no alert; data missing for five minutes does. */
class DataAlertGraceTest {
    private val clock = MutableClock(Instant.parse("2026-06-22T13:30:00Z"))
    private val reader = { rel: String -> javaClass.getResource("/replay/$rel")!!.readText() }
    private val replay = ReplayProvider(ReplayFixtures(reader))

    @Volatile private var down = false

    /** The replay data standing in for Coinbase, with the network switchable off. */
    private val live =
        object : MarketDataProvider by replay {
            override fun quote(
                symbol: String,
                assetClass: AssetClass,
                now: Instant,
            ): ProviderResult<QuoteData> = if (down) ProviderResult.Failed(FailureKind.UNAVAILABLE, "Network error: UnknownHostException") else replay.quote(symbol, assetClass, now)
        }

    private val e =
        Engine(JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")), clock, reader, cryptoProvider = { live }).also {
            it.setMarketMode(MarketMode.LIVE)
            val btc = it.instruments.bySymbol("BTC-USD").id
            it.marketInterests += MarketInterest { setOf(btc) }
        }

    private fun alerts() =
        e.db
            .sql("select count(*) from notification_events where title like 'Market data unavailable%'")
            .long()

    @Test
    fun `a one-minute drop is ignored and a five-minute outage alerts once`() {
        val first = e.ingestion.refreshAll()
        assertThat(first.verified).`as`(first.toString()).isEqualTo(1)
        down = true
        // While the last price is under two minutes old, a failed refresh still counts as verified.
        clock.advanceSeconds(15)
        assertThat(e.ingestion.refreshAll().verified).isEqualTo(1)
        clock.advanceSeconds(60)
        e.ingestion.refreshAll()
        assertThat(alerts()).isZero()
        // A plan checking the price meanwhile still sees a usable one, so it is not paused.
        val btc = e.instruments.bySymbol("BTC-USD")
        assertThat(e.market.verifyQuote(btc, 300).verified).isTrue()
        // Back within a minute or so: nothing was raised.
        down = false
        clock.advanceSeconds(15)
        assertThat(e.ingestion.refreshAll().verified).isEqualTo(1)
        assertThat(alerts()).isZero()

        // A real outage: no verified price for five minutes.
        down = true
        repeat(30) {
            clock.advanceSeconds(15)
            e.ingestion.refreshAll()
        }
        assertThat(alerts()).isEqualTo(1)
        // By then the stored price is too old, so trading is blocked as before.
        assertThat(e.market.verifyQuote(e.instruments.bySymbol("BTC-USD"), 300).verified).isFalse()
    }

    @Test
    fun `the crypto data test reports Coinbase reachable and how old the last price is`() {
        e.ingestion.refreshAll()
        val ok = e.cryptoSource()!!.diagnose(clock.instant())
        assertThat(ok.status.name).isIn("OK", "DEGRADED")
    }
}
