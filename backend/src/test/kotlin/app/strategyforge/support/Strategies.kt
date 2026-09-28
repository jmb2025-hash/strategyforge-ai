package app.strategyforge.support

import com.fasterxml.jackson.databind.JsonNode
import java.time.Duration
import java.time.Instant

/** Builds deterministic, always-triggering strategies and drives them to Paper Eligible for signal tests. */
object Strategies {
    fun alwaysLong(
        name: String,
        timeframe: String,
        symbol: String = "BTC-USD",
        quantity: String = "0.01",
        maxHoldingBars: Int = 3,
        maxConsecutiveLosses: Int = 5,
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
            "exitRules" to mapOf("stopLossPercent" to 20, "takeProfitPercent" to 20, "maximumHoldingBars" to maxHoldingBars),
            "positionSizing" to mapOf("method" to "FIXED_QUANTITY", "value" to java.math.BigDecimal(quantity)),
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

    /** Creates the strategy, backtests it on data before the replay start, and waits for Paper Eligible. */
    fun eligible(
        h: TestHttp,
        content: Map<String, Any?>,
        from: String,
        to: String,
    ): String {
        val r = h.post("/v1/strategies", mapOf("content" to content))
        check(r.status == 201) { "strategy create failed: $r" }
        val id = r.json["strategy"]["id"].asText()
        check(r.json["strategy"]["status"].asText() == "VALIDATED") { "strategy not validated: $r" }
        val b = h.post("/v1/backtests", mapOf("strategyId" to id, "from" to from, "to" to to, "startingCapital" to "100000"))
        check(b.status == 202) { "backtest failed to start: $b" }
        val bid = b.json["id"].asText()
        val deadline = Instant.now().plus(Duration.ofSeconds(180))
        var last: JsonNode? = null
        while (Instant.now().isBefore(deadline)) {
            last = h.get("/v1/backtests/$bid").json
            if (last["status"].asText() in setOf("COMPLETED", "FAILED")) break
            Thread.sleep(200)
        }
        val status = h.get("/v1/strategies/$id").json["strategy"]["status"].asText()
        check(status == "PAPER_ELIGIBLE") { "strategy $id is $status after backtest $last" }
        return id
    }

    fun activate(
        h: TestHttp,
        strategyId: String,
        portfolioId: String,
        body: Map<String, Any?> = emptyMap(),
    ): Resp = h.post("/v1/strategies/$strategyId/activate", mapOf("portfolioId" to portfolioId, "allocationPercent" to "50") + body)

    fun recommendations(
        h: TestHttp,
        strategyId: String,
        status: String? = null,
    ): List<JsonNode> {
        val q = "/v1/recommendations?strategyId=$strategyId&limit=100" + (status?.let { "&status=$it" } ?: "")
        val r = h.get(q)
        check(r.status == 200) { r.toString() }
        return r.json["items"].toList()
    }
}
