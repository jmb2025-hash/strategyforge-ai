package app.strategyforge.engine.reports

import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuidOrNull
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.market.CandleData
import app.strategyforge.engine.market.InstrumentService
import app.strategyforge.engine.market.MarketClock
import app.strategyforge.engine.market.MarketDataService
import app.strategyforge.engine.market.Timeframe
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A simulated fill drawn on a price chart: buys below the bar, sells above it. */
data class TradeMarker(
    val at: Instant,
    val side: String,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val strategyId: UUID?,
)

data class CandleChart(
    val symbol: String,
    val timeframe: String,
    val status: String,
    val detail: String,
    val bars: List<CandleData>,
    val trades: List<TradeMarker>,
)

data class EquityPoint(
    val at: Instant,
    val value: BigDecimal,
)

data class EquityChart(
    val portfolioId: UUID,
    val range: String,
    val points: List<EquityPoint>,
    val change: BigDecimal?,
    val changePercent: BigDecimal?,
)

/** One point of a holding's history (D-080): what the shares held were worth, and what they cost. */
data class HoldingPoint(
    val at: Instant,
    val price: BigDecimal,
    val quantity: BigDecimal,
    val value: BigDecimal,
    val cost: BigDecimal,
)

/** A fill of one holding, newest first. */
data class HoldingTrade(
    val at: Instant,
    val side: String,
    val quantity: BigDecimal,
    val price: BigDecimal,
    val fees: BigDecimal,
    val realizedPnl: BigDecimal,
    val strategyId: UUID?,
)

/**
 * One symbol held in a portfolio over a range (D-080): the holding's value and cost through time,
 * the gain or loss over the range, the profit already taken by selling, and its trades.
 */
data class HoldingChart(
    val portfolioId: UUID,
    val symbol: String,
    val name: String,
    val assetClass: String,
    val range: String,
    val points: List<HoldingPoint>,
    /** Change in the holding's gain (value minus cost) over the range, so buying more is not counted as a gain. */
    val gainChange: BigDecimal?,
    /** Price change over the range, in percent. */
    val priceChangePercent: BigDecimal?,
    val realizedPnl: BigDecimal,
    val fees: BigDecimal,
    val firstBoughtAt: Instant?,
    val trades: List<HoldingTrade>,
)

/**
 * Chart data for the phone (D-038): recent candles with the simulated trades placed on them, and a
 * portfolio's equity curve for a time range, thinned to a size a phone can draw quickly.
 */
class ChartService(
    private val db: Db,
    private val instruments: InstrumentService,
    private val market: MarketDataService,
    private val marketClock: MarketClock,
) {
    fun candles(
        symbol: String,
        timeframe: String,
        bars: Int = DEFAULT_BARS,
        portfolioId: UUID? = null,
        strategyId: UUID? = null,
    ): CandleChart {
        val tf = runCatching { Timeframe.of(timeframe) }.getOrElse { throw Problems.badRequest("invalid-timeframe", "timeframe must be one of 1m, 5m, 15m, 30m, 1h, 4h, 1d") }
        val count = bars.coerceIn(MIN_BARS, MAX_BARS)
        val i = instruments.bySymbol(symbol.trim().uppercase())
        val now = marketClock.now()
        // Stock markets are closed most of the day and at weekends, so look further back for them.
        val factor =
            when {
                i.assetClass != AssetClass.US_EQUITY -> 1.0
                tf == Timeframe.D1 -> 1.6
                else -> 5.5
            }
        val span = Duration.ofSeconds((tf.duration.seconds * count * factor).toLong() + tf.duration.seconds)
        val series = market.candles(i, tf, now.minus(span), now)
        val shown = series.bars.takeLast(count)
        val from = shown.firstOrNull()?.openTime ?: now.minus(span)
        val trades =
            db
                .sql(
                    """
                    select e.executed_at, e.side, e.price, e.quantity, o.strategy_id from paper_executions e join paper_orders o on o.id = e.order_id
                    where e.instrument_id = :i and e.executed_at >= :from and e.executed_at <= :to
                      and (:p is null or e.portfolio_id = :p) and (:s is null or o.strategy_id = :s)
                    order by e.executed_at, e.fill_seq limit $MAX_TRADES
                    """.trimIndent(),
                ).param("i", i.id)
                .param("from", from)
                .param("to", now)
                .param("p", portfolioId)
                .param("s", strategyId)
                .list { TradeMarker(it.instant("executed_at"), it.str("side"), it.dec("price"), it.dec("quantity"), it.uuidOrNull("strategy_id")) }
        return CandleChart(i.symbol, tf.code, series.status.name, series.detail, shown, trades)
    }

    fun equity(
        portfolioId: UUID,
        range: String,
    ): EquityChart {
        val r = range.uppercase()
        val window =
            when (r) {
                "1D" -> Duration.ofDays(1)
                "1W" -> Duration.ofDays(7)
                "1M" -> Duration.ofDays(30)
                "3M" -> Duration.ofDays(90)
                "ALL" -> null
                else -> throw Problems.badRequest("invalid-range", "range must be one of 1D, 1W, 1M, 3M, ALL")
            }
        val from = window?.let { marketClock.now().minus(it) } ?: Instant.EPOCH
        val all =
            db
                .sql("select at, equity from portfolio_equity_snapshots where portfolio_id = :p and at >= :from order by at, id")
                .param("p", portfolioId)
                .param("from", from)
                .list { EquityPoint(it.instant("at"), it.dec("equity")) }
        val points = thin(all)
        val first = points.firstOrNull()?.value
        val last = points.lastOrNull()?.value
        val change = if (first != null && last != null && points.size >= 2) last.subtract(first) else null
        val pct = change?.takeIf { first!!.signum() > 0 }?.multiply(BigDecimal(100))?.divide(first, 2, java.math.RoundingMode.HALF_EVEN)
        return EquityChart(portfolioId, r, points, change, pct)
    }

    /**
     * [symbol]'s history in a portfolio over [range] (D-080). Quantity and cost follow the fills with
     * average cost: buying (or shorting) adds the price paid plus commission, and selling part of a
     * holding takes away that share of its cost. Value is the quantity at each bar's close; shorts are
     * negative, so value minus cost is the gain either way.
     */
    fun holding(
        portfolioId: UUID,
        symbol: String,
        range: String,
    ): HoldingChart {
        val r = range.uppercase()
        val i = instruments.bySymbol(symbol.trim().uppercase())
        val fills =
            db
                .sql(
                    """
                    select e.executed_at, e.side, e.quantity, e.price, e.commission, e.spread_cost, e.slippage_cost, e.realized_pnl, o.strategy_id
                    from paper_executions e join paper_orders o on o.id = e.order_id
                    where e.portfolio_id = :p and e.instrument_id = :i order by e.executed_at, e.fill_seq
                    """.trimIndent(),
                ).param("p", portfolioId)
                .param("i", i.id)
                .list {
                    HoldingTrade(
                        it.instant("executed_at"),
                        it.str("side"),
                        it.dec("quantity"),
                        it.dec("price"),
                        it.dec("commission"),
                        it.dec("realized_pnl"),
                        it.uuidOrNull("strategy_id"),
                    )
                }
        val now = marketClock.now()
        val (tf, window) =
            when (r) {
                "1D" -> Timeframe.of("5m") to Duration.ofDays(1)
                "1W" -> Timeframe.of("1h") to Duration.ofDays(7)
                "1M" -> Timeframe.of("4h") to Duration.ofDays(30)
                "3M" -> Timeframe.D1 to Duration.ofDays(90)
                "ALL" -> Timeframe.D1 to Duration.between(fills.firstOrNull()?.at ?: now.minus(Duration.ofDays(30)), now).plusDays(1)
                else -> throw Problems.badRequest("invalid-range", "range must be one of 1D, 1W, 1M, 3M, ALL")
            }
        val from = now.minus(window)
        val bars = market.candles(i, tf, from, now).bars
        // Running quantity and cost after each fill.
        val steps = mutableListOf<Triple<Instant, BigDecimal, BigDecimal>>()
        var qty = BigDecimal.ZERO
        var cost = BigDecimal.ZERO
        fills.forEach { f ->
            val signed = if (f.side == "BUY" || f.side == "BUY_TO_COVER") f.quantity else f.quantity.negate()
            val next = qty.add(signed)
            if (qty.signum() == 0 || qty.signum() == signed.signum()) {
                cost = cost.add(signed.multiply(f.price)).add(f.fees)
            } else if (next.signum() == 0 || next.signum() == qty.signum()) {
                cost = cost.multiply(next).divide(qty, 10, java.math.RoundingMode.HALF_EVEN)
            } else {
                // Crossed through zero: the remainder opens a new holding at this price.
                cost = next.multiply(f.price).add(f.fees)
            }
            qty = next
            steps += Triple(f.at, qty, cost)
        }

        fun held(at: Instant): Pair<BigDecimal, BigDecimal> = steps.lastOrNull { !it.first.isAfter(at) }?.let { it.second to it.third } ?: (BigDecimal.ZERO to BigDecimal.ZERO)
        val points =
            bars.map { b ->
                val end = b.openTime.plus(tf.duration).let { if (it.isAfter(now)) now else it }
                val (q, c) = held(end)
                HoldingPoint(end, b.close, q, q.multiply(b.close), c)
            }
        // Leave out the stretch before the first purchase.
        val shown = thin(points.dropWhile { it.quantity.signum() == 0 && it.cost.signum() == 0 })
        val gainChange =
            if (shown.size >= 2) {
                val g = { p: HoldingPoint -> p.value.subtract(p.cost) }
                g(shown.last()).subtract(g(shown.first()))
            } else {
                null
            }
        val firstPrice = points.firstOrNull()?.price
        val lastPrice = points.lastOrNull()?.price
        val priceChange =
            if (firstPrice != null && lastPrice != null && firstPrice.signum() > 0 && points.size >= 2) {
                lastPrice.subtract(firstPrice).multiply(BigDecimal(100)).divide(firstPrice, 2, java.math.RoundingMode.HALF_EVEN)
            } else {
                null
            }
        return HoldingChart(
            portfolioId,
            i.symbol,
            i.name,
            i.assetClass.name,
            r,
            shown,
            gainChange,
            priceChange,
            fills.fold(BigDecimal.ZERO) { a, f -> a.add(f.realizedPnl) },
            fills.fold(BigDecimal.ZERO) { a, f -> a.add(f.fees) },
            fills.firstOrNull { it.side == "BUY" || it.side == "SELL_SHORT" }?.at,
            fills.asReversed().take(MAX_HOLDING_TRADES),
        )
    }

    companion object {
        const val DEFAULT_BARS = 120
        private const val MAX_HOLDING_TRADES = 100
        const val MIN_BARS = 20
        const val MAX_BARS = 500
        const val MAX_POINTS = 400
        private const val MAX_TRADES = 1000

        /** Keeps at most [max] points, evenly spaced, always including the first and the latest. */
        fun <T> thin(
            points: List<T>,
            max: Int = MAX_POINTS,
        ): List<T> {
            if (points.size <= max) return points
            val step = (points.size - 1).toDouble() / (max - 1)
            return (0 until max).map { k -> points[Math.round(k * step).toInt().coerceAtMost(points.size - 1)] }
        }
    }
}
