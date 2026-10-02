package app.strategyforge.engine.market

import java.time.Duration
import java.time.Instant

/**
 * Futures context for crypto strategies (D-044). Demo mode derives it from the replay candles; live
 * mode reads Kraken Futures. Results are cached briefly so a strategy evaluated every bar does not
 * refetch the same history.
 */
class DerivativesService(
    private val sources: MarketSources,
    private val replay: DerivativesProvider,
    private val live: () -> DerivativesProvider?,
) {
    private data class Key(
        val provider: String,
        val symbol: String,
        val timeframe: Timeframe,
        val from: Instant,
        val to: Instant,
    )

    private val cache =
        object : LinkedHashMap<Key, Pair<Instant, DerivativesData>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Pair<Instant, DerivativesData>>?) = size > 32
        }

    fun provider(): DerivativesProvider? = if (sources.mode == MarketMode.DEMO) replay else live()

    fun history(
        symbol: String,
        timeframe: Timeframe,
        from: Instant,
        to: Instant,
        now: Instant,
    ): ProviderResult<DerivativesData> {
        val p = provider() ?: return ProviderResult.Unsupported(Capability.CRYPTO_QUOTES, "No futures data source is configured")
        val key = Key(p.name, symbol, timeframe, from, to)
        synchronized(cache) {
            cache[key]?.let { (at, v) -> if (!now.isBefore(at) && Duration.between(at, now) < TTL) return ProviderResult.Ok(v) }
        }
        val r = p.history(symbol, timeframe, from, to, now)
        if (r is ProviderResult.Ok) synchronized(cache) { cache[key] = now to r.value }
        return r
    }

    /** History covering [bars], lined up one value per bar. */
    fun forBars(
        symbol: String,
        timeframe: Timeframe,
        bars: List<CandleData>,
        now: Instant,
    ): ProviderResult<Pair<DerivativesData, AlignedDerivatives>> {
        if (bars.isEmpty()) return ProviderResult.Failed(FailureKind.NO_DATA, "No bars")
        val from = bars.first().openTime
        val to = bars.last().openTime.plus(timeframe.duration)
        return when (val r = history(symbol, timeframe, from, to, now)) {
            is ProviderResult.Ok -> ProviderResult.Ok(r.value to r.value.align(bars, timeframe.duration))
            is ProviderResult.Failed -> r
            is ProviderResult.Unsupported -> r
        }
    }

    /** Tests the live source (Kraken Futures) whatever the current mode, so it can be checked before going live. */
    fun diagnoseLive(now: Instant): Pair<String, ProviderTestResult> = live()?.let { it.name to it.diagnose(now) } ?: ("NONE" to ProviderTestResult(TestStatus.FAILED, "No live futures data source is available in this build"))

    companion object {
        val TTL: Duration = Duration.ofSeconds(50)
    }
}
