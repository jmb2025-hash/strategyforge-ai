package app.strategyforge.engine.market

import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.common.Problems
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/** One search result for the order screen's symbol field (D-065). */
data class InstrumentHit(
    val symbol: String,
    val name: String,
    val assetClass: AssetClass,
    val exchange: String?,
    val sector: String?,
    val industry: String?,
    /** In the app's instrument list already; otherwise it is added (after checking with the stock data source) when picked. */
    val added: Boolean,
    val active: Boolean,
    /** The latest stored trading price, when there is one. */
    val lastPrice: BigDecimal?,
)

/** Everything the symbol information screen shows (D-065). */
data class InstrumentDetail(
    val hit: InstrumentHit,
    val snapshot: SymbolSnapshot?,
    /** The trading price paper orders fill from, and its source. */
    val quote: StoredQuote?,
)

/**
 * Symbol search and information for the order screen (D-065): instruments the app knows first, then
 * US stocks and ETFs from the public directory that can be added. Crypto stays limited to the
 * allowlisted pairs. Directory failures only shorten the results; they never fail a search.
 */
class InstrumentLookup(
    private val instruments: InstrumentService,
    private val market: MarketDataService,
    private val directory: () -> SymbolDirectory?,
) {
    /** Directory facts per symbol (sector, industry), kept for the session so screen refreshes cost nothing. */
    private val profiles = ConcurrentHashMap<String, SymbolMatch>()

    fun search(
        query: String,
        assetClass: AssetClass? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<InstrumentHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val upper = q.uppercase()
        val local =
            instruments
                .list(q, assetClass?.name, activeOnly = false)
                .sortedWith(compareBy({ rank(it, upper) }, { !it.active }, { it.symbol }))
                .map { hit(it, profiles[it.symbol]) }
        if (assetClass == AssetClass.CRYPTO || local.size >= limit) return local.take(limit)
        val known = local.map { it.symbol }.toSet()
        val remote =
            runCatching { directory()?.search(q).orEmpty() }
                .getOrDefault(emptyList())
                .onEach { profiles.putIfAbsent(it.symbol, it) }
                .filter { it.type != "CRYPTOCURRENCY" && it.symbol !in known && instruments.findBySymbol(it.symbol) == null }
                .map { InstrumentHit(it.symbol, it.name, AssetClass.US_EQUITY, it.exchange, it.sector, it.industry, added = false, active = true, lastPrice = null) }
        // Fill in sector and industry for known stocks the directory also returned.
        val enriched = local.map { h -> if (h.sector == null) profiles[h.symbol]?.let { p -> h.copy(sector = p.sector, industry = p.industry) } ?: h else h }
        return (enriched + remote).take(limit)
    }

    fun detail(
        symbolRaw: String,
        range: String,
        refresh: Boolean,
    ): InstrumentDetail {
        val symbol = InstrumentService.normalize(symbolRaw)
        val r = range.uppercase().takeIf { it in SymbolDirectory.RANGES } ?: throw Problems.badRequest("invalid-range", "range must be one of ${SymbolDirectory.RANGES.joinToString()}")
        val inst = instruments.findBySymbol(symbol)
        val dir = directory()
        val profile = profiles[symbol] ?: if (inst?.assetClass != AssetClass.CRYPTO) profileFor(dir, symbol) else null
        if (inst == null && profile == null) throw unknown(symbol)
        if (inst != null && refresh && inst.active) runCatching { market.refreshQuote(inst) }
        val snapshot = runCatching { dir?.snapshot(symbol, r) }.getOrNull()
        val hit =
            inst?.let { hit(it, profile) }
                ?: InstrumentHit(symbol, profile!!.name, AssetClass.US_EQUITY, profile.exchange, profile.sector, profile.industry, added = false, active = true, lastPrice = null)
        return InstrumentDetail(hit, snapshot, inst?.let { market.latestQuote(it.id) })
    }

    /**
     * Makes [symbolRaw] tradable: an instrument the app knows is returned as is; a US stock or ETF is
     * checked with the stock data source and added (FR-020).
     */
    fun ensure(symbolRaw: String): Instrument {
        val symbol = InstrumentService.normalize(symbolRaw)
        instruments.findBySymbol(symbol)?.let { return it }
        if (symbol.endsWith("-USD")) throw unknown(symbol)
        return try {
            instruments.add(symbol)
        } catch (e: EngineException) {
            throw when (e.code) {
                "lookup-unsupported" -> Problems.unprocessable("no-stock-data", "To trade $symbol, add a Twelve Data key in Settings → Market data first. It checks and prices US stocks.", mapOf("field" to "symbol"))
                "instrument-not-verified", "unsupported-asset", "invalid-symbol" -> unknown(symbol)
                else -> e
            }
        }
    }

    private fun profileFor(
        dir: SymbolDirectory?,
        symbol: String,
    ): SymbolMatch? =
        runCatching { dir?.search(symbol).orEmpty() }
            .getOrDefault(emptyList())
            .onEach { profiles.putIfAbsent(it.symbol, it) }
            .firstOrNull { it.symbol == symbol }

    private fun hit(
        i: Instrument,
        p: SymbolMatch?,
    ) = InstrumentHit(i.symbol, i.name, i.assetClass, i.exchange, p?.sector, p?.industry, added = true, active = i.active, lastPrice = market.latestQuote(i.id)?.last)

    /** Exact ticker, then tickers starting with the text (BTC finds BTC-USD), then names containing it. */
    private fun rank(
        i: Instrument,
        upper: String,
    ): Int =
        when {
            i.symbol == upper || i.symbol == "$upper-USD" -> 0
            i.symbol.startsWith(upper) -> 1
            i.name.uppercase().startsWith(upper) -> 2
            i.name.uppercase().contains(upper) -> 3
            else -> 4
        }

    companion object {
        const val DEFAULT_LIMIT = 12

        fun unknown(symbol: String) =
            EngineException(
                404,
                "unknown-symbol",
                "$symbol isn't a symbol you can trade here. Type a company or coin name and pick one from the suggestions.",
                mapOf("field" to "symbol"),
            )
    }
}
