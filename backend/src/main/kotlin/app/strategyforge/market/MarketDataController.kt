package app.strategyforge.market

import app.strategyforge.common.idempotency.IdempotencyService
import app.strategyforge.common.web.Problems
import app.strategyforge.common.web.parseUuid
import app.strategyforge.market.data.DataStatus
import app.strategyforge.market.data.MarketDataIngestion
import app.strategyforge.market.data.MarketDataService
import app.strategyforge.market.data.ReplayService
import app.strategyforge.market.provider.MarketProviderRegistry
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class AddInstrumentRequest(
    val symbol: String,
)

data class InstrumentActiveRequest(
    val active: Boolean,
)

/** Quote with explicit freshness and feed labelling (FR-023). */
data class QuoteView(
    val symbol: String,
    val assetClass: AssetClass,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val last: BigDecimal?,
    val exchangeTimestamp: Instant?,
    val receivedAt: Instant?,
    val ageSeconds: Long?,
    val feedType: String,
    val feedLabel: String,
    val provider: String?,
    val status: DataStatus,
    val detail: String,
    val session: SessionState,
    val marketTime: Instant,
)

data class CandleView(
    val openTime: Instant,
    val closeTime: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
)

data class CandlesResponse(
    val symbol: String,
    val timeframe: String,
    val sourceTimeframe: String?,
    val provider: String,
    val status: DataStatus,
    val detail: String,
    val gaps: List<Instant>,
    val bars: List<CandleView>,
)

data class CapabilityStatusView(
    val capability: String,
    val state: String,
)

data class MarketStatusView(
    val providerId: String,
    val providerType: String,
    val clockMode: ClockMode,
    val marketTime: Instant,
    val capabilities: List<CapabilityStatusView>,
    val equitySession: SessionState,
    val dataFlags: Map<String, Int>,
    val lastIngestionAt: Instant?,
    val lastIngestionFailures: Map<String, String>,
    val syntheticData: Boolean,
)

data class ReplayAdvanceRequest(
    val minutes: Long,
    val stepMinutes: Long = 1,
)

data class ReplaySetRequest(
    val to: Instant,
)

data class WatchlistCreate(
    val name: String,
    val symbols: List<String> = emptyList(),
)

data class WatchlistItemAdd(
    val symbol: String,
)

data class AlertCreate(
    val symbol: String,
    val condition: String,
    val threshold: BigDecimal,
    val note: String? = null,
)

@RestController
@RequestMapping("/v1/instruments")
@Tag(name = "Markets")
class InstrumentsController(
    private val instruments: InstrumentService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) assetClass: String?,
        @RequestParam(defaultValue = "true") activeOnly: Boolean,
    ) = instruments.list(q, assetClass, activeOnly)

    @GetMapping("/{symbol}")
    fun get(
        @PathVariable symbol: String,
    ) = instruments.bySymbol(symbol)

    @PostMapping
    fun add(
        @RequestBody req: AddInstrumentRequest,
    ): ResponseEntity<Instrument> = ResponseEntity.status(HttpStatus.CREATED).body(instruments.add(req.symbol))

    @PatchMapping("/{symbol}")
    fun setActive(
        @PathVariable symbol: String,
        @RequestBody req: InstrumentActiveRequest,
    ) = instruments.setActive(symbol, req.active)
}

@RestController
@RequestMapping("/v1/market-data")
@Tag(name = "Markets")
class MarketDataController(
    private val instruments: InstrumentService,
    private val data: MarketDataService,
    private val registry: MarketProviderRegistry,
    private val clock: MarketClock,
    private val ingestion: MarketDataIngestion,
    private val replay: ReplayService,
    private val corporate: CorporateActionService,
    private val idempotency: IdempotencyService,
) {
    @GetMapping("/status")
    fun status(): MarketStatusView {
        val a = registry.active()
        val last = ingestion.lastReport.get()
        return MarketStatusView(
            a.id.toString(),
            a.type.name,
            clock.mode(),
            clock.now(),
            a.provider.capabilities().map { (c, s) -> CapabilityStatusView(c.name, s.name) },
            MarketCalendar.state(AssetClass.US_EQUITY, clock.now()),
            data.flagCounts(),
            last?.at,
            last?.failures.orEmpty(),
            a.type.name == "REPLAY",
        )
    }

    @GetMapping("/quotes/{symbol}")
    fun quote(
        @PathVariable symbol: String,
        @RequestParam(defaultValue = "false") refresh: Boolean,
        @RequestParam(defaultValue = "60") maxAgeSeconds: Long,
    ): QuoteView {
        val i = instruments.bySymbol(symbol)
        if (refresh) data.refreshQuote(i)
        val now = clock.now()
        val v = data.verifyQuote(i, maxAgeSeconds, now)
        val q = v.quote
        val feed = q?.feedType?.name ?: "UNKNOWN"
        return QuoteView(
            i.symbol,
            i.assetClass,
            q?.bid,
            q?.ask,
            q?.last,
            q?.exchangeTs,
            q?.receivedAt,
            v.ageSeconds,
            feed,
            feedLabel(feed),
            q?.provider,
            v.status,
            v.detail,
            MarketCalendar.state(i.assetClass, now),
            now,
        )
    }

    @GetMapping("/candles/{symbol}")
    fun candles(
        @PathVariable symbol: String,
        @RequestParam timeframe: String,
        @RequestParam(required = false) from: Instant?,
        @RequestParam(required = false) to: Instant?,
    ): CandlesResponse {
        val i = instruments.bySymbol(symbol)
        val tf = Timeframe.ofOrNull(timeframe) ?: throw Problems.badRequest("invalid-timeframe", "Supported timeframes: ${Timeframe.entries.map { it.code }}")
        val end = to ?: clock.now()
        val start = from ?: end.minus(tf.duration.multipliedBy(200))
        if (Duration.between(start, end) > tf.duration.multipliedBy(MAX_BARS)) throw Problems.badRequest("range-too-large", "Request at most $MAX_BARS bars")
        val s = data.candles(i, tf, start, end)
        return CandlesResponse(
            i.symbol,
            tf.code,
            s.sourceTimeframe?.code,
            s.provider,
            s.status,
            s.detail,
            s.gaps,
            s.bars.map {
                CandleView(
                    it.openTime,
                    app.strategyforge.market.data.BarSchedule
                        .closeTime(i.assetClass, tf, it.openTime),
                    it.open,
                    it.high,
                    it.low,
                    it.close,
                    it.volume,
                )
            },
        )
    }

    @GetMapping("/fx/{base}/{quote}")
    fun fx(
        @PathVariable base: String,
        @PathVariable quote: String,
    ) = data.latestFx(base.uppercase(), quote.uppercase()) ?: data.refreshFx(base.uppercase(), quote.uppercase())?.let { data.latestFx(base.uppercase(), quote.uppercase()) }
        ?: throw Problems.unavailable("fx-unavailable", "No timestamped ${base.uppercase()}/${quote.uppercase()} rate is available; conversions are not shown")

    @GetMapping("/corporate-actions/{symbol}")
    fun corporateActions(
        @PathVariable symbol: String,
        @RequestParam from: LocalDate,
        @RequestParam to: LocalDate,
    ): Map<String, Any?> {
        val i = instruments.bySymbol(symbol)
        val coverage = corporate.sync(i, from, to)
        return mapOf("symbol" to i.symbol, "coverage" to coverage, "actions" to corporate.actions(i.id, from, to), "manualReviewRequired" to (coverage.status == CoverageStatus.UNAVAILABLE))
    }

    /** Clears data-quality flags for a symbol after the owner has reviewed the issue; audited. */
    @PostMapping("/status/{symbol}/reset")
    fun resetFlags(
        @PathVariable symbol: String,
    ): QuoteView {
        val i = instruments.bySymbol(symbol)
        Timeframe.entries.forEach { data.clearFlag(i.id, it.code) }
        data.clearFlag(i.id, MarketDataService.QUOTE_KEY)
        return quote(symbol, true, 60)
    }

    @GetMapping("/clock")
    fun clock() = replay.view()

    @PostMapping("/replay/advance")
    fun advance(
        @RequestBody req: ReplayAdvanceRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("replay-advance", key, req) { replay.advance(req.minutes, req.stepMinutes) }

    @PostMapping("/replay/set")
    fun set(
        @RequestBody req: ReplaySetRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("replay-set", key, req) { replay.setTime(req.to) }

    companion object {
        const val MAX_BARS = 5000L

        fun feedLabel(feed: String) =
            when (feed) {
                "REPLAY_SYNTHETIC" -> "Replay (synthetic data)"
                "REALTIME" -> "Real-time"
                "DELAYED" -> "Delayed"
                else -> "Delay unknown"
            }
    }
}

@RestController
@RequestMapping("/v1/watchlists")
@Tag(name = "Markets")
class WatchlistsController(
    private val watchlists: WatchlistService,
    private val alerts: PriceAlertService,
) {
    @GetMapping
    fun list() = watchlists.list()

    @PostMapping
    fun create(
        @RequestBody req: WatchlistCreate,
    ): ResponseEntity<Watchlist> = ResponseEntity.status(HttpStatus.CREATED).body(watchlists.create(req.name, req.symbols))

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ) = watchlists.get(parseUuid(id))

    @PostMapping("/{id}/items")
    fun add(
        @PathVariable id: String,
        @RequestBody req: WatchlistItemAdd,
    ) = watchlists.addItem(parseUuid(id), req.symbol)

    @DeleteMapping("/{id}/items/{symbol}")
    fun remove(
        @PathVariable id: String,
        @PathVariable symbol: String,
    ) = watchlists.removeItem(parseUuid(id), symbol)

    @DeleteMapping("/{id}")
    fun archive(
        @PathVariable id: String,
    ): ResponseEntity<Unit> {
        watchlists.archive(parseUuid(id))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/alerts")
    fun alerts(
        @RequestParam(required = false) status: String?,
    ) = alerts.list(status)

    @PostMapping("/alerts")
    fun createAlert(
        @RequestBody req: AlertCreate,
    ): ResponseEntity<PriceAlert> = ResponseEntity.status(HttpStatus.CREATED).body(alerts.create(req.symbol, req.condition, req.threshold, req.note))

    @DeleteMapping("/alerts/{id}")
    fun cancelAlert(
        @PathVariable id: String,
    ) = alerts.cancel(parseUuid(id))
}
