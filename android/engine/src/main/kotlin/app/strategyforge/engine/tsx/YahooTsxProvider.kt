package app.strategyforge.engine.tsx

import app.strategyforge.engine.market.CoinbaseProvider
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class TsxBar(
    val day: LocalDate,
    val close: Double,
    /** Close adjusted for splits and dividends (total return). */
    val adj: Double,
)

sealed interface TsxFetch {
    data class Ok(
        val bars: List<TsxBar>,
        val dividends: Map<LocalDate, Double>,
    ) : TsxFetch

    data class Failed(
        val reason: String,
        val notFound: Boolean = false,
    ) : TsxFetch
}

/** A current (intraday) price for a TSX listing (D-056); display only. */
data class TsxQuote(
    val price: Double,
    val at: Instant,
    val previousClose: Double?,
)

/** Daily TSX history with dividends (D-055), and current prices where the source has them (D-056). */
fun interface TsxHistoryProvider {
    fun history(
        symbol: String,
        from: LocalDate,
        to: LocalDate,
    ): TsxFetch

    /** The latest trade price during the session; null when unavailable. */
    fun latest(symbol: String): TsxQuote? = null
}

/**
 * Yahoo Finance's public chart endpoint for `.TO` listings (D-055): daily closes, adjusted closes
 * and dividends, with no key. Read-only market data; prices may be delayed and the endpoint is
 * unofficial, so failures are reported and retried at the next refresh.
 */
class YahooTsxProvider(
    private val http: OkHttpClient = CoinbaseProvider.defaultClient(),
    private val baseUrl: HttpUrl = "https://query1.finance.yahoo.com".toHttpUrl(),
) : TsxHistoryProvider {
    private val mapper = ObjectMapper()

    override fun history(
        symbol: String,
        from: LocalDate,
        to: LocalDate,
    ): TsxFetch {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegments("v8/finance/chart")
                .addPathSegment("$symbol.TO")
                .addQueryParameter("period1", from.atStartOfDay(TORONTO).toEpochSecond().toString())
                .addQueryParameter(
                    "period2",
                    to
                        .plusDays(1)
                        .atStartOfDay(TORONTO)
                        .toEpochSecond()
                        .toString(),
                ).addQueryParameter("interval", "1d")
                .addQueryParameter("events", "div,split")
                .build()
        val req =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) StrategyForge")
                .header("Accept", "application/json")
                .build()
        return try {
            http.newCall(req).execute().use { r ->
                if (r.code == 404) return TsxFetch.Failed("Yahoo has no data for $symbol.TO", notFound = true)
                if (!r.isSuccessful) return TsxFetch.Failed("Yahoo answered HTTP ${r.code} for $symbol.TO")
                parse(r.body?.string() ?: return TsxFetch.Failed("Empty response for $symbol.TO"))
            }
        } catch (e: IOException) {
            TsxFetch.Failed("Could not reach Yahoo Finance: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    override fun latest(symbol: String): TsxQuote? {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegments("v8/finance/chart")
                .addPathSegment("$symbol.TO")
                .addQueryParameter("range", "1d")
                .addQueryParameter("interval", "5m")
                .build()
        val req =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) StrategyForge")
                .header("Accept", "application/json")
                .build()
        return try {
            http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string()?.let { parseLatest(it) } else null }
        } catch (e: IOException) {
            null
        }
    }

    /** The chart's meta block: regularMarketPrice at regularMarketTime, with the previous close. */
    fun parseLatest(body: String): TsxQuote? {
        val meta =
            runCatching { mapper.readTree(body) }
                .getOrNull()
                ?.path("chart")
                ?.path("result")
                ?.path(0)
                ?.path("meta") ?: return null
        val price =
            meta
                .path("regularMarketPrice")
                .takeIf { it.isNumber }
                ?.asDouble()
                ?.takeIf { it > 0 } ?: return null
        val time = meta.path("regularMarketTime").takeIf { it.isNumber }?.asLong() ?: return null
        val prev = (meta.path("previousClose").takeIf { it.isNumber } ?: meta.path("chartPreviousClose").takeIf { it.isNumber })?.asDouble()
        return TsxQuote(price, Instant.ofEpochSecond(time), prev)
    }

    fun parse(body: String): TsxFetch {
        val root = runCatching { mapper.readTree(body) }.getOrElse { return TsxFetch.Failed("Unreadable response") }
        val result =
            root
                .path("chart")
                .path("result")
                .takeIf { it.isArray && it.size() > 0 }
                ?.get(0)
        if (result == null) {
            val err =
                root
                    .path("chart")
                    .path("error")
                    .path("description")
                    .asText("no result")
            return TsxFetch.Failed(err, notFound = err.contains("No data found", ignoreCase = true) || err.contains("delisted", ignoreCase = true))
        }
        val ts = result.path("timestamp")
        val close =
            result
                .path("indicators")
                .path("quote")
                .path(0)
                .path("close")
        val adj =
            result
                .path("indicators")
                .path("adjclose")
                .path(0)
                .path("adjclose")
        val bars = linkedMapOf<LocalDate, TsxBar>()
        for (i in 0 until ts.size()) {
            val c = close.path(i)
            if (!c.isNumber) continue
            val day = Instant.ofEpochSecond(ts.path(i).asLong()).atZone(TORONTO).toLocalDate()
            val a = adj.path(i).takeIf { it.isNumber }?.asDouble() ?: c.asDouble()
            bars[day] = TsxBar(day, c.asDouble(), a)
        }
        val divs = mutableMapOf<LocalDate, Double>()
        result.path("events").path("dividends").forEach { d ->
            val day = Instant.ofEpochSecond(d.path("date").asLong()).atZone(TORONTO).toLocalDate()
            divs[day] = (divs[day] ?: 0.0) + d.path("amount").asDouble()
        }
        return TsxFetch.Ok(bars.values.toList(), divs)
    }

    companion object {
        val TORONTO: ZoneId = ZoneId.of("America/Toronto")
    }
}
