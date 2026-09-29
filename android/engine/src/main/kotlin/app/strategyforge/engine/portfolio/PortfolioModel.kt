@file:UseSerializers(BigDecimalSerializer::class)

package app.strategyforge.engine.portfolio

import app.strategyforge.engine.common.BigDecimalSerializer
import app.strategyforge.engine.market.DataStatus
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Execution cost and short-selling assumptions (section 3 fees/slippage, shorting). */
@Serializable
data class CostModel(
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
    val executionMaxQuoteAgeSeconds: Long = 60,
    val reservationBufferPercent: BigDecimal = BigDecimal("2"),
)

data class Portfolio(
    val id: UUID,
    val name: String,
    val accountType: String,
    val status: String,
    val baseCurrency: String,
    val startingBalance: BigDecimal,
    val costModel: CostModel,
    val shortingEnabled: Boolean,
    val clonedFrom: UUID?,
    val resetFrom: UUID?,
    val reconciliationStatus: String,
    val createdAt: Instant,
    val archivedAt: Instant?,
    val version: Long,
)

data class PositionView(
    val instrumentId: UUID,
    val symbol: String,
    val assetClass: String,
    val side: String,
    val quantity: BigDecimal,
    val costBasis: BigDecimal,
    val averageCost: BigDecimal,
    val marketPrice: BigDecimal?,
    val marketValue: BigDecimal?,
    val unrealizedPnl: BigDecimal?,
    val priceTimestamp: Instant?,
    val priceStatus: DataStatus,
    val manualReviewRequired: Boolean,
)

data class PortfolioSummary(
    val portfolio: Portfolio,
    val cash: BigDecimal,
    val reservedCash: BigDecimal,
    val buyingPower: BigDecimal,
    val shortRequirement: BigDecimal,
    val costBasis: BigDecimal,
    val marketValue: BigDecimal,
    val equity: BigDecimal,
    val realizedPnl: BigDecimal,
    val unrealizedPnl: BigDecimal,
    val fees: BigDecimal,
    val borrowFees: BigDecimal,
    val dividends: BigDecimal,
    val totalReturn: BigDecimal,
    val totalReturnPercent: BigDecimal?,
    val fullyPriced: Boolean,
    val positions: List<PositionView>,
    val asOf: Instant,
)
