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

/** One numbered slot of an asset class (D-051) and the strategy running in it, if any. */
data class SlotView(
    val assetClass: AssetClass,
    val number: Int,
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
 * Up to ten crypto and ten stock strategies run at a time, each in a numbered slot (D-035, D-051).
 * Activating a strategy into an occupied slot replaces its occupant in one transaction: the old
 * strategy is paused, its open positions are either handed to the new strategy (which then manages
 * their exits) or closed with market orders, and the new strategy is activated through the usual
 * gates. Two running strategies may not trade the same symbol in the same portfolio, so each
 * position always has one owner; give each slot its own portfolio for that. If any step fails,
 * nothing changes.
 */
class StrategySlots(
    private val db: Db,
    private val strategies: StrategyService,
    private val control: StrategyActivationFacade,
    private val activations: ActivationService,
    private val orders: OrderService,
    private val audit: AuditService,
) {
    /** Every slot of both asset classes, empty ones included. */
    fun slots(): List<SlotView> = ASSET_CLASSES.flatMap { slots(it) }

    fun slots(assetClass: AssetClass): List<SlotView> {
        val taken = occupied(assetClass)
        return (1..SLOTS).map { n ->
            val s = taken[n]?.let { strategies.get(it) }
            SlotView(assetClass, n, s, s?.let { activations.active(it.id) }, s?.let { holdings(it.id) }.orEmpty())
        }
    }

    /** Slot number to the strategy running in it. */
    private fun occupied(assetClass: AssetClass): Map<Int, UUID> =
        db
            .sql(
                """
                select s.id, a.slot from strategies s join strategy_activations a on a.strategy_id = s.id and a.status = 'ACTIVE'
                where s.asset_class = :ac order by a.created_at
                """.trimIndent(),
            ).param("ac", assetClass.name)
            .list { (it.string("slot")?.toIntOrNull() ?: 1) to it.uuid("id") }
            .toMap()

    /** The active strategy in slot [number] of an asset class, other than [except]. */
    fun occupant(
        assetClass: AssetClass,
        number: Int,
        except: UUID?,
    ): StrategyView? = occupied(assetClass)[number]?.takeIf { it != except }?.let { strategies.get(it) }

    /**
     * Activates [strategyId] in [slot] of its asset class, or in the slot it already runs in, or the
     * first free one. An occupied slot is replaced as the owner chose with [positions].
     */
    fun activate(
        strategyId: UUID,
        req: ActivationRequest,
        positions: PositionHandling?,
        slot: Int? = null,
    ): SwitchResult =
        db.tx {
            val target = strategies.get(strategyId)
            val assetClass = AssetClass.valueOf(target.assetClass)
            if (slot != null && slot !in 1..SLOTS) throw Problems.badRequest("invalid-slot", "Slot must be between 1 and $SLOTS")
            val taken = occupied(assetClass)
            val number =
                slot ?: activations.active(strategyId)?.slot ?: (1..SLOTS).firstOrNull { it !in taken.keys }
                    ?: throw Problems.conflict("slots-full", "All $SLOTS ${label(assetClass)} slots are in use. Choose a slot to replace.", mapOf("assetClass" to assetClass))
            val old = occupant(assetClass, number, except = strategyId)
            if (old != null && positions == null) {
                val open = holdings(old.id)
                throw Problems.conflict(
                    "slot-occupied",
                    "${old.name} is running in ${label(assetClass)} slot $number. Choose whether to keep or close its ${open.size} open position(s) before replacing it.",
                    mapOf("slot" to number, "currentStrategyId" to old.id, "currentStrategyName" to old.name, "openPositions" to open.map { mapOf("symbol" to it.symbol, "side" to it.side, "quantity" to it.quantity) }),
                )
            }
            sharedSymbols(target, req.portfolioId, setOfNotNull(strategyId, old?.id))?.let { (other, symbols) ->
                throw Problems.conflict(
                    "symbol-shared",
                    "${other.name} already trades ${symbols.joinToString()} in this portfolio. Two running strategies cannot trade the same symbol in one portfolio: choose another portfolio for this slot (you can create one for it).",
                    mapOf("otherStrategyId" to other.id, "otherStrategyName" to other.name, "symbols" to symbols),
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
                        // Only positions in the portfolio the new strategy trades can be handed to it.
                        val traded = targetSymbols(target)
                        handedOver = open.filter { it.symbol in traded && it.portfolioId == req.portfolioId }
                        leftOpen = open - handedOver.toSet()
                        handedOver.forEach { h -> handOver(old.id, strategyId, h) }
                    }
                    PositionHandling.CLOSE -> open.forEach { h -> closing += close(old, h) }
                }
            }
            val activation = control.activate(strategyId, req.copy(slot = number))
            if (old != null) {
                audit.record(
                    AuditCategory.AUTONOMY,
                    "STRATEGY_SLOT_SWITCHED",
                    entityType = "Strategy",
                    entityId = strategyId,
                    details =
                        mapOf(
                            "assetClass" to assetClass,
                            "slot" to number,
                            "replaced" to old.id,
                            "positions" to positions,
                            "handedOver" to handedOver.map { it.symbol },
                            "closingOrders" to closing,
                            "leftOpen" to leftOpen.map { it.symbol },
                            "activationId" to activation.id,
                        ),
                )
            }
            SwitchResult(slots(assetClass).first { it.number == number }, old, if (old != null) positions else null, handedOver, closing, leftOpen)
        }

    /** Another running strategy (not in [except]) trading any of [target]'s symbols in [portfolioId]. */
    private fun sharedSymbols(
        target: StrategyView,
        portfolioId: UUID,
        except: Set<UUID>,
    ): Pair<StrategyView, List<String>>? {
        val mine = targetSymbols(target)
        return activations
            .activeAll()
            .filter { it.portfolioId == portfolioId && it.strategyId !in except }
            .firstNotNullOfOrNull { a ->
                val shared = runCatching { strategies.definition(a.versionId).symbols }.getOrDefault(emptyList()).filter { it in mine }
                if (shared.isEmpty()) null else strategies.get(a.strategyId) to shared
            }
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

    companion object {
        /** Slots per asset class (D-051). */
        const val SLOTS = 10
        val ASSET_CLASSES = listOf(AssetClass.CRYPTO, AssetClass.US_EQUITY)
    }
}
