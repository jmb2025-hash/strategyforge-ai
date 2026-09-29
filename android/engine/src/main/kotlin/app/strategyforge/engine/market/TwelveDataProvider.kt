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
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * US stocks and ETFs from Twelve Data (https://twelvedata.com) with the owner's free key (D-032).
 * Ported from the Version 1 adapter. The free plan allows about 8 requests a minute and 800 a day,
 * so responses are cached (a quote for [quoteTtl], candles for [candleTtl]), nothing is requested
 * while the US market is closed once a quote is cached, and both budgets are enforced locally.
 * Quotes carry no bid/ask, so the simulator applies the configured fallback spread; whether the
 * feed is real-time or delayed is measured from quote timestamps, never assumed.
 */
class TwelveDataProvider(
    private val apiKey: () -> String?,
    private val clock: Clock,
    private val http: OkHttpClient = CoinbaseProvider.defaultClient(),
    private val baseUrl: HttpUrl = "https://api.twelvedata.com".toHttpUrl(),
    requestsPerMinute: Int = 8,
    private val requestsPerDay: Int = 780,
    private val quoteTtl: Duration = Duration.ofMinutes(5),
    private val candleTtl: Duration = Duration.ofMinutes(15),
) : MarketDataProvider {
    override val type = ProviderType.TWELVE_DATA
    private val limiter = RateLimiter(requestsPerMinute, clock, perMinute = true)
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val quotes = mutableMapOf<String, Pair<Instant, QuoteData>>()
    private val candleCache = mutableMapOf<Pair<String, Timeframe>, CachedBars>()
    private var feed: FeedType = FeedType.UNKNOWN
    private var day: LocalDate? = null
    private var usedToday = 0

    private class CachedBars(
        val fetchedAt: Instant,
        val from: Instant,
        val bars: List<CandleData>,
    )

    override fun capabilities(): Map<Capability, CapabilityState> =
        Capability.entries.associateWith {
            when (it) {
                Capability.EQUITY_QUOTES, Capability.EQUITY_INTRADAY_CANDLES, Capability.EQUITY_DAILY_CANDLES, Capability.INSTRUMENT_LOOKUP, Capability.CORPORATE_ACTIONS -> CapabilityState.UNVERIFIED
                else -> CapabilityState.UNSUPPORTED
            }
        }

    /** Quotes are polled every [quoteTtl]; a delayed free feed adds about 15 minutes. */
    override fun expectedLagSeconds(): Long = quoteTtl.seconds + if (feed == FeedType.DELAYED) DELAYED_FEED_SECONDS else 0

    override fun nativeTimeframes(assetClass: AssetClass) = if (assetClass == AssetClass.US_EQUITY) Timeframe.entries.toSet() else emptySet()

    private fun equitiesOnly(assetClass: AssetClass) = ProviderResult.Unsupported(Capability.CRYPTO_QUOTES, "Twelve Data is used for US stocks and ETFs only (${assetClass.name} requested)")

    private fun interval(tf: Timeframe) =
        when (tf) {
            Timeframe.M1 -> "1min"
            Timeframe.M5 -> "5min"
            Timeframe.M15 -> "15min"
            Timeframe.H1 -> "1h"
            Timeframe.H4 -> "4h"
            Timeframe.D1 -> "1day"
        }

    // ------------------------------------------------------------------ HTTP

    private sealed interface Call {
        data class Ok(
            val json: JsonNode,
        ) : Call

        data class Err(
            val kind: FailureKind,
            val detail: String,
        ) : Call
    }

    @Synchronized
    private fun budget(): String? {
        val today = LocalDate.now(clock.withZone(ZoneOffset.UTC))
        if (day != today) {
            day = today
            usedToday = 0
        }
        if (usedToday >= requestsPerDay) return "Daily request budget ($requestsPerDay) used; stock data resumes tomorrow (UTC)"
        if (!limiter.tryAcquire()) return "Per-minute request budget used; retrying shortly"
        usedToday++
        return null
    }

    private fun call(
        path: String,
        params: Map<String, String>,
    ): Call {
        val key = apiKey() ?: return Call.Err(FailureKind.AUTHENTICATION, "No Twelve Data key configured")
        budget()?.let { return Call.Err(FailureKind.RATE_LIMITED, it) }
        val url =
            baseUrl
                .newBuilder()
                .addPathSegments(path.trimStart('/'))
                .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
                .addQueryParameter("apikey", key)
                .build()
        val req =
            Request
                .Builder()
                .url(url)
                .header("Accept", "application/json")
                .header("User-Agent", CoinbaseProvider.USER_AGENT)
                .get()
                .build()
        return try {
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                val json =
                    try {
                        mapper.readTree(body)
                    } catch (e: JsonProcessingException) {
                        return Call.Err(FailureKind.MALFORMED, "Response is not valid JSON (HTTP ${resp.code})")
                    }
                if (json == null || json.isNull || json.isMissingNode) return Call.Err(FailureKind.MALFORMED, "Empty response (HTTP ${resp.code})")
                val code = json.get("code")?.takeIf { it.canConvertToInt() }?.asInt() ?: resp.code
                if (resp.code >= 400 || json.get("status")?.asText() == "error") {
                    val msg = strip(json.get("message")?.asText()?.take(200) ?: "HTTP ${resp.code}", key)
                    return Call.Err(
                        when (code) {
                            401, 403 -> if (msg.contains("plan", true) || msg.contains("upgrade", true)) FailureKind.NO_DATA else FailureKind.AUTHENTICATION
                            429 -> FailureKind.RATE_LIMITED
                            400, 404 -> FailureKind.NO_DATA
                            else -> FailureKind.HTTP_ERROR
                        },
                        msg,
                    )
                }
                Call.Ok(json)
            }
        } catch (e: InterruptedIOException) {
            Call.Err(FailureKind.TIMEOUT, "Twelve Data did not answer in time")
        } catch (e: IOException) {
            Call.Err(FailureKind.UNAVAILABLE, "Network error: ${e.javaClass.simpleName}")
        }
    }

    private fun dec(
        node: JsonNode?,
        field: String,
    ): BigDecimal? {
        val v = node?.get(field) ?: return null
        if (v.isNull) return null
        return v.asText().toBigDecimalOrNull()
    }

    // ------------------------------------------------------------------ data

    override fun quote(
        symbol: String,
        assetClass: AssetClass,
        now: Instant,
    ): ProviderResult<QuoteData> {
        if (assetClass != AssetClass.US_EQUITY) return equitiesOnly(assetClass)
        val cached = synchronized(quotes) { quotes[symbol] }
        val open = MarketCalendar.state(assetClass, now) == SessionState.OPEN
        if (cached != null && (!open || Duration.between(cached.first, now) < quoteTtl)) return ProviderResult.Ok(cached.second)
        return when (val r = call("/quote", mapOf("symbol" to symbol))) {
            is Call.Err -> ProviderResult.Failed(r.kind, r.detail)
            is Call.Ok -> {
                val last = dec(r.json, "close") ?: return ProviderResult.Failed(FailureKind.MALFORMED, "Quote has no numeric close")
                if (last.signum() <= 0) return ProviderResult.Failed(FailureKind.MALFORMED, "Non-positive price")
                val tsSec =
                    listOf("last_quote_at", "timestamp")
                        .firstNotNullOfOrNull { f ->
                            r.json
                                .get(f)
                                ?.takeIf { it.canConvertToLong() }
                                ?.asLong()
                        }
                        ?: return ProviderResult.Failed(FailureKind.MALFORMED, "Quote has no timestamp")
                val ts = Instant.ofEpochSecond(tsSec)
                val q = QuoteData(null, null, last, null, null, ts, classifyFeed(ts, now))
                synchronized(quotes) { quotes[symbol] = now to q }
                ProviderResult.Ok(q)
            }
        }
    }

    /** During an open session a quote over 10 minutes old means a delayed feed; under 60 seconds, real-time. */
    fun classifyFeed(
        ts: Instant,
        now: Instant,
    ): FeedType {
        if (MarketCalendar.state(AssetClass.US_EQUITY, now) != SessionState.OPEN) return feed
        val lag = Duration.between(ts, now)
        feed =
            when {
                lag > Duration.ofMinutes(10) -> FeedType.DELAYED
                lag < Duration.ofSeconds(60) -> FeedType.REALTIME
                else -> FeedType.UNKNOWN
            }
        return feed
    }

    override fun candles(
        symbol: String,
        assetClass: AssetClass,
        timeframe: Timeframe,
        from: Instant,
        to: Instant,
        now: Instant,
    ): ProviderResult<List<CandleData>> {
        if (assetClass != AssetClass.US_EQUITY) return equitiesOnly(assetClass)
        val cacheKey = symbol to timeframe
        val cached = synchronized(candleCache) { candleCache[cacheKey] }
        if (cached != null && !from.isBefore(cached.from) && Duration.between(cached.fetchedAt, now) < candleTtl) {
            return ProviderResult.Ok(cached.bars.filter { !it.openTime.isBefore(from) && it.openTime.isBefore(to) })
        }
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val r =
            call(
                "/time_series",
                mapOf(
                    "symbol" to symbol,
                    "interval" to interval(timeframe),
                    "timezone" to "UTC",
                    "order" to "ASC",
                    "start_date" to LocalDateTime.ofInstant(from, ZoneOffset.UTC).format(fmt),
                    "end_date" to LocalDateTime.ofInstant(minOf(to, now), ZoneOffset.UTC).format(fmt),
                    "outputsize" to "5000",
                ),
            )
        return when (r) {
            is Call.Err -> ProviderResult.Failed(r.kind, r.detail)
            is Call.Ok -> {
                val values = r.json.get("values") ?: return ProviderResult.Failed(FailureKind.MALFORMED, "time_series response has no values")
                if (!values.isArray) return ProviderResult.Failed(FailureKind.MALFORMED, "values is not an array")
                val out = sortedMapOf<Instant, CandleData>()
                for (v in values) {
                    val dt = v.get("datetime")?.asText() ?: return ProviderResult.Failed(FailureKind.MALFORMED, "Bar without datetime")
                    val openTime =
                        try {
                            if (dt.length == 10) {
                                val d = LocalDate.parse(dt)
                                MarketCalendar.session(d)?.open ?: d.atStartOfDay().toInstant(ZoneOffset.UTC)
                            } else {
                                LocalDateTime.parse(dt, fmt).toInstant(ZoneOffset.UTC)
                            }
                        } catch (e: DateTimeParseException) {
                            return ProviderResult.Failed(FailureKind.MALFORMED, "Unparseable datetime '$dt'")
                        }
                    val o = dec(v, "open")
                    val h = dec(v, "high")
                    val l = dec(v, "low")
                    val c = dec(v, "close")
                    if (o == null || h == null || l == null || c == null) return ProviderResult.Failed(FailureKind.MALFORMED, "Bar $dt has non-numeric OHLC")
                    val vol = dec(v, "volume") ?: BigDecimal.ZERO
                    if (!openTime.isBefore(from) && openTime.isBefore(to) && !barCloseTime(assetClass, timeframe, openTime).isAfter(now)) out[openTime] = CandleData(openTime, o, h, l, c, vol)
                }
                val bars = out.values.toList()
                synchronized(candleCache) { candleCache[cacheKey] = CachedBars(now, from, bars) }
                ProviderResult.Ok(bars)
            }
        }
    }

    override fun fxRate(
        base: String,
        quote: String,
        now: Instant,
    ): ProviderResult<FxData> = ProviderResult.Unsupported(Capability.FX_RATES, "FX rates come from Coinbase")

    override fun corporateActions(
        symbol: String,
        assetClass: AssetClass,
        from: LocalDate,
        to: LocalDate,
    ): ProviderResult<List<CorporateActionData>> {
        if (assetClass != AssetClass.US_EQUITY) return ProviderResult.Ok(emptyList())
        val range = mapOf("symbol" to symbol, "start_date" to from.toString(), "end_date" to to.toString())
        val out = mutableListOf<CorporateActionData>()
        when (val splits = call("/splits", range)) {
            is Call.Err -> return if (splits.kind == FailureKind.NO_DATA) ProviderResult.Unsupported(Capability.CORPORATE_ACTIONS, splits.detail) else ProviderResult.Failed(splits.kind, splits.detail)
            is Call.Ok ->
                splits.json.get("splits")?.forEach { s ->
                    val date = s.get("date")?.asText()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val fromF = dec(s, "from_factor")
                    val toF = dec(s, "to_factor")
                    if (date == null || fromF == null || toF == null || fromF.signum() <= 0 || toF.signum() <= 0) return ProviderResult.Failed(FailureKind.MALFORMED, "Split entry missing date or factors")
                    // "4-for-1" is from_factor 4, to_factor 1: four new shares for one old share.
                    out += CorporateActionData(CorporateActionType.SPLIT, date, date, fromF, toF, null)
                } ?: return ProviderResult.Failed(FailureKind.MALFORMED, "splits response has no splits array")
        }
        when (val dividends = call("/dividends", range)) {
            is Call.Err -> return if (dividends.kind == FailureKind.NO_DATA) ProviderResult.Unsupported(Capability.CORPORATE_ACTIONS, dividends.detail) else ProviderResult.Failed(dividends.kind, dividends.detail)
            is Call.Ok ->
                dividends.json.get("dividends")?.forEach { d ->
                    val date = d.get("ex_date")?.asText()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val amount = dec(d, "amount")
                    if (date == null || amount == null || amount.signum() <= 0) return ProviderResult.Failed(FailureKind.MALFORMED, "Dividend entry missing ex_date or amount")
                    out += CorporateActionData(CorporateActionType.CASH_DIVIDEND, date, null, null, null, amount)
                } ?: return ProviderResult.Failed(FailureKind.MALFORMED, "dividends response has no dividends array")
        }
        return ProviderResult.Ok(out.sortedBy { it.exDate })
    }

    override fun lookup(symbol: String): ProviderResult<InstrumentInfo> =
        when (val r = call("/symbol_search", mapOf("symbol" to symbol))) {
            is Call.Err -> ProviderResult.Failed(r.kind, r.detail)
            is Call.Ok -> {
                val match =
                    r.json.get("data")?.firstOrNull { d ->
                        d.get("symbol")?.asText() == symbol && d.get("country")?.asText() == "United States" &&
                            d.get("instrument_type")?.asText() in setOf("Common Stock", "ETF") && d.get("exchange")?.asText() in US_EXCHANGES
                    }
                if (match == null) {
                    ProviderResult.Failed(FailureKind.NO_DATA, "$symbol is not a supported US-listed common stock or ETF")
                } else {
                    ProviderResult.Ok(InstrumentInfo(symbol, match.get("instrument_name")?.asText() ?: symbol, match.get("exchange").asText(), AssetClass.US_EQUITY))
                }
            }
        }

    /** One quote request: checks the key, the plan and the feed without spending the daily budget. */
    override fun diagnose(now: Instant): ProviderTestResult {
        synchronized(quotes) { quotes.remove(DIAGNOSE_SYMBOL) }
        return when (val q = quote(DIAGNOSE_SYMBOL, AssetClass.US_EQUITY, now)) {
            is ProviderResult.Ok -> {
                val feedText =
                    when (q.value.feedType) {
                        FeedType.REALTIME -> "real-time"
                        FeedType.DELAYED -> "delayed"
                        else -> "feed timing is measured while the market is open"
                    }
                ProviderTestResult(TestStatus.OK, "$DIAGNOSE_SYMBOL ${q.value.last.toPlainString()} at ${q.value.exchangeTs} ($feedText); $usedToday of $requestsPerDay requests used today")
            }
            is ProviderResult.Failed -> ProviderTestResult(TestStatus.FAILED, "${q.kind}: ${q.detail}")
            is ProviderResult.Unsupported -> ProviderTestResult(TestStatus.UNSUPPORTED, q.detail)
        }
    }

    private fun strip(
        message: String,
        secret: String,
    ): String = if (secret.length >= 4) message.replace(secret, "[REDACTED]") else message

    companion object {
        const val DIAGNOSE_SYMBOL = "AAPL"
        const val DELAYED_FEED_SECONDS = 20L * 60
        val US_EXCHANGES = setOf("NASDAQ", "NYSE", "NYSE ARCA", "NYSE American", "Cboe BZX", "CBOE", "BATS")
    }
}
