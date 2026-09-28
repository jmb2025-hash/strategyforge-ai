package app.strategyforge.identity

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Android device registration, push token storage and public push configuration (FR-101, FR-031). */
class DevicesIT : FreshDatabaseTest() {
    @Test
    fun `devices register, store push tokens encrypted and read only public push configuration`() {
        val h = TestOwner.client(baseUrl)
        assertThat(h.get("/v1/devices/push-config").json["enabled"].asBoolean()).isFalse()

        val d = h.post("/v1/devices", mapOf("name" to "Pixel 9", "platform" to "ANDROID"))
        assertThat(d.status).isEqualTo(201)
        val id = d.json["id"].asText()
        val token = "fcm-token-" + "x".repeat(40)
        val push = h.put("/v1/devices/$id/push", mapOf("pushToken" to token, "pushEnabled" to true))
        assertThat(push.status).isEqualTo(200)
        assertThat(push.json["pushTokenRegistered"].asBoolean()).isTrue()
        assertThat(h.get("/v1/devices").body).doesNotContain(token)
        val stored = jdbc.sql("select count(*) from devices where push_token_enc is not null").query(Int::class.java).single()
        assertThat(stored).isEqualTo(1)

        // A configured FCM provider exposes only its public client identifiers.
        jdbc
            .sql(
                """
                insert into provider_configurations(id, kind, provider_type, display_name, settings, credential_enc, active, created_at, updated_at)
                values (gen_random_uuid(), 'PUSH', 'FCM', 'fcm', cast(:s as jsonb), :c, true, now(), now())
                """.trimIndent(),
            ).param("s", """{"projectId":"sf-test","applicationId":"1:2:android:3","apiKey":"public-api-key","senderId":"1234"}""")
            .param("c", byteArrayOf(1, 2, 3))
            .update()
        val cfg = h.get("/v1/devices/push-config")
        assertThat(cfg.json["enabled"].asBoolean()).isTrue()
        assertThat(cfg.json["projectId"].asText()).isEqualTo("sf-test")
        assertThat(cfg.json["senderId"].asText()).isEqualTo("1234")
        assertThat(cfg.json.has("credential")).isFalse()
    }
}
