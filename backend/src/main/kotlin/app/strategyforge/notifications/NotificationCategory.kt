package app.strategyforge.notifications

/**
 * Notification categories (section 14, FR-101). Critical safety categories are always
 * delivered to the inbox and, when a push channel exists, pushed; they cannot be disabled.
 */
enum class NotificationCategory(
    val channel: String,
    val critical: Boolean,
) {
    RECOMMENDATION("recommendation_action", false),
    RECOMMENDATION_EXPIRY("recommendation_action", false),
    ORDER_FILL("order_execution", false),
    ORDER_REJECTION("order_execution", false),
    STOP_TARGET("order_execution", false),
    RISK_EVENT("risk_safety", true),
    STRATEGY_HEALTH("strategy_health", false),
    STRATEGY_SUSPENSION("risk_safety", true),
    STALE_DATA("market_data_health", true),
    MARKET_DATA_HEALTH("market_data_health", false),
    SYSTEM_HEALTH("risk_safety", true),
    SECURITY("security", true),
    DAILY_SUMMARY("daily_summary", false),
    PRICE_ALERT("market_data_health", false),
}
