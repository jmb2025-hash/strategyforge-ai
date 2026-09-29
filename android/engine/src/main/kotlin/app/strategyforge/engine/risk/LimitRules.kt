package app.strategyforge.engine.risk

import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.money.Decimals
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration

/** Every deterministic rule, in the Version 1 evaluation order (limit gates, core checks, limits). */
object StandardRules {
    fun all(): List<RiskRule> =
        listOf(
            EmergencyControlsRule(),
            StrategyStatusRule(),
            PortfolioActiveRule(),
            ReconciliationRule(),
            InstrumentTradableRule(),
            QuantityRule(),
            PriceSanityRule(),
            MarketDataVerifiedRule(),
            TradingScheduleRule(),
            HoldingsRule(),
            ShortingPermittedRule(),
            BuyingPowerRule(),
            SystemHealthRule(),
            SymbolListRule(),
            TradeValueRule(),
            AllocationRule(),
            OpenPositionsRule(),
            TradeFrequencyRule(),
            LossLimitsRule(),
            ShortExposureRule(),
            MarketQualityRule(),
        )
}

/** Helpers shared by limit rules. Missing limits or metrics make a rule UNVERIFIED (fail closed). */
abstract class LimitRule : RiskRule {
    protected fun notional(ctx: RiskContext): BigDecimal? = ctx.estimatedPrice?.let { ctx.intent.quantity.multiply(it) }

    protected fun pctOf(
        part: BigDecimal,
        whole: BigDecimal,
    ): BigDecimal? = if (whole.signum() <= 0) null else part.multiply(Decimals.HUNDRED).divide(whole, Decimals.MC)

    protected fun fmt(v: BigDecimal?) = v?.setScale(2, RoundingMode.HALF_EVEN)?.toPlainString() ?: "n/a"

    override fun evaluate(ctx: RiskContext): RuleResult {
        val l = ctx.limits ?: return unverified("Risk limits could not be loaded")
        val m = ctx.metrics ?: return unverified("Portfolio risk metrics could not be computed")
        return check(ctx, l, m)
    }

    abstract fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ): RuleResult
}

class EmergencyControlsRule : LimitRule() {
    override val name = "EMERGENCY_CONTROLS"
    override val family = "SYSTEM_SAFETY"
    override val blocksReducing = true

    override fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ): RuleResult {
        val exempt = ctx.intent.source == OrderSource.EMERGENCY_CLOSE || ctx.intent.source == OrderSource.FORCED_COVER
        return when {
            m.pauseAll && !exempt -> fail("Pause All is active: no new paper orders", RiskLevel.SYSTEM)
            m.preventNewPositions && ctx.intent.side.increasesRisk -> fail("Prevent New Positions is active: only position-reducing orders are allowed", RiskLevel.SYSTEM)
            else -> pass("No emergency control blocks this order", RiskLevel.SYSTEM)
        }
    }
}

class StrategyStatusRule : LimitRule() {
    override val name = "STRATEGY_STATUS"
    override val family = "ELIGIBILITY"

    override fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ): RuleResult {
        val src = ctx.intent.source
        if (src != OrderSource.RECOMMENDATION && src != OrderSource.AUTONOMOUS) return na()
        val status = m.strategyStatus ?: return fail("Strategy not found", RiskLevel.STRATEGY)
        val ok = if (src == OrderSource.AUTONOMOUS) status == "ACTIVE_AUTONOMOUS" else status == "ACTIVE_RECOMMENDATION" || status == "ACTIVE_AUTONOMOUS"
        return if (ok) pass("Strategy is $status", RiskLevel.STRATEGY) else fail("Strategy is $status", RiskLevel.STRATEGY)
    }
}

class SystemHealthRule : LimitRule() {
    override val name = "SYSTEM_HEALTH"
    override val family = "SYSTEM_SAFETY"

    override fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ) = if (kotlin.math.abs(m.clockDriftMs) > MAX_DRIFT_MS) unverified("Clock drift ${m.clockDriftMs}ms exceeds ${MAX_DRIFT_MS}ms") else pass("Clock drift ${m.clockDriftMs}ms", RiskLevel.SYSTEM)

    companion object {
        const val MAX_DRIFT_MS = 10_000L
    }
}

class SymbolListRule : LimitRule() {
    override val name = "SYMBOL_LISTS"
    override val family = "ELIGIBILITY"

    override fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ): RuleResult {
        val s = ctx.instrument.symbol
        if (s in l.denySymbols) return fail("$s is on the deny list", RiskLevel.GLOBAL)
        val allow = l.allowSymbols ?: return pass("No allow list restricts $s")
        return if (s in allow.value) pass("$s is allow-listed", allow.level) else fail("$s is not on the allow list", allow.level)
    }
}

class TradeValueRule : LimitRule() {
    override val name = "TRADE_VALUE"
    override val family = "EXPOSURE"

    override fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ): RuleResult {
        val n = notional(ctx) ?: return unverified("No price to value the trade")
        l.maxTradeValue?.let { if (n > it.value) return fail("Trade value ${fmt(n)} exceeds ${fmt(it.value)}", it.level, it.value, n) }
        l.maxTradePercentOfEquity?.let { lim ->
            val pct = pctOf(n, m.equity) ?: return unverified("Equity is not positive")
            if (pct > lim.value) return fail("Trade is ${fmt(pct)}% of equity; limit ${fmt(lim.value)}%", lim.level, lim.value, pct)
        }
        return pass("Trade value ${fmt(n)} within limits")
    }
}

class AllocationRule : LimitRule() {
    override val name = "ALLOCATION"
    override val family = "EXPOSURE"

    override fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ): RuleResult {
        val n = notional(ctx) ?: return unverified("No price to value the trade")
        l.maxInstrumentAllocationPercent?.let { lim ->
            val pct = pctOf(m.instrumentValue.add(n), m.equity) ?: return unverified("Equity is not positive")
            if (pct > lim.value) return fail("${ctx.instrument.symbol} would be ${fmt(pct)}% of equity; limit ${fmt(lim.value)}%", lim.level, lim.value, pct)
        }
        l.maxAssetClassAllocationPercent[ctx.instrument.assetClass.name]?.let { lim ->
            val pct = pctOf(m.assetClassValue.add(n), m.equity) ?: return unverified("Equity is not positive")
            if (pct > lim.value) return fail("${ctx.instrument.assetClass} would be ${fmt(pct)}% of equity; limit ${fmt(lim.value)}%", lim.level, lim.value, pct)
        }
        l.maxStrategyAllocationPercent?.takeIf { ctx.intent.strategyId != null }?.let { lim ->
            val pct = pctOf(m.strategyExposure.add(n), m.equity) ?: return unverified("Equity is not positive")
            if (pct > lim.value) return fail("Strategy allocation would be ${fmt(pct)}% of equity; limit ${fmt(lim.value)}%", lim.level, lim.value, pct)
        }
        return pass("Instrument, asset-class and strategy allocation within limits")
    }
}

class OpenPositionsRule : LimitRule() {
    override val name = "OPEN_POSITIONS"
    override val family = "EXPOSURE"

    override fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ): RuleResult {
        val adds = ctx.summary.positions.none { it.instrumentId == ctx.instrument.id }
        if (adds) {
            l.maxOpenPositions?.let { if (m.openPositions + 1 > it.value) return fail("Would open position ${m.openPositions + 1}; limit ${it.value}", it.level, it.value, m.openPositions + 1) }
            m.strategyLimits?.let {
                if (m.strategyOpenPositions + 1 > it.maxOpenPositions) return fail("Strategy would hold ${m.strategyOpenPositions + 1} positions; limit ${it.maxOpenPositions}", RiskLevel.STRATEGY)
            }
        }
        return pass("Open positions ${m.openPositions} within limits")
    }
}

class TradeFrequencyRule : LimitRule() {
    override val name = "TRADE_FREQUENCY"
    override val family = "ACTIVITY"

    override fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ): RuleResult {
        l.maxTradesPerMinute?.let { if (m.ordersLastMinute + 1 > it.value) return fail("${m.ordersLastMinute} orders in the last minute; limit ${it.value}", it.level, it.value, m.ordersLastMinute) }
        l.maxTradesPerHour?.let { if (m.ordersLastHour + 1 > it.value) return fail("${m.ordersLastHour} orders in the last hour; limit ${it.value}", it.level, it.value, m.ordersLastHour) }
        l.maxTradesPerDay?.let { if (m.ordersToday + 1 > it.value) return fail("${m.ordersToday} orders today; limit ${it.value}", it.level, it.value, m.ordersToday) }
        m.strategyLimits?.let { if (m.strategyOrdersToday + 1 > it.maxDailyTrades) return fail("Strategy placed ${m.strategyOrdersToday} orders today; limit ${it.maxDailyTrades}", RiskLevel.STRATEGY) }
        l.cooldownSeconds?.takeIf { it.value > 0 }?.let { c ->
            val last = m.lastOrderForInstrumentAt
            if (last != null && Duration.between(last, ctx.now).seconds < c.value) return fail("Cooldown of ${c.value}s for ${ctx.instrument.symbol} has not elapsed", c.level, c.value, Duration.between(last, ctx.now).seconds)
        }
        return pass("Order frequency within limits")
    }
}

class LossLimitsRule : LimitRule() {
    override val name = "LOSS_LIMITS"
    override val family = "LOSS"

    override fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ): RuleResult {
        if (!ctx.summary.fullyPriced) return unverified("Portfolio valuation is not fully priced; loss limits cannot be verified")
        l.maxDailyLossPercent?.let { lim ->
            val loss = pctOf(m.dayStartEquity.subtract(m.equity), m.dayStartEquity) ?: return unverified("No day-start equity")
            if (loss >= lim.value) return fail("Daily loss ${fmt(loss)}% reached limit ${fmt(lim.value)}%", lim.level, lim.value, loss)
        }
        l.maxDrawdownPercent?.let { lim ->
            val dd = pctOf(m.peakEquity.subtract(m.equity), m.peakEquity) ?: return unverified("No peak equity")
            if (dd >= lim.value) return fail("Drawdown ${fmt(dd)}% reached limit ${fmt(lim.value)}%", lim.level, lim.value, dd)
        }
        l.maxConsecutiveLosses?.let { if (m.consecutiveLosses >= it.value) return fail("${m.consecutiveLosses} consecutive losing trades; limit ${it.value}", it.level, it.value, m.consecutiveLosses) }
        m.strategyLimits?.maxConsecutiveLosses?.let { if (m.strategyConsecutiveLosses >= it) return fail("Strategy has $it consecutive losses", RiskLevel.STRATEGY, it, m.strategyConsecutiveLosses) }
        return pass("Daily loss, drawdown and loss streak within limits")
    }
}

class ShortExposureRule : LimitRule() {
    override val name = "SHORT_EXPOSURE"
    override val family = "SHORTING"

    override fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ): RuleResult {
        if (ctx.intent.side != OrderSide.SELL_SHORT) return na()
        l.shortingAllowed?.let { if (!it.value) return fail("Shorting is not allowed at ${it.level} level", it.level) }
        val n = notional(ctx) ?: return unverified("No price to value the short")
        l.maxShortExposurePercent?.let { lim ->
            val pct = pctOf(m.shortExposure.add(n), m.equity) ?: return unverified("Equity is not positive")
            if (pct > lim.value) return fail("Short exposure would be ${fmt(pct)}% of equity; limit ${fmt(lim.value)}%", lim.level, lim.value, pct)
        }
        return pass("Short exposure within limits")
    }
}

class MarketQualityRule : LimitRule() {
    override val name = "MARKET_QUALITY"
    override val family = "MARKET_QUALITY"

    override fun check(
        ctx: RiskContext,
        l: EffectiveLimits,
        m: RiskMetrics,
    ): RuleResult {
        val q = ctx.quote.quote ?: return unverified("No verified quote")
        l.maxQuoteAgeSeconds?.let { lim ->
            val age = ctx.quote.ageSeconds ?: return unverified("Quote age unknown")
            if (age > lim.value) return fail("Quote age ${age}s exceeds ${lim.value}s", lim.level, lim.value, age)
        }
        if (q.bid != null && q.ask != null) {
            l.maxSpreadPercent?.let { lim ->
                val spread = pctOf(q.ask.subtract(q.bid), q.mid) ?: return unverified("Invalid quote")
                if (spread > lim.value) return fail("Spread ${fmt(spread)}% exceeds ${fmt(lim.value)}%", lim.level, lim.value, spread)
            }
        }
        l.maxParticipationPercent?.let { lim ->
            val adv = m.averageDailyVolume ?: return unverified("Average daily volume unavailable; liquidity cannot be verified")
            val pct = pctOf(ctx.intent.quantity, adv) ?: return unverified("Average daily volume is zero")
            if (pct > lim.value) return fail("Order is ${fmt(pct)}% of average daily volume; limit ${fmt(lim.value)}%", lim.level, lim.value, pct)
        }
        l.maxPriceDeviationPercent?.let { lim ->
            val prev = m.previousClose ?: return unverified("Previous close unavailable; abnormal price moves cannot be checked")
            val dev = pctOf(q.last.subtract(prev).abs(), prev) ?: return unverified("Invalid previous close")
            if (dev > lim.value) return fail("Price moved ${fmt(dev)}% from the previous close; limit ${fmt(lim.value)}%", lim.level, lim.value, dev)
        }
        return pass("Quote age, spread${if (q.bid == null) " (not quoted by provider; fallback spread applies)" else ""}, liquidity and price deviation within limits")
    }
}
