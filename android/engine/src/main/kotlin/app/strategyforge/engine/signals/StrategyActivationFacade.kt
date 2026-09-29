package app.strategyforge.engine.signals

import app.strategyforge.engine.autonomy.Activation
import app.strategyforge.engine.autonomy.ActivationRequest
import app.strategyforge.engine.autonomy.ActivationService
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.strategy.StrategyStatus
import java.util.UUID

/** Activation lifecycle that keeps recommendations consistent with the strategy state. */
class StrategyActivationFacade(
    private val db: Db,
    private val activations: ActivationService,
    private val recommendations: () -> RecommendationService,
) {
    fun activate(
        strategyId: UUID,
        req: ActivationRequest,
    ): Activation = activations.activate(strategyId, req)

    fun deactivate(
        strategyId: UUID,
        reason: String?,
    ): Activation? =
        db.tx {
            val text = reason?.takeIf { it.isNotBlank() }?.take(300) ?: "Paused by owner"
            val current = activations.active(strategyId)
            activations.deactivate(strategyId, text, StrategyStatus.PAUSED)
            recommendations().closePendingForStrategy(strategyId, text)
            current?.let { activations.get(it.id) }
        }
}
