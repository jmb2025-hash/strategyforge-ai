package app.strategyforge.engine.support

import app.strategyforge.engine.Engine
import app.strategyforge.engine.autonomy.Activation
import app.strategyforge.engine.autonomy.ActivationMode
import app.strategyforge.engine.autonomy.ActivationRequest
import app.strategyforge.engine.autonomy.AutonomyDisclosure
import app.strategyforge.engine.backtest.BacktestRequest
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.signals.Recommendation
import app.strategyforge.engine.strategy.StrategyStatus
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Deterministic, always-triggering strategies driven to Paper Eligible (ported from the server test support). */
object Strategies {
    fun alwaysLong(
        name: String,
        timeframe: String,
        symbol: String = "BTC-USD",
        quantity: String = "0.01",
        maxHoldingBars: Int = 3,
        maxConsecutiveLosses: Int = 5,
        stopLossPercent: Number = 20,
        takeProfitPercent: Number = 20,
    ): Map<String, Any?> =
        mapOf(
            "schemaVersion" to "1.0",
            "metadata" to mapOf("name" to name, "assetClass" to "CRYPTO", "timeframe" to timeframe, "createdBy" to "OWNER"),
            "universe" to mapOf("symbols" to listOf(symbol)),
            "dataRequirements" to
                mapOf(
                    "minimumHistoryBars" to 30,
                    "maximumQuoteAgeSeconds" to 120,
                    "indicators" to listOf(mapOf("id" to "EMA_5", "type" to "EMA", "period" to 5)),
                ),
            "entryRules" to mapOf("operator" to "ALL", "conditions" to listOf(mapOf("left" to "CLOSE", "comparison" to "GT", "right" to 1))),
            "exitRules" to mapOf("stopLossPercent" to stopLossPercent, "takeProfitPercent" to takeProfitPercent, "maximumHoldingBars" to maxHoldingBars),
            "positionSizing" to mapOf("method" to "FIXED_QUANTITY", "value" to BigDecimal(quantity)),
            "orderInstructions" to mapOf("orderType" to "MARKET", "timeInForce" to "GTC"),
            "riskLimits" to
                mapOf(
                    "maximumOpenPositions" to 3,
                    "maximumDailyTrades" to 200,
                    "maximumDailyLossPercent" to 5,
                    "maximumDrawdownPercent" to 20,
                    "maximumConsecutiveLosses" to maxConsecutiveLosses,
                    "allowShort" to false,
                ),
            "inactivityConditions" to listOf("STALE_MARKET_DATA", "RISK_ENGINE_UNAVAILABLE", "RECONCILIATION_FAILURE"),
        )

    /** Creates the strategy, backtests it on data before the replay start, and checks it is Paper Eligible. */
    fun Engine.eligible(
        content: Map<String, Any?>,
        from: String = "2026-06-22T00:00:00Z",
        to: String = "2026-06-22T13:00:00Z",
    ): UUID {
        val r = strategies.create(JacksonCanonical.mapper.valueToTree(content))
        check(r.strategy.status == StrategyStatus.VALIDATED) { "strategy not validated: ${r.validation.issues}" }
        val b = backtests.submit(BacktestRequest(r.strategy.id, from = Instant.parse(from), to = Instant.parse(to), startingCapital = BigDecimal("100000")))
        val status = strategies.get(r.strategy.id).status
        check(status == StrategyStatus.PAPER_ELIGIBLE) { "strategy is $status after backtest ${b.status} ${b.resultStatus} ${b.error} ${b.integrity}" }
        return r.strategy.id
    }

    fun Engine.activate(
        strategyId: UUID,
        portfolioId: UUID,
        mode: ActivationMode = ActivationMode.RECOMMENDATION,
        allocation: String = "50",
    ): Activation {
        if (mode == ActivationMode.AUTONOMOUS) auth.confirmed()
        return strategyControl.activate(strategyId, ActivationRequest(mode, portfolioId, BigDecimal(allocation), mode == ActivationMode.AUTONOMOUS, AutonomyDisclosure.VERSION.takeIf { mode == ActivationMode.AUTONOMOUS }))
    }

    fun Engine.recs(
        strategyId: UUID,
        status: String? = null,
    ): List<Recommendation> = recommendations.list(status, strategyId, 100)
}
