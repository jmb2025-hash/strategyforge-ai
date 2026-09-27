package app.strategyforge.market

import app.strategyforge.market.provider.TwelveDataProvider
import app.strategyforge.providers.ProviderType
import app.strategyforge.providers.ResolvedProvider
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Provider fixture tests: recorded Twelve Data responses served by a local mock server (MS-17, MS-09). */
class TwelveDataProviderTest {
    private lateinit var server: MockWebServer
    private val key = "td-secret-key-123456"
    private var nowTs: Long = Instant.parse("2026-06-23T15:00:00Z").epochSecond
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
                    val symbol = request.requestUrl!!.queryParameter("symbol") ?: ""
                    return (routes["$path?$symbol"] ?: routes[path])?.invoke() ?: MockResponse().setResponseCode(404).setBody("""{"code":404,"message":"not found","status":"error"}""")
                }
            }
        server.start()
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun provider(
        perMinute: Int = 1000,
        timeoutSeconds: Int = 2,
    ) = TwelveDataProvider(
        ResolvedProvider(UUID.randomUUID(), ProviderType.TWELVE_DATA, "td", mapOf("baseUrl" to server.url("").toString().trimEnd('/'), "requestsPerMinute" to perMinute, "timeoutSeconds" to timeoutSeconds), key),
        Clock.systemUTC(),
        emptyMap(),
    )

    @Test
    fun `FR-022 quote parses decimals and exchange timestamp without bid ask`() {
        routes["/quote"] = json("quote_aapl.json")
        val now = Instant.ofEpochSecond(nowTs).plusSeconds(20)
        val q = (provider().quote("AAPL", AssetClass.US_EQUITY, now) as ProviderResult.Ok).value
        assertThat(q.last).isEqualByComparingTo("191.25")
        assertThat(q.bid).isNull()
        assertThat(q.exchangeTs).isEqualTo(Instant.ofEpochSecond(nowTs))
        assertThat(q.feedType).isEqualTo(FeedType.REALTIME)
        val req = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertThat(req.requestUrl!!.queryParameter("symbol")).isEqualTo("AAPL")
    }

    @Test
    fun `MS-09 FR-023 feed type is measured from quote lag, never assumed`() {
        val p = provider()
        val open = Instant.parse("2026-06-23T15:00:00Z")
        assertThat(p.classifyFeed(AssetClass.US_EQUITY, open.minusSeconds(20), open)).isEqualTo(FeedType.REALTIME)
        assertThat(p.classifyFeed(AssetClass.US_EQUITY, open.minusSeconds(900), open)).isEqualTo(FeedType.DELAYED)
        assertThat(p.classifyFeed(AssetClass.US_EQUITY, open.minusSeconds(300), open)).isEqualTo(FeedType.UNKNOWN)
        val fresh = provider()
        assertThat(fresh.classifyFeed(AssetClass.US_EQUITY, Instant.parse("2026-06-20T15:00:00Z"), Instant.parse("2026-06-20T15:30:00Z"))).isEqualTo(FeedType.UNKNOWN)
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
    fun `MS-17 provider timeout, auth failure and rate limit are classified and never leak the key`() {
        routes["/quote"] = { MockResponse().setBody("{}").setHeadersDelay(5, TimeUnit.SECONDS) }
        val t = provider(timeoutSeconds = 1).quote("AAPL", AssetClass.US_EQUITY, Instant.now())
        assertThat((t as ProviderResult.Failed).kind).isEqualTo(FailureKind.TIMEOUT)
        routes["/quote"] = json("error_auth.json", 401)
        val a = provider().quote("AAPL", AssetClass.US_EQUITY, Instant.now()) as ProviderResult.Failed
        assertThat(a.kind).isEqualTo(FailureKind.AUTHENTICATION)
        assertThat(a.detail).doesNotContain(key)
        routes["/quote"] = json("quote_aapl.json")
        val limited = provider(perMinute = 1)
        limited.quote("AAPL", AssetClass.US_EQUITY, Instant.now())
        assertThat((limited.quote("AAPL", AssetClass.US_EQUITY, Instant.now()) as ProviderResult.Failed).kind).isEqualTo(FailureKind.RATE_LIMITED)
    }

    @Test
    fun `MS-09 capability diagnostics report unsupported features explicitly`() {
        nowTs = Instant.now().epochSecond - 30
        routes["/quote"] = json("quote_aapl.json")
        routes["/time_series"] = json("time_series_aapl_1h.json")
        routes["/exchange_rate"] = json("exchange_rate.json")
        routes["/splits"] = json("error_plan.json", 403)
        routes["/dividends"] = json("error_plan.json", 403)
        routes["/symbol_search"] = json("symbol_search.json")
        val r = provider().diagnose(Instant.now())
        val caps = r.capabilities.associate { it.capability to it.status }
        assertThat(caps["BID_ASK"]).isEqualTo("UNSUPPORTED")
        assertThat(caps["CORPORATE_ACTIONS"]).isEqualTo("UNSUPPORTED")
        assertThat(caps["EQUITY_QUOTES"]).isEqualTo("SUPPORTED")
        assertThat(caps["FX_RATES"]).isEqualTo("SUPPORTED")
        assertThat(caps).containsKey("REALTIME_EQUITY")
        assertThat(caps["INSTRUMENT_LOOKUP"]).isEqualTo("UNSUPPORTED") // AAPL is not in the recorded search result
        assertThat(r.status.name).isEqualTo("DEGRADED")
    }

    @Test
    fun `FR-020 lookup accepts only US-listed common stock or ETF`() {
        routes["/symbol_search"] = json("symbol_search.json")
        val ok = provider().lookup("PLTR") as ProviderResult.Ok
        assertThat(ok.value.exchange).isEqualTo("NASDAQ")
        assertThat(provider().lookup("SAP")).isInstanceOf(ProviderResult.Failed::class.java)
    }

    @Test
    fun `FR-025 corporate actions unavailable on the plan are reported as unsupported`() {
        routes["/splits"] = json("error_plan.json", 403)
        routes["/dividends"] = json("error_plan.json", 403)
        val r = provider().corporateActions("AAPL", AssetClass.US_EQUITY, LocalDate.parse("2025-01-01"), LocalDate.parse("2025-12-31"))
        assertThat(r).isInstanceOf(ProviderResult.Unsupported::class.java)
    }
}
