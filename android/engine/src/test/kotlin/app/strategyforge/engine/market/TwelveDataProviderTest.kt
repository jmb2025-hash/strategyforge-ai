package app.strategyforge.engine.market

import app.strategyforge.engine.Engine
import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.execution.OrderStatus
import app.strategyforge.engine.support.MutableClock
import app.strategyforge.engine.support.order
import app.strategyforge.engine.support.portfolio
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** US stock data from Twelve Data (D-032) against recorded responses served locally (MS-17, MS-09). */
class TwelveDataProviderTest {
    private lateinit var server: MockWebServer
    private val key = "td-secret-key-123456"

    // Tuesday 2026-06-23 11:00 New York: the US market is open.
    private val open = Instant.parse("2026-06-23T15:00:00Z")
    private var nowTs: Long = open.epochSecond
    private val routes = mutableMapOf<String, () -> MockResponse>()

    private fun fixture(name: String) = javaClass.getResource("/twelvedata/$name")!!.readText().replace("__TS__", nowTs.toString())

    private fun json(
        name: String,
        code: Int = 200,
    ) = { MockResponse().setResponseCode(code).setBody(fixture(name)).addHeader("Content-Type", "application/json") }

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return routes[path]?.invoke() ?: MockResponse().setResponseCode(404).setBody("""{"code":404,"message":"not found","status":"error"}""")
                }
            }
        server.start()
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun provider(
        clock: java.time.Clock = MutableClock(open),
        perMinute: Int = 1000,
        perDay: Int = 780,
        timeoutSeconds: Long = 2,
    ) = TwelveDataProvider(
        { key },
        clock,
        OkHttpClient.Builder().callTimeout(Duration.ofSeconds(timeoutSeconds)).build(),
        server.url("/"),
        requestsPerMinute = perMinute,
        requestsPerDay = perDay,
    )

    @Test
    fun `FR-022 quote parses decimals and the exchange timestamp without bid or ask`() {
        routes["/quote"] = json("quote_aapl.json")
        val now = open.plusSeconds(20)
        val q = (provider().quote("AAPL", AssetClass.US_EQUITY, now) as ProviderResult.Ok).value
        assertThat(q.last).isEqualByComparingTo("191.25")
        assertThat(q.bid).isNull()
        assertThat(q.exchangeTs).isEqualTo(open)
        assertThat(q.feedType).isEqualTo(FeedType.REALTIME)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(req.requestUrl!!.queryParameter("symbol")).isEqualTo("AAPL")
        assertThat(req.requestUrl!!.queryParameter("apikey")).isEqualTo(key)
    }

    @Test
    fun `MS-09 FR-023 feed type is measured from quote lag, never assumed`() {
        val p = provider()
        assertThat(p.classifyFeed(open.minusSeconds(20), open)).isEqualTo(FeedType.REALTIME)
        assertThat(p.classifyFeed(open.minusSeconds(900), open)).isEqualTo(FeedType.DELAYED)
        assertThat(p.classifyFeed(open.minusSeconds(300), open)).isEqualTo(FeedType.UNKNOWN)
        assertThat(provider().classifyFeed(Instant.parse("2026-06-20T15:00:00Z"), Instant.parse("2026-06-20T15:30:00Z"))).isEqualTo(FeedType.UNKNOWN)
    }

    @Test
    fun `free plan budgets quotes are cached, closed markets cost nothing and daily and minute limits hold`() {
        routes["/quote"] = json("quote_aapl.json")
        val p = provider()
        p.quote("AAPL", AssetClass.US_EQUITY, open)
        p.quote("AAPL", AssetClass.US_EQUITY, open.plusSeconds(60))
        assertThat(server.requestCount).`as`("second quote within five minutes is served from the cache").isEqualTo(1)
        p.quote("AAPL", AssetClass.US_EQUITY, open.plusSeconds(301))
        assertThat(server.requestCount).isEqualTo(2)
        // Saturday: the cached quote is served without a request.
        p.quote("AAPL", AssetClass.US_EQUITY, Instant.parse("2026-06-27T15:00:00Z"))
        assertThat(server.requestCount).isEqualTo(2)

        val daily = provider(perDay = 1)
        daily.quote("AAPL", AssetClass.US_EQUITY, open)
        val refused = daily.quote("MSFT", AssetClass.US_EQUITY, open) as ProviderResult.Failed
        assertThat(refused.kind).isEqualTo(FailureKind.RATE_LIMITED)
        assertThat(refused.detail).contains("Daily request budget")

        val minute = provider(perMinute = 1)
        minute.quote("AAPL", AssetClass.US_EQUITY, open)
        assertThat((minute.quote("MSFT", AssetClass.US_EQUITY, open) as ProviderResult.Failed).kind).isEqualTo(FailureKind.RATE_LIMITED)
    }

    @Test
    fun `MS-17 time series parses and malformed values are rejected`() {
        routes["/time_series"] = json("time_series_aapl_1h.json")
        val now = Instant.parse("2026-06-23T00:00:00Z")
        val bars = (provider().candles("AAPL", AssetClass.US_EQUITY, Timeframe.H1, now.minusSeconds(86400), now, now) as ProviderResult.Ok).value
        assertThat(bars).hasSize(3)
        assertThat(bars[0].openTime).isEqualTo(Instant.parse("2026-06-22T13:30:00Z"))
        routes["/time_series"] = json("time_series_malformed.json")
        val bad = provider().candles("AAPL", AssetClass.US_EQUITY, Timeframe.H1, now.minusSeconds(86400), now, now)
        assertThat((bad as ProviderResult.Failed).kind).isEqualTo(FailureKind.MALFORMED)
        routes["/time_series"] = { MockResponse().setBody("<html>not json</html>") }
        assertThat((provider().candles("AAPL", AssetClass.US_EQUITY, Timeframe.H1, now.minusSeconds(86400), now, now) as ProviderResult.Failed).kind).isEqualTo(FailureKind.MALFORMED)
    }

    @Test
    fun `MS-17 timeout, auth failure and plan limits are classified and never leak the key`() {
        routes["/quote"] = { MockResponse().setBody("{}").setHeadersDelay(5, TimeUnit.SECONDS) }
        val t = provider(timeoutSeconds = 1).quote("AAPL", AssetClass.US_EQUITY, open)
        assertThat((t as ProviderResult.Failed).kind).isEqualTo(FailureKind.TIMEOUT)
        routes["/quote"] = json("error_auth.json", 401)
        val a = provider().quote("AAPL", AssetClass.US_EQUITY, open) as ProviderResult.Failed
        assertThat(a.kind).isEqualTo(FailureKind.AUTHENTICATION)
        assertThat(a.detail).doesNotContain(key)
        routes["/splits"] = json("error_plan.json", 403)
        routes["/dividends"] = json("error_plan.json", 403)
        assertThat(provider().corporateActions("AAPL", AssetClass.US_EQUITY, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"))).isInstanceOf(ProviderResult.Unsupported::class.java)
        assertThat(provider().quote("BTC-USD", AssetClass.CRYPTO, open)).isInstanceOf(ProviderResult.Unsupported::class.java)
    }

    @Test
    fun `FR-020 lookup accepts only US-listed common stock or ETF`() {
        routes["/symbol_search"] = json("symbol_search.json")
        val ok = provider().lookup("PLTR") as ProviderResult.Ok
        assertThat(ok.value.exchange).isEqualTo("NASDAQ")
        assertThat(provider().lookup("SAP")).isInstanceOf(ProviderResult.Failed::class.java)
    }

    @Test
    fun `D-032 live stocks need the owner's key, then quotes verify and paper orders fill`() {
        routes["/quote"] = json("quote_aapl.json")
        // Daily bars (for the liquidity check): 60 sessions ending the day before.
        routes["/time_series"] = {
            val days =
                (1..90)
                    .map { LocalDate.parse("2026-06-22").minusDays(it.toLong()) }
                    .filter { MarketCalendar.session(it) != null }
                    .take(60)
                    .reversed()
            val values = days.joinToString(",") { d -> """{"datetime":"$d","open":"190.0","high":"192.0","low":"189.0","close":"191.0","volume":"50000000"}""" }
            MockResponse().setBody("""{"meta":{"symbol":"AAPL","interval":"1day"},"values":[$values],"status":"ok"}""").addHeader("Content-Type", "application/json")
        }
        val clock = MutableClock(open.plusSeconds(20))
        val e =
            Engine(
                JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
                clock,
                fixtureReader = { rel -> javaClass.getResource("/replay/$rel")!!.readText() },
                equityProvider = { keyOf -> TwelveDataProvider(keyOf, clock, OkHttpClient(), server.url("/"), requestsPerMinute = 1000) },
            )
        e.setMarketMode(MarketMode.LIVE)
        val aapl = e.instruments.bySymbol("AAPL")
        assertThat(e.market.refreshQuote(aapl).status).`as`("no key: explicitly unsupported").isEqualTo(DataStatus.UNSUPPORTED)
        assertThat(server.requestCount).isZero()

        assertThrows<EngineException> { e.equityKey.set(key) }
        e.auth.confirmed()
        e.equityKey.set(key)
        assertThat(e.equityKey.fingerprint()).hasSize(12)
        assertThat(
            e.db
                .sql("select count(*) from audit_events where details like :k")
                .param("k", "%$key%")
                .long(),
        ).`as`("key never in the audit log").isZero()

        val v = e.market.refreshQuote(aapl)
        assertThat(v.status).`as`(v.detail).isEqualTo(DataStatus.VERIFIED)
        assertThat(v.quote!!.provider).isEqualTo("TWELVE_DATA")
        val pid = e.portfolio()
        val r = e.order(pid, "AAPL", "BUY", "2")
        assertThat(r.order.status).`as`(r.risk.reasons.toString()).isEqualTo(OrderStatus.PENDING)
        clock.advanceSeconds(61)
        nowTs = clock.instant().epochSecond - 5
        e.step(clock.instant().minusSeconds(61), clock.instant())
        assertThat(e.orders.get(r.order.id).status).isEqualTo(OrderStatus.FILLED)

        e.equityKey.set(null)
        assertThat(e.market.refreshQuote(aapl).status).isEqualTo(DataStatus.UNSUPPORTED)
    }
}
