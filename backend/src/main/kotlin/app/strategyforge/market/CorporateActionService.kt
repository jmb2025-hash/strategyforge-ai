package app.strategyforge.market

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditOutcome
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.market.provider.MarketProviderRegistry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
@Service
class CorporateActionService(
    private val jdbc: JdbcClient,
    private val registry: MarketProviderRegistry,
    private val audit: AuditService,
    private val clock: Clock,
) {
    @Transactional
    fun sync(
        instrument: Instrument,
        from: LocalDate,
        to: LocalDate,
    ): Coverage {
        val active = registry.active()
        val provider = active.type.name
        val coverage =
            when (val r = active.provider.corporateActions(instrument.symbol, instrument.assetClass, from, to)) {
                is ProviderResult.Ok -> {
                    r.value.forEach { a ->
                        jdbc
                            .sql(
                                """
                                insert into corporate_actions(instrument_id, action_type, ex_date, pay_date, ratio_new, ratio_old, cash_amount, provider, received_at)
                                values (:i, :t, :ex, :pay, :rn, :ro, :c, :p, :now) on conflict (instrument_id, action_type, ex_date) do nothing
                                """.trimIndent(),
                            ).param("i", instrument.id)
                            .param("t", a.type.name)
                            .param("ex", a.exDate)
                            .param("pay", a.payDate)
                            .param("rn", a.ratioNew)
                            .param("ro", a.ratioOld)
                            .param("c", a.cashAmount)
                            .param("p", provider)
                            .param("now", ts(clock.instant()))
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
        jdbc
            .sql(
                """
                insert into corporate_action_coverage(instrument_id, status, covered_from, covered_to, provider, detail, updated_at)
                values (:i, :s, :f, :t, :p, :d, :now)
                on conflict (instrument_id) do update set status = excluded.status, covered_from = excluded.covered_from, covered_to = excluded.covered_to,
                  provider = excluded.provider, detail = excluded.detail, updated_at = excluded.updated_at
                """.trimIndent(),
            ).param("i", instrument.id)
            .param("s", coverage.status.name)
            .param("f", coverage.from)
            .param("t", coverage.to)
            .param("p", provider)
            .param("d", coverage.detail?.take(500))
            .param("now", ts(clock.instant()))
            .update()
        if (coverage.status == CoverageStatus.UNAVAILABLE) {
            audit.record(AuditCategory.MARKET_DATA, "CORPORATE_ACTIONS_UNAVAILABLE", AuditOutcome.FAILURE, "Instrument", instrument.id, mapOf("symbol" to instrument.symbol, "detail" to coverage.detail))
        }
        return coverage
    }

    fun coverage(instrumentId: UUID): Coverage? =
        jdbc
            .sql("select * from corporate_action_coverage where instrument_id = :i")
            .param("i", instrumentId)
            .query { rs, _ ->
                Coverage(CoverageStatus.valueOf(rs.getString("status")), rs.getObject("covered_from", LocalDate::class.java), rs.getObject("covered_to", LocalDate::class.java), rs.getString("provider"), rs.getString("detail"))
            }.optional()
            .orElse(null)

    /** True only when verified corporate-action data covers the full date range. */
    fun covered(
        instrument: Instrument,
        from: LocalDate,
        to: LocalDate,
    ): Boolean {
        if (instrument.assetClass == AssetClass.CRYPTO) return true
        val c = coverage(instrument.id) ?: sync(instrument, from, to)
        if (c.status != CoverageStatus.AVAILABLE || c.from == null || c.to == null) return false
        if (c.from.isAfter(from) || c.to.isBefore(to)) return sync(instrument, from, to).let { it.status == CoverageStatus.AVAILABLE && !it.from!!.isAfter(from) && !it.to!!.isBefore(to) }
        return true
    }

    fun actions(
        instrumentId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<CorporateAction> =
        jdbc
            .sql("select * from corporate_actions where instrument_id = :i and ex_date between :f and :t order by ex_date")
            .param("i", instrumentId)
            .param("f", from)
            .param("t", to)
            .query { rs, _ ->
                CorporateAction(
                    rs.uuid("id"),
                    rs.uuid("instrument_id"),
                    CorporateActionType.valueOf(rs.getString("action_type")),
                    rs.getObject("ex_date", LocalDate::class.java),
                    rs.getObject("pay_date", LocalDate::class.java),
                    rs.getBigDecimal("ratio_new"),
                    rs.getBigDecimal("ratio_old"),
                    rs.getBigDecimal("cash_amount"),
                    rs.getString("provider"),
                )
            }.list()
}
