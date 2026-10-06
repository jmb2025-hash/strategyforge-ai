package app.strategyforge.engine.market

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

/** A symbol found by name or ticker in the public directory (D-065). Symbols use the app's spelling (BRK.B, BTC-USD). */
data class SymbolMatch(
    val symbol: String,
    val name: String,
    /** EQUITY, ETF or CRYPTOCURRENCY. */
    val type: String,
    val exchange: String?,
    val sector: String?,
    val industry: String?,
)

data class PricePoint(
    val at: Instant,
    val price: BigDecimal,
)

/** Display-only market facts for one symbol: price, day and 52-week ranges and a price history for [range]. */
data class SymbolSnapshot(
    val symbol: String,
    val name: String?,
    val currency: String?,
    val exchange: String?,
    val price: BigDecimal?,
    val previousClose: BigDecimal?,
    val dayHigh: BigDecimal?,
    val dayLow: BigDecimal?,
    val yearHigh: BigDecimal?,
    val yearLow: BigDecimal?,
    val volume: BigDecimal?,
    val marketTime: Instant?,
    val range: String,
    val points: List<PricePoint>,
)

/**
 * Looks up symbols and their public market facts for the order screen's search and the symbol
 * information screen (D-065). Display only: orders are still priced from the trading data sources.
 */
interface SymbolDirectory {
    fun search(query: String): List<SymbolMatch>

    fun snapshot(
        symbol: String,
        range: String,
    ): SymbolSnapshot?

    companion object {
        /** Chart ranges the information screen offers. */
        val RANGES = listOf("1D", "5D", "1M", "6M", "1Y", "5Y")
    }
}

/** Yahoo Finance's public search and chart endpoints (no key), the same source as the TSX plans (D-055). */
class YahooSymbolDirectory(
    private val http: OkHttpClient = CoinbaseProvider.defaultClient(),
    private val baseUrl: HttpUrl = "https://query1.finance.yahoo.com".toHttpUrl(),
) : SymbolDirectory {
    private val mapper = ObjectMapper()

    override fun search(query: String): List<SymbolMatch> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val url =
            baseUrl
                .newBuilder()
                .addPathSegments("v1/finance/search")
                .addQueryParameter("q", q)
                .addQueryParameter("quotesCount", "15")
                .addQueryParameter("newsCount", "0")
                .addQueryParameter("listsCount", "0")
                .build()
        val root = fetch(url) ?: return emptyList()
        return parseSearch(root)
    }

    override fun snapshot(
        symbol: String,
        range: String,
    ): SymbolSnapshot? {
        val (yRange, interval) = INTERVALS[range.uppercase()] ?: INTERVALS.getValue("1M")
        val url =
            baseUrl
                .newBuilder()
                .addPathSegments("v8/finance/chart")
                .addPathSegment(toYahoo(symbol))
                .addQueryParameter("range", yRange)
                .addQueryParameter("interval", interval)
                .build()
        val root = fetch(url) ?: return null
        return parseChart(symbol, range.uppercase(), root)
    }

    private fun fetch(url: HttpUrl): JsonNode? {
        val req =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) StrategyForge")
                .header("Accept", "application/json")
                .build()
        return try {
            http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string()?.let { mapper.readTree(it) } else null }
        } catch (_: IOException) {
            null
        } catch (_: com.fasterxml.jackson.core.JacksonException) {
            null
        }
    }

    companion object {
        /** Yahoo range and bar interval for each range the screen offers. */
        private val INTERVALS =
            mapOf(
                "1D" to ("1d" to "5m"),
                "5D" to ("5d" to "30m"),
                "1M" to ("1mo" to "1d"),
                "6M" to ("6mo" to "1d"),
                "1Y" to ("1y" to "1d"),
                "5Y" to ("5y" to "1wk"),
            )

        /** US exchanges Yahoo reports; listings elsewhere (Toronto, London, ...) are left out. */
        private val US_EXCHANGES = setOf("NMS", "NGM", "NCM", "NAS", "NYQ", "NYS", "ASE", "PCX", "BTS", "CXI", "PNK")

        /** Class shares are BRK.B in the app and BRK-B at Yahoo; crypto pairs are spelled the same. */
        fun toYahoo(symbol: String): String = if (symbol.endsWith("-USD")) symbol else symbol.replace('.', '-')

        fun fromYahoo(
            symbol: String,
            type: String,
        ): String = if (type == "CRYPTOCURRENCY") symbol else symbol.replace('-', '.')

        fun parseSearch(root: JsonNode): List<SymbolMatch> =
            root.path("quotes").mapNotNull { q ->
                val type = q.path("quoteType").asText("")
                val raw = q.path("symbol").asText("")
                val exchange = q.path("exchange").asText("")
                val keep =
                    when (type) {
                        "EQUITY", "ETF" -> exchange in US_EXCHANGES && Regex("^[A-Z][A-Z0-9]{0,6}(-[A-Z])?$").matches(raw)
                        "CRYPTOCURRENCY" -> raw.endsWith("-USD")
                        else -> false
                    }
                if (!keep) return@mapNotNull null
                SymbolMatch(
                    fromYahoo(raw, type),
                    q.text("longname") ?: q.text("shortname") ?: raw,
                    type,
                    q.text("exchDisp"),
                    q.text("sector"),
                    q.text("industry"),
                )
            }

        fun parseChart(
            symbol: String,
            range: String,
            root: JsonNode,
        ): SymbolSnapshot? {
            val r = root.path("chart").path("result").path(0)
            if (r.isMissingNode) return null
            val m = r.path("meta")
            val times = r.path("timestamp")
            val closes =
                r
                    .path("indicators")
                    .path("quote")
                    .path(0)
                    .path("close")
            val points =
                (0 until times.size()).mapNotNull { k ->
                    val c = closes.path(k)
                    if (!c.isNumber) null else PricePoint(Instant.ofEpochSecond(times.path(k).asLong()), BigDecimal(c.asText()))
                }
            val price = m.dec("regularMarketPrice")
            return SymbolSnapshot(
                symbol,
                m.text("longName") ?: m.text("shortName"),
                m.text("currency"),
                m.text("fullExchangeName") ?: m.text("exchangeName"),
                price,
                previousClose(price, m.dec("regularMarketChangePercent"), m.dec("previousClose") ?: m.dec("chartPreviousClose").takeIf { range == "1D" }),
                m.dec("regularMarketDayHigh"),
                m.dec("regularMarketDayLow"),
                m.dec("fiftyTwoWeekHigh"),
                m.dec("fiftyTwoWeekLow"),
                m.dec("regularMarketVolume"),
                m.path("regularMarketTime").takeIf { it.isNumber }?.let { Instant.ofEpochSecond(it.asLong()) },
                range,
                points,
            )
        }

        /** Yesterday's close: from today's change when Yahoo gives it (any range), else the one-day chart's previous close. */
        private fun previousClose(
            price: BigDecimal?,
            changePercent: BigDecimal?,
            fallback: BigDecimal?,
        ): BigDecimal? {
            if (price == null || changePercent == null) return fallback
            val factor = BigDecimal.ONE.add(changePercent.movePointLeft(2))
            return if (factor.signum() <= 0) fallback else price.divide(factor, 6, java.math.RoundingMode.HALF_EVEN)
        }

        private fun JsonNode.text(field: String): String? = path(field).takeIf { it.isTextual }?.asText()?.takeIf { it.isNotBlank() }

        private fun JsonNode.dec(field: String): BigDecimal? = path(field).takeIf { it.isNumber }?.let { BigDecimal(it.asText()) }
    }
}
