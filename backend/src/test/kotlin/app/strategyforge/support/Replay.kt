package app.strategyforge.support

import java.util.UUID

object Replay {
    fun advance(
        h: TestHttp,
        minutes: Int,
        step: Int = 1,
    ) {
        val r = h.post("/v1/market-data/replay/advance", mapOf("minutes" to minutes, "stepMinutes" to step), idem = "adv-" + UUID.randomUUID())
        check(r.status == 200) { "advance failed: $r" }
    }

    fun portfolio(
        h: TestHttp,
        name: String = "P-" + UUID.randomUUID().toString().take(8),
        balance: String = "100000",
        costModel: Map<String, Any?>? = null,
    ): String {
        val body =
            buildMap<String, Any?> {
                put("name", name)
                put("startingBalance", balance)
                costModel?.let { put("costModel", it) }
            }
        val r = h.post("/v1/portfolios", body)
        check(r.status == 201) { "portfolio create failed: $r" }
        return r.json["id"].asText()
    }

    fun order(
        h: TestHttp,
        portfolioId: String,
        symbol: String,
        side: String,
        qty: String,
        type: String = "MARKET",
        limit: String? = null,
        stop: String? = null,
        tif: String = "GTC",
        key: String = UUID.randomUUID().toString(),
    ): Resp =
        h.post(
            "/v1/orders",
            buildMap {
                put("portfolioId", portfolioId)
                put("symbol", symbol)
                put("side", side)
                put("orderType", type)
                put("quantity", qty)
                put("timeInForce", tif)
                limit?.let { put("limitPrice", it) }
                stop?.let { put("stopPrice", it) }
            },
            idem = key,
        )
}
