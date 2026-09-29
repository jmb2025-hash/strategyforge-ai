package app.strategyforge.engine.signals

import app.strategyforge.engine.common.JacksonCanonical
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.decOrNull
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.db.uuidOrNull
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class SignalView(
    val id: UUID,
    val strategyId: UUID,
    val versionId: UUID,
    val contentHash: String,
    val activationId: UUID,
    val instrumentId: UUID,
    val bucketStart: Instant,
    val action: String,
    val side: String,
    val quantity: BigDecimal,
    val orderType: String,
    val limitPrice: BigDecimal?,
    val referencePrice: BigDecimal,
    val marketSnapshotId: UUID,
    val triggeredRules: List<String>,
    val rationale: String,
    val expiresAt: Instant,
    val disposition: String,
    val riskEvaluationId: UUID?,
    val createdAt: Instant,
)

/** Recorded signals with their full provenance (FR-061). */
class SignalQueries(
    private val db: Db,
) {
    fun list(
        strategyId: UUID,
        limit: Int = 50,
    ): List<SignalView> =
        db
            .sql("select * from signals where strategy_id = :s order by created_at desc, id limit :l")
            .param("s", strategyId)
            .param("l", limit.coerceIn(1, MAX_LIMIT))
            .list { rs ->
                SignalView(
                    rs.uuid("id"),
                    rs.uuid("strategy_id"),
                    rs.uuid("version_id"),
                    rs.str("content_hash"),
                    rs.uuid("activation_id"),
                    rs.uuid("instrument_id"),
                    rs.instant("bucket_start"),
                    rs.str("action"),
                    rs.str("side"),
                    rs.dec("quantity"),
                    rs.str("order_type"),
                    rs.decOrNull("limit_price"),
                    rs.dec("reference_price"),
                    rs.uuid("market_snapshot_id"),
                    JacksonCanonical.mapper.readTree(rs.str("triggered_rules")).map { it.asText() },
                    rs.str("rationale"),
                    rs.instant("expires_at"),
                    rs.str("disposition"),
                    rs.uuidOrNull("risk_evaluation_id"),
                    rs.instant("created_at"),
                )
            }

    companion object {
        const val MAX_LIMIT = 200
    }
}
