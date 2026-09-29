package app.strategyforge.engine.support

import app.strategyforge.engine.Engine
import app.strategyforge.engine.db.JdbcSqlBackend
import app.strategyforge.engine.execution.OrderRequest
import app.strategyforge.engine.execution.OrderResult
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderType
import app.strategyforge.engine.execution.TimeInForce
import app.strategyforge.engine.portfolio.CostModel
import app.strategyforge.engine.portfolio.PortfolioCreate
import app.strategyforge.engine.risk.OrderSource
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/** A fresh engine on an in-memory SQLite database in demo (replay) mode, as the phone starts. */
object TestEngine {
    fun create(wall: Clock = Clock.systemUTC()): Engine =
        Engine(
            JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:")),
            wall,
            fixtureReader = { rel -> TestEngine::class.java.getResource("/replay/$rel")!!.readText() },
        )
}

fun Engine.portfolio(
    name: String = "P-" + UUID.randomUUID().toString().take(8),
    balance: String = "100000",
    costModel: CostModel? = null,
): UUID = portfolios.create(PortfolioCreate(name, BigDecimal(balance), costModel)).id

fun Engine.order(
    portfolioId: UUID,
    symbol: String,
    side: String,
    qty: String,
    type: String = "MARKET",
    limit: String? = null,
    stop: String? = null,
    tif: TimeInForce = TimeInForce.GTC,
): OrderResult =
    orders.create(
        OrderRequest(portfolioId, symbol, OrderSide.valueOf(side), OrderType.valueOf(type), BigDecimal(qty), limit?.let(::BigDecimal), stop?.let(::BigDecimal), tif),
        OrderSource.MANUAL,
    )

fun Engine.advance(
    minutes: Long,
    step: Long = 1,
) {
    replay.advance(minutes, step)
}
