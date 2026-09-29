package app.strategyforge.engine.market

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Market-data sources available on the device (D-027). No provider can place orders. */
enum class ProviderType(
    val label: String,
    val credentialRequired: Boolean,
) {
    REPLAY("Replay (synthetic demo data)", false),
    COINBASE("Coinbase Exchange public market data", false),
    TWELVE_DATA("Twelve Data", true),
}

enum class TestStatus { OK, DEGRADED, FAILED, UNSUPPORTED }

data class CapabilityResult(
    val capability: String,
    val status: String,
    val detail: String?,
)

data class ProviderTestResult(
    val status: TestStatus,
    val detail: String,
    val capabilities: List<CapabilityResult> = emptyList(),
)

/** Contract implemented by every market-data adapter. All calls are point-in-time relative to [now]. */
interface MarketDataProvider {
    val type: ProviderType

    fun capabilities(): Map<Capability, CapabilityState>

    fun nativeTimeframes(assetClass: AssetClass): Set<Timeframe>

    fun quote(
        symbol: String,
        assetClass: AssetClass,
        now: Instant,
    ): ProviderResult<QuoteData>

    /** Closed bars with openTime in [from, to) whose close time is at or before [now]. */
    fun candles(
        symbol: String,
        assetClass: AssetClass,
        timeframe: Timeframe,
        from: Instant,
        to: Instant,
        now: Instant,
    ): ProviderResult<List<CandleData>>

    fun fxRate(
        base: String,
        quote: String,
        now: Instant,
    ): ProviderResult<FxData>

    fun corporateActions(
        symbol: String,
        assetClass: AssetClass,
        from: LocalDate,
        to: LocalDate,
    ): ProviderResult<List<CorporateActionData>>

    fun lookup(symbol: String): ProviderResult<InstrumentInfo>

    fun diagnose(now: Instant): ProviderTestResult
}

/** Close time of a bar: equities never extend past the session close (half-hour last bar, daily = session). */
fun barCloseTime(
    assetClass: AssetClass,
    timeframe: Timeframe,
    openTime: Instant,
): Instant {
    val nominal = openTime.plus(timeframe.duration)
    if (assetClass == AssetClass.CRYPTO) return nominal
    val session = MarketCalendar.currentOrPreviousSession(openTime) ?: return nominal
    if (timeframe == Timeframe.D1) return session.close
    return if (nominal.isAfter(session.close)) session.close else nominal
}

enum class DataStatus { VERIFIED, STALE, MISSING, OUT_OF_ORDER, MALFORMED, CLOCK_SKEW, UNSUPPORTED, PROVIDER_ERROR, INSUFFICIENT_HISTORY, GAPS }

data class StoredQuote(
    val instrumentId: UUID,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val last: BigDecimal,
    val bidSize: BigDecimal?,
    val askSize: BigDecimal?,
    val exchangeTs: Instant,
    val receivedAt: Instant,
    val provider: String,
    val feedType: FeedType,
) {
    val mid: BigDecimal get() = if (bid != null && ask != null) bid.add(ask).divide(BigDecimal(2)) else last
}

data class QuoteVerification(
    val status: DataStatus,
    val quote: StoredQuote?,
    val ageSeconds: Long?,
    val detail: String,
) {
    val verified: Boolean get() = status == DataStatus.VERIFIED
}

data class MarketSnapshot(
    val id: UUID,
    val instrumentId: UUID,
    val symbol: String,
    val marketTime: Instant,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val last: BigDecimal?,
    val quoteTs: Instant?,
    val quoteAgeSeconds: Long?,
    val feedType: String,
    val provider: String,
    val freshness: DataStatus,
)

data class CandleSeries(
    val instrumentId: UUID,
    val symbol: String,
    val timeframe: Timeframe,
    val sourceTimeframe: Timeframe?,
    val bars: List<CandleData>,
    val provider: String,
    val gaps: List<Instant>,
    val status: DataStatus,
    val detail: String,
)
