package app.strategyforge.market

import app.strategyforge.market.data.DataStatus
import app.strategyforge.market.data.MarketDataService
import app.strategyforge.support.IntegrationTest
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant

class MarketDataIT : IntegrationTest() {
    @Autowired lateinit var data: MarketDataService

    @Autowired lateinit var instruments: InstrumentService

    @Autowired lateinit var clock: MarketClock

    @Test
    fun `FR-020 instrument master contains US equities and allowlisted crypto only`() {
        val h = TestOwner.client(baseUrl)
        val all = h.get("/v1/instruments").json
        val symbols = all.map { it["symbol"].asText() }
        assertThat(symbols).contains("AAPL", "SPY", "BTC-USD", "ETH-USD")
        assertThat(all.map { it["assetClass"].asText() }.toSet()).containsOnly("US_EQUITY", "CRYPTO")
        val crypto = h.post("/v1/instruments", mapOf("symbol" to "PEPE-USD"))
        assertThat(crypto.status).isEqualTo(422)
        assertThat(crypto.json["code"].asText()).isEqualTo("crypto-not-allowlisted")
        val unknown = h.post("/v1/instruments", mapOf("symbol" to "ZZZZ"))
        assertThat(unknown.status).isEqualTo(422)
        assertThat(unknown.json["code"].asText()).isEqualTo("instrument-not-verified")
    }

    @Test
    fun `FR-023 MS-09 quotes show feed label, exact timestamp and freshness in replay mode`() {
        val h = TestOwner.client(baseUrl)
        val q = h.get("/v1/market-data/quotes/AAPL?refresh=true").json
        assertThat(q["feedType"].asText()).isEqualTo("REPLAY_SYNTHETIC")
        assertThat(q["feedLabel"].asText()).isEqualTo("Replay (synthetic data)")
        assertThat(q["exchangeTimestamp"].asText()).isNotBlank()
        assertThat(q["provider"].asText()).isEqualTo("REPLAY")
        assertThat(q["last"].isTextual).`as`("decimals are strings").isTrue()
        val status = h.get("/v1/market-data/status").json
        assertThat(status["clockMode"].asText()).isEqualTo("REPLAY")
        assertThat(status["syntheticData"].asBoolean()).isTrue()
        val realtime = status["capabilities"].first { it["capability"].asText() == "REALTIME_EQUITY" }
        assertThat(realtime["state"].asText()).isEqualTo("UNSUPPORTED")
    }

    @Test
    fun `FR-024 MS-17 out-of-order, clock-skewed and malformed data are rejected and block verification`() {
        val jpm = instruments.bySymbol("JPM")
        val now = clock.now()
        val first = data.storeQuote(jpm, QuoteData(BigDecimal("100.00"), BigDecimal("100.02"), BigDecimal("100.01"), null, null, now.minusSeconds(5), FeedType.REPLAY_SYNTHETIC), "REPLAY", now)
        assertThat(first.status).isEqualTo(DataStatus.VERIFIED)
        val older = data.storeQuote(jpm, QuoteData(null, null, BigDecimal("99"), null, null, now.minusSeconds(60), FeedType.REPLAY_SYNTHETIC), "REPLAY", now)
        assertThat(older.status).isEqualTo(DataStatus.OUT_OF_ORDER)
        assertThat(data.verifyQuote(jpm, 600, now).status).isEqualTo(DataStatus.OUT_OF_ORDER)
        val future = data.storeQuote(jpm, QuoteData(null, null, BigDecimal("101"), null, null, now.plusSeconds(120), FeedType.REPLAY_SYNTHETIC), "REPLAY", now)
        assertThat(future.status).isEqualTo(DataStatus.CLOCK_SKEW)
        val crossed = data.storeQuote(jpm, QuoteData(BigDecimal("101"), BigDecimal("100"), BigDecimal("100.5"), null, null, now, FeedType.REPLAY_SYNTHETIC), "REPLAY", now)
        assertThat(crossed.status).isEqualTo(DataStatus.MALFORMED)
        // A newer valid quote clears the flag.
        val ok = data.storeQuote(jpm, QuoteData(null, null, BigDecimal("100.50"), null, null, now, FeedType.REPLAY_SYNTHETIC), "REPLAY", now)
        assertThat(ok.status).isEqualTo(DataStatus.VERIFIED)
        assertThat(data.verifyQuote(jpm, 1, now.plusSeconds(30)).status).isEqualTo(DataStatus.STALE)

        val candlesOutOfOrder =
            listOf(
                CandleData(Instant.parse("2026-06-22T14:30:00Z"), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE),
                CandleData(Instant.parse("2026-06-22T13:30:00Z"), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE),
            )
        val bac = instruments.bySymbol("BAC")
        assertThat(data.storeCandles(bac, Timeframe.H1, candlesOutOfOrder, "REPLAY", Instant.parse("2026-06-23T00:00:00Z"))).isEqualTo(DataStatus.OUT_OF_ORDER)
        assertThat(data.flagFor(bac.id, "1h")!!.first).isEqualTo(DataStatus.OUT_OF_ORDER)
        assertThat(data.verifyCandles(bac, Timeframe.H1, 3).status).isNotEqualTo(DataStatus.VERIFIED)
        val auditBlocks = jdbc.sql("select count(*) from audit_events where action in ('QUOTE_REJECTED','CANDLES_REJECTED')").query(Int::class.java).single()
        assertThat(auditBlocks).isGreaterThanOrEqualTo(4)
    }

    @Test
    fun `FR-022 FR-054 candles aggregate from finer bars and documented gaps are reported`() {
        val h = TestOwner.client(baseUrl)
        val four = h.get("/v1/market-data/candles/MSFT?timeframe=4h&from=2026-06-15T00:00:00Z&to=2026-06-19T00:00:00Z").json
        assertThat(four["sourceTimeframe"].asText()).isEqualTo("1h")
        assertThat(four["bars"].size()).isEqualTo(8) // two session-aligned 4h bars per trading day
        assertThat(four["bars"][0]["closeTime"].asText()).isEqualTo("2026-06-15T17:30:00Z")
        assertThat(four["bars"][1]["closeTime"].asText()).isEqualTo("2026-06-15T20:00:00Z")
        val xom = h.get("/v1/market-data/candles/XOM?timeframe=1h&from=2026-03-10T00:00:00Z&to=2026-03-13T00:00:00Z").json
        assertThat(xom["status"].asText()).isEqualTo("GAPS")
        assertThat(xom["gaps"].map { it.asText() }).contains("2026-03-11T14:30:00Z", "2026-03-11T15:30:00Z", "2026-03-11T16:30:00Z")
        val xomSeries = data.verifyCandles(instruments.bySymbol("XOM"), Timeframe.H1, 5, Instant.parse("2026-03-11T19:00:00Z"))
        assertThat(xomSeries.status).isEqualTo(DataStatus.GAPS)
        val msft = data.verifyCandles(instruments.bySymbol("MSFT"), Timeframe.H1, 50, clock.now())
        assertThat(msft.status).`as`(msft.detail).isEqualTo(DataStatus.VERIFIED)
        val insufficient = data.verifyCandles(instruments.bySymbol("MSFT"), Timeframe.H1, 5000, clock.now())
        assertThat(insufficient.status).isEqualTo(DataStatus.INSUFFICIENT_HISTORY)
    }

    @Test
    fun `FR-021 watchlists and price alerts notify through the inbox`() {
        val h = TestOwner.client(baseUrl)
        val wl = h.post("/v1/watchlists", mapOf("name" to "Core", "symbols" to listOf("AAPL", "BTC-USD")))
        assertThat(wl.status).isEqualTo(201)
        assertThat(wl.json["items"].map { it["symbol"].asText() }).containsExactly("AAPL", "BTC-USD")
        val btc = h.get("/v1/market-data/quotes/BTC-USD?refresh=true").json["last"].asText()
        val alert = h.post("/v1/watchlists/alerts", mapOf("symbol" to "BTC-USD", "condition" to "ABOVE", "threshold" to BigDecimal(btc).multiply(BigDecimal("0.95")).setScale(2, java.math.RoundingMode.DOWN).toPlainString()))
        assertThat(alert.status).isEqualTo(201)
        h.post("/v1/market-data/replay/advance", mapOf("minutes" to 1))
        val a = h.get("/v1/watchlists/alerts?status=TRIGGERED").json.first { it["id"].asText() == alert.json["id"].asText() }
        assertThat(a["triggeredPrice"].asText()).isNotBlank()
        val inbox = h.get("/v1/notifications").json["items"]
        val n = inbox.first { it["category"].asText() == "PRICE_ALERT" && it["entityId"].asText() == alert.json["id"].asText() }
        assertThat(n["redactedTitle"].asText()).isEqualTo("Price alert").doesNotContain("BTC")
        assertThat(n["pushStatus"].asText()).isEqualTo("NO_CHANNEL")
    }

    @Test
    fun `FR-025 corporate actions and FX are available from replay with coverage`() {
        val h = TestOwner.client(baseUrl)
        val ca = h.get("/v1/market-data/corporate-actions/NVDA?from=2025-01-01&to=2025-12-31").json
        assertThat(ca["manualReviewRequired"].asBoolean()).isFalse()
        assertThat(ca["actions"].map { it["type"].asText() }).contains("SPLIT")
        val fx = h.get("/v1/market-data/fx/USD/CAD")
        assertThat(fx.status).isEqualTo(200)
        assertThat(fx.json["asOf"].asText()).isNotBlank()
        assertThat(h.get("/v1/market-data/fx/USD/EUR").status).isEqualTo(503)
    }

    @Test
    fun `FR-004 diagnostics include market provider capabilities and data freshness`() {
        val h = TestOwner.client(baseUrl)
        val comps = h.get("/v1/diagnostics").json["components"].associateBy { it["component"].asText() }
        assertThat(comps.keys).contains("market-provider", "data-freshness")
        assertThat(comps["market-provider"]!!["detail"].asText()).contains("SYNTHETIC")
    }

    @Test
    fun `FR-112 replay clock advances deterministically and is idempotent`() {
        val h = TestOwner.client(baseUrl)
        val before = Instant.parse(h.get("/v1/market-data/clock").json["now"].asText())
        val key = "replay-advance-" + java.util.UUID.randomUUID()
        val a = h.post("/v1/market-data/replay/advance", mapOf("minutes" to 2), idem = key)
        val b = h.post("/v1/market-data/replay/advance", mapOf("minutes" to 2), idem = key)
        assertThat(a.status).isEqualTo(200)
        assertThat(b.header("Idempotent-Replayed")).isEqualTo("true")
        val after = Instant.parse(h.get("/v1/market-data/clock").json["now"].asText())
        assertThat(after).isEqualTo(before.plusSeconds(120))
    }
}
