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
        val tf = runCatching { Timeframe.of(timeframe) }.getOrElse { throw Problems.badRequest("invalid-timeframe", "timeframe must be one of 1m, 5m, 15m, 1h, 4h, 1d") }
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

    companion object {
        const val DEFAULT_BARS = 120
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
