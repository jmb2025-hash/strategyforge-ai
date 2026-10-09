package app.strategyforge.engine.market

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditOutcome
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.date
import app.strategyforge.engine.db.dateOrNull
import app.strategyforge.engine.db.decOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

data class CorporateAction(
    val id: UUID,
    val instrumentId: UUID,
    val type: CorporateActionType,
    val exDate: LocalDate,
    val payDate: LocalDate?,
    val ratioNew: BigDecimal?,
    val ratioOld: BigDecimal?,
    val cashAmount: BigDecimal?,
    val provider: String,
)

enum class CoverageStatus { AVAILABLE, UNAVAILABLE }

data class Coverage(
    val status: CoverageStatus,
    val from: LocalDate?,
    val to: LocalDate?,
    val provider: String,
    val detail: String?,
)

/**
 * Supported corporate actions: splits and cash dividends (FR-025). When the provider cannot
 * supply them, coverage is UNAVAILABLE and dependent results are Manual Review Required.
 */
class CorporateActionService(
    private val db: Db,
    private val registry: MarketSources,
    private val audit: AuditService,
    private val clock: Clock,
    /** A keyless second source for US splits and dividends (Yahoo Finance in the app, D-077). */
    private val fallback: () -> SymbolDirectory? = { null },
) {
    fun sync(
        instrument: Instrument,
        from: LocalDate,
        to: LocalDate,
    ): Coverage {
        val active = registry.active()
        var provider = registry.nameFor(instrument.assetClass)
        var result = active.provider.corporateActions(instrument.symbol, instrument.assetClass, from, to)
        // Twelve Data's free plan has no splits or dividends, which left every stock backtest needing a manual review
        // nobody could do (D-077): ask the second source before giving up.
        if (result !is ProviderResult.Ok && instrument.assetClass != AssetClass.CRYPTO) {
            runCatching { fallback()?.corporateActions(instrument.symbol, from, to) }.getOrNull()?.let {
                result = ProviderResult.Ok(it)
                provider = "YAHOO"
            }
        }
        val coverage =
            when (val r = result) {
                is ProviderResult.Ok -> {
                    r.value.forEach { a ->
                        db
                            .sql(
                                """
                                insert or ignore into corporate_actions(id, instrument_id, action_type, ex_date, pay_date, ratio_new, ratio_old, cash_amount, provider, received_at)
                                values (:id, :i, :t, :ex, :pay, :rn, :ro, :c, :p, :now)
                                """.trimIndent(),
                            ).param("id", UUID.randomUUID())
                            .param("i", instrument.id)
                            .param("t", a.type.name)
                            .param("ex", a.exDate)
                            .param("pay", a.payDate)
                            .param("rn", a.ratioNew)
                            .param("ro", a.ratioOld)
                            .param("c", a.cashAmount)
                            .param("p", provider)
                            .param("now", (clock.instant()))
                            .update()
                    }
                    val existing = coverage(instrument.id)
                    val f = if (existing?.status == CoverageStatus.AVAILABLE && existing.from != null && existing.from.isBefore(from)) existing.from else from
                    val t = if (existing?.status == CoverageStatus.AVAILABLE && existing.to != null && existing.to.isAfter(to)) existing.to else to
                    Coverage(CoverageStatus.AVAILABLE, f, t, provider, "${r.value.size} action(s) in range")
                }
                is ProviderResult.Unsupported -> Coverage(CoverageStatus.UNAVAILABLE, null, null, provider, r.detail)
                is ProviderResult.Failed -> Coverage(CoverageStatus.UNAVAILABLE, null, null, provider, "${r.kind}: ${r.detail}")
            }
        db
            .sql(
                """
                insert or replace into corporate_action_coverage(instrument_id, status, covered_from, covered_to, provider, detail, updated_at)
                values (:i, :s, :f, :t, :p, :d, :now)
                """.trimIndent(),
            ).param("i", instrument.id)
            .param("s", coverage.status.name)
            .param("f", coverage.from)
            .param("t", coverage.to)
            .param("p", provider)
            .param("d", coverage.detail?.take(500))
            .param("now", (clock.instant()))
            .update()
        if (coverage.status == CoverageStatus.UNAVAILABLE) {
            audit.record(AuditCategory.MARKET_DATA, "CORPORATE_ACTIONS_UNAVAILABLE", AuditOutcome.FAILURE, "Instrument", instrument.id, mapOf("symbol" to instrument.symbol, "detail" to coverage.detail))
        }
        return coverage
    }

    fun coverage(instrumentId: UUID): Coverage? =
        db
            .sql("select * from corporate_action_coverage where instrument_id = :i")
            .param("i", instrumentId)
            .firstOrNull { rs ->
                Coverage(CoverageStatus.valueOf(rs.str("status")), rs.dateOrNull("covered_from"), rs.dateOrNull("covered_to"), rs.str("provider"), rs.string("detail"))
            }

    /** True only when verified corporate-action data covers the full date range. */
    fun covered(
        instrument: Instrument,
        from: LocalDate,
        to: LocalDate,
    ): Boolean {
        if (instrument.assetClass == AssetClass.CRYPTO) return true
        // An earlier "unavailable" is asked again: a source may answer now, or a second source may (D-077).
        val c = coverage(instrument.id)?.takeIf { it.status == CoverageStatus.AVAILABLE } ?: sync(instrument, from, to)
        if (c.status != CoverageStatus.AVAILABLE || c.from == null || c.to == null) return false
        if (c.from.isAfter(from) || c.to.isBefore(to)) return sync(instrument, from, to).let { it.status == CoverageStatus.AVAILABLE && !it.from!!.isAfter(from) && !it.to!!.isBefore(to) }
        return true
    }

    fun actions(
        instrumentId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<CorporateAction> =
        db
            .sql("select * from corporate_actions where instrument_id = :i and ex_date between :f and :t order by ex_date")
            .param("i", instrumentId)
            .param("f", from)
            .param("t", to)
            .list { rs ->
                CorporateAction(
                    rs.uuid("id"),
                    rs.uuid("instrument_id"),
                    CorporateActionType.valueOf(rs.str("action_type")),
                    rs.date("ex_date"),
                    rs.dateOrNull("pay_date"),
                    rs.decOrNull("ratio_new"),
                    rs.decOrNull("ratio_old"),
                    rs.decOrNull("cash_amount"),
                    rs.str("provider"),
                )
            }
}
