package app.strategyforge.signals

import app.strategyforge.autonomy.Activation
import app.strategyforge.autonomy.ActivationRequest
import app.strategyforge.autonomy.ActivationService
import app.strategyforge.autonomy.AutonomyDisclosure
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.uuid
import app.strategyforge.common.db.uuidOrNull
import app.strategyforge.common.idempotency.IdempotencyService
import app.strategyforge.common.web.parseUuid
import app.strategyforge.strategy.StrategyStatus
import com.fasterxml.jackson.databind.ObjectMapper
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class DeactivateRequest(
    val reason: String? = null,
)

data class DisclosureView(
    val version: String,
    val text: String,
)

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

/** Activation lifecycle that keeps recommendations consistent with the strategy state. */
@Service
class StrategyActivationFacade(
    private val activations: ActivationService,
    private val recommendations: RecommendationService,
) {
    fun activate(
        strategyId: UUID,
        req: ActivationRequest,
    ): Activation = activations.activate(strategyId, req)

    @Transactional
    fun deactivate(
        strategyId: UUID,
        reason: String?,
    ): Activation? {
        val text = reason?.takeIf { it.isNotBlank() }?.take(300) ?: "Paused by owner"
        val current = activations.active(strategyId)
        activations.deactivate(strategyId, text, StrategyStatus.PAUSED)
        recommendations.closePendingForStrategy(strategyId, text)
        return current?.let { activations.get(it.id) }
    }
}

@RestController
@Tag(name = "Autonomy")
class ActivationController(
    private val facade: StrategyActivationFacade,
    private val activations: ActivationService,
    private val idempotency: IdempotencyService,
) {
    /** Recommendation Mode by default; Autonomous needs disclosure acceptance and recent authentication (FR-071). */
    @PostMapping("/v1/strategies/{id}/activate")
    fun activate(
        @PathVariable id: String,
        @RequestBody req: ActivationRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("strategy-activate:$id", key, req, HttpStatus.CREATED) { facade.activate(parseUuid(id), req) }

    @PostMapping("/v1/strategies/{id}/deactivate")
    fun deactivate(
        @PathVariable id: String,
        @RequestBody(required = false) req: DeactivateRequest?,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("strategy-deactivate:$id", key, req) { mapOf("activation" to facade.deactivate(parseUuid(id), req?.reason)) }

    @GetMapping("/v1/strategies/{id}/activations")
    fun history(
        @PathVariable id: String,
    ) = activations.history(parseUuid(id))

    @GetMapping("/v1/autonomy/disclosure")
    fun disclosure() = DisclosureView(AutonomyDisclosure.VERSION, AutonomyDisclosure.TEXT)
}

@RestController
@RequestMapping("/v1/recommendations")
@Tag(name = "Recommendations")
class RecommendationController(
    private val recommendations: RecommendationService,
    private val facade: StrategyActivationFacade,
    private val idempotency: IdempotencyService,
) {
    @GetMapping
    fun list(
        @RequestParam(required = false) status: String?,
        @RequestParam(required = false) strategyId: String?,
        @RequestParam(required = false) cursor: String?,
        @RequestParam(required = false) limit: Int?,
    ) = recommendations.list(status, strategyId?.let { parseUuid(it) }, cursor, limit)

    /** Detail includes a fresh single-use action token required to accept (FR-063). */
    @GetMapping("/{id}")
    fun get(
        @PathVariable id: String,
    ) = recommendations.detail(parseUuid(id))

    @PostMapping("/{id}/accept")
    fun accept(
        @PathVariable id: String,
        @RequestBody req: AcceptRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("recommendation-accept:$id", key, req) { recommendations.accept(parseUuid(id), req) }

    @PostMapping("/{id}/decline")
    fun decline(
        @PathVariable id: String,
        @RequestBody(required = false) req: DeclineRequest?,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("recommendation-decline:$id", key, req) { recommendations.decline(parseUuid(id), req?.reason) }

    @PostMapping("/{id}/snooze")
    fun snooze(
        @PathVariable id: String,
        @RequestBody req: SnoozeRequest,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("recommendation-snooze:$id", key, req) { recommendations.snooze(parseUuid(id), req.minutes) }

    @PostMapping("/{id}/pause-strategy")
    fun pauseStrategy(
        @PathVariable id: String,
        @RequestHeader(IdempotencyService.HEADER, required = false) key: String?,
    ) = idempotency.execute("recommendation-pause:$id", key, null) {
        recommendations.pauseStrategy(parseUuid(id)) { sid, reason -> facade.deactivate(sid, reason) }
    }
}

@RestController
@RequestMapping("/v1/signals")
@Tag(name = "Recommendations")
class SignalController(
    private val jdbc: JdbcClient,
    private val mapper: ObjectMapper,
) {
    @GetMapping
    fun list(
        @RequestParam strategyId: String,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<SignalView> =
        jdbc
            .sql("select * from signals where strategy_id = :s order by created_at desc, id limit :l")
            .param("s", parseUuid(strategyId))
            .param("l", limit.coerceIn(1, MAX_LIMIT))
            .query { rs, _ ->
                SignalView(
                    rs.uuid("id"),
                    rs.uuid("strategy_id"),
                    rs.uuid("version_id"),
                    rs.getString("content_hash"),
                    rs.uuid("activation_id"),
                    rs.uuid("instrument_id"),
                    rs.instant("bucket_start"),
                    rs.getString("action"),
                    rs.getString("side"),
                    rs.getBigDecimal("quantity"),
                    rs.getString("order_type"),
                    rs.getBigDecimal("limit_price"),
                    rs.getBigDecimal("reference_price"),
                    rs.uuid("market_snapshot_id"),
                    mapper.readValue(rs.getString("triggered_rules"), mapper.typeFactory.constructCollectionType(List::class.java, String::class.java)),
                    rs.getString("rationale"),
                    rs.instant("expires_at"),
                    rs.getString("disposition"),
                    rs.uuidOrNull("risk_evaluation_id"),
                    rs.instant("created_at"),
                )
            }.list()

    companion object {
        const val MAX_LIMIT = 200
    }
}
