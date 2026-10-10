package app.strategyforge.engine.tsx

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.int
import app.strategyforge.engine.db.intOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.notifications.NotificationService
import app.strategyforge.engine.notifications.Severity
import com.fasterxml.jackson.databind.node.ObjectNode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

enum class TsxMode { NOTIFY, AUTONOMOUS }

data class TsxDataStatus(
    val listings: Int,
    val cached: Int,
    val latestDay: LocalDate?,
    val lastRefreshAt: Instant?,
    val refreshing: Boolean,
    val done: Int,
    val total: Int,
    val failed: Int,
    val error: String?,
)

data class TsxHolding(
    val symbol: String,
    val shares: Double,
    val price: Double,
    /** Intraday price newer than [price] (the last close), when known (D-056). */
    val livePrice: Double? = null,
)

data class TsxRunView(
    val id: UUID,
    val planId: String,
    val planName: String,
    val slot: Int,
    val mode: TsxMode,
    val drip: Boolean,
    val startingCash: Double,
    val cash: Double,
    val value: Double,
    val dividendsReceived: Double,
    val status: String,
    val startDay: LocalDate?,
    val lastDay: LocalDate?,
    val holdings: List<TsxHolding>,
    val pending: Map<String, Double>?,
    val pendingDay: LocalDate?,
    val createdAt: Instant,
    /** Value at intraday prices since the last close (display only, D-056); null outside the session. */
    val liveValue: Double? = null,
    val liveAt: Instant? = null,
)

data class TsxEvent(
    val day: LocalDate,
    val kind: String,
    val symbol: String?,
    val shares: Double?,
    val price: Double?,
    val amount: Double?,
)

/**
 * TSX portfolio plans (D-055): the four researched plans run as C$ paper portfolios in ten TSX
 * slots, separate from the order and ledger engine. Data is daily (Yahoo Finance, refreshed in the
 * background); each run re-weights on its plan's schedule at the day's closes. In NOTIFY mode a
 * rebalance waits for the owner's approval; in AUTONOMOUS mode it is applied. Dividends are
 * reinvested in the payer (DRIP) or paid out as income, as chosen per run.
 */
class TsxService(
    private val db: Db,
    private val notifications: NotificationService,
    private val audit: AuditService,
    private val wall: Clock,
    private val provider: () -> TsxHistoryProvider?,
    private val background: (() -> Unit) -> Unit,
    private val engineThread: (() -> Unit) -> Unit,
    /** A fresh streamed price for a listing, when a stream carries it (D-056). */
    private val streamTick: (String) -> app.strategyforge.engine.market.StreamTick? = { null },
) {
    private val log = EngineLog.of(javaClass)
    private val mapper = JacksonCanonical.mapper

    /** Pause between listings while downloading, to stay polite to the data source; tests set 0. */
    internal var pauseMs = 250L

    @Volatile private var refreshing = false

    @Volatile private var done = 0

    @Volatile private var total = 0

    @Volatile private var failed = 0

    @Volatile private var lastError: String? = null
    private var cache: TsxData? = null

    /** Intraday prices for held listings, refreshed in the background while the TSX is open (D-056). */
    private val intraday = java.util.concurrent.ConcurrentHashMap<String, TsxQuote>()

    @Volatile private var intradayRunning = false

    @Volatile private var intradayAt: Instant? = null

    val listings: List<TsxListing> get() = TsxCatalog.listings

    // ------------------------------------------------------------------ data

    fun data(): TsxData =
        cache ?: run {
            val series =
                db
                    .sql("select symbol, bars, dividends from tsx_history")
                    .list { r ->
                        r.str("symbol") to decode(r.str("bars"), r.str("dividends"))
                    }.filter { it.second.dates.isNotEmpty() }
                    .toMap()
            TsxData(series, DATA_START).also { cache = it }
        }

    fun status(): TsxDataStatus {
        val d = data()
        return TsxDataStatus(listings.size, d.series.size, d.calendar.lastOrNull(), lastRefreshAt(), refreshing, done, total, failed, lastError)
    }

    private fun lastRefreshAt(): Instant? =
        db
            .sql("select value from settings where key = :k")
            .param("k", REFRESH_KEY)
            .firstOrNull { it.str("value").toLongOrNull() }
            ?.let(Instant::ofEpochMilli)

    /** Starts a background download of new TSX history; false when one is already running or no source exists. */
    fun refresh(): Boolean {
        val p = provider() ?: return false.also { lastError = "No TSX data source is configured" }
        if (refreshing) return false
        // Only completed sessions: before 16:30 Toronto today's daily bar is still forming.
        val local = wall.instant().atZone(YahooTsxProvider.TORONTO)
        val today = if (local.toLocalTime() < java.time.LocalTime.of(16, 30)) local.toLocalDate().minusDays(1) else local.toLocalDate()
        val lastDays = db.sql("select symbol, last_day from tsx_history").list { it.str("symbol") to it.string("last_day")?.let(LocalDate::parse) }.toMap()
        val symbols = (listings.map { it.symbol } + TsxCatalog.plans.flatMap { it.namedSymbols }).distinct()
        refreshing = true
        done = 0
        total = symbols.size
        failed = 0
        lastError = null
        background {
            val results = mutableListOf<Pair<String, TsxFetch>>()
            for (s in symbols) {
                val from = lastDays[s]?.minusDays(14) ?: DATA_START
                val r = runCatching { p.history(s, from, today) }.getOrElse { TsxFetch.Failed(it.message ?: "error") }
                results += s to r
                done++
                if (r is TsxFetch.Failed && !r.notFound) failed++
                Thread.sleep(pauseMs)
            }
            engineThread { finishRefresh(results) }
        }
        return true
    }

    private fun finishRefresh(results: List<Pair<String, TsxFetch>>) {
        try {
            db.tx {
                results.forEach { (s, r) -> if (r is TsxFetch.Ok && r.bars.isNotEmpty()) merge(s, r) }
            }
            val ok = results.count { it.second is TsxFetch.Ok }
            lastError =
                if (ok == 0) {
                    (results.firstOrNull { it.second is TsxFetch.Failed }?.second as? TsxFetch.Failed)?.reason ?: "No data received"
                } else if (failed > 0) {
                    "$failed of ${results.size} listings could not be updated; they are retried next time"
                } else {
                    null
                }
            if (ok > 0) {
                db
                    .sql("insert or replace into settings(key, value) values (:k, :v)")
                    .param("k", REFRESH_KEY)
                    .param("v", wall.millis().toString())
                    .update()
            }
            cache = null
            audit.record(AuditCategory.MARKET_DATA, "TSX_DATA_REFRESHED", entityType = "TsxData", details = mapOf("updated" to ok, "failed" to failed))
            processRuns()
        } finally {
            refreshing = false
        }
    }

    private fun merge(
        symbol: String,
        r: TsxFetch.Ok,
    ) {
        val old =
            db
                .sql("select bars, dividends from tsx_history where symbol = :s")
                .param("s", symbol)
                .firstOrNull { decodeRaw(it.str("bars"), it.str("dividends")) }
        val bars = sortedMapOf<LocalDate, TsxBar>()
        old?.first?.forEach { bars[it.day] = it }
        val firstNew = r.bars.first().day
        bars.keys.filter { it >= firstNew }.forEach { bars.remove(it) }
        r.bars.forEach { bars[it.day] = it }
        val divs = sortedMapOf<LocalDate, Double>()
        old?.second?.forEach { (d, a) -> divs[d] = a }
        r.dividends.forEach { (d, a) -> divs[d] = a }
        db
            .sql("insert or replace into tsx_history(symbol, bars, dividends, first_day, last_day, updated_at) values (:s, :b, :d, :f, :l, :u)")
            .param("s", symbol)
            .param("b", bars.values.joinToString("\n") { "${it.day},${it.close},${it.adj}" })
            .param("d", divs.entries.joinToString("\n") { "${it.key},${it.value}" })
            .param("f", bars.firstKey().toString())
            .param("l", bars.lastKey().toString())
            .param("u", wall.millis())
            .update()
    }

    /** Called by the scheduler: refreshes data at most every few hours while a run is active, then advances the runs. */
    fun tick() {
        val active = db.sql("select count(*) from tsx_runs where status = 'ACTIVE'").long() > 0
        val last = lastRefreshAt()
        if (active && !refreshing && (last == null || Duration.between(last, wall.instant()) > AUTO_REFRESH)) refresh()
        processRuns()
        if (active) refreshIntraday()
    }

    /** True from 9:30 to 16:15 Toronto time on weekdays (holidays simply return yesterday's prices). */
    fun sessionOpen(now: Instant = wall.instant()): Boolean {
        val t = now.atZone(YahooTsxProvider.TORONTO)
        if (t.dayOfWeek == java.time.DayOfWeek.SATURDAY || t.dayOfWeek == java.time.DayOfWeek.SUNDAY) return false
        val m = t.hour * 60 + t.minute
        return m in (9 * 60 + 30)..(16 * 60 + 15)
    }

    /** The listings active runs hold. */
    fun heldSymbols(): Set<String> =
        db
            .sql("select holdings from tsx_runs where status = 'ACTIVE'")
            .list {
                mapper
                    .readTree(it.str("holdings"))
                    .fieldNames()
                    .asSequence()
                    .toList()
            }.flatten()
            .toSortedSet()

    /**
     * Polls intraday prices, at most once a minute during the session, for held listings the stream
     * is not pricing (the stream is the main source when it runs).
     */
    fun refreshIntraday(): Boolean {
        val p = provider() ?: return false
        val now = wall.instant()
        if (intradayRunning || !sessionOpen(now)) return false
        if (intradayAt?.let { Duration.between(it, now) < INTRADAY_EVERY } == true) return false
        // Listings the stream is pricing need no polling.
        val symbols = heldSymbols().filter { streamTick(it) == null }
        if (symbols.isEmpty()) return false
        intradayRunning = true
        intradayAt = now
        background {
            try {
                for (s in symbols) {
                    runCatching { p.latest(s) }.getOrNull()?.let { intraday[s] = it }
                    if (pauseMs > 0) Thread.sleep(pauseMs / 2)
                }
            } finally {
                intradayRunning = false
            }
        }
        return true
    }

    // ------------------------------------------------------------------ runs

    fun create(
        planId: String,
        slot: Int?,
        startingCash: Double,
        drip: Boolean,
        mode: TsxMode,
    ): TsxRunView {
        val plan = TsxCatalog.plan(planId) ?: throw Problems.notFound("TSX plan", planId)
        if (startingCash < 100 || startingCash > 10_000_000) throw Problems.badRequest("invalid-amount", "Starting cash must be between C$100 and C$10,000,000")
        val used = activeSlots()
        val n = slot ?: (1..SLOTS).firstOrNull { it !in used } ?: throw Problems.conflict("slots-full", "All $SLOTS TSX slots are in use. Stop a plan first.")
        if (n !in 1..SLOTS) throw Problems.badRequest("invalid-slot", "Slot must be between 1 and $SLOTS")
        if (n in used) throw Problems.conflict("slot-occupied", "TSX slot $n is in use. Stop that plan first or choose another slot.")
        val data = data()
        val latest = data.calendar.lastOrNull() ?: throw Problems.conflict("no-tsx-data", "Load TSX data first (TSX plans, Refresh data).")
        val today = wall.instant().atZone(YahooTsxProvider.TORONTO).toLocalDate()
        if (latest.isBefore(today.minusDays(10))) throw Problems.conflict("stale-tsx-data", "TSX data ends on $latest. Refresh the data first.")
        val id = UUID.randomUUID()
        val book = Book(startingCash)
        val state = mutableMapOf<Int, SleevePick>()
        val targets = PlanRules(data, listings).targets(plan, latest, state)
        val events = mutableListOf<TsxEvent>()
        var pending: Map<String, Double>? = targets
        if (mode == TsxMode.AUTONOMOUS) {
            events += apply(book, latest, targets, data, plan)
            pending = null
        }
        db.tx {
            db
                .sql(
                    """
                    insert into tsx_runs(id, plan_id, slot, mode, drip, starting_cash, cash, holdings, sleeve_state, start_day, last_day, last_period,
                      pending, pending_day, status, created_at)
                    values (:id, :p, :slot, :m, :drip, :sc, :cash, :h, :st, :d, :d, :lp, :pend, :pd, 'ACTIVE', :now)
                    """.trimIndent(),
                ).param("id", id)
                .param("p", plan.id)
                .param("slot", n)
                .param("m", mode.name)
                .param("drip", if (drip) 1 else 0)
                .param("sc", money(startingCash))
                .param("cash", book.cash.toString())
                .param("h", holdingsJson(book))
                .param("st", stateJson(state))
                .param("d", latest.toString())
                .param("lp", plan.rebalance.period(latest))
                .param("pend", pending?.let { mapper.writeValueAsString(it) })
                .param("pd", pending?.let { latest.toString() })
                .param("now", wall.millis())
                .update()
            events.forEach { event(id, it) }
            value(id, latest, book.value())
            audit.record(AuditCategory.AUTONOMY, "TSX_PLAN_STARTED", entityType = "TsxRun", entityId = id, details = mapOf("plan" to plan.id, "slot" to n, "mode" to mode, "drip" to drip, "cash" to startingCash))
        }
        if (pending != null) proposeNotice(id, plan, n, latest, pending)
        return get(id)
    }

    fun list(): List<TsxRunView> = db.sql("select id from tsx_runs order by status = 'ACTIVE' desc, slot, created_at desc").list { UUID.fromString(it.str("id")) }.map { get(it) }

    fun get(id: UUID): TsxRunView =
        db
            .sql("select * from tsx_runs where id = :id")
            .param("id", id)
            .firstOrNull { r ->
                val book = book(r.str("cash"), r.str("holdings"))
                val lastDay = r.string("last_day")?.let(LocalDate::parse)
                val live = liveQuotes(book, lastDay)
                TsxRunView(
                    id = id,
                    planId = r.str("plan_id"),
                    planName = TsxCatalog.plan(r.str("plan_id"))?.name ?: r.str("plan_id"),
                    slot = r.int("slot"),
                    mode = TsxMode.valueOf(r.str("mode")),
                    drip = r.int("drip") == 1,
                    startingCash = r.str("starting_cash").toDouble(),
                    cash = book.cash,
                    value = book.value(),
                    dividendsReceived = dividendsReceived(id),
                    status = r.str("status"),
                    startDay = r.string("start_day")?.let(LocalDate::parse),
                    lastDay = r.string("last_day")?.let(LocalDate::parse),
                    holdings = book.shares.map { (s, n) -> TsxHolding(s, n, book.lastPx[s] ?: 0.0, live[s]?.price) }.sortedByDescending { it.shares * it.price },
                    pending = r.string("pending")?.let { readWeights(it) },
                    pendingDay = r.string("pending_day")?.let(LocalDate::parse),
                    createdAt = Instant.ofEpochMilli(r.long("created_at") ?: 0L),
                    liveValue = if (live.isEmpty() || r.str("status") != "ACTIVE") null else book.cash + book.shares.entries.sumOf { (s, n) -> n * (live[s]?.price ?: book.lastPx[s] ?: 0.0) },
                    liveAt = live.values.maxOfOrNull { it.at },
                )
            } ?: throw Problems.notFound("TSX plan run", id)

    /** Intraday prices for the book's holdings that are newer than the run's last close. */
    private fun liveQuotes(
        book: Book,
        lastDay: LocalDate?,
    ): Map<String, TsxQuote> =
        book.shares.keys
            .mapNotNull { s ->
                val streamed = streamTick(s)?.let { TsxQuote(it.last.toDouble(), it.exchangeTs, null) }
                listOfNotNull(streamed, intraday[s])
                    .maxByOrNull { it.at }
                    ?.takeIf { q ->
                        lastDay == null ||
                            q.at
                                .atZone(YahooTsxProvider.TORONTO)
                                .toLocalDate()
                                .isAfter(lastDay)
                    }?.let { s to it }
            }.toMap()

    fun values(id: UUID): List<SimPoint> =
        db
            .sql("select day, value from tsx_run_values where run_id = :id order by day")
            .param("id", id)
            .list { SimPoint(LocalDate.parse(it.str("day")), it.str("value").toDouble()) }

    fun events(
        id: UUID,
        limit: Int = 500,
    ): List<TsxEvent> =
        db
            .sql("select * from tsx_run_events where run_id = :id order by day desc, created_at desc limit :n")
            .param("id", id)
            .param("n", limit)
            .list { r -> TsxEvent(LocalDate.parse(r.str("day")), r.str("kind"), r.string("symbol"), r.string("shares")?.toDouble(), r.string("price")?.toDouble(), r.string("amount")?.toDouble()) }

    /** Dividends received per month (reinvested or paid out), keyed yyyy-MM. */
    fun monthlyDividends(id: UUID): Map<String, Double> =
        db
            .sql("select day, amount from tsx_run_events where run_id = :id and kind in ('DIVIDEND_PAID', 'DIVIDEND_REINVESTED')")
            .param("id", id)
            .list { it.str("day").take(7) to (it.string("amount")?.toDouble() ?: 0.0) }
            .groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
            .toSortedMap()

    private fun dividendsReceived(id: UUID): Double = monthlyDividends(id).values.sum()

    fun approve(id: UUID): TsxRunView {
        val run = get(id)
        val pending = run.pending ?: throw Problems.conflict("nothing-pending", "This plan has no rebalance waiting for approval.")
        if (run.status != "ACTIVE") throw Problems.conflict("run-stopped", "This plan is stopped.")
        val plan = TsxCatalog.plan(run.planId) ?: throw Problems.notFound("TSX plan", run.planId)
        val data = data()
        val day = data.calendar.lastOrNull() ?: throw Problems.conflict("no-tsx-data", "Load TSX data first.")
        db.tx {
            val (cash, holdings) = db.sql("select cash, holdings from tsx_runs where id = :id").param("id", id).single { it.str("cash") to it.str("holdings") }
            val book = book(cash, holdings)
            apply(book, day, pending, data, plan).forEach { event(id, it) }
            event(id, TsxEvent(day, "REBALANCE_APPROVED", null, null, null, null))
            save(id, book, null, null)
            value(id, day, book.value())
        }
        return get(id)
    }

    fun decline(id: UUID): TsxRunView {
        val run = get(id)
        if (run.pending == null) throw Problems.conflict("nothing-pending", "This plan has no rebalance waiting for approval.")
        db.tx {
            event(id, TsxEvent(run.lastDay ?: LocalDate.now(wall), "REBALANCE_DECLINED", null, null, null, null))
            db.sql("update tsx_runs set pending = null, pending_day = null where id = :id").param("id", id).update()
        }
        return get(id)
    }

    /**
     * Switches a running plan between notify-and-approve and autonomous (D-078). Turning autonomous
     * on applies a rebalance that was waiting for approval, as an autonomous run would have.
     */
    fun setMode(
        id: UUID,
        mode: TsxMode,
    ): TsxRunView {
        val run = get(id)
        if (run.status != "ACTIVE") throw Problems.conflict("run-stopped", "This plan is stopped.")
        if (run.mode == mode) return run
        if (mode == TsxMode.AUTONOMOUS && run.pending != null) approve(id)
        db.tx {
            db
                .sql("update tsx_runs set mode = :m where id = :id and status = 'ACTIVE'")
                .param("m", mode.name)
                .param("id", id)
                .update()
            audit.record(AuditCategory.AUTONOMY, "TSX_PLAN_MODE_CHANGED", entityType = "TsxRun", entityId = id, details = mapOf("from" to run.mode, "to" to mode))
        }
        return get(id)
    }

    fun stop(id: UUID): TsxRunView {
        get(id)
        db.tx {
            db
                .sql("update tsx_runs set status = 'STOPPED', stopped_at = :now, pending = null, pending_day = null where id = :id and status = 'ACTIVE'")
                .param("now", wall.millis())
                .param("id", id)
                .update()
            audit.record(AuditCategory.AUTONOMY, "TSX_PLAN_STOPPED", entityType = "TsxRun", entityId = id)
        }
        return get(id)
    }

    private fun activeSlots(): Set<Int> = db.sql("select slot from tsx_runs where status = 'ACTIVE'").list { it.int("slot") }.toSet()

    /** Advances every active run through the trading days it has not processed yet. */
    fun processRuns() {
        val data = data()
        val latest = data.calendar.lastOrNull() ?: return
        val runs =
            db
                .sql("select id, plan_id, slot, mode, drip, cash, holdings, sleeve_state, last_day, last_period, pending from tsx_runs where status = 'ACTIVE'")
                .list { r ->
                    RunRow(
                        UUID.fromString(r.str("id")),
                        r.str("plan_id"),
                        r.int("slot"),
                        TsxMode.valueOf(r.str("mode")),
                        r.int("drip") == 1,
                        r.str("cash"),
                        r.str("holdings"),
                        r.str("sleeve_state"),
                        r.string("last_day")?.let(LocalDate::parse),
                        r.intOrNull("last_period"),
                        r.string("pending"),
                    )
                }
        for (run in runs) {
            if (run.lastDay != null && !run.lastDay.isBefore(latest)) continue
            runCatching { advance(run, data, latest) }.onFailure { log.error("TSX run {} could not advance", run.id, it) }
        }
    }

    private data class RunRow(
        val id: UUID,
        val planId: String,
        val slot: Int,
        val mode: TsxMode,
        val drip: Boolean,
        val cash: String,
        val holdings: String,
        val state: String,
        val lastDay: LocalDate?,
        val lastPeriod: Int?,
        val pending: String?,
    )

    private fun advance(
        run: RunRow,
        data: TsxData,
        latest: LocalDate,
    ) {
        val plan = TsxCatalog.plan(run.planId) ?: return
        val rules = PlanRules(data, listings)
        val book = book(run.cash, run.holdings)
        val state = readState(run.state)
        var lastPeriod = run.lastPeriod
        var pending = run.pending?.let { readWeights(it) }
        var pendingDay: LocalDate? = null
        val proposals = mutableListOf<Pair<LocalDate, Map<String, Double>>>()
        db.tx {
            for (day in data.calendar.filter { (run.lastDay == null || it > run.lastDay) && it <= latest }) {
                val paid =
                    book.dailyStep(day, data, run.drip, DELIST_GRACE_DAYS) { s, proceeds ->
                        event(run.id, TsxEvent(day, "TAKEN_OVER_OR_DELISTED", s, null, null, proceeds))
                    }
                paid.forEach { event(run.id, TsxEvent(day, if (it.reinvested) "DIVIDEND_REINVESTED" else "DIVIDEND_PAID", it.symbol, null, data.close(it.symbol, day), it.amount)) }
                val period = plan.rebalance.period(day)
                if (period != lastPeriod) {
                    val targets = rules.targets(plan, day, state)
                    if (run.mode == TsxMode.AUTONOMOUS) {
                        apply(book, day, targets, data, plan).forEach { event(run.id, it) }
                    } else {
                        pending = targets
                        pendingDay = day
                        proposals += day to targets
                        event(run.id, TsxEvent(day, "REBALANCE_PROPOSED", null, null, null, null))
                    }
                    lastPeriod = period
                }
                // Runs started before D-062 paid their first fees out of cash; bring it back to zero.
                book.coverNegativeCash(day, data, plan.cost).forEach { t ->
                    event(run.id, TsxEvent(day, "SELL", t.symbol, kotlin.math.abs(t.shares), t.price, kotlin.math.abs(t.shares * t.price)))
                }
                value(run.id, day, book.value())
            }
            db
                .sql("update tsx_runs set cash = :c, holdings = :h, sleeve_state = :st, last_day = :d, last_period = :lp, pending = :p, pending_day = coalesce(:pd, pending_day) where id = :id")
                .param("c", book.cash.toString())
                .param("h", holdingsJson(book))
                .param("st", stateJson(state))
                .param("d", latest.toString())
                .param("lp", lastPeriod)
                .param("p", pending?.let { mapper.writeValueAsString(it) })
                .param("pd", pendingDay?.toString())
                .param("id", run.id)
                .update()
        }
        proposals.lastOrNull()?.let { (day, t) -> proposeNotice(run.id, plan, run.slot, day, t) }
    }

    /** Re-weights the book at [day]'s closes and returns the trades as events. */
    private fun apply(
        book: Book,
        day: LocalDate,
        targets: Map<String, Double>,
        data: TsxData,
        plan: PortfolioPlan,
    ): List<TsxEvent> {
        val (trades, _) = book.rebalance(day, targets, data, plan.cost)
        return trades.map { TsxEvent(day, if (it.shares > 0) "BUY" else "SELL", it.symbol, kotlin.math.abs(it.shares), it.price, kotlin.math.abs(it.shares * it.price)) }
    }

    private fun proposeNotice(
        id: UUID,
        plan: PortfolioPlan,
        slot: Int,
        day: LocalDate,
        targets: Map<String, Double>,
    ) {
        val top =
            targets.entries
                .sortedByDescending { it.value }
                .take(6)
                .joinToString { "${it.key.replace('-', '.')} ${"%.0f".format(it.value * 100)}%" }
        notifications.notify(
            NotificationCategory.RECOMMENDATION,
            Severity.INFO,
            "TSX slot $slot: rebalance ready",
            "${plan.name} has new target weights for $day ($top${if (targets.size > 6) ", …" else ""}). Open TSX plans to approve or decline.",
            entityType = "TsxRun",
            entityId = id,
            dedupeKey = "tsx-$id-$day",
        )
    }

    private fun save(
        id: UUID,
        book: Book,
        pending: Map<String, Double>?,
        pendingDay: LocalDate?,
    ) {
        db
            .sql("update tsx_runs set cash = :c, holdings = :h, pending = :p, pending_day = :pd where id = :id")
            .param("c", book.cash.toString())
            .param("h", holdingsJson(book))
            .param("p", pending?.let { mapper.writeValueAsString(it) })
            .param("pd", pendingDay?.toString())
            .param("id", id)
            .update()
    }

    private fun event(
        run: UUID,
        e: TsxEvent,
    ) {
        db
            .sql("insert into tsx_run_events(id, run_id, day, kind, symbol, shares, price, amount, created_at) values (:id, :r, :d, :k, :s, :sh, :p, :a, :now)")
            .param("id", UUID.randomUUID())
            .param("r", run)
            .param("d", e.day.toString())
            .param("k", e.kind)
            .param("s", e.symbol)
            .param("sh", e.shares?.toString())
            .param("p", e.price?.toString())
            .param("a", e.amount?.let { money(it) })
            .param("now", wall.millis())
            .update()
    }

    private fun value(
        run: UUID,
        day: LocalDate,
        v: Double,
    ) {
        db
            .sql("insert or replace into tsx_run_values(run_id, day, value) values (:r, :d, :v)")
            .param("r", run)
            .param("d", day.toString())
            .param("v", money(v))
            .update()
    }

    // ------------------------------------------------------------------ backtests

    /** Runs a plan over the stored history in the background, both with DRIP and with dividends paid out. */
    fun backtest(
        planId: String,
        from: LocalDate,
        to: LocalDate,
        startingCash: Double,
    ): UUID {
        val plan = TsxCatalog.plan(planId) ?: throw Problems.notFound("TSX plan", planId)
        val data = data()
        if (data.calendar.isEmpty()) throw Problems.conflict("no-tsx-data", "Load TSX data first (TSX plans, Refresh data).")
        if (!from.isBefore(to)) throw Problems.badRequest("invalid-range", "The start must be before the end")
        if (startingCash < 100 || startingCash > 10_000_000) throw Problems.badRequest("invalid-amount", "Starting cash must be between C$100 and C$10,000,000")
        val id = UUID.randomUUID()
        db
            .sql("insert into tsx_backtests(id, plan_id, from_day, to_day, starting_cash, status, created_at) values (:id, :p, :f, :t, :c, 'RUNNING', :now)")
            .param("id", id)
            .param("p", planId)
            .param("f", from.toString())
            .param("t", to.toString())
            .param("c", money(startingCash))
            .param("now", wall.millis())
            .update()
        background {
            val result = runCatching { backtestResult(plan, data, from, to, startingCash) }
            engineThread {
                db
                    .sql("update tsx_backtests set status = :s, result = :r, error = :e where id = :id")
                    .param("s", if (result.isSuccess) "COMPLETED" else "FAILED")
                    .param("r", result.getOrNull()?.let { mapper.writeValueAsString(it) })
                    .param("e", result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName })
                    .param("id", id)
                    .update()
            }
        }
        return id
    }

    fun backtestResult(
        plan: PortfolioPlan,
        data: TsxData,
        from: LocalDate,
        to: LocalDate,
        startingCash: Double,
    ): ObjectNode {
        val drip = PlanSimulator.run(plan, data, listings, from, to, startingCash, true)
        val paid = PlanSimulator.run(plan, data, listings, from, to, startingCash, false)
        val m = PlanSimulator.metrics(drip.values)
        val months = PlanSimulator.monthEnds(drip.values)
        val paidMonths = PlanSimulator.monthEnds(paid.values).associateBy { it.day.year * 100 + it.day.monthValue }
        val divMonths = PlanSimulator.monthlyDividends(paid.dividends)
        val out = mapper.createObjectNode()
        out.put("planId", plan.id)
        out.put(
            "from",
            drip.values
                .firstOrNull()
                ?.day
                ?.toString(),
        )
        out.put(
            "to",
            drip.values
                .lastOrNull()
                ?.day
                ?.toString(),
        )
        out.put("startingCash", startingCash)
        val mo = out.putArray("months")
        val vd = out.putArray("valueDrip")
        val vp = out.putArray("valuePaidOut")
        val dp = out.putArray("dividendsPaid")
        months.forEach { p ->
            val key = "%04d-%02d".format(p.day.year, p.day.monthValue)
            mo.add(key)
            vd.add(round2(p.value))
            vp.add(round2(paidMonths[p.day.year * 100 + p.day.monthValue]?.value ?: 0.0))
            dp.add(round2(divMonths[key] ?: 0.0))
        }
        out.put("perYear", round2(m.cagrPercent))
        out.put("totalPercent", round2(m.totalPercent))
        out.put("maxDrawdown", round2(m.maxDrawdownPercent))
        m.worst12mPercent?.let { out.put("worst12m", round2(it)) }
        m.positive12mPercent?.let { out.put("positive12m", round2(it)) }
        out.put("endDrip", round2(drip.values.lastOrNull()?.value ?: startingCash))
        out.put("endPaidOut", round2(paid.values.lastOrNull()?.value ?: startingCash))
        out.put("dividendsTotal", round2(paid.dividends.sumOf { it.amount }))
        val lastDay = paid.values.lastOrNull()?.day
        out.put("dividendsLast12m", round2(paid.dividends.filter { lastDay != null && it.day > lastDay.minusYears(1) }.sumOf { it.amount }))
        out.put("trades", drip.trades)
        val yrs = out.putObject("years")
        drip.values.groupBy { it.day.year }.forEach { (y, pts) -> yrs.put(y.toString(), round2(100 * (pts.last().value / pts.first().value - 1))) }
        val tg = out.putObject("lastTargets")
        drip.lastTargets.entries
            .sortedByDescending { it.value }
            .forEach { (s, w) -> tg.put(s, round2(w * 100)) }
        return out
    }

    fun backtestView(id: UUID): ObjectNode =
        db
            .sql("select * from tsx_backtests where id = :id")
            .param("id", id)
            .firstOrNull { r ->
                mapper.createObjectNode().apply {
                    put("id", id.toString())
                    put("planId", r.str("plan_id"))
                    put("from", r.str("from_day"))
                    put("to", r.str("to_day"))
                    put("status", r.str("status"))
                    r.string("error")?.let { put("error", it) }
                    r.string("result")?.let { set<ObjectNode>("result", mapper.readTree(it)) }
                }
            } ?: throw Problems.notFound("TSX backtest", id)

    fun backtests(planId: String?): List<ObjectNode> =
        db
            .sql("select id from tsx_backtests where (:p is null or plan_id = :p) order by created_at desc limit 20")
            .param("p", planId)
            .list { UUID.fromString(it.str("id")) }
            .map { backtestView(it) }

    // ------------------------------------------------------------------ encoding

    private fun book(
        cash: String,
        holdings: String,
    ): Book {
        val b = Book(cash.toDouble())
        val n = mapper.readTree(holdings)
        n.properties().forEach { (s, v) ->
            b.shares[s] = v.path(0).asDouble()
            b.lastPx[s] = v.path(1).asDouble()
        }
        return b
    }

    private fun holdingsJson(b: Book): String {
        val o = mapper.createObjectNode()
        b.shares.forEach { (s, n) -> o.putArray(s).add(n).add(b.lastPx[s] ?: 0.0) }
        return mapper.writeValueAsString(o)
    }

    private fun stateJson(state: Map<Int, SleevePick>): String {
        val o = mapper.createObjectNode()
        state.forEach { (i, p) ->
            val n = o.putObject(i.toString())
            n.put("period", p.period)
            val w = n.putObject("weights")
            p.weights.forEach { (s, x) -> w.put(s, x) }
        }
        return mapper.writeValueAsString(o)
    }

    private fun readState(json: String): MutableMap<Int, SleevePick> =
        mapper
            .readTree(json)
            .properties()
            .associate { (k, v) -> k.toInt() to SleevePick(v.path("period").asInt(), v.path("weights").properties().associate { (s, x) -> s to x.asDouble() }) }
            .toMutableMap()

    private fun readWeights(json: String): Map<String, Double> = mapper.readTree(json).properties().associate { (k, v) -> k to v.asDouble() }

    companion object {
        const val SLOTS = 10
        val DATA_START: LocalDate = LocalDate.parse("2014-10-01")
        const val REFRESH_KEY = "tsx_last_refresh"
        val AUTO_REFRESH: Duration = Duration.ofHours(6)

        /** Intraday prices are display-only and refreshed at most this often during the session (D-056). */
        val INTRADAY_EVERY: Duration = Duration.ofMinutes(1)

        /** Trading days a held listing may go without prices before it is treated as taken over. */
        const val DELIST_GRACE_DAYS = 10

        private fun money(v: Double): String =
            java.math
                .BigDecimal(v)
                .setScale(2, java.math.RoundingMode.HALF_EVEN)
                .toPlainString()

        private fun round2(v: Double): Double = Math.round(v * 100) / 100.0

        fun decodeRaw(
            bars: String,
            dividends: String,
        ): Pair<List<TsxBar>, Map<LocalDate, Double>> {
            val b =
                bars
                    .lineSequence()
                    .filter { it.isNotBlank() }
                    .map { l ->
                        val f = l.split(',')
                        TsxBar(LocalDate.parse(f[0]), f[1].toDouble(), f[2].toDouble())
                    }.toList()
            val d =
                dividends.lineSequence().filter { it.isNotBlank() }.associate { l ->
                    val f = l.split(',')
                    LocalDate.parse(f[0]) to f[1].toDouble()
                }
            return b to d
        }

        fun decode(
            bars: String,
            dividends: String,
        ): TsxSeries {
            val (b, d) = decodeRaw(bars, dividends)
            return TsxSeries(b.map { it.day }, b.map { it.close }.toDoubleArray(), b.map { it.adj }.toDoubleArray(), d)
        }
    }
}
