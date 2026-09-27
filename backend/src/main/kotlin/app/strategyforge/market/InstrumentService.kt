package app.strategyforge.market

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.uuid
import app.strategyforge.common.web.Problems
import app.strategyforge.market.provider.MarketProviderRegistry
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.UUID

/**
 * Instrument master (FR-020): curated US equities/ETFs plus the allowlisted USD crypto pairs.
 * Further US-listed common stocks/ETFs can be added after the active provider confirms them;
 * crypto is restricted to the allowlist seeded by migration.
 */
@Service
class InstrumentService(
    private val jdbc: JdbcClient,
    private val registry: MarketProviderRegistry,
    private val audit: AuditService,
) {
    fun list(
        query: String?,
        assetClass: String?,
        activeOnly: Boolean,
    ): List<Instrument> =
        jdbc
            .sql(
                """
                select * from instruments
                where (cast(:q as text) is null or symbol ilike :qlike or name ilike :qlike)
                  and (cast(:ac as text) is null or asset_class = :ac)
                  and (:all or active)
                order by asset_class, symbol
                """.trimIndent(),
            ).param("q", query)
            .param("qlike", "%${query ?: ""}%")
            .param("ac", assetClass)
            .param("all", !activeOnly)
            .query { rs, _ -> map(rs) }
            .list()

    fun bySymbol(symbol: String): Instrument = findBySymbol(symbol) ?: throw Problems.notFound("Instrument", symbol)

    fun findBySymbol(symbol: String): Instrument? =
        jdbc
            .sql("select * from instruments where symbol = :s")
            .param("s", normalize(symbol))
            .query { rs, _ -> map(rs) }
            .optional()
            .orElse(null)

    fun byId(id: UUID): Instrument =
        jdbc
            .sql("select * from instruments where id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .single()

    fun byIds(ids: Collection<UUID>): List<Instrument> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            jdbc
                .sql("select * from instruments where id in (:ids)")
                .param("ids", ids)
                .query { rs, _ -> map(rs) }
                .list()
        }

    @Transactional
    fun add(symbolRaw: String): Instrument {
        val symbol = normalize(symbolRaw)
        findBySymbol(symbol)?.let { throw Problems.conflict("instrument-exists", "$symbol is already in the instrument master") }
        if (symbol.contains('-')) {
            throw Problems.unprocessable("crypto-not-allowlisted", "Only allowlisted major USD crypto pairs are supported in Version 1")
        }
        if (!Regex("^[A-Z][A-Z0-9.]{0,9}$").matches(symbol)) throw Problems.badRequest("invalid-symbol", "Invalid symbol")
        val info =
            when (val r = registry.active().provider.lookup(symbol)) {
                is ProviderResult.Ok -> r.value
                is ProviderResult.Unsupported -> throw Problems.unprocessable("lookup-unsupported", "The active provider cannot verify instruments: ${r.detail}")
                is ProviderResult.Failed -> throw Problems.unprocessable("instrument-not-verified", "The provider could not confirm $symbol as a US-listed stock or ETF (${r.kind}: ${r.detail})")
            }
        if (info.assetClass != AssetClass.US_EQUITY) throw Problems.unprocessable("unsupported-asset", "Only US-listed equities can be added")
        val id = UUID.randomUUID()
        jdbc
            .sql(
                """
                insert into instruments(id, symbol, asset_class, name, exchange, price_increment, quantity_increment, min_quantity, shortable, source)
                values (:id, :s, 'US_EQUITY', :n, :e, 0.01, 0.0001, 0.0001, true, 'PROVIDER_LOOKUP')
                """.trimIndent(),
            ).param("id", id)
            .param("s", symbol)
            .param("n", info.name.take(120))
            .param("e", info.exchange)
            .update()
        audit.record(AuditCategory.MARKET_DATA, "INSTRUMENT_ADDED", entityType = "Instrument", entityId = id, details = mapOf("symbol" to symbol, "exchange" to info.exchange))
        return byId(id)
    }

    @Transactional
    fun setActive(
        symbol: String,
        active: Boolean,
    ): Instrument {
        val i = bySymbol(symbol)
        jdbc
            .sql("update instruments set active = :a, version = version + 1 where id = :id")
            .param("a", active)
            .param("id", i.id)
            .update()
        audit.record(AuditCategory.MARKET_DATA, if (active) "INSTRUMENT_ACTIVATED" else "INSTRUMENT_DEACTIVATED", entityType = "Instrument", entityId = i.id)
        return byId(i.id)
    }

    private fun map(rs: java.sql.ResultSet) =
        Instrument(
            rs.uuid("id"),
            rs.getString("symbol"),
            AssetClass.valueOf(rs.getString("asset_class")),
            rs.getString("name"),
            rs.getString("exchange"),
            rs.getString("currency"),
            rs.getBigDecimal("price_increment").stripZeros(),
            rs.getBigDecimal("quantity_increment").stripZeros(),
            rs.getBigDecimal("min_quantity").stripZeros(),
            rs.getBoolean("shortable"),
            rs.getBoolean("active"),
            rs.getString("source"),
            rs.getLong("version"),
        )

    private fun BigDecimal.stripZeros(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    companion object {
        fun normalize(s: String) = s.trim().uppercase()
    }
}
