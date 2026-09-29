package app.strategyforge.engine.market

import com.fasterxml.jackson.databind.ObjectMapper
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate

/** Loaded synthetic dataset (D-006). */
class ReplayDataset(
    val candles: Map<String, Map<Timeframe, List<CandleData>>>,
    val assetClasses: Map<String, AssetClass>,
    val spreadBps: Map<String, BigDecimal>,
    val corporateActions: Map<String, List<CorporateActionData>>,
    val fx: List<FxData>,
    val replayStart: Instant,
    val replayEnd: Instant,
)

/** Reads fixture files by relative path (classpath, assets or a directory). */
class ReplayFixtures(
    private val reader: (String) -> String,
) {
    val dataset: ReplayDataset by lazy { load() }

    private fun read(rel: String): String = reader(rel)

    private fun rows(text: String): List<List<String>> =
        text
            .lineSequence()
            .drop(1)
            .filter { it.isNotBlank() }
            .map { it.split(',') }
            .toList()

    private fun load(): ReplayDataset {
        val manifest = ObjectMapper().readTree(read("manifest.json"))
        check(manifest["synthetic"].asBoolean()) { "Replay manifest must declare synthetic data" }
        val instruments = rows(read("instruments.csv"))
        val classes = instruments.associate { it[0] to AssetClass.valueOf(it[1]) }
        val spreads = instruments.associate { it[0] to BigDecimal(it[2]) }
        val candles =
            classes.keys.associateWith { symbol ->
                listOf(Timeframe.M1, Timeframe.H1, Timeframe.D1).associateWith { tf ->
                    rows(read("candles/${tf.code}/$symbol.csv")).map { r ->
                        CandleData(Instant.parse(r[0]), BigDecimal(r[1]), BigDecimal(r[2]), BigDecimal(r[3]), BigDecimal(r[4]), BigDecimal(r[5]))
                    }
                }
            }
        val actions =
            rows(read("corporate_actions.csv")).groupBy({ it[0] }) { r ->
                CorporateActionData(
                    CorporateActionType.valueOf(r[1]),
                    LocalDate.parse(r[2]),
                    r[3].takeIf { it.isNotBlank() }?.let(LocalDate::parse),
                    r[4].takeIf { it.isNotBlank() }?.let(::BigDecimal),
                    r[5].takeIf { it.isNotBlank() }?.let(::BigDecimal),
                    r.getOrNull(6)?.takeIf { it.isNotBlank() }?.let(::BigDecimal),
                )
            }
        val fx = rows(read("fx/USD_CAD.csv")).map { FxData(it[1], it[2], BigDecimal(it[3]), Instant.parse(it[0])) }
        return ReplayDataset(candles, classes, spreads, actions, fx, Instant.parse(manifest["replayStartUtc"].asText()), Instant.parse(manifest["replayEndUtc"].asText()))
    }
}

/**
 * Deterministic replay adapter over the synthetic fixtures. Quotes at market time t are
 * derived from the minute bar open at t (known at t), never from later data.
 */
class ReplayProvider(
    private val fixtures: ReplayFixtures,
) : MarketDataProvider {
    override val type = ProviderType.REPLAY

    override fun capabilities(): Map<Capability, CapabilityState> = Capability.entries.associateWith { if (it == Capability.REALTIME_EQUITY) CapabilityState.UNSUPPORTED else CapabilityState.SUPPORTED }

    override fun nativeTimeframes(assetClass: AssetClass) = setOf(Timeframe.M1, Timeframe.H1, Timeframe.D1)

    private fun ds() = fixtures.dataset

    override fun quote(
        symbol: String,
        assetClass: AssetClass,
        now: Instant,
    ): ProviderResult<QuoteData> {
        val series = ds().candles[symbol] ?: return ProviderResult.Failed(FailureKind.NO_DATA, "No replay data for $symbol")
        // Freshest price knowable at `now` across timeframes: a bar's open is known at its open time,
        // its close only at its close time. Nothing after `now` is ever read.
        val best =
            listOf(Timeframe.M1, Timeframe.H1, Timeframe.D1)
                .mapNotNull { tf ->
                    val bars = series[tf].orEmpty()
                    val idx = lastIndexAtOrBefore(bars, now)
                    if (idx < 0) return@mapNotNull null
                    val bar = bars[idx]
                    val close = barCloseTime(assetClass, tf, bar.openTime)
                    if (close.isAfter(now)) bar.open to bar.openTime else bar.close to close
                }.maxByOrNull { it.second } ?: return ProviderResult.Failed(FailureKind.NO_DATA, "No replay data for $symbol at or before $now")
        val (price, ts) = best
        val half = price.multiply(ds().spreadBps[symbol] ?: BigDecimal.ONE).divide(BigDecimal(20000), 12, RoundingMode.HALF_EVEN)
        val bid = price.subtract(half).setScale(2, RoundingMode.DOWN).max(BigDecimal("0.01"))
        val ask = price.add(half).setScale(2, RoundingMode.UP)
        return ProviderResult.Ok(QuoteData(bid, ask, price, null, null, ts, FeedType.REPLAY_SYNTHETIC))
    }

    override fun candles(
        symbol: String,
        assetClass: AssetClass,
        timeframe: Timeframe,
        from: Instant,
        to: Instant,
        now: Instant,
    ): ProviderResult<List<CandleData>> {
        if (timeframe !in nativeTimeframes(assetClass)) return ProviderResult.Unsupported(Capability.EQUITY_INTRADAY_CANDLES, "Replay provides ${timeframe.code} by aggregation only")
        val bars = ds().candles[symbol]?.get(timeframe) ?: return ProviderResult.Failed(FailureKind.NO_DATA, "No replay data for $symbol")
        return ProviderResult.Ok(
            bars.filter { !it.openTime.isBefore(from) && it.openTime.isBefore(to) && !barCloseTime(assetClass, timeframe, it.openTime).isAfter(now) },
        )
    }

    override fun fxRate(
        base: String,
        quote: String,
        now: Instant,
    ): ProviderResult<FxData> {
        if (base != "USD" || quote != "CAD") return ProviderResult.Unsupported(Capability.FX_RATES, "Replay provides USD/CAD only")
        return ds().fx.lastOrNull { !it.asOf.isAfter(now) }?.let { ProviderResult.Ok(it) }
            ?: ProviderResult.Failed(FailureKind.NO_DATA, "No replay FX rate at or before $now")
    }

    override fun corporateActions(
        symbol: String,
        assetClass: AssetClass,
        from: LocalDate,
        to: LocalDate,
    ): ProviderResult<List<CorporateActionData>> {
        if (symbol !in ds().candles) return ProviderResult.Failed(FailureKind.NO_DATA, "No replay data for $symbol")
        return ProviderResult.Ok(ds().corporateActions[symbol].orEmpty().filter { !it.exDate.isBefore(from) && !it.exDate.isAfter(to) })
    }

    override fun lookup(symbol: String): ProviderResult<InstrumentInfo> {
        val cls = ds().assetClasses[symbol] ?: return ProviderResult.Failed(FailureKind.NO_DATA, "Symbol $symbol is not in the replay dataset")
        return ProviderResult.Ok(InstrumentInfo(symbol, "$symbol (replay)", if (cls == AssetClass.CRYPTO) "CRYPTO" else "REPLAY", cls))
    }

    override fun diagnose(now: Instant): ProviderTestResult {
        val d = ds()
        val caps = capabilities().map { (c, s) -> CapabilityResult(c.name, s.name, if (c == Capability.REALTIME_EQUITY) "Replay data is synthetic, not real-time" else "Synthetic replay dataset") }
        return ProviderTestResult(
            TestStatus.OK,
            "Replay dataset loaded: ${d.candles.size} symbols, window ${d.replayStart}..${d.replayEnd}; data is SYNTHETIC",
            caps,
        )
    }

    private fun lastIndexAtOrBefore(
        bars: List<CandleData>,
        t: Instant,
    ): Int {
        var lo = 0
        var hi = bars.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (!bars[mid].openTime.isAfter(t)) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
