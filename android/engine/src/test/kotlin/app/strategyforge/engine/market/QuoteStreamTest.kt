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

    @Test
    fun `alpaca authenticates with the key pair, then streams trades and quotes`() {
        serve { it.send("""[{"T":"success","msg":"connected"}]""") }
        val s = AlpacaQuoteStream(http, Clock.systemUTC(), { "PKTESTKEYID123" to "test-secret-value-0123456789" }, url())
        s.sync(setOf("AAPL", "BRK.B"))
        val auth = next()
        assertThat(auth).contains("\"auth\"").contains("PKTESTKEYID123").contains("test-secret-value-0123456789")
        val ws = serverSocket.poll(5, TimeUnit.SECONDS)!!
        assertThat(s.status().state).isEqualTo(StreamState.CONNECTING)
        ws.send("""[{"T":"success","msg":"authenticated"}]""")
        val sub = next()
        assertThat(sub).contains("\"subscribe\"").contains("\"trades\":[\"AAPL\",\"BRK.B\"]").contains("\"quotes\":[\"AAPL\",\"BRK.B\"]")
        assertThat(s.status().state).isEqualTo(StreamState.LIVE)
        ws.send("""[{"T":"q","S":"AAPL","bp":227.1,"ap":227.14,"t":"2026-10-02T14:30:01.5Z"},{"T":"t","S":"AAPL","p":227.12,"s":100,"t":"2026-10-02T14:30:02Z"}]""")
        waitFor { s.latest("AAPL", Duration.ofMinutes(5))?.last?.compareTo(BigDecimal("227.12")) == 0 }
        val t = s.latest("AAPL", Duration.ofMinutes(5))!!
        assertThat(t.bid).isEqualByComparingTo("227.1")
        assertThat(t.ask).isEqualByComparingTo("227.14")
        assertThat(t.exchangeTs).isEqualTo(Instant.parse("2026-10-02T14:30:02Z"))
        // A quote alone (no trade yet) prices at the mid.
        ws.send("""[{"T":"q","S":"BRK.B","bp":450.0,"ap":450.2,"t":"2026-10-02T14:30:03Z"}]""")
        waitFor { s.latest("BRK.B", Duration.ofMinutes(5)) != null }
        assertThat(s.latest("BRK.B", Duration.ofMinutes(5))!!.last).isEqualByComparingTo("450.1")
    }

    @Test
    fun `a rejected alpaca key stops the stream with the reason and waits before retrying`() {
        serve { it.send("""[{"T":"success","msg":"connected"}]""") }
        val s = AlpacaQuoteStream(http, Clock.systemUTC(), { "PKTESTKEYID123" to "wrong-secret-value-0123456789" }, url())
        s.sync(setOf("AAPL"))
        next()
        serverSocket.poll(5, TimeUnit.SECONDS)!!.send("""[{"T":"error","code":402,"msg":"auth failed"}]""")
        waitFor { s.status().state == StreamState.ERROR }
        assertThat(s.status().error).isEqualTo("Alpaca: auth failed (402)")
        s.sync(setOf("AAPL"))
        assertThat(s.status().state).`as`("no immediate retry").isEqualTo(StreamState.ERROR)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `without a key the stock stream stays off`() {
        val s = AlpacaQuoteStream(http, Clock.systemUTC(), { null }, url())
        s.sync(setOf("AAPL"))
        assertThat(s.status().state).isEqualTo(StreamState.OFF)
        assertThat(server.requestCount).isZero()
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
