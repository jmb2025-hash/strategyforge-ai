package app.strategyforge.portfolio

import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.money.Decimals
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
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
 * FIFO tax lots. Lots are a projection reconciled against the ledger: the sum of remaining
 * lot quantity and cost must equal the ledger POSITION account for every instrument.
 */
@Service
class LotService(
    private val jdbc: JdbcClient,
) {
    @Transactional(propagation = Propagation.MANDATORY)
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
        jdbc
            .sql(
                """
                insert into position_lots(id, portfolio_id, instrument_id, side, opened_at, open_execution_id, quantity_open, quantity_remaining, cost_remaining)
                values (:id, :p, :i, :s, :at, :e, :q, :q, :c)
                """.trimIndent(),
            ).param("id", id)
            .param("p", portfolioId)
            .param("i", instrumentId)
            .param("s", side.name)
            .param("at", ts(at))
            .param("e", executionId)
            .param("q", Decimals.quantity(quantity))
            .param("c", Decimals.money(signedCost))
            .update()
        return id
    }

    /** Removes [quantity] from the oldest lots first; the final slice of a lot takes its exact remaining cost. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun relieveFifo(
        portfolioId: UUID,
        instrumentId: UUID,
        side: LotSide,
        quantity: BigDecimal,
        at: Instant,
    ): Relief {
        var remaining = quantity
        var cost = BigDecimal.ZERO
        val touched = mutableListOf<Pair<UUID, BigDecimal>>()
        val lots =
            jdbc
                .sql(
                    "select * from position_lots where portfolio_id = :p and instrument_id = :i and side = :s and quantity_remaining > 0 order by opened_at, id for update",
                ).param("p", portfolioId)
                .param("i", instrumentId)
                .param("s", side.name)
                .query { rs, _ -> map(rs) }
                .list()
        for (lot in lots) {
            if (remaining.signum() <= 0) break
            val take = remaining.min(lot.quantityRemaining)
            val portion =
                if (take.compareTo(lot.quantityRemaining) == 0) {
                    lot.costRemaining
                } else {
                    lot.costRemaining.multiply(take).divide(lot.quantityRemaining, Decimals.MONEY_SCALE, RoundingMode.HALF_EVEN)
                }
            val newQty = lot.quantityRemaining.subtract(take)
            jdbc
                .sql("update position_lots set quantity_remaining = :q, cost_remaining = :c, closed_at = :closed where id = :id")
                .param("q", Decimals.quantity(newQty))
                .param("c", Decimals.money(lot.costRemaining.subtract(portion)))
                .param("closed", if (newQty.signum() == 0) ts(at) else null)
                .param("id", lot.id)
                .update()
            cost = cost.add(portion)
            remaining = remaining.subtract(take)
            touched += lot.id to take
        }
        check(remaining.signum() == 0) { "Insufficient $side lots to relieve $quantity (short by $remaining)" }
        return Relief(quantity, cost, touched)
    }

    /** Applies a split ratio to open lots; cost basis is unchanged. Returns the signed quantity delta. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun applySplit(
        portfolioId: UUID,
        instrumentId: UUID,
        ratioNew: BigDecimal,
        ratioOld: BigDecimal,
    ): BigDecimal {
        var delta = BigDecimal.ZERO
        val lots =
            jdbc
                .sql("select * from position_lots where portfolio_id = :p and instrument_id = :i and quantity_remaining > 0 for update")
                .param("p", portfolioId)
                .param("i", instrumentId)
                .query { rs, _ -> map(rs) }
                .list()
        lots.forEach { lot ->
            val newQty = Decimals.quantity(lot.quantityRemaining.multiply(ratioNew).divide(ratioOld, Decimals.MC))
            val newOpen = Decimals.quantity(lot.quantityOpen.multiply(ratioNew).divide(ratioOld, Decimals.MC))
            jdbc
                .sql("update position_lots set quantity_remaining = :q, quantity_open = :o where id = :id")
                .param("q", newQty)
                .param("o", newOpen)
                .param("id", lot.id)
                .update()
            val d = newQty.subtract(lot.quantityRemaining)
            delta = delta.add(if (lot.side == LotSide.LONG) d else d.negate())
        }
        return delta
    }

    fun openLots(
        portfolioId: UUID,
        instrumentId: UUID? = null,
    ): List<Lot> =
        jdbc
            .sql("select * from position_lots where portfolio_id = :p and (cast(:i as uuid) is null or instrument_id = :i) and quantity_remaining > 0 order by opened_at")
            .param("p", portfolioId)
            .param("i", instrumentId)
            .query { rs, _ -> map(rs) }
            .list()

    fun closedLots(
        portfolioId: UUID,
        limit: Int,
    ): List<Lot> =
        jdbc
            .sql("select * from position_lots where portfolio_id = :p and quantity_remaining = 0 order by closed_at desc limit :l")
            .param("p", portfolioId)
            .param("l", limit)
            .query { rs, _ -> map(rs) }
            .list()

    /** Signed totals per instrument (short quantities negative) for reconciliation. */
    fun totals(portfolioId: UUID): Map<UUID, Pair<BigDecimal, BigDecimal>> =
        jdbc
            .sql(
                """
                select instrument_id, sum(case when side = 'LONG' then quantity_remaining else -quantity_remaining end) as q, sum(cost_remaining) as c
                from position_lots where portfolio_id = :p and quantity_remaining > 0 group by instrument_id
                """.trimIndent(),
            ).param("p", portfolioId)
            .query { rs, _ -> rs.uuid("instrument_id") to (rs.getBigDecimal("q") to rs.getBigDecimal("c")) }
            .list()
            .toMap()

    private fun map(rs: java.sql.ResultSet) =
        Lot(
            rs.uuid("id"),
            rs.uuid("portfolio_id"),
            rs.uuid("instrument_id"),
            LotSide.valueOf(rs.getString("side")),
            rs.instant("opened_at"),
            rs.getBigDecimal("quantity_open"),
            rs.getBigDecimal("quantity_remaining"),
            rs.getBigDecimal("cost_remaining"),
            rs.instantOrNull("closed_at"),
        )
}
