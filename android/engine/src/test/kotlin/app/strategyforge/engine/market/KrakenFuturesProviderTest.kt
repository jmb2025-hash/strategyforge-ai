package app.strategyforge.engine.market

import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * D-044 Kraken Futures public data: the analytics and funding formats (as published and as used by
 * ccxt), contract names, paging, and how failures are reported.
 */
class KrakenFuturesProviderTest {
    private val mapper = ObjectMapper()
    private val server = MockWebServer()
    private val t0 = 1_780_000_000L - 1_780_000_000L % 3600

    @AfterEach
    fun stop() = server.shutdown()

    private fun json(s: String) = mapper.readTree(s)

    @Test
    fun `contracts follow Kraken's perpetual names`() {
        val p = KrakenFuturesProvider(Clock.systemUTC())
        assertThat(p.contractFor("BTC-USD")).isEqualTo("PF_XBTUSD")
        assertThat(p.contractFor("ETH-USD")).isEqualTo("PF_ETHUSD")
        assertThat(p.contractFor("SOL-USD")).isEqualTo("PF_SOLUSD")
        assertThat(p.contractFor("AAPL")).isNull()
        assertThat(p.contractFor("BTC-EUR")).isNull()
    }

    @Test
    fun `analytics values are read from plain arrays, OHLC objects and buy and sell columns`() {
        val plain = KrakenFuturesProvider.parseAnalytics(json("""{"result":{"timestamp":[$t0,${t0 + 3600}],"data":[1950.5,"1960.25"],"more":false},"errors":[]}"""), "open-interest")
        val p = (plain as ProviderResult.Ok).value
        assertThat(p.points.map { it.ts }).containsExactly(Instant.ofEpochSecond(t0), Instant.ofEpochSecond(t0 + 3600))
        assertThat(p.points.map { it.value }).containsExactly(1950.5, 1960.25)
        assertThat(p.more).isFalse()

        val ohlc = KrakenFuturesProvider.parseAnalytics(json("""{"result":{"timestamp":[$t0],"data":{"open":[1],"high":[3],"low":[0.5],"close":[2]},"more":true}}"""), "open-interest")
        assertThat(
            (ohlc as ProviderResult.Ok)
                .value.points
                .single()
                .value,
        ).isEqualTo(2.0)
        assertThat(ohlc.value.more).isTrue()

        val flow = KrakenFuturesProvider.parseAnalytics(json("""{"result":{"timestamp":[${t0 * 1000}],"data":{"buyVolume":[12.5],"sellVolume":[20]}}}"""), "aggressor-differential")
        val point = (flow as ProviderResult.Ok).value.points.single()
        assertThat(point.value).isEqualTo(-7.5)
        assertThat(point.ts).`as`("millisecond timestamps are recognised").isEqualTo(Instant.ofEpochSecond(t0))

        val rows = KrakenFuturesProvider.parseAnalytics(json("""{"result":{"timestamp":[$t0, ${t0 + 60}],"data":[{"buy":5,"sell":2}, null]}}"""), "aggressor-differential")
        assertThat((rows as ProviderResult.Ok).value.points.map { it.value }).`as`("missing values are skipped").containsExactly(3.0)
    }

    @Test
    fun `malformed or refused analytics are reported, never guessed`() {
        assertThat(KrakenFuturesProvider.parseAnalytics(json("""{"errors":[{"message":"Symbol not found"}]}"""), "open-interest"))
            .isEqualTo(ProviderResult.Failed(FailureKind.NO_DATA, "open-interest: Symbol not found"))
        val odd = KrakenFuturesProvider.parseAnalytics(json("""{"result":{"timestamp":[$t0],"data":{"alpha":[1],"beta":[2]}}}"""), "aggressor-differential")
        assertThat((odd as ProviderResult.Failed).detail).contains("unrecognised data fields alpha, beta")
        val short = KrakenFuturesProvider.parseAnalytics(json("""{"result":{"timestamp":[$t0, ${t0 + 1}],"data":[1]}}"""), "open-interest")
        assertThat((short as ProviderResult.Failed).kind).isEqualTo(FailureKind.MALFORMED)
    }

    @Test
    fun `funding is the relative rate per hour, in percent`() {
        val r =
            KrakenFuturesProvider.parseFunding(
                json("""{"rates":[{"timestamp":"2026-09-01T01:00:00.000Z","fundingRate":"2.18900669884E-7","relativeFundingRate":"0.000060779960000000"},{"timestamp":"2026-09-01T00:00:00.000Z","fundingRate":1e-7,"relativeFundingRate":-0.00001}]}"""),
            )
        val pts = (r as ProviderResult.Ok).value
        assertThat(pts.map { it.ts.toString() }).containsExactly("2026-09-01T00:00:00Z", "2026-09-01T01:00:00Z")
        assertThat(pts[0].value).isCloseTo(-0.001, within(1e-12))
        assertThat(pts[1].value).isCloseTo(0.006077996, within(1e-12))
    }

    @Test
    fun `history pages through analytics and combines open interest, delta and funding`() {
        val calls = mutableListOf<String>()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path!!
                    calls += path
                    val u = request.requestUrl!!
                    val since = u.queryParameter("since")?.toLong()
                    val body =
                        when {
                            "historicalfundingrates" in path -> """{"rates":[{"timestamp":"${Instant.ofEpochSecond(t0)}","relativeFundingRate":0.0001}],"result":"success"}"""
                            "open-interest" in path && since == t0 -> """{"result":{"timestamp":[$t0,${t0 + 3600}],"data":[100,110],"more":true},"errors":[]}"""
                            "open-interest" in path -> """{"result":{"timestamp":[${t0 + 7200}],"data":[120],"more":false},"errors":[]}"""
                            else -> """{"result":{"timestamp":[$t0,${t0 + 3600},${t0 + 7200}],"data":[1,-2,3],"more":false},"errors":[]}"""
                        }
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
                }
            }
        server.start()
        val now = Instant.ofEpochSecond(t0 + 4 * 3600)
        val p = KrakenFuturesProvider(Clock.fixed(now, ZoneOffset.UTC), OkHttpClient(), server.url("/"), requestsPerSecond = 50)
        val r = p.history("BTC-USD", Timeframe.H1, Instant.ofEpochSecond(t0), Instant.ofEpochSecond(t0 + 3 * 3600), now)
        val d = (r as ProviderResult.Ok).value
        assertThat(d.openInterest.map { it.value }).containsExactly(100.0, 110.0, 120.0)
        assertThat(d.delta.map { it.value }).containsExactly(1.0, -2.0, 3.0)
        assertThat(d.fundingRate.single().value).isCloseTo(0.01, within(1e-12))
        assertThat(d.source).isEqualTo("KRAKEN_FUTURES")
        assertThat(calls.filter { "open-interest" in it }).hasSize(2)
        assertThat(calls.first { "open-interest" in it }).contains("/api/charts/v1/analytics/PF_XBTUSD/open-interest").contains("interval=3600")
        assertThat(calls.single { "historicalfundingrates" in it }).contains("/derivatives/api/v4/historicalfundingrates?symbol=PF_XBTUSD")
    }

    @Test
    fun `an unreachable service is a provider failure`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
            }
        server.start()
        val p = KrakenFuturesProvider(Clock.systemUTC(), OkHttpClient(), server.url("/"), requestsPerSecond = 50)
        val r = p.history("ETH-USD", Timeframe.H1, Instant.ofEpochSecond(t0), Instant.ofEpochSecond(t0 + 3600), Instant.ofEpochSecond(t0 + 7200))
        assertThat((r as ProviderResult.Failed).kind).isEqualTo(FailureKind.HTTP_ERROR)
        assertThat(p.diagnose(Instant.now()).status).isEqualTo(TestStatus.FAILED)
    }
}
