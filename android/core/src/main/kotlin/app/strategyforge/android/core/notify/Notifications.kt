package app.strategyforge.android.core.notify

/** Android notification channels (section 14); critical categories cannot be silenced in-app. */
enum class Channel(
    val id: String,
    val label: String,
    val critical: Boolean,
) {
    RECOMMENDATION("recommendation_action", "Recommendation actions", false),
    ORDERS("order_execution", "Orders and executions", false),
    RISK("risk_safety", "Risk and safety alerts", true),
    STRATEGY("strategy_health", "Strategy health", false),
    MARKET_DATA("market_data_health", "Market-data health", true),
    SECURITY("security", "Security events", true),
    DAILY("daily_summary", "Daily portfolio summary", false),
    ;

    companion object {
        fun of(id: String?): Channel = entries.firstOrNull { it.id == id } ?: RISK
    }
}

/** A push payload carries only identifiers and redacted text; details are loaded after authentication. */
data class PushPayload(
    val notificationId: String?,
    val channel: String?,
    val title: String?,
    val body: String?,
    val deepLink: String?,
)

data class DisplayedNotification(
    val channel: Channel,
    /** Shown on the lock screen (always redacted, FR-102). */
    val publicTitle: String,
    val publicBody: String,
    /** Shown only when the device is unlocked, unless the owner keeps redaction everywhere. */
    val privateTitle: String,
    val privateBody: String,
    val route: Route?,
)

object NotificationPresenter {
    private const val PUBLIC_BODY = "Open StrategyForge to view details."

    /**
     * Lock-screen content is always generic. Unlocked content uses the (already redacted) payload text
     * unless [redactUnlocked] is set by the privacy preference. Payloads can never trigger actions:
     * the only effect is navigation to an authenticated screen (section 14).
     */
    fun present(
        p: PushPayload,
        redactUnlocked: Boolean,
    ): DisplayedNotification {
        val channel = Channel.of(p.channel)
        val publicTitle = "StrategyForge: ${channel.label.lowercase()}"
        val title = (p.title?.takeIf { it.isNotBlank() && !redactUnlocked } ?: publicTitle).take(MAX_TITLE)
        val body = (p.body?.takeIf { it.isNotBlank() && !redactUnlocked } ?: PUBLIC_BODY).take(MAX_BODY)
        val route = DeepLinks.parse(p.deepLink) ?: p.notificationId?.let { Route.Notification(it) }
        return DisplayedNotification(channel, publicTitle, PUBLIC_BODY, title, body, route)
    }

    private const val MAX_TITLE = 80
    private const val MAX_BODY = 240
}

/** Navigation targets reachable from notifications and links. Every target requires a signed-in session. */
sealed interface Route {
    data class Recommendation(
        val id: String,
    ) : Route

    data class Order(
        val id: String,
    ) : Route

    data class Strategy(
        val id: String,
    ) : Route

    data class Portfolio(
        val id: String,
    ) : Route

    data class Notification(
        val id: String,
    ) : Route

    data class Research(
        val id: String,
    ) : Route

    /** A TSX portfolio plan run (D-055). */
    data class TsxRun(
        val id: String,
    ) : Route

    data object Inbox : Route

    data object Diagnostics : Route

    data object Emergency : Route
}

/**
 * Parses `strategyforge://` and backend `/app/...` links. Only known shapes with UUID identifiers are
 * accepted; anything else is ignored, so a crafted link cannot reach an unexpected screen or action.
 */
object DeepLinks {
    private val uuid = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun parse(link: String?): Route? {
        if (link.isNullOrBlank() || link.length > MAX_LINK) return null
        val path =
            when {
                link.startsWith("strategyforge://") -> link.removePrefix("strategyforge://")
                link.startsWith("/app/") -> link.removePrefix("/app/")
                else -> return null
            }.substringBefore('?').trim('/')
        val parts = path.split('/')
        if (parts.size == 1) {
            return when (parts[0]) {
                "inbox", "notifications" -> Route.Inbox
                "diagnostics" -> Route.Diagnostics
                "emergency" -> Route.Emergency
                else -> null
            }
        }
        if (parts.size != 2 || !uuid.matches(parts[1])) return null
        val id = parts[1].lowercase()
        return when (parts[0]) {
            "recommendations", "recommendation" -> Route.Recommendation(id)
            "orders", "order", "paperorder" -> Route.Order(id)
            "strategies", "strategy" -> Route.Strategy(id)
            "portfolios", "portfolio" -> Route.Portfolio(id)
            "notifications", "notification" -> Route.Notification(id)
            "research", "researchsession" -> Route.Research(id)
            "tsxrun", "tsx-runs" -> Route.TsxRun(id)
            else -> null
        }
    }

    fun toUri(route: Route): String =
        when (route) {
            is Route.Recommendation -> "strategyforge://recommendations/${route.id}"
            is Route.Order -> "strategyforge://orders/${route.id}"
            is Route.Strategy -> "strategyforge://strategies/${route.id}"
            is Route.Portfolio -> "strategyforge://portfolios/${route.id}"
            is Route.Notification -> "strategyforge://notifications/${route.id}"
            is Route.Research -> "strategyforge://research/${route.id}"
            is Route.TsxRun -> "strategyforge://tsxrun/${route.id}"
            Route.Inbox -> "strategyforge://inbox"
            Route.Diagnostics -> "strategyforge://diagnostics"
            Route.Emergency -> "strategyforge://emergency"
        }

    private const val MAX_LINK = 300
}
