package app.strategyforge.engine.market

import app.strategyforge.engine.Engine
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.execution.OrderStatus
import app.strategyforge.engine.support.MutableClock
import app.strategyforge.engine.support.order
import app.strategyforge.engine.support.portfolio
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Coinbase public market data (D-027, FR-022, FR-023, FR-024) against recorded responses; no network. */
class CoinbaseProviderTest {
    private val server = MockWebServer()
    private val clock = MutableClock(Instant.parse("2026-09-29T14:03:00Z"))

    private fun resource(name: String) = javaClass.getResource("/coinbase/$name")!!.readText()

    private fun httpDate(t: Instant) = DateTimeFormatter.RFC_1123_DATE_TIME.format(t.atZone(ZoneOffset.UTC))

    private fun json(
        body: String,
        at: Instant = clock.instant(),
    ) = MockResponse().setBody(body).setHeader("Content-Type", "application/json").setHeader("Date", httpDate(at))

    private fun provider() = CoinbaseProvider(clock, exchangeUrl = server.url("/"), ratesUrl = server.url("/"), requestsPerSecond = 100)

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `ticker gives exact decimals, bid and ask, a real-time label and the book snapshot time`() {
        server.enqueue(json(resource("ticker-btc.json")))
        val q = (provider().quote("BTC-USD", AssetClass.CRYPTO, clock.instant()) as ProviderResult.Ok).value
        assertThat(q.last.toPlainString()).isEqualTo("64123.45")
        assertThat(q.bid!!.toPlainString()).isEqualTo("64123.45")
        assertThat(q.ask!!.toPlainString()).isEqualTo("64123.46")
        assertThat(q.feedType).isEqualTo(FeedType.REALTIME)
        assertThat(q.exchangeTs).isEqualTo(Instant.parse("2026-09-29T14:03:00Z"))
        val req = server.takeRequest()
        assertThat(req.path).isEqualTo("/products/BTC-USD/ticker")
        assertThat(req.getHeader("User-Agent")).startsWith("StrategyForge/")
        assertThat(req.getHeader("Authorization")).isNull()
    }

    @Test
    fun `candles are ascending, exact, closed only and requested in 300-bar pages`() {
        server.enqueue(json(resource("candles-btc-1m.json")))
        val now = Instant.parse("2026-09-29T14:02:30Z")
        val bars = (provider().candles("BTC-USD", AssetClass.CRYPTO, Timeframe.M1, Instant.parse("2026-09-29T14:00:00Z"), now, now) as ProviderResult.Ok).value
        // The 14:02 bar is still open at 14:02:30 and is excluded.
        assertThat(bars.map { it.openTime }).containsExactly(Instant.parse("2026-09-29T14:00:00Z"), Instant.parse("2026-09-29T14:01:00Z"))
        assertThat(bars[1].open.toPlainString()).isEqualTo("64095.5")
        assertThat(bars[1].close.toPlainString()).isEqualTo("64110.25")
        assertThat(bars[1].volume.toPlainString()).isEqualTo("9.87654321")
        val req = server.takeRequest()
        assertThat(req.requestUrl!!.queryParameter("granularity")).isEqualTo("60")

        // Ten hours of 1m bars need two requests of at most 300 bars.
        server.enqueue(json("[]"))
        server.enqueue(json("[]"))
        val end = Instant.parse("2026-09-29T14:00:00Z")
        provider().candles("BTC-USD", AssetClass.CRYPTO, Timeframe.M1, end.minusSeconds(36_000), end, end)
        assertThat(server.requestCount).isEqualTo(3)
        assertThat(provider().candles("BTC-USD", AssetClass.CRYPTO, Timeframe.H4, end.minusSeconds(36_000), end, end)).isInstanceOf(ProviderResult.Unsupported::class.java)
    }

    @Test
    fun `errors are classified and never guessed`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"NotFound"}"""))
        server.enqueue(json("not json"))
        server.enqueue(json("""{"price":"abc","time":"2026-09-29T14:00:00Z"}"""))
        server.enqueue(json("""[[1790690400,"x",1,1,1,1]]"""))
        val p = provider()
        val now = clock.instant()
        assertThat((p.quote("BTC-USD", AssetClass.CRYPTO, now) as ProviderResult.Failed).kind).isEqualTo(FailureKind.RATE_LIMITED)
        assertThat((p.quote("NOPE-USD", AssetClass.CRYPTO, now) as ProviderResult.Failed).kind).isEqualTo(FailureKind.NO_DATA)
        assertThat((p.quote("BTC-USD", AssetClass.CRYPTO, now) as ProviderResult.Failed).kind).isEqualTo(FailureKind.MALFORMED)
        assertThat((p.quote("BTC-USD", AssetClass.CRYPTO, now) as ProviderResult.Failed).kind).isEqualTo(FailureKind.MALFORMED)
        assertThat((p.candles("BTC-USD", AssetClass.CRYPTO, Timeframe.M1, now.minusSeconds(180), now, now) as ProviderResult.Failed).kind).isEqualTo(FailureKind.MALFORMED)
        assertThat(p.quote("AAPL", AssetClass.US_EQUITY, now)).isInstanceOf(ProviderResult.Unsupported::class.java)
        server.shutdown()
        assertThat((p.quote("BTC-USD", AssetClass.CRYPTO, now) as ProviderResult.Failed).kind).isEqualTo(FailureKind.UNAVAILABLE)
    }

    @Test
    fun `USD-CAD reference rate and product lookup`() {
        server.enqueue(json(resource("rates-usd.json")))
        server.enqueue(json(resource("product-btc.json")))
        val p = provider()
        val fx = (p.fxRate("USD", "CAD", clock.instant()) as ProviderResult.Ok).value
        assertThat(fx.rate.toPlainString()).isEqualTo("1.3712")
        assertThat(fx.asOf).isEqualTo(clock.instant())
        assertThat(server.takeRequest().path).isEqualTo("/v2/exchange-rates?currency=USD")
        val info = (p.lookup("BTC-USD") as ProviderResult.Ok).value
        assertThat(info.assetClass).isEqualTo(AssetClass.CRYPTO)
    }

    /** A tiny live exchange: ticker at the device clock, flat 1m/1d candles with steady volume. */
    private fun liveExchange() =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = request.requestUrl!!
                return when {
                    url.encodedPath.endsWith("/ticker") -> json("""{"ask":"2000.10","bid":"2000.00","price":"2000.05","time":"${clock.instant().minusSeconds(2)}"}""")
                    url.encodedPath.endsWith("/candles") -> {
                        val g = url.queryParameter("granularity")!!.toLong()
                        val from = Instant.parse(url.queryParameter("start")!!).epochSecond
                        val to = Instant.parse(url.queryParameter("end")!!).epochSecond
                        val rows = (from until to step g).toList().reversed().joinToString(",") { t -> "[$t,1999,2001,2000,2000.05,500]" }
                        json("[$rows]")
                    }
                    url.encodedPath.endsWith("/exchange-rates") -> json(resource("rates-usd.json"))
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

    @Test
    fun `D-027 live mode trades crypto on Coinbase data end to end in the engine`() {
        server.dispatcher = liveExchange()
        val cb = provider()
        val e =
            Engine(
                JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
                clock,
                fixtureReader = { rel -> javaClass.getResource("/replay/$rel")!!.readText() },
                cryptoProvider = { cb },
            )
        e.setMarketMode(MarketMode.LIVE)
        val eth = e.instruments.bySymbol("ETH-USD")
        val v = e.market.refreshQuote(eth)
        assertThat(v.status).`as`(v.detail).isEqualTo(DataStatus.VERIFIED)
        assertThat(v.quote!!.provider).isEqualTo("COINBASE")
        assertThat(v.quote!!.feedType).isEqualTo(FeedType.REALTIME)

        val pid = e.portfolio()
        val r = e.order(pid, "ETH-USD", "BUY", "1.5")
        assertThat(r.order.status).`as`(r.risk.reasons.toString()).isEqualTo(OrderStatus.PENDING)
        clock.advanceSeconds(61)
        e.step(clock.instant().minusSeconds(61), clock.instant())
        val filled = e.orders.get(r.order.id)
        assertThat(filled.status).isEqualTo(OrderStatus.FILLED)
        assertThat(filled.averageFillPrice).isGreaterThanOrEqualTo(BigDecimal("2000.10"))
        assertThat(e.reconciliation.run(pid).status).isEqualTo("OK")
        // Equities have no live source until a key is configured: explicit, never silently replayed.
        assertThat(e.market.refreshQuote(e.instruments.bySymbol("AAPL")).status).isEqualTo(DataStatus.UNSUPPORTED)
    }
}
