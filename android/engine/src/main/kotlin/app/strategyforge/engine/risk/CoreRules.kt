package app.strategyforge.engine.risk

import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.bool
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.decOrNull
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderType
import app.strategyforge.engine.execution.Pricing
import app.strategyforge.engine.execution.QuoteInput
import app.strategyforge.engine.market.Instrument
import app.strategyforge.engine.market.InstrumentService
import app.strategyforge.engine.market.MarketCalendar
import app.strategyforge.engine.market.MarketClock
import app.strategyforge.engine.market.MarketDataService
import app.strategyforge.engine.market.SessionState
import app.strategyforge.engine.market.Timeframe
import app.strategyforge.engine.money.Decimals
import app.strategyforge.engine.portfolio.Portfolio
import app.strategyforge.engine.portfolio.PortfolioService
import app.strategyforge.engine.portfolio.PortfolioSummary
import app.strategyforge.engine.strategy.StrategyDefinition
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Resolves the immutable definition of a strategy version (implemented by the strategy service). */
fun interface StrategyDefinitionLookup {
    fun definition(versionId: UUID): StrategyDefinition
}

class DefaultRiskContextFactory(
    private val portfolios: PortfolioService,
    private val instruments: InstrumentService,
    private val market: MarketDataService,
    private val clock: MarketClock,
    private val wall: java.time.Clock,
    private val db: Db,
    private val profiles: RiskProfileService,
    private val strategies: StrategyDefinitionLookup,
) : RiskContextFactory {
    override fun build(intent: OrderIntent): RiskContext {
        val p = portfolios.get(intent.portfolioId)
        val i = instruments.byId(intent.instrumentId)
        val now = clock.now()
        val strategyDef = intent.strategyVersionId?.let { runCatching { strategies.definition(it) }.getOrNull() }
        val allocation =
            intent.strategyId?.let { sid ->
                db
                    .sql("select allocation_percent from strategy_activations where strategy_id = :s and status = 'ACTIVE'")
                    .param("s", sid)
                    .firstOrNull { it.decOrNull("allocation_percent") }
            }
        val levels = mutableListOf<Pair<RiskLevel, RiskLimits>>(RiskLevel.GLOBAL to profiles.global().limits)
        profiles.limitsFor("PORTFOLIO", p.id)?.let { levels += RiskLevel.PORTFOLIO to it }
        intent.strategyId?.let { sid -> profiles.limitsFor("STRATEGY", sid)?.let { levels += RiskLevel.STRATEGY to it } }
        if (strategyDef != null) {
            levels +=
                RiskLevel.STRATEGY to
                RiskLimits(
                    maxInstrumentAllocationPercent = strategyDef.risk.maximumPositionPercent,
                    maxStrategyAllocationPercent = allocation,
                    maxDailyLossPercent = strategyDef.risk.maximumDailyLossPercent,
                    maxDrawdownPercent = strategyDef.risk.maximumDrawdownPercent,
                    shortingAllowed = strategyDef.risk.allowShort,
                    maxQuoteAgeSeconds = strategyDef.maximumQuoteAgeSeconds,
                )
        }
        val limits = EffectiveLimits.merge(levels)
        val maxAge = minOf(p.costModel.executionMaxQuoteAgeSeconds, limits.maxQuoteAgeSeconds?.value ?: Long.MAX_VALUE)
        val quote = market.verifyQuote(i, maxAge, now)
        val q = quote.quote
        val est =
            when {
                intent.orderType == OrderType.LIMIT || intent.orderType == OrderType.STOP_LIMIT -> intent.limitPrice
                q == null -> intent.stopPrice
                else -> {
                    val touch = Pricing.touch(QuoteInput(q.bid, q.ask, q.last), intent.side.buys, i.assetClass, p.costModel).first
                    if (intent.orderType == OrderType.STOP && intent.stopPrice != null) {
                        if (intent.side.buys) touch.max(intent.stopPrice) else touch.min(intent.stopPrice)
                    } else {
                        touch
                    }
                }
            }
        val open =
            db
                .sql(
                    """
                    select id, instrument_id, side, quantity, filled_quantity, reserved_amount, reservation_released
                    from paper_orders where portfolio_id = :p and status in ('VALIDATED', 'PENDING', 'PARTIALLY_FILLED')
                    """.trimIndent(),
                ).param("p", p.id)
                .list { rs ->
                    OpenOrderInfo(
                        rs.uuid("id"),
                        rs.uuid("instrument_id"),
                        OrderSide.valueOf(rs.str("side")),
                        rs.dec("quantity").subtract(rs.dec("filled_quantity")),
                        rs.dec("reserved_amount").subtract(rs.dec("reservation_released")),
                    )
                }
        val summary = portfolios.summary(p.id)
        return RiskContext(intent, p, summary, i, quote, est, open, now, limits = limits, metrics = metrics(intent, p, summary, i, now, allocation, strategyDef))
    }

    private fun metrics(
        intent: OrderIntent,
        p: Portfolio,
        summary: PortfolioSummary,
        i: Instrument,
        now: Instant,
        allocation: BigDecimal?,
        def: StrategyDefinition?,
    ): RiskMetrics {
        val dayStart =
            MarketCalendar.NEW_YORK.let {
                now
                    .atZone(it)
                    .toLocalDate()
                    .atStartOfDay(it)
                    .toInstant()
            }
        val dayStartEquity =
            db
                .sql("select equity from portfolio_equity_snapshots where portfolio_id = :p and at <= :t order by at desc, id desc limit 1")
                .param("p", p.id)
                .param("t", (dayStart))
                .firstOrNull { it.dec("equity") }
                ?: db
                    .sql("select equity from portfolio_equity_snapshots where portfolio_id = :p order by at, id limit 1")
                    .param("p", p.id)
                    .firstOrNull { it.dec("equity") }
                ?: p.startingBalance
        val peak = portfolios.peakEquity(p.id)?.max(p.startingBalance) ?: p.startingBalance

        fun ordersSince(
            since: Instant,
            strategy: Boolean = false,
            instrument: Boolean = false,
        ): Int =
            db
                .sql(
                    """
                    select count(*) n from order_status_history h join paper_orders o on o.id = h.order_id
                    where o.portfolio_id = :p and h.to_status = 'PENDING' and h.market_time > :since
                      and (:strat = 0 or o.strategy_id = :sid) and (:inst = 0 or o.instrument_id = :iid)
                    """.trimIndent(),
                ).param("p", p.id)
                .param("since", (since))
                .param("strat", strategy)
                .param("sid", intent.strategyId)
                .param("inst", instrument)
                .param("iid", i.id)
                .int()
        val lastForInstrument =
            db
                .sql(
                    "select max(h.market_time) n from order_status_history h join paper_orders o on o.id = h.order_id where o.portfolio_id = :p and o.instrument_id = :i and h.to_status = 'PENDING'",
                ).param("p", p.id)
                .param("i", i.id)
                .single { it.instantOrNull("n") }

        fun consecutiveLosses(strategyOnly: Boolean): Int {
            val pnls =
                db
                    .sql(
                        """
                        select e.realized_pnl from paper_executions e join paper_orders o on o.id = e.order_id
                        where e.portfolio_id = :p and e.side in ('SELL', 'BUY_TO_COVER') and (:strat = 0 or o.strategy_id = :sid)
                        order by e.executed_at desc, e.fill_seq desc limit 100
                        """.trimIndent(),
                    ).param("p", p.id)
                    .param("strat", strategyOnly)
                    .param("sid", intent.strategyId)
                    .list { it.dec("realized_pnl") }
            return pnls.takeWhile { it.signum() < 0 }.size
        }
        val instrumentValue = summary.positions.filter { it.instrumentId == i.id }.fold(BigDecimal.ZERO) { a, x -> a.add((x.marketValue ?: x.costBasis).abs()) }
        val assetValue = summary.positions.filter { it.assetClass == i.assetClass.name }.fold(BigDecimal.ZERO) { a, x -> a.add((x.marketValue ?: x.costBasis).abs()) }
        val shortValue = summary.positions.filter { it.quantity.signum() < 0 }.fold(BigDecimal.ZERO) { a, x -> a.add((x.marketValue ?: x.costBasis).abs()) }
        val strategyLots =
            if (intent.strategyId == null) {
                emptyList()
            } else {
                db
                    .sql(
                        """
                        select l.instrument_id, l.quantity_remaining, l.side from position_lots l
                        join paper_executions e on e.id = l.open_execution_id join paper_orders o on o.id = e.order_id
                        where l.portfolio_id = :p and l.closed_at is null and o.strategy_id = :s
                        """.trimIndent(),
                    ).param("p", p.id)
                    .param("s", intent.strategyId)
                    .list { rs -> Triple(rs.uuid("instrument_id"), rs.dec("quantity_remaining"), rs.str("side")) }
            }
        val prices = summary.positions.associate { it.instrumentId to (it.marketPrice ?: it.averageCost) }
        val strategyExposure = strategyLots.fold(BigDecimal.ZERO) { a, (iid, q, _) -> a.add(q.multiply(prices[iid] ?: BigDecimal.ZERO)) }
        val daily = runCatching { market.candles(i, Timeframe.D1, now.minus(Duration.ofDays(12)), now, now).bars.takeLast(5) }.getOrDefault(emptyList())
        val emergency =
            db
                .sql("select pause_all, prevent_new_positions from emergency_state")
                .firstOrNull { rs -> rs.bool("pause_all") to rs.bool("prevent_new_positions") }
                ?: (true to true)
        val strategyStatus =
            intent.strategyId?.let { sid ->
                db
                    .sql("select status from strategies where id = :s")
                    .param("s", sid)
                    .firstOrNull { it.str("status") }
            }
        // Database and engine share the device clock on the phone, so there is no drift to measure.
        val drift = 0L
        return RiskMetrics(
            equity = summary.equity,
            dayStartEquity = dayStartEquity,
            peakEquity = peak,
            ordersLastMinute = ordersSince(now.minusSeconds(60)),
            ordersLastHour = ordersSince(now.minusSeconds(3600)),
            ordersToday = ordersSince(dayStart),
            lastOrderForInstrumentAt = lastForInstrument,
            consecutiveLosses = consecutiveLosses(false),
            openPositions = summary.positions.size,
            instrumentValue = instrumentValue,
            assetClassValue = assetValue,
            shortExposure = shortValue,
            averageDailyVolume = daily.takeIf { it.isNotEmpty() }?.let { d -> d.fold(BigDecimal.ZERO) { a, b -> a.add(b.volume) }.divide(BigDecimal(d.size), Decimals.MC) },
            previousClose = daily.lastOrNull()?.close,
            pauseAll = emergency.first,
            preventNewPositions = emergency.second,
            strategyStatus = strategyStatus,
            strategyAllocationPercent = allocation,
            strategyExposure = strategyExposure,
            strategyOpenPositions = strategyLots.map { it.first }.distinct().size,
            strategyOrdersToday = if (intent.strategyId == null) 0 else ordersSince(dayStart, strategy = true),
            strategyConsecutiveLosses = if (intent.strategyId == null) 0 else consecutiveLosses(true),
            strategyLimits = def?.let { StrategyLimitView(it.risk.maximumOpenPositions, it.risk.maximumDailyTrades, it.risk.maximumConsecutiveLosses, it.risk.allowShort) },
            clockDriftMs = drift,
        )
    }
}

class PortfolioActiveRule : RiskRule {
    override val name = "PORTFOLIO_ACTIVE"
    override val family = "ELIGIBILITY"
    override val blocksReducing = true

    override fun evaluate(ctx: RiskContext) = if (ctx.portfolio.status == "ACTIVE" && ctx.portfolio.accountType == "PAPER") pass("Paper portfolio is active") else fail("Portfolio is ${ctx.portfolio.status}")
}

class ReconciliationRule : RiskRule {
    override val name = "RECONCILIATION_VERIFIED"
    override val family = "SYSTEM_SAFETY"

    override fun evaluate(ctx: RiskContext) =
        when (ctx.portfolio.reconciliationStatus) {
            "OK" -> pass("Ledger reconciliation verified", RiskLevel.SYSTEM)
            "FAILED" -> fail("Ledger reconciliation failed; new or larger positions are blocked", RiskLevel.SYSTEM)
            else -> unverified("Ledger reconciliation state is unverified")
        }
}

class InstrumentTradableRule : RiskRule {
    override val name = "INSTRUMENT_TRADABLE"
    override val family = "ELIGIBILITY"

    override fun evaluate(ctx: RiskContext) = if (ctx.instrument.active) pass("${ctx.instrument.symbol} is active") else fail("${ctx.instrument.symbol} is deactivated")
}

class QuantityRule : RiskRule {
    override val name = "QUANTITY_VALID"
    override val family = "ORDER"
    override val blocksReducing = true

    override fun evaluate(ctx: RiskContext): RuleResult {
        val q = ctx.intent.quantity
        val inc = ctx.instrument.quantityIncrement
        if (q < ctx.instrument.minQuantity) return fail("Quantity below minimum ${ctx.instrument.minQuantity.toPlainString()}", RiskLevel.ORDER, ctx.instrument.minQuantity, q)
        if (q.remainder(inc).signum() != 0) return fail("Quantity must be a multiple of ${inc.toPlainString()}", RiskLevel.ORDER, inc, q)
        if (q.scale() > 18 || q > BigDecimal("1000000000")) return fail("Quantity is outside permitted bounds", RiskLevel.ORDER)
        return pass("Quantity respects lot rules", RiskLevel.ORDER)
    }
}

class PriceSanityRule : RiskRule {
    override val name = "PRICE_SANITY"
    override val family = "ORDER"
    override val blocksReducing = true

    override fun evaluate(ctx: RiskContext): RuleResult {
        val ref = ctx.quote.quote?.last
        val prices = listOfNotNull(ctx.intent.limitPrice, ctx.intent.stopPrice)
        if (prices.isEmpty()) return na()
        prices.forEach { p ->
            if (p.remainder(ctx.instrument.priceIncrement).signum() != 0) return fail("Price ${p.toPlainString()} is not a multiple of ${ctx.instrument.priceIncrement.toPlainString()}", RiskLevel.ORDER)
        }
        if (ref == null) return unverified("No reference price to validate limit/stop prices")
        prices.forEach { p ->
            val dev =
                p
                    .subtract(ref)
                    .abs()
                    .multiply(Decimals.HUNDRED)
                    .divide(ref, Decimals.MC)
            if (dev > MAX_DEVIATION) return fail("Price ${p.toPlainString()} deviates ${dev.setScale(2, java.math.RoundingMode.HALF_EVEN)}% from market ${ref.toPlainString()}", RiskLevel.ORDER, MAX_DEVIATION, dev)
        }
        return pass("Limit/stop prices within ${MAX_DEVIATION}% of market", RiskLevel.ORDER)
    }

    companion object {
        val MAX_DEVIATION = BigDecimal("50")
    }
}

class MarketDataVerifiedRule : RiskRule {
    override val name = "MARKET_DATA_VERIFIED"
    override val family = "DATA_QUALITY"

    override fun evaluate(ctx: RiskContext) =
        if (ctx.quote.verified) {
            pass("Quote verified (age ${ctx.quote.ageSeconds}s)", RiskLevel.SYSTEM, ctx.portfolio.costModel.executionMaxQuoteAgeSeconds, ctx.quote.ageSeconds)
        } else {
            fail("Market data not verified: ${ctx.quote.status} - ${ctx.quote.detail}", RiskLevel.SYSTEM, ctx.portfolio.costModel.executionMaxQuoteAgeSeconds, ctx.quote.ageSeconds)
        }
}

class TradingScheduleRule : RiskRule {
    override val name = "TRADING_SCHEDULE"
    override val family = "MARKET_QUALITY"

    override fun evaluate(ctx: RiskContext) =
        when (MarketCalendar.state(ctx.instrument.assetClass, ctx.now)) {
            SessionState.OPEN -> pass("Market session open")
            SessionState.CLOSED -> pass("Market closed; order will wait for the next session")
            SessionState.UNVERIFIED -> unverified("Trading calendar does not cover ${ctx.now}; equity trading blocked")
        }
}

class HoldingsRule : RiskRule {
    override val name = "HOLDINGS"
    override val family = "ELIGIBILITY"
    override val blocksReducing = true

    /** Prevents selling beyond holdings and mixing long/short in one instrument (FR-084). */
    override fun evaluate(ctx: RiskContext): RuleResult {
        val pos =
            ctx.summary.positions
                .firstOrNull { it.instrumentId == ctx.instrument.id }
                ?.quantity ?: BigDecimal.ZERO
        val pending = { side: OrderSide -> ctx.openOrders.filter { it.instrumentId == ctx.instrument.id && it.side == side }.fold(BigDecimal.ZERO) { a, o -> a.add(o.remaining) } }
        val q = ctx.intent.quantity
        return when (ctx.intent.side) {
            OrderSide.SELL -> {
                val available = pos.max(BigDecimal.ZERO).subtract(pending(OrderSide.SELL))
                if (q > available) fail("Sell of ${q.toPlainString()} exceeds available long holdings ${available.toPlainString()}", RiskLevel.ORDER, available, q) else pass("Within long holdings")
            }
            OrderSide.BUY_TO_COVER -> {
                val available = pos.negate().max(BigDecimal.ZERO).subtract(pending(OrderSide.BUY_TO_COVER))
                if (q > available) fail("Cover of ${q.toPlainString()} exceeds open short ${available.toPlainString()}", RiskLevel.ORDER, available, q) else pass("Within short position")
            }
            OrderSide.BUY -> if (pos.signum() < 0) fail("Position is short; use BUY_TO_COVER", RiskLevel.ORDER) else pass("No conflicting short")
            OrderSide.SELL_SHORT -> if (pos.signum() > 0) fail("Position is long; sell the long position before shorting", RiskLevel.ORDER) else pass("No conflicting long")
        }
    }
}

class ShortingPermittedRule : RiskRule {
    override val name = "SHORTING_PERMITTED"
    override val family = "SHORTING"

    override fun evaluate(ctx: RiskContext): RuleResult {
        if (ctx.intent.side != OrderSide.SELL_SHORT) return na()
        if (!ctx.portfolio.shortingEnabled) return fail("Simulated shorting is disabled for this portfolio")
        if (!ctx.instrument.shortable) return fail("${ctx.instrument.symbol} is not shortable under the locate assumption", RiskLevel.SYSTEM)
        return pass("Shorting enabled and locate assumed available")
    }
}

class BuyingPowerRule : RiskRule {
    override val name = "BUYING_POWER"
    override val family = "EXPOSURE"
    override val blocksReducing = true

    override fun evaluate(ctx: RiskContext): RuleResult {
        val price = ctx.estimatedPrice ?: return if (ctx.intent.side.increasesRisk) unverified("No price available to size the cash requirement") else na()
        val m = ctx.portfolio.costModel
        val required =
            when (ctx.intent.side) {
                OrderSide.BUY, OrderSide.BUY_TO_COVER -> Pricing.buyReservation(m, ctx.intent.quantity, price)
                OrderSide.SELL_SHORT -> Pricing.shortReservation(m, ctx.intent.quantity, price)
                OrderSide.SELL -> return na()
            }
        val available = if (ctx.intent.side == OrderSide.BUY_TO_COVER) ctx.summary.cash else ctx.summary.buyingPower
        ctx.attributes["cashRequirement"] = required
        return if (required > available) {
            fail("Requires ${Decimals.report(required)} USD but only ${Decimals.report(available)} USD is available", RiskLevel.PORTFOLIO, available, required)
        } else {
            pass("Cash requirement ${Decimals.report(required)} within available ${Decimals.report(available)}", RiskLevel.PORTFOLIO, available, required)
        }
    }
}
