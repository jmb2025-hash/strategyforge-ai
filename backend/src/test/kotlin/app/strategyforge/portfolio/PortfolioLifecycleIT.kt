package app.strategyforge.portfolio

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PortfolioLifecycleIT : FreshDatabaseTest() {
    @Test
    fun `FR-010 FR-015 MS-03 portfolio create, rename, clone, archive, reset and the ten-portfolio cap`() {
        val h = TestOwner.client(baseUrl)
        val created = h.post("/v1/portfolios", mapOf("name" to "Main"))
        assertThat(created.status).isEqualTo(201)
        val id = created.json["id"].asText()
        assertThat(created.json["startingBalance"].asText()).isEqualTo("100000.000000000000")
        assertThat(created.json["accountType"].asText()).isEqualTo("PAPER")
        val summary = h.get("/v1/portfolios/$id/summary").json
        assertThat(summary["cash"].asText()).startsWith("100000")
        assertThat(summary["equity"].asText()).startsWith("100000")
        assertThat(summary["buyingPower"].asText()).startsWith("100000")

        // Rename with optimistic concurrency.
        val etag = h.get("/v1/portfolios/$id").header("ETag")!!
        val cm = TestOwnerJson.costModel(h, id)
        assertThat(h.patch("/v1/portfolios/$id", mapOf("name" to "Core", "costModel" to cm), mapOf("If-Match" to etag)).status).isEqualTo(200)
        assertThat(h.patch("/v1/portfolios/$id", mapOf("name" to "Core2", "costModel" to cm), mapOf("If-Match" to etag)).status).isEqualTo(412)
        assertThat(h.post("/v1/portfolios", mapOf("name" to "core")).json["code"].asText()).isEqualTo("portfolio-name-taken")

        // Clone copies configuration into a fresh ledger.
        val clone = h.post("/v1/portfolios/$id/clone", mapOf("name" to "Core copy"))
        assertThat(clone.status).isEqualTo(201)
        assertThat(clone.json["clonedFrom"].asText()).isEqualTo(id)
        assertThat(h.get("/v1/ledger?portfolioId=${clone.json["id"].asText()}").json["items"].size()).isEqualTo(1)

        // Reset archives the old portfolio (ledger preserved) and creates a new ledger.
        assertThat(h.post("/v1/portfolios/$id/reset", mapOf("confirm" to "nope")).status).isEqualTo(400)
        val reset = h.post("/v1/portfolios/$id/reset", mapOf("confirm" to "RESET"))
        assertThat(reset.status).isEqualTo(201)
        val newId = reset.json["id"].asText()
        assertThat(reset.json["resetFrom"].asText()).isEqualTo(id)
        assertThat(h.get("/v1/portfolios/$id").json["status"].asText()).isEqualTo("ARCHIVED")
        assertThat(h.get("/v1/ledger?portfolioId=$id").json["items"].size()).isEqualTo(1)
        assertThat(h.get("/v1/portfolios/$newId/summary").json["cash"].asText()).startsWith("100000")

        // Archive and the cap of 10 active portfolios.
        assertThat(h.post("/v1/portfolios/${clone.json["id"].asText()}/archive").json["status"].asText()).isEqualTo("ARCHIVED")
        val active = h.get("/v1/portfolios").json.size()
        repeat(10 - active) { Replay.portfolio(h, "Extra $it") }
        val over = h.post("/v1/portfolios", mapOf("name" to "Eleventh"))
        assertThat(over.status).isEqualTo(422)
        assertThat(over.json["code"].asText()).isEqualTo("portfolio-limit")
        val archivedCount = h.get("/v1/portfolios?includeArchived=true").json.count { it["status"].asText() == "ARCHIVED" }
        assertThat(archivedCount).isEqualTo(2)
        val audits = jdbc.sql("select count(*) from audit_events where action in ('PORTFOLIO_CREATED','PORTFOLIO_CLONED','PORTFOLIO_RESET','PORTFOLIO_ARCHIVED','PORTFOLIO_ARCHIVED_FOR_RESET')").query(Int::class.java).single()
        assertThat(audits).isGreaterThanOrEqualTo(14)
    }
}

object TestOwnerJson {
    fun costModel(
        h: app.strategyforge.support.TestHttp,
        id: String,
    ): Map<*, *> =
        app.strategyforge.support.TestHttp.mapper
            .convertValue(h.get("/v1/portfolios/$id").json["costModel"], Map::class.java)
}
