@file:UseSerializers(BigDecimalSerializer::class, InstantSerializer::class)

package app.strategyforge.engine.settings

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.BigDecimalSerializer
import app.strategyforge.engine.common.EngineJson
import app.strategyforge.engine.common.InstantSerializer
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.common.RecentAuth
import app.strategyforge.engine.common.toJsonElement
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.str
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.portfolio.CostModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigDecimal
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class NotificationPreferences(
    val notificationsEnabled: Boolean = true,
    /** Lock-screen content is redacted by default (FR-102). */
    val lockScreenRedaction: Boolean = true,
    val dailySummaryLocalTime: String = "17:00",
    val categories: Map<String, Boolean> = NotificationCategory.entries.associate { it.name to true },
)

@Serializable
data class PrivacyPreferences(
    /** No external analytics exist; crash reporting is opt-in and off by default. */
    val crashReportingOptIn: Boolean = false,
)

/** Defaults applied to new portfolios (FR-003 risk/cost defaults; editable per portfolio). */
@Serializable
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
) {
    fun costModel() =
        CostModel(
            commissionPerOrder = commissionPerOrder,
            commissionPerShare = commissionPerShare,
            commissionPercent = commissionPercent,
            slippageBps = slippageBps,
            equityFallbackSpreadPercent = equityFallbackSpreadPercent,
            cryptoFallbackSpreadPercent = cryptoFallbackSpreadPercent,
            participationRatePercent = participationRatePercent,
            executionDelaySeconds = executionDelaySeconds,
            borrowRateAnnualPercent = borrowRateAnnualPercent,
            shortInitialMarginPercent = shortInitialMarginPercent,
            shortMaintenancePercent = shortMaintenancePercent,
            maxPriceDeviationPercent = maxPriceDeviationPercent,
        )
}

@Serializable
data class OwnerSettings(
    val timezone: String = "America/Halifax",
    val displayCurrency: String = "USD",
    val showCadEquivalent: Boolean = false,
    val theme: String = "SYSTEM",
    val notifications: NotificationPreferences = NotificationPreferences(),
    val privacy: PrivacyPreferences = PrivacyPreferences(),
    val portfolioDefaults: PortfolioDefaults = PortfolioDefaults(),
    val updatedAt: Instant = Instant.EPOCH,
    val version: Long = 0,
)

/**
 * Owner preferences (FR-005), stored as one JSON document in the key/value settings table.
 * Updates use optimistic concurrency on [OwnerSettings.version]; lowering margin requirements
 * or widening the price-deviation guard needs a recent device-lock confirmation.
 */
class SettingsService(
    private val db: Db,
    private val audit: AuditService,
    private val auth: RecentAuth,
    private val clock: Clock,
) {
    fun get(): OwnerSettings =
        db
            .sql("select value from settings where key = :k")
            .param("k", KEY)
            .firstOrNull { EngineJson.decodeFromString(OwnerSettings.serializer(), it.str("value")) }
            ?: OwnerSettings()

    fun zone(): ZoneId = runCatching { ZoneId.of(get().timezone) }.getOrDefault(ZoneId.of("America/Halifax"))

    fun update(
        req: OwnerSettings,
        expectedVersion: Long,
    ): OwnerSettings =
        db.tx {
            val current = get()
            if (current.version != expectedVersion) throw Problems.preconditionFailed("Settings changed; reload and retry")
            validate(req)
            if (increasesRisk(current.portfolioDefaults, req.portfolioDefaults)) auth.require("increase risk defaults")
            val updated = req.copy(updatedAt = clock.instant(), version = current.version + 1)
            db
                .sql("insert or replace into settings(key, value) values (:k, :v)")
                .param("k", KEY)
                .param("v", EngineJson.encodeToString(OwnerSettings.serializer(), updated))
                .update()
            audit.record(
                AuditCategory.SETTINGS,
                "SETTINGS_UPDATED",
                entityType = "OwnerSettings",
                entityId = "owner",
                details = mapOf("before" to EngineJson.encodeToJsonElement(OwnerSettings.serializer(), current), "after" to EngineJson.encodeToJsonElement(OwnerSettings.serializer(), updated)),
            )
            updated
        }

    private fun increasesRisk(
        old: PortfolioDefaults,
        new: PortfolioDefaults,
    ): Boolean =
        new.shortMaintenancePercent < old.shortMaintenancePercent || new.shortInitialMarginPercent < old.shortInitialMarginPercent ||
            new.maxPriceDeviationPercent > old.maxPriceDeviationPercent

    private fun validate(req: OwnerSettings) {
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

    companion object {
        const val KEY = "owner_settings"
    }
}
