package app.strategyforge.engine.market

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** Token bucket for a provider's request budget: [permits] per second, or per minute when [perMinute]. */
class RateLimiter(
    private val permits: Int,
    private val clock: Clock,
    perMinute: Boolean = false,
) {
    private val windowMs = if (perMinute) 60_000L else 1_000L
    private val capacity = permits.toLong() * windowMs
    private var tokens = capacity
    private var last = clock.millis()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock.millis()
        tokens = minOf(capacity, tokens + (now - last) * permits)
        last = now
        if (tokens < windowMs) return false
        tokens -= windowMs
        return true
    }
}

/**
 * Free, keyless real-time crypto data from the Coinbase Exchange public REST API (D-027):
 * ticker (best bid/ask and last trade) and OHLCV candles for the allowlisted USD pairs, plus the
 * public USD/CAD reference rate. Read-only market data; nothing here can place an order.
 *
 * Prices are parsed from the JSON text into BigDecimal and never pass through a double (NFR-002).
 * The best bid/ask is a live book snapshot, so a quote is timestamped with the exchange's
 * response time; the time of the last trade is kept for the last price when there is no book.
 */
class CoinbaseProvider(
    private val clock: Clock,
    private val http: OkHttpClient = defaultClient(),
    private val exchangeUrl: HttpUrl = "https://api.exchange.coinbase.com".toHttpUrl(),
    private val ratesUrl: HttpUrl = "https://api.coinbase.com".toHttpUrl(),
    requestsPerSecond: Int = 3,
) : MarketDataProvider {
    override val type = ProviderType.COINBASE
    private val limiter = RateLimiter(requestsPerSecond, clock)
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)

    override fun capabilities(): Map<Capability, CapabilityState> =
        Capability.entries.associateWith {
            when (it) {
                Capability.CRYPTO_QUOTES, Capability.BID_ASK, Capability.CRYPTO_CANDLES, Capability.FX_RATES, Capability.INSTRUMENT_LOOKUP -> CapabilityState.SUPPORTED
                else -> CapabilityState.UNSUPPORTED
            }
        }

    /** Coinbase serves 1m, 5m, 15m, 1h and 1d bars; 4h is aggregated from 1h by the data service. */
    override fun nativeTimeframes(assetClass: AssetClass): Set<Timeframe> = if (assetClass == AssetClass.CRYPTO) setOf(Timeframe.M1, Timeframe.M5, Timeframe.M15, Timeframe.H1, Timeframe.D1) else emptySet()

    private fun unsupported(assetClass: AssetClass) = ProviderResult.Unsupported(Capability.EQUITY_QUOTES, "Coinbase provides crypto data only (${assetClass.name} requested)")

    // ------------------------------------------------------------------ HTTP

    private sealed interface Call {
        data class Ok(
            val json: JsonNode,
            val serverTime: Instant?,
        ) : Call

        data class Err(
            val kind: FailureKind,
            val detail: String,
        ) : Call
    }

    private fun get(url: HttpUrl): Call {
        if (!limiter.tryAcquire()) return Call.Err(FailureKind.RATE_LIMITED, "Local request budget exhausted")
        val req =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.code == 429 -> Call.Err(FailureKind.RATE_LIMITED, "Coinbase rate limit reached")
                    resp.code == 404 -> Call.Err(FailureKind.NO_DATA, "Coinbase does not list this product")
                    resp.code in 401..403 -> Call.Err(FailureKind.AUTHENTICATION, "Coinbase refused the request (HTTP ${resp.code})")
                    !resp.isSuccessful -> Call.Err(FailureKind.HTTP_ERROR, "Coinbase returned HTTP ${resp.code}")
                    else ->
                        try {
                            Call.Ok(mapper.readTree(body), resp.header("Date")?.let(::parseHttpDate))
                        } catch (e: JsonProcessingException) {
                            Call.Err(FailureKind.MALFORMED, "Response is not valid JSON")
                        }
                }
            }
        } catch (e: InterruptedIOException) {
            Call.Err(FailureKind.TIMEOUT, "Coinbase did not answer in time")
        } catch (e: IOException) {
            Call.Err(FailureKind.UNAVAILABLE, "Network error: ${e.javaClass.simpleName}")
        }
    }

    private fun parseHttpDate(s: String): Instant? =
        try {
            ZonedDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
        } catch (e: DateTimeParseException) {
            null
        }

    private fun JsonNode.decimal(field: String): BigDecimal? = get(field)?.let(::decimalOf)

    /** Numbers arrive as JSON strings (ticker) or JSON numbers (candles); both are read exactly. */
    private fun decimalOf(n: JsonNode): BigDecimal? =
        when {
            n.isNumber -> n.decimalValue()
            n.isTextual -> n.asText().toBigDecimalOrNull()
            else -> null
        }

    // ------------------------------------------------------------------ data

    override fun quote(
        symbol: String,
        assetClass: AssetClass,
        now: Instant,
    ): ProviderResult<QuoteData> {
        if (assetClass != AssetClass.CRYPTO) return unsupported(assetClass)
        val url =
            exchangeUrl
                .newBuilder()
                .addPathSegments("products/$symbol/ticker")
                .build()
        return when (val c = get(url)) {
            is Call.Err -> ProviderResult.Failed(c.kind, c.detail)
            is Call.Ok -> {
                val j = c.json
                val last = j.decimal("price") ?: return ProviderResult.Failed(FailureKind.MALFORMED, "Ticker has no price")
                val tradeTime = j.get("time")?.asText()?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ProviderResult.Failed(FailureKind.MALFORMED, "Ticker has no time")
                val bid = j.decimal("bid")
                val ask = j.decimal("ask")
                val ts = if (bid != null && ask != null && c.serverTime != null) maxOf(c.serverTime, tradeTime) else tradeTime
                ProviderResult.Ok(QuoteData(bid, ask, last, null, null, ts, FeedType.REALTIME))
            }
        }
    }

    override fun candles(
        symbol: String,
        assetClass: AssetClass,
        timeframe: Timeframe,
        from: Instant,
        to: Instant,
        now: Instant,
    ): ProviderResult<List<CandleData>> {
        if (assetClass != AssetClass.CRYPTO) return unsupported(assetClass)
        if (timeframe !in nativeTimeframes(assetClass)) return ProviderResult.Unsupported(Capability.CRYPTO_CANDLES, "Coinbase has no ${timeframe.code} candles")
        val step = timeframe.duration
        // Align to bar boundaries; Coinbase returns at most 300 candles per request.
        var start = Instant.ofEpochSecond(from.epochSecond - Math.floorMod(from.epochSecond, step.seconds))
        val end = minOf(to, now)
        val out = sortedMapOf<Instant, CandleData>()
        var requests = 0
        while (start.isBefore(end)) {
            if (++requests > MAX_PAGES) return ProviderResult.Failed(FailureKind.RATE_LIMITED, "Requested range needs more than $MAX_PAGES pages")
            val chunkEnd = minOf(end, start.plus(step.multipliedBy(MAX_CANDLES.toLong())))
            val url =
                exchangeUrl
                    .newBuilder()
                    .addPathSegments("products/$symbol/candles")
                    .addQueryParameter("granularity", step.seconds.toString())
                    .addQueryParameter("start", start.toString())
                    .addQueryParameter("end", chunkEnd.toString())
                    .build()
            when (val c = get(url)) {
                is Call.Err -> return ProviderResult.Failed(c.kind, c.detail)
                is Call.Ok -> {
                    if (!c.json.isArray) return ProviderResult.Failed(FailureKind.MALFORMED, "Candles response is not an array")
                    for (row in c.json) {
                        // [time, low, high, open, close, volume]
                        if (!row.isArray || row.size() < 6) return ProviderResult.Failed(FailureKind.MALFORMED, "Candle row has ${row.size()} fields")
                        val values = (1..5).map { i -> decimalOf(row[i]) }
                        if (values.any { it == null } || !row[0].canConvertToLong()) return ProviderResult.Failed(FailureKind.MALFORMED, "Candle row has non-numeric values")
                        val open = Instant.ofEpochSecond(row[0].asLong())
                        val bar = CandleData(open, values[2]!!, values[1]!!, values[0]!!, values[3]!!, values[4]!!)
                        if (!open.isBefore(from) && open.isBefore(to) && !open.plus(step).isAfter(now)) out[open] = bar
                    }
                }
            }
            start = chunkEnd
        }
        return ProviderResult.Ok(out.values.toList())
    }

    override fun fxRate(
        base: String,
        quote: String,
        now: Instant,
    ): ProviderResult<FxData> {
        val url =
            ratesUrl
                .newBuilder()
                .addPathSegments("v2/exchange-rates")
                .addQueryParameter("currency", base)
                .build()
        return when (val c = get(url)) {
            is Call.Err -> ProviderResult.Failed(c.kind, c.detail)
            is Call.Ok -> {
                val rate =
                    c.json
                        .path("data")
                        .path("rates")
                        .decimal(quote) ?: return ProviderResult.Failed(FailureKind.NO_DATA, "No $base/$quote rate")
                ProviderResult.Ok(FxData(base, quote, rate, c.serverTime ?: now))
            }
        }
    }

    override fun corporateActions(
        symbol: String,
        assetClass: AssetClass,
        from: LocalDate,
        to: LocalDate,
    ): ProviderResult<List<CorporateActionData>> = if (assetClass == AssetClass.CRYPTO) ProviderResult.Ok(emptyList()) else ProviderResult.Unsupported(Capability.CORPORATE_ACTIONS, "Coinbase has no equity corporate actions")

    override fun lookup(symbol: String): ProviderResult<InstrumentInfo> {
        val url =
            exchangeUrl
                .newBuilder()
                .addPathSegments("products/$symbol")
                .build()
        return when (val c = get(url)) {
            is Call.Err -> ProviderResult.Failed(c.kind, c.detail)
            is Call.Ok -> {
                val j = c.json
                if (j.path("quote_currency").asText() != "USD" || j.path("status").asText() != "online") {
                    ProviderResult.Failed(FailureKind.NO_DATA, "$symbol is not an online USD product")
                } else {
                    ProviderResult.Ok(InstrumentInfo(j.path("id").asText(symbol), j.path("display_name").asText(symbol), "COINBASE", AssetClass.CRYPTO))
                }
            }
        }
    }

    override fun diagnose(now: Instant): ProviderTestResult =
        when (val q = quote("BTC-USD", AssetClass.CRYPTO, now)) {
            is ProviderResult.Ok -> {
                val age = Duration.between(q.value.exchangeTs, now).seconds
                ProviderTestResult(
                    if (age in -5..60) TestStatus.OK else TestStatus.DEGRADED,
                    "BTC-USD ${q.value.last.toPlainString()} at ${q.value.exchangeTs} (${if (age in -5..60) "real-time" else "timestamp ${age}s from device clock"})",
                    capabilities().map { (k, v) -> CapabilityResult(k.name, v.name, null) },
                )
            }
            is ProviderResult.Failed -> ProviderTestResult(TestStatus.FAILED, "${q.kind}: ${q.detail}")
            is ProviderResult.Unsupported -> ProviderTestResult(TestStatus.UNSUPPORTED, q.detail)
        }

    companion object {
        const val MAX_CANDLES = 300
        const val MAX_PAGES = 20
        const val USER_AGENT = "StrategyForge/1.0 (paper trading; market data only)"

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(Duration.ofSeconds(10))
                .readTimeout(Duration.ofSeconds(15))
                .callTimeout(Duration.ofSeconds(20))
                .followRedirects(false)
                .build()
    }
}
