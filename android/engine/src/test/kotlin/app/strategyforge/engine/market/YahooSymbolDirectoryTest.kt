package app.strategyforge.engine.market

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/** D-065 Yahoo's search and chart answers become symbol matches and snapshots in the app's spelling. */
class YahooSymbolDirectoryTest {
    private val server = MockWebServer().also { it.start() }
    private val dir = YahooSymbolDirectory(baseUrl = server.url("/"))

    @AfterEach
    fun stop() = server.shutdown()

    @Test
    fun `search keeps US stocks, ETFs and USD coins and spells class shares the app's way`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"quotes":[
                  {"exchange":"NMS","shortname":"NVIDIA Corporation","quoteType":"EQUITY","symbol":"NVDA","longname":"NVIDIA Corporation","exchDisp":"NASDAQ","sector":"Technology","industry":"Semiconductors"},
                  {"exchange":"CME","shortname":"Micro NVIDIA futures","quoteType":"FUTURE","symbol":"XNVDA=F"},
                  {"exchange":"TOR","shortname":"NVIDIA CDR","quoteType":"EQUITY","symbol":"NVDA.TO"},
                  {"exchange":"BTS","shortname":"T-Rex 2X Long NVIDIA","quoteType":"ETF","symbol":"NVDX","exchDisp":"BATS Trading"},
                  {"exchange":"NYQ","shortname":"Berkshire Hathaway B","quoteType":"EQUITY","symbol":"BRK-B","exchDisp":"NYSE"},
                  {"exchange":"CCC","shortname":"Bitcoin USD","quoteType":"CRYPTOCURRENCY","symbol":"BTC-USD"},
                  {"exchange":"CCC","shortname":"Bitcoin EUR","quoteType":"CRYPTOCURRENCY","symbol":"BTC-EUR"}
                ]}
                """.trimIndent(),
            ),
        )
        val r = dir.search("nvidia")
        assertThat(r.map { it.symbol }).containsExactly("NVDA", "NVDX", "BRK.B", "BTC-USD")
        assertThat(r.first().sector).isEqualTo("Technology")
        assertThat(r.first().name).isEqualTo("NVIDIA Corporation")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("q")).isEqualTo("nvidia")
    }

    @Test
    fun `a chart answer gives price, yesterday's close from today's change, ranges and points`() {
        server.enqueue(
            MockResponse().setBody(
                """
                {"chart":{"result":[{"meta":{"currency":"USD","symbol":"BRK-B","fullExchangeName":"NYSE","regularMarketPrice":200.0,"regularMarketChangePercent":2.0,
                  "fiftyTwoWeekHigh":210.5,"fiftyTwoWeekLow":150.25,"regularMarketDayHigh":201.0,"regularMarketDayLow":195.0,"regularMarketVolume":1000,
                  "longName":"Berkshire Hathaway Inc.","chartPreviousClose":180.0,"regularMarketTime":1791230401},
                  "timestamp":[1791000000,1791086400,1791172800],
                  "indicators":{"quote":[{"close":[190.5,null,200.0]}]}}],"error":null}}
                """.trimIndent(),
            ),
        )
        val s = dir.snapshot("BRK.B", "1Y")!!
        assertThat(server.takeRequest().requestUrl!!.encodedPath).isEqualTo("/v8/finance/chart/BRK-B")
        assertThat(s.price).isEqualByComparingTo("200.0")
        // 200 / 1.02, not the one-year chart's starting close.
        assertThat(s.previousClose!!.setScale(2, java.math.RoundingMode.HALF_EVEN)).isEqualByComparingTo(BigDecimal("196.08"))
        assertThat(s.yearHigh).isEqualByComparingTo("210.5")
        assertThat(s.name).isEqualTo("Berkshire Hathaway Inc.")
        assertThat(s.points.map { it.price.toPlainString() }).containsExactly("190.5", "200.0")
    }

    @Test
    fun `an error or unreadable answer gives nothing instead of failing`() {
        server.enqueue(MockResponse().setResponseCode(500))
        assertThat(dir.search("x")).isEmpty()
        server.enqueue(MockResponse().setBody("not json"))
        assertThat(dir.snapshot("AAPL", "1M")).isNull()
        server.enqueue(MockResponse().setBody("""{"chart":{"result":null,"error":{"code":"Not Found"}}}"""))
        assertThat(dir.snapshot("ZZZZ", "1M")).isNull()
    }

    /** Opt-in (SF_LIVE_YAHOO=1): the real service answers in the shape the parser expects. */
    @Test
    @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SF_LIVE_YAHOO", matches = "1")
    fun `live Yahoo search and chart`() {
        val live = YahooSymbolDirectory()
        assertThat(live.search("nvidia").map { it.symbol }).contains("NVDA")
        assertThat(live.search("bitcoin").map { it.symbol }).contains("BTC-USD")
        listOf("NVDA" to "1D", "BRK.B" to "1Y", "BTC-USD" to "5Y").forEach { (sym, range) ->
            val s = live.snapshot(sym, range)!!
            assertThat(s.price).`as`(sym).isNotNull()
            assertThat(s.previousClose).`as`(sym).isNotNull()
            assertThat(s.yearHigh).`as`(sym).isNotNull()
            assertThat(s.points.size).`as`(sym).isGreaterThan(5)
        }
    }
}
