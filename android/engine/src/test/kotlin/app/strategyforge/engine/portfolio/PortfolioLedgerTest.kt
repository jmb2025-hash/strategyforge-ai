package app.strategyforge.engine.portfolio

import app.strategyforge.engine.common.EngineException
import app.strategyforge.engine.execution.OrderStatus
import app.strategyforge.engine.support.TestEngine
import app.strategyforge.engine.support.advance
import app.strategyforge.engine.support.order
import app.strategyforge.engine.support.portfolio
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.random.Random

/** Ported from the Version 1 PortfolioLifecycleIT and LedgerInvariantIT. */
class PortfolioLedgerTest {
    private val e = TestEngine.create()

    private fun code(block: () -> Unit): String =
        try {
            block()
            "none"
        } catch (x: EngineException) {
            x.code
        }

    @Test
    fun `FR-010 FR-015 MS-03 portfolio create, rename, clone, archive, reset and the ten-portfolio cap`() {
        val p = e.portfolios.create(PortfolioCreate("Main"))
        assertThat(p.accountType).isEqualTo("PAPER")
        assertThat(p.startingBalance).isEqualByComparingTo("100000")
        val s = e.portfolios.summary(p.id)
        assertThat(s.cash).isEqualByComparingTo("100000")
        assertThat(s.equity).isEqualByComparingTo("100000")
        assertThat(s.buyingPower).isEqualByComparingTo("100000")

        // Rename with optimistic concurrency.
        e.portfolios.update(p.id, PortfolioUpdate("Core", p.costModel), p.version)
        assertThat(code { e.portfolios.update(p.id, PortfolioUpdate("Core2", p.costModel), p.version) }).isEqualTo("version-mismatch")
        assertThat(code { e.portfolios.create(PortfolioCreate("core")) }).isEqualTo("portfolio-name-taken")

        // Clone copies configuration into a fresh ledger.
        val clone = e.portfolios.clone(p.id, "Core copy")
        assertThat(clone.clonedFrom).isEqualTo(p.id)
        assertThat(e.ledger.journals(clone.id, null, 50)).hasSize(1)

        // Reset archives the old portfolio (ledger preserved) and creates a new ledger.
        assertThat(code { e.portfolios.reset(p.id, "nope") }).isEqualTo("confirmation-required")
        val reset = e.portfolios.reset(p.id, "RESET")
        assertThat(reset.resetFrom).isEqualTo(p.id)
        assertThat(e.portfolios.get(p.id).status).isEqualTo("ARCHIVED")
        assertThat(e.ledger.journals(p.id, null, 50)).hasSize(1)
        assertThat(e.portfolios.summary(reset.id).cash).isEqualByComparingTo("100000")

        // Archive and the cap of 10 active portfolios.
        assertThat(e.portfolios.archive(clone.id).status).isEqualTo("ARCHIVED")
        val active = e.portfolios.list(false).size
        repeat(10 - active) { e.portfolio("Extra $it") }
        assertThat(code { e.portfolio("Eleventh") }).isEqualTo("portfolio-limit")
        assertThat(e.portfolios.list(true).count { it.status == "ARCHIVED" }).isEqualTo(2)
        val audits = e.db.sql("select count(*) from audit_events where action in ('PORTFOLIO_CREATED','PORTFOLIO_CLONED','PORTFOLIO_RESET','PORTFOLIO_ARCHIVED','PORTFOLIO_ARCHIVED_FOR_RESET')").long()
        assertThat(audits).isGreaterThanOrEqualTo(14)
        assertThat(e.audit.verifyChain()).`as`("first broken audit id").isNull()
    }

    @Test
    fun `FR-011 FR-113 unbalanced journals are refused and ledger rows cannot change`() {
        val pid = e.portfolio(name = "Integrity")
        assertThatThrownBy {
            e.ledger.post(pid, JournalType.ADJUSTMENT, e.marketClock.now(), "bad", listOf(Posting(Account.CASH, BigDecimal("5"))), "Portfolio", pid)
        }.isInstanceOf(UnbalancedJournalException::class.java)
        assertThatThrownBy {
            e.db
                .sql("update ledger_entries set amount = '1' where portfolio_id = :p")
                .param("p", pid)
                .update()
        }.hasMessageContaining("append-only")
        assertThatThrownBy {
            e.db
                .sql("delete from ledger_journals where portfolio_id = :p")
                .param("p", pid)
                .update()
        }.hasMessageContaining("append-only")
        assertThatThrownBy {
            e.db
                .sql("update portfolios set account_type = 'LIVE' where id = :p")
                .param("p", pid)
                .update()
        }.isNotNull()
        assertThat(e.reconciliation.run(pid).status).isEqualTo("OK")
    }

    @Test
    fun `MS-04 FR-012 FR-013 FR-014 randomized activity always reconciles to the ledger and equity identity holds`() {
        e.auth.confirmed()
        val symbols = listOf("ETH-USD", "SOL-USD", "AAPL", "MSFT", "SPY")
        for (seed in 1..3) {
            val rnd = Random(seed)
            val pid = e.portfolio(name = "Seed $seed", balance = "250000", costModel = CostModel(commissionPerOrder = BigDecimal("0.50"), commissionPercent = BigDecimal("0.01"), slippageBps = BigDecimal("3")))
            e.portfolios.setShorting(pid, true)
            repeat(18) {
                val symbol = symbols[rnd.nextInt(symbols.size)]
                val held =
                    e.portfolios
                        .summary(pid)
                        .positions
                        .firstOrNull { it.symbol == symbol }
                        ?.quantity ?: BigDecimal.ZERO
                val qty = if (symbol.endsWith("-USD")) BigDecimal(rnd.nextInt(1, 40)).divide(BigDecimal(10)) else BigDecimal(rnd.nextInt(1, 30))
                when (rnd.nextInt(6)) {
                    0, 1 -> if (held.signum() >= 0) e.order(pid, symbol, "BUY", qty.toPlainString())
                    2 ->
                        if (held.signum() > 0) {
                            val q =
                                held
                                    .divide(BigDecimal(2))
                                    .setScale(4, RoundingMode.DOWN)
                                    .max(BigDecimal("0.0001"))
                                    .min(held)
                            e.order(pid, symbol, "SELL", q.toPlainString())
                        }
                    3 -> if (held.signum() <= 0 && !symbol.endsWith("-USD")) e.order(pid, symbol, "SELL_SHORT", qty.toPlainString())
                    4 -> if (held.signum() < 0) e.order(pid, symbol, "BUY_TO_COVER", held.negate().toPlainString())
                    else -> {
                        val last =
                            e.market
                                .refreshQuote(e.instruments.bySymbol(symbol))
                                .quote!!
                                .last
                        val side = if (held.signum() < 0) "BUY_TO_COVER" else "BUY"
                        val q = if (held.signum() < 0) held.negate() else qty
                        val r = e.order(pid, symbol, side, q.toPlainString(), type = "LIMIT", limit = last.multiply(BigDecimal("0.9")).setScale(2, RoundingMode.DOWN).toPlainString())
                        if (r.order.status == OrderStatus.PENDING) e.orders.cancel(r.order.id, "test")
                    }
                }
                if (rnd.nextBoolean()) e.advance(1)
            }
            e.advance(2)
            e.orders.list(pid, listOf(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED), 500).forEach { e.orders.cancel(it.id, "test") }

            val recon = e.reconciliation.run(pid)
            assertThat(recon.status).`as`("seed $seed: ${recon.checks}").isEqualTo("OK")
            val b = e.ledger.balances(pid)
            assertThat(b.values.fold(BigDecimal.ZERO, BigDecimal::add)).`as`("ledger sums to zero").isEqualByComparingTo(BigDecimal.ZERO)
            assertThat(b.getValue(Account.CASH_RESERVED)).isEqualByComparingTo(BigDecimal.ZERO)
            val s = e.portfolios.summary(pid)
            val positionsValue = s.positions.fold(BigDecimal.ZERO) { a, p -> a.add(p.marketValue ?: p.costBasis) }
            assertThat(s.equity).isEqualByComparingTo(s.cash.add(s.reservedCash).add(positionsValue))
            val executions =
                e.db
                    .sql("select count(*) from paper_executions where portfolio_id = :p")
                    .param("p", pid)
                    .long()
            assertThat(executions).`as`("seed $seed produced fills").isGreaterThan(0)
        }
    }
}
