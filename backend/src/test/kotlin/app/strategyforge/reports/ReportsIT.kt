package app.strategyforge.reports

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.math.BigDecimal
import java.time.Instant

/** FR-103 reports, FR-104 descriptive comparisons, FR-105 exports and MS-19 reconciliation. */
class ReportsIT : FreshDatabaseTest() {
    @Test
    fun `FR-103 FR-104 FR-105 MS-19 reports and exports reconcile to the ledger`() {
        val h = TestOwner.client(baseUrl)
        val p = Replay.portfolio(h, costModel = mapOf("commissionPerOrder" to "1.50"))
        assertThat(Replay.order(h, p, "BTC-USD", "BUY", "0.02").json["order"]["status"].asText()).isEqualTo("PENDING")
        assertThat(Replay.order(h, p, "ETH-USD", "BUY", "0.5").status).isEqualTo(201)
        Replay.advance(h, 2)
        assertThat(Replay.order(h, p, "BTC-USD", "SELL", "0.01").status).isEqualTo(201)
        Replay.advance(h, 2)

        val report = h.get("/v1/reports/portfolios/$p").json
        val costs = report["costs"]
        assertThat(BigDecimal(costs["commissions"].asText())).isEqualByComparingTo("4.50")
        assertThat(BigDecimal(costs["spread"].asText())).isPositive()
        assertThat(BigDecimal(costs["total"].asText())).isGreaterThan(BigDecimal("4.50"))
        assertThat(report["byAsset"].map { it["label"].asText() }).contains("BTC-USD", "ETH-USD")
        assertThat(report["byStrategy"].single()["label"].asText()).isEqualTo("Manual and emergency orders")
        assertThat(report["closedTrades"]).hasSize(1)
        assertThat(report["benchmarks"].map { it["symbol"].asText() }).containsExactly("SPY", "BTC-USD")
        assertThat(report["disclaimer"].asText()).contains("Simulated")

        val outcomes = h.get("/v1/reports/portfolios/$p/outcomes").json
        assertThat(outcomes["bySource"].single()["group"].asText()).isEqualTo("MANUAL")
        assertThat(outcomes["disclaimer"].asText()).contains("do not show").contains("caused")
        val risk = h.get("/v1/reports/portfolios/$p/risk").json
        assertThat(risk["decisions"]["ALLOW"].asInt()).isEqualTo(3)
        assertThat(h.get("/v1/reports/ai-provenance").status).isEqualTo(200)

        // Exports: stable schema, totals and reconciliation against the ledger (MS-19).
        for (dataset in listOf("ledger", "executions", "orders", "recommendations", "risk-evaluations")) {
            val r = h.get("/v1/exports/$dataset?portfolioId=$p&format=json")
            assertThat(r.status).`as`(dataset).isEqualTo(200)
            assertThat(r.header("X-StrategyForge-Reconciled")).`as`("$dataset ${r.json["reconciliation"]}").isEqualTo("true")
            assertThat(r.json["schemaVersion"].asInt()).isEqualTo(1)
            assertThat(r.json["reconciled"].asBoolean()).isTrue()
        }
        val ledger = h.get("/v1/exports/ledger?portfolioId=$p&format=json").json
        assertThat(BigDecimal(ledger["totals"]["allEntries"].asText())).isEqualByComparingTo("0")
        assertThat(ledger["reconciliation"].map { it["name"].asText() }).contains("Double entry: all entries sum to zero", "CASH account equals portfolio cash")
        val execs = h.get("/v1/exports/executions?portfolioId=$p&format=json").json
        assertThat(execs["rows"]).hasSize(3)
        assertThat(BigDecimal(execs["totals"]["commission"].asText())).isEqualByComparingTo("4.50")

        val csv = h.get("/v1/exports/executions?portfolioId=$p&format=csv")
        assertThat(csv.header("Content-Type")).startsWith("text/csv")
        assertThat(csv.header("Content-Disposition")).contains("strategyforge-executions-v1.csv")
        val lines = csv.body.trim().lines()
        assertThat(lines.first()).isEqualTo("executionId,orderId,symbol,side,quantity,price,notional,commission,spreadCost,slippageCost,realizedPnl,executedAt")
        assertThat(lines.filter { it.startsWith("TOTAL,commission,") }).containsExactly("TOTAL,commission,4.500000000000")

        val auditCsv = h.get("/v1/exports/audit?format=csv")
        assertThat(auditCsv.status).isEqualTo(200)
        assertThat(h.get("/v1/exports/unknown?format=json").status).isEqualTo(404)
        assertThat(h.get("/v1/exports/ledger?format=json").status).isEqualTo(400)
        assertThat(h.get("/v1/exports/ledger?portfolioId=$p&format=xml").status).isEqualTo(400)
        val exportsAudited = jdbc.sql("select count(*) from audit_events where action = 'EXPORT_CREATED'").query(Int::class.java).single()
        assertThat(exportsAudited).isGreaterThanOrEqualTo(8)
    }

    @Autowired
    lateinit var daily: DailySummaryService

    @Test
    fun `FR-101 daily summary is sent once per local day after the configured time`() {
        TestOwner.client(baseUrl)
        // Default: 17:00 in the owner's timezone (America/Halifax, UTC-3 in June).
        assertThat(daily.runIfDue(Instant.parse("2026-06-22T19:59:00Z"))).isNull()
        val id = daily.runIfDue(Instant.parse("2026-06-22T20:01:00Z"))
        assertThat(id).isNotNull()
        assertThat(daily.runIfDue(Instant.parse("2026-06-22T23:00:00Z"))).`as`("once per day").isNull()
        val row =
            jdbc
                .sql("select category || '|' || title || '|' || body from notification_events where id = :id")
                .param("id", id)
                .query(String::class.java)
                .single()
        assertThat(row).startsWith("DAILY_SUMMARY|Daily summary 2026-06-22|").contains("(simulated)")
        assertThat(daily.runIfDue(Instant.parse("2026-06-23T20:30:00Z"))).isNotNull()
    }

    @Test
    fun `CSV output escapes delimiters and neutralises spreadsheet formulas`() {
        val doc =
            ExportDocument(
                "t",
                1,
                Instant.parse("2026-06-22T00:00:00Z"),
                null,
                listOf("a", "b", "c"),
                listOf(listOf("=HYPERLINK(\"x\")", "plain, with comma", BigDecimal("-1.50")), listOf("@cmd", "line\nbreak", null)),
                mapOf("sum" to BigDecimal("-1.50")),
                emptyList(),
                "d",
            )
        val out = ExportService.csv(doc)
        assertThat(out).contains("\"'=HYPERLINK(\"\"x\"\")\",\"plain, with comma\",-1.50\r\n")
        assertThat(out).contains("'@cmd,\"line\nbreak\",\r\n")
        assertThat(out).endsWith("TOTAL,sum,-1.50\r\n")
    }
}
