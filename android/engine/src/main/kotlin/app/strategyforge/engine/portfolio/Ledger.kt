package app.strategyforge.engine.portfolio

import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.db.uuidOrNull
import app.strategyforge.engine.money.Decimals
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

private data class Entry(
    val journalId: UUID,
    val account: Account,
    val instrumentId: UUID?,
    val amount: BigDecimal,
    val quantity: BigDecimal,
)

/**
 * Append-only double-entry ledger (FR-011, FR-012). Balances and positions are always computed
 * from entries (summed exactly in Kotlin), never from mutable totals. The database rejects
 * updates and deletes; this service rejects unbalanced journals before anything is written.
 */
class LedgerService(
    private val db: Db,
    private val clock: Clock,
) {
    fun post(
        portfolioId: UUID,
        type: JournalType,
        occurredAt: Instant,
        description: String,
        postings: List<Posting>,
        referenceType: String? = null,
        referenceId: Any? = null,
    ): UUID =
        db.tx {
            val nonZero = postings.filter { it.amount.signum() != 0 || it.quantity.signum() != 0 }
            require(nonZero.isNotEmpty()) { "Journal must contain at least one posting" }
            val sum = nonZero.fold(BigDecimal.ZERO) { a, p -> a.add(p.amount) }
            if (sum.signum() != 0) throw UnbalancedJournalException("Journal '$description' is unbalanced by $sum")
            nonZero.forEach { p ->
                require((p.account == Account.POSITION) == (p.instrumentId != null)) { "Only POSITION postings carry an instrument" }
                require(p.account == Account.POSITION || p.quantity.signum() == 0) { "Only POSITION postings carry quantity" }
            }
            val id = UUID.randomUUID()
            val seq = db.sql("select coalesce(max(seq), 0) + 1 n from ledger_journals").long()
            db
                .sql(
                    """
                    insert into ledger_journals(id, seq, portfolio_id, journal_type, reference_type, reference_id, occurred_at, recorded_at, description)
                    values (:id, :seq, :p, :t, :rt, :rid, :occ, :rec, :d)
                    """.trimIndent(),
                ).param("id", id)
                .param("seq", seq)
                .param("p", portfolioId)
                .param("t", type)
                .param("rt", referenceType)
                .param("rid", referenceId?.toString())
                .param("occ", occurredAt)
                .param("rec", clock.instant())
                .param("d", description.take(300))
                .update()
            nonZero.forEach { p ->
                db
                    .sql("insert into ledger_entries(journal_id, portfolio_id, account, instrument_id, amount, quantity) values (:j, :p, :a, :i, :amt, :q)")
                    .param("j", id)
                    .param("p", portfolioId)
                    .param("a", p.account)
                    .param("i", p.instrumentId)
                    .param("amt", Decimals.money(p.amount))
                    .param("q", Decimals.quantity(p.quantity))
                    .update()
            }
            id
        }

    private fun entries(
        portfolioId: UUID,
        account: Account? = null,
        instrumentId: UUID? = null,
    ): List<Entry> =
        db
            .sql(
                "select journal_id, account, instrument_id, amount, quantity from ledger_entries where portfolio_id = :p and (:a is null or account = :a) and (:i is null or instrument_id = :i)",
            ).param("p", portfolioId)
            .param("a", account)
            .param("i", instrumentId)
            .list { r -> Entry(r.uuid("journal_id"), Account.valueOf(r.str("account")), r.uuidOrNull("instrument_id"), r.dec("amount"), r.dec("quantity")) }

    fun balance(
        portfolioId: UUID,
        account: Account,
    ): BigDecimal = entries(portfolioId, account).fold(BigDecimal.ZERO) { a, e -> a.add(e.amount) }

    fun balances(portfolioId: UUID): Map<Account, BigDecimal> {
        val all = entries(portfolioId)
        return Account.entries.associateWith { acc -> all.filter { it.account == acc }.fold(BigDecimal.ZERO) { a, e -> a.add(e.amount) } }
    }

    /** Positions derived purely from ledger entries (quantity and signed cost basis). */
    fun positions(portfolioId: UUID): List<LedgerPosition> =
        entries(portfolioId, Account.POSITION)
            .groupBy { it.instrumentId!! }
            .map { (i, es) -> LedgerPosition(i, es.fold(BigDecimal.ZERO) { a, e -> a.add(e.quantity) }, es.fold(BigDecimal.ZERO) { a, e -> a.add(e.amount) }) }
            .filter { it.quantity.signum() != 0 || it.cost.signum() != 0 }

    fun position(
        portfolioId: UUID,
        instrumentId: UUID,
    ): LedgerPosition {
        val es = entries(portfolioId, Account.POSITION, instrumentId)
        return LedgerPosition(instrumentId, es.fold(BigDecimal.ZERO) { a, e -> a.add(e.quantity) }, es.fold(BigDecimal.ZERO) { a, e -> a.add(e.amount) })
    }

    /** Journals whose entries do not sum to exactly zero; must always be empty. */
    fun unbalancedJournals(portfolioId: UUID): List<UUID> =
        entries(portfolioId)
            .groupBy { it.journalId }
            .filterValues { es -> es.fold(BigDecimal.ZERO) { a, e -> a.add(e.amount) }.signum() != 0 }
            .keys
            .toList()

    fun journals(
        portfolioId: UUID,
        beforeSeq: Long?,
        limit: Int,
    ): List<JournalView> {
        val journals =
            db
                .sql("select * from ledger_journals where portfolio_id = :p and (:s is null or seq < :s) order by seq desc limit :l")
                .param("p", portfolioId)
                .param("s", beforeSeq)
                .param("l", limit)
                .list { r ->
                    JournalView(
                        r.uuid("id"),
                        r.long("seq")!!,
                        r.str("journal_type"),
                        r.string("reference_type"),
                        r.string("reference_id"),
                        r.instant("occurred_at"),
                        r.instant("recorded_at"),
                        r.str("description"),
                        emptyList(),
                    )
                }
        if (journals.isEmpty()) return journals
        val entries =
            db
                .sql("select e.journal_id, e.account, e.instrument_id, e.amount, e.quantity, i.symbol from ledger_entries e left join instruments i on i.id = e.instrument_id where e.journal_id in (:ids) order by e.id")
                .param("ids", journals.map { it.id })
                .list { r -> r.uuid("journal_id") to EntryView(r.str("account"), r.uuidOrNull("instrument_id"), r.string("symbol"), r.dec("amount"), r.dec("quantity")) }
                .groupBy({ it.first }, { it.second })
        return journals.map { it.copy(entries = entries[it.id].orEmpty()) }
    }
}
