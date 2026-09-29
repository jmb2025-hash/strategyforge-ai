package app.strategyforge.engine.autonomy

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.execution.OrderLinks
import app.strategyforge.engine.execution.OrderRequest
import app.strategyforge.engine.execution.OrderService
import app.strategyforge.engine.execution.OrderSide
import app.strategyforge.engine.execution.OrderType
import app.strategyforge.engine.execution.TimeInForce
import app.strategyforge.engine.market.AssetClass
import app.strategyforge.engine.risk.OrderSource
import app.strategyforge.engine.signals.StrategyActivationFacade
import app.strategyforge.engine.strategy.StrategyService
import app.strategyforge.engine.strategy.StrategyView
import java.math.BigDecimal
import java.util.UUID

/** What to do with the open positions of the strategy being replaced (asked each time, D-035). */
enum class PositionHandling { KEEP, CLOSE }

/** An open position a strategy manages, summed per symbol and side. */
data class SlotHolding(
    val portfolioId: UUID,
    val instrumentId: UUID,
    val symbol: String,
    val side: String,
    val quantity: BigDecimal,
)

/** One of the two strategy slots: the crypto strategy and the stock strategy running now. */
data class SlotView(
    val assetClass: AssetClass,
    val strategy: StrategyView?,
    val activation: Activation?,
    val holdings: List<SlotHolding>,
)

data class SwitchResult(
    val slot: SlotView,
    val replaced: StrategyView?,
    val positions: PositionHandling?,
    /** Positions the new strategy now manages (KEEP). */
    val handedOver: List<SlotHolding>,
    /** Market orders placed to close the replaced strategy's positions (CLOSE). */
    val closingOrders: List<UUID>,
    /** Positions left open and unmanaged: KEEP in symbols the new strategy does not trade. */
    val leftOpen: List<SlotHolding>,
)

/**
 * One crypto strategy and one stock strategy run at a time (D-035). Activating a strategy while
 * another of the same asset class is active replaces it in one transaction: the old strategy is
 * paused, its open positions are either handed to the new strategy (which then manages their exits)
 * or closed with market orders, and the new strategy is activated through the usual gates. If any
 * step fails, nothing changes.
 */
class StrategySlots(
    private val db: Db,
    private val strategies: StrategyService,
    private val control: StrategyActivationFacade,
    private val activations: ActivationService,
    private val orders: OrderService,
    private val audit: AuditService,
) {
    fun slots(): List<SlotView> = listOf(AssetClass.CRYPTO, AssetClass.US_EQUITY).map { slot(it) }

    fun slot(assetClass: AssetClass): SlotView {
        val s = occupant(assetClass, except = null)
        return SlotView(assetClass, s, s?.let { activations.active(it.id) }, s?.let { holdings(it.id) }.orEmpty())
    }

    /** The active strategy in a slot, other than [except]. */
    fun occupant(
        assetClass: AssetClass,
        except: UUID?,
    ): StrategyView? =
        db
            .sql(
                """
                select s.id from strategies s join strategy_activations a on a.strategy_id = s.id and a.status = 'ACTIVE'
                where s.asset_class = :ac and (:x is null or s.id <> :x) order by a.created_at desc limit 1
                """.trimIndent(),
            ).param("ac", assetClass.name)
            .param("x", except)
            .firstOrNull { it.uuid("id") }
            ?.let { strategies.get(it) }

    /** Activates [strategyId] in its asset class's slot, replacing the current occupant as the owner chose. */
    fun activate(
        strategyId: UUID,
        req: ActivationRequest,
        positions: PositionHandling?,
    ): SwitchResult =
        db.tx {
            val target = strategies.get(strategyId)
            val assetClass = AssetClass.valueOf(target.assetClass)
            val old = occupant(assetClass, except = strategyId)
            if (old != null && positions == null) {
                val open = holdings(old.id)
                throw Problems.conflict(
                    "slot-occupied",
                    "${old.name} is the active ${label(assetClass)} strategy. Choose whether to keep or close its ${open.size} open position(s) before replacing it.",
                    mapOf("currentStrategyId" to old.id, "currentStrategyName" to old.name, "openPositions" to open.map { mapOf("symbol" to it.symbol, "side" to it.side, "quantity" to it.quantity) }),
                )
            }
            var handedOver = emptyList<SlotHolding>()
            var leftOpen = emptyList<SlotHolding>()
            val closing = mutableListOf<UUID>()
            if (old != null) {
                control.deactivate(old.id, "Replaced by ${target.name}")
                val open = holdings(old.id)
                when (positions!!) {
                    PositionHandling.KEEP -> {
                        val traded = targetSymbols(target)
                        handedOver = open.filter { it.symbol in traded }
                        leftOpen = open.filterNot { it.symbol in traded }
                        handedOver.forEach { h -> handOver(old.id, strategyId, h) }
                    }
                    PositionHandling.CLOSE -> open.forEach { h -> closing += close(old, h) }
                }
            }
            val activation = control.activate(strategyId, req)
            if (old != null) {
                audit.record(
                    AuditCategory.AUTONOMY,
                    "STRATEGY_SLOT_SWITCHED",
                    entityType = "Strategy",
                    entityId = strategyId,
                    details =
                        mapOf(
                            "assetClass" to assetClass,
                            "replaced" to old.id,
                            "positions" to positions,
                            "handedOver" to handedOver.map { it.symbol },
                            "closingOrders" to closing,
                            "leftOpen" to leftOpen.map { it.symbol },
                            "activationId" to activation.id,
                        ),
                )
            }
            SwitchResult(slot(assetClass), old, if (old != null) positions else null, handedOver, closing, leftOpen)
        }

    /** Open lots the strategy manages: opened by its orders, or handed to it (D-035). */
    fun holdings(strategyId: UUID): List<SlotHolding> =
        db
            .sql(
                """
                select l.portfolio_id, l.instrument_id, i.symbol, l.side, l.quantity_remaining from position_lots l
                join paper_executions e on e.id = l.open_execution_id join paper_orders o on o.id = e.order_id
                join instruments i on i.id = l.instrument_id
                where l.closed_at is null and coalesce(l.managed_by_strategy_id, o.strategy_id) = :s
                """.trimIndent(),
            ).param("s", strategyId)
            .list { rs -> SlotHolding(rs.uuid("portfolio_id"), rs.uuid("instrument_id"), rs.str("symbol"), rs.str("side"), rs.dec("quantity_remaining")) }
            // Summed in Kotlin: quantities are exact decimal text.
            .groupBy { Triple(it.portfolioId, it.instrumentId, it.side) }
            .map { (_, lots) -> lots.first().copy(quantity = lots.fold(BigDecimal.ZERO) { a, l -> a.add(l.quantity) }) }
            .filter { it.quantity.signum() != 0 }
            .sortedBy { it.symbol }

    private fun targetSymbols(target: StrategyView): Set<String> = target.currentVersionId?.let { strategies.definition(it).symbols.toSet() }.orEmpty()

    private fun handOver(
        from: UUID,
        to: UUID,
        h: SlotHolding,
    ) {
        val lots =
            db
                .sql(
                    """
                    select l.id from position_lots l join paper_executions e on e.id = l.open_execution_id join paper_orders o on o.id = e.order_id
                    where l.closed_at is null and l.portfolio_id = :p and l.instrument_id = :i and l.side = :side
                      and coalesce(l.managed_by_strategy_id, o.strategy_id) = :from
                    """.trimIndent(),
                ).param("p", h.portfolioId)
                .param("i", h.instrumentId)
                .param("side", h.side)
                .param("from", from)
                .list { it.uuid("id") }
        lots.forEach { id ->
            db
                .sql("update position_lots set managed_by_strategy_id = :to where id = :id")
                .param("to", to)
                .param("id", id)
                .update()
        }
    }

    /** A market order that closes the position; it stays linked to the replaced strategy's history. */
    private fun close(
        old: StrategyView,
        h: SlotHolding,
    ): UUID {
        val side = if (h.side == "LONG") OrderSide.SELL else OrderSide.BUY_TO_COVER
        val r = orders.create(OrderRequest(h.portfolioId, h.symbol, side, OrderType.MARKET, h.quantity, timeInForce = TimeInForce.GTC), OrderSource.SYSTEM, OrderLinks(strategyId = old.id))
        return r.order.id
    }

    private fun label(assetClass: AssetClass) = if (assetClass == AssetClass.CRYPTO) "crypto" else "stock"
}
