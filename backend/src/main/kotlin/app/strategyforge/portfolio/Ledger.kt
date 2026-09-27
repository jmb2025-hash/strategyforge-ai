package app.strategyforge.portfolio

import app.strategyforge.common.db.instant
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.db.uuidOrNull
import app.strategyforge.common.money.Decimals
import app.strategyforge.common.web.CorrelationIdFilter
import app.strategyforge.common.web.Cursor
import app.strategyforge.common.web.PageResponse
import app.strategyforge.common.web.Paging
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Chart of accounts. Debits are positive, credits negative; every journal sums to zero. */
enum class Account { CASH, CASH_RESERVED, POSITION, OWNER_CAPITAL, REALIZED_PNL, FEES, BORROW_FEES, DIVIDENDS }

enum class JournalType { FUNDING, RESERVATION, RESERVATION_RELEASE, EXECUTION, FEE, BORROW_FEE, DIVIDEND, SPLIT, ADJUSTMENT }

data class Posting(
    val account: Account,
    val amount: BigDecimal,
    val instrumentId: UUID? = null,
    val quantity: BigDecimal = BigDecimal.ZERO,
)

data class JournalView(
    val id: UUID,
    val seq: Long,
    val type: String,
    val referenceType: String?,
    val referenceId: String?,
    val occurredAt: Instant,
    val recordedAt: Instant,
    val description: String,
    val entries: List<EntryView>,
)

data class EntryView(
    val account: String,
    val instrumentId: UUID?,
    val symbol: String?,
    val amount: BigDecimal,
    val quantity: BigDecimal,
)

data class LedgerPosition(
    val instrumentId: UUID,
    val quantity: BigDecimal,
    val cost: BigDecimal,
)

class UnbalancedJournalException(
    message: String,
) : IllegalStateException(message)

/**
 * Append-only double-entry ledger (FR-011, FR-012). Balances and positions are always
 * computed from entries, never from mutable totals. The database rejects updates, deletes
 * and unbalanced journals; this service validates before writing as well.
 */
@Service
class LedgerService(
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {
    @Transactional(propagation = Propagation.MANDATORY)
    fun post(
        portfolioId: UUID,
        type: JournalType,
        occurredAt: Instant,
        description: String,
        postings: List<Posting>,
        referenceType: String? = null,
        referenceId: Any? = null,
    ): UUID {
        val nonZero = postings.filter { it.amount.signum() != 0 || it.quantity.signum() != 0 }
        require(nonZero.isNotEmpty()) { "Journal must contain at least one posting" }
        val sum = nonZero.fold(BigDecimal.ZERO) { a, p -> a.add(p.amount) }
        if (sum.signum() != 0) throw UnbalancedJournalException("Journal '$description' is unbalanced by $sum")
        nonZero.forEach { p ->
            require((p.account == Account.POSITION) == (p.instrumentId != null)) { "Only POSITION postings carry an instrument" }
            require(p.account == Account.POSITION || p.quantity.signum() == 0) { "Only POSITION postings carry quantity" }
        }
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                insert into ledger_journals(id, portfolio_id, journal_type, reference_type, reference_id, occurred_at, recorded_at, description, correlation_id)
                values (:id, :p, :t, :rt, :rid, :occ, :rec, :d, :c)
                """.trimIndent(),
            ).param("id", id)
            .param("p", portfolioId)
            .param("t", type.name)
            .param("rt", referenceType)
            .param("rid", referenceId?.toString())
            .param("occ", ts(occurredAt))
            .param("rec", ts(clock.instant()))
            .param("d", description.take(300))
            .param("c", CorrelationIdFilter.current())
            .update()
        nonZero.forEach { p ->
            jdbc
                .sql("insert into ledger_entries(journal_id, portfolio_id, account, instrument_id, amount, quantity) values (:j, :p, :a, :i, :amt, :q)")
                .param("j", id)
                .param("p", portfolioId)
                .param("a", p.account.name)
                .param("i", p.instrumentId)
                .param("amt", Decimals.money(p.amount))
                .param("q", Decimals.quantity(p.quantity))
                .update()
        }
        return id
    }

    fun balance(
        portfolioId: UUID,
        account: Account,
    ): BigDecimal =
        jdbc
            .sql("select coalesce(sum(amount), 0) from ledger_entries where portfolio_id = :p and account = :a")
            .param("p", portfolioId)
            .param("a", account.name)
            .query(BigDecimal::class.java)
            .single()

    fun balances(portfolioId: UUID): Map<Account, BigDecimal> {
        val rows =
            jdbc
                .sql("select account, coalesce(sum(amount), 0) from ledger_entries where portfolio_id = :p group by account")
                .param("p", portfolioId)
                .query { rs, _ -> Account.valueOf(rs.getString(1)) to rs.getBigDecimal(2) }
                .list()
                .toMap()
        return Account.entries.associateWith { rows[it] ?: BigDecimal.ZERO }
    }

    /** Positions derived purely from ledger entries (quantity and signed cost basis). */
    fun positions(portfolioId: UUID): List<LedgerPosition> =
        jdbc
            .sql(
                """
                select instrument_id, sum(quantity) as q, sum(amount) as c from ledger_entries
                where portfolio_id = :p and account = 'POSITION' group by instrument_id having sum(quantity) <> 0 or sum(amount) <> 0
                """.trimIndent(),
            ).param("p", portfolioId)
            .query { rs, _ -> LedgerPosition(rs.uuid("instrument_id"), rs.getBigDecimal("q"), rs.getBigDecimal("c")) }
            .list()

    fun position(
        portfolioId: UUID,
        instrumentId: UUID,
    ): LedgerPosition =
        jdbc
            .sql("select coalesce(sum(quantity), 0) as q, coalesce(sum(amount), 0) as c from ledger_entries where portfolio_id = :p and account = 'POSITION' and instrument_id = :i")
            .param("p", portfolioId)
            .param("i", instrumentId)
            .query { rs, _ -> LedgerPosition(instrumentId, rs.getBigDecimal("q"), rs.getBigDecimal("c")) }
            .single()

    /** Sum of all entries in every journal of the portfolio; must be exactly zero. */
    fun unbalancedJournals(portfolioId: UUID): List<UUID> =
        jdbc
            .sql("select journal_id from ledger_entries where portfolio_id = :p group by journal_id having sum(amount) <> 0")
            .param("p", portfolioId)
            .query(UUID::class.java)
            .list()

    fun journals(
        portfolioId: UUID,
        cursor: String?,
        limit: Int?,
    ): PageResponse<JournalView> {
        val l = Paging.limit(limit)
        val beforeSeq = Cursor.decode(cursor)?.id?.toLongOrNull()
        val journals =
            jdbc
                .sql(
                    """
                    select * from ledger_journals where portfolio_id = :p and (cast(:s as bigint) is null or seq < :s) order by seq desc limit :l
                    """.trimIndent(),
                ).param("p", portfolioId)
                .param("s", beforeSeq)
                .param("l", l + 1)
                .query { rs, _ ->
                    JournalView(
                        rs.uuid("id"),
                        rs.getLong("seq"),
                        rs.getString("journal_type"),
                        rs.getString("reference_type"),
                        rs.getString("reference_id"),
                        rs.instant("occurred_at"),
                        rs.instant("recorded_at"),
                        rs.getString("description"),
                        emptyList(),
                    )
                }.list()
        val ids = journals.map { it.id }
        val entries =
            if (ids.isEmpty()) {
                emptyMap()
            } else {
                jdbc
                    .sql(
                        "select e.*, i.symbol from ledger_entries e left join instruments i on i.id = e.instrument_id where e.journal_id in (:ids) order by e.id",
                    ).param("ids", ids)
                    .query { rs, _ ->
                        rs.uuid("journal_id") to EntryView(rs.getString("account"), rs.uuidOrNull("instrument_id"), rs.getString("symbol"), rs.getBigDecimal("amount"), rs.getBigDecimal("quantity"))
                    }.list()
                    .groupBy({ it.first }, { it.second })
            }
        return Paging.page(journals.map { it.copy(entries = entries[it.id].orEmpty()) }, l) { Cursor(it.occurredAt, it.seq.toString()) }
    }
}
