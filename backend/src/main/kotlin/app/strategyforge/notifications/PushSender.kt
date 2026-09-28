package app.strategyforge.notifications

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.identity.DeviceService
import app.strategyforge.providers.ProviderService
import app.strategyforge.providers.ResolvedProvider
import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Clock
import java.time.Duration
import java.util.UUID

data class PushRunReport(
    val attempted: Int,
    val sent: Int,
    val retrying: Int,
    val failed: Int,
)

/**
 * Delivers the push outbox through Firebase Cloud Messaging HTTP v1 (FR-101). Messages are
 * data-only and contain the notification id, channel, deep link and the REDACTED title/body, so
 * nothing sensitive reaches Google or the lock screen (FR-102). The inbox stays authoritative:
 * failed pushes never lose a notification (MS-16). Transient failures retry with backoff; an
 * unregistered token disables push for that device.
 */
@Service
class PushSender(
    private val jdbc: JdbcClient,
    private val providers: ProviderService,
    private val devices: DeviceService,
    private val audit: AuditService,
    private val mapper: ObjectMapper,
    private val props: StrategyForgeProperties,
    private val clock: Clock,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val tx = TransactionTemplate(txManager)
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    @Scheduled(fixedDelayString = "\${strategyforge.scheduler.push-interval-ms:15000}", initialDelay = 20000)
    fun scheduled() {
        if (!props.scheduler.enabled) return
        CorrelationIdFilter.withCorrelation("push") { processDue() }
    }

    fun processDue(limit: Int = BATCH): PushRunReport {
        val providerId =
            jdbc
                .sql("select id from provider_configurations where kind = 'PUSH' and provider_type = 'FCM' and active and archived_at is null")
                .query(UUID::class.java)
                .optional()
                .orElse(null) ?: return PushRunReport(0, 0, 0, 0)
        val provider = providers.resolve(providerId)
        val tokens = devices.pushTargets().associate { it.deviceId to it.token }
        val due =
            tx.execute {
                jdbc
                    .sql(
                        """
                        select d.id, d.device_id, d.attempts, n.id nid, n.category, n.redacted_title, n.redacted_body, n.deep_link
                        from push_deliveries d join notification_events n on n.id = d.notification_id
                        where d.status = 'PENDING' and d.next_attempt_at <= :now order by d.next_attempt_at limit :l for update of d skip locked
                        """.trimIndent(),
                    ).param("now", ts(clock.instant()))
                    .param("l", limit)
                    .query { rs, _ ->
                        Due(rs.uuid("id"), rs.uuid("device_id"), rs.getInt("attempts"), rs.uuid("nid"), rs.getString("category"), rs.getString("redacted_title"), rs.getString("redacted_body"), rs.getString("deep_link"))
                    }.list()
                    .onEach { d ->
                        // Claim: push the next attempt out so a concurrent worker does not resend meanwhile.
                        jdbc
                            .sql("update push_deliveries set next_attempt_at = :t, updated_at = :now where id = :id")
                            .param("t", ts(clock.instant().plus(CLAIM)))
                            .param("now", ts(clock.instant()))
                            .param("id", d.id)
                            .update()
                    }
            }!!
        if (due.isEmpty()) return PushRunReport(0, 0, 0, 0)
        val accessToken =
            try {
                accessToken(provider)
            } catch (e: IOException) {
                log.warn("FCM authentication failed: {}", e.javaClass.simpleName)
                due.forEach { outcome(it, Outcome.RETRY, "Authentication with FCM failed") }
                return PushRunReport(due.size, 0, due.size, 0)
            }
        var sent = 0
        var retry = 0
        var failed = 0
        due.forEach { d ->
            val token = tokens[d.deviceId]
            val o =
                if (token == null) {
                    Outcome.FAILED to "Device push disabled or revoked"
                } else {
                    send(provider, accessToken, token, d)
                }
            when (o.first) {
                Outcome.SENT -> sent++
                Outcome.RETRY -> retry++
                else -> failed++
            }
            outcome(d, o.first, o.second)
        }
        return PushRunReport(due.size, sent, retry, failed)
    }

    private fun send(
        p: ResolvedProvider,
        accessToken: String,
        token: String,
        d: Due,
    ): Pair<Outcome, String?> {
        val channel = runCatching { NotificationCategory.valueOf(d.category).channel }.getOrDefault("risk_safety")
        val message =
            mapOf(
                "message" to
                    mapOf(
                        "token" to token,
                        "data" to mapOf("notificationId" to d.notificationId.toString(), "channel" to channel, "title" to d.title, "body" to d.body, "deepLink" to (d.deepLink ?: "")),
                        "android" to mapOf("priority" to "high", "ttl" to "86400s"),
                    ),
            )
        val base = p.string("baseUrl")?.trimEnd('/') ?: "https://fcm.googleapis.com"
        val projectId = p.string("projectId") ?: return Outcome.FAILED to "FCM projectId is not configured"
        val req =
            HttpRequest
                .newBuilder(URI.create("$base/v1/projects/$projectId/messages:send"))
                .timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer $accessToken")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(message)))
                .build()
        return try {
            val r = http.send(req, HttpResponse.BodyHandlers.ofString())
            when {
                r.statusCode() in 200..299 -> Outcome.SENT to null
                r.statusCode() == 404 || r.body().contains("UNREGISTERED") -> Outcome.UNREGISTERED to "Token is no longer registered"
                r.statusCode() == 429 || r.statusCode() >= 500 -> Outcome.RETRY to "FCM HTTP ${r.statusCode()}"
                else -> Outcome.FAILED to "FCM HTTP ${r.statusCode()}"
            }
        } catch (e: IOException) {
            Outcome.RETRY to "Network error (${e.javaClass.simpleName})"
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Outcome.RETRY to "Interrupted"
        }
    }

    /** OAuth2 access token from the service-account credential (the provider's encrypted credential). */
    private fun accessToken(p: ResolvedProvider): String {
        val json = p.credential ?: throw IOException("No FCM credential configured")
        val creds = GoogleCredentials.fromStream(json.byteInputStream()).createScoped(listOf(SCOPE))
        creds.refreshIfExpired()
        return creds.accessToken?.tokenValue ?: throw IOException("No access token issued")
    }

    private fun outcome(
        d: Due,
        o: Outcome,
        error: String?,
    ) {
        tx.executeWithoutResult {
            val attempts = d.attempts + 1
            val status =
                when {
                    o == Outcome.SENT -> "SENT"
                    o == Outcome.RETRY && attempts < MAX_ATTEMPTS -> "PENDING"
                    else -> "FAILED"
                }
            val backoff = Duration.ofSeconds(BASE_BACKOFF_SECONDS shl minOf(attempts, MAX_SHIFT))
            jdbc
                .sql("update push_deliveries set status = :s, attempts = :a, last_error = :e, next_attempt_at = :n, updated_at = :now where id = :id")
                .param("s", status)
                .param("a", attempts)
                .param("e", error?.take(300))
                .param("n", ts(clock.instant().plus(backoff)))
                .param("now", ts(clock.instant()))
                .param("id", d.id)
                .update()
            if (o == Outcome.UNREGISTERED) {
                jdbc.sql("update devices set push_enabled = false, push_token_enc = null, push_token_fingerprint = null where id = :id").param("id", d.deviceId).update()
                audit.record(AuditCategory.NOTIFICATION, "PUSH_TOKEN_UNREGISTERED", AuditOutcome.FAILURE, "Device", d.deviceId, mapOf("notificationId" to d.notificationId))
            }
            // Aggregate delivery state onto the inbox item; the inbox itself is always complete.
            val states =
                jdbc
                    .sql("select status from push_deliveries where notification_id = :n")
                    .param("n", d.notificationId)
                    .query(String::class.java)
                    .list()
            val agg =
                when {
                    states.any { it == "PENDING" } -> "QUEUED"
                    states.all { it == "SENT" } -> "SENT"
                    states.any { it == "SENT" } -> "PARTIAL"
                    else -> "FAILED"
                }
            jdbc
                .sql("update notification_events set push_status = :s where id = :id")
                .param("s", agg)
                .param("id", d.notificationId)
                .update()
        }
    }

    fun queueDepth(): Int = jdbc.sql("select count(*) from push_deliveries where status = 'PENDING'").query(Int::class.java).single()

    private data class Due(
        val id: UUID,
        val deviceId: UUID,
        val attempts: Int,
        val notificationId: UUID,
        val category: String,
        val title: String,
        val body: String,
        val deepLink: String?,
    )

    private enum class Outcome { SENT, RETRY, FAILED, UNREGISTERED }

    companion object {
        const val SCOPE = "https://www.googleapis.com/auth/firebase.messaging"
        const val BATCH = 50
        const val MAX_ATTEMPTS = 6
        const val BASE_BACKOFF_SECONDS = 30L
        const val MAX_SHIFT = 6
        val CLAIM: Duration = Duration.ofMinutes(2)
    }
}
