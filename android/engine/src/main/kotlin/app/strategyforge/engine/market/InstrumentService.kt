package app.strategyforge.engine.market

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.bool
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import java.math.BigDecimal
import java.util.UUID

/**
 * Instrument master (FR-020): curated US equities/ETFs plus the allowlisted USD crypto pairs.
 * Further US-listed common stocks/ETFs can be added after the active provider confirms them;
 * crypto is restricted to the allowlist seeded by migration.
 */
class InstrumentService(
    private val db: Db,
    private val registry: MarketSources,
    private val audit: AuditService,
) {
    fun list(
        query: String?,
        assetClass: String?,
        activeOnly: Boolean,
    ): List<Instrument> =
        db
            .sql(
                """
                select * from instruments
                where (:q is null or symbol like :qlike or name like :qlike)
                  and (:ac is null or asset_class = :ac)
                  and (:all = 1 or active = 1)
                order by asset_class, symbol
                """.trimIndent(),
            ).param("q", query)
            .param("qlike", "%${query ?: ""}%")
            .param("ac", assetClass)
            .param("all", !activeOnly)
            .list { rs -> map(rs) }

    fun bySymbol(symbol: String): Instrument = findBySymbol(symbol) ?: throw Problems.notFound("Instrument", symbol)

    fun findBySymbol(symbol: String): Instrument? =
        db
            .sql("select * from instruments where symbol = :s")
            .param("s", normalize(symbol))
            .firstOrNull { rs -> map(rs) }

    fun byId(id: UUID): Instrument =
        db
            .sql("select * from instruments where id = :id")
            .param("id", id)
            .single { rs -> map(rs) }

    fun byIds(ids: Collection<UUID>): List<Instrument> =
        if (ids.isEmpty()) {
            emptyList()
        } else {
            db
                .sql("select * from instruments where id in (:ids)")
                .param("ids", ids)
                .list { rs -> map(rs) }
        }

    fun add(symbolRaw: String): Instrument =
        db.tx {
            addInTx(symbolRaw)
        }

    private fun addInTx(symbolRaw: String): Instrument {
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
        db
            .sql(
                """
                insert into instruments(id, symbol, asset_class, name, exchange, price_increment, quantity_increment, min_quantity, shortable, source)
                values (:id, :s, 'US_EQUITY', :n, :e, '0.01', '0.0001', '0.0001', 1, 'PROVIDER_LOOKUP')
                """.trimIndent(),
            ).param("id", id)
            .param("s", symbol)
            .param("n", info.name.take(120))
            .param("e", info.exchange)
            .update()
        audit.record(AuditCategory.MARKET_DATA, "INSTRUMENT_ADDED", entityType = "Instrument", entityId = id, details = mapOf("symbol" to symbol, "exchange" to info.exchange))
        return byId(id)
    }

    fun setActive(
        symbol: String,
        active: Boolean,
    ): Instrument =
        db.tx {
            setActiveInTx(symbol, active)
        }

    private fun setActiveInTx(
        symbol: String,
        active: Boolean,
    ): Instrument {
        val i = bySymbol(symbol)
        db
            .sql("update instruments set active = :a, version = version + 1 where id = :id")
            .param("a", active)
            .param("id", i.id)
            .update()
        audit.record(AuditCategory.MARKET_DATA, if (active) "INSTRUMENT_ACTIVATED" else "INSTRUMENT_DEACTIVATED", entityType = "Instrument", entityId = i.id)
        return byId(i.id)
    }

    private fun map(rs: Row) =
        Instrument(
            rs.uuid("id"),
            rs.str("symbol"),
            AssetClass.valueOf(rs.str("asset_class")),
            rs.str("name"),
            rs.str("exchange"),
            rs.str("currency"),
            rs.dec("price_increment").stripZeros(),
            rs.dec("quantity_increment").stripZeros(),
            rs.dec("min_quantity").stripZeros(),
            rs.bool("shortable"),
            rs.bool("active"),
            rs.str("source"),
            rs.long("version") ?: 0L,
        )

    private fun BigDecimal.stripZeros(): BigDecimal = stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }

    companion object {
        fun normalize(s: String) = s.trim().uppercase()
    }
}
