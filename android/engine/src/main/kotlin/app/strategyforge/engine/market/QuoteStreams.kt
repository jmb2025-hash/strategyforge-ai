package app.strategyforge.engine.market

import app.strategyforge.engine.common.EngineLog
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** The latest price a stream has pushed for one symbol (D-056). */
data class StreamTick(
    val symbol: String,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val last: BigDecimal,
    val exchangeTs: Instant,
    val receivedAt: Instant,
    val feedType: FeedType = FeedType.REALTIME,
) {
    fun toQuote(): QuoteData = QuoteData(bid, ask, last, null, null, exchangeTs, feedType)
}

enum class StreamState { OFF, CONNECTING, LIVE, ERROR }

data class StreamStatus(
    val name: String,
    val assetClass: AssetClass,
    val state: StreamState,
    val symbols: Int,
    val subscribed: Int,
    val connectedAt: Instant?,
    val lastMessageAt: Instant?,
    val error: String?,
    val reconnects: Int,
)

/** A push price feed for one asset class. [sync] is called on every engine tick with the symbols of interest. */
interface QuoteStream {
    val name: String
    val assetClass: AssetClass

    /** Connects, resubscribes or disconnects so the stream carries exactly [symbols] (empty: disconnect). */
    fun sync(symbols: Set<String>)

    /** The latest tick for [symbol] if it arrived within [maxAge]. */
    fun latest(
        symbol: String,
        maxAge: Duration,
    ): StreamTick?

    fun status(): StreamStatus

    /** Drops the connection (for example after a key change); the next [sync] reconnects. */
    fun reset()
}

/**
 * Shared WebSocket plumbing (D-056): one connection, subscriptions kept in step with the wanted
 * symbols, reconnection with backoff (2 s doubling to 60 s) and an optional silence watchdog.
 * Socket callbacks arrive on OkHttp threads and only touch the tick map and this object's state,
 * never the database; the engine thread reads ticks when it ingests quotes.
 */
abstract class WebSocketQuoteStream(
    private val http: OkHttpClient,
    protected val clock: Clock,
) : QuoteStream {
    private val log = EngineLog.of(javaClass)
    protected val mapper: ObjectMapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val ticks = ConcurrentHashMap<String, StreamTick>()
    private val lock = Any()
    private var socket: WebSocket? = null
    private var wanted: Set<String> = emptySet()
    private var subscribed: Set<String> = emptySet()
    private var failures = 0
    private var retryAt: Instant? = null

    @Volatile private var state = StreamState.OFF

    @Volatile private var connectedAt: Instant? = null

    @Volatile private var lastMessageAt: Instant? = null

    @Volatile private var error: String? = null

    @Volatile private var reconnects = 0

    protected abstract fun url(): String

    /** False while the stream cannot connect at all (for example no key); it stays off. */
    protected open fun ready(): Boolean = true

    /** Silence after which a live connection is assumed dead and replaced; null: no watchdog. */
    protected open val silenceLimit: Duration? = null

    /** Some feeds drop subscriptions that are not repeated; null: subscribe once. */
    protected open val resubscribeEvery: Duration? = null
    private var resubscribedAt: Instant? = null

    /** Called when the socket opens; protocols without a handshake call [markReady] here. */
    protected open fun onOpened(ws: WebSocket) {}

    protected abstract fun subscribe(symbols: Set<String>): List<String>

    protected abstract fun unsubscribe(symbols: Set<String>): List<String>

    /** Handles one text frame; call [markReady] when subscriptions may be sent and [put] for each price. */
    protected abstract fun handle(
        ws: WebSocket,
        text: String,
    )

    override fun sync(symbols: Set<String>) {
        synchronized(lock) {
            wanted = symbols
            val s = socket
            if (symbols.isEmpty() || !ready()) {
                if (s != null) close(s, "No symbols to stream")
                state = StreamState.OFF
                if (!ready()) error = null
                return
            }
            val now = clock.instant()
            if (s == null) {
                if (retryAt?.let { now.isBefore(it) } == true) return
                connect()
                return
            }
            if (state != StreamState.LIVE) return
            val quiet = silenceLimit
            val last = lastMessageAt ?: connectedAt
            if (quiet != null && last != null && Duration.between(last, now) > quiet) {
                fail(s, "No data for ${Duration.between(last, now).seconds}s; reconnecting")
                return
            }
            val add = symbols - subscribed
            val remove = subscribed - symbols
            if (remove.isNotEmpty()) unsubscribe(remove).forEach { s.send(it) }
            if (add.isNotEmpty()) subscribe(add).forEach { s.send(it) }
            subscribed = symbols
            val again = resubscribeEvery
            if (again != null && resubscribedAt?.let { Duration.between(it, now) >= again } != false) {
                if (add.isEmpty()) subscribe(symbols).forEach { s.send(it) }
                resubscribedAt = now
            }
        }
    }

    override fun latest(
        symbol: String,
        maxAge: Duration,
    ): StreamTick? = ticks[symbol]?.takeIf { state == StreamState.LIVE && !it.receivedAt.isBefore(clock.instant().minus(maxAge)) }

    override fun status(): StreamStatus =
        synchronized(lock) {
            StreamStatus(name, assetClass, state, wanted.size, subscribed.size, connectedAt, lastMessageAt, error, reconnects)
        }

    override fun reset() {
        synchronized(lock) {
            socket?.let { close(it, "Reset") }
            failures = 0
            retryAt = null
            error = null
            state = StreamState.OFF
        }
    }

    private fun connect() {
        state = StreamState.CONNECTING
        subscribed = emptySet()
        val req = Request.Builder().url(url()).build()
        socket = http.newWebSocket(req, Listener())
    }

    private fun close(
        s: WebSocket,
        reason: String,
    ) {
        socket = null
        subscribed = emptySet()
        connectedAt = null
        runCatching { s.close(1000, reason.take(100)) }
    }

    /** Ends [s] after a problem and schedules the next attempt. */
    protected fun fail(
        s: WebSocket,
        reason: String,
        retryAfter: Duration? = null,
    ) {
        synchronized(lock) {
            if (s !== socket) return
            socket = null
            subscribed = emptySet()
            connectedAt = null
            state = StreamState.ERROR
            error = reason
            failures++
            reconnects++
            val wait = retryAfter ?: Duration.ofSeconds(minOf(60L, 1L shl minOf(failures, 6)))
            retryAt = clock.instant().plus(wait)
        }
        runCatching { s.cancel() }
        log.warn("{} stream stopped: {}", name, reason)
    }

    /** The protocol is ready for subscriptions (connected and, where needed, authenticated). */
    protected fun markReady(ws: WebSocket) {
        synchronized(lock) {
            if (ws !== socket) return
            state = StreamState.LIVE
            failures = 0
            error = null
            connectedAt = clock.instant()
            if (wanted.isNotEmpty()) subscribe(wanted).forEach { ws.send(it) }
            subscribed = wanted
        }
    }

    protected fun reportError(message: String) {
        error = message
    }

    /**
     * Records a price; null fields keep what the previous tick had. The time is capped at arrival, so
     * a phone clock a few seconds behind the exchange never makes a just-received price look future-dated.
     */
    protected fun put(
        symbol: String,
        last: BigDecimal?,
        bid: BigDecimal?,
        ask: BigDecimal?,
        exchangeTime: Instant,
        feedType: FeedType = FeedType.REALTIME,
    ) {
        val now = clock.instant()
        val ts = minOf(exchangeTime, now)
        // Bid and ask are used only as a pair: a one-sided or crossed quote would skew simulated fills.
        val pair = bid != null && ask != null
        val (b, a) = if (pair && bid!! <= ask!!) bid to ask else null to null
        ticks.compute(symbol) { _, old ->
            val price = last ?: old?.last ?: if (b != null && a != null) b.add(a).divide(BigDecimal(2)) else null
            if (price == null || price.signum() <= 0) {
                old
            } else {
                StreamTick(
                    symbol,
                    if (pair) b else old?.bid,
                    if (pair) a else old?.ask,
                    price,
                    maxOf(ts, old?.exchangeTs ?: ts),
                    now,
                    feedType,
                )
            }
        }
    }

    /** Numbers arrive as JSON numbers or strings (Coinbase). */
    protected fun JsonNode.decimal(field: String): BigDecimal? =
        get(field)?.let { n ->
            when {
                n.isNumber -> n.decimalValue()
                n.isTextual -> n.asText().toBigDecimalOrNull()
                else -> null
            }
        }

    protected fun JsonNode.instant(field: String): Instant? = get(field)?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(
            webSocket: WebSocket,
            response: Response,
        ) {
            synchronized(lock) { if (webSocket !== socket) return }
            onOpened(webSocket)
        }

        override fun onMessage(
            webSocket: WebSocket,
            text: String,
        ) {
            if (webSocket !== socket) return
            lastMessageAt = clock.instant()
            runCatching { handle(webSocket, text) }.onFailure { log.warn("{} stream message not understood", name, it) }
        }

        override fun onClosing(
            webSocket: WebSocket,
            code: Int,
            reason: String,
        ) {
            fail(webSocket, "Closed by the server ($code${if (reason.isNotBlank()) ": $reason" else ""})")
        }

        override fun onFailure(
            webSocket: WebSocket,
            t: Throwable,
            response: Response?,
        ) {
            fail(webSocket, "Connection failed: ${response?.code?.let { "HTTP $it" } ?: (t.message ?: t.javaClass.simpleName)}")
        }
    }

    companion object {
        /** A client for long-lived sockets: no call timeout, pings every 20 s to notice dead links. */
        fun client(base: OkHttpClient = CoinbaseProvider.defaultClient()): OkHttpClient =
            base
                .newBuilder()
                .callTimeout(Duration.ZERO)
                .readTimeout(Duration.ZERO)
                .pingInterval(Duration.ofSeconds(20))
                .build()
    }
}

/**
 * Coinbase Advanced Trade's public WebSocket (D-056): the `ticker` channel pushes price, best bid
 * and best ask for each product, no key needed; `heartbeats` arrive every second, so silence means
 * a dead link. Messages carry the event time in `timestamp` and a list of `events[].tickers[]`.
 */
class CoinbaseQuoteStream(
    http: OkHttpClient,
    clock: Clock,
    private val endpoint: String = "wss://advanced-trade-ws.coinbase.com",
) : WebSocketQuoteStream(http, clock) {
    override val name = "Coinbase"
    override val assetClass = AssetClass.CRYPTO
    override val silenceLimit: Duration = Duration.ofSeconds(30)

    override fun url() = endpoint

    private fun message(
        type: String,
        channel: String,
        symbols: Set<String>,
    ): String {
        val o = mapper.createObjectNode()
        o.put("type", type)
        val ids = o.putArray("product_ids")
        symbols.sorted().forEach { ids.add(it) }
        o.put("channel", channel)
        return mapper.writeValueAsString(o)
    }

    override fun subscribe(symbols: Set<String>) = listOf(message("subscribe", "ticker", symbols), message("subscribe", "heartbeats", symbols))

    override fun unsubscribe(symbols: Set<String>) = listOf(message("unsubscribe", "ticker", symbols))

    override fun onOpened(ws: WebSocket) {
        // No handshake: the socket is ready as soon as it opens.
        markReady(ws)
    }

    override fun handle(
        ws: WebSocket,
        text: String,
    ) {
        val j = mapper.readTree(text)
        if (j.path("type").asText() == "error") {
            reportError("Coinbase: ${j.path("message").asText()}".trim())
            return
        }
        if (j.path("channel").asText() != "ticker") return
        val ts = j.instant("timestamp") ?: clock.instant()
        for (e in j.path("events")) {
            for (t in e.path("tickers")) {
                val sym = t.path("product_id").asText().takeIf { it.isNotEmpty() } ?: continue
                put(sym, t.decimal("price"), t.decimal("best_bid"), t.decimal("best_ask"), ts)
            }
        }
    }
}

/**
 * Yahoo Finance's streaming endpoint (D-056), the one its web pages use: no key, US stocks and ETFs
 * and TSX listings (`.TO`). It is unofficial and may change without notice; the engine falls back
 * to polling whenever it is silent. Subscriptions are JSON (`{"subscribe":[...]}`) and must be
 * repeated every 15 s; each price arrives as `{"type":"pricing","message":<base64 protobuf>}`
 * (yfinance's `PricingData`). Only regular-session prices are used: pre- and post-market ticks
 * would move stops on thin trading.
 */
class YahooQuoteStream(
    http: OkHttpClient,
    clock: Clock,
    private val endpoint: String = "wss://streamer.finance.yahoo.com/?version=2",
) : WebSocketQuoteStream(http, clock) {
    override val name = "Yahoo Finance"
    override val assetClass = AssetClass.US_EQUITY
    override val resubscribeEvery: Duration = Duration.ofSeconds(15)

    /** Yahoo symbol to the engine's symbol (US class shares use a dash at Yahoo: BRK.B is BRK-B). */
    private val aliases = ConcurrentHashMap<String, String>()

    override fun url() = endpoint

    private fun yahoo(symbol: String) = (if (symbol.endsWith(".TO")) symbol else symbol.replace('.', '-')).also { aliases[it] = symbol }

    private fun message(
        action: String,
        symbols: Set<String>,
    ): String {
        val o = mapper.createObjectNode()
        o.putArray(action).also { a -> symbols.sorted().forEach { a.add(yahoo(it)) } }
        return mapper.writeValueAsString(o)
    }

    override fun subscribe(symbols: Set<String>) = listOf(message("subscribe", symbols))

    override fun unsubscribe(symbols: Set<String>) = listOf(message("unsubscribe", symbols))

    override fun onOpened(ws: WebSocket) {
        markReady(ws)
    }

    override fun handle(
        ws: WebSocket,
        text: String,
    ) {
        val payload =
            if (text.trimStart().startsWith("{")) {
                mapper
                    .readTree(text)
                    .path("message")
                    .asText()
                    .takeIf { it.isNotEmpty() } ?: return
            } else {
                text.trim()
            }
        val p =
            YahooPricing.decode(
                java.util.Base64
                    .getDecoder()
                    .decode(payload),
            ) ?: return
        if (p.marketHours != YahooPricing.REGULAR_MARKET) return
        val symbol = aliases[p.id] ?: p.id
        val scale = maxOf(2, p.priceHint ?: 2)

        fun dec(v: Float?) = v?.takeIf { it > 0f }?.let { BigDecimal(it.toDouble()).setScale(scale, java.math.RoundingMode.HALF_UP) }
        val at = p.time ?: clock.instant()
        val delayed = Duration.between(at, clock.instant()) > Duration.ofMinutes(10)
        put(symbol, dec(p.price), dec(p.bid), dec(p.ask), at, if (delayed) FeedType.DELAYED else FeedType.REALTIME)
    }
}

/** The fields of Yahoo's `PricingData` protobuf the engine uses, read without a protobuf library. */
data class YahooPricing(
    val id: String,
    val price: Float?,
    val time: Instant?,
    val marketHours: Int,
    val bid: Float?,
    val ask: Float?,
    val priceHint: Int?,
) {
    companion object {
        const val REGULAR_MARKET = 1

        /** Decodes the message; null when it has no symbol or is malformed. */
        fun decode(bytes: ByteArray): YahooPricing? =
            runCatching {
                var i = 0
                var id: String? = null
                var price: Float? = null
                var time: Instant? = null
                var hours = 0
                var bid: Float? = null
                var ask: Float? = null
                var hint: Int? = null

                fun varint(): Long {
                    var shift = 0
                    var r = 0L
                    while (true) {
                        val b = bytes[i++].toInt() and 0xff
                        r = r or ((b and 0x7f).toLong() shl shift)
                        if (b and 0x80 == 0) return r
                        shift += 7
                        require(shift < 64) { "varint too long" }
                    }
                }

                fun zigzag(v: Long) = (v ushr 1) xor -(v and 1)

                fun fixed32(): Int {
                    val v = (bytes[i].toInt() and 0xff) or ((bytes[i + 1].toInt() and 0xff) shl 8) or ((bytes[i + 2].toInt() and 0xff) shl 16) or ((bytes[i + 3].toInt() and 0xff) shl 24)
                    i += 4
                    return v
                }
                while (i < bytes.size) {
                    val key = varint()
                    val field = (key ushr 3).toInt()
                    when ((key and 7).toInt()) {
                        0 -> {
                            val v = varint()
                            when (field) {
                                3 -> zigzag(v).let { t -> time = if (t < 100_000_000_000L) Instant.ofEpochSecond(t) else Instant.ofEpochMilli(t) }
                                7 -> hours = v.toInt()
                                27 -> hint = zigzag(v).toInt()
                            }
                        }
                        1 -> i += 8
                        2 -> {
                            val len = varint().toInt()
                            if (field == 1) id = String(bytes, i, len, Charsets.UTF_8)
                            i += len
                        }
                        5 -> {
                            val f = Float.fromBits(fixed32())
                            when (field) {
                                2 -> price = f
                                23 -> bid = f
                                25 -> ask = f
                            }
                        }
                        else -> error("Unsupported wire type")
                    }
                }
                id?.takeIf { it.isNotEmpty() }?.let { YahooPricing(it, price, time, hours, bid, ask, hint) }
            }.getOrNull()
    }
}

/**
 * The live price streams (D-056). In LIVE mode the engine keeps each stream subscribed to the
 * instruments it watches; quotes are taken from a stream while its ticks are fresh and polled
 * otherwise, so a dropped stream degrades to the earlier polling instead of stopping trading.
 */
class StreamHub(
    private val crypto: () -> QuoteStream?,
    private val stocks: () -> QuoteStream?,
) {
    fun streams(): List<QuoteStream> = listOfNotNull(crypto(), stocks())

    private fun forClass(a: AssetClass): QuoteStream? = if (a == AssetClass.CRYPTO) crypto() else stocks()

    /** [tsxSymbols] (TSX plan holdings, without `.TO`) stream in both modes; the rest only in LIVE mode. */
    fun sync(
        live: Boolean,
        cryptoSymbols: Set<String>,
        stockSymbols: Set<String>,
        tsxSymbols: Set<String> = emptySet(),
    ) {
        crypto()?.sync(if (live) cryptoSymbols else emptySet())
        val stocks = (if (live) stockSymbols.sorted().take(STOCK_SYMBOL_LIMIT) else emptyList()) + tsxSymbols.sorted().take(STOCK_SYMBOL_LIMIT).map { "$it.TO" }
        stocks()?.sync(stocks.toSet())
    }

    /** A fresh streamed price for a TSX listing (display only, D-056). */
    fun tsxTick(symbol: String): StreamTick? = stocks()?.latest("$symbol.TO", STOCK_MAX_AGE)

    /** The streamed quote for [instrument] when its latest tick is fresh enough to use. */
    fun quote(instrument: Instrument): QuoteData? =
        forClass(instrument.assetClass)
            ?.latest(instrument.symbol, if (instrument.assetClass == AssetClass.CRYPTO) CRYPTO_MAX_AGE else STOCK_MAX_AGE)
            ?.toQuote()

    fun statuses(): List<StreamStatus> = streams().map { it.status() }

    fun reset() = streams().forEach { it.reset() }

    companion object {
        /** At most this many stock and this many TSX symbols are streamed; the rest keep polling. */
        const val STOCK_SYMBOL_LIMIT = 60
        val CRYPTO_MAX_AGE: Duration = Duration.ofSeconds(30)
        val STOCK_MAX_AGE: Duration = Duration.ofMinutes(5)
    }
}
