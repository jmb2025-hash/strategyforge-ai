package app.strategyforge.market

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.TestOwner
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Live-provider path using recorded responses from a local mock server (never the real API).
 * Covers capability discovery, explicit unsupported states and delayed labelling (MS-09).
 */
class LiveProviderIT : FreshDatabaseTest() {
    companion object {
        val server =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse {
                            val path = request.requestUrl!!.encodedPath
                            val symbol = request.requestUrl!!.queryParameter("symbol") ?: ""

                            fun f(
                                name: String,
                                ts: Long = Instant.now().epochSecond,
                            ) = MockResponse().setBody(javaClass.getResource("/twelvedata/$name")!!.readText().replace("__TS__", ts.toString()))
                            return when (path) {
                                // Crypto trades 24/7 so the lag measurement is deterministic: 20 minutes old => delayed.
                                "/quote" -> f("quote_aapl.json", Instant.now().epochSecond - (if (symbol == "BTC/USD") 1200 else 30))
                                "/time_series" -> f("time_series_aapl_1h.json")
                                "/exchange_rate" -> f("exchange_rate.json")
                                "/splits", "/dividends" -> f("error_plan.json").setResponseCode(403)
                                "/symbol_search" -> f("symbol_search.json")
                                else -> MockResponse().setResponseCode(404)
                            }
                        }
                    }
                start()
            }

        @JvmStatic
        @AfterAll
        fun stop() = server.shutdown()
    }

    @Test
    fun `MS-09 FR-004 live provider capabilities are detected, unsupported states explicit, delayed data labelled`() {
        val h = TestOwner.client(baseUrl)
        val created =
            h.post(
                "/v1/providers",
                mapOf("providerType" to "TWELVE_DATA", "displayName" to "Twelve Data", "credential" to "td-live-key-000000", "settings" to mapOf("baseUrl" to server.url("").toString().trimEnd('/'), "requestsPerMinute" to 600)),
            )
        assertThat(created.status).isEqualTo(201)
        val id = created.json["id"].asText()
        val tested = h.post("/v1/providers/$id/test").json
        val caps = tested["capabilities"].associate { it["capability"].asText() to it["status"].asText() }
        assertThat(caps["BID_ASK"]).isEqualTo("UNSUPPORTED")
        assertThat(caps["CORPORATE_ACTIONS"]).isEqualTo("UNSUPPORTED")
        assertThat(caps["CRYPTO_QUOTES"]).isEqualTo("SUPPORTED")
        assertThat(tested["lastTestStatus"].asText()).isEqualTo("DEGRADED")

        assertThat(h.post("/v1/providers/$id/activate").status).isEqualTo(200)
        val status = h.get("/v1/market-data/status").json
        assertThat(status["providerType"].asText()).isEqualTo("TWELVE_DATA")
        assertThat(status["clockMode"].asText()).isEqualTo("LIVE")
        assertThat(status["capabilities"].first { it["capability"].asText() == "CORPORATE_ACTIONS" }["state"].asText()).isEqualTo("UNSUPPORTED")

        val btc = h.get("/v1/market-data/quotes/BTC-USD?refresh=true&maxAgeSeconds=3600").json
        assertThat(btc["feedLabel"].asText()).isEqualTo("Delayed")
        assertThat(btc["ageSeconds"].asLong()).isGreaterThanOrEqualTo(1190)
        val stale = h.get("/v1/market-data/quotes/BTC-USD?maxAgeSeconds=30").json
        assertThat(stale["status"].asText()).isEqualTo("STALE")

        // FR-025: corporate actions unavailable => Manual Review Required for dependent results.
        val ca = h.get("/v1/market-data/corporate-actions/AAPL?from=2025-01-01&to=2025-12-31").json
        assertThat(ca["manualReviewRequired"].asBoolean()).isTrue()
        assertThat(ca["coverage"]["status"].asText()).isEqualTo("UNAVAILABLE")

        // The replay clock is not available in live mode.
        assertThat(h.post("/v1/market-data/replay/advance", mapOf("minutes" to 1)).json["code"].asText()).isEqualTo("not-replay-mode")
    }
}
