package app.strategyforge.engine.execution

import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.money.Decimals
import app.strategyforge.engine.portfolio.CostModel
import java.math.BigDecimal
import java.math.RoundingMode

enum class OrderSide(
    val increasesRisk: Boolean,
    val buys: Boolean,
) {
    BUY(true, true),
    SELL(false, false),
    SELL_SHORT(true, false),
    BUY_TO_COVER(false, true),
}

enum class OrderType { MARKET, LIMIT, STOP, STOP_LIMIT }

enum class TimeInForce { DAY, GTC }

data class QuoteInput(
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val last: BigDecimal,
)

data class FillPrice(
    val price: BigDecimal,
    val referencePrice: BigDecimal,
    val spreadCostPerUnit: BigDecimal,
    val slippagePerUnit: BigDecimal,
    val spreadSource: String,
)

/**
 * Pure execution-price arithmetic (FR-082). All values are BigDecimal; prices are rounded
 * to the instrument increment in the direction that is worse for the owner.
 */
object Pricing {
    private val TWO = BigDecimal(2)

    /** Executable side of the market: quoted bid/ask when available, else last +/- half the fallback spread. */
    fun touch(
        q: QuoteInput,
        buy: Boolean,
        assetClass: AssetClass,
        m: CostModel,
    ): Pair<BigDecimal, String> {
        if (q.bid != null && q.ask != null) return (if (buy) q.ask else q.bid) to "QUOTED"
        val pct = if (assetClass == AssetClass.CRYPTO) m.cryptoFallbackSpreadPercent else m.equityFallbackSpreadPercent
        val half =
            q.last
                .multiply(pct)
                .divide(Decimals.HUNDRED)
                .divide(TWO, Decimals.MC)
        return (if (buy) q.last.add(half) else q.last.subtract(half)) to "FALLBACK_${pct.toPlainString()}%"
    }

    fun mid(q: QuoteInput): BigDecimal = if (q.bid != null && q.ask != null) q.bid.add(q.ask).divide(TWO, Decimals.MC) else q.last

    /**
     * Price for a marketable fill. Slippage is applied adversely; for limit orders the price never
     * crosses the limit (the fill is skipped instead if the touch is not marketable).
     */
    fun fillPrice(
        q: QuoteInput,
        buy: Boolean,
        assetClass: AssetClass,
        m: CostModel,
        limit: BigDecimal?,
        increment: BigDecimal,
    ): FillPrice? {
        val (touch, source) = touch(q, buy, assetClass, m)
        if (limit != null && (if (buy) touch > limit else touch < limit)) return null
        val slip = touch.multiply(m.slippageBps).divide(Decimals.BPS, Decimals.MC)
        var p = if (buy) touch.add(slip) else touch.subtract(slip)
        if (limit != null) p = if (buy) p.min(limit) else p.max(limit)
        p = roundToIncrement(p, increment, if (buy) RoundingMode.CEILING else RoundingMode.FLOOR)
        if (limit != null) p = if (buy) p.min(limit) else p.max(limit)
        if (p.signum() <= 0) return null
        val ref = mid(q)
        val spreadPerUnit = (if (buy) touch.subtract(ref) else ref.subtract(touch)).max(BigDecimal.ZERO)
        val slipPerUnit = (if (buy) p.subtract(touch) else touch.subtract(p)).max(BigDecimal.ZERO)
        return FillPrice(Decimals.price(p), Decimals.price(ref), spreadPerUnit, slipPerUnit, source)
    }

    /** Whether a stop has been triggered by the last trade price. */
    fun stopTriggered(
        side: OrderSide,
        stop: BigDecimal,
        last: BigDecimal,
    ): Boolean = if (side.buys) last >= stop else last <= stop

    fun roundToIncrement(
        p: BigDecimal,
        increment: BigDecimal,
        mode: RoundingMode,
    ): BigDecimal {
        if (increment.signum() <= 0) return p
        return p.divide(increment, 0, mode).multiply(increment)
    }

    fun commission(
        m: CostModel,
        quantity: BigDecimal,
        notional: BigDecimal,
        firstFill: Boolean,
    ): BigDecimal {
        val perOrder = if (firstFill) m.commissionPerOrder else BigDecimal.ZERO
        val perShare = m.commissionPerShare.multiply(quantity)
        val pct = notional.abs().multiply(m.commissionPercent).divide(Decimals.HUNDRED, Decimals.MC)
        return Decimals.money(perOrder.add(perShare).add(pct))
    }

    /** Conservative cash to hold for a buy-side order: worst expected price x (1 + buffer) plus fees. */
    fun buyReservation(
        m: CostModel,
        quantity: BigDecimal,
        worstPrice: BigDecimal,
    ): BigDecimal {
        val notional = quantity.multiply(worstPrice).multiply(BigDecimal.ONE.add(m.reservationBufferPercent.divide(Decimals.HUNDRED)))
        return Decimals.money(notional.add(commission(m, quantity, notional, true)))
    }

    /** Collateral held for a short sale beyond its proceeds (initial margin). */
    fun shortReservation(
        m: CostModel,
        quantity: BigDecimal,
        price: BigDecimal,
    ): BigDecimal = Decimals.money(quantity.multiply(price).multiply(m.shortInitialMarginPercent).divide(Decimals.HUNDRED, Decimals.MC))

    /** Liquidity cap: participation rate of observed bar volume, floored to the quantity increment. */
    fun liquidityCap(
        m: CostModel,
        barVolume: BigDecimal?,
        increment: BigDecimal,
    ): BigDecimal? = barVolume?.let { Decimals.floorToStep(it.multiply(m.participationRatePercent).divide(Decimals.HUNDRED, Decimals.MC), increment) }
}
