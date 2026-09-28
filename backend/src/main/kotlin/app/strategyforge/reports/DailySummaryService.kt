package app.strategyforge.reports

import app.strategyforge.common.config.StrategyForgeProperties
import app.strategyforge.common.db.ts
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.notifications.NotificationCategory
import app.strategyforge.notifications.NotificationService
import app.strategyforge.notifications.Severity
import app.strategyforge.portfolio.PortfolioService
import app.strategyforge.settings.SettingsService
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Daily summary notification (FR-101) at the owner's configured local time. The dedupe key is the
 * local date, so restarts and repeated scheduler ticks send at most one summary per day.
 */
@Service
class DailySummaryService(
    private val jdbc: JdbcClient,
    private val settings: SettingsService,
    private val portfolios: PortfolioService,
    private val notifications: NotificationService,
    private val props: StrategyForgeProperties,
    private val clock: Clock,
) {
    @Scheduled(fixedDelayString = "\${strategyforge.scheduler.daily-summary-interval-ms:60000}", initialDelay = 30000)
    fun scheduled() {
        if (!props.scheduler.enabled) return
        CorrelationIdFilter.withCorrelation("daily-summary") { runIfDue(clock.instant()) }
    }

    /** Returns the notification id when a summary was created. */
    fun runIfDue(now: Instant): UUID? {
        val s = settings.get()
        val zone = runCatching { ZoneId.of(s.timezone) }.getOrDefault(ZoneId.of("UTC"))
        val local = now.atZone(zone)
        val at = runCatching { LocalTime.parse(s.notifications.dailySummaryLocalTime) }.getOrDefault(LocalTime.of(17, 0))
        if (local.toLocalTime().isBefore(at)) return null
        val key = "daily-summary:${local.toLocalDate()}"
        if (jdbc
                .sql("select count(*) from notification_events where dedupe_key = :k")
                .param("k", key)
                .query(Int::class.java)
                .single() > 0
        ) {
            return null
        }
        val dayStart = local.toLocalDate().atStartOfDay(zone).toInstant()
        val active = portfolios.list(false)
        val equity = active.fold(BigDecimal.ZERO) { acc, p -> acc.add(portfolios.summary(p.id).equity) }

        fun count(sql: String) =
            jdbc
                .sql(sql)
                .param("t", ts(dayStart))
                .query(Int::class.java)
                .single()
        val fills = count("select count(*) from paper_executions where executed_at >= :t")
        val recs = count("select count(*) from recommendations where created_at >= :t")
        val blocked = count("select count(*) from risk_evaluations where created_at >= :t and decision <> 'ALLOW'")
        val body =
            "${active.size} paper portfolio(s), combined equity ${equity.setScale(2, RoundingMode.HALF_EVEN).toPlainString()} USD (simulated). " +
                "Today: $fills fill(s), $recs recommendation(s), $blocked risk block(s)."
        return notifications.notify(NotificationCategory.DAILY_SUMMARY, Severity.INFO, "Daily summary ${local.toLocalDate()}", body, null, null, key)
    }
}
