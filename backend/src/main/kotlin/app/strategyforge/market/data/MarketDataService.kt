package app.strategyforge.market.data

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.decimalOrNull
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.market.CandleData
import app.strategyforge.market.FailureKind
import app.strategyforge.market.FeedType
import app.strategyforge.market.FxData
import app.strategyforge.market.Instrument
import app.strategyforge.market.MarketClock
import app.strategyforge.market.ProviderResult
import app.strategyforge.market.QuoteData
import app.strategyforge.market.Timeframe
import app.strategyforge.market.provider.MarketProviderRegistry
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

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

/**
 * Market data ingestion, storage and verification (FR-022, FR-024). Every quote and candle
 * keeps its provider and exchange timestamp. Malformed, out-of-order and future-dated data is
 * rejected and flagged; evaluation consults [verifyQuote]/[verifyCandles] and fails closed.
 */
@Service
class MarketDataService(
    private val jdbc: JdbcClient,
    private val registry: MarketProviderRegistry,
    private val marketClock: MarketClock,
    private val wall: Clock,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
) {
    // ---------------------------------------------------------------- quotes

    @Transactional
    fun refreshQuote(instrument: Instrument): QuoteVerification {
        val active = registry.active()
        val now = marketClock.now()
        return when (val r = active.provider.quote(instrument.symbol, instrument.assetClass, now)) {
            is ProviderResult.Ok -> storeQuote(instrument, r.value, active.type.name, now)
            is ProviderResult.Unsupported -> {
                flag(instrument.id, QUOTE_KEY, DataStatus.UNSUPPORTED, r.detail)
                QuoteVerification(DataStatus.UNSUPPORTED, null, null, r.detail)
            }
            is ProviderResult.Failed -> {
                val status = if (r.kind == FailureKind.MALFORMED) DataStatus.MALFORMED else DataStatus.PROVIDER_ERROR
                flag(instrument.id, QUOTE_KEY, status, "${r.kind}: ${r.detail}")
                QuoteVerification(status, latestQuote(instrument.id), null, "${r.kind}: ${r.detail}")
            }
        }
    }

    /** Validates and stores a quote; rejects malformed, future-dated (clock skew) and out-of-order quotes. */
    @Transactional
    fun storeQuote(
        instrument: Instrument,
        q: QuoteData,
        provider: String,
        now: Instant,
    ): QuoteVerification {
        val problem =
            when {
                q.last.signum() <= 0 -> DataStatus.MALFORMED to "Non-positive last price"
                (q.bid != null && q.bid.signum() <= 0) || (q.ask != null && q.ask.signum() <= 0) -> DataStatus.MALFORMED to "Non-positive bid/ask"
                q.bid != null && q.ask != null && q.bid > q.ask -> DataStatus.MALFORMED to "Crossed quote: bid ${q.bid} > ask ${q.ask}"
                q.exchangeTs.isAfter(now.plus(SKEW_TOLERANCE)) -> DataStatus.CLOCK_SKEW to "Quote timestamp ${q.exchangeTs} is ahead of market time $now"
                else -> null
            }
        if (problem != null) {
            flag(instrument.id, QUOTE_KEY, problem.first, problem.second)
            audit.record(AuditCategory.MARKET_DATA, "QUOTE_REJECTED", AuditOutcome.BLOCKED, "Instrument", instrument.id, mapOf("symbol" to instrument.symbol, "reason" to problem.second))
            return QuoteVerification(problem.first, latestQuote(instrument.id), null, problem.second)
        }
        val existing = latestQuote(instrument.id)
        if (existing != null && existing.provider == provider && q.exchangeTs.isBefore(existing.exchangeTs)) {
            val detail = "Out-of-order quote ${q.exchangeTs} older than stored ${existing.exchangeTs}"
            flag(instrument.id, QUOTE_KEY, DataStatus.OUT_OF_ORDER, detail)
            audit.record(AuditCategory.MARKET_DATA, "QUOTE_REJECTED", AuditOutcome.BLOCKED, "Instrument", instrument.id, mapOf("symbol" to instrument.symbol, "reason" to detail))
            return QuoteVerification(DataStatus.OUT_OF_ORDER, existing, null, detail)
        }
        jdbc
            .sql(
                """
                insert into latest_quotes(instrument_id, bid, ask, last, bid_size, ask_size, exchange_ts, received_at, provider, feed_type)
                values (:i, :bid, :ask, :last, :bs, :as, :ts, :recv, :p, :f)
                on conflict (instrument_id) do update set bid = excluded.bid, ask = excluded.ask, last = excluded.last, bid_size = excluded.bid_size,
                  ask_size = excluded.ask_size, exchange_ts = excluded.exchange_ts, received_at = excluded.received_at, provider = excluded.provider,
                  feed_type = excluded.feed_type
                """.trimIndent(),
            ).param("i", instrument.id)
            .param("bid", q.bid)
            .param("ask", q.ask)
            .param("last", q.last)
            .param("bs", q.bidSize)
            .param("as", q.askSize)
            .param("ts", ts(q.exchangeTs))
            .param("recv", ts(wall.instant()))
            .param("p", provider)
            .param("f", q.feedType.name)
            .update()
        flag(instrument.id, QUOTE_KEY, null, null)
        return verifyQuote(instrument, Long.MAX_VALUE, now)
    }

    fun latestQuote(instrumentId: UUID): StoredQuote? =
        jdbc
            .sql("select * from latest_quotes where instrument_id = :i")
            .param("i", instrumentId)
            .query { rs, _ ->
                StoredQuote(
                    rs.uuid("instrument_id"),
                    rs.decimalOrNull("bid"),
                    rs.decimalOrNull("ask"),
                    rs.getBigDecimal("last"),
                    rs.decimalOrNull("bid_size"),
                    rs.decimalOrNull("ask_size"),
                    rs.instant("exchange_ts"),
                    rs.instant("received_at"),
                    rs.getString("provider"),
                    FeedType.valueOf(rs.getString("feed_type")),
                )
            }.optional()
            .orElse(null)

    /** Fail-closed quote verification used by risk, execution and evaluation (FR-024). */
    fun verifyQuote(
        instrument: Instrument,
        maxAgeSeconds: Long,
        at: Instant? = null,
    ): QuoteVerification {
        val now = at ?: marketClock.now()
        val q = latestQuote(instrument.id)
        flagFor(instrument.id, QUOTE_KEY)?.let { (status, detail) -> return QuoteVerification(status, q, q?.let { age(it, now) }, detail) }
        if (q == null) return QuoteVerification(DataStatus.MISSING, null, null, "No quote has been received for ${instrument.symbol}")
        if (q.provider != registry.active().type.name) return QuoteVerification(DataStatus.STALE, q, age(q, now), "Quote came from inactive provider ${q.provider}")
        if (q.exchangeTs.isAfter(now.plus(SKEW_TOLERANCE))) return QuoteVerification(DataStatus.CLOCK_SKEW, q, null, "Quote is dated in the future")
        val ageSec = age(q, now)
        if (ageSec > maxAgeSeconds) return QuoteVerification(DataStatus.STALE, q, ageSec, "Quote age ${ageSec}s exceeds ${maxAgeSeconds}s")
        return QuoteVerification(DataStatus.VERIFIED, q, ageSec, "Verified")
    }

    private fun age(
        q: StoredQuote,
        now: Instant,
    ) = Duration.between(q.exchangeTs, now).seconds.coerceAtLeast(0)

    /** Immutable snapshot referenced by signals, orders and risk decisions (FR-061, FR-085). */
    @Transactional
    fun captureSnapshot(
        instrument: Instrument,
        maxAgeSeconds: Long,
    ): Pair<MarketSnapshot, QuoteVerification> {
        val now = marketClock.now()
        val v = verifyQuote(instrument, maxAgeSeconds, now)
        val q = v.quote
        val id = UUID.randomUUID()
        val provider = q?.provider ?: registry.active().type.name
        jdbc
            .sql(
                """
                insert into market_snapshots(id, instrument_id, captured_at, market_time, provider, feed_type, bid, ask, last, quote_ts, quote_age_seconds, freshness, detail)
                values (:id, :i, :cap, :mt, :p, :f, :bid, :ask, :last, :qts, :age, :fr, cast(:d as jsonb))
                """.trimIndent(),
            ).param("id", id)
            .param("i", instrument.id)
            .param("cap", ts(wall.instant()))
            .param("mt", ts(now))
            .param("p", provider)
            .param("f", q?.feedType?.name ?: "UNKNOWN")
            .param("bid", q?.bid)
            .param("ask", q?.ask)
            .param("last", q?.last)
            .param("qts", ts(q?.exchangeTs))
            .param("age", v.ageSeconds)
            .param("fr", v.status.name)
            .param("d", mapper.writeValueAsString(mapOf("detail" to v.detail, "maxAgeSeconds" to maxAgeSeconds)))
            .update()
        return MarketSnapshot(id, instrument.id, instrument.symbol, now, q?.bid, q?.ask, q?.last, q?.exchangeTs, v.ageSeconds, q?.feedType?.name ?: "UNKNOWN", provider, v.status) to v
    }

    fun snapshot(id: UUID): MarketSnapshot? =
        jdbc
            .sql("select s.*, i.symbol from market_snapshots s join instruments i on i.id = s.instrument_id where s.id = :id")
            .param("id", id)
            .query { rs, _ ->
                MarketSnapshot(
                    rs.uuid("id"),
                    rs.uuid("instrument_id"),
                    rs.getString("symbol"),
                    rs.instant("market_time"),
                    rs.decimalOrNull("bid"),
                    rs.decimalOrNull("ask"),
                    rs.decimalOrNull("last"),
                    rs.getObject("quote_ts", java.time.OffsetDateTime::class.java)?.toInstant(),
                    rs.getLong("quote_age_seconds").takeIf { !rs.wasNull() },
                    rs.getString("feed_type"),
                    rs.getString("provider"),
                    DataStatus.valueOf(rs.getString("freshness")),
                )
            }.optional()
            .orElse(null)

    // ---------------------------------------------------------------- candles

    /**
     * Fetches bars from the provider and stores them after validating the whole batch:
     * strictly increasing timestamps, consistent OHLC, positive prices, closed bars only.
     * An invalid batch is rejected in full and the series is flagged (MS-17).
     */
    @Transactional
    fun ingestCandles(
        instrument: Instrument,
        tf: Timeframe,
        from: Instant,
        to: Instant,
    ): DataStatus {
        val active = registry.active()
        val now = marketClock.now()
        return when (val r = active.provider.candles(instrument.symbol, instrument.assetClass, tf, from, to, now)) {
            is ProviderResult.Unsupported -> DataStatus.UNSUPPORTED.also { flag(instrument.id, tf.code, it, r.detail) }
            is ProviderResult.Failed -> (if (r.kind == FailureKind.MALFORMED) DataStatus.MALFORMED else DataStatus.PROVIDER_ERROR).also { flag(instrument.id, tf.code, it, "${r.kind}: ${r.detail}") }
            is ProviderResult.Ok -> storeCandles(instrument, tf, r.value, active.type.name, now)
        }
    }

    @Transactional
    fun storeCandles(
        instrument: Instrument,
        tf: Timeframe,
        bars: List<CandleData>,
        provider: String,
        now: Instant,
    ): DataStatus {
        validateBatch(instrument, tf, bars, now)?.let { (status, detail) ->
            flag(instrument.id, tf.code, status, detail)
            audit.record(AuditCategory.MARKET_DATA, "CANDLES_REJECTED", AuditOutcome.BLOCKED, "Instrument", instrument.id, mapOf("symbol" to instrument.symbol, "timeframe" to tf.code, "reason" to detail))
            return status
        }
        val recv = ts(wall.instant())
        val feed = if (provider == "REPLAY") FeedType.REPLAY_SYNTHETIC.name else (latestQuote(instrument.id)?.feedType ?: FeedType.UNKNOWN).name
        bars.chunked(500).forEach { chunk ->
            chunk.forEach { b ->
                jdbc
                    .sql(
                        """
                        insert into candles(instrument_id, timeframe, open_time, open, high, low, close, volume, provider, feed_type, received_at)
                        values (:i, :tf, :t, :o, :h, :l, :c, :v, :p, :f, :r) on conflict do nothing
                        """.trimIndent(),
                    ).param("i", instrument.id)
                    .param("tf", tf.code)
                    .param("t", ts(b.openTime))
                    .param("o", b.open)
                    .param("h", b.high)
                    .param("l", b.low)
                    .param("c", b.close)
                    .param("v", b.volume)
                    .param("p", provider)
                    .param("f", feed)
                    .param("r", recv)
                    .update()
            }
        }
        flag(instrument.id, tf.code, null, null)
        return DataStatus.VERIFIED
    }

    fun validateBatch(
        instrument: Instrument,
        tf: Timeframe,
        bars: List<CandleData>,
        now: Instant,
    ): Pair<DataStatus, String>? {
        var prev: Instant? = null
        for (b in bars) {
            if (prev != null && !b.openTime.isAfter(prev)) return DataStatus.OUT_OF_ORDER to "Bar ${b.openTime} is not after $prev"
            if (listOf(b.open, b.high, b.low, b.close).any { it.signum() <= 0 } || b.volume.signum() < 0) return DataStatus.MALFORMED to "Bar ${b.openTime} has non-positive values"
            if (b.high < b.low || b.high < b.open || b.high < b.close || b.low > b.open || b.low > b.close) return DataStatus.MALFORMED to "Bar ${b.openTime} has inconsistent OHLC"
            if (BarSchedule.closeTime(instrument.assetClass, tf, b.openTime).isAfter(now.plus(SKEW_TOLERANCE))) return DataStatus.CLOCK_SKEW to "Bar ${b.openTime} closes after market time $now"
            prev = b.openTime
        }
        return null
    }

    private fun storedBars(
        instrumentId: UUID,
        tf: Timeframe,
        from: Instant,
        to: Instant,
    ): List<CandleData> =
        jdbc
            .sql("select * from candles where instrument_id = :i and timeframe = :tf and open_time >= :f and open_time < :t order by open_time")
            .param("i", instrumentId)
            .param("tf", tf.code)
            .param("f", ts(from))
            .param("t", ts(to))
            .query { rs, _ -> CandleData(rs.instant("open_time"), rs.getBigDecimal("open"), rs.getBigDecimal("high"), rs.getBigDecimal("low"), rs.getBigDecimal("close"), rs.getBigDecimal("volume")) }
            .list()

    private fun storedProvider(
        instrumentId: UUID,
        tf: Timeframe,
    ): String? =
        jdbc
            .sql("select provider from candles where instrument_id = :i and timeframe = :tf order by open_time desc limit 1")
            .param("i", instrumentId)
            .param("tf", tf.code)
            .query(String::class.java)
            .optional()
            .orElse(null)

    /** Point-in-time candles for [from, to) as of market time [asOf]; aggregates from finer bars when needed. */
    fun candles(
        instrument: Instrument,
        tf: Timeframe,
        from: Instant,
        to: Instant,
        at: Instant? = null,
        hydrate: Boolean = true,
    ): CandleSeries {
        val asOf = at ?: marketClock.now()
        val active = registry.active()
        val native = active.provider.nativeTimeframes(instrument.assetClass)
        val source =
            when {
                tf in native -> tf
                tf == Timeframe.M5 || tf == Timeframe.M15 -> Timeframe.M1.takeIf { it in native }
                tf == Timeframe.H4 -> Timeframe.H1.takeIf { it in native }
                else -> null
            } ?: return CandleSeries(instrument.id, instrument.symbol, tf, null, emptyList(), active.type.name, emptyList(), DataStatus.UNSUPPORTED, "Timeframe ${tf.code} unavailable from ${active.type}")
        val end = minOf(to, asOf)
        if (hydrate) hydrate(instrument, source, from, end)
        flagFor(instrument.id, source.code)?.let { (status, detail) ->
            return CandleSeries(instrument.id, instrument.symbol, tf, source, emptyList(), active.type.name, emptyList(), status, detail)
        }
        val raw = storedBars(instrument.id, source, from, end).filter { !BarSchedule.closeTime(instrument.assetClass, source, it.openTime).isAfter(asOf) }
        val provider = storedProvider(instrument.id, source) ?: active.type.name
        val (bars, aggGaps) = if (source == tf) raw to emptyList() else CandleAggregator.aggregate(raw, instrument.assetClass, source, tf, asOf)
        val gaps = (BarSchedule.missing(instrument.assetClass, tf, bars.map { it.openTime }) + aggGaps).distinct().sorted()
        return CandleSeries(instrument.id, instrument.symbol, tf, source, bars, provider, gaps, if (gaps.isEmpty()) DataStatus.VERIFIED else DataStatus.GAPS, if (gaps.isEmpty()) "OK" else "${gaps.size} missing bar(s)")
    }

    private fun hydrate(
        instrument: Instrument,
        tf: Timeframe,
        from: Instant,
        to: Instant,
    ) {
        if (!from.isBefore(to)) return
        val range =
            jdbc
                .sql("select min(open_time) as mn, max(open_time) as mx from candles where instrument_id = :i and timeframe = :tf and open_time >= :f and open_time < :t")
                .param("i", instrument.id)
                .param("tf", tf.code)
                .param("f", ts(from))
                .param("t", ts(to))
                .query { rs, _ -> rs.getObject("mn", java.time.OffsetDateTime::class.java)?.toInstant() to rs.getObject("mx", java.time.OffsetDateTime::class.java)?.toInstant() }
                .single()
        val (mn, mx) = range
        if (mn == null || mx == null) {
            ingestCandles(instrument, tf, from, to)
            return
        }
        if (mn.isAfter(from.plus(tf.duration))) ingestCandles(instrument, tf, from, mn)
        val next = mx.plus(tf.duration)
        if (next.isBefore(to)) ingestCandles(instrument, tf, next, to)
    }

    /**
     * Verifies that at least [minBars] closed bars ending at the most recent expected bar exist
     * with no gaps, no flags and no staleness (FR-024). Returns the verified series.
     */
    fun verifyCandles(
        instrument: Instrument,
        tf: Timeframe,
        minBars: Int,
        at: Instant? = null,
    ): CandleSeries {
        val asOf = at ?: marketClock.now()
        val lookback = BarSchedule.lookback(instrument.assetClass, tf, minBars + 2)
        val series = candles(instrument, tf, asOf.minus(lookback), asOf.plusSeconds(1), asOf)
        if (series.status == DataStatus.UNSUPPORTED || series.status in FLAG_STATUSES) return series
        val bars = series.bars
        if (bars.size < minBars) return series.copy(status = DataStatus.INSUFFICIENT_HISTORY, detail = "Only ${bars.size} of $minBars required bars available")
        val window = bars.takeLast(minBars)
        val windowGaps = series.gaps.filter { !it.isBefore(window.first().openTime) }
        if (windowGaps.isNotEmpty()) return series.copy(status = DataStatus.GAPS, detail = "${windowGaps.size} missing bar(s) within the evaluation window")
        val expectedLast = BarSchedule.lastClosedBarStart(instrument.assetClass, tf, asOf)
        if (expectedLast != null && bars.last().openTime.isBefore(expectedLast)) {
            return series.copy(status = DataStatus.STALE, detail = "Latest bar ${bars.last().openTime} is older than expected $expectedLast")
        }
        return series.copy(status = DataStatus.VERIFIED, detail = "Verified ${window.size} bars")
    }

    // ---------------------------------------------------------------- FX

    @Transactional
    fun refreshFx(
        base: String = "USD",
        quote: String = "CAD",
    ): FxData? {
        val active = registry.active()
        return when (val r = active.provider.fxRate(base, quote, marketClock.now())) {
            is ProviderResult.Ok -> {
                jdbc
                    .sql(
                        "insert into fx_rates(base, quote, rate, as_of, provider, received_at) values (:b, :q, :r, :a, :p, :now) on conflict do nothing",
                    ).param("b", base)
                    .param("q", quote)
                    .param("r", r.value.rate)
                    .param("a", ts(r.value.asOf))
                    .param("p", active.type.name)
                    .param("now", ts(wall.instant()))
                    .update()
                r.value
            }
            else -> null
        }
    }

    /** Latest rate at or before [asOf]; never silently converts without a timestamped rate. */
    fun latestFx(
        base: String = "USD",
        quote: String = "CAD",
        at: Instant? = null,
    ): FxRateView? =
        jdbc
            .sql("select * from fx_rates where base = :b and quote = :q and as_of <= :a order by as_of desc limit 1")
            .param("b", base)
            .param("q", quote)
            .param("a", ts(at ?: marketClock.now()))
            .query { rs, _ -> FxRateView(rs.getString("base"), rs.getString("quote"), rs.getBigDecimal("rate"), rs.instant("as_of"), rs.getString("provider")) }
            .optional()
            .orElse(null)

    // ---------------------------------------------------------------- flags

    private fun flag(
        instrumentId: UUID,
        key: String,
        status: DataStatus?,
        detail: String?,
    ) {
        if (status == null) {
            jdbc
                .sql(
                    "insert into market_data_status(instrument_id, timeframe, status, detail, updated_at) values (:i, :k, 'OK', null, :now) " +
                        "on conflict (instrument_id, timeframe) do update set status = 'OK', detail = null, updated_at = excluded.updated_at",
                ).param("i", instrumentId)
                .param("k", key)
                .param("now", ts(wall.instant()))
                .update()
            return
        }
        val s =
            when (status) {
                DataStatus.OUT_OF_ORDER, DataStatus.MALFORMED, DataStatus.CLOCK_SKEW, DataStatus.UNSUPPORTED -> status.name
                else -> "PROVIDER_ERROR"
            }
        jdbc
            .sql(
                "insert into market_data_status(instrument_id, timeframe, status, detail, updated_at) values (:i, :k, :s, :d, :now) " +
                    "on conflict (instrument_id, timeframe) do update set status = excluded.status, detail = excluded.detail, updated_at = excluded.updated_at",
            ).param("i", instrumentId)
            .param("k", key)
            .param("s", s)
            .param("d", detail?.take(500))
            .param("now", ts(wall.instant()))
            .update()
    }

    fun flagFor(
        instrumentId: UUID,
        key: String,
    ): Pair<DataStatus, String>? =
        jdbc
            .sql("select status, detail from market_data_status where instrument_id = :i and timeframe = :k and status <> 'OK'")
            .param("i", instrumentId)
            .param("k", key)
            .query { rs, _ -> (if (rs.getString(1) == "PROVIDER_ERROR") DataStatus.PROVIDER_ERROR else DataStatus.valueOf(rs.getString(1))) to (rs.getString(2) ?: "") }
            .optional()
            .orElse(null)

    fun clearFlag(
        instrumentId: UUID,
        key: String,
    ) = flag(instrumentId, key, null, null)

    fun flagCounts(): Map<String, Int> =
        jdbc
            .sql("select status, count(*) from market_data_status group by status")
            .query { rs, _ -> rs.getString(1) to rs.getInt(2) }
            .list()
            .toMap()

    companion object {
        const val QUOTE_KEY = "quote"
        val SKEW_TOLERANCE: Duration = Duration.ofSeconds(5)
        val FLAG_STATUSES = setOf(DataStatus.OUT_OF_ORDER, DataStatus.MALFORMED, DataStatus.CLOCK_SKEW, DataStatus.PROVIDER_ERROR)
    }
}

data class FxRateView(
    val base: String,
    val quote: String,
    val rate: BigDecimal,
    val asOf: Instant,
    val provider: String,
)
