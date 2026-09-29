package app.strategyforge.engine.market

import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class AssetClass { US_EQUITY, CRYPTO }

enum class Timeframe(
    val code: String,
    val duration: Duration,
) {
    M1("1m", Duration.ofMinutes(1)),
    M5("5m", Duration.ofMinutes(5)),
    M15("15m", Duration.ofMinutes(15)),
    H1("1h", Duration.ofHours(1)),
    H4("4h", Duration.ofHours(4)),
    D1("1d", Duration.ofDays(1)),
    ;

    companion object {
        fun of(code: String): Timeframe = entries.firstOrNull { it.code == code } ?: throw IllegalArgumentException("Unsupported timeframe '$code'")

        fun ofOrNull(code: String): Timeframe? = entries.firstOrNull { it.code == code }
    }
}

/** How current a price is, as detected from the provider (FR-023). Never assumed. */
enum class FeedType { REPLAY_SYNTHETIC, REALTIME, DELAYED, UNKNOWN }

enum class Capability {
    EQUITY_QUOTES,
    CRYPTO_QUOTES,
    BID_ASK,
    REALTIME_EQUITY,
    EQUITY_INTRADAY_CANDLES,
    EQUITY_DAILY_CANDLES,
    CRYPTO_CANDLES,
    FX_RATES,
    CORPORATE_ACTIONS,
    INSTRUMENT_LOOKUP,
}

enum class CapabilityState { SUPPORTED, UNSUPPORTED, UNVERIFIED, DEGRADED }

data class Instrument(
    val id: UUID,
    val symbol: String,
    val assetClass: AssetClass,
    val name: String,
    val exchange: String,
    val currency: String,
    val priceIncrement: BigDecimal,
    val quantityIncrement: BigDecimal,
    val minQuantity: BigDecimal,
    val shortable: Boolean,
    val active: Boolean,
    val source: String,
    val version: Long,
)

data class QuoteData(
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val last: BigDecimal,
    val bidSize: BigDecimal?,
    val askSize: BigDecimal?,
    val exchangeTs: Instant,
    val feedType: FeedType,
)

data class CandleData(
    val openTime: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
)

data class FxData(
    val base: String,
    val quote: String,
    val rate: BigDecimal,
    val asOf: Instant,
)

enum class CorporateActionType { SPLIT, CASH_DIVIDEND }

data class CorporateActionData(
    val type: CorporateActionType,
    val exDate: LocalDate,
    val payDate: LocalDate?,
    val ratioNew: BigDecimal?,
    val ratioOld: BigDecimal?,
    val cashAmount: BigDecimal?,
)

data class InstrumentInfo(
    val symbol: String,
    val name: String,
    val exchange: String,
    val assetClass: AssetClass,
)

enum class FailureKind { TIMEOUT, HTTP_ERROR, RATE_LIMITED, AUTHENTICATION, MALFORMED, NO_DATA, UNAVAILABLE }

/** Every provider call returns data, an explicit unsupported state, or a classified failure. */
sealed interface ProviderResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : ProviderResult<T>

    data class Unsupported(
        val capability: Capability,
        val detail: String,
    ) : ProviderResult<Nothing>

    data class Failed(
        val kind: FailureKind,
        val detail: String,
    ) : ProviderResult<Nothing>
}

fun <T> ProviderResult<T>.valueOrNull(): T? = (this as? ProviderResult.Ok<T>)?.value
