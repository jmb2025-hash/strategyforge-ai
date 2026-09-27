package app.strategyforge.risk

import app.strategyforge.common.db.uuid
import app.strategyforge.common.money.Decimals
import app.strategyforge.execution.OrderSide
import app.strategyforge.execution.OrderType
import app.strategyforge.execution.Pricing
import app.strategyforge.execution.QuoteInput
import app.strategyforge.market.InstrumentService
import app.strategyforge.market.MarketCalendar
import app.strategyforge.market.MarketClock
import app.strategyforge.market.SessionState
import app.strategyforge.market.data.MarketDataService
import app.strategyforge.portfolio.PortfolioService
import org.springframework.core.annotation.Order
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import java.math.BigDecimal

@Component
class DefaultRiskContextFactory(
    private val portfolios: PortfolioService,
    private val instruments: InstrumentService,
    private val market: MarketDataService,
    private val clock: MarketClock,
    private val jdbc: JdbcClient,
) : RiskContextFactory {
    override fun build(intent: OrderIntent): RiskContext {
        val p = portfolios.get(intent.portfolioId)
        val i = instruments.byId(intent.instrumentId)
        val now = clock.now()
        val quote = market.verifyQuote(i, p.costModel.executionMaxQuoteAgeSeconds, now)
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
            jdbc
                .sql(
                    """
                    select id, instrument_id, side, quantity - filled_quantity as remaining, reserved_amount - reservation_released as reserved
                    from paper_orders where portfolio_id = :p and status in ('VALIDATED', 'PENDING', 'PARTIALLY_FILLED')
                    """.trimIndent(),
                ).param("p", p.id)
                .query { rs, _ ->
                    OpenOrderInfo(rs.uuid("id"), rs.uuid("instrument_id"), OrderSide.valueOf(rs.getString("side")), rs.getBigDecimal("remaining"), rs.getBigDecimal("reserved"))
                }.list()
        return RiskContext(intent, p, portfolios.summary(p.id), i, quote, est, open, now)
    }
}

@Component
@Order(10)
class PortfolioActiveRule : RiskRule {
    override val name = "PORTFOLIO_ACTIVE"
    override val family = "ELIGIBILITY"
    override val blocksReducing = true

    override fun evaluate(ctx: RiskContext) = if (ctx.portfolio.status == "ACTIVE" && ctx.portfolio.accountType == "PAPER") pass("Paper portfolio is active") else fail("Portfolio is ${ctx.portfolio.status}")
}

@Component
@Order(11)
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

@Component
@Order(12)
class InstrumentTradableRule : RiskRule {
    override val name = "INSTRUMENT_TRADABLE"
    override val family = "ELIGIBILITY"

    override fun evaluate(ctx: RiskContext) = if (ctx.instrument.active) pass("${ctx.instrument.symbol} is active") else fail("${ctx.instrument.symbol} is deactivated")
}

@Component
@Order(13)
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

@Component
@Order(14)
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

@Component
@Order(15)
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

@Component
@Order(16)
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

@Component
@Order(17)
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

@Component
@Order(18)
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

@Component
@Order(19)
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
