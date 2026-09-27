package app.strategyforge.market

import app.strategyforge.common.audit.AuditCategory
import app.strategyforge.common.audit.AuditService
import app.strategyforge.common.db.instant
import app.strategyforge.common.db.instantOrNull
import app.strategyforge.common.db.ts
import app.strategyforge.common.db.uuid
import app.strategyforge.common.web.Problems
import app.strategyforge.market.data.StoredQuote
import app.strategyforge.notifications.NotificationCategory
import app.strategyforge.notifications.NotificationService
import app.strategyforge.notifications.Severity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.util.UUID

data class WatchlistItem(
    val symbol: String,
    val instrumentId: UUID,
    val addedAt: Instant,
)

data class Watchlist(
    val id: UUID,
    val name: String,
    val items: List<WatchlistItem>,
    val createdAt: Instant,
    val archivedAt: Instant?,
    val version: Long,
)

data class PriceAlert(
    val id: UUID,
    val symbol: String,
    val instrumentId: UUID,
    val condition: String,
    val threshold: BigDecimal,
    val status: String,
    val note: String?,
    val createdAt: Instant,
    val triggeredAt: Instant?,
    val triggeredPrice: BigDecimal?,
)

/** Instruments the ingestion job must keep fresh. Each module contributes its own set. */
fun interface MarketInterest {
    fun instrumentIds(): Set<UUID>
}

@Service
class WatchlistService(
    private val jdbc: JdbcClient,
    private val instruments: InstrumentService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    fun list(): List<Watchlist> = jdbc.sql("select * from watchlists where archived_at is null order by created_at").query { rs, _ -> map(rs) }.list()

    fun get(id: UUID): Watchlist =
        jdbc
            .sql("select * from watchlists where id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElseThrow { Problems.notFound("Watchlist", id) }

    @Transactional
    fun create(
        name: String,
        symbols: List<String>,
    ): Watchlist {
        if (name.isBlank() || name.length > 60) throw Problems.badRequest("invalid-name", "Watchlist name must be 1-60 characters")
        if (symbols.size > MAX_ITEMS) throw Problems.badRequest("too-many-items", "A watchlist holds at most $MAX_ITEMS instruments")
        val id = UUID.randomUUID()
        jdbc
            .sql("insert into watchlists(id, name, created_at) values (:id, :n, :now)")
            .param("id", id)
            .param("n", name.trim())
            .param("now", ts(clock.instant()))
            .update()
        symbols.distinct().forEach { addItem(id, it, audited = false) }
        audit.record(AuditCategory.MARKET_DATA, "WATCHLIST_CREATED", entityType = "Watchlist", entityId = id, details = mapOf("name" to name, "symbols" to symbols))
        return get(id)
    }

    @Transactional
    fun addItem(
        id: UUID,
        symbol: String,
        audited: Boolean = true,
    ): Watchlist {
        val w = get(id)
        if (w.archivedAt != null) throw Problems.conflict("watchlist-archived", "Watchlist is archived")
        if (w.items.size >= MAX_ITEMS) throw Problems.badRequest("too-many-items", "A watchlist holds at most $MAX_ITEMS instruments")
        val i = instruments.bySymbol(symbol)
        jdbc
            .sql("insert into watchlist_items(watchlist_id, instrument_id, added_at) values (:w, :i, :now) on conflict do nothing")
            .param("w", id)
            .param("i", i.id)
            .param("now", ts(clock.instant()))
            .update()
        if (audited) audit.record(AuditCategory.MARKET_DATA, "WATCHLIST_ITEM_ADDED", entityType = "Watchlist", entityId = id, details = mapOf("symbol" to i.symbol))
        return get(id)
    }

    @Transactional
    fun removeItem(
        id: UUID,
        symbol: String,
    ): Watchlist {
        val i = instruments.bySymbol(symbol)
        jdbc
            .sql("delete from watchlist_items where watchlist_id = :w and instrument_id = :i")
            .param("w", id)
            .param("i", i.id)
            .update()
        audit.record(AuditCategory.MARKET_DATA, "WATCHLIST_ITEM_REMOVED", entityType = "Watchlist", entityId = id, details = mapOf("symbol" to i.symbol))
        return get(id)
    }

    @Transactional
    fun archive(id: UUID) {
        get(id)
        jdbc
            .sql("update watchlists set archived_at = :now, version = version + 1 where id = :id")
            .param("now", ts(clock.instant()))
            .param("id", id)
            .update()
        audit.record(AuditCategory.MARKET_DATA, "WATCHLIST_ARCHIVED", entityType = "Watchlist", entityId = id)
    }

    private fun map(rs: java.sql.ResultSet): Watchlist {
        val id = rs.uuid("id")
        val items =
            jdbc
                .sql("select i.symbol, i.id, w.added_at from watchlist_items w join instruments i on i.id = w.instrument_id where w.watchlist_id = :w order by i.symbol")
                .param("w", id)
                .query { r, _ -> WatchlistItem(r.getString(1), r.uuid("id"), r.instant("added_at")) }
                .list()
        return Watchlist(id, rs.getString("name"), items, rs.instant("created_at"), rs.instantOrNull("archived_at"), rs.getLong("version"))
    }

    companion object {
        const val MAX_ITEMS = 200
    }
}

@Service
class PriceAlertService(
    private val jdbc: JdbcClient,
    private val instruments: InstrumentService,
    private val notifications: NotificationService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    fun list(status: String?): List<PriceAlert> =
        jdbc
            .sql("select a.*, i.symbol from price_alerts a join instruments i on i.id = a.instrument_id where (cast(:s as text) is null or a.status = :s) order by a.created_at desc")
            .param("s", status)
            .query { rs, _ -> map(rs) }
            .list()

    @Transactional
    fun create(
        symbol: String,
        condition: String,
        threshold: BigDecimal,
        note: String?,
    ): PriceAlert {
        if (condition !in setOf("ABOVE", "BELOW")) throw Problems.badRequest("invalid-condition", "condition must be ABOVE or BELOW")
        if (threshold.signum() <= 0) throw Problems.badRequest("invalid-threshold", "threshold must be positive")
        val i = instruments.bySymbol(symbol)
        val id = UUID.randomUUID()
        jdbc
            .sql("insert into price_alerts(id, instrument_id, condition, threshold, status, note, created_at) values (:id, :i, :c, :t, 'ACTIVE', :n, :now)")
            .param("id", id)
            .param("i", i.id)
            .param("c", condition)
            .param("t", threshold)
            .param("n", note?.take(200))
            .param("now", ts(clock.instant()))
            .update()
        audit.record(AuditCategory.MARKET_DATA, "PRICE_ALERT_CREATED", entityType = "PriceAlert", entityId = id, details = mapOf("symbol" to i.symbol, "condition" to condition, "threshold" to threshold))
        return get(id)
    }

    fun get(id: UUID): PriceAlert =
        jdbc
            .sql("select a.*, i.symbol from price_alerts a join instruments i on i.id = a.instrument_id where a.id = :id")
            .param("id", id)
            .query { rs, _ -> map(rs) }
            .optional()
            .orElseThrow { Problems.notFound("Price alert", id) }

    @Transactional
    fun cancel(id: UUID): PriceAlert {
        val n = jdbc.sql("update price_alerts set status = 'CANCELLED', version = version + 1 where id = :id and status = 'ACTIVE'").param("id", id).update()
        if (n == 0) throw Problems.conflict("alert-not-active", "Only active alerts can be cancelled")
        audit.record(AuditCategory.MARKET_DATA, "PRICE_ALERT_CANCELLED", entityType = "PriceAlert", entityId = id)
        return get(id)
    }

    /** Evaluated on every stored quote; each alert fires once. */
    @Transactional
    fun evaluate(
        instrument: Instrument,
        quote: StoredQuote,
    ) {
        val triggered =
            jdbc
                .sql(
                    """
                    update price_alerts set status = 'TRIGGERED', triggered_at = :now, triggered_price = :p, version = version + 1
                    where instrument_id = :i and status = 'ACTIVE' and ((condition = 'ABOVE' and :p >= threshold) or (condition = 'BELOW' and :p <= threshold))
                    returning id, condition, threshold
                    """.trimIndent(),
                ).param("now", ts(clock.instant()))
                .param("p", quote.last)
                .param("i", instrument.id)
                .query { rs, _ -> Triple(rs.uuid("id"), rs.getString("condition"), rs.getBigDecimal("threshold")) }
                .list()
        triggered.forEach { (id, cond, threshold) ->
            notifications.notify(
                NotificationCategory.PRICE_ALERT,
                Severity.INFO,
                "${instrument.symbol} ${cond.lowercase()} ${threshold.toPlainString()}",
                "${instrument.symbol} traded at ${quote.last.toPlainString()} (quote time ${quote.exchangeTs}, ${quote.feedType}).",
                "PriceAlert",
                id,
                "alert:$id",
            )
            audit.record(AuditCategory.MARKET_DATA, "PRICE_ALERT_TRIGGERED", entityType = "PriceAlert", entityId = id, details = mapOf("symbol" to instrument.symbol, "price" to quote.last))
        }
    }

    private fun map(rs: java.sql.ResultSet) =
        PriceAlert(
            rs.uuid("id"),
            rs.getString("symbol"),
            rs.uuid("instrument_id"),
            rs.getString("condition"),
            rs.getBigDecimal("threshold"),
            rs.getString("status"),
            rs.getString("note"),
            rs.instant("created_at"),
            rs.instantOrNull("triggered_at"),
            rs.getBigDecimal("triggered_price"),
        )
}

@Component
class WatchlistInterest(
    private val jdbc: JdbcClient,
) : MarketInterest {
    override fun instrumentIds(): Set<UUID> =
        jdbc
            .sql(
                """
                select distinct instrument_id from watchlist_items wi join watchlists w on w.id = wi.watchlist_id where w.archived_at is null
                union select distinct instrument_id from price_alerts where status = 'ACTIVE'
                """.trimIndent(),
            ).query(UUID::class.java)
            .list()
            .toSet()
}
