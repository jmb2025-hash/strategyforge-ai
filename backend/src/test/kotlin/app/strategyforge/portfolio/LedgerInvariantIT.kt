package app.strategyforge.portfolio

import app.strategyforge.support.FreshDatabaseTest
import app.strategyforge.support.Replay
import app.strategyforge.support.TestOwner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/**
 * Property-style financial invariants over randomized, seeded activity (MS-04, FR-011, FR-012):
 * buys, sells, shorts, covers, fees, partial fills and cancellations must always reconcile.
 */
class LedgerInvariantIT : FreshDatabaseTest() {
    @Test
    fun `FR-011 FR-113 the database rejects unbalanced journals, ledger mutation and non-paper accounts`() {
        val h = TestOwner.client(baseUrl)
        val pid = Replay.portfolio(h, name = "Integrity")
        assertThatThrownBy {
            jdbc
                .sql("insert into ledger_journals(id, portfolio_id, journal_type, occurred_at, recorded_at, description) values (gen_random_uuid(), cast(:p as uuid), 'ADJUSTMENT', now(), now(), 'bad')")
                .param("p", pid)
                .update()
            val j = jdbc.sql("select id from ledger_journals where description = 'bad'").query(java.util.UUID::class.java).single()
            jdbc
                .sql("insert into ledger_entries(journal_id, portfolio_id, account, amount) values (:j, cast(:p as uuid), 'CASH', 5)")
                .param("j", j)
                .param("p", pid)
                .update()
            jdbc.sql("set constraints all immediate").update()
        }.hasMessageContaining("unbalanced")
        assertThatThrownBy { jdbc.sql("update ledger_entries set amount = amount + 1 where portfolio_id = cast(:p as uuid)").param("p", pid).update() }.hasMessageContaining("append-only")
        assertThatThrownBy { jdbc.sql("delete from ledger_journals where portfolio_id = cast(:p as uuid)").param("p", pid).update() }.hasMessageContaining("append-only")
        assertThatThrownBy { jdbc.sql("update portfolios set account_type = 'LIVE' where id = cast(:p as uuid)").param("p", pid).update() }.hasMessageContaining("account_type")
        val venueCheck = jdbc.sql("select pg_get_constraintdef(oid) from pg_constraint where conname = 'paper_orders_venue_check'").query(String::class.java).single()
        assertThat(venueCheck).contains("PAPER_SIMULATOR")
    }

    @Test
    fun `MS-04 FR-012 FR-013 randomized activity always reconciles to the ledger`() {
        val h = TestOwner.client(baseUrl)
        h.post("/v1/auth/reauthenticate", mapOf("password" to TestOwner.PASSWORD))
        val symbols = listOf("ETH-USD", "SOL-USD", "AAPL", "MSFT", "SPY")
        for (seed in 1..3) {
            val rnd = Random(seed)
            val pid = Replay.portfolio(h, name = "Seed $seed", balance = "250000", costModel = mapOf("commissionPerOrder" to "0.50", "commissionPercent" to "0.01", "slippageBps" to "3"))
            h.put("/v1/portfolios/$pid/shorting", mapOf("enabled" to true))
            repeat(18) {
                val symbol = symbols[rnd.nextInt(symbols.size)]
                val positions = h.get("/v1/positions?portfolioId=$pid").json.associateBy({ it["symbol"].asText() }, { BigDecimal(it["quantity"].asText()) })
                val held = positions[symbol] ?: BigDecimal.ZERO
                val qty = if (symbol.endsWith("-USD")) BigDecimal(rnd.nextInt(1, 40)).divide(BigDecimal(10)) else BigDecimal(rnd.nextInt(1, 30))
                when (rnd.nextInt(6)) {
                    0, 1 -> if (held.signum() >= 0) Replay.order(h, pid, symbol, "BUY", qty.toPlainString())
                    2 ->
                        if (held.signum() > 0) {
                            Replay.order(
                                h,
                                pid,
                                symbol,
                                "SELL",
                                held
                                    .divide(BigDecimal(2))
                                    .setScale(4, RoundingMode.DOWN)
                                    .max(BigDecimal("0.0001"))
                                    .min(held)
                                    .toPlainString(),
                            )
                        }
                    3 -> if (held.signum() <= 0 && !symbol.endsWith("-USD")) Replay.order(h, pid, symbol, "SELL_SHORT", qty.toPlainString())
                    4 -> if (held.signum() < 0) Replay.order(h, pid, symbol, "BUY_TO_COVER", held.negate().toPlainString())
                    else -> {
                        val last = BigDecimal(h.get("/v1/market-data/quotes/$symbol?refresh=true").json["last"].asText())
                        val r = Replay.order(h, pid, symbol, if (held.signum() < 0) "BUY_TO_COVER" else "BUY", if (held.signum() < 0) held.negate().toPlainString() else qty.toPlainString(), type = "LIMIT", limit = last.multiply(BigDecimal("0.9")).setScale(2, RoundingMode.DOWN).toPlainString())
                        if (r.json["order"]["status"].asText() == "PENDING") h.post("/v1/orders/${r.json["order"]["id"].asText()}/cancel")
                    }
                }
                if (rnd.nextBoolean()) Replay.advance(h, 1)
            }
            Replay.advance(h, 2)
            h.get("/v1/orders?portfolioId=$pid&status=PENDING,PARTIALLY_FILLED").json["items"].forEach { h.post("/v1/orders/${it["id"].asText()}/cancel") }

            val recon = h.post("/v1/portfolios/$pid/reconciliation").json
            assertThat(recon["status"].asText()).`as`("seed $seed: ${recon["checks"]}").isEqualTo("OK")
            val b = h.get("/v1/ledger/balances?portfolioId=$pid").json
            val total = b.fields().asSequence().fold(BigDecimal.ZERO) { a, e -> a.add(BigDecimal(e.value.asText())) }
            assertThat(total).`as`("ledger sums to zero").isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(BigDecimal(b["CASH_RESERVED"].asText())).isEqualByComparingTo(BigDecimal.ZERO)
            val s = h.get("/v1/portfolios/$pid/summary").json
            val positionsValue = s["positions"].fold(BigDecimal.ZERO) { a, p -> a.add(BigDecimal((p["marketValue"].takeIf { !it.isNull } ?: p["costBasis"]).asText())) }
            assertThat(BigDecimal(s["equity"].asText())).isEqualByComparingTo(BigDecimal(s["cash"].asText()).add(BigDecimal(s["reservedCash"].asText())).add(positionsValue))
            val executions =
                jdbc
                    .sql("select count(*) from paper_executions where portfolio_id = cast(:p as uuid)")
                    .param("p", pid)
                    .query(Int::class.java)
                    .single()
            assertThat(executions).`as`("seed $seed produced fills").isGreaterThan(0)
        }
    }
}
