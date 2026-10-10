package app.strategyforge.android.core.state

import app.strategyforge.android.core.model.PortfolioSummary
import app.strategyforge.android.core.model.Position
import java.math.BigDecimal
import java.math.RoundingMode

/** One holding as a stock app shows it (D-080): shares, what they cost, what they are worth, and the difference. */
data class HoldingStats(
    val symbol: String,
    val name: String?,
    val short: Boolean,
    val shares: BigDecimal,
    val averageCost: BigDecimal?,
    val price: BigDecimal?,
    /** What the shares held cost (for a short, what selling them brought in). */
    val paid: BigDecimal?,
    /** What the same shares are worth now. */
    val value: BigDecimal?,
    /** Value now minus what was paid (for a short, the other way round). */
    val gain: BigDecimal?,
    val gainPercent: BigDecimal?,
    /** Share of the portfolio's equity, in percent. */
    val weightPercent: BigDecimal?,
)

/** The holdings together (D-080). */
data class HoldingsTotals(
    val paid: BigDecimal,
    val value: BigDecimal,
    val gain: BigDecimal,
    val gainPercent: BigDecimal?,
    /** False when a price is missing, so the totals leave something out. */
    val complete: Boolean,
)

object Holdings {
    private fun dec(v: String?) = v?.toBigDecimalOrNull()

    private fun percent(
        part: BigDecimal,
        whole: BigDecimal,
    ): BigDecimal? = if (whole.signum() == 0) null else part.multiply(BigDecimal(100)).divide(whole, 2, RoundingMode.HALF_EVEN)

    fun stats(
        p: Position,
        equity: String?,
    ): HoldingStats {
        val paid = dec(p.costBasis)?.abs()
        val value = dec(p.marketValue)?.abs()
        val gain = dec(p.unrealizedPnl)
        return HoldingStats(
            p.symbol,
            p.name,
            p.side == "SHORT" || (dec(p.quantity)?.signum() ?: 0) < 0,
            dec(p.quantity)?.abs() ?: BigDecimal.ZERO,
            dec(p.averageCost)?.abs(),
            dec(p.marketPrice),
            paid,
            value,
            gain,
            if (gain != null && paid != null) percent(gain, paid) else null,
            if (value != null) dec(equity)?.let { percent(value, it) } else null,
        )
    }

    /** Every open position, biggest first. */
    fun all(s: PortfolioSummary): List<HoldingStats> = s.positions.map { stats(it, s.equity) }.sortedByDescending { it.value ?: BigDecimal.ZERO }

    fun totals(s: PortfolioSummary): HoldingsTotals {
        val h = all(s)
        val paid = h.fold(BigDecimal.ZERO) { a, x -> a.add(x.paid ?: BigDecimal.ZERO) }
        val gain = h.fold(BigDecimal.ZERO) { a, x -> a.add(x.gain ?: BigDecimal.ZERO) }
        return HoldingsTotals(
            paid,
            h.fold(BigDecimal.ZERO) { a, x -> a.add(x.value ?: BigDecimal.ZERO) },
            gain,
            percent(gain, paid),
            h.all { it.value != null && it.gain != null },
        )
    }
}
