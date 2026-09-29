package app.strategyforge.engine.execution

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.EngineLog
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.decOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CorporateActionService
import app.strategyforge.engine.market.CorporateActionType
import app.strategyforge.engine.market.CoverageStatus
import app.strategyforge.engine.market.InstrumentService
import app.strategyforge.engine.market.MarketCalendar
import app.strategyforge.engine.market.MarketClock
import app.strategyforge.engine.market.MarketInterest
import app.strategyforge.engine.market.VolumeInterest
import app.strategyforge.engine.money.Decimals
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.notifications.NotificationService
import app.strategyforge.engine.notifications.Severity
import app.strategyforge.engine.portfolio.Account
import app.strategyforge.engine.portfolio.JournalType
import app.strategyforge.engine.portfolio.LedgerService
import app.strategyforge.engine.portfolio.LotService
import app.strategyforge.engine.portfolio.PortfolioService
import app.strategyforge.engine.portfolio.Posting
import app.strategyforge.engine.portfolio.ReconciliationService
import app.strategyforge.engine.risk.OrderSource
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Daily and per-step portfolio upkeep: corporate actions (splits, dividends), short borrow-fee
 * accrual, and forced covers when the short maintenance requirement is breached (FR-013, FR-083).
 */
class PortfolioMaintenance(
    private val db: Db,
    private val portfolios: PortfolioService,
    private val instruments: InstrumentService,
    private val corporate: CorporateActionService,
    private val ledger: LedgerService,
    private val lots: LotService,
    private val orders: OrderService,
    private val audit: AuditService,
    private val notifications: NotificationService,
    private val clock: MarketClock,
    private val reconciliation: ReconciliationService,
) {
    private val log = EngineLog.of(javaClass)
    private val dailyDone = java.util.concurrent.ConcurrentHashMap<UUID, LocalDate>()

    /**
     * Corporate actions and borrow fees are daily events (run once per portfolio per market date);
     * short maintenance is checked every run for portfolios holding shorts.
     */
    fun runAll(force: Boolean = false) {
        val date = clock.now().atZone(MarketCalendar.NEW_YORK).toLocalDate()
        val withPositions =
            db
                .sql(
                    "select distinct e.portfolio_id from ledger_entries e join portfolios p on p.id = e.portfolio_id where p.status = 'ACTIVE' and e.account = 'POSITION'",
                ).list { it.uuid("portfolio_id") }
        withPositions.forEach { id ->
            if (force || dailyDone[id] != date) {
                runCatching { db.tx { corporateActions(id) } }.onFailure { log.error("Corporate actions failed for {}", id, it) }
                runCatching { db.tx { accrueBorrow(id) } }.onFailure { log.error("Borrow accrual failed for {}", id, it) }
                dailyDone[id] = date
            }
        }
        // Net quantities are summed in Kotlin (decimal text).
        val withShorts = withPositions.filter { id -> ledger.positions(id).any { it.quantity.signum() < 0 } }
        withShorts.forEach { id -> runCatching { db.tx { enforceShortMaintenance(id) } }.onFailure { log.error("Short maintenance failed for {}", id, it) } }
    }

    /** Applies splits and dividend entitlements/payments once each, based on positions held at the ex-date. */
    fun corporateActions(portfolioId: UUID) {
        val today = clock.now().atZone(MarketCalendar.NEW_YORK).toLocalDate()
        val held = ledger.positions(portfolioId).filter { it.quantity.signum() != 0 }
        held.forEach { pos ->
            val i = instruments.byId(pos.instrumentId)
            if (i.assetClass != AssetClass.US_EQUITY) return@forEach
            val from = today.minusDays(LOOKBACK_DAYS)
            val coverage = corporate.coverage(i.id)?.takeIf { it.status == CoverageStatus.AVAILABLE && it.to != null && !it.to.isBefore(today) } ?: corporate.sync(i, from, today.plusDays(30))
            if (coverage.status == CoverageStatus.UNAVAILABLE) {
                notifications.notify(
                    NotificationCategory.RISK_EVENT,
                    Severity.WARNING,
                    "Manual review required: ${i.symbol} corporate actions",
                    "Corporate-action data for ${i.symbol} is unavailable from the active provider; valuations may need manual review.",
                    "Instrument",
                    i.id,
                    "ca-unavailable:$portfolioId:${i.id}:$today",
                )
                return@forEach
            }
            corporate.actions(i.id, from, today).forEach { a ->
                val applied =
                    db
                        .sql("select status from corporate_action_applications where portfolio_id = :p and action_id = :a")
                        .param("p", portfolioId)
                        .param("a", a.id)
                        .firstOrNull { it.str("status") }
                when (a.type) {
                    CorporateActionType.SPLIT -> if (applied == null && !a.exDate.isAfter(today)) applySplit(portfolioId, i.symbol, a.id, a.instrumentId, a.ratioNew!!, a.ratioOld!!)
                    CorporateActionType.CASH_DIVIDEND -> {
                        if (applied == null && !a.exDate.isAfter(today)) entitle(portfolioId, a.id, a.instrumentId, a.cashAmount!!)
                        val payDate = a.payDate ?: a.exDate
                        if (!payDate.isAfter(today)) pay(portfolioId, i.symbol, a.id)
                    }
                }
            }
        }
    }

    private fun applySplit(
        portfolioId: UUID,
        symbol: String,
        actionId: UUID,
        instrumentId: UUID,
        ratioNew: BigDecimal,
        ratioOld: BigDecimal,
    ) {
        val delta = lots.applySplit(portfolioId, instrumentId, ratioNew, ratioOld)
        val journal =
            ledger.post(
                portfolioId,
                JournalType.SPLIT,
                clock.now(),
                "Split ${ratioNew.stripTrailingZeros().toPlainString()}:${ratioOld.stripTrailingZeros().toPlainString()} $symbol",
                listOf(Posting(Account.POSITION, BigDecimal.ZERO, instrumentId, delta)),
                "CorporateAction",
                actionId,
            )
        db
            .sql("insert into corporate_action_applications(portfolio_id, action_id, status, entitled_quantity, journal_id, applied_at) values (:p, :a, 'APPLIED', :q, :j, :t)")
            .param("p", portfolioId)
            .param("a", actionId)
            .param("q", delta)
            .param("j", journal)
            .param("t", (clock.now()))
            .update()
        audit.record(AuditCategory.LEDGER, "SPLIT_APPLIED", entityType = "Portfolio", entityId = portfolioId, details = mapOf("symbol" to symbol, "quantityDelta" to delta))
    }

    private fun entitle(
        portfolioId: UUID,
        actionId: UUID,
        instrumentId: UUID,
        perShare: BigDecimal,
    ) {
        val qty = ledger.position(portfolioId, instrumentId).quantity
        val amount = Decimals.money(qty.multiply(perShare))
        db
            .sql("insert into corporate_action_applications(portfolio_id, action_id, status, entitled_quantity, amount, applied_at) values (:p, :a, 'ENTITLED', :q, :amt, :t)")
            .param("p", portfolioId)
            .param("a", actionId)
            .param("q", qty)
            .param("amt", amount)
            .param("t", (clock.now()))
            .update()
    }

    private fun pay(
        portfolioId: UUID,
        symbol: String,
        actionId: UUID,
    ) {
        val amount =
            db
                .sql("select amount from corporate_action_applications where portfolio_id = :p and action_id = :a and status = 'ENTITLED'")
                .param("p", portfolioId)
                .param("a", actionId)
                .firstOrNull { it.decOrNull("amount") } ?: return
        val journal =
            if (amount.signum() != 0) {
                // Longs receive the dividend; shorts pay it (payment in lieu).
                ledger.post(
                    portfolioId,
                    JournalType.DIVIDEND,
                    clock.now(),
                    "Cash dividend $symbol${if (amount.signum() < 0) " (short, payment in lieu)" else ""}",
                    listOf(Posting(Account.CASH, amount), Posting(Account.DIVIDENDS, amount.negate())),
                    "CorporateAction",
                    actionId,
                )
            } else {
                null
            }
        db
            .sql("update corporate_action_applications set status = 'PAID', journal_id = :j where portfolio_id = :p and action_id = :a")
            .param("j", journal)
            .param("p", portfolioId)
            .param("a", actionId)
            .update()
        audit.record(AuditCategory.LEDGER, "DIVIDEND_PAID", entityType = "Portfolio", entityId = portfolioId, details = mapOf("symbol" to symbol, "amount" to amount))
    }

    /** Daily borrow fee on short market value: |MV| x annual rate / 360 (FR-013, FR-083). */
    fun accrueBorrow(portfolioId: UUID) {
        val p = portfolios.get(portfolioId)
        val date: LocalDate = clock.now().atZone(MarketCalendar.NEW_YORK).toLocalDate()
        portfolios.positionViews(p).filter { it.quantity.signum() < 0 }.forEach { pos ->
            val exists =
                db
                    .sql("select count(*) n from borrow_accruals where portfolio_id = :p and instrument_id = :i and accrual_date = :d")
                    .param("p", portfolioId)
                    .param("i", pos.instrumentId)
                    .param("d", date)
                    .int()
            if (exists > 0) return@forEach
            val value = (pos.marketValue ?: pos.costBasis).abs()
            val fee = Decimals.money(value.multiply(p.costModel.borrowRateAnnualPercent).divide(Decimals.HUNDRED).divide(BigDecimal(360), Decimals.MC))
            if (fee.signum() <= 0) return@forEach
            val j = ledger.post(portfolioId, JournalType.BORROW_FEE, clock.now(), "Borrow fee ${pos.symbol} $date", listOf(Posting(Account.BORROW_FEES, fee), Posting(Account.CASH, fee.negate())), "Instrument", pos.instrumentId)
            db
                .sql("insert into borrow_accruals(portfolio_id, instrument_id, accrual_date, amount, journal_id) values (:p, :i, :d, :a, :j)")
                .param("p", portfolioId)
                .param("i", pos.instrumentId)
                .param("d", date)
                .param("a", fee)
                .param("j", j)
                .update()
        }
    }

    /** Forced cover when equity falls below the maintenance requirement on short market value. */
    fun enforceShortMaintenance(portfolioId: UUID) {
        val s = portfolios.summary(portfolioId)
        val shorts = s.positions.filter { it.quantity.signum() < 0 }
        if (shorts.isEmpty()) return
        val shortValue = shorts.fold(BigDecimal.ZERO) { a, x -> a.add((x.marketValue ?: x.costBasis).abs()) }
        val required = shortValue.multiply(s.portfolio.costModel.shortMaintenancePercent).divide(Decimals.HUNDRED, Decimals.MC)
        if (s.equity >= required) return
        shorts.forEach { pos ->
            val pending =
                db
                    .sql("select count(*) n from paper_orders where portfolio_id = :p and instrument_id = :i and side = 'BUY_TO_COVER' and status in ('PENDING','PARTIALLY_FILLED')")
                    .param("p", portfolioId)
                    .param("i", pos.instrumentId)
                    .int()
            if (pending > 0) return@forEach
            val r = orders.create(OrderRequest(portfolioId, pos.symbol, OrderSide.BUY_TO_COVER, OrderType.MARKET, pos.quantity.negate(), timeInForce = TimeInForce.GTC), OrderSource.FORCED_COVER)
            audit.record(AuditCategory.RISK, "FORCED_COVER", entityType = "PaperOrder", entityId = r.order.id, details = mapOf("symbol" to pos.symbol, "equity" to s.equity, "required" to required))
        }
        notifications.notify(
            NotificationCategory.RISK_EVENT,
            Severity.CRITICAL,
            "Forced cover of simulated short positions",
            "Equity ${Decimals.report(s.equity)} USD fell below the short maintenance requirement ${Decimals.report(required)} USD; covering orders were created.",
            "Portfolio",
            portfolioId,
            "forced-cover:$portfolioId:${clock.now().epochSecond / 3600}",
        )
    }

    fun onFilled(e: OrderFilled) = reconciliation.markDirty(e.portfolioId)

    companion object {
        const val LOOKBACK_DAYS = 10L
    }
}

/** Keeps quotes fresh for instruments with positions or open orders. */
class PositionInterest(
    private val db: Db,
) : MarketInterest {
    override fun instrumentIds(): Set<UUID> =
        db
            .sql(
                """
                select distinct instrument_id from position_lots l join portfolios p on p.id = l.portfolio_id
                where l.closed_at is null and p.status = 'ACTIVE'
                union select instrument_id from paper_orders where status in ('PENDING','PARTIALLY_FILLED')
                """.trimIndent(),
            ).list { rs -> rs.uuid("instrument_id") }
            .toSet()
}

class OpenOrderVolumeInterest(
    private val db: Db,
) : VolumeInterest {
    override fun instrumentIds(): Set<UUID> =
        db
            .sql("select distinct instrument_id from paper_orders where status in ('PENDING','PARTIALLY_FILLED')")
            .list { it.uuid("instrument_id") }
            .toSet()
}
