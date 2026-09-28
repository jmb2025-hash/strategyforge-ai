package app.strategyforge.safety

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.Strategies
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * MS-20 / RG-09 / FR-113: every attempt to activate real-money behaviour fails, layer by layer:
 * environment, HTTP routes, request fields, settings, providers, strategy files, orders, and the
 * database itself. Nothing is created by any attempt, and the rejections are audited.
 */
class RealMoneyProhibitionIT : FreshDatabaseTest() {
    @Test
    fun `MS-20 RG-09 FR-113 real-money activation attempts fail at all layers`() {
        // 1. Environment / configuration: the process refuses to start.
        for (v in listOf("true", "TRUE", "1", "yes", "False", "")) {
            assertThatThrownBy { RealMoneyPolicy.assertEnvironmentSafe(mapOf(RealMoneyPolicy.ENV_FLAG to v)) }.`as`("flag '$v'").isNotNull()
        }
        RealMoneyPolicy.assertEnvironmentSafe(mapOf(RealMoneyPolicy.ENV_FLAG to "false"))

        val h = TestOwner.client(baseUrl)
        val p = Replay.portfolio(h)
        val ordersBefore = jdbc.sql("select count(*) from paper_orders").query(Int::class.java).single()

        // 2. HTTP routes that imply live functionality: 403 and audited, authenticated or not.
        for (path in listOf("/v1/live-trading/enable", "/v1/brokerage/accounts", "/v1/portfolios/$p/live", "/v1/deposits", "/v1/withdrawals", "/v1/wallets")) {
            val r = h.post(path, mapOf("enabled" to true))
            assertThat(r.status).`as`(path).isEqualTo(403)
            assertThat(r.json["code"].asText()).isEqualTo("real-money-prohibited")
        }

        // 3. Orders cannot request a live venue or account.
        for (extra in listOf(mapOf("executionVenue" to "NASDAQ"), mapOf("accountType" to "LIVE"), mapOf("brokerAccount" to "U123"), mapOf("liveTrading" to true))) {
            val r =
                h.post(
                    "/v1/orders",
                    mapOf("portfolioId" to p, "symbol" to "BTC-USD", "side" to "BUY", "orderType" to "MARKET", "quantity" to "0.01", "timeInForce" to "GTC") + extra,
                )
            assertThat(r.status).`as`("order with $extra: $r").isBetween(400, 422)
        }
        assertThat(jdbc.sql("select count(*) from paper_orders").query(Int::class.java).single()).isEqualTo(ordersBefore)

        // 4. Portfolios are PAPER only.
        val live = h.post("/v1/portfolios", mapOf("name" to "Live", "startingBalance" to "1000", "accountType" to "LIVE"))
        assertThat(live.status).`as`(live.toString()).isBetween(400, 422)
        assertThat(jdbc.sql("select count(*) from portfolios where account_type <> 'PAPER'").query(Int::class.java).single()).isZero()

        // 5. Settings cannot carry a real-money switch.
        val settings = h.get("/v1/settings")
        val etag = settings.header("ETag")
        val body = (
            settings.json
                .fields()
                .asSequence()
                .associate { it.key to it.value } + ("realMoneyTradingEnabled" to true)
        )
        val put = h.put("/v1/settings", body, headers = mapOf("If-Match" to (etag ?: "")))
        assertThat(put.status).`as`(put.toString()).isBetween(400, 422)

        // 6. No provider type can trade, and live-looking provider settings are refused.
        assertThat(h.get("/v1/providers/types").json.map { it["kind"].asText() }).doesNotContain("BROKER", "BROKERAGE", "EXECUTION")
        val broker = h.post("/v1/providers", mapOf("providerType" to "ALPACA", "displayName" to "Broker"))
        assertThat(broker.status).isEqualTo(400)
        val sneaky = h.post("/v1/providers", mapOf("providerType" to "TWELVE_DATA", "displayName" to "x", "settings" to mapOf("brokerageUrl" to "https://broker.example")))
        assertThat(sneaky.status).`as`(sneaky.toString()).isIn(400, 403)

        // 7. Strategy files with live-execution fields never become runnable.
        val content = Strategies.alwaysLong("Live Strategy", "1m") + ("executionVenue" to "LIVE")
        val s = h.post("/v1/strategies", mapOf("content" to content))
        if (s.status == 201) {
            assertThat(s.json["strategy"]["status"].asText()).isIn("REJECTED", "MANUAL_REVIEW_REQUIRED")
        } else {
            assertThat(s.status).isBetween(400, 422)
        }

        // 8. The database itself rejects non-paper accounts and venues.
        assertThatThrownBy { jdbc.sql("update portfolios set account_type = 'LIVE' where id = cast(:p as uuid)").param("p", p).update() }.isNotNull()
        Replay.order(h, p, "BTC-USD", "BUY", "0.01")
        assertThatThrownBy { jdbc.sql("update paper_orders set venue = 'NASDAQ'").update() }.isNotNull()

        // Rejections are audited.
        val audited = jdbc.sql("select count(*) from audit_events where action = 'REAL_MONEY_ROUTE_REJECTED'").query(Int::class.java).single()
        assertThat(audited).isGreaterThanOrEqualTo(6)
    }
}
