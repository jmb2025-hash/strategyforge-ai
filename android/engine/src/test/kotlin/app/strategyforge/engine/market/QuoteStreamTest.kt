package app.strategyforge.engine.market

import app.strategyforge.engine.Engine
import app.strategyforge.engine.EngineScheduler
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.support.MutableClock
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** D-056 live price streams: protocol handling against a local WebSocket server, and the engine's use of streamed quotes. */
class QuoteStreamTest {
    private val server = MockWebServer().also { it.start() }
    private val http = WebSocketQuoteStream.client(OkHttpClient())

    /** What the client sent, and the server side of the socket for pushing frames. */
    private val received = LinkedBlockingQueue<String>()
    private val serverSocket = LinkedBlockingQueue<WebSocket>()

    private fun serve(onOpen: (WebSocket) -> Unit = {}) {
        server.enqueue(
            MockResponse().withWebSocketUpgrade(
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) {
                        serverSocket.put(webSocket)
                        onOpen(webSocket)
                    }

                    override fun onMessage(
                        webSocket: WebSocket,
                        text: String,
                    ) {
                        received.put(text)
                    }

                    override fun onClosing(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) {
                        webSocket.close(1000, null)
                    }
                },
            ),
        )
    }

    private fun url() = server.url("/").toString().replace("http://", "ws://")

    private fun next(): String = received.poll(5, TimeUnit.SECONDS) ?: error("Nothing received")

    private fun waitFor(what: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!what()) {
            check(System.currentTimeMillis() < until) { "Timed out" }
            Thread.sleep(20)
        }
    }

    @AfterEach
    fun stop() {
        server.shutdown()
    }

    @Test
    fun `coinbase subscribes to the watched products and records ticker prices`() {
        serve()
        val s = CoinbaseQuoteStream(http, Clock.systemUTC(), url())
        s.sync(setOf("BTC-USD"))
        assertThat(next()).contains("\"subscribe\"").contains("BTC-USD").contains("\"channel\":\"ticker\"")
        assertThat(next()).contains("\"channel\":\"heartbeats\"")
        assertThat(s.status().state).isEqualTo(StreamState.LIVE)
        val ws = serverSocket.poll(5, TimeUnit.SECONDS)!!
        // Recorded from the live feed (2026-10-04), trimmed.
        ws.send(
            """{"channel":"ticker","timestamp":"2026-10-04T14:00:00.123456789Z","sequence_num":0,"events":[{"type":"snapshot","tickers":[""" +
                """{"type":"ticker","product_id":"BTC-USD","price":"64123.45","volume_24_h":"1728.44","best_bid":"64123.40","best_ask":"64123.50"}]}]}""",
        )
        ws.send("""{"channel":"heartbeats","timestamp":"2026-10-04T14:00:00.5Z","sequence_num":1,"events":[{"heartbeat_counter":324095}]}""")
        waitFor { s.latest("BTC-USD", Duration.ofSeconds(30)) != null }
        val t = s.latest("BTC-USD", Duration.ofSeconds(30))!!
        assertThat(t.last).isEqualByComparingTo("64123.45")
        assertThat(t.bid).isEqualByComparingTo("64123.40")
        assertThat(t.ask).isEqualByComparingTo("64123.50")
        assertThat(t.exchangeTs).isEqualTo(Instant.parse("2026-10-04T14:00:00.123456789Z"))
        assertThat(t.toQuote().feedType).isEqualTo(FeedType.REALTIME)

        s.sync(setOf("BTC-USD", "ETH-USD"))
        val add = next()
        assertThat(add).contains("\"subscribe\"").contains("ETH-USD").doesNotContain("BTC-USD")
        next()
        s.sync(setOf("ETH-USD"))
        assertThat(next()).contains("\"unsubscribe\"").contains("BTC-USD")
        s.sync(emptySet())
        assertThat(s.status().state).isEqualTo(StreamState.OFF)
        assertThat(s.latest("ETH-USD", Duration.ofSeconds(30))).isNull()
    }

    /** Builds a Yahoo `PricingData` message the way the streamer sends it (protobuf, base64, JSON envelope). */
    private fun pricing(
        id: String,
        price: Float,
        timeMs: Long,
        marketHours: Int = 1,
        bid: Float? = null,
        ask: Float? = null,
    ): String {
        val out = java.io.ByteArrayOutputStream()

        fun varint(v: Long) {
            var x = v
            while (x and 0x7fL.inv() != 0L) {
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }

        fun key(
            field: Int,
            wire: Int,
        ) = varint(((field shl 3) or wire).toLong())

        fun float(
            field: Int,
            v: Float,
        ) {
            key(field, 5)
            val bits = v.toRawBits()
            for (k in 0 until 4) out.write((bits ushr (8 * k)) and 0xff)
        }
        val idBytes = id.toByteArray()
        key(1, 2)
        varint(idBytes.size.toLong())
        out.write(idBytes)
        float(2, price)
        key(3, 0)
        varint((timeMs shl 1) xor (timeMs shr 63))
        key(4, 2)
        varint(3)
        out.write("USD".toByteArray())
        if (marketHours != 0) {
            key(7, 0)
            varint(marketHours.toLong())
        }
        bid?.let { float(23, it) }
        ask?.let { float(25, it) }
        key(27, 0)
        varint(4)
        val b64 =
            java.util.Base64
                .getEncoder()
                .encodeToString(out.toByteArray())
        return """{"type":"pricing","message":"$b64"}"""
    }

    @Test
    fun `yahoo subscribes without a key, decodes protobuf prices and repeats the subscription`() {
        serve()
        val clock = MutableClock(Instant.now())
        val s = YahooQuoteStream(http, clock, url())
        s.sync(setOf("AAPL", "BRK.B", "RY.TO"))
        assertThat(next()).isEqualTo("""{"subscribe":["AAPL","BRK-B","RY.TO"]}""")
        assertThat(s.status().state).isEqualTo(StreamState.LIVE)
        val ws = serverSocket.poll(5, TimeUnit.SECONDS)!!
        val now = clock.instant().toEpochMilli()
        ws.send(pricing("AAPL", 227.12f, now, bid = 227.1f, ask = 227.14f))
        ws.send(pricing("BRK-B", 450.5f, now))
        ws.send(pricing("RY.TO", 178.42f, now))
        waitFor { s.latest("RY.TO", Duration.ofMinutes(5)) != null }
        val a = s.latest("AAPL", Duration.ofMinutes(5))!!
        assertThat(a.last).isEqualByComparingTo("227.12")
        assertThat(a.bid).isEqualByComparingTo("227.10")
        assertThat(a.ask).isEqualByComparingTo("227.14")
        assertThat(a.exchangeTs.toEpochMilli()).isEqualTo(now)
        assertThat(a.feedType).isEqualTo(FeedType.REALTIME)
        assertThat(s.latest("BRK.B", Duration.ofMinutes(5))!!.last).`as`("mapped back from BRK-B").isEqualByComparingTo("450.50")
        assertThat(s.latest("RY.TO", Duration.ofMinutes(5))!!.last).isEqualByComparingTo("178.42")

        // A one-sided quote (as Yahoo sends for some TSX listings) keeps no bid/ask.
        ws.send(pricing("RY.TO", 178.5f, now, ask = 178.6f))
        waitFor { s.latest("RY.TO", Duration.ofMinutes(5))!!.last.compareTo(BigDecimal("178.50")) == 0 }
        assertThat(s.latest("RY.TO", Duration.ofMinutes(5))!!.ask).isNull()

        // Pre-market ticks are ignored; a price 20 minutes old is labelled delayed.
        ws.send(pricing("AAPL", 230.0f, now, marketHours = 0))
        ws.send(pricing("RY.TO", 179.0f, now - 20 * 60_000))
        waitFor { s.latest("RY.TO", Duration.ofMinutes(5))!!.feedType == FeedType.DELAYED }
        assertThat(s.latest("AAPL", Duration.ofMinutes(5))!!.last).isEqualByComparingTo("227.12")

        // Yahoo drops subscriptions that are not repeated: every 15 s the full list is sent again.
        s.sync(setOf("AAPL", "BRK.B", "RY.TO"))
        clock.advanceSeconds(16)
        s.sync(setOf("AAPL", "BRK.B", "RY.TO"))
        assertThat(generateSequence { received.poll(1, TimeUnit.SECONDS) }.toList()).contains("""{"subscribe":["AAPL","BRK-B","RY.TO"]}""")
        s.sync(setOf("AAPL"))
        assertThat(generateSequence { received.poll(1, TimeUnit.SECONDS) }.toList()).contains("""{"unsubscribe":["BRK-B","RY.TO"]}""")
    }

    @Test
    fun `the protobuf reader ignores fields it does not use and rejects garbage`() {
        val msg = pricing("ENB.TO", 61.25f, 1_790_971_200_000L, bid = 61.24f, ask = 61.26f)
        val b64 = msg.substringAfter("\"message\":\"").substringBefore("\"")
        val p =
            YahooPricing.decode(
                java.util.Base64
                    .getDecoder()
                    .decode(b64),
            )!!
        assertThat(p.id).isEqualTo("ENB.TO")
        assertThat(p.price).isEqualTo(61.25f)
        assertThat(p.time).isEqualTo(Instant.ofEpochMilli(1_790_971_200_000L))
        assertThat(p.marketHours).isEqualTo(YahooPricing.REGULAR_MARKET)
        assertThat(p.priceHint).isEqualTo(2)
        assertThat(YahooPricing.decode(byteArrayOf(0x0a, 0x7f, 0x41))).isNull()
    }

    /** A stream the test feeds by hand. */
    private class FakeStream(
        private val clock: Clock,
    ) : QuoteStream {
        override val name = "Fake"
        override val assetClass = AssetClass.CRYPTO
        var tick: StreamTick? = null
        var synced: Set<String> = emptySet()

        override fun sync(symbols: Set<String>) {
            synced = symbols
        }

        override fun latest(
            symbol: String,
            maxAge: Duration,
        ) = tick?.takeIf { it.symbol == symbol && !it.receivedAt.isBefore(clock.instant().minus(maxAge)) }

        override fun status() = StreamStatus(name, assetClass, StreamState.LIVE, synced.size, synced.size, null, null, null, 0)

        override fun reset() {}
    }

    @Test
    fun `the engine stores fresh streamed quotes every tick and falls back to polling without flagging`() {
        val clock = MutableClock(Instant.parse("2026-06-22T13:30:00Z"))
        val reader = { rel: String -> javaClass.getResource("/replay/$rel")!!.readText() }
        val live = ReplayProvider(ReplayFixtures(reader))
        val fake = FakeStream(clock)
        val e = Engine(JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")), clock, reader, cryptoProvider = { live }, cryptoStream = { fake })
        e.setMarketMode(MarketMode.LIVE)
        val btc = e.instruments.bySymbol("BTC-USD")
        e.marketInterests.add(MarketInterest { setOf(btc.id) })
        val s = EngineScheduler(e)
        s.tick()
        assertThat(fake.synced).contains("BTC-USD")
        val polled = e.market.latestQuote(btc.id)!!

        fake.tick = StreamTick("BTC-USD", BigDecimal("70000.0"), BigDecimal("70001.0"), BigDecimal("70000.5"), clock.instant(), clock.instant())
        clock.advanceSeconds(5)
        assertThat(s.tick().ran).contains("stream").doesNotContain("ingestion")
        val streamed = e.market.latestQuote(btc.id)!!
        assertThat(streamed.last).isEqualByComparingTo("70000.5")
        assertThat(streamed.provider).isEqualTo(polled.provider)
        assertThat(e.market.verifyQuote(btc, 60).verified).isTrue()

        // The stream goes quiet: polling takes over; an older polled quote is not an out-of-order error.
        clock.advanceSeconds(40)
        val v = e.market.refreshQuote(btc)
        assertThat(v.status).isNotEqualTo(DataStatus.OUT_OF_ORDER)
        assertThat(e.market.verifyQuote(btc, 600).status).isNotEqualTo(DataStatus.OUT_OF_ORDER)

        e.setMarketMode(MarketMode.DEMO)
        s.tick()
        assertThat(fake.synced).isEmpty()
    }
}
