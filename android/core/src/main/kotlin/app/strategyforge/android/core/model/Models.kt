package app.strategyforge.android.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// DTOs mirror backend responses. Money and quantities are decimal STRINGS (never floating point,
// NFR-002); timestamps are ISO-8601 UTC strings formatted for display in the owner's timezone.

@Serializable
data class Page<T>(
    val items: List<T> = emptyList(),
    val nextCursor: String? = null,
)

@Serializable
data class BootstrapStatus(
    val bootstrapped: Boolean,
    val bootstrapTokenRequired: Boolean = false,
)

@Serializable
data class SessionResponse(
    val sessionId: String,
    val accessToken: String,
    val expiresAt: String,
    val lastAuthenticatedAt: String,
)

@Serializable
data class BootstrapResponse(
    val session: SessionResponse,
    val recoveryCodes: List<String>,
    val notice: String,
)

@Serializable
data class RecoverResponse(
    val session: SessionResponse,
    val remainingRecoveryCodes: Int,
)

@Serializable
data class Me(
    val ownerId: String,
    val username: String,
    val totpEnabled: Boolean,
    val sessionId: String,
    val lastAuthenticatedAt: String,
    val recentAuthValidUntil: String,
    val remainingRecoveryCodes: Int,
)

@Serializable
data class SessionInfo(
    val id: String,
    val deviceName: String? = null,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val current: Boolean = false,
)

@Serializable
data class Portfolio(
    val id: String,
    val name: String,
    val status: String,
    val baseCurrency: String = "USD",
    val startingBalance: String,
    val shortingEnabled: Boolean = false,
    val reconciliationStatus: String = "OK",
    val createdAt: String? = null,
    val version: Long = 0,
)

@Serializable
data class Position(
    val instrumentId: String,
    val symbol: String,
    val assetClass: String,
    val side: String,
    val quantity: String,
    val costBasis: String,
    val averageCost: String,
    val marketPrice: String? = null,
    val marketValue: String? = null,
    val unrealizedPnl: String? = null,
    val priceTimestamp: String? = null,
    val priceStatus: String = "VERIFIED",
    val manualReviewRequired: Boolean = false,
)

@Serializable
data class PortfolioSummary(
    val portfolio: Portfolio,
    val cash: String,
    val reservedCash: String = "0",
    val buyingPower: String,
    val equity: String,
    val realizedPnl: String = "0",
    val unrealizedPnl: String = "0",
    val fees: String = "0",
    val totalReturn: String = "0",
    val totalReturnPercent: String? = null,
    val fullyPriced: Boolean = true,
    val positions: List<Position> = emptyList(),
    val asOf: String,
)

@Serializable
data class Order(
    val id: String,
    val portfolioId: String,
    val symbol: String,
    val side: String,
    val orderType: String,
    val timeInForce: String = "DAY",
    val quantity: String,
    val limitPrice: String? = null,
    val stopPrice: String? = null,
    val status: String,
    val source: String,
    val strategyId: String? = null,
    val recommendationId: String? = null,
    val filledQuantity: String = "0",
    val averageFillPrice: String? = null,
    val rejectionReason: String? = null,
    val createdAt: String,
)

@Serializable
data class OrderResult(
    val order: Order,
)

@Serializable
data class Strategy(
    val id: String,
    val name: String,
    val assetClass: String,
    val status: String,
    val statusReason: String? = null,
    val currentVersionId: String? = null,
    val currentVersion: Int? = null,
    val contentHash: String? = null,
    val validationStatus: String? = null,
    val updatedAt: String? = null,
    val version: Long = 0,
)

@Serializable
data class StrategyVersion(
    val id: String,
    val versionNumber: Int,
    val content: JsonElement,
    val contentHash: String,
    val source: String,
    val sourceRef: String? = null,
    val validationStatus: String,
    val createdAt: String,
)

@Serializable
data class ValidationIssue(
    val code: String,
    val path: String? = null,
    val message: String,
    val category: String? = null,
    val severity: String? = null,
)

@Serializable
data class Validation(
    val id: String,
    val status: String,
    val issues: List<ValidationIssue> = emptyList(),
)

@Serializable
data class StrategyDetail(
    val strategy: Strategy,
    val currentVersion: StrategyVersion? = null,
    val validation: Validation? = null,
    val explanation: String? = null,
)

@Serializable
data class StrategyResult(
    val strategy: Strategy,
    val version: StrategyVersion? = null,
    val validation: Validation? = null,
)

@Serializable
data class Activation(
    val id: String,
    val strategyId: String,
    val portfolioId: String,
    val mode: String,
    val allocationPercent: String,
    val status: String,
    val createdAt: String,
    val endReason: String? = null,
)

@Serializable
data class Disclosure(
    val version: String,
    val text: String,
)

@Serializable
data class Backtest(
    val id: String,
    val strategyId: String,
    val status: String,
    val resultStatus: String? = null,
    val metrics: JsonObject? = null,
    val error: String? = null,
    val createdAt: String,
    val completedAt: String? = null,
)

@Serializable
data class Recommendation(
    val id: String,
    val strategyId: String,
    val strategyName: String,
    val portfolioId: String,
    val symbol: String,
    val status: String,
    val statusReason: String? = null,
    val side: String,
    val quantity: String,
    val orderType: String,
    val limitPrice: String? = null,
    val referencePrice: String,
    val maxDeviationPercent: String,
    val expiresAt: String,
    val snoozedUntil: String? = null,
    val orderId: String? = null,
    val rationale: String,
    val triggeredRules: List<String> = emptyList(),
    val versionHash: String,
    val createdAt: String,
)

@Serializable
data class ActionToken(
    val token: String,
    val expiresAt: String,
)

@Serializable
data class RecommendationDetail(
    val recommendation: Recommendation,
    val actionToken: ActionToken? = null,
    val decisions: List<JsonObject> = emptyList(),
    val disclaimer: String,
)

@Serializable
data class DecisionResult(
    val recommendation: Recommendation,
    val orderId: String? = null,
    val orderStatus: String? = null,
    val detail: String,
)

@Serializable
data class Notification(
    val id: String,
    val category: String,
    val severity: String,
    val critical: Boolean,
    val title: String,
    val body: String,
    val redactedTitle: String,
    val redactedBody: String,
    val entityType: String? = null,
    val entityId: String? = null,
    val deepLink: String? = null,
    val pushStatus: String,
    val createdAt: String,
    val readAt: String? = null,
)

@Serializable
data class UnreadCount(
    val unread: Int,
)

@Serializable
data class EmergencyState(
    val pauseAll: Boolean,
    val preventNewPositions: Boolean,
    val reason: String? = null,
    val updatedAt: String,
    val version: Long = 0,
)

@Serializable
data class EmergencyResult(
    val action: String,
    val state: EmergencyState,
    val affected: List<String> = emptyList(),
    val detail: String,
)

@Serializable
data class ComponentHealth(
    val component: String,
    val status: String,
    val detail: String,
    val checkedAt: String,
)

@Serializable
data class Diagnostics(
    val overall: String,
    val generatedAt: String,
    val components: List<ComponentHealth> = emptyList(),
)

@Serializable
data class ClockView(
    val mode: String,
    val now: String,
    val synthetic: Boolean = false,
)

@Serializable
data class FxRate(
    val base: String,
    val quote: String,
    val rate: String,
    val asOf: String,
)

@Serializable
data class Settings(
    val timezone: String,
    val displayCurrency: String,
    val showCadEquivalent: Boolean,
    val theme: String,
    val notifications: JsonObject,
    val privacy: JsonObject,
    val portfolioDefaults: JsonObject,
    val updatedAt: String,
    val version: Long,
)

@Serializable
data class Provider(
    val id: String,
    val kind: String,
    val providerType: String,
    val displayName: String,
    val active: Boolean,
    val lastTestStatus: String? = null,
    val lastTestDetail: String? = null,
)

@Serializable
data class ResearchSession(
    val id: String,
    val title: String,
    val providerType: String,
    val model: String,
    val assetClass: String,
    val universe: List<String>,
    val timeframe: String,
    val status: String,
    val reviewStatus: String,
    val requestsUsed: Int,
    val maxRequests: Int,
    val costUsd: String,
    val maxCostUsd: String,
    val createdAt: String,
)

@Serializable
data class ResearchRun(
    val id: String,
    val purpose: String,
    val status: String,
    val label: String,
    val failureCode: String? = null,
    val failureDetail: String? = null,
    val responseText: String? = null,
    val estimatedCostUsd: String? = null,
    val sources: List<ResearchSource> = emptyList(),
    val startedAt: String,
)

@Serializable
data class ResearchSource(
    val url: String,
    val title: String? = null,
    val citedText: String? = null,
)

@Serializable
data class Compilation(
    val id: String,
    val status: String,
    val strategyId: String? = null,
    val contentHash: String? = null,
    val createdAt: String,
)

@Serializable
data class ResearchDetail(
    val session: ResearchSession,
    val runs: List<ResearchRun> = emptyList(),
    val compilations: List<Compilation> = emptyList(),
    val disclaimer: String,
)

@Serializable
data class PushConfig(
    val enabled: Boolean,
    val projectId: String? = null,
    val applicationId: String? = null,
    val apiKey: String? = null,
    val senderId: String? = null,
)

@Serializable
data class Device(
    val id: String,
    val name: String,
    val platform: String? = null,
    val pushEnabled: Boolean = false,
)

// ------------------------------------------------------------------ reports (FR-103, FR-104)

@Serializable
data class ReportCosts(
    val commissions: String = "0",
    val spread: String = "0",
    val slippage: String = "0",
    val borrow: String = "0",
    val dividends: String = "0",
    val total: String = "0",
)

@Serializable
data class ReportAttribution(
    val key: String,
    val label: String,
    val realizedPnl: String = "0",
    val unrealizedPnl: String? = null,
    val trades: Int = 0,
)

@Serializable
data class ReportBenchmark(
    val symbol: String,
    val returnPercent: String? = null,
    val note: String? = null,
)

@Serializable
data class PortfolioReport(
    val summary: PortfolioSummary,
    val maxDrawdownPercent: String = "0",
    val periodReturnPercent: String? = null,
    val costs: ReportCosts = ReportCosts(),
    val byAsset: List<ReportAttribution> = emptyList(),
    val byStrategy: List<ReportAttribution> = emptyList(),
    val benchmarks: List<ReportBenchmark> = emptyList(),
    val disclaimer: String,
)

@Serializable
data class OutcomeGroup(
    val group: String,
    val count: Int = 0,
    val realizedPnl: String = "0",
    val averagePnl: String? = null,
    val note: String = "",
)

@Serializable
data class OutcomeComparison(
    val bySource: List<OutcomeGroup> = emptyList(),
    val byRecommendationDecision: List<OutcomeGroup> = emptyList(),
    val disclaimer: String,
)

/** Portfolio report together with its descriptive outcome comparison. */
@Serializable
data class ReportView(
    val report: PortfolioReport,
    val outcomes: OutcomeComparison,
)
