package app.strategyforge.engine.notifications

import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.bool
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.settings.SettingsService
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
    val shown: Boolean,
    val createdAt: Instant,
    val readAt: Instant?,
)

/**
 * The in-app inbox is authoritative (FR-100): every notification is stored first. The app shows
 * a local Android notification for each row still marked unshown, using only the redacted text
 * on the lock screen (FR-102); a notification can never execute a trade (section 14).
 * Categories the owner turned off stay in the inbox but are never shown; critical ones always are.
 */
class NotificationService(
    private val db: Db,
    private val clock: Clock,
    private val settings: SettingsService,
) {
    /** Called after a notification is stored so the host can show it immediately. */
    @Volatile
    var listener: ((NotificationView) -> Unit)? = null

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
        val showAllowed = category.critical || (prefs?.notificationsEnabled != false && prefs?.categories?.get(category.name) != false)
        val deepLink = entityType?.let { "strategyforge://${it.lowercase()}/${entityId ?: ""}" }
        val inserted =
            db
                .sql(
                    """
                    insert or ignore into notification_events(id, category, severity, title, body, redacted_title, redacted_body, entity_type, entity_id,
                      deep_link, dedupe_key, shown, created_at)
                    values (:id, :cat, :sev, :title, :body, :rt, :rb, :et, :eid, :dl, :dk, :shown, :now)
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
                .param("shown", !showAllowed)
                .param("now", now)
                .update()
        if (inserted == 0) return null
        if (showAllowed) listener?.let { l -> runCatching { l(get(id)) } }
        return id
    }

    fun list(
        limit: Int,
        unreadOnly: Boolean,
        before: Instant? = null,
    ): List<NotificationView> =
        db
            .sql(
                """
                select * from notification_events
                where (:unread = 0 or read_at is null) and (:before is null or created_at < :before)
                order by created_at desc, id desc limit :l
                """.trimIndent(),
            ).param("unread", unreadOnly)
            .param("before", before)
            .param("l", limit.coerceIn(1, 200))
            .list { map(it) }

    fun get(id: UUID): NotificationView =
        db
            .sql("select * from notification_events where id = :id")
            .param("id", id)
            .firstOrNull { map(it) } ?: throw Problems.notFound("Notification", id)

    fun unreadCount(): Int = db.sql("select count(*) n from notification_events where read_at is null").int()

    /** Stored notifications not yet shown on the device (for example, raised while the service was stopped). */
    fun pendingLocal(): List<NotificationView> = db.sql("select * from notification_events where shown = 0 order by created_at").list { map(it) }

    fun markShown(id: UUID) {
        db.sql("update notification_events set shown = 1 where id = :id").param("id", id).update()
    }

    fun markRead(id: UUID): NotificationView {
        get(id)
        db
            .sql("update notification_events set read_at = coalesce(read_at, :now), shown = 1 where id = :id")
            .param("now", clock.instant())
            .param("id", id)
            .update()
        return get(id)
    }

    fun markAllRead(): Int = db.sql("update notification_events set read_at = :now, shown = 1 where read_at is null").param("now", clock.instant()).update()

    private fun map(rs: Row): NotificationView {
        val cat = rs.str("category")
        return NotificationView(
            rs.uuid("id"),
            cat,
            rs.str("severity"),
            NotificationCategory.entries.firstOrNull { it.name == cat }?.critical ?: false,
            rs.str("title"),
            rs.str("body"),
            rs.str("redacted_title"),
            rs.str("redacted_body"),
            rs.string("entity_type"),
            rs.string("entity_id"),
            rs.string("deep_link"),
            rs.bool("shown"),
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
