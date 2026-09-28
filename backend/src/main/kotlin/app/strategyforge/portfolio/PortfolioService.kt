package app.strategyforge.portfolio

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.db.uuidOrNull
import app.strategyforge.common.money.Decimals
import app.strategyforge.common.web.ETags
import app.strategyforge.common.web.Problems
import app.strategyforge.identity.RecentAuth
import app.strategyforge.market.InstrumentService
import app.strategyforge.market.MarketClock
import app.strategyforge.market.data.DataStatus
import app.strategyforge.market.data.MarketDataService
import app.strategyforge.settings.PortfolioDefaults
import app.strategyforge.settings.SettingsService
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Execution cost and short-selling assumptions (section 3 fees/slippage, shorting). */
data class CostModel(
    val commissionPerOrder: BigDecimal = BigDecimal.ZERO,
    val commissionPerShare: BigDecimal = BigDecimal.ZERO,
    val commissionPercent: BigDecimal = BigDecimal.ZERO,
    val slippageBps: BigDecimal = BigDecimal("2"),
    val equityFallbackSpreadPercent: BigDecimal = BigDecimal("0.10"),
    val cryptoFallbackSpreadPercent: BigDecimal = BigDecimal("0.20"),
    val participationRatePercent: BigDecimal = BigDecimal("10"),
    val executionDelaySeconds: Int = 1,
    val borrowRateAnnualPercent: BigDecimal = BigDecimal("3.0"),
    val shortInitialMarginPercent: BigDecimal = BigDecimal("50"),
    val shortMaintenancePercent: BigDecimal = BigDecimal("30"),
    val maxPriceDeviationPercent: BigDecimal = BigDecimal("1.0"),
    val executionMaxQuoteAgeSeconds: Long = 60,
    val reservationBufferPercent: BigDecimal = BigDecimal("2"),
) {
    companion object {
        fun from(d: PortfolioDefaults) =
            CostModel(
                d.commissionPerOrder,
                d.commissionPerShare,
                d.commissionPercent,
                d.slippageBps,
                d.equityFallbackSpreadPercent,
                d.cryptoFallbackSpreadPercent,
                d.participationRatePercent,
                d.executionDelaySeconds,
                d.borrowRateAnnualPercent,
                d.shortInitialMarginPercent,
                d.shortMaintenancePercent,
                d.maxPriceDeviationPercent,
            )
    }
}

data class Portfolio(
    val id: UUID,
    val name: String,
    val accountType: String,
    val status: String,
    val baseCurrency: String,
    val startingBalance: BigDecimal,
    val costModel: CostModel,
    val shortingEnabled: Boolean,
    val clonedFrom: UUID?,
    val resetFrom: UUID?,
    val reconciliationStatus: String,
    val createdAt: Instant,
    val archivedAt: Instant?,
    val version: Long,
)

data class PositionView(
    val instrumentId: UUID,
    val symbol: String,
    val assetClass: String,
    val side: String,
    val quantity: BigDecimal,
    val costBasis: BigDecimal,
    val averageCost: BigDecimal,
    val marketPrice: BigDecimal?,
    val marketValue: BigDecimal?,
    val unrealizedPnl: BigDecimal?,
    val priceTimestamp: Instant?,
    val priceStatus: DataStatus,
    val manualReviewRequired: Boolean,
)

data class PortfolioSummary(
    val portfolio: Portfolio,
    val cash: BigDecimal,
    val reservedCash: BigDecimal,
    val buyingPower: BigDecimal,
    val shortRequirement: BigDecimal,
    val costBasis: BigDecimal,
    val marketValue: BigDecimal,
    val equity: BigDecimal,
    val realizedPnl: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val fees: BigDecimal,
    val borrowFees: BigDecimal,
    val dividends: BigDecimal,
    val totalReturn: BigDecimal,
    val totalReturnPercent: BigDecimal?,
    val fullyPriced: Boolean,
    val positions: List<PositionView>,
    val asOf: Instant,
)

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

@Service
class PortfolioService(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val ledger: LedgerService,
    private val settings: SettingsService,
    private val audit: AuditService,
    private val clock: Clock,
    private val marketClock: MarketClock,
    private val market: MarketDataService,
    private val instruments: InstrumentService,
    private val events: org.springframework.context.ApplicationEventPublisher,
) {
    fun list(includeArchived: Boolean): List<Portfolio> =
        jdbc
            .sql("select * from portfolios where (:all or status = 'ACTIVE') order by created_at")
            .param("all", includeArchived)
            .query { rs, _ -> map(rs) }
            .list()

    fun get(id: UUID): Portfolio = find(id) ?: throw Problems.notFound("Portfolio", id)

    fun find(id: UUID): Portfolio? =
        jdbc
            .sql("select * from portfolios where id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    @Transactional
    fun create(req: PortfolioCreate): Portfolio {
        val defaults = settings.get().portfolioDefaults
        val start = req.startingBalance ?: defaults.startingBalance
        val model = req.costModel ?: CostModel.from(defaults)
        return createInternal(req.name, start, model, false, null, null, "PORTFOLIO_CREATED")
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
        // Serialize portfolio creation so the 10-portfolio cap cannot be exceeded by concurrent requests.
        jdbc.sql("select pg_advisory_xact_lock(7102027)").query().singleRow()
        val active = jdbc.sql("select count(*) from portfolios where status = 'ACTIVE'").query(Int::class.java).single()
        if (active >= MAX_ACTIVE) throw Problems.unprocessable("portfolio-limit", "At most $MAX_ACTIVE active paper portfolios are allowed")
        val exists =
            jdbc
                .sql("select count(*) from portfolios where status = 'ACTIVE' and lower(name) = lower(:n)")
                .param("n", name.trim())
                .query(Int::class.java)
                .single()
        if (exists > 0) throw Problems.conflict("portfolio-name-taken", "An active portfolio named '$name' already exists")
        val id = UUID.randomUUID()
        val now = clock.instant()
        jdbc
            .sql(
                """
                insert into portfolios(id, name, status, starting_balance, cost_model, shorting_enabled, cloned_from, reset_from, created_at)
                values (:id, :n, 'ACTIVE', :s, cast(:cm as jsonb), :sh, :cf, :rf, :now)
                """.trimIndent(),
            ).param("id", id)
            .param("n", name.trim())
            .param("s", Decimals.money(start))
            .param("cm", mapper.writeValueAsString(model))
            .param("sh", shorting)
            .param("cf", clonedFrom)
            .param("rf", resetFrom)
            .param("now", ts(now))
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

    @Transactional
    fun update(
        id: UUID,
        req: PortfolioUpdate,
        ifMatch: String?,
    ): Portfolio {
        val p = requireActive(id)
        ETags.require(ifMatch, p.version)
        validateName(req.name)
        validateCostModel(req.costModel)
        if (!req.name.trim().equals(p.name, ignoreCase = true)) {
            val exists =
                jdbc
                    .sql("select count(*) from portfolios where status = 'ACTIVE' and lower(name) = lower(:n) and id <> :id")
                    .param("n", req.name.trim())
                    .param("id", id)
                    .query(Int::class.java)
                    .single()
            if (exists > 0) throw Problems.conflict("portfolio-name-taken", "An active portfolio named '${req.name}' already exists")
        }
        if (riskIncrease(p.costModel, req.costModel)) RecentAuth.require(clock.instant(), "portfolio-risk-increase")
        val n =
            jdbc
                .sql("update portfolios set name = :n, cost_model = cast(:cm as jsonb), version = version + 1 where id = :id and version = :v")
                .param("n", req.name.trim())
                .param("cm", mapper.writeValueAsString(req.costModel))
                .param("id", id)
                .param("v", p.version)
                .update()
        if (n == 0) throw Problems.preconditionFailed("Portfolio changed concurrently")
        audit.record(AuditCategory.PORTFOLIO, "PORTFOLIO_UPDATED", entityType = "Portfolio", entityId = id, details = mapOf("name" to req.name, "before" to p.costModel, "after" to req.costModel))
        if (p.costModel != req.costModel) events.publishEvent(PortfolioChanged(id, "COST_MODEL"))
        return get(id)
    }

    /** Enabling simulated shorting is a risk increase and requires recent authentication (section 15). */
    @Transactional
    fun setShorting(
        id: UUID,
        enabled: Boolean,
    ): Portfolio {
        val p = requireActive(id)
        if (enabled) RecentAuth.require(clock.instant(), "enable-shorting")
        if (!enabled && ledger.positions(id).any { it.quantity.signum() < 0 }) {
            throw Problems.conflict("open-short-positions", "Cover all short positions before disabling shorting")
        }
        jdbc
            .sql("update portfolios set shorting_enabled = :e, version = version + 1 where id = :id")
            .param("e", enabled)
            .param("id", id)
            .update()
        audit.record(AuditCategory.PORTFOLIO, if (enabled) "SHORTING_ENABLED" else "SHORTING_DISABLED", entityType = "Portfolio", entityId = id)
        if (enabled != p.shortingEnabled) events.publishEvent(PortfolioChanged(id, "SHORTING"))
        return get(id)
    }

    @Transactional
    fun archive(id: UUID): Portfolio {
        requireActive(id)
        val open =
            jdbc
                .sql("select count(*) from paper_orders where portfolio_id = :p and status in ('CREATED','VALIDATED','PENDING','PARTIALLY_FILLED')")
                .param("p", id)
                .query(Int::class.java)
                .single()
        if (open > 0) throw Problems.conflict("open-orders", "Cancel $open open paper order(s) before archiving")
        archiveInternal(id, "PORTFOLIO_ARCHIVED")
        return get(id)
    }

    private fun archiveInternal(
        id: UUID,
        action: String,
    ) {
        jdbc
            .sql("update portfolios set status = 'ARCHIVED', archived_at = :now, version = version + 1 where id = :id")
            .param("now", ts(clock.instant()))
            .param("id", id)
            .update()
        audit.record(AuditCategory.PORTFOLIO, action, entityType = "Portfolio", entityId = id)
        events.publishEvent(PortfolioChanged(id, "ARCHIVED"))
    }

    /** Clone copies configuration (starting balance, costs, shorting flag) into a fresh ledger; no history is copied. */
    @Transactional
    fun clone(
        id: UUID,
        name: String,
    ): Portfolio {
        val src = get(id)
        if (src.shortingEnabled) RecentAuth.require(clock.instant(), "enable-shorting")
        return createInternal(name, src.startingBalance, src.costModel, src.shortingEnabled, src.id, null, "PORTFOLIO_CLONED")
    }

    /** Reset archives the existing portfolio (ledger preserved) and creates a new one with a new ledger (FR-015). */
    @Transactional
    fun reset(
        id: UUID,
        confirm: String?,
    ): Portfolio {
        if (confirm != "RESET") throw Problems.badRequest("confirmation-required", "Send confirm = \"RESET\" to reset this portfolio")
        val p = requireActive(id)
        val open =
            jdbc
                .sql("select count(*) from paper_orders where portfolio_id = :p and status in ('CREATED','VALIDATED','PENDING','PARTIALLY_FILLED')")
                .param("p", id)
                .query(Int::class.java)
                .single()
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
            jdbc
                .sql("select instrument_id from corporate_action_coverage where status = 'UNAVAILABLE'")
                .query(UUID::class.java)
                .list()
                .toSet()
        return positions.filter { it.quantity.signum() != 0 }.map { pos ->
            val i = byId.getValue(pos.instrumentId)
            // While a market is closed, the last session's closing print is a verified valuation mark.
            val closedSince =
                if (app.strategyforge.market.MarketCalendar
                        .state(i.assetClass, now) == app.strategyforge.market.SessionState.CLOSED
                ) {
                    app.strategyforge.market.MarketCalendar
                        .currentOrPreviousSession(now)
                        ?.close
                        ?.takeIf { !it.isAfter(now) }
                } else {
                    null
                }
            val maxAge =
                closedSince?.let {
                    java.time.Duration
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
        jdbc
            .sql("insert into portfolio_equity_snapshots(portfolio_id, at, equity, cash, positions_value, priced, source) values (:p, :at, :e, :c, :v, :pr, :s)")
            .param("p", id)
            .param("at", ts(marketClock.now()))
            .param("e", Decimals.money(s.equity))
            .param("c", Decimals.money(s.cash.add(s.reservedCash)))
            .param("v", Decimals.money(s.marketValue))
            .param("pr", s.fullyPriced)
            .param("s", source)
            .update()
    }

    fun equityHistory(
        id: UUID,
        limit: Int,
    ): List<Map<String, Any?>> =
        jdbc
            .sql("select * from portfolio_equity_snapshots where portfolio_id = :p order by at desc, id desc limit :l")
            .param("p", id)
            .param("l", limit)
            .query { rs, _ -> mapOf("at" to rs.instant("at"), "equity" to rs.getBigDecimal("equity"), "cash" to rs.getBigDecimal("cash"), "positionsValue" to rs.getBigDecimal("positions_value"), "priced" to rs.getBoolean("priced"), "source" to rs.getString("source")) }
            .list()
            .reversed()

    fun peakEquity(id: UUID): BigDecimal? =
        jdbc
            .sql("select max(equity) from portfolio_equity_snapshots where portfolio_id = :p and priced")
            .param("p", id)
            .query(BigDecimal::class.java)
            .optional()
            .orElse(null)

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

    private fun map(rs: java.sql.ResultSet) =
        Portfolio(
            rs.uuid("id"),
            rs.getString("name"),
            rs.getString("account_type"),
            rs.getString("status"),
            rs.getString("base_currency"),
            rs.getBigDecimal("starting_balance"),
            mapper.readValue(rs.getString("cost_model"), CostModel::class.java),
            rs.getBoolean("shorting_enabled"),
            rs.uuidOrNull("cloned_from"),
            rs.uuidOrNull("reset_from"),
            rs.getString("reconciliation_status"),
            rs.instant("created_at"),
            rs.instantOrNull("archived_at"),
            rs.getLong("version"),
        )

    companion object {
        const val MAX_ACTIVE = 10
        const val VALUATION_MAX_AGE = 3600L
        const val VALUATION_CLOSE_GRACE = 120L
    }
}
