package app.strategyforge.notifications

import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.web.Cursor
import app.strategyforge.common.web.PageResponse
import app.strategyforge.common.web.Paging
import app.strategyforge.common.web.Problems
import app.strategyforge.common.web.parseUuid
import app.strategyforge.identity.SecurityEvent
import app.strategyforge.settings.SettingsService
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.event.EventListener
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class Severity { INFO, WARNING, CRITICAL }

data class NotificationView(
    val id: UUID,
    val category: String,
    val severity: String,
    val critical: Boolean,
    val title: String,
    val body: String,
    val redactedTitle: String,
    val redactedBody: String,
    val entityType: String?,
    val entityId: String?,
    val deepLink: String?,
    val pushStatus: String,
    val createdAt: Instant,
    val readAt: Instant?,
)

/**
 * The in-app inbox is authoritative (FR-100): every notification is stored first, whether or
 * not push is available. Push deliveries are queued as a convenience channel and carry only the
 * redacted text and the notification id; they can never execute a trade (section 14).
 */
@Service
class NotificationService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
    private val settings: SettingsService,
) {
    @Transactional(propagation = Propagation.REQUIRED)
    fun notify(
        category: NotificationCategory,
        severity: Severity,
        title: String,
        body: String,
        entityType: String? = null,
        entityId: Any? = null,
        dedupeKey: String? = null,
    ): UUID? {
        val id = UUID.randomUUID()
        val now = clock.instant()
        val prefs = runCatching { settings.get().notifications }.getOrNull()
        val pushAllowed = category.critical || (prefs?.pushEnabled != false && prefs?.categories?.get(category.name) != false)
        val targets = if (pushAllowed) activeDevices() else emptyList()
        val pushStatus =
            when {
                !pushAllowed -> "DISABLED_BY_PREFERENCE"
                targets.isEmpty() || !pushChannelConfigured() -> "NO_CHANNEL"
                else -> "QUEUED"
            }
        val deepLink = entityType?.let { "strategyforge://${it.lowercase()}/${entityId ?: ""}" }
        val inserted =
            jdbc
                .sql(
                    """
                    insert into notification_events(id, category, severity, title, body, redacted_title, redacted_body, entity_type, entity_id,
                      deep_link, dedupe_key, push_status, created_at)
                    values (:id, :cat, :sev, :title, :body, :rt, :rb, :et, :eid, :dl, :dk, :ps, :now)
                    on conflict (dedupe_key) do nothing
                    """.trimIndent(),
                ).param("id", id)
                .param("cat", category.name)
                .param("sev", severity.name)
                .param("title", title.take(200))
                .param("body", body.take(2000))
                .param("rt", redactedTitle(category))
                .param("rb", REDACTED_BODY)
                .param("et", entityType)
                .param("eid", entityId?.toString())
                .param("dl", deepLink)
                .param("dk", dedupeKey)
                .param("ps", pushStatus)
                .param("now", ts(now))
                .update()
        if (inserted == 0) return null
        if (pushStatus == "QUEUED") {
            targets.forEach { device ->
                jdbc
                    .sql(
                        """
                        insert into push_deliveries(id, notification_id, device_id, status, created_at, updated_at, next_attempt_at)
                        values (:id, :n, :d, 'PENDING', :now, :now, :now)
                        """.trimIndent(),
                    ).param("id", UUID.randomUUID())
                    .param("n", id)
                    .param("d", device)
                    .param("now", ts(now))
                    .update()
            }
        }
        return id
    }

    private fun activeDevices(): List<UUID> = jdbc.sql("select id from devices where push_enabled and revoked_at is null and push_token_enc is not null").query(UUID::class.java).list()

    private fun pushChannelConfigured(): Boolean = jdbc.sql("select count(*) from provider_configurations where kind = 'PUSH' and active and archived_at is null").query(Int::class.java).single() > 0

    fun list(
        cursor: String?,
        limit: Int?,
        unreadOnly: Boolean,
    ): PageResponse<NotificationView> {
        val c = Cursor.decode(cursor)
        val l = Paging.limit(limit)
        val rows =
            jdbc
                .sql(
                    """
                    select * from notification_events
                    where (:unread = false or read_at is null)
                      and (cast(:cat as timestamptz) is null or (created_at, id) < (cast(:cat as timestamptz), cast(:cid as uuid)))
                    order by created_at desc, id desc limit :l
                    """.trimIndent(),
                ).param("unread", unreadOnly)
                .param("cat", ts(c?.at))
                .param("cid", c?.id)
                .param("l", l + 1)
                .query { rs, _ -> map(rs) }
                .list()
        return Paging.page(rows, l) { Cursor(it.createdAt, it.id.toString()) }
    }

    fun get(id: UUID): NotificationView =
        jdbc
            .sql("select * from notification_events where id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElseThrow { Problems.notFound("Notification", id) }

    fun unreadCount(): Int = jdbc.sql("select count(*) from notification_events where read_at is null").query(Int::class.java).single()

    @Transactional
    fun markRead(id: UUID): NotificationView {
        get(id)
        jdbc
            .sql("update notification_events set read_at = coalesce(read_at, :now) where id = :id")
            .param("now", ts(clock.instant()))
            .param("id", id)
            .update()
        return get(id)
    }

    @Transactional
    fun markAllRead(): Int = jdbc.sql("update notification_events set read_at = :now where read_at is null").param("now", ts(clock.instant())).update()

    @EventListener
    fun onSecurityEvent(e: SecurityEvent) {
        notify(NotificationCategory.SECURITY, Severity.CRITICAL, "Security: ${e.action.replace('_', ' ').lowercase()}", e.detail, "security", e.action)
    }

    private fun map(rs: java.sql.ResultSet): NotificationView {
        val cat = rs.getString("category")
        return NotificationView(
            rs.uuid("id"),
            cat,
            rs.getString("severity"),
            NotificationCategory.entries.firstOrNull { it.name == cat }?.critical ?: false,
            rs.getString("title"),
            rs.getString("body"),
            rs.getString("redacted_title"),
            rs.getString("redacted_body"),
            rs.getString("entity_type"),
            rs.getString("entity_id"),
            rs.getString("deep_link"),
            rs.getString("push_status"),
            rs.instant("created_at"),
            rs.instantOrNull("read_at"),
        )
    }

    companion object {
        const val REDACTED_BODY = "Open StrategyForge to view details."

        fun redactedTitle(c: NotificationCategory): String =
            when (c) {
                NotificationCategory.RECOMMENDATION -> "New paper-trading recommendation"
                NotificationCategory.RECOMMENDATION_EXPIRY -> "A recommendation expired"
                NotificationCategory.ORDER_FILL -> "Paper order update"
                NotificationCategory.ORDER_REJECTION -> "Paper order rejected"
                NotificationCategory.STOP_TARGET -> "Stop or target event"
                NotificationCategory.RISK_EVENT -> "Risk alert"
                NotificationCategory.STRATEGY_HEALTH -> "Strategy health update"
                NotificationCategory.STRATEGY_SUSPENSION -> "Strategy paused or suspended"
                NotificationCategory.STALE_DATA -> "Market data is stale"
                NotificationCategory.MARKET_DATA_HEALTH -> "Market data update"
                NotificationCategory.SYSTEM_HEALTH -> "System health alert"
                NotificationCategory.SECURITY -> "Security event"
                NotificationCategory.DAILY_SUMMARY -> "Daily summary available"
                NotificationCategory.PRICE_ALERT -> "Price alert"
            }
    }
}

@RestController
@RequestMapping("/v1/notifications")
@Tag(name = "Notifications")
class NotificationsController(
    private val notifications: NotificationService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
        @RequestParam(defaultValue = "false") unreadOnly: Boolean,
    ) = notifications.list(cursor, limit, unreadOnly)

    @GetMapping("/unread-count")
    fun unread() = mapOf("unread" to notifications.unreadCount())

    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ) = notifications.get(parseUuid(id))

    @PostMapping("/{id}/read")
    fun read(
        @PathVariable id: String,
    ) = notifications.markRead(parseUuid(id))

    @PostMapping("/read-all")
    fun readAll(): ResponseEntity<Map<String, Int>> = ResponseEntity.ok(mapOf("updated" to notifications.markAllRead()))
}
