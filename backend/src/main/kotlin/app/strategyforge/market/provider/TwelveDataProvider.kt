package app.strategyforge.market.provider

import app.strategyforge.market.AssetClass
import app.strategyforge.market.CandleData
import app.strategyforge.market.Capability
import app.strategyforge.market.CapabilityState
import app.strategyforge.market.CorporateActionData
import app.strategyforge.market.CorporateActionType
import app.strategyforge.market.FailureKind
import app.strategyforge.market.FeedType
import app.strategyforge.market.FxData
import app.strategyforge.market.InstrumentInfo
import app.strategyforge.market.MarketCalendar
import app.strategyforge.market.ProviderResult
import app.strategyforge.market.QuoteData
import app.strategyforge.market.SessionState
import app.strategyforge.market.Timeframe
import app.strategyforge.providers.CapabilityResult
import app.strategyforge.providers.ProviderTestResult
import app.strategyforge.providers.ProviderType
import app.strategyforge.providers.ResolvedProvider
import app.strategyforge.providers.TestStatus
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.net.ProxySelector
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** Simple token bucket enforcing the configured provider request budget (WP2 "limits"). */
class RateLimiter(
    private val perMinute: Int,
    private val clock: Clock,
) {
    private var tokens = perMinute.toLong() * 1000
    private var last = clock.millis()

    @Synchronized
    fun tryAcquire(): Boolean {
        val now = clock.millis()
        tokens = minOf(perMinute.toLong() * 1000, tokens + (now - last) * perMinute / 60)
        last = now
        if (tokens < 1000) return false
        tokens -= 1000
        return true
    }
}

/**
 * Twelve Data REST adapter (https://twelvedata.com). Used only when the owner configures it;
 * core CI uses recorded fixtures through a local mock server (NFR-012). Capabilities are
 * established by probing the account at diagnostics time and stored; nothing is assumed.
 * Twelve Data quotes carry no bid/ask, so BID_ASK is reported UNSUPPORTED and the
 * simulator applies the configured fallback spread.
 */
class TwelveDataProvider(
    private val config: ResolvedProvider,
    private val clock: Clock,
    private val detected: Map<Capability, CapabilityState>,
) : MarketDataProvider {
    override val type = ProviderType.TWELVE_DATA
    private val baseUrl = config.string("baseUrl") ?: "https://api.twelvedata.com"
    private val timeout = Duration.ofSeconds(config.int("timeoutSeconds", 15).toLong())
    private val limiter = RateLimiter(config.int("requestsPerMinute", 8), clock)
    private val mapper = ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
    private val http =
        HttpClient
            .newBuilder()
            .connectTimeout(timeout)
            .proxy(ProxySelector.getDefault())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()
    private val feedCache = ConcurrentHashMap<AssetClass, FeedType>()

    override fun capabilities(): Map<Capability, CapabilityState> = Capability.entries.associateWith { detected[it] ?: CapabilityState.UNVERIFIED }

    override fun nativeTimeframes(assetClass: AssetClass) = Timeframe.entries.toSet()

    private fun providerSymbol(symbol: String) = if (symbol.endsWith("-USD")) symbol.removeSuffix("-USD") + "/USD" else symbol

    private fun interval(tf: Timeframe) =
        when (tf) {
            Timeframe.M1 -> "1min"
            Timeframe.M5 -> "5min"
            Timeframe.M15 -> "15min"
            Timeframe.H1 -> "1h"
            Timeframe.H4 -> "4h"
            Timeframe.D1 -> "1day"
        }

    sealed interface Call {
        data class Ok(
            val json: JsonNode,
        ) : Call

        data class Err(
            val kind: FailureKind,
            val detail: String,
        ) : Call
    }

    fun call(
        path: String,
        params: Map<String, String>,
    ): Call {
        val key = config.credential ?: return Call.Err(FailureKind.AUTHENTICATION, "No API key configured")
        if (!limiter.tryAcquire()) return Call.Err(FailureKind.RATE_LIMITED, "Local request budget exhausted")
        val query = (params + ("apikey" to key)).entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, Charsets.UTF_8)}" }
        val req =
            HttpRequest
                .newBuilder(URI.create("$baseUrl$path?$query"))
                .timeout(timeout)
                .GET()
                .header("Accept", "application/json")
                .build()
        val resp =
            try {
                http.send(req, HttpResponse.BodyHandlers.ofString())
            } catch (e: HttpTimeoutException) {
                return Call.Err(FailureKind.TIMEOUT, "Timed out after ${timeout.seconds}s")
            } catch (e: java.io.IOException) {
                return Call.Err(FailureKind.UNAVAILABLE, "Network error: ${e.javaClass.simpleName}")
            }
        val json =
            try {
                mapper.readTree(resp.body())
            } catch (e: com.fasterxml.jackson.core.JsonProcessingException) {
                return Call.Err(FailureKind.MALFORMED, "Response is not valid JSON (HTTP ${resp.statusCode()})")
            }
        val code = json?.get("code")?.asInt() ?: resp.statusCode()
        val isError = resp.statusCode() >= 400 || json?.get("status")?.asText() == "error"
        if (json == null || json.isNull) return Call.Err(FailureKind.MALFORMED, "Empty response")
        if (isError) {
            val msg = json.get("message")?.asText()?.take(200) ?: "HTTP ${resp.statusCode()}"
            return Call.Err(
                when (code) {
                    401, 403 -> if (msg.contains("plan", true) || msg.contains("upgrade", true)) FailureKind.NO_DATA else FailureKind.AUTHENTICATION
                    429 -> FailureKind.RATE_LIMITED
                    404, 400 -> FailureKind.NO_DATA
                    else -> FailureKind.HTTP_ERROR
                },
                SecretSafe.strip(msg, key),
            )
        }
        return Call.Ok(json)
    }

    private fun dec(
        node: JsonNode?,
        field: String,
    ): BigDecimal? {
        val v = node?.get(field) ?: return null
        if (v.isNull) return null
        return try {
            BigDecimal(v.asText())
        } catch (e: NumberFormatException) {
            null
        }
    }

    override fun quote(
        symbol: String,
        assetClass: AssetClass,
        now: Instant,
    ): ProviderResult<QuoteData> {
        val cap = if (assetClass == AssetClass.CRYPTO) Capability.CRYPTO_QUOTES else Capability.EQUITY_QUOTES
        if (detected[cap] == CapabilityState.UNSUPPORTED) return ProviderResult.Unsupported(cap, "Quotes for $assetClass are not available on this account")
        return when (val r = call("/quote", mapOf("symbol" to providerSymbol(symbol)))) {
            is Call.Err -> ProviderResult.Failed(r.kind, r.detail)
            is Call.Ok -> {
                val last = dec(r.json, "close") ?: return ProviderResult.Failed(FailureKind.MALFORMED, "Quote has no numeric close")
                val tsSec =
                    r.json
                        .get("last_quote_at")
                        ?.takeIf { it.canConvertToLong() }
                        ?.asLong() ?: r.json
                        .get("timestamp")
                        ?.takeIf { it.canConvertToLong() }
                        ?.asLong()
                        ?: return ProviderResult.Failed(FailureKind.MALFORMED, "Quote has no timestamp")
                if (last.signum() <= 0) return ProviderResult.Failed(FailureKind.MALFORMED, "Non-positive price")
                val ts = Instant.ofEpochSecond(tsSec)
                ProviderResult.Ok(QuoteData(null, null, last, null, null, ts, classifyFeed(assetClass, ts, now)))
            }
        }
    }

    /**
     * Real-time vs delayed is measured, not assumed: during an open session a quote older than
     * 10 minutes indicates a delayed feed; under 60 seconds indicates real-time; otherwise UNKNOWN.
     */
    fun classifyFeed(
        assetClass: AssetClass,
        ts: Instant,
        now: Instant,
    ): FeedType {
        if (detected[Capability.REALTIME_EQUITY] == CapabilityState.UNSUPPORTED && assetClass == AssetClass.US_EQUITY) return FeedType.DELAYED
        if (MarketCalendar.state(assetClass, now) != SessionState.OPEN) return feedCache[assetClass] ?: FeedType.UNKNOWN
        val lag = Duration.between(ts, now)
        val feed =
            when {
                lag > Duration.ofMinutes(10) -> FeedType.DELAYED
                lag < Duration.ofSeconds(60) -> FeedType.REALTIME
                else -> FeedType.UNKNOWN
            }
        feedCache[assetClass] = feed
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
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val r =
            call(
                "/time_series",
                mapOf(
                    "symbol" to providerSymbol(symbol),
                    "interval" to interval(timeframe),
                    "timezone" to "UTC",
                    "order" to "ASC",
                    "start_date" to LocalDateTime.ofInstant(from, ZoneOffset.UTC).format(fmt),
                    "end_date" to LocalDateTime.ofInstant(to, ZoneOffset.UTC).format(fmt),
                    "outputsize" to "5000",
                ),
            )
        return when (r) {
            is Call.Err -> ProviderResult.Failed(r.kind, r.detail)
            is Call.Ok -> {
                val values = r.json.get("values") ?: return ProviderResult.Failed(FailureKind.MALFORMED, "time_series response has no values")
                if (!values.isArray) return ProviderResult.Failed(FailureKind.MALFORMED, "values is not an array")
                val out = mutableListOf<CandleData>()
                for (v in values) {
                    val dt = v.get("datetime")?.asText() ?: return ProviderResult.Failed(FailureKind.MALFORMED, "bar without datetime")
                    val openTime =
                        try {
                            if (dt.length == 10) {
                                val d = LocalDate.parse(dt)
                                if (assetClass == AssetClass.US_EQUITY) MarketCalendar.session(d)?.open ?: d.atStartOfDay().toInstant(ZoneOffset.UTC) else d.atStartOfDay().toInstant(ZoneOffset.UTC)
                            } else {
                                LocalDateTime.parse(dt, fmt).toInstant(ZoneOffset.UTC)
                            }
                        } catch (e: java.time.format.DateTimeParseException) {
                            return ProviderResult.Failed(FailureKind.MALFORMED, "Unparseable datetime '$dt'")
                        }
                    val o = dec(v, "open")
                    val h = dec(v, "high")
                    val l = dec(v, "low")
                    val c = dec(v, "close")
                    if (o == null || h == null || l == null || c == null) return ProviderResult.Failed(FailureKind.MALFORMED, "Bar $dt has non-numeric OHLC")
                    val vol = dec(v, "volume") ?: BigDecimal.ZERO
                    if (!barCloseTime(assetClass, timeframe, openTime).isAfter(now)) out += CandleData(openTime, o, h, l, c, vol)
                }
                ProviderResult.Ok(out)
            }
        }
    }

    override fun fxRate(
        base: String,
        quote: String,
        now: Instant,
    ): ProviderResult<FxData> =
        when (val r = call("/exchange_rate", mapOf("symbol" to "$base/$quote"))) {
            is Call.Err -> ProviderResult.Failed(r.kind, r.detail)
            is Call.Ok -> {
                val rate = dec(r.json, "rate")
                val ts =
                    r.json
                        .get("timestamp")
                        ?.takeIf { it.canConvertToLong() }
                        ?.asLong()
                if (rate == null || ts == null || rate.signum() <= 0) ProviderResult.Failed(FailureKind.MALFORMED, "exchange_rate response missing rate/timestamp") else ProviderResult.Ok(FxData(base, quote, rate, Instant.ofEpochSecond(ts)))
            }
        }

    override fun corporateActions(
        symbol: String,
        assetClass: AssetClass,
        from: LocalDate,
        to: LocalDate,
    ): ProviderResult<List<CorporateActionData>> {
        if (assetClass == AssetClass.CRYPTO) return ProviderResult.Ok(emptyList())
        if (detected[Capability.CORPORATE_ACTIONS] == CapabilityState.UNSUPPORTED) return ProviderResult.Unsupported(Capability.CORPORATE_ACTIONS, "Corporate actions are not available on this account")
        val range = mapOf("symbol" to symbol, "start_date" to from.toString(), "end_date" to to.toString())
        val splits = call("/splits", range)
        val dividends = call("/dividends", range)
        val out = mutableListOf<CorporateActionData>()
        when (splits) {
            is Call.Err -> return if (splits.kind == FailureKind.NO_DATA) ProviderResult.Unsupported(Capability.CORPORATE_ACTIONS, splits.detail) else ProviderResult.Failed(splits.kind, splits.detail)
            is Call.Ok ->
                splits.json.get("splits")?.forEach { s ->
                    val date = s.get("date")?.asText()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    val fromF = dec(s, "from_factor")
                    val toF = dec(s, "to_factor")
                    if (date == null || fromF == null || toF == null || fromF.signum() <= 0 || toF.signum() <= 0) return ProviderResult.Failed(FailureKind.MALFORMED, "Split entry missing date or factors")
                    // "4-for-1" => from_factor 4, to_factor 1: 4 new shares for 1 old share.
                    out += CorporateActionData(CorporateActionType.SPLIT, date, date, fromF, toF, null)
                } ?: return ProviderResult.Failed(FailureKind.MALFORMED, "splits response has no splits array")
        }
        when (dividends) {
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
                if (match == null) ProviderResult.Failed(FailureKind.NO_DATA, "$symbol is not a supported US-listed common stock or ETF") else ProviderResult.Ok(InstrumentInfo(symbol, match.get("instrument_name")?.asText() ?: symbol, match.get("exchange").asText(), AssetClass.US_EQUITY))
            }
        }

    override fun diagnose(now: Instant): ProviderTestResult {
        val caps = mutableListOf<CapabilityResult>()

        fun probe(
            cap: Capability,
            call: () -> ProviderResult<*>,
        ) {
            val r = call()
            caps +=
                when (r) {
                    is ProviderResult.Ok -> CapabilityResult(cap.name, CapabilityState.SUPPORTED.name, "Probe succeeded")
                    is ProviderResult.Unsupported -> CapabilityResult(cap.name, CapabilityState.UNSUPPORTED.name, r.detail)
                    is ProviderResult.Failed ->
                        CapabilityResult(
                            cap.name,
                            if (r.kind == FailureKind.NO_DATA) CapabilityState.UNSUPPORTED.name else CapabilityState.UNVERIFIED.name,
                            "${r.kind}: ${r.detail}",
                        )
                }
        }
        val eq = quote("AAPL", AssetClass.US_EQUITY, now)
        probe(Capability.EQUITY_QUOTES) { eq }
        probe(Capability.CRYPTO_QUOTES) { quote("BTC-USD", AssetClass.CRYPTO, now) }
        probe(Capability.EQUITY_INTRADAY_CANDLES) { candles("AAPL", AssetClass.US_EQUITY, Timeframe.H1, now.minus(Duration.ofDays(7)), now, now) }
        probe(Capability.EQUITY_DAILY_CANDLES) { candles("AAPL", AssetClass.US_EQUITY, Timeframe.D1, now.minus(Duration.ofDays(30)), now, now) }
        probe(Capability.CRYPTO_CANDLES) { candles("BTC-USD", AssetClass.CRYPTO, Timeframe.H1, now.minus(Duration.ofDays(2)), now, now) }
        probe(Capability.FX_RATES) { fxRate("USD", "CAD", now) }
        probe(Capability.CORPORATE_ACTIONS) { corporateActions("AAPL", AssetClass.US_EQUITY, LocalDate.now(clock).minusYears(1), LocalDate.now(clock)) }
        probe(Capability.INSTRUMENT_LOOKUP) { lookup("AAPL") }
        caps += CapabilityResult(Capability.BID_ASK.name, CapabilityState.UNSUPPORTED.name, "Quote endpoint does not provide bid/ask; fallback spread applies")
        val realtime =
            when (val q = eq) {
                is ProviderResult.Ok ->
                    when (q.value.feedType) {
                        FeedType.REALTIME -> CapabilityResult(Capability.REALTIME_EQUITY.name, CapabilityState.SUPPORTED.name, "Quote lag under 60s during an open session")
                        FeedType.DELAYED -> CapabilityResult(Capability.REALTIME_EQUITY.name, CapabilityState.UNSUPPORTED.name, "Quotes are delayed (lag over 10 minutes during an open session)")
                        else -> CapabilityResult(Capability.REALTIME_EQUITY.name, CapabilityState.UNVERIFIED.name, "Cannot measure quote lag while the market is closed")
                    }
                else -> CapabilityResult(Capability.REALTIME_EQUITY.name, CapabilityState.UNVERIFIED.name, "Equity quote probe failed")
            }
        caps += realtime
        val supported = caps.count { it.status == CapabilityState.SUPPORTED.name }
        val status =
            when {
                caps.first { it.capability == Capability.EQUITY_QUOTES.name }.status != CapabilityState.SUPPORTED.name -> TestStatus.FAILED
                supported < caps.size -> TestStatus.DEGRADED
                else -> TestStatus.OK
            }
        return ProviderTestResult(status, "$supported of ${caps.size} capabilities verified", caps)
    }

    companion object {
        val US_EXCHANGES = setOf("NASDAQ", "NYSE", "NYSE ARCA", "NYSE American", "Cboe BZX", "CBOE", "BATS")
    }
}

/** Ensures provider error messages never echo credentials. */
object SecretSafe {
    fun strip(
        message: String,
        secret: String,
    ): String = if (secret.length >= 4) message.replace(secret, "[REDACTED]") else message
}
