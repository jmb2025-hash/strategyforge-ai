package app.strategyforge.engine.market

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Free, keyless perpetual-futures context from Kraken Futures' public API (D-044): open interest and
 * taker buy-minus-sell volume (delta) from the charts analytics service, and hourly funding rates.
 * Read-only market context for strategy rules; nothing here can place an order, and spot prices
 * still come from the spot provider.
 */
class KrakenFuturesProvider(
    private val clock: Clock,
    private val http: OkHttpClient = CoinbaseProvider.defaultClient(),
    private val baseUrl: HttpUrl = "https://futures.kraken.com".toHttpUrl(),
    requestsPerSecond: Int = 2,
) : DerivativesProvider {
    override val name = "KRAKEN_FUTURES"
    private val limiter = RateLimiter(requestsPerSecond, clock)
    private val mapper = ObjectMapper()

    /** Funding history is one long list per contract; it is fetched at most every few minutes. */
    private val fundingCache = mutableMapOf<String, Pair<Instant, List<DerivPoint>>>()

    override fun contractFor(symbol: String): String? {
        val base = symbol.substringBefore('-', "").uppercase()
        if (base.isEmpty() || !symbol.uppercase().endsWith("-USD") || !base.all { it.isLetterOrDigit() }) return null
        return "PF_${if (base == "BTC") "XBT" else base}USD"
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

    private fun get(url: HttpUrl): Call {
        if (!limiter.tryAcquire()) {
            // Paging needs a few calls in a row: wait briefly for the budget instead of failing.
            Thread.sleep(600)
            if (!limiter.tryAcquire()) return Call.Err(FailureKind.RATE_LIMITED, "Local request budget exhausted")
        }
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
                when {
                    resp.code == 429 -> Call.Err(FailureKind.RATE_LIMITED, "Kraken Futures rate limit reached")
                    resp.code == 404 -> Call.Err(FailureKind.NO_DATA, "Kraken Futures has no such contract or data")
                    resp.code in 401..403 -> Call.Err(FailureKind.AUTHENTICATION, "Kraken Futures refused the request (HTTP ${resp.code})")
                    !resp.isSuccessful -> Call.Err(FailureKind.HTTP_ERROR, "Kraken Futures returned HTTP ${resp.code}")
                    else ->
                        try {
                            Call.Ok(mapper.readTree(body))
                        } catch (e: JsonProcessingException) {
                            Call.Err(FailureKind.MALFORMED, "Kraken Futures response is not valid JSON")
                        }
                }
            }
        } catch (e: InterruptedIOException) {
            Call.Err(FailureKind.TIMEOUT, "Kraken Futures did not answer in time")
        } catch (e: IOException) {
            Call.Err(FailureKind.UNAVAILABLE, "Network error: ${e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ data

    override fun history(
        symbol: String,
        timeframe: Timeframe,
        from: Instant,
        to: Instant,
        now: Instant,
    ): ProviderResult<DerivativesData> {
        val contract = contractFor(symbol) ?: return ProviderResult.Unsupported(Capability.CRYPTO_QUOTES, "Kraken Futures has no perpetual for $symbol")
        val end = minOf(to, now)
        val oi =
            when (val r = analytics(contract, "open-interest", timeframe, from, end)) {
                is ProviderResult.Ok -> r.value
                is ProviderResult.Failed -> return r
                is ProviderResult.Unsupported -> return r
            }
        val delta =
            when (val r = analytics(contract, "aggressor-differential", timeframe, from, end)) {
                is ProviderResult.Ok -> r.value
                is ProviderResult.Failed -> return r
                is ProviderResult.Unsupported -> return r
            }
        val funding =
            when (val r = funding(contract, now)) {
                is ProviderResult.Ok -> r.value.filter { !it.ts.isBefore(from.minus(Duration.ofDays(1))) && !it.ts.isAfter(end) }
                is ProviderResult.Failed -> return r
                is ProviderResult.Unsupported -> return r
            }
        return ProviderResult.Ok(DerivativesData(oi.filter { !it.ts.isAfter(end) }, funding, delta.filter { !it.ts.isAfter(end) }, name))
    }

    private fun analytics(
        contract: String,
        kind: String,
        timeframe: Timeframe,
        from: Instant,
        to: Instant,
    ): ProviderResult<List<DerivPoint>> {
        val interval = intervalSeconds(timeframe)
        val out = mutableListOf<DerivPoint>()
        var since = from.epochSecond
        repeat(MAX_PAGES) {
            val url =
                baseUrl
                    .newBuilder()
                    .addPathSegments("api/charts/v1/analytics/$contract/$kind")
                    .addQueryParameter("since", since.toString())
                    .addQueryParameter("to", to.epochSecond.toString())
                    .addQueryParameter("interval", interval.toString())
                    .build()
            val page =
                when (val c = get(url)) {
                    is Call.Err -> return ProviderResult.Failed(c.kind, "$kind: ${c.detail}")
                    is Call.Ok ->
                        when (val p = parseAnalytics(c.json, kind)) {
                            is ProviderResult.Ok -> p.value
                            is ProviderResult.Failed -> return p
                            is ProviderResult.Unsupported -> return p
                        }
                }
            out += page.points
            val last = page.points.maxOfOrNull { it.ts.epochSecond }
            if (!page.more || last == null || last + interval > to.epochSecond || last < since) return ProviderResult.Ok(out.distinctBy { it.ts }.sortedBy { it.ts })
            since = last + interval
        }
        return ProviderResult.Ok(out.distinctBy { it.ts }.sortedBy { it.ts })
    }

    private fun funding(
        contract: String,
        now: Instant,
    ): ProviderResult<List<DerivPoint>> {
        synchronized(fundingCache) {
            fundingCache[contract]?.let { (at, v) -> if (Duration.between(at, now) < Duration.ofMinutes(10) && !now.isBefore(at)) return ProviderResult.Ok(v) }
        }
        val url =
            baseUrl
                .newBuilder()
                .addPathSegments("derivatives/api/v4/historicalfundingrates")
                .addQueryParameter("symbol", contract)
                .build()
        return when (val c = get(url)) {
            is Call.Err -> ProviderResult.Failed(c.kind, "funding: ${c.detail}")
            is Call.Ok ->
                parseFunding(c.json).also { r ->
                    if (r is ProviderResult.Ok) synchronized(fundingCache) { fundingCache[contract] = now to r.value }
                }
        }
    }

    override fun diagnose(now: Instant): ProviderTestResult {
        val url = baseUrl.newBuilder().addPathSegments("derivatives/api/v3/tickers").build()
        return when (val c = get(url)) {
            is Call.Err -> ProviderTestResult(TestStatus.FAILED, c.detail)
            is Call.Ok -> {
                val t = c.json["tickers"]?.firstOrNull { it["symbol"]?.asText() == "PF_XBTUSD" }
                if (t == null) {
                    ProviderTestResult(TestStatus.DEGRADED, "Kraken Futures answered but did not list PF_XBTUSD")
                } else {
                    val mark = t["markPrice"]?.asText()?.toDoubleOrNull()
                    val rate = t["fundingRate"]?.asText()?.toDoubleOrNull()
                    val pct = if (mark != null && rate != null && mark > 0) rate / mark * 100 else null
                    ProviderTestResult(
                        TestStatus.OK,
                        "BTC perpetual: open interest ${t["openInterest"]?.asText() ?: "?"} BTC" + (pct?.let { ", funding %.5f%% per hour".format(it) } ?: ""),
                    )
                }
            }
        }
    }

    data class AnalyticsPage(
        val points: List<DerivPoint>,
        val more: Boolean,
    )

    companion object {
        const val MAX_PAGES = 40

        fun intervalSeconds(tf: Timeframe): Long =
            when (tf) {
                Timeframe.M1 -> 60
                Timeframe.M5 -> 300
                Timeframe.M15 -> 900
                Timeframe.M30 -> 1800
                Timeframe.H1 -> 3600
                Timeframe.H4 -> 14400
                Timeframe.D1 -> 86400
            }

        private fun num(n: JsonNode?): Double? =
            when {
                n == null || n.isNull -> null
                n.isNumber -> n.asDouble()
                n.isTextual -> n.asText().toDoubleOrNull()
                else -> null
            }?.takeIf { it.isFinite() }

        /** Seconds or milliseconds since the epoch, or an ISO-8601 text. */
        private fun time(n: JsonNode?): Instant? =
            when {
                n == null || n.isNull -> null
                n.isNumber -> n.asLong().let { if (it > 100_000_000_000L) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it) }
                n.isTextual -> n.asText().let { s -> s.toLongOrNull()?.let { if (it > 100_000_000_000L) Instant.ofEpochMilli(it) else Instant.ofEpochSecond(it) } ?: runCatching { Instant.parse(s) }.getOrNull() }
                else -> null
            }

        /**
         * One value from an analytics data element: a number, the last number of an array (for
         * example [open, high, low, close]), or from an object its close, its buy minus sell, or its
         * single numeric field.
         */
        private fun valueOf(
            n: JsonNode?,
            kind: String,
        ): Double? {
            if (n == null || n.isNull) return null
            num(n)?.let { return it }
            if (n.isArray) return n.lastOrNull { num(it) != null }?.let(::num)
            if (!n.isObject) return null
            val fields = n.fields().asSequence().associate { it.key.lowercase() to it.value }
            fields["close"]?.let { return num(it) }
            val buy =
                fields.entries
                    .firstOrNull { "buy" in it.key }
                    ?.value
                    ?.let(::num)
            val sell =
                fields.entries
                    .firstOrNull { "sell" in it.key }
                    ?.value
                    ?.let(::num)
            if (buy != null && sell != null) return buy - sell
            fields.entries
                .firstOrNull { k -> listOf("diff", "delta", "value", if (kind == "open-interest") "interest" else "\u0000").any { it in k.key } }
                ?.value
                ?.let { return num(it) }
            return fields.values.mapNotNull(::num).singleOrNull()
        }

        /** Reads `{"result": {"timestamp": [...], "data": ..., "more": bool}, "errors": [...]}`. */
        fun parseAnalytics(
            json: JsonNode,
            kind: String,
        ): ProviderResult<AnalyticsPage> {
            json["errors"]?.takeIf { it.isArray && it.size() > 0 }?.let { e ->
                val msg = e.joinToString("; ") { it["message"]?.asText() ?: it.asText() }.take(200)
                return ProviderResult.Failed(if ("not found" in msg.lowercase() || "unknown" in msg.lowercase()) FailureKind.NO_DATA else FailureKind.HTTP_ERROR, "$kind: $msg")
            }
            val result = json["result"]?.takeIf { it.isObject } ?: return ProviderResult.Failed(FailureKind.MALFORMED, "$kind: response has no result")
            val times = result["timestamp"]?.takeIf { it.isArray }?.map(::time) ?: return ProviderResult.Failed(FailureKind.MALFORMED, "$kind: response has no timestamps")
            val data = result["data"] ?: return ProviderResult.Failed(FailureKind.MALFORMED, "$kind: response has no data")
            val values: List<Double?> =
                when {
                    data.isArray -> data.map { valueOf(it, kind) }
                    data.isObject -> {
                        val arrays =
                            data
                                .fields()
                                .asSequence()
                                .filter { it.value.isArray && it.value.size() == times.size }
                                .associate { it.key.lowercase() to it.value }

                        fun col(k: String) = arrays[k]?.map(::num)
                        val buyKey = arrays.keys.firstOrNull { "buy" in it }
                        val sellKey = arrays.keys.firstOrNull { "sell" in it }
                        when {
                            arrays.size == 1 -> arrays.values.single().map(::num)
                            "close" in arrays -> col("close")!!
                            buyKey != null && sellKey != null -> col(buyKey)!!.zip(col(sellKey)!!) { b, s -> if (b != null && s != null) b - s else null }
                            else ->
                                arrays.keys.firstOrNull { k -> listOf("diff", "delta", "value").any { it in k } }?.let { col(it) }
                                    ?: return ProviderResult.Failed(FailureKind.MALFORMED, "$kind: unrecognised data fields ${arrays.keys.sorted().joinToString()}")
                        }
                    }
                    else -> return ProviderResult.Failed(FailureKind.MALFORMED, "$kind: unrecognised data")
                }
            if (values.size != times.size) return ProviderResult.Failed(FailureKind.MALFORMED, "$kind: ${times.size} timestamps but ${values.size} values")
            val points = times.zip(values).mapNotNull { (t, v) -> if (t != null && v != null) DerivPoint(t, v) else null }
            return ProviderResult.Ok(AnalyticsPage(points, result["more"]?.asBoolean() ?: false))
        }

        /** Reads `{"rates": [{"timestamp": ISO, "fundingRate": abs, "relativeFundingRate": fraction per hour}]}` as percent per hour. */
        fun parseFunding(json: JsonNode): ProviderResult<List<DerivPoint>> {
            val rates = json["rates"]?.takeIf { it.isArray } ?: return ProviderResult.Failed(FailureKind.MALFORMED, "funding: response has no rates")
            val points =
                rates.mapNotNull { r ->
                    val t = time(r["timestamp"]) ?: return@mapNotNull null
                    val rel = num(r["relativeFundingRate"]) ?: return@mapNotNull null
                    DerivPoint(t, rel * 100)
                }
            if (points.isEmpty() && rates.size() > 0) return ProviderResult.Failed(FailureKind.MALFORMED, "funding: no readable rates")
            return ProviderResult.Ok(points.sortedBy { it.ts })
        }
    }
}
