package app.strategyforge.engine.backtest

import app.strategyforge.engine.execution.Pricing
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.market.CorporateActionType
import app.strategyforge.engine.market.MarketCalendar
import app.strategyforge.engine.money.BigMath
import app.strategyforge.engine.money.Decimals
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.risk.RiskLimits
import app.strategyforge.engine.strategy.Indicators
import app.strategyforge.engine.strategy.RuleEvaluator
import app.strategyforge.engine.strategy.SeriesContext
import app.strategyforge.engine.strategy.SizingMethod
import app.strategyforge.engine.strategy.StrategyDefinition
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

data class SimpleCorporateAction(
    val type: CorporateActionType,
    val exDate: LocalDate,
    val ratioNew: BigDecimal?,
    val ratioOld: BigDecimal?,
    val cashAmount: BigDecimal?,
)

/** Point-in-time data for one symbol: bars may include warm-up history before the test window. */
data class SymbolSeries(
    val symbol: String,
    val assetClass: AssetClass,
    val bars: List<CandleData>,
    val priceIncrement: BigDecimal,
    val quantityIncrement: BigDecimal,
    val minQuantity: BigDecimal,
    val corporateActions: List<SimpleCorporateAction> = emptyList(),
)

data class BacktestParams(
    val from: Instant,
    val to: Instant,
    val startingCapital: BigDecimal,
    val costModel: CostModel,
    val executionDelayBars: Int = 1,
    /** Portfolio-level risk profile applied on top of the strategy's own limits (FR-051). */
    val riskProfile: RiskLimits = RiskLimits(),
)

data class BacktestTrade(
    val symbol: String,
    val side: String,
    val entryTime: Instant,
    val entryPrice: BigDecimal,
    val exitTime: Instant?,
    val exitPrice: BigDecimal?,
    val quantity: BigDecimal,
    val grossPnl: BigDecimal,
    val fees: BigDecimal,
    val spreadCost: BigDecimal,
    val slippageCost: BigDecimal,
    val borrowCost: BigDecimal,
    val dividends: BigDecimal,
    val netPnl: BigDecimal,
    val holdingBars: Int,
    val exitReason: String,
    val partialFill: Boolean,
)

data class SeriesPoint(
    val t: Instant,
    val v: BigDecimal,
)

data class BacktestOutput(
    val trades: List<BacktestTrade>,
    val equity: List<SeriesPoint>,
    val drawdown: List<SeriesPoint>,
    val endingCash: BigDecimal,
    val openPositionsValue: BigDecimal,
    val unfilledOrders: Int,
    val partialFills: Int,
    val adjustmentsApplied: Int,
    val barsWithExposure: Int,
    val timelineBars: Int,
    val tradedNotional: BigDecimal,
    val riskBlockedEntries: Int = 0,
)

/**
 * Deterministic, point-in-time, event-driven backtester (section 12, FR-050..FR-053).
 *
 * At each bar close only bars up to that close are visible; decisions made at the close of
 * bar i are filled at the open of bar i + executionDelayBars. Resting stops and targets fill
 * inside the bar using its high/low (stop assumed first when both are touched). Indicator
 * series are causal, so values at index i never depend on later bars.
 */
class BacktestEngine(
    private val def: StrategyDefinition,
    private val params: BacktestParams,
) {
    fun run(data: List<SymbolSeries>): BacktestOutput = Simulation(def, params, data).run()
}

/** Mutable state of one backtest run. Each step method sees only data up to the current bar. */
private class Simulation(
    private val def: StrategyDefinition,
    private val params: BacktestParams,
    private val data: List<SymbolSeries>,
) {
    private val mc = MathContext(34, RoundingMode.HALF_EVEN)
    private val m = params.costModel
    private val rp = params.riskProfile

    // Strictest of the strategy's limits and the risk profile (FR-091).
    private val maxOpen = minOf(def.risk.maximumOpenPositions, rp.maxOpenPositions ?: Int.MAX_VALUE)
    private val maxDayTrades = minOf(def.risk.maximumDailyTrades, rp.maxTradesPerDay ?: Int.MAX_VALUE)
    private val maxDailyLoss = listOfNotNull(def.risk.maximumDailyLossPercent, rp.maxDailyLossPercent).min()
    private val maxDrawdown = listOfNotNull(def.risk.maximumDrawdownPercent, rp.maxDrawdownPercent).minOrNull()
    private val maxTradePercent = listOfNotNull(rp.maxTradePercentOfEquity, rp.maxInstrumentAllocationPercent).minOrNull()
    private val shortingAllowed = rp.shortingAllowed != false

    /** One open position; long or short is per position because a strategy may trade both (D-042). */
    private class Pos(
        val symbol: String,
        val short: Boolean,
        var qty: BigDecimal,
        var entryPrice: BigDecimal,
        val entryTime: Instant,
        var entryFees: BigDecimal,
        var spread: BigDecimal,
        var slippage: BigDecimal,
        var borrow: BigDecimal = BigDecimal.ZERO,
        var dividends: BigDecimal = BigDecimal.ZERO,
        var bars: Int = 0,
        var extreme: BigDecimal,
        val partial: Boolean,
        var partialTaken: Boolean = false,
    )

    private data class Pending(
        val symbol: String,
        val fillIndex: Int,
        val entry: Boolean,
        val qty: BigDecimal?,
        val limit: BigDecimal?,
        val reason: String,
        val short: Boolean = false,
    )

    private val ctx = SeriesContext.of(def.timeframe, def.assetClass)
    private val index = data.associate { s -> s.symbol to s.bars.withIndex().associate { it.value.openTime to it.index } }
    private val evaluators = data.associate { s -> s.symbol to RuleEvaluator(s.bars, Indicators.compute(def.indicators, s.bars, ctx)) }
    private val timeline =
        data
            .flatMap { s -> s.bars.map { it.openTime } }
            .filter { !it.isBefore(params.from) && it.isBefore(params.to) }
            .toSortedSet()
            .toList()

    private var cash = params.startingCapital
    private val positions = linkedMapOf<String, Pos>()
    private val pending = mutableListOf<Pending>()
    private val trades = mutableListOf<BacktestTrade>()
    private val equity = mutableListOf<SeriesPoint>()
    private val lastClose = mutableMapOf<String, BigDecimal>()
    private var unfilled = 0
    private var partialFills = 0
    private var adjustments = 0
    private var exposureBars = 0
    private var turnover = BigDecimal.ZERO
    private var currentDay: LocalDate? = null
    private var dayStartEquity = params.startingCapital
    private var dayTrades = 0
    private var dayLosses = 0
    private var halted = false
    private var drawdownHalted = false
    private var peakEquity = params.startingCapital
    private var riskBlocked = 0
    private val appliedActions = mutableSetOf<Pair<String, LocalDate>>()

    fun run(): BacktestOutput {
        for (t in timeline) {
            val day = t.atZone(MarketCalendar.NEW_YORK).toLocalDate()
            if (day != currentDay) startDay(day)
            for (s in data) {
                val i = index.getValue(s.symbol)[t] ?: continue
                val bar = s.bars[i]
                applyCorporateActions(s, day)
                fillPending(s, i, bar, t)
                positions[s.symbol]?.let { protectiveExits(it, s, bar, t) }
                lastClose[s.symbol] = bar.close
            }
            val eq = markToMarket()
            equity += SeriesPoint(t, Decimals.money(eq))
            if (positions.isNotEmpty()) exposureBars++
            checkDailyLoss(eq)
            for (s in data) index.getValue(s.symbol)[t]?.let { decide(s, it, eq) }
        }
        return finish()
    }

    private fun markToMarket(): BigDecimal =
        positions.values.fold(cash) { acc, p ->
            val px = lastClose[p.symbol] ?: p.entryPrice
            if (p.short) acc.subtract(p.qty.multiply(px)) else acc.add(p.qty.multiply(px))
        }

    private fun halfSpread(ac: AssetClass) = (if (ac == AssetClass.CRYPTO) m.cryptoFallbackSpreadPercent else m.equityFallbackSpreadPercent).divide(BigDecimal(200), mc)

    private fun slip() = m.slippageBps.divide(Decimals.BPS, mc)

    private fun startDay(day: LocalDate) {
        currentDay = day
        dayStartEquity = markToMarket()
        dayTrades = 0
        dayLosses = 0
        halted = false
        positions.values.filter { it.short }.forEach { p ->
            val v = p.qty.multiply(lastClose[p.symbol] ?: p.entryPrice)
            val fee = v.multiply(m.borrowRateAnnualPercent).divide(Decimals.HUNDRED).divide(BigDecimal(360), mc)
            p.borrow = p.borrow.add(fee)
            cash = cash.subtract(fee)
        }
    }

    private fun checkDailyLoss(eq: BigDecimal) {
        if (halted || dayStartEquity.signum() <= 0) return
        val lossPct = dayStartEquity.subtract(eq).multiply(Decimals.HUNDRED).divide(dayStartEquity, mc)
        if (lossPct >= maxDailyLoss) halted = true
        peakEquity = peakEquity.max(eq)
        if (maxDrawdown != null && peakEquity.signum() > 0) {
            val dd = peakEquity.subtract(eq).multiply(Decimals.HUNDRED).divide(peakEquity, mc)
            if (dd >= maxDrawdown) drawdownHalted = true
        }
    }

    private fun applyCorporateActions(
        s: SymbolSeries,
        day: LocalDate,
    ) {
        s.corporateActions.filter { it.exDate == day && (s.symbol to day) !in appliedActions }.forEach { a ->
            appliedActions += s.symbol to day
            val p = positions[s.symbol] ?: return@forEach
            adjustments++
            when (a.type) {
                CorporateActionType.SPLIT -> {
                    val ratio = a.ratioNew!!.divide(a.ratioOld!!, mc)
                    p.qty = Decimals.quantity(p.qty.multiply(ratio))
                    p.entryPrice = Decimals.price(p.entryPrice.divide(ratio, mc))
                    p.extreme = p.extreme.divide(ratio, mc)
                }
                CorporateActionType.CASH_DIVIDEND -> {
                    val d = p.qty.multiply(a.cashAmount!!)
                    val signed = if (p.short) d.negate() else d
                    p.dividends = p.dividends.add(signed)
                    cash = cash.add(signed)
                }
            }
        }
    }

    private fun fillPending(
        s: SymbolSeries,
        i: Int,
        bar: CandleData,
        t: Instant,
    ) {
        pending.filter { it.symbol == s.symbol && it.fillIndex == i }.forEach { o ->
            pending.remove(o)
            if (o.entry) fillEntry(o, s, bar, t) else positions[s.symbol]?.let { closePosition(it, bar.open, t, o.reason, s, true) }
        }
    }

    private fun fillEntry(
        o: Pending,
        s: SymbolSeries,
        bar: CandleData,
        t: Instant,
    ) {
        val short = o.short
        val price = entryPrice(o, bar)
        if (price == null) {
            unfilled++
            return
        }
        val cap = Pricing.liquidityCap(m, bar.volume, s.quantityIncrement)
        var qty = Decimals.floorToStep(o.qty!!.min(cap ?: o.qty), s.quantityIncrement)
        val hs = if (o.limit != null) BigDecimal.ZERO else halfSpread(s.assetClass)
        val sl = if (o.limit != null) BigDecimal.ZERO else slip()
        val touch = if (short) price.multiply(BigDecimal.ONE.subtract(hs)) else price.multiply(BigDecimal.ONE.add(hs))
        var px = if (short) touch.multiply(BigDecimal.ONE.subtract(sl)) else touch.multiply(BigDecimal.ONE.add(sl))
        px = Pricing.roundToIncrement(px, s.priceIncrement, if (short) RoundingMode.FLOOR else RoundingMode.CEILING)
        if (!short) {
            val affordable = Decimals.floorToStep(cash.max(BigDecimal.ZERO).divide(px.multiply(BigDecimal("1.001")), 18, RoundingMode.FLOOR), s.quantityIncrement)
            qty = qty.min(affordable)
        }
        if (qty < s.minQuantity || qty.signum() <= 0) {
            unfilled++
            return
        }
        val isPartial = qty < o.qty
        if (isPartial) partialFills++
        val notional = qty.multiply(px)
        val fee = Pricing.commission(m, qty, notional, true)
        cash = if (short) cash.add(notional).subtract(fee) else cash.subtract(notional).subtract(fee)
        turnover = turnover.add(notional)
        positions[s.symbol] = Pos(s.symbol, short, qty, Decimals.price(px), t, fee, price.multiply(hs).multiply(qty), touch.subtract(px).abs().multiply(qty), extreme = px, partial = isPartial)
        dayTrades++
    }

    /** Market entries fill at the open; limit entries only if the bar trades through the limit. */
    private fun entryPrice(
        o: Pending,
        bar: CandleData,
    ): BigDecimal? {
        val limit = o.limit ?: return bar.open
        val marketable = if (o.short) bar.high >= limit else bar.low <= limit
        if (!marketable) return null
        return if (o.short) bar.open.max(limit) else bar.open.min(limit)
    }

    /**
     * Resting stop, trailing stop, partial target and target inside the bar. The stop is assumed
     * first; a partial target and the full target can both fill in one bar.
     */
    private fun protectiveExits(
        p: Pos,
        s: SymbolSeries,
        bar: CandleData,
        t: Instant,
    ) {
        val short = p.short
        val stopPct = def.exit.stopLossPercent.divide(Decimals.HUNDRED, mc)
        val tpPct = def.exit.takeProfitPercent.divide(Decimals.HUNDRED, mc)
        val up = { x: BigDecimal, f: BigDecimal -> x.multiply(BigDecimal.ONE.add(f)) }
        val down = { x: BigDecimal, f: BigDecimal -> x.multiply(BigDecimal.ONE.subtract(f)) }
        val partial = def.exit.partialTakeProfit
        // After a partial exit the stop may move to the entry price (D-042).
        val stop =
            if (p.partialTaken && partial?.moveStopToEntry == true) {
                p.entryPrice
            } else if (short) {
                up(p.entryPrice, stopPct)
            } else {
                down(p.entryPrice, stopPct)
            }
        val target = if (short) down(p.entryPrice, tpPct) else up(p.entryPrice, tpPct)
        val trail =
            def.exit.trailingStopPercent
                ?.divide(Decimals.HUNDRED, mc)
                ?.let { tr -> if (short) up(p.extreme, tr) else down(p.extreme, tr) }
        val effectiveStop =
            if (trail == null) {
                stop
            } else if (short) {
                stop.min(trail)
            } else {
                stop.max(trail)
            }
        val stopHit = if (short) bar.high >= effectiveStop else bar.low <= effectiveStop
        if (stopHit) {
            val reason =
                if (trail != null && effectiveStop == trail) {
                    "TRAILING_STOP"
                } else if (p.partialTaken && partial?.moveStopToEntry == true) {
                    "BREAKEVEN_STOP"
                } else {
                    "STOP_LOSS"
                }
            closePosition(p, if (short) bar.open.max(effectiveStop) else bar.open.min(effectiveStop), t, reason, s, true)
            return
        }
        if (partial != null && !p.partialTaken) {
            val at = partial.atPercent.divide(Decimals.HUNDRED, mc)
            val first = if (short) down(p.entryPrice, at) else up(p.entryPrice, at)
            if (if (short) bar.low <= first else bar.high >= first) {
                val part = Decimals.floorToStep(p.qty.multiply(partial.closePercent).divide(Decimals.HUNDRED, mc), s.quantityIncrement)
                p.partialTaken = true
                if (part >= s.minQuantity && part < p.qty) closePosition(p, if (short) bar.open.min(first) else bar.open.max(first), t, "PARTIAL_TAKE_PROFIT", s, false, part)
            }
        }
        val targetHit = if (short) bar.low <= target else bar.high >= target
        if (targetHit) {
            closePosition(p, if (short) bar.open.min(target) else bar.open.max(target), t, "TAKE_PROFIT", s, false)
        } else {
            p.extreme = if (short) p.extreme.min(bar.low) else p.extreme.max(bar.high)
        }
    }

    /** Closes [quantity] of the position (all of it by default); entry costs are shared pro rata. */
    private fun closePosition(
        p: Pos,
        rawPrice: BigDecimal,
        t: Instant,
        reason: String,
        s: SymbolSeries,
        adverseCosts: Boolean,
        quantity: BigDecimal = p.qty,
    ) {
        val short = p.short
        val qty = quantity.min(p.qty)
        val share = if (qty.compareTo(p.qty) == 0) BigDecimal.ONE else qty.divide(p.qty, mc)
        val hs = if (adverseCosts) halfSpread(s.assetClass) else BigDecimal.ZERO
        val sl = if (adverseCosts) slip() else BigDecimal.ZERO
        // Exiting a long sells (price down); exiting a short buys (price up).
        val touch = if (short) rawPrice.multiply(BigDecimal.ONE.add(hs)) else rawPrice.multiply(BigDecimal.ONE.subtract(hs))
        var px = if (short) touch.multiply(BigDecimal.ONE.add(sl)) else touch.multiply(BigDecimal.ONE.subtract(sl))
        px = Pricing.roundToIncrement(px, s.priceIncrement, if (short) RoundingMode.CEILING else RoundingMode.FLOOR)
        val notional = qty.multiply(px)
        val fee = Pricing.commission(m, qty, notional, true)
        cash = if (short) cash.subtract(notional).subtract(fee) else cash.add(notional).subtract(fee)
        turnover = turnover.add(notional)
        val gross = if (short) p.entryPrice.subtract(px).multiply(qty) else px.subtract(p.entryPrice).multiply(qty)
        val entryFees = p.entryFees.multiply(share, mc)
        val spread = p.spread.multiply(share, mc)
        val slippage = p.slippage.multiply(share, mc)
        val borrow = p.borrow.multiply(share, mc)
        val dividends = p.dividends.multiply(share, mc)
        val fees = entryFees.add(fee)
        val net = gross.subtract(fees).subtract(borrow).add(dividends)
        trades +=
            BacktestTrade(
                p.symbol,
                if (short) "SHORT" else "LONG",
                p.entryTime,
                p.entryPrice,
                t,
                Decimals.price(px),
                qty,
                Decimals.money(gross),
                Decimals.money(fees),
                Decimals.money(spread.add(rawPrice.multiply(hs).multiply(qty))),
                Decimals.money(slippage.add(touch.subtract(px).abs().multiply(qty))),
                Decimals.money(borrow),
                Decimals.money(dividends),
                Decimals.money(net),
                p.bars,
                reason,
                p.partial,
            )
        if (share.compareTo(BigDecimal.ONE) == 0) {
            positions.remove(p.symbol)
            if (net.signum() < 0) dayLosses++
        } else {
            p.qty = p.qty.subtract(qty)
            p.entryFees = p.entryFees.subtract(entryFees)
            p.spread = p.spread.subtract(spread)
            p.slippage = p.slippage.subtract(slippage)
            p.borrow = p.borrow.subtract(borrow)
            p.dividends = p.dividends.subtract(dividends)
        }
    }

    /** Decisions at the close of bar [i]; fills are scheduled for bar i + executionDelayBars. */
    private fun decide(
        s: SymbolSeries,
        i: Int,
        eq: BigDecimal,
    ) {
        val fillAt = i + params.executionDelayBars
        if (fillAt >= s.bars.size || pending.any { it.symbol == s.symbol }) return
        val ev = evaluators.getValue(s.symbol)
        val p = positions[s.symbol]
        if (p != null) {
            p.bars++
            val exitSignal = def.exit.conditionsFor(p.short, def.direction)?.let { ev.evaluate(it, i) } ?: false
            if (p.bars >= def.exit.maximumHoldingBars) {
                pending += Pending(s.symbol, fillAt, false, null, null, "MAX_HOLDING_BARS", p.short)
            } else if (exitSignal) {
                pending += Pending(s.symbol, fillAt, false, null, null, "EXIT_RULE", p.short)
            }
            return
        }
        val losingCap = def.risk.maximumDailyLosingTrades?.let { dayLosses >= it } ?: false
        val blocked =
            halted || drawdownHalted || losingCap || i + 1 < def.minimumHistoryBars ||
                positions.size + pending.count { it.entry } >= maxOpen || dayTrades >= maxDayTrades
        if (blocked) return
        val long = def.entryFor(false)?.let { ev.evaluate(it, i) } ?: false
        val shortSignal = def.entryFor(true)?.let { ev.evaluate(it, i) } ?: false
        // Conflicting long and short signals on the same bar: no trade.
        if (long == shortSignal) return
        val short = shortSignal
        if (short && !shortingAllowed) return
        val close = s.bars[i].close
        val qty = sizing(def, eq, close, maxTradePercent, rp.maxTradeValue)
        val limit =
            def.order.limitOffsetPercent?.takeIf { def.order.orderType == "LIMIT" }?.let { off ->
                val f = off.divide(Decimals.HUNDRED, mc)
                Pricing.roundToIncrement(if (short) close.multiply(BigDecimal.ONE.add(f)) else close.multiply(BigDecimal.ONE.subtract(f)), s.priceIncrement, RoundingMode.HALF_EVEN)
            }
        val notional = qty.multiply(close)
        val tooLarge =
            (rp.maxTradeValue != null && notional > rp.maxTradeValue) ||
                (maxTradePercent != null && eq.signum() > 0 && notional.multiply(Decimals.HUNDRED).divide(eq, mc) > maxTradePercent)
        if (tooLarge) {
            riskBlocked++
            return
        }
        pending += Pending(s.symbol, fillAt, true, Decimals.floorToStep(qty, s.quantityIncrement), limit, "ENTRY_RULE", short)
    }

    private fun finish(): BacktestOutput {
        val openValue =
            positions.values.fold(BigDecimal.ZERO) { a, p ->
                val v = p.qty.multiply(lastClose[p.symbol] ?: p.entryPrice)
                if (p.short) a.subtract(v) else a.add(v)
            }
        positions.values.forEach { p ->
            val px = lastClose[p.symbol] ?: p.entryPrice
            val gross = if (p.short) p.entryPrice.subtract(px).multiply(p.qty) else px.subtract(p.entryPrice).multiply(p.qty)
            trades +=
                BacktestTrade(
                    p.symbol,
                    if (p.short) "SHORT" else "LONG",
                    p.entryTime,
                    p.entryPrice,
                    null,
                    null,
                    p.qty,
                    Decimals.money(gross),
                    Decimals.money(p.entryFees),
                    Decimals.money(p.spread),
                    Decimals.money(p.slippage),
                    Decimals.money(p.borrow),
                    Decimals.money(p.dividends),
                    Decimals.money(gross.subtract(p.entryFees).subtract(p.borrow).add(p.dividends)),
                    p.bars,
                    "OPEN_AT_END",
                    p.partial,
                )
        }
        unfilled += pending.count { it.entry }
        var peak = BigDecimal.ZERO
        val dd =
            equity.map { pt ->
                if (pt.v > peak) peak = pt.v
                SeriesPoint(pt.t, if (peak.signum() == 0) BigDecimal.ZERO else Decimals.percent(peak.subtract(pt.v).multiply(Decimals.HUNDRED).divide(peak, mc)))
            }
        return BacktestOutput(trades, equity, dd, Decimals.money(cash), Decimals.money(openValue), unfilled, partialFills, adjustments, exposureBars, timeline.size, Decimals.money(turnover), riskBlocked)
    }
}

/**
 * Position size before rounding. D-042 adds RISK_PERCENT: the quantity whose stop-loss loss equals
 * the given percent of equity, reduced to fit the strictest position limit (the strategy's
 * maximumPositionPercent, the risk profile's per-trade percent and value) rather than skipped.
 */
fun sizing(
    def: StrategyDefinition,
    equity: BigDecimal,
    price: BigDecimal,
    profileMaxTradePercent: BigDecimal? = null,
    profileMaxTradeValue: BigDecimal? = null,
): BigDecimal {
    val mc = Decimals.MC
    if (price.signum() <= 0) return BigDecimal.ZERO
    return when (def.sizing.method) {
        SizingMethod.FIXED_QUANTITY -> def.sizing.value
        SizingMethod.FIXED_CASH -> def.sizing.value.divide(price, 18, RoundingMode.FLOOR)
        SizingMethod.PERCENT_OF_EQUITY -> equity.multiply(def.sizing.value).divide(Decimals.HUNDRED, mc).divide(price, 18, RoundingMode.FLOOR)
        SizingMethod.RISK_PERCENT -> {
            val risk = equity.multiply(def.sizing.value).divide(Decimals.HUNDRED, mc)
            val perUnit = price.multiply(def.exit.stopLossPercent).divide(Decimals.HUNDRED, mc)
            val byRisk = risk.divide(perUnit, 18, RoundingMode.FLOOR)
            val capPct = listOfNotNull(def.risk.maximumPositionPercent, profileMaxTradePercent, Decimals.HUNDRED).min()
            val capValue = listOfNotNull(equity.multiply(capPct).divide(Decimals.HUNDRED, mc), profileMaxTradeValue).min()
            // Slightly inside the cap so rounding never pushes the order over a limit.
            val byCap = capValue.multiply(BigDecimal("0.999")).divide(price, 18, RoundingMode.FLOOR)
            byRisk.min(byCap).max(BigDecimal.ZERO)
        }
    }
}

/** Performance and risk statistics (FR-052). All arithmetic is BigDecimal. */
object BacktestMetrics {
    private val mc = MathContext(34, RoundingMode.HALF_EVEN)

    fun compute(
        out: BacktestOutput,
        start: BigDecimal,
        from: Instant,
        to: Instant,
        benchmarkReturn: BigDecimal?,
    ): Map<String, Any?> {
        val end = out.equity.lastOrNull()?.v ?: start
        val netReturn = end.subtract(start).divide(start, mc)
        val days =
            Duration
                .between(from, minOf(to, out.equity.lastOrNull()?.t ?: to))
                .toHours()
                .toBigDecimal()
                .divide(BigDecimal(24), mc)
        val annualized =
            if (days >= BigDecimal(365) && end.signum() > 0) {
                BigMath.pow(end.divide(start, mc), BigDecimal("365.25").divide(days, mc)).subtract(BigDecimal.ONE)
            } else {
                null
            }
        val rets = out.equity.zipWithNext { a, b -> if (a.v.signum() == 0) BigDecimal.ZERO else b.v.divide(a.v, mc).subtract(BigDecimal.ONE) }
        val volatility =
            if (rets.size >= 2 && days.signum() > 0) {
                val mean = rets.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(rets.size), mc)
                val variance = rets.fold(BigDecimal.ZERO) { a, r -> a.add(r.subtract(mean).pow(2)) }.divide(BigDecimal(rets.size - 1), mc)
                val periodsPerYear = BigDecimal(out.equity.size).divide(days.divide(BigDecimal("365.25"), mc), mc)
                BigMath.sqrt(variance).multiply(BigMath.sqrt(periodsPerYear))
            } else {
                null
            }
        val closed = out.trades.filter { it.exitTime != null }
        val wins = closed.filter { it.netPnl.signum() > 0 }
        val losses = closed.filter { it.netPnl.signum() < 0 }
        val sumWins = wins.fold(BigDecimal.ZERO) { a, t -> a.add(t.netPnl) }
        val sumLosses = losses.fold(BigDecimal.ZERO) { a, t -> a.add(t.netPnl) }

        fun avg(xs: List<BigDecimal>) = if (xs.isEmpty()) null else Decimals.money(xs.fold(BigDecimal.ZERO, BigDecimal::add).divide(BigDecimal(xs.size), mc))
        val realized = closed.fold(BigDecimal.ZERO) { a, t -> a.add(t.netPnl) }
        val unrealized = out.trades.filter { it.exitTime == null }.fold(BigDecimal.ZERO) { a, t -> a.add(t.netPnl) }
        val avgEquity = if (out.equity.isEmpty()) start else out.equity.fold(BigDecimal.ZERO) { a, p -> a.add(p.v) }.divide(BigDecimal(out.equity.size), mc)

        fun pct(x: BigDecimal?) = x?.let { Decimals.percent(it.multiply(Decimals.HUNDRED)) }
        return linkedMapOf(
            "startingEquity" to Decimals.money(start),
            "endingEquity" to Decimals.money(end),
            "netReturnPercent" to pct(netReturn),
            "annualizedReturnPercent" to pct(annualized),
            "annualizedReturnNote" to if (annualized == null) "Not annualized: period shorter than one year" else null,
            "volatilityAnnualizedPercent" to pct(volatility),
            "maxDrawdownPercent" to (out.drawdown.maxOfOrNull { it.v } ?: BigDecimal.ZERO),
            "trades" to out.trades.size,
            "riskBlockedEntries" to out.riskBlockedEntries,
            "closedTrades" to closed.size,
            "winRatePercent" to if (closed.isEmpty()) null else Decimals.percent(BigDecimal(wins.size).multiply(Decimals.HUNDRED).divide(BigDecimal(closed.size), mc)),
            "lossRatePercent" to if (closed.isEmpty()) null else Decimals.percent(BigDecimal(losses.size).multiply(Decimals.HUNDRED).divide(BigDecimal(closed.size), mc)),
            "averageGain" to avg(wins.map { it.netPnl }),
            "averageLoss" to avg(losses.map { it.netPnl }),
            "expectancy" to avg(closed.map { it.netPnl }),
            "profitFactor" to if (sumLosses.signum() == 0) null else Decimals.percent(sumWins.divide(sumLosses.negate(), mc)),
            "realizedPnl" to Decimals.money(realized),
            "unrealizedPnl" to Decimals.money(unrealized),
            "exposurePercent" to if (out.timelineBars == 0) BigDecimal.ZERO else Decimals.percent(BigDecimal(out.barsWithExposure).multiply(Decimals.HUNDRED).divide(BigDecimal(out.timelineBars), mc)),
            "turnover" to if (avgEquity.signum() == 0) null else Decimals.percent(out.tradedNotional.divide(avgEquity, mc)),
            "fees" to Decimals.money(out.trades.fold(BigDecimal.ZERO) { a, t -> a.add(t.fees) }),
            "spreadCost" to Decimals.money(out.trades.fold(BigDecimal.ZERO) { a, t -> a.add(t.spreadCost) }),
            "slippage" to Decimals.money(out.trades.fold(BigDecimal.ZERO) { a, t -> a.add(t.slippageCost) }),
            "borrowCost" to Decimals.money(out.trades.fold(BigDecimal.ZERO) { a, t -> a.add(t.borrowCost) }),
            "dividends" to Decimals.money(out.trades.fold(BigDecimal.ZERO) { a, t -> a.add(t.dividends) }),
            "averageHoldingBars" to if (closed.isEmpty()) null else BigDecimal(closed.sumOf { it.holdingBars }).divide(BigDecimal(closed.size), 2, RoundingMode.HALF_EVEN),
            "benchmarkReturnPercent" to pct(benchmarkReturn),
            "benchmarkDifferencePercent" to benchmarkReturn?.let { pct(netReturn.subtract(it)) },
            "unfilledOrders" to out.unfilledOrders,
            "partialFills" to out.partialFills,
            "adjustmentsApplied" to out.adjustmentsApplied,
        )
    }
}
