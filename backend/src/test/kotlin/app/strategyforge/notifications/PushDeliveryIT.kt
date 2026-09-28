package app.strategyforge.notifications

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.TestHttp
import app.strategyforge.support.TestOwner
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.security.KeyPairGenerator
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** FR-101 optional push, FR-102 redacted payloads, MS-16 inbox complete when push fails. */
class PushDeliveryIT : FreshDatabaseTest() {
    companion object {
        val sends = ConcurrentLinkedQueue<String>()
        val sendStatus = AtomicInteger(200)
        val server: MockWebServer =
            MockWebServer().also {
                it.dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            when {
                                request.path == "/token" ->
                                    MockResponse().setHeader("Content-Type", "application/json").setBody("""{"access_token":"ya29.test-token","expires_in":3600,"token_type":"Bearer"}""")
                                request.path == "/v1/projects/sf-test/messages:send" -> {
                                    sends.add(request.getHeader("Authorization") + "\n" + request.body.readUtf8())
                                    val status = sendStatus.get()
                                    MockResponse()
                                        .setResponseCode(status)
                                        .setHeader("Content-Type", "application/json")
                                        .setBody(if (status == 404) """{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}""" else """{"name":"projects/sf-test/messages/1"}""")
                                }
                                else -> MockResponse().setResponseCode(404)
                            }
                    }
                it.start()
            }

        @JvmStatic
        @AfterAll
        fun stop() = server.shutdown()
    }

    @Autowired
    lateinit var notifications: NotificationService

    @Autowired
    lateinit var sender: PushSender

    private fun serviceAccount(): String {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(kp.private.encoded) + "\n-----END PRIVATE KEY-----\n"
        return TestHttp.mapper.writeValueAsString(
            mapOf(
                "type" to "service_account",
                "project_id" to "sf-test",
                "private_key_id" to "k1",
                "private_key" to pem,
                "client_email" to "push@sf-test.iam.gserviceaccount.com",
                "client_id" to "1",
                "token_uri" to server.url("/token").toString(),
            ),
        )
    }

    @Test
    fun `FR-100 FR-101 FR-102 MS-16 push carries only redacted text, retries transient errors and the inbox stays complete`() {
        val h = TestOwner.client(baseUrl)
        val base = server.url("/").toString().trimEnd('/')
        val p =
            h.post(
                "/v1/providers",
                mapOf(
                    "providerType" to "FCM",
                    "displayName" to "Firebase",
                    "settings" to mapOf("projectId" to "sf-test", "applicationId" to "1:1:android:1", "apiKey" to "public-key", "senderId" to "1", "baseUrl" to base),
                    "credential" to serviceAccount(),
                ),
            )
        assertThat(p.status).`as`(p.toString()).isEqualTo(201)
        assertThat(h.post("/v1/providers/${p.json["id"].asText()}/activate", null).status).isEqualTo(200)
        val device = h.post("/v1/devices", mapOf("name" to "Pixel", "platform" to "ANDROID")).json["id"].asText()
        h.put("/v1/devices/$device/push", mapOf("pushToken" to "device-token-123", "pushEnabled" to true))

        // Delivered: the payload has the redacted title, never the symbol or amount.
        val first = notifications.notify(NotificationCategory.RISK_EVENT, Severity.WARNING, "Order blocked: BTC-USD 5,000.00", "Trade value exceeded", "PaperOrder", "abc", "push-test-1")!!
        val r1 = sender.processDue()
        assertThat(r1.sent).isEqualTo(1)
        val sent = sends.poll()
        assertThat(sent)
            .startsWith("Bearer ya29.test-token")
            .contains("device-token-123")
            .contains(first.toString())
            .contains("risk_safety")
        assertThat(sent).doesNotContain("BTC-USD").doesNotContain("5,000")
        assertThat(
            jdbc
                .sql("select push_status from notification_events where id = :id")
                .param("id", first)
                .query(String::class.java)
                .single(),
        ).isEqualTo("SENT")

        // Transient failure: stays queued with backoff.
        sendStatus.set(503)
        val second = notifications.notify(NotificationCategory.STRATEGY_SUSPENSION, Severity.CRITICAL, "Momentum suspended", "3 consecutive losses", "Strategy", "s1", "push-test-2")!!
        val r2 = sender.processDue()
        assertThat(r2.retrying).isEqualTo(1)
        val row =
            jdbc
                .sql("select status || ':' || attempts from push_deliveries where notification_id = :n")
                .param("n", second)
                .query(String::class.java)
                .single()
        assertThat(row).isEqualTo("PENDING:1")
        assertThat(sender.processDue().attempted).`as`("backoff defers the retry").isZero()

        // Unregistered token: delivery fails and push is disabled for the device.
        sendStatus.set(404)
        jdbc.sql("update push_deliveries set next_attempt_at = now() - interval '1 minute' where notification_id = :n").param("n", second).update()
        assertThat(sender.processDue().failed).isEqualTo(1)
        assertThat(
            jdbc
                .sql("select push_enabled from devices where id = cast(:d as uuid)")
                .param("d", device)
                .query(Boolean::class.java)
                .single(),
        ).isFalse()
        assertThat(
            jdbc
                .sql("select push_status from notification_events where id = :id")
                .param("id", second)
                .query(String::class.java)
                .single(),
        ).isEqualTo("FAILED")

        // MS-16: the inbox is authoritative and complete regardless of push outcome.
        val inbox = h.get("/v1/notifications?limit=100").json["items"].map { it["id"].asText() }
        assertThat(inbox).contains(first.toString(), second.toString())
        val third = notifications.notify(NotificationCategory.RISK_EVENT, Severity.WARNING, "Another", "x", null, null, "push-test-3")!!
        assertThat(
            jdbc
                .sql("select push_status from notification_events where id = :id")
                .param("id", third)
                .query(String::class.java)
                .single(),
        ).isEqualTo("NO_CHANNEL")
        assertThat(h.get("/v1/notifications?limit=100").json["items"].map { it["id"].asText() }).contains(third.toString())
    }
}
