package app.strategyforge.engine.market

import java.time.Instant
import java.time.LocalDate

/** DEMO runs everything on the synthetic replay data; LIVE uses real providers per asset class (D-027). */
enum class MarketMode { DEMO, LIVE }

/** The provider serving market data right now. [name] is stored with every quote and candle. */
data class ActiveProvider(
    val name: String,
    val provider: MarketDataProvider,
)

/**
 * Chooses market-data sources. LIVE mode routes crypto to [crypto] (free public exchange data)
 * and US equities to [equities] (keyed, delayed; absent until the owner adds a key). DEMO mode
 * routes everything to the replay provider and switches the market clock to replay time.
 */
class MarketSources(
    private val clock: MarketClock,
    private val replay: ReplayProvider,
    private val replayStart: () -> Instant,
    private val crypto: () -> MarketDataProvider?,
    private val equities: () -> MarketDataProvider?,
) {
    @Volatile var mode: MarketMode = MarketMode.DEMO
        private set

    fun use(mode: MarketMode) {
        this.mode = mode
        if (mode == MarketMode.DEMO) clock.useReplay(replayStart()) else clock.useLive()
    }

    /** Provider for one asset class; null when LIVE data for that class is not configured. */
    fun forClass(assetClass: AssetClass): MarketDataProvider? =
        when {
            mode == MarketMode.DEMO -> replay
            assetClass == AssetClass.CRYPTO -> crypto()
            else -> equities()
        }

    fun nameFor(assetClass: AssetClass): String = forClass(assetClass)?.type?.name ?: "UNCONFIGURED"

    fun active(): ActiveProvider = ActiveProvider(if (mode == MarketMode.DEMO) ProviderType.REPLAY.name else "LIVE", router)

    private val router =
        object : MarketDataProvider {
            override val type: ProviderType get() = if (mode == MarketMode.DEMO) ProviderType.REPLAY else ProviderType.COINBASE

            private fun p(a: AssetClass) = forClass(a)

            private fun <T> none(a: AssetClass): ProviderResult<T> = ProviderResult.Unsupported(if (a == AssetClass.CRYPTO) Capability.CRYPTO_QUOTES else Capability.EQUITY_QUOTES, "No live ${a.name.lowercase().replace('_', ' ')} data source is configured")

            override fun capabilities(): Map<Capability, CapabilityState> =
                Capability.entries.associateWith { c ->
                    listOfNotNull(crypto(), equities(), replay.takeIf { mode == MarketMode.DEMO }).mapNotNull { it.capabilities()[c] }.firstOrNull { it == CapabilityState.SUPPORTED }
                        ?: CapabilityState.UNSUPPORTED
                }

            override fun nativeTimeframes(assetClass: AssetClass): Set<Timeframe> = p(assetClass)?.nativeTimeframes(assetClass).orEmpty()

            override fun quote(
                symbol: String,
                assetClass: AssetClass,
                now: Instant,
            ) = p(assetClass)?.quote(symbol, assetClass, now) ?: none(assetClass)

            override fun candles(
                symbol: String,
                assetClass: AssetClass,
                timeframe: Timeframe,
                from: Instant,
                to: Instant,
                now: Instant,
            ) = p(assetClass)?.candles(symbol, assetClass, timeframe, from, to, now) ?: none(assetClass)

            override fun fxRate(
                base: String,
                quote: String,
                now: Instant,
            ): ProviderResult<FxData> = (if (mode == MarketMode.DEMO) replay else equities() ?: crypto())?.fxRate(base, quote, now) ?: ProviderResult.Unsupported(Capability.FX_RATES, "No FX source is configured")

            override fun corporateActions(
                symbol: String,
                assetClass: AssetClass,
                from: LocalDate,
                to: LocalDate,
            ) = p(assetClass)?.corporateActions(symbol, assetClass, from, to) ?: none(assetClass)

            override fun lookup(symbol: String): ProviderResult<InstrumentInfo> {
                val cls = if (symbol.endsWith("-USD")) AssetClass.CRYPTO else AssetClass.US_EQUITY
                return p(cls)?.lookup(symbol) ?: none(cls)
            }

            override fun diagnose(now: Instant): ProviderTestResult = p(AssetClass.CRYPTO)?.diagnose(now) ?: ProviderTestResult(TestStatus.UNSUPPORTED, "No crypto data source")
        }
}
