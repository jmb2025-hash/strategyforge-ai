package app.strategyforge.engine.portfolio

import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.money.Decimals
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

enum class LotSide { LONG, SHORT }

data class Lot(
    val id: UUID,
    val portfolioId: UUID,
    val instrumentId: UUID,
    val side: LotSide,
    val openedAt: Instant,
    val quantityOpen: BigDecimal,
    val quantityRemaining: BigDecimal,
    val costRemaining: BigDecimal,
    val closedAt: Instant?,
)

/** Relief result: signed cost removed from lots (same sign convention as the POSITION account). */
data class Relief(
    val quantity: BigDecimal,
    val cost: BigDecimal,
    val lots: List<Pair<UUID, BigDecimal>>,
)

/**
 * FIFO tax lots. Lots are a projection reconciled against the ledger: the sum of remaining lot
 * quantity and cost must equal the ledger POSITION account for every instrument. A lot is open
 * exactly while closed_at is null (decimal text is never compared in SQL).
 */
class LotService(
    private val db: Db,
) {
    fun open(
        portfolioId: UUID,
        instrumentId: UUID,
        side: LotSide,
        quantity: BigDecimal,
        signedCost: BigDecimal,
        at: Instant,
        executionId: UUID?,
    ): UUID {
        val id = UUID.randomUUID()
        db
            .sql(
                """
                insert into position_lots(id, portfolio_id, instrument_id, side, opened_at, open_execution_id, quantity_open, quantity_remaining, cost_remaining)
                values (:id, :p, :i, :s, :at, :e, :q, :q, :c)
                """.trimIndent(),
            ).param("id", id)
            .param("p", portfolioId)
            .param("i", instrumentId)
            .param("s", side)
            .param("at", at)
            .param("e", executionId)
            .param("q", Decimals.quantity(quantity))
            .param("c", Decimals.money(signedCost))
            .update()
        return id
    }

    /**
     * Removes [quantity] from the oldest lots first; the final slice of a lot takes its exact remaining
     * cost. Lots in [first] (a plan setup's own lots, D-045) are relieved before any others.
     */
    fun relieveFifo(
        portfolioId: UUID,
        instrumentId: UUID,
        side: LotSide,
        quantity: BigDecimal,
        at: Instant,
        first: Set<UUID> = emptySet(),
    ): Relief =
        db.tx {
            var remaining = quantity
            var cost = BigDecimal.ZERO
            val touched = mutableListOf<Pair<UUID, BigDecimal>>()
            for (lot in openLots(portfolioId, instrumentId).filter { it.side == side }.sortedBy { if (it.id in first) 0 else 1 }) {
                if (remaining.signum() <= 0) break
                val take = remaining.min(lot.quantityRemaining)
                val portion =
                    if (take.compareTo(lot.quantityRemaining) == 0) {
                        lot.costRemaining
                    } else {
                        lot.costRemaining.multiply(take).divide(lot.quantityRemaining, Decimals.MONEY_SCALE, RoundingMode.HALF_EVEN)
                    }
                val newQty = lot.quantityRemaining.subtract(take)
                db
                    .sql("update position_lots set quantity_remaining = :q, cost_remaining = :c, closed_at = :closed where id = :id")
                    .param("q", Decimals.quantity(newQty))
                    .param("c", Decimals.money(lot.costRemaining.subtract(portion)))
                    .param("closed", if (newQty.signum() == 0) at else null)
                    .param("id", lot.id)
                    .update()
                cost = cost.add(portion)
                remaining = remaining.subtract(take)
                touched += lot.id to take
            }
            check(remaining.signum() == 0) { "Insufficient $side lots to relieve $quantity (short by $remaining)" }
            Relief(quantity, cost, touched)
        }

    /** Applies a split ratio to open lots; cost basis is unchanged. Returns the signed quantity delta. */
    fun applySplit(
        portfolioId: UUID,
        instrumentId: UUID,
        ratioNew: BigDecimal,
        ratioOld: BigDecimal,
    ): BigDecimal =
        db.tx {
            var delta = BigDecimal.ZERO
            openLots(portfolioId, instrumentId).forEach { lot ->
                val newQty = Decimals.quantity(lot.quantityRemaining.multiply(ratioNew).divide(ratioOld, Decimals.MC))
                val newOpen = Decimals.quantity(lot.quantityOpen.multiply(ratioNew).divide(ratioOld, Decimals.MC))
                db
                    .sql("update position_lots set quantity_remaining = :q, quantity_open = :o where id = :id")
                    .param("q", newQty)
                    .param("o", newOpen)
                    .param("id", lot.id)
                    .update()
                val d = newQty.subtract(lot.quantityRemaining)
                delta = delta.add(if (lot.side == LotSide.LONG) d else d.negate())
            }
            delta
        }

    fun openLots(
        portfolioId: UUID,
        instrumentId: UUID? = null,
    ): List<Lot> =
        db
            .sql("select * from position_lots where portfolio_id = :p and (:i is null or instrument_id = :i) and closed_at is null order by opened_at, id")
            .param("p", portfolioId)
            .param("i", instrumentId)
            .list(::map)

    fun closedLots(
        portfolioId: UUID,
        limit: Int,
    ): List<Lot> =
        db
            .sql("select * from position_lots where portfolio_id = :p and closed_at is not null order by closed_at desc limit :l")
            .param("p", portfolioId)
            .param("l", limit)
            .list(::map)

    /** Signed totals per instrument (short quantities negative) for reconciliation. */
    fun totals(portfolioId: UUID): Map<UUID, Pair<BigDecimal, BigDecimal>> =
        openLots(portfolioId)
            .groupBy { it.instrumentId }
            .mapValues { (_, lots) ->
                lots.fold(BigDecimal.ZERO) { a, l -> a.add(if (l.side == LotSide.LONG) l.quantityRemaining else l.quantityRemaining.negate()) } to
                    lots.fold(BigDecimal.ZERO) { a, l -> a.add(l.costRemaining) }
            }

    private fun map(r: app.strategyforge.engine.db.Row) =
        Lot(
            r.uuid("id"),
            r.uuid("portfolio_id"),
            r.uuid("instrument_id"),
            LotSide.valueOf(r.str("side")),
            r.instant("opened_at"),
            r.dec("quantity_open"),
            r.dec("quantity_remaining"),
            r.dec("cost_remaining"),
            r.instantOrNull("closed_at"),
        )
}
