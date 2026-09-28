package app.strategyforge.e2e

import app.strategyforge.StrategyForgeApplication
import app.strategyforge.support.Postgres
import app.strategyforge.support.Replay
import app.strategyforge.support.Strategies
import app.strategyforge.support.TestHttp
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.boot.web.context.WebServerApplicationContext
import java.math.BigDecimal
import java.sql.DriverManager

/**
 * RG-07: the replay end-to-end suite runs twice, each time from a clean database with its own
 * application instance, and must produce identical outcomes. It covers manual orders,
 * Recommendation Mode with an accepted recommendation, and Autonomous Mode with protective exits.
 * The comparison digest excludes generated ids and wall-clock timestamps; market timestamps
 * (replay clock) are included.
 */
class ReplayEndToEndIT {
    @Test
    fun `RG-07 NFR-012 replay end-to-end suite passes twice from clean databases with identical results`() {
        val first = run("sf_e2e_a_" + System.nanoTime())
        val second = run("sf_e2e_b_" + System.nanoTime())
        assertThat(first).`as`("the scenario produces trading activity").anyMatch { it.startsWith("execution|") }
        assertThat(first.filter { it.startsWith("signal|") }).hasSizeGreaterThan(3)
        assertThat(first).contains("recommendation|E2E Recommendation|ACCEPTED|1")
        assertThat(first).anyMatch { it.startsWith("order|E2E Manual|MANUAL|FILLED") }
        assertThat(first).anyMatch { it.startsWith("signal|E2E Autonomous|ETH-USD|") && (it.endsWith("[\"STOP_LOSS\"]") || it.endsWith("[\"TAKE_PROFIT\"]")) }
        assertThat(second).isEqualTo(first)
    }

    private fun run(database: String): List<String> {
        val url = Postgres.freshDatabase(database)
        val ctx =
            SpringApplicationBuilder(StrategyForgeApplication::class.java)
                .profiles("test")
                .run(
                    "--spring.datasource.url=$url",
                    "--spring.datasource.username=${Postgres.container.username}",
                    "--spring.datasource.password=${Postgres.container.password}",
                    "--server.port=0",
                )
        try {
            val port = (ctx as WebServerApplicationContext).webServer.port
            scenario(TestOwner.client("http://localhost:$port"))
        } finally {
            ctx.close()
        }
        return digest(url)
    }

    private fun scenario(h: TestHttp) {
        val manual = Replay.portfolio(h, name = "E2E Manual", costModel = mapOf("commissionPerOrder" to "1.00"))
        val rec = Replay.portfolio(h, name = "E2E Recommendations")
        val auto = Replay.portfolio(h, name = "E2E Autonomous")

        // Manual trading: market and limit orders.
        check(Replay.order(h, manual, "BTC-USD", "BUY", "0.05").status == 201)
        check(Replay.order(h, manual, "ETH-USD", "BUY", "1").status == 201)
        Replay.advance(h, 2)
        check(Replay.order(h, manual, "BTC-USD", "SELL", "0.02").status == 201)

        // Recommendation Mode: one recommendation is accepted.
        val recStrategy = Strategies.eligible(h, Strategies.alwaysLong("E2E Recommendation", "1m", symbol = "SOL-USD", quantity = "2"), "2026-06-22T00:00:00Z", "2026-06-22T13:00:00Z")
        check(Strategies.activate(h, recStrategy, rec).status == 201)
        Replay.advance(h, 1)
        val pending = Strategies.recommendations(h, recStrategy, "PENDING").single()["id"].asText()
        val token = h.get("/v1/recommendations/$pending").json["actionToken"]["token"].asText()
        val accepted = h.post("/v1/recommendations/$pending/accept", mapOf("actionToken" to token))
        check(accepted.status == 200) { "accept failed: $accepted" }

        // Autonomous Mode with tight protective exits.
        val autoStrategy =
            Strategies.eligible(
                h,
                Strategies.alwaysLong("E2E Autonomous", "1m", symbol = "ETH-USD", quantity = "0.5", maxHoldingBars = 500, stopLossPercent = BigDecimal("0.05"), takeProfitPercent = BigDecimal("0.05")),
                "2026-06-22T00:00:00Z",
                "2026-06-22T13:00:00Z",
            )
        val version = h.get("/v1/autonomy/disclosure").json["version"].asText()
        val a = Strategies.activate(h, autoStrategy, auto, mapOf("mode" to "AUTONOMOUS", "disclosureAccepted" to true, "disclosureVersion" to version))
        check(a.status == 201) { "autonomous activation failed: $a" }
        Replay.advance(h, 20)
        check(h.get("/v1/reports/portfolios/$manual").status == 200)
    }

    private fun digest(url: String): List<String> =
        DriverManager.getConnection(url, Postgres.container.username, Postgres.container.password).use { c ->
            fun rows(sql: String): List<String> =
                c.createStatement().use { s ->
                    s.executeQuery(sql).use { rs ->
                        buildList {
                            while (rs.next()) add((1..rs.metaData.columnCount).joinToString("|") { i -> rs.getObject(i)?.let(::norm) ?: "null" })
                        }
                    }
                }
            buildList {
                addAll(rows("select 'market', market_time from replay_state"))
                addAll(
                    rows(
                        """
                        select 'execution', p.name, i.symbol, e.side, e.fill_seq, e.quantity, e.price, e.reference_price, e.commission, e.spread_cost, e.slippage_cost, e.realized_pnl
                        from paper_executions e join portfolios p on p.id = e.portfolio_id join instruments i on i.id = e.instrument_id
                        """.trimIndent(),
                    ),
                )
                addAll(
                    rows(
                        """
                        select 'signal', st.name, i.symbol, s.bucket_start, s.action, s.side, s.quantity, s.reference_price, s.disposition, s.triggered_rules::text
                        from signals s join strategies st on st.id = s.strategy_id join instruments i on i.id = s.instrument_id
                        """.trimIndent(),
                    ),
                )
                addAll(rows("select 'recommendation', st.name, r.status, count(*) from recommendations r join strategies st on st.id = r.strategy_id group by 2, 3"))
                addAll(rows("select 'order', p.name, o.source, o.status, o.side, o.quantity, o.filled_quantity from paper_orders o join portfolios p on p.id = o.portfolio_id"))
                addAll(rows("select 'ledger', p.name, l.account, sum(l.amount), sum(l.quantity) from ledger_entries l join portfolios p on p.id = l.portfolio_id group by 2, 3"))
                addAll(rows("select 'risk', decision, count(*) from risk_evaluations group by 2"))
                addAll(rows("select 'backtest', st.name, b.status, b.result_status, b.metrics::text from backtests b join strategies st on st.id = b.strategy_id"))
                addAll(rows("select 'strategy', name, status from strategies"))
            }.sorted()
        }

    private fun norm(v: Any): String =
        when (v) {
            is BigDecimal -> v.stripTrailingZeros().toPlainString()
            is java.time.OffsetDateTime -> v.toInstant().toString()
            is java.sql.Timestamp -> v.toInstant().toString()
            else -> v.toString()
        }
}
