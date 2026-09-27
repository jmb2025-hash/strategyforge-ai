package app.strategyforge.providers

import app.strategyforge.support.IntegrationTest
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProviderCredentialIT : IntegrationTest() {
    private val secret = "sk-ant-api03-THIS-IS-A-TEST-SECRET-0123456789"

    @Test
    fun `FR-031 MS-02 provider keys are encrypted server-side, never returned, and never logged or audited`() {
        val h = TestOwner.client(baseUrl)
        val r =
            h.post(
                "/v1/providers",
                mapOf(
                    "providerType" to "ANTHROPIC",
                    "displayName" to "Claude",
                    "settings" to mapOf("model" to "claude-sonnet-5", "inputPricePerMillionTokensUsd" to "3", "outputPricePerMillionTokensUsd" to "15"),
                    "credential" to secret,
                ),
            )
        assertThat(r.status).isEqualTo(201)
        assertThat(r.body).doesNotContain(secret).doesNotContain("THIS-IS-A-TEST-SECRET")
        assertThat(r.json["credential"]["configured"].asBoolean()).isTrue()
        assertThat(r.json["credential"]["source"].asText()).isEqualTo("STORED")
        val id = r.json["id"].asText()

        val stored =
            jdbc
                .sql("select credential_enc from provider_configurations where id = cast(:id as uuid)")
                .param("id", id)
                .query(ByteArray::class.java)
                .single()
        assertThat(String(stored, Charsets.ISO_8859_1)).doesNotContain("THIS-IS-A-TEST-SECRET")
        val anyPlain = jdbc.sql("select count(*) from provider_configurations where settings::text like '%TEST-SECRET%' or display_name like '%TEST-SECRET%'").query(Int::class.java).single()
        assertThat(anyPlain).isZero()
        val auditLeak = jdbc.sql("select count(*) from audit_events where details::text like '%TEST-SECRET%'").query(Int::class.java).single()
        assertThat(auditLeak).isZero()

        assertThat(h.get("/v1/providers/$id").body).doesNotContain("TEST-SECRET")
        assertThat(h.get("/v1/providers").body).doesNotContain("TEST-SECRET")
    }

    @Test
    fun `FR-031 key rotation requires recent authentication`() {
        val h = TestOwner.client(baseUrl)
        val id =
            h
                .post(
                    "/v1/providers",
                    mapOf("providerType" to "OPENAI", "displayName" to "OpenAI", "settings" to mapOf("model" to "gpt-5", "inputPricePerMillionTokensUsd" to "1", "outputPricePerMillionTokensUsd" to "2")),
                ).json["id"]
                .asText()
        jdbc.sql("update sessions set last_authenticated_at = now() - interval '1 hour'").update()
        val denied = h.put("/v1/providers/$id/credential", mapOf("credential" to "sk-proj-another-test-key-000000"))
        assertThat(denied.status).isEqualTo(403)
        assertThat(h.post("/v1/auth/reauthenticate", mapOf("password" to TestOwner.PASSWORD)).status).isEqualTo(200)
        val ok = h.put("/v1/providers/$id/credential", mapOf("credential" to "sk-proj-another-test-key-000000"))
        assertThat(ok.status).isEqualTo(200)
        assertThat(ok.body).doesNotContain("another-test-key")
        assertThat(ok.json["credential"]["fingerprint"].asText()).hasSize(8)
    }

    @Test
    fun `FR-030 FR-113 provider settings are validated and real-money fields are refused`() {
        val h = TestOwner.client(baseUrl)
        val unknown = h.post("/v1/providers", mapOf("providerType" to "BROKER_X", "displayName" to "x"))
        assertThat(unknown.status).isEqualTo(400)
        assertThat(unknown.json["code"].asText()).isEqualTo("unsupported-provider")
        val live = h.post("/v1/providers", mapOf("providerType" to "TWELVE_DATA", "displayName" to "x", "settings" to mapOf("brokerAccount" to "123")))
        assertThat(live.status).isEqualTo(403)
        assertThat(live.json["code"].asText()).isEqualTo("real-money-prohibited")
        val missing = h.post("/v1/providers", mapOf("providerType" to "GEMINI", "displayName" to "Gemini", "settings" to mapOf("model" to "gemini-x")))
        assertThat(missing.status).isEqualTo(400)
        assertThat(missing.json["code"].asText()).isEqualTo("missing-setting")
        val insecure = h.post("/v1/providers", mapOf("providerType" to "TWELVE_DATA", "displayName" to "x", "settings" to mapOf("baseUrl" to "http://evil.example.com")))
        assertThat(insecure.json["code"].asText()).isEqualTo("invalid-setting")
    }
}
