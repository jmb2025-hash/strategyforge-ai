package app.strategyforge.settings

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import app.strategyforge.common.web.ETags
import app.strategyforge.common.web.Problems
import app.strategyforge.identity.RecentAuth
import app.strategyforge.notifications.NotificationCategory
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

data class NotificationPreferences(
    val pushEnabled: Boolean = true,
    /** Lock-screen content is redacted by default (FR-102). */
    val lockScreenRedaction: Boolean = true,
    val dailySummaryLocalTime: String = "17:00",
    val categories: Map<String, Boolean> = NotificationCategory.entries.associate { it.name to true },
)

data class PrivacyPreferences(
    /** No external analytics exist; crash reporting is opt-in and off by default. */
    val crashReportingOptIn: Boolean = false,
)

/** Defaults applied to new portfolios (FR-003 risk/cost defaults; editable per portfolio). */
data class PortfolioDefaults(
    val startingBalance: BigDecimal = BigDecimal("100000"),
    val commissionPerOrder: BigDecimal = BigDecimal.ZERO,
    val commissionPerShare: BigDecimal = BigDecimal.ZERO,
    val commissionPercent: BigDecimal = BigDecimal.ZERO,
    val slippageBps: BigDecimal = BigDecimal("2"),
    val equityFallbackSpreadPercent: BigDecimal = BigDecimal("0.10"),
    val cryptoFallbackSpreadPercent: BigDecimal = BigDecimal("0.20"),
    val participationRatePercent: BigDecimal = BigDecimal("10"),
    val executionDelaySeconds: Int = 1,
    val borrowRateAnnualPercent: BigDecimal = BigDecimal("3.0"),
    val shortInitialMarginPercent: BigDecimal = BigDecimal("50"),
    val shortMaintenancePercent: BigDecimal = BigDecimal("30"),
    val maxPriceDeviationPercent: BigDecimal = BigDecimal("1.0"),
)

data class OwnerSettings(
    val timezone: String,
    val displayCurrency: String,
    val showCadEquivalent: Boolean,
    val theme: String,
    val notifications: NotificationPreferences,
    val privacy: PrivacyPreferences,
    val portfolioDefaults: PortfolioDefaults,
    val riskDefaults: String = "/v1/risk/profiles/global",
    val updatedAt: Instant,
    val version: Long,
)

data class SettingsUpdate(
    val timezone: String,
    val displayCurrency: String,
    val showCadEquivalent: Boolean,
    val theme: String,
    val notifications: NotificationPreferences,
    val privacy: PrivacyPreferences,
    val portfolioDefaults: PortfolioDefaults,
)

object SettingsDefaults {
    private val mapper = ObjectMapper().findAndRegisterModules()
    val NOTIFICATIONS_JSON: String = mapper.writeValueAsString(NotificationPreferences())
    val PRIVACY_JSON: String = mapper.writeValueAsString(PrivacyPreferences())
    val PORTFOLIO_DEFAULTS_JSON: String = mapper.writeValueAsString(PortfolioDefaults())
}

@Service
class SettingsService(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
    private val audit: AuditService,
    private val clock: Clock,
) {
    fun get(): OwnerSettings =
        jdbc
            .sql("select * from owner_settings")
            .query { rs, _ ->
                OwnerSettings(
                    timezone = rs.getString("timezone"),
                    displayCurrency = rs.getString("display_currency"),
                    showCadEquivalent = rs.getBoolean("show_cad_equivalent"),
                    theme = rs.getString("theme"),
                    notifications = mapper.readValue(rs.getString("notifications"), NotificationPreferences::class.java),
                    privacy = mapper.readValue(rs.getString("privacy"), PrivacyPreferences::class.java),
                    portfolioDefaults = mapper.readValue(rs.getString("portfolio_defaults"), PortfolioDefaults::class.java),
                    updatedAt = rs.instant("updated_at"),
                    version = rs.getLong("version"),
                )
            }.optional()
            .orElseThrow { Problems.conflict("not-bootstrapped", "Owner settings do not exist until bootstrap") }

    fun zone(): ZoneId = runCatching { ZoneId.of(get().timezone) }.getOrDefault(ZoneId.of("America/Halifax"))

    @Transactional
    fun update(
        req: SettingsUpdate,
        ifMatch: String?,
    ): OwnerSettings {
        val current = get()
        ETags.require(ifMatch, current.version)
        validate(req)
        if (increasesRisk(current.portfolioDefaults, req.portfolioDefaults)) RecentAuth.require(clock.instant(), "increase-risk-defaults")
        val n =
            jdbc
                .sql(
                    """
                    update owner_settings set timezone = :tz, display_currency = :cur, show_cad_equivalent = :cad, theme = :theme,
                      notifications = cast(:n as jsonb), privacy = cast(:p as jsonb), portfolio_defaults = cast(:d as jsonb),
                      updated_at = :now, version = version + 1
                    where version = :v
                    """.trimIndent(),
                ).param("tz", req.timezone)
                .param("cur", req.displayCurrency)
                .param("cad", req.showCadEquivalent)
                .param("theme", req.theme)
                .param("n", mapper.writeValueAsString(req.notifications))
                .param("p", mapper.writeValueAsString(req.privacy))
                .param("d", mapper.writeValueAsString(req.portfolioDefaults))
                .param("now", ts(clock.instant()))
                .param("v", current.version)
                .update()
        if (n == 0) throw Problems.preconditionFailed("Settings changed concurrently; reload and retry")
        val updated = get()
        audit.record(
            AuditCategory.SETTINGS,
            "SETTINGS_UPDATED",
            entityType = "OwnerSettings",
            entityId = "owner",
            details = mapOf("before" to mapper.convertValue(current, Map::class.java), "after" to mapper.convertValue(updated, Map::class.java)),
        )
        return updated
    }

    private fun increasesRisk(
        old: PortfolioDefaults,
        new: PortfolioDefaults,
    ): Boolean =
        new.shortMaintenancePercent < old.shortMaintenancePercent || new.shortInitialMarginPercent < old.shortInitialMarginPercent ||
            new.maxPriceDeviationPercent > old.maxPriceDeviationPercent

    private fun validate(req: SettingsUpdate) {
        try {
            ZoneId.of(req.timezone)
        } catch (e: DateTimeException) {
            throw Problems.badRequest("invalid-timezone", "Unknown timezone '${req.timezone}'")
        }
        if (req.displayCurrency !in setOf("USD", "CAD")) throw Problems.badRequest("invalid-currency", "displayCurrency must be USD or CAD")
        if (req.theme !in setOf("SYSTEM", "LIGHT", "DARK")) throw Problems.badRequest("invalid-theme", "theme must be SYSTEM, LIGHT or DARK")
        try {
            LocalTime.parse(req.notifications.dailySummaryLocalTime)
        } catch (e: DateTimeException) {
            throw Problems.badRequest("invalid-time", "dailySummaryLocalTime must be HH:mm")
        }
        val known = NotificationCategory.entries.map { it.name }.toSet()
        val unknown = req.notifications.categories.keys - known
        if (unknown.isNotEmpty()) throw Problems.badRequest("unknown-notification-category", "Unknown categories: $unknown")
        val disabledCritical = NotificationCategory.entries.filter { it.critical && req.notifications.categories[it.name] == false }
        if (disabledCritical.isNotEmpty()) {
            throw Problems.unprocessable("critical-notification-required", "Critical safety notifications cannot be disabled: ${disabledCritical.map { it.name }}")
        }
        val d = req.portfolioDefaults

        fun range(
            name: String,
            v: BigDecimal,
            min: String,
            max: String,
        ) {
            if (v < BigDecimal(min) || v > BigDecimal(max)) throw Problems.badRequest("invalid-portfolio-default", "$name must be between $min and $max")
        }
        range("startingBalance", d.startingBalance, "100", "100000000")
        range("commissionPerOrder", d.commissionPerOrder, "0", "1000")
        range("commissionPerShare", d.commissionPerShare, "0", "10")
        range("commissionPercent", d.commissionPercent, "0", "5")
        range("slippageBps", d.slippageBps, "0", "500")
        range("equityFallbackSpreadPercent", d.equityFallbackSpreadPercent, "0.01", "5")
        range("cryptoFallbackSpreadPercent", d.cryptoFallbackSpreadPercent, "0.01", "10")
        range("participationRatePercent", d.participationRatePercent, "0.1", "100")
        range("borrowRateAnnualPercent", d.borrowRateAnnualPercent, "0", "100")
        range("shortInitialMarginPercent", d.shortInitialMarginPercent, "50", "300")
        range("shortMaintenancePercent", d.shortMaintenancePercent, "25", "200")
        range("maxPriceDeviationPercent", d.maxPriceDeviationPercent, "0.1", "10")
        if (d.executionDelaySeconds !in 0..300) throw Problems.badRequest("invalid-portfolio-default", "executionDelaySeconds must be 0-300")
    }
}

@RestController
@RequestMapping("/v1/settings")
@Tag(name = "Configuration")
class SettingsController(
    private val settings: SettingsService,
) {
    @GetMapping
    fun get(): ResponseEntity<OwnerSettings> = settings.get().let { ETags.ok(it, it.version) }

    /** Full replacement with optimistic concurrency (If-Match). */
    @PutMapping
    fun put(
        @RequestBody req: SettingsUpdate,
        @RequestHeader("If-Match", required = false) ifMatch: String?,
    ): ResponseEntity<OwnerSettings> = settings.update(req, ifMatch).let { ETags.ok(it, it.version) }
}
