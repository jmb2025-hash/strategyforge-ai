package app.strategyforge.engine.market

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.toJsonElement
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.decOrNull
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.int
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Market data ingestion, storage and verification (FR-022, FR-024). Every quote and candle
 * keeps its provider and exchange timestamp. Malformed, out-of-order and future-dated data is
 * rejected and flagged; evaluation consults [verifyQuote]/[verifyCandles] and fails closed.
 */
class MarketDataService(
    private val db: Db,
    private val registry: MarketSources,
    private val marketClock: MarketClock,
    private val wall: Clock,
    private val audit: AuditService,
    /** A fresh streamed quote for an instrument, or null (D-056). */
    private val stream: (Instrument) -> QuoteData? = { null },
) {
    /** Instruments whose stored quote came from a stream, with that quote's time. */
    private val streamed = java.util.concurrent.ConcurrentHashMap<UUID, Instant>()

    // ---------------------------------------------------------------- quotes

    fun refreshQuote(instrument: Instrument): QuoteVerification {
        val active = registry.active()
        val now = marketClock.now()
        streamQuote(instrument, now)?.let { return it }
        return when (val r = active.provider.quote(instrument.symbol, instrument.assetClass, now)) {
            is ProviderResult.Ok -> {
                val existing = streamed[instrument.id]
                // A polled quote (possibly delayed) older than the streamed one is not news, and not out of order.
                if (existing != null && r.value.exchangeTs.isBefore(existing)) return verifyQuote(instrument, Long.MAX_VALUE, now)
                streamed.remove(instrument.id)
                storeQuote(instrument, r.value, registry.nameFor(instrument.assetClass), now)
            }
            is ProviderResult.Unsupported -> {
                flag(instrument.id, QUOTE_KEY, DataStatus.UNSUPPORTED, r.detail)
                QuoteVerification(DataStatus.UNSUPPORTED, null, null, r.detail)
            }
            is ProviderResult.Failed -> {
                val status = if (r.kind == FailureKind.MALFORMED) DataStatus.MALFORMED else DataStatus.PROVIDER_ERROR
                // Bad data is flagged until good data replaces it. A failed request (the phone briefly offline) says
                // nothing about the stored price, so it is not flagged: that price stays usable until it is too old,
                // which the age limit enforces as before (D-074).
                if (status == DataStatus.MALFORMED) flag(instrument.id, QUOTE_KEY, status, "${r.kind}: ${r.detail}")
                QuoteVerification(status, latestQuote(instrument.id), null, "${r.kind}: ${r.detail}")
            }
        }
    }

    /**
     * Stores the stream's latest tick for [instrument] in LIVE mode (D-056). Null when no fresh tick
     * exists, so the caller polls instead; a tick already stored returns the current verification.
     */
    fun streamQuote(
        instrument: Instrument,
        now: Instant = marketClock.now(),
    ): QuoteVerification? {
        if (registry.mode != MarketMode.LIVE) return null
        val q = stream(instrument) ?: return null
        val seen = streamed[instrument.id]
        if (seen != null && !q.exchangeTs.isAfter(seen)) return verifyQuote(instrument, Long.MAX_VALUE, now)
        val existing = latestQuote(instrument.id)
        if (existing != null && q.exchangeTs.isBefore(existing.exchangeTs)) return null
        streamed[instrument.id] = q.exchangeTs
        return storeQuote(instrument, q, registry.nameFor(instrument.assetClass), now)
    }

    /** Validates and stores a quote; rejects malformed, future-dated (clock skew) and out-of-order quotes. */
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
        db
            .sql(
                """
                insert or replace into latest_quotes(instrument_id, bid, ask, last, bid_size, ask_size, exchange_ts, received_at, provider, feed_type)
                values (:i, :bid, :ask, :last, :bs, :as, :ts, :recv, :p, :f)
                """.trimIndent(),
            ).param("i", instrument.id)
            .param("bid", q.bid)
            .param("ask", q.ask)
            .param("last", q.last)
            .param("bs", q.bidSize)
            .param("as", q.askSize)
            .param("ts", (q.exchangeTs))
            .param("recv", (wall.instant()))
            .param("p", provider)
            .param("f", q.feedType.name)
            .update()
        flag(instrument.id, QUOTE_KEY, null, null)
        return verifyQuote(instrument, Long.MAX_VALUE, now)
    }

    fun latestQuote(instrumentId: UUID): StoredQuote? =
        db
            .sql("select * from latest_quotes where instrument_id = :i")
            .param("i", instrumentId)
            .firstOrNull { rs ->
                StoredQuote(
                    rs.uuid("instrument_id"),
                    rs.decOrNull("bid"),
                    rs.decOrNull("ask"),
                    rs.dec("last"),
                    rs.decOrNull("bid_size"),
                    rs.decOrNull("ask_size"),
                    rs.instant("exchange_ts"),
                    rs.instant("received_at"),
                    rs.str("provider"),
                    FeedType.valueOf(rs.str("feed_type")),
                )
            }

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
        if (q.provider != registry.nameFor(instrument.assetClass)) return QuoteVerification(DataStatus.STALE, q, age(q, now), "Quote came from inactive provider ${q.provider}")
        if (q.exchangeTs.isAfter(now.plus(SKEW_TOLERANCE))) return QuoteVerification(DataStatus.CLOCK_SKEW, q, null, "Quote is dated in the future")
        val ageSec = age(q, now)
        val lag = registry.forClass(instrument.assetClass)?.expectedLagSeconds() ?: 0
        val limit = if (maxAgeSeconds > Long.MAX_VALUE - lag) Long.MAX_VALUE else maxAgeSeconds + lag
        if (ageSec > limit) return QuoteVerification(DataStatus.STALE, q, ageSec, "Quote age ${ageSec}s exceeds ${limit}s")
        return QuoteVerification(DataStatus.VERIFIED, q, ageSec, if (lag > 0) "Verified (${q.feedType.name.lowercase()} feed, up to ${lag}s behind)" else "Verified")
    }

    private fun age(
        q: StoredQuote,
        now: Instant,
    ) = Duration.between(q.exchangeTs, now).seconds.coerceAtLeast(0)

    /** Immutable snapshot referenced by signals, orders and risk decisions (FR-061, FR-085). */
    fun captureSnapshot(
        instrument: Instrument,
        maxAgeSeconds: Long,
    ): Pair<MarketSnapshot, QuoteVerification> {
        val now = marketClock.now()
        val v = verifyQuote(instrument, maxAgeSeconds, now)
        val q = v.quote
        val id = UUID.randomUUID()
        val provider = q?.provider ?: registry.nameFor(instrument.assetClass)
        db
            .sql(
                """
                insert into market_snapshots(id, instrument_id, captured_at, market_time, provider, feed_type, bid, ask, last, quote_ts, quote_age_seconds, freshness, detail)
                values (:id, :i, :cap, :mt, :p, :f, :bid, :ask, :last, :qts, :age, :fr, :d)
                """.trimIndent(),
            ).param("id", id)
            .param("i", instrument.id)
            .param("cap", (wall.instant()))
            .param("mt", (now))
            .param("p", provider)
            .param("f", q?.feedType?.name ?: "UNKNOWN")
            .param("bid", q?.bid)
            .param("ask", q?.ask)
            .param("last", q?.last)
            .param("qts", (q?.exchangeTs))
            .param("age", v.ageSeconds)
            .param("fr", v.status.name)
            .param("d", mapOf("detail" to v.detail, "maxAgeSeconds" to maxAgeSeconds).toJsonElement().toString())
            .update()
        return MarketSnapshot(id, instrument.id, instrument.symbol, now, q?.bid, q?.ask, q?.last, q?.exchangeTs, v.ageSeconds, q?.feedType?.name ?: "UNKNOWN", provider, v.status) to v
    }

    fun snapshot(id: UUID): MarketSnapshot? =
        db
            .sql("select s.*, i.symbol from market_snapshots s join instruments i on i.id = s.instrument_id where s.id = :id")
            .param("id", id)
            .firstOrNull { rs ->
                MarketSnapshot(
                    rs.uuid("id"),
                    rs.uuid("instrument_id"),
                    rs.str("symbol"),
                    rs.instant("market_time"),
                    rs.decOrNull("bid"),
                    rs.decOrNull("ask"),
                    rs.decOrNull("last"),
                    rs.instantOrNull("quote_ts"),
                    rs.long("quote_age_seconds"),
                    rs.str("feed_type"),
                    rs.str("provider"),
                    DataStatus.valueOf(rs.str("freshness")),
                )
            }

    // ---------------------------------------------------------------- candles

    /**
     * Fetches bars from the provider and stores them after validating the whole batch:
     * strictly increasing timestamps, consistent OHLC, positive prices, closed bars only.
     * An invalid batch is rejected in full and the series is flagged (MS-17).
     */
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
            is ProviderResult.Ok -> storeCandles(instrument, tf, r.value, registry.nameFor(instrument.assetClass), now)
        }
    }

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
        val recv = (wall.instant())
        val feed = if (provider == "REPLAY") FeedType.REPLAY_SYNTHETIC.name else (latestQuote(instrument.id)?.feedType ?: FeedType.UNKNOWN).name
        db.tx {
            bars.forEach { b ->
                db
                    .sql(
                        """
                        insert or ignore into candles(instrument_id, timeframe, open_time, open, high, low, close, volume, provider, feed_type, received_at)
                        values (:i, :tf, :t, :o, :h, :l, :c, :v, :p, :f, :r)
                        """.trimIndent(),
                    ).param("i", instrument.id)
                    .param("tf", tf.code)
                    .param("t", (b.openTime))
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
        db
            .sql("select * from candles where instrument_id = :i and timeframe = :tf and open_time >= :f and open_time < :t order by open_time")
            .param("i", instrumentId)
            .param("tf", tf.code)
            .param("f", (from))
            .param("t", (to))
            .list { rs -> CandleData(rs.instant("open_time"), rs.dec("open"), rs.dec("high"), rs.dec("low"), rs.dec("close"), rs.dec("volume")) }

    private fun storedProvider(
        instrumentId: UUID,
        tf: Timeframe,
    ): String? =
        db
            .sql("select provider from candles where instrument_id = :i and timeframe = :tf order by open_time desc limit 1")
            .param("i", instrumentId)
            .param("tf", tf.code)
            .firstOrNull { it.str("provider") }

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
        val providerName = registry.nameFor(instrument.assetClass)
        val native = active.provider.nativeTimeframes(instrument.assetClass)
        val source =
            when {
                tf in native -> tf
                tf == Timeframe.M5 || tf == Timeframe.M15 -> Timeframe.M1.takeIf { it in native }
                tf == Timeframe.M30 -> Timeframe.M15.takeIf { it in native } ?: Timeframe.M1.takeIf { it in native }
                tf == Timeframe.H4 -> Timeframe.H1.takeIf { it in native }
                else -> null
            } ?: return CandleSeries(instrument.id, instrument.symbol, tf, null, emptyList(), providerName, emptyList(), DataStatus.UNSUPPORTED, "Timeframe ${tf.code} unavailable from $providerName")
        val end = minOf(to, asOf)
        if (hydrate) hydrate(instrument, source, from, end)
        flagFor(instrument.id, source.code)?.let { (status, detail) ->
            return CandleSeries(instrument.id, instrument.symbol, tf, source, emptyList(), providerName, emptyList(), status, detail)
        }
        val raw = storedBars(instrument.id, source, from, end).filter { !BarSchedule.closeTime(instrument.assetClass, source, it.openTime).isAfter(asOf) }
        val provider = storedProvider(instrument.id, source) ?: providerName
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
            db
                .sql("select min(open_time) as mn, max(open_time) as mx from candles where instrument_id = :i and timeframe = :tf and open_time >= :f and open_time < :t")
                .param("i", instrument.id)
                .param("tf", tf.code)
                .param("f", (from))
                .param("t", (to))
                .single { rs -> rs.instantOrNull("mn") to rs.instantOrNull("mx") }
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

    fun refreshFx(
        base: String = "USD",
        quote: String = "CAD",
    ): FxData? {
        val active = registry.active()
        return when (val r = active.provider.fxRate(base, quote, marketClock.now())) {
            is ProviderResult.Ok -> {
                db
                    .sql(
                        "insert or ignore into fx_rates(base, quote, rate, as_of, provider, received_at) values (:b, :q, :r, :a, :p, :now)",
                    ).param("b", base)
                    .param("q", quote)
                    .param("r", r.value.rate)
                    .param("a", (r.value.asOf))
                    .param("p", if (registry.mode == MarketMode.DEMO) ProviderType.REPLAY.name else "LIVE")
                    .param("now", (wall.instant()))
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
        db
            .sql("select * from fx_rates where base = :b and quote = :q and as_of <= :a order by as_of desc limit 1")
            .param("b", base)
            .param("q", quote)
            .param("a", (at ?: marketClock.now()))
            .firstOrNull { rs -> FxRateView(rs.str("base"), rs.str("quote"), rs.dec("rate"), rs.instant("as_of"), rs.str("provider")) }

    // ---------------------------------------------------------------- flags

    private fun flag(
        instrumentId: UUID,
        key: String,
        status: DataStatus?,
        detail: String?,
    ) {
        if (status == null) {
            db
                .sql(
                    "insert or replace into market_data_status(instrument_id, timeframe, status, detail, updated_at) values (:i, :k, 'OK', null, :now)",
                ).param("i", instrumentId)
                .param("k", key)
                .param("now", (wall.instant()))
                .update()
            return
        }
        val s =
            when (status) {
                DataStatus.OUT_OF_ORDER, DataStatus.MALFORMED, DataStatus.CLOCK_SKEW, DataStatus.UNSUPPORTED -> status.name
                else -> "PROVIDER_ERROR"
            }
        db
            .sql(
                "insert or replace into market_data_status(instrument_id, timeframe, status, detail, updated_at) values (:i, :k, :s, :d, :now)",
            ).param("i", instrumentId)
            .param("k", key)
            .param("s", s)
            .param("d", detail?.take(500))
            .param("now", (wall.instant()))
            .update()
    }

    fun flagFor(
        instrumentId: UUID,
        key: String,
    ): Pair<DataStatus, String>? =
        db
            .sql("select status, detail from market_data_status where instrument_id = :i and timeframe = :k and status <> 'OK'")
            .param("i", instrumentId)
            .param("k", key)
            .firstOrNull { rs -> DataStatus.valueOf(rs.str("status")) to (rs.string("detail") ?: "") }

    fun clearFlag(
        instrumentId: UUID,
        key: String,
    ) = flag(instrumentId, key, null, null)

    fun flagCounts(): Map<String, Int> =
        db
            .sql("select status, count(*) n from market_data_status group by status")
            .list { rs -> rs.str("status") to rs.int("n") }
            .toMap()

    companion object {
        const val QUOTE_KEY = "quote"

        /** How far ahead of the phone's clock a quote or bar may be dated: phone clocks drift by seconds (D-071). */
        val SKEW_TOLERANCE: Duration = Duration.ofSeconds(60)
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
