package app.strategyforge.engine.reports

import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.portfolio.PortfolioService
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.order
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** D-038 chart data: candles with the portfolio's trades on them, and equity curves by range. */
class ChartsTest {
    private val e = TestEngine.create()
    private val p = e.portfolio(balance = "1000000")

    @Test
    fun `candles come back oldest first with this portfolio's fills as markers`() {
        e.advance(90)
        e.order(p, "BTC-USD", "BUY", "0.2")
        e.advance(5)
        e.order(p, "BTC-USD", "SELL", "0.1")
        e.advance(5)
        val other = e.portfolio(name = "Other")
        e.order(other, "BTC-USD", "BUY", "0.3")
        e.advance(2)

        val c = e.charts.candles("btc-usd", "1m", 60, portfolioId = p)
        assertThat(c.symbol).isEqualTo("BTC-USD")
        assertThat(c.bars).hasSizeLessThanOrEqualTo(60).isNotEmpty()
        assertThat(c.bars.map { it.openTime }).isSorted()
        assertThat(c.trades.map { it.side }).containsExactly("BUY", "SELL")
        assertThat(c.trades.map { it.quantity.stripTrailingZeros().toPlainString() }).containsExactly("0.2", "0.1")
        assertThat(c.trades).allSatisfy { assertThat(it.at).isAfterOrEqualTo(c.bars.first().openTime) }

        assertThat(e.charts.candles("BTC-USD", "1m", 60).trades).`as`("all portfolios").hasSize(3)
        assertThat(e.charts.candles("BTC-USD", "5m", 20).bars).isNotEmpty().hasSizeLessThanOrEqualTo(20)
        assertThat(assertThrows<EngineException> { e.charts.candles("BTC-USD", "2m") }.code).isEqualTo("invalid-timeframe")
    }

    @Test
    fun `periodic snapshots draw the equity curve but never move risk limits`() {
        e.order(p, "BTC-USD", "BUY", "1")
        e.advance(60)
        val peakBefore = e.portfolios.peakEquity(p)

        val c = e.charts.equity(p, "1D")
        assertThat(c.points.size).`as`("a snapshot every 5 market minutes").isGreaterThanOrEqualTo(10)
        assertThat(c.points.map { it.at }).isSorted()
        assertThat(c.change).isNotNull()
        val periodic =
            e.db
                .sql("select count(*) from portfolio_equity_snapshots where portfolio_id = :p and source = :s")
                .param("p", p)
                .param("s", PortfolioService.PERIODIC)
                .long()
        assertThat(periodic).isGreaterThanOrEqualTo(10)
        assertThat(e.portfolios.peakEquity(p)).`as`("risk peak ignores chart snapshots").isEqualByComparingTo(peakBefore)
        assertThat(assertThrows<EngineException> { e.charts.equity(p, "2Y") }.code).isEqualTo("invalid-range")
    }

    @Test
    fun `D-080 a holding's history follows its fills with average cost, and lists its trades newest first`() {
        e.order(p, "BTC-USD", "BUY", "0.2")
        e.advance(30)
        e.order(p, "BTC-USD", "SELL", "0.1")
        e.advance(30)
        val h = e.charts.holding(p, "btc-usd", "1D")
        assertThat(h.symbol).isEqualTo("BTC-USD")
        assertThat(h.name).isNotBlank()
        assertThat(h.points).isNotEmpty()
        assertThat(h.points.map { it.at }).isSorted()
        val last = h.points.last()
        assertThat(last.quantity).isEqualByComparingTo("0.1")
        assertThat(last.value).isEqualByComparingTo(last.quantity.multiply(last.price))
        // The cost left matches the open position's cost basis.
        val position =
            e.portfolios
                .summary(p)
                .positions
                .single()
        assertThat(last.cost.subtract(position.costBasis).abs()).isLessThan(java.math.BigDecimal("0.01"))
        assertThat(h.trades.map { it.side }).containsExactly("SELL", "BUY")
        assertThat(h.realizedPnl).isEqualByComparingTo(h.trades.fold(java.math.BigDecimal.ZERO) { a, t -> a.add(t.realizedPnl) })
        assertThat(h.firstBoughtAt).isEqualTo(h.trades.last().at)
        assertThat(h.gainChange).isNotNull()
        assertThat(assertThrows<EngineException> { e.charts.holding(p, "BTC-USD", "2Y") }.code).isEqualTo("invalid-range")
    }

    @Test
    fun `thinning keeps the first and latest points`() {
        val thinned = ChartService.thin((1..1000).toList(), 50)
        assertThat(thinned).hasSize(50)
        assertThat(thinned.first()).isEqualTo(1)
        assertThat(thinned.last()).isEqualTo(1000)
        assertThat(thinned).isSorted()
        assertThat(ChartService.thin(listOf(1, 2, 3), 50)).containsExactly(1, 2, 3)
    }
}
