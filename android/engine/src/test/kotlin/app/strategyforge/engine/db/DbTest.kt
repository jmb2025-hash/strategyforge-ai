package app.strategyforge.engine.db

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.sql.DriverManager
import java.time.Instant
import java.util.UUID

/** On-device storage layer (D-027): schema, named parameters, exact decimals, append-only guards, transactions. */
class DbTest {
    private val db = Db(JdbcSqlBackend(DriverManager.getConnection("jdbc:sqlite::memory:"))).also { it.migrate() }

    private fun portfolio(): UUID {
        val id = UUID.randomUUID()
        db
            .sql("insert into portfolios(id, name, status, starting_balance, cost_model, created_at) values (:id, :n, 'ACTIVE', :b, '{}', :t)")
            .param("id", id)
            .param("n", "P-$id")
            .param("b", BigDecimal("100000.000000000001"))
            .param("t", Instant.parse("2026-06-22T14:00:00Z"))
            .update()
        return id
    }

    @Test
    fun `NFR-002 decimals round-trip exactly as text and timestamps as UTC millis`() {
        val id = portfolio()
        val row =
            db.sql("select starting_balance, created_at from portfolios where id = :id").param("id", id).single { r ->
                r.dec("starting_balance") to r.instant("created_at")
            }
        assertThat(row.first).isEqualByComparingTo("100000.000000000001")
        assertThat(row.first.toPlainString()).isEqualTo("100000.000000000001")
        assertThat(row.second).isEqualTo(Instant.parse("2026-06-22T14:00:00Z"))
        // Migration is idempotent.
        db.migrate()
        assertThat(db.sql("select count(*) n from portfolios").long()).isEqualTo(1)
    }

    @Test
    fun `FR-011 FR-110 append-only tables reject updates and deletes`() {
        val p = portfolio()
        val j = UUID.randomUUID()
        db
            .sql("insert into ledger_journals(id, seq, portfolio_id, journal_type, occurred_at, recorded_at, description) values (:id, 1, :p, 'FUNDING', 0, 0, 'x')")
            .param("id", j)
            .param("p", p)
            .update()
        db
            .sql("insert into ledger_entries(journal_id, portfolio_id, account, amount) values (:j, :p, 'CASH', '10')")
            .param("j", j)
            .param("p", p)
            .update()
        assertThatThrownBy { db.sql("update ledger_entries set amount = '11'").update() }.hasMessageContaining("append-only")
        assertThatThrownBy { db.sql("delete from ledger_journals").update() }.hasMessageContaining("append-only")
        db
            .sql("insert into audit_events(event_id, occurred_at, actor, category, action, outcome, details, hash) values ('e', 0, 'owner', 'X', 'Y', 'SUCCESS', '{}', 'h')")
            .update()
        assertThatThrownBy { db.sql("delete from audit_events").update() }.hasMessageContaining("append-only")
        // CHECK constraints from the server schema are enforced too.
        assertThatThrownBy { db.sql("update portfolios set account_type = 'LIVE'").update() }.isNotNull()
    }

    @Test
    fun `transactions roll back completely and nested calls join the outer one`() {
        assertThatThrownBy {
            db.tx {
                portfolio()
                db.tx { portfolio() }
                error("boom")
            }
        }.hasMessage("boom")
        assertThat(db.sql("select count(*) n from portfolios").long()).isZero()
        db.tx { db.tx { portfolio() } }
        assertThat(db.sql("select count(*) n from portfolios").long()).isEqualTo(1)
    }

    @Test
    fun `collection parameters expand and quoted colons are left alone`() {
        val a = portfolio()
        val b = portfolio()
        portfolio()
        val names = db.sql("select name from portfolios where id in (:ids) and name <> ':not-a-param' order by name").param("ids", listOf(a, b)).list { it.str("name") }
        assertThat(names).hasSize(2)
        assertThatThrownBy { db.sql("select * from portfolios where id = :missing").list { it } }.hasMessageContaining(":missing")
    }

    @Test
    fun `SQL newer than Android 10's SQLite is refused before it can ship`() {
        for (s in listOf("insert into settings values ('a','b') returning key", "insert into settings values ('a','b') on conflict(key) do nothing", "select sum(1) over (order by key) n from settings", "select 1::text n")) {
            assertThatThrownBy { db.sql(s).update() }.`as`(s).hasMessageContaining("SQLite 3.22")
        }
    }
}
