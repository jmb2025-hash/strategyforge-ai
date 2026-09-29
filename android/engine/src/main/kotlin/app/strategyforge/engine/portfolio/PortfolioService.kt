package app.strategyforge.engine.portfolio

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineEvents
import app.strategyforge.engine.common.EngineJson
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.RecentAuth
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.bool
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.db.uuidOrNull
import app.strategyforge.engine.market.DataStatus
import app.strategyforge.engine.market.InstrumentService
import app.strategyforge.engine.market.MarketCalendar
import app.strategyforge.engine.market.MarketClock
import app.strategyforge.engine.market.MarketDataService
import app.strategyforge.engine.market.SessionState
import app.strategyforge.engine.money.Decimals
import app.strategyforge.engine.settings.SettingsService
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class PortfolioCreate(
    val name: String,
    val startingBalance: BigDecimal? = null,
    val costModel: CostModel? = null,
)

data class PortfolioUpdate(
    val name: String,
    val costModel: CostModel,
)

/** Published after lifecycle changes that must invalidate autonomy authorization (FR-072). */
data class PortfolioChanged(
    val portfolioId: UUID,
    val change: String,
)

class PortfolioService(
    private val db: Db,
    private val ledger: LedgerService,
    private val settings: SettingsService,
    private val audit: AuditService,
    private val clock: Clock,
    private val marketClock: MarketClock,
    private val market: MarketDataService,
    private val instruments: InstrumentService,
    private val events: EngineEvents,
    private val auth: RecentAuth,
) {
    fun list(includeArchived: Boolean): List<Portfolio> =
        db
            .sql("select * from portfolios where (:all = 1 or status = 'ACTIVE') order by created_at")
            .param("all", includeArchived)
            .list { rs -> map(rs) }

    fun get(id: UUID): Portfolio = find(id) ?: throw Problems.notFound("Portfolio", id)

    fun find(id: UUID): Portfolio? =
        db
            .sql("select * from portfolios where id = :id")
            .param("id", id)
            .firstOrNull { rs -> map(rs) }

    fun create(req: PortfolioCreate): Portfolio =
        db.tx {
            val defaults = settings.get().portfolioDefaults
            val start = req.startingBalance ?: defaults.startingBalance
            val model = req.costModel ?: defaults.costModel()
            createInternal(req.name, start, model, false, null, null, "PORTFOLIO_CREATED")
        }

    private fun createInternal(
        name: String,
        start: BigDecimal,
        model: CostModel,
        shorting: Boolean,
        clonedFrom: UUID?,
        resetFrom: UUID?,
        action: String,
    ): Portfolio {
        validateName(name)
        validateBalance(start)
        validateCostModel(model)
        val active = db.sql("select count(*) n from portfolios where status = 'ACTIVE'").int()
        if (active >= MAX_ACTIVE) throw Problems.unprocessable("portfolio-limit", "At most $MAX_ACTIVE active paper portfolios are allowed")
        val exists =
            db
                .sql("select count(*) n from portfolios where status = 'ACTIVE' and lower(name) = lower(:n)")
                .param("n", name.trim())
                .int()
        if (exists > 0) throw Problems.conflict("portfolio-name-taken", "An active portfolio named '$name' already exists")
        val id = UUID.randomUUID()
        val now = clock.instant()
        db
            .sql(
                """
                insert into portfolios(id, name, status, starting_balance, cost_model, shorting_enabled, cloned_from, reset_from, created_at)
                values (:id, :n, 'ACTIVE', :s, :cm, :sh, :cf, :rf, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("n", name.trim())
            .param("s", Decimals.money(start))
            .param("cm", EngineJson.encodeToString(CostModel.serializer(), model))
            .param("sh", shorting)
            .param("cf", clonedFrom)
            .param("rf", resetFrom)
            .param("now", (now))
            .update()
        ledger.post(
            id,
            JournalType.FUNDING,
            marketClock.now(),
            "Initial paper funding",
            listOf(Posting(Account.CASH, start), Posting(Account.OWNER_CAPITAL, start.negate())),
            "Portfolio",
            id,
        )
        recordEquity(id, "FUNDING")
        audit.record(AuditCategory.PORTFOLIO, action, entityType = "Portfolio", entityId = id, details = mapOf("name" to name, "startingBalance" to start, "clonedFrom" to clonedFrom, "resetFrom" to resetFrom))
        return get(id)
    }

    fun update(
        id: UUID,
        req: PortfolioUpdate,
        expectedVersion: Long,
    ): Portfolio = db.tx { updateInTx(id, req, expectedVersion) }

    private fun updateInTx(
        id: UUID,
        req: PortfolioUpdate,
        expectedVersion: Long,
    ): Portfolio {
        val p = requireActive(id)
        if (p.version != expectedVersion) throw Problems.preconditionFailed("Portfolio changed; reload and retry")
        validateName(req.name)
        validateCostModel(req.costModel)
        if (!req.name.trim().equals(p.name, ignoreCase = true)) {
            val exists =
                db
                    .sql("select count(*) n from portfolios where status = 'ACTIVE' and lower(name) = lower(:n) and id <> :id")
                    .param("n", req.name.trim())
                    .param("id", id)
                    .int()
            if (exists > 0) throw Problems.conflict("portfolio-name-taken", "An active portfolio named '${req.name}' already exists")
        }
        if (riskIncrease(p.costModel, req.costModel)) auth.require("portfolio-risk-increase")
        val n =
            db
                .sql("update portfolios set name = :n, cost_model = :cm, version = version + 1 where id = :id and version = :v")
                .param("n", req.name.trim())
                .param("cm", EngineJson.encodeToString(CostModel.serializer(), req.costModel))
                .param("id", id)
                .param("v", p.version)
                .update()
        if (n == 0) throw Problems.preconditionFailed("Portfolio changed concurrently")
        audit.record(AuditCategory.PORTFOLIO, "PORTFOLIO_UPDATED", entityType = "Portfolio", entityId = id, details = mapOf("name" to req.name, "before" to p.costModel, "after" to req.costModel))
        if (p.costModel != req.costModel) events.publish(PortfolioChanged(id, "COST_MODEL"))
        return get(id)
    }

    /** Enabling simulated shorting is a risk increase and requires recent authentication (section 15). */
    fun setShorting(
        id: UUID,
        enabled: Boolean,
    ): Portfolio = db.tx { setShortingInTx(id, enabled) }

    private fun setShortingInTx(
        id: UUID,
        enabled: Boolean,
    ): Portfolio {
        val p = requireActive(id)
        if (enabled) auth.require("enable-shorting")
        if (!enabled && ledger.positions(id).any { it.quantity.signum() < 0 }) {
            throw Problems.conflict("open-short-positions", "Cover all short positions before disabling shorting")
        }
        db
            .sql("update portfolios set shorting_enabled = :e, version = version + 1 where id = :id")
            .param("e", enabled)
            .param("id", id)
            .update()
        audit.record(AuditCategory.PORTFOLIO, if (enabled) "SHORTING_ENABLED" else "SHORTING_DISABLED", entityType = "Portfolio", entityId = id)
        if (enabled != p.shortingEnabled) events.publish(PortfolioChanged(id, "SHORTING"))
        return get(id)
    }

    fun archive(id: UUID): Portfolio = db.tx { archiveInTx(id) }

    private fun archiveInTx(id: UUID): Portfolio {
        requireActive(id)
        val open =
            db
                .sql("select count(*) n from paper_orders where portfolio_id = :p and status in ('CREATED','VALIDATED','PENDING','PARTIALLY_FILLED')")
                .param("p", id)
                .int()
        if (open > 0) throw Problems.conflict("open-orders", "Cancel $open open paper order(s) before archiving")
        archiveInternal(id, "PORTFOLIO_ARCHIVED")
        return get(id)
    }

    private fun archiveInternal(
        id: UUID,
        action: String,
    ) {
        db
            .sql("update portfolios set status = 'ARCHIVED', archived_at = :now, version = version + 1 where id = :id")
            .param("now", (clock.instant()))
            .param("id", id)
            .update()
        audit.record(AuditCategory.PORTFOLIO, action, entityType = "Portfolio", entityId = id)
        events.publish(PortfolioChanged(id, "ARCHIVED"))
    }

    /** Clone copies configuration (starting balance, costs, shorting flag) into a fresh ledger; no history is copied. */
    fun clone(
        id: UUID,
        name: String,
    ): Portfolio = db.tx { cloneInTx(id, name) }

    private fun cloneInTx(
        id: UUID,
        name: String,
    ): Portfolio {
        val src = get(id)
        if (src.shortingEnabled) auth.require("enable-shorting")
        return createInternal(name, src.startingBalance, src.costModel, src.shortingEnabled, src.id, null, "PORTFOLIO_CLONED")
    }

    /** Reset archives the existing portfolio (ledger preserved) and creates a new one with a new ledger (FR-015). */
    fun reset(
        id: UUID,
        confirm: String?,
    ): Portfolio = db.tx { resetInTx(id, confirm) }

    private fun resetInTx(
        id: UUID,
        confirm: String?,
    ): Portfolio {
        if (confirm != "RESET") throw Problems.badRequest("confirmation-required", "Send confirm = \"RESET\" to reset this portfolio")
        val p = requireActive(id)
        val open =
            db
                .sql("select count(*) n from paper_orders where portfolio_id = :p and status in ('CREATED','VALIDATED','PENDING','PARTIALLY_FILLED')")
                .param("p", id)
                .int()
        if (open > 0) throw Problems.conflict("open-orders", "Cancel $open open paper order(s) before resetting")
        archiveInternal(id, "PORTFOLIO_ARCHIVED_FOR_RESET")
        return createInternal(p.name, p.startingBalance, p.costModel, false, null, p.id, "PORTFOLIO_RESET")
    }

    fun requireActive(id: UUID): Portfolio {
        val p = get(id)
        if (p.status != "ACTIVE") throw Problems.conflict("portfolio-archived", "Portfolio ${p.name} is archived")
        return p
    }

    /** Portfolio valuation from the ledger plus verified quotes (FR-014). Unpriced positions are flagged, not guessed. */
    fun summary(id: UUID): PortfolioSummary {
        val p = get(id)
        val b = ledger.balances(id)
        val positions = positionViews(p)
        val cash = b.getValue(Account.CASH)
        val reserved = b.getValue(Account.CASH_RESERVED)
        val mv = positions.mapNotNull { it.marketValue }.fold(BigDecimal.ZERO, BigDecimal::add)
        val cost = positions.fold(BigDecimal.ZERO) { a, x -> a.add(x.costBasis) }
        val unreal = positions.mapNotNull { it.unrealizedPnl }.fold(BigDecimal.ZERO, BigDecimal::add)
        val fullyPriced = positions.all { it.priceStatus == DataStatus.VERIFIED }
        // Unpriced positions are carried at cost for equity so equity is never inflated by guesses.
        val valued = mv.add(positions.filter { it.marketValue == null }.fold(BigDecimal.ZERO) { a, x -> a.add(x.costBasis) })
        val equity = cash.add(reserved).add(valued)
        val shortReq = shortRequirement(p, positions)
        val totalReturn = equity.subtract(p.startingBalance)
        return PortfolioSummary(
            portfolio = p,
            cash = cash,
            reservedCash = reserved,
            buyingPower = cash.subtract(shortReq).max(BigDecimal.ZERO),
            shortRequirement = shortReq,
            costBasis = cost,
            marketValue = mv,
            equity = equity,
            realizedPnl = b.getValue(Account.REALIZED_PNL).negate(),
            unrealizedPnl = unreal,
            fees = b.getValue(Account.FEES),
            borrowFees = b.getValue(Account.BORROW_FEES),
            dividends = b.getValue(Account.DIVIDENDS).negate(),
            totalReturn = totalReturn,
            totalReturnPercent = Decimals.ratioPercent(totalReturn, p.startingBalance),
            fullyPriced = fullyPriced,
            positions = positions,
            asOf = marketClock.now(),
        )
    }

    /** Cash that must stay held against open shorts: |short market value| x (1 + initial margin). */
    fun shortRequirement(
        p: Portfolio,
        positions: List<PositionView>,
    ): BigDecimal =
        positions.filter { it.quantity.signum() < 0 }.fold(BigDecimal.ZERO) { a, x ->
            val v = (x.marketValue ?: x.costBasis).abs()
            a.add(v.multiply(BigDecimal.ONE.add(p.costModel.shortInitialMarginPercent.divide(Decimals.HUNDRED))))
        }

    fun positionViews(p: Portfolio): List<PositionView> {
        val positions = ledger.positions(p.id)
        if (positions.isEmpty()) return emptyList()
        val byId = instruments.byIds(positions.map { it.instrumentId }).associateBy { it.id }
        val now = marketClock.now()
        val mrr =
            db
                .sql("select instrument_id from corporate_action_coverage where status = 'UNAVAILABLE'")
                .list { it.uuid("instrument_id") }
                .toSet()
        return positions.filter { it.quantity.signum() != 0 }.map { pos ->
            val i = byId.getValue(pos.instrumentId)
            // While a market is closed, the last session's closing print is a verified valuation mark.
            val closedSince =
                if (MarketCalendar.state(i.assetClass, now) == SessionState.CLOSED) {
                    MarketCalendar
                        .currentOrPreviousSession(now)
                        ?.close
                        ?.takeIf { !it.isAfter(now) }
                } else {
                    null
                }
            val maxAge =
                closedSince?.let {
                    Duration
                        .between(it, now)
                        .seconds + VALUATION_CLOSE_GRACE
                } ?: VALUATION_MAX_AGE
            val v = market.verifyQuote(i, maxAge.coerceAtLeast(p.costModel.executionMaxQuoteAgeSeconds), now)
            val q = v.quote
            val price = q?.let { if (pos.quantity.signum() > 0) it.bid ?: it.last else it.ask ?: it.last }
            val mv = price?.let { Decimals.money(pos.quantity.multiply(it)) }
            PositionView(
                i.id,
                i.symbol,
                i.assetClass.name,
                if (pos.quantity.signum() > 0) "LONG" else "SHORT",
                pos.quantity,
                pos.cost,
                Decimals.price(pos.cost.divide(pos.quantity, Decimals.MC)),
                price,
                mv,
                mv?.subtract(pos.cost),
                q?.exchangeTs,
                v.status,
                i.assetClass.name == "US_EQUITY" && i.id in mrr,
            )
        }
    }

    /** Appends an equity snapshot used for drawdown and history (valued at bid/ask or last). */
    fun recordEquity(
        id: UUID,
        source: String,
    ) {
        val s = summary(id)
        db
            .sql("insert into portfolio_equity_snapshots(portfolio_id, at, equity, cash, positions_value, priced, source) values (:p, :at, :e, :c, :v, :pr, :s)")
            .param("p", id)
            .param("at", (marketClock.now()))
            .param("e", Decimals.money(s.equity))
            .param("c", Decimals.money(s.cash.add(s.reservedCash)))
            .param("v", Decimals.money(s.marketValue))
            .param("pr", s.fullyPriced)
            .param("s", source)
            .update()
    }

    /**
     * Records a chart snapshot for each active portfolio at most once per [every] of market time
     * (D-038). Tagged [PERIODIC] so risk limits keep using trade and funding snapshots only.
     */
    fun recordPeriodicEquity(every: Duration = Duration.ofMinutes(5)) {
        val now = marketClock.now()
        list(false).forEach { p ->
            val last =
                db
                    .sql("select max(at) at from portfolio_equity_snapshots where portfolio_id = :p")
                    .param("p", p.id)
                    .firstOrNull { it.instantOrNull("at") }
            if (last == null || !now.isBefore(last.plus(every))) runCatching { recordEquity(p.id, PERIODIC) }
        }
    }

    fun equityHistory(
        id: UUID,
        limit: Int,
    ): List<Map<String, Any?>> =
        db
            .sql("select * from portfolio_equity_snapshots where portfolio_id = :p order by at desc, id desc limit :l")
            .param("p", id)
            .param("l", limit)
            .list { rs -> mapOf("at" to rs.instant("at"), "equity" to rs.dec("equity"), "cash" to rs.dec("cash"), "positionsValue" to rs.dec("positions_value"), "priced" to rs.bool("priced"), "source" to rs.str("source")) }
            .reversed()

    /** Highest priced equity snapshot; compared in Kotlin because amounts are decimal text. */
    fun peakEquity(id: UUID): BigDecimal? =
        db
            .sql("select equity from portfolio_equity_snapshots where portfolio_id = :p and priced = 1 and source <> '$PERIODIC'")
            .param("p", id)
            .list { it.dec("equity") }
            .maxOrNull()

    private fun riskIncrease(
        old: CostModel,
        new: CostModel,
    ) = new.shortInitialMarginPercent < old.shortInitialMarginPercent || new.shortMaintenancePercent < old.shortMaintenancePercent ||
        new.maxPriceDeviationPercent > old.maxPriceDeviationPercent || new.participationRatePercent > old.participationRatePercent

    private fun validateName(n: String) {
        if (n.isBlank() || n.trim().length > 60) throw Problems.badRequest("invalid-name", "Portfolio name must be 1-60 characters")
    }

    private fun validateBalance(b: BigDecimal) {
        if (b < BigDecimal("100") || b > BigDecimal("100000000")) throw Problems.badRequest("invalid-starting-balance", "Starting balance must be between 100 and 100,000,000 USD")
    }

    private fun validateCostModel(m: CostModel) {
        fun range(
            name: String,
            v: BigDecimal,
            min: String,
            max: String,
        ) {
            if (v < BigDecimal(min) || v > BigDecimal(max)) throw Problems.badRequest("invalid-cost-model", "$name must be between $min and $max")
        }
        range("commissionPerOrder", m.commissionPerOrder, "0", "1000")
        range("commissionPerShare", m.commissionPerShare, "0", "10")
        range("commissionPercent", m.commissionPercent, "0", "5")
        range("slippageBps", m.slippageBps, "0", "500")
        range("equityFallbackSpreadPercent", m.equityFallbackSpreadPercent, "0.01", "5")
        range("cryptoFallbackSpreadPercent", m.cryptoFallbackSpreadPercent, "0.01", "10")
        range("participationRatePercent", m.participationRatePercent, "0.1", "100")
        range("borrowRateAnnualPercent", m.borrowRateAnnualPercent, "0", "100")
        range("shortInitialMarginPercent", m.shortInitialMarginPercent, "50", "300")
        range("shortMaintenancePercent", m.shortMaintenancePercent, "25", "200")
        range("maxPriceDeviationPercent", m.maxPriceDeviationPercent, "0.1", "10")
        range("reservationBufferPercent", m.reservationBufferPercent, "0", "20")
        if (m.executionDelaySeconds !in 0..300) throw Problems.badRequest("invalid-cost-model", "executionDelaySeconds must be 0-300")
        if (m.executionMaxQuoteAgeSeconds !in 1..3600) throw Problems.badRequest("invalid-cost-model", "executionMaxQuoteAgeSeconds must be 1-3600")
    }

    private fun map(rs: Row) =
        Portfolio(
            rs.uuid("id"),
            rs.str("name"),
            rs.str("account_type"),
            rs.str("status"),
            rs.str("base_currency"),
            rs.dec("starting_balance"),
            EngineJson.decodeFromString(CostModel.serializer(), rs.str("cost_model")),
            rs.bool("shorting_enabled"),
            rs.uuidOrNull("cloned_from"),
            rs.uuidOrNull("reset_from"),
            rs.str("reconciliation_status"),
            rs.instant("created_at"),
            rs.instantOrNull("archived_at"),
            rs.long("version")!!,
        )

    companion object {
        const val MAX_ACTIVE = 10

        /** Snapshot source for chart-only periodic snapshots (D-038). */
        const val PERIODIC = "PERIODIC"
        const val VALUATION_MAX_AGE = 3600L
        const val VALUATION_CLOSE_GRACE = 120L
    }
}
