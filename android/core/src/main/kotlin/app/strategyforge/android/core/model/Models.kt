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
    val lastAuthenticatedAt: String? = null,
    val expiresAt: String? = null,
    val revokedAt: String? = null,
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
    /** Set when this activation replaced another strategy in its slot (D-035). */
    val replacedStrategyName: String? = null,
    val positions: String? = null,
    val handedOver: List<SlotHolding> = emptyList(),
    val leftOpen: List<SlotHolding> = emptyList(),
    val closingOrders: List<String> = emptyList(),
)

/** An open position a strategy manages. */
@Serializable
data class SlotHolding(
    val portfolioId: String? = null,
    val symbol: String,
    val side: String,
    val quantity: String,
)

/** The crypto or stock strategy running now (D-035); [strategy] is null when the slot is empty. */
@Serializable
data class Slot(
    val assetClass: String,
    val strategy: Strategy? = null,
    val activation: Activation? = null,
    val holdings: List<SlotHolding> = emptyList(),
)

/** One point of a time series; [value] is exact decimal text (D-038). */
@Serializable
data class ChartPoint(
    val at: String,
    val value: String,
)

@Serializable
data class EquityChart(
    val portfolioId: String,
    val range: String,
    val points: List<ChartPoint> = emptyList(),
    val change: String? = null,
    val changePercent: String? = null,
)

/** One candle: open time and open/high/low/close/volume as exact decimal text. */
@Serializable
data class Candle(
    val t: String,
    val o: String,
    val h: String,
    val l: String,
    val c: String,
    val v: String = "0",
)

@Serializable
data class TradeMarker(
    val at: String,
    val side: String,
    val price: String,
    val quantity: String,
    val strategyId: String? = null,
)

@Serializable
data class CandleChart(
    val symbol: String,
    val timeframe: String,
    val status: String = "VERIFIED",
    val detail: String = "",
    val bars: List<Candle> = emptyList(),
    val trades: List<TradeMarker> = emptyList(),
)

/** Simulated trading results of one strategy (D-037). Money and percentages are exact decimal text. */
@Serializable
data class LiveStats(
    val closedTrades: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    val winRatePercent: String? = null,
    val realizedPnl: String = "0",
    val averagePnl: String? = null,
    val bestTrade: String? = null,
    val worstTrade: String? = null,
    val maxDrawdown: String = "0",
    val openPositions: Int = 0,
    val activeDays: String = "0",
    val firstTradeAt: String? = null,
    val lastTradeAt: String? = null,
    val pnlSeries: List<ChartPoint> = emptyList(),
)

@Serializable
data class BacktestStats(
    val backtestId: String,
    val netReturnPercent: String? = null,
    val maxDrawdownPercent: String? = null,
    val trades: Int? = null,
    val winRatePercent: String? = null,
    val profitFactor: String? = null,
    val completedAt: String? = null,
)

/** How a saved strategy did in paper trading and in its latest backtest (D-037). */
@Serializable
data class Scorecard(
    val strategy: Strategy,
    val live: LiveStats = LiveStats(),
    val backtest: BacktestStats? = null,
    val sampleWarning: String? = null,
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
    val settings: Map<String, String> = emptyMap(),
    val credentialConfigured: Boolean = false,
    val credentialFingerprint: String? = null,
)

/** An AI provider the phone can call, with the presets that pre-fill the setup form (D-030). */
@Serializable
data class ProviderType(
    val providerType: String,
    val label: String,
    val presets: Map<String, String> = emptyMap(),
    val keyUrl: String? = null,
    val settings: List<ProviderSetting> = emptyList(),
)

@Serializable
data class ProviderSetting(
    val name: String,
    val type: String,
    val required: Boolean = false,
)

/** What the on-device engine is running: demo (replay) or live data, and the demo speed. */
@Serializable
data class RuntimeState(
    val marketMode: String,
    val stocksConfigured: Boolean = false,
    val demoStepMinutes: Int? = null,
    val marketTime: String? = null,
    val tickSeconds: Int = 5,
)

/** US stock data (Twelve Data, D-032): whether the owner's key is stored, and the last test. */
@Serializable
data class StockData(
    val provider: String = "TWELVE_DATA",
    val configured: Boolean = false,
    val fingerprint: String? = null,
    val keyUrl: String? = null,
    val lastTestStatus: String? = null,
    val lastTestDetail: String? = null,
)

@Serializable
data class RestoreResult(
    val restoredFrom: String,
    val safetyBackup: String,
    val tables: Int = 0,
    val rows: Long = 0,
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
    /** Research as a conversation (D-034): no fixed timeframe or symbols; the AI proposes them. */
    val conversation: Boolean = false,
    /** The first message (or, for older form-based research, the research question). */
    val prompt: String = "",
    /** Research pasted in from elsewhere (D-041). */
    val imported: Boolean = false,
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
    /** The owner's message this turn answers (conversations). */
    val ownerMessage: String? = null,
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
    /** Why a compilation was not accepted (validation issues or the model's reason). */
    val issues: JsonElement? = null,
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
    val pushTokenRegistered: Boolean = false,
    val createdAt: String? = null,
    val lastSeenAt: String? = null,
    val revokedAt: String? = null,
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

// ------------------------------------------------------------------ account security (FR-002)

@Serializable
data class TotpSetup(
    val secret: String,
    val otpauthUri: String,
)

@Serializable
data class RecoveryCodes(
    val recoveryCodes: List<String>,
    val notice: String = "",
)

@Serializable
data class CountResult(
    val count: Int = 0,
)

// ------------------------------------------------------------------ AI budget (FR-037)

@Serializable
data class AiBudget(
    val monthlyCostLimitUsd: String,
    val dailyRequestLimit: Int,
    val maxOutputTokens: Int,
    val maxInputChars: Int,
    val monthCostUsd: String = "0",
    val todayRequests: Int = 0,
    val updatedAt: String? = null,
    val version: Long = 0,
)

// ------------------------------------------------------------------ backups (FR-112)

@Serializable
data class BackupFile(
    val name: String,
    val sizeBytes: Long = 0,
    val modifiedAt: String? = null,
)

@Serializable
data class BackupResult(
    val file: BackupFile,
    val sha256: String,
    val schemaVersion: String,
    val tables: Int = 0,
    val rows: Long = 0,
)

@Serializable
data class BackupVerification(
    val name: String,
    val valid: Boolean,
    val schemaVersion: String? = null,
    val createdAt: String? = null,
    val tables: Int = 0,
    val rows: Long = 0,
    val error: String? = null,
)
