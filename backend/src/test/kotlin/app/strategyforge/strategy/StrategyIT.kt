package app.strategyforge.strategy

import app.strategyforge.common.security.Crypto
import app.strategyforge.support.IntegrationTest
import app.strategyforge.support.Resp
import app.strategyforge.support.TestHttp
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.UUID

class StrategyIT : IntegrationTest() {
    private fun fixture(name: String): ByteArray = javaClass.getResource("/strategies/$name")!!.readBytes()

    private fun import(
        h: TestHttp,
        bytes: ByteArray,
        filename: String = "s.json",
    ): Resp {
        val req =
            HttpRequest
                .newBuilder(URI.create("$baseUrl/v1/strategies/import?filename=$filename"))
                .header("Authorization", "Bearer ${h.token}")
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "imp-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build()
        val r = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
        return Resp(r.statusCode(), r.body(), r.headers().map())
    }

    @Test
    fun `FR-040 FR-041 FR-044 FR-046 MS-05 valid import is validated, versioned, hash-addressed and explained`() {
        val h = TestOwner.client(baseUrl)
        val r = import(h, fixture("valid_momentum.json"))
        assertThat(r.status).`as`(r.body).isEqualTo(201)
        assertThat(r.json["validation"]["status"].asText()).isEqualTo("VALIDATED")
        assertThat(r.json["strategy"]["status"].asText()).isEqualTo("VALIDATED")
        val id = r.json["strategy"]["id"].asText()
        val v = r.json["version"]
        assertThat(v["versionNumber"].asInt()).isEqualTo(1)
        assertThat(v["content"]["strategyId"].asText()).`as`("server-normalized id").isEqualTo(id)
        assertThat(r.json["explanation"].asText())
            .contains("Buy AAPL, MSFT")
            .contains("1-hour")
            .contains("3% stop loss")
            .contains("Paper trading only")

        val export = h.get("/v1/strategies/$id/export")
        assertThat(export.header("X-Content-Hash")).isEqualTo(v["contentHash"].asText())
        val canonical =
            jdbc
                .sql("select canonical_content from strategy_versions where id = cast(:v as uuid)")
                .param("v", v["id"].asText())
                .query(String::class.java)
                .single()
        assertThat(Crypto.sha256Hex(canonical)).isEqualTo(v["contentHash"].asText())

        // Versions are immutable at the database level.
        assertThatThrownBy { jdbc.sql("update strategy_versions set content = '{}'::jsonb where id = cast(:v as uuid)").param("v", v["id"].asText()).update() }.hasMessageContaining("immutable")
        assertThatThrownBy { jdbc.sql("delete from strategy_versions where id = cast(:v as uuid)").param("v", v["id"].asText()).update() }.hasMessageContaining("immutable")

        // Editing creates version 2 with a different hash; version 1 remains addressable.
        val etag = h.get("/v1/strategies/$id").header("ETag")!!
        val content = TestHttp.mapper.readTree(fixture("valid_momentum.json")) as com.fasterxml.jackson.databind.node.ObjectNode
        (content["exitRules"] as com.fasterxml.jackson.databind.node.ObjectNode).put("takeProfitPercent", 8.0)
        val upd = h.put("/v1/strategies/$id/content", mapOf("content" to content), mapOf("If-Match" to etag))
        assertThat(upd.json["version"]["versionNumber"].asInt()).isEqualTo(2)
        assertThat(upd.json["version"]["contentHash"].asText()).isNotEqualTo(v["contentHash"].asText())
        assertThat(h.get("/v1/strategies/$id/versions/1").json["contentHash"].asText()).isEqualTo(v["contentHash"].asText())
        assertThat(h.get("/v1/strategies/$id/versions").json.size()).isEqualTo(2)

        val clone = h.post("/v1/strategies/$id/clone", mapOf("name" to "Momentum clone"))
        assertThat(clone.status).isEqualTo(201)
        assertThat(clone.json["strategy"]["clonedFrom"].asText()).isEqualTo(id)
        assertThat(clone.json["strategy"]["name"].asText()).isEqualTo("Momentum clone")
        assertThat(h.post("/v1/strategies/$id/pause").status).isEqualTo(409)
        assertThat(h.post("/v1/strategies/${clone.json["strategy"]["id"].asText()}/archive").json["status"].asText()).isEqualTo("ARCHIVED")
    }

    @Test
    fun `FR-045 all approved indicators, nested rule groups, sizing, targets and schedules validate`() {
        val h = TestOwner.client(baseUrl)
        val r = import(h, fixture("valid_crypto_rsi.json"))
        assertThat(r.json["validation"]["status"].asText()).`as`(r.body).isEqualTo("VALIDATED")
        assertThat(r.json["explanation"].asText()).contains("RSI(14)").contains("trailing stop").contains("5000 USD")
    }

    @Test
    fun `FR-042 MS-06 executable, network, SQL, shell, real-money and prompt-injection content is rejected`() {
        val h = TestOwner.client(baseUrl)
        val before = jdbc.sql("select count(*) from strategies").query(Int::class.java).single()
        val cases =
            mapOf(
                "malicious_script_field.json" to "EXECUTABLE_FIELD",
                "malicious_script_value.json" to "PROHIBITED_CONTENT",
                "malicious_python.json" to "PROHIBITED_CONTENT",
                "malicious_url.json" to "PROHIBITED_CONTENT",
                "malicious_sql.json" to "PROHIBITED_CONTENT",
                "prompt_injection.json" to "PROMPT_INJECTION",
                "real_money_fields.json" to "REAL_MONEY_FIELD",
            )
        cases.forEach { (file, code) ->
            val r = import(h, fixture(file), file)
            assertThat(r.status).`as`("$file: ${r.body}").isEqualTo(422)
            assertThat(r.json["code"].asText()).isEqualTo("strategy-rejected")
            assertThat(r.json["issues"].map { it["code"].asText() }).`as`(file).contains(code)
        }
        assertThat(jdbc.sql("select count(*) from strategies").query(Int::class.java).single()).isEqualTo(before)
        val rejected = jdbc.sql("select count(*) from strategy_imports where outcome = 'REJECTED'").query(Int::class.java).single()
        assertThat(rejected).isGreaterThanOrEqualTo(cases.size)
        assertThat(jdbc.sql("select count(*) from audit_events where action = 'STRATEGY_IMPORT_REJECTED'").query(Int::class.java).single()).isGreaterThanOrEqualTo(cases.size)
    }

    @Test
    fun `FR-041 MS-05 malformed files are rejected by size, encoding and strict JSON parsing`() {
        val h = TestOwner.client(baseUrl)
        mapOf(
            "duplicate_keys.json" to "INVALID_JSON",
            "comments.json" to "INVALID_JSON",
            "not_json.json" to "INVALID_JSON",
            "bad_utf8.json" to "INVALID_ENCODING",
        ).forEach { (file, code) ->
            val r = import(h, fixture(file), file)
            assertThat(r.status).`as`(file).isEqualTo(422)
            assertThat(r.json["issues"][0]["code"].asText()).`as`(file).isEqualTo(code)
        }
        val big = ByteArray(StrategyValidator.MAX_BYTES + 10) { ' '.code.toByte() }
        assertThat(import(h, big).status).isIn(413, 422)
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + fixture("valid_momentum.json")
        assertThat(import(h, bom).json["issues"][0]["code"].asText()).isEqualTo("BOM_NOT_ALLOWED")
    }

    @Test
    fun `FR-042 FR-047 unsupported operators, unsafe values, contradictions and future data fail validation`() {
        val h = TestOwner.client(baseUrl)
        val cases =
            mapOf(
                "unsupported_operator.json" to "SCHEMA_",
                "unsupported_indicator.json" to "SCHEMA_",
                "unsafe_sizing.json" to "UNSAFE_VALUE",
                "unsafe_stop.json" to "SCHEMA_",
                "contradictory_rules.json" to "CONTRADICTORY_RULES",
                "contradictory_bounds.json" to "CONTRADICTORY_RULES",
                "future_data.json" to "FUTURE_DATA_REFERENCE",
                "unknown_reference.json" to "UNKNOWN_REFERENCE",
                "insufficient_history.json" to "INSUFFICIENT_HISTORY_REQUIREMENT",
                "asset_mismatch.json" to "ASSET_CLASS_MISMATCH",
                "too_many_symbols.json" to "SCHEMA_",
                "missing_section.json" to "SCHEMA_",
                "limit_without_offset.json" to "MISSING_PARAMETER",
            )
        cases.forEach { (file, code) ->
            val r = import(h, fixture(file), file)
            assertThat(r.status).`as`("$file ${r.body}").isEqualTo(201)
            assertThat(r.json["strategy"]["status"].asText()).`as`(file).isEqualTo("VALIDATION_FAILED")
            assertThat(r.json["validation"]["issues"].map { it["code"].asText() }).`as`(file).anyMatch { it.startsWith(code) }
            assertThat(r.json["explanation"].isNull).isTrue()
        }
    }

    @Test
    fun `FR-043 MS-07 unknown but meaningful content requires manual review until resolved in a new version`() {
        val h = TestOwner.client(baseUrl)
        val r = import(h, fixture("unknown_fields_mrr.json"))
        assertThat(r.status).isEqualTo(201)
        assertThat(r.json["strategy"]["status"].asText()).isEqualTo("MANUAL_REVIEW_REQUIRED")
        assertThat(r.json["validation"]["unknownFields"].map { it.asText() }).containsExactlyInAnyOrder("$.metadata.sentimentFilter", "$.riskLimits.notes")
        val id = r.json["strategy"]["id"].asText()
        // The unknown content is preserved (never silently dropped) and the version cannot be explained or run.
        assertThat(r.json["version"]["content"]["metadata"]["sentimentFilter"]["source"].asText()).isEqualTo("news")
        assertThat(h.get("/v1/strategies/$id/explanation").json["code"].asText()).isEqualTo("strategy-not-validated")
        assertThat(h.post("/v1/strategies/$id/validate").json["validation"]["status"].asText()).isEqualTo("MANUAL_REVIEW_REQUIRED")
        // Owner resolves by removing the fields: new version validates.
        val etag = h.get("/v1/strategies/$id").header("ETag")!!
        val resolved = h.put("/v1/strategies/$id/content", mapOf("content" to TestHttp.mapper.readTree(fixture("valid_momentum.json"))), mapOf("If-Match" to etag))
        assertThat(resolved.json["strategy"]["status"].asText()).isEqualTo("VALIDATED")
        assertThat(resolved.json["version"]["versionNumber"].asInt()).isEqualTo(2)
        val history = h.get("/v1/strategies/$id").json["history"].map { it["to"].asText() }
        assertThat(history).containsSubsequence("DRAFT", "MANUAL_REVIEW_REQUIRED", "DRAFT", "VALIDATED")
    }

    @Test
    fun `FR-040 strategies can be created from JSON content and listed, with idempotent creation`() {
        val h = TestOwner.client(baseUrl)
        val key = "strategy-" + UUID.randomUUID()
        val body = mapOf("content" to TestHttp.mapper.readTree(fixture("valid_crypto_rsi.json")))
        val a = h.post("/v1/strategies", body, idem = key)
        val b = h.post("/v1/strategies", body, idem = key)
        assertThat(a.status).isEqualTo(201)
        assertThat(b.json["strategy"]["id"].asText()).isEqualTo(a.json["strategy"]["id"].asText())
        assertThat(h.get("/v1/strategies").json.map { it["id"].asText() }).contains(a.json["strategy"]["id"].asText())
    }
}
