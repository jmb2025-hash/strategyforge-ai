package app.strategyforge.engine.strategy

import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.common.Hashing
import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.db.str
import app.strategyforge.engine.support.TestEngine
import com.fasterxml.jackson.databind.node.ObjectNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** MS-05, MS-06, MS-07, ported from the Version 1 StrategyIT (same strategy fixtures). */
class StrategyLibraryTest {
    private val e = TestEngine.create()

    private fun fixture(name: String): ByteArray = javaClass.getResource("/strategies/$name")!!.readBytes()

    private fun rejected(
        bytes: ByteArray,
        name: String = "s.json",
    ): EngineException =
        try {
            e.strategies.import(bytes, name)
            error("$name was accepted")
        } catch (x: EngineException) {
            x
        }

    @Suppress("UNCHECKED_CAST")
    private fun EngineException.issueCodes() = (properties["issues"] as List<ValidationIssue>).map { it.code }

    @Test
    fun `FR-040 FR-041 FR-044 FR-046 MS-05 valid import is validated, versioned, hash-addressed and explained`() {
        val r = e.strategies.import(fixture("valid_momentum.json"), "valid_momentum.json")
        assertThat(r.validation.status).isEqualTo("VALIDATED")
        assertThat(r.strategy.status).isEqualTo(StrategyStatus.VALIDATED)
        val id = r.strategy.id
        val v = r.version!!
        assertThat(v.versionNumber).isEqualTo(1)
        assertThat(v.content["strategyId"].asText()).`as`("engine-normalized id").isEqualTo(id.toString())
        assertThat(r.explanation)
            .contains("Buy AAPL, MSFT")
            .contains("1-hour")
            .contains("3% stop loss")
            .contains("Paper trading only")
        val canonical =
            e.db
                .sql("select canonical_content from strategy_versions where id = :v")
                .param("v", v.id)
                .single { it.str("canonical_content") }
        assertThat(Hashing.sha256Hex(canonical)).isEqualTo(v.contentHash)

        // Versions are immutable at the database level.
        assertThatThrownBy {
            e.db
                .sql("update strategy_versions set content = '{}' where id = :v")
                .param("v", v.id)
                .update()
        }.hasMessageContaining("immutable")
        assertThatThrownBy {
            e.db
                .sql("delete from strategy_versions where id = :v")
                .param("v", v.id)
                .update()
        }.hasMessageContaining("immutable")

        // Editing creates version 2 with a different hash; version 1 remains addressable.
        val content = JacksonCanonical.mapper.readTree(fixture("valid_momentum.json")) as ObjectNode
        (content["exitRules"] as ObjectNode).put("takeProfitPercent", 8.0)
        val upd = e.strategies.update(id, content, e.strategies.get(id).version)
        assertThat(upd.version!!.versionNumber).isEqualTo(2)
        assertThat(upd.version!!.contentHash).isNotEqualTo(v.contentHash)
        assertThat(e.strategies.versionByNumber(id, 1).contentHash).isEqualTo(v.contentHash)
        assertThat(e.strategies.versions(id)).hasSize(2)

        val clone = e.strategies.clone(id, "Momentum clone")
        assertThat(clone.strategy.clonedFrom).isEqualTo(id)
        assertThat(clone.strategy.name).isEqualTo("Momentum clone")
        assertThat((runCatching { e.strategies.pause(id, "x") }.exceptionOrNull() as EngineException).code).isEqualTo("strategy-not-active")
        assertThat(e.strategies.archive(clone.strategy.id).status).isEqualTo(StrategyStatus.ARCHIVED)
    }

    @Test
    fun `FR-045 all approved indicators, nested rule groups, sizing, targets and schedules validate`() {
        val r = e.strategies.import(fixture("valid_crypto_rsi.json"), "rsi.json")
        assertThat(r.validation.status).`as`(r.validation.issues.toString()).isEqualTo("VALIDATED")
        assertThat(r.explanation).contains("RSI(14)").contains("trailing stop").contains("5000 USD")
    }

    @Test
    fun `FR-042 MS-06 executable, network, SQL, shell, real-money and prompt-injection content is rejected`() {
        val before = e.db.sql("select count(*) from strategies").long()
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
            val x = rejected(fixture(file), file)
            assertThat(x.code).isEqualTo("strategy-rejected")
            assertThat(x.issueCodes()).`as`(file).contains(code)
        }
        assertThat(e.db.sql("select count(*) from strategies").long()).isEqualTo(before)
        // Rejections are recorded even though nothing was stored as a strategy.
        assertThat(e.db.sql("select count(*) from strategy_imports where outcome = 'REJECTED'").long()).isGreaterThanOrEqualTo(cases.size.toLong())
        assertThat(e.db.sql("select count(*) from audit_events where action = 'STRATEGY_IMPORT_REJECTED'").long()).isGreaterThanOrEqualTo(cases.size.toLong())
    }

    @Test
    fun `FR-041 MS-05 malformed files are rejected by size, encoding and strict JSON parsing`() {
        mapOf(
            "duplicate_keys.json" to "INVALID_JSON",
            "comments.json" to "INVALID_JSON",
            "not_json.json" to "INVALID_JSON",
            "bad_utf8.json" to "INVALID_ENCODING",
        ).forEach { (file, code) -> assertThat(rejected(fixture(file), file).issueCodes().first()).`as`(file).isEqualTo(code) }
        assertThat(rejected(ByteArray(StrategyValidator.MAX_BYTES + 10) { ' '.code.toByte() }).code).isEqualTo("strategy-rejected")
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + fixture("valid_momentum.json")
        assertThat(rejected(bom).issueCodes().first()).isEqualTo("BOM_NOT_ALLOWED")
    }

    @Test
    fun `FR-042 FR-047 unsupported operators, unsafe values, contradictions and future data fail validation`() {
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
                "asset_mismatch.json" to "ASSET_CLASS_MISMATCH",
                "too_many_symbols.json" to "SCHEMA_",
                "missing_section.json" to "SCHEMA_",
                "limit_without_offset.json" to "MISSING_PARAMETER",
            )
        cases.forEach { (file, code) ->
            val r = e.strategies.import(fixture(file), file)
            assertThat(r.strategy.status).`as`(file).isEqualTo(StrategyStatus.VALIDATION_FAILED)
            assertThat(r.validation.issues.map { it.code }).`as`(file).anyMatch { it.startsWith(code) }
            assertThat(r.explanation).isNull()
        }
    }

    @Test
    fun `D-042 a history requirement below what the indicators need is raised, not rejected`() {
        val r = e.strategies.import(fixture("insufficient_history.json"), "insufficient_history.json")
        assertThat(r.validation.issues.map { it.code }).contains("HISTORY_RAISED")
        assertThat(
            r.validation.issues
                .filter { it.code == "HISTORY_RAISED" }
                .map { it.severity },
        ).containsOnly(IssueSeverity.WARNING)
    }

    @Test
    fun `FR-043 MS-07 unknown but meaningful content requires manual review until resolved in a new version`() {
        val r = e.strategies.import(fixture("unknown_fields_mrr.json"), "mrr.json")
        assertThat(r.strategy.status).isEqualTo(StrategyStatus.MANUAL_REVIEW_REQUIRED)
        assertThat(r.validation.unknownFields).containsExactlyInAnyOrder("$.metadata.sentimentFilter", "$.riskLimits.notes")
        val id = r.strategy.id
        // The unknown content is preserved (never silently dropped) and the version cannot be explained or run.
        assertThat(r.version!!.content["metadata"]["sentimentFilter"]["source"].asText()).isEqualTo("news")
        assertThat((runCatching { e.strategies.definition(r.version!!.id) }.exceptionOrNull() as EngineException).code).isEqualTo("strategy-not-validated")
        assertThat(
            e.strategies
                .revalidate(id)
                .validation.status,
        ).isEqualTo("MANUAL_REVIEW_REQUIRED")
        // Owner resolves by removing the fields: the new version validates.
        val resolved = e.strategies.update(id, JacksonCanonical.mapper.readTree(fixture("valid_momentum.json")), e.strategies.get(id).version)
        assertThat(resolved.strategy.status).isEqualTo(StrategyStatus.VALIDATED)
        assertThat(resolved.version!!.versionNumber).isEqualTo(2)
        assertThat(e.strategies.statusHistoryOf(id).map { it["to"] }).containsSubsequence("DRAFT", "MANUAL_REVIEW_REQUIRED", "DRAFT", "VALIDATED")
    }
}
