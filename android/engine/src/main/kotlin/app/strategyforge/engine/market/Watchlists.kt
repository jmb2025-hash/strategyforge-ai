package app.strategyforge.engine.market

import app.strategyforge.engine.common.AuditCategory
import app.strategyforge.engine.common.AuditService
import app.strategyforge.engine.common.Problems
import app.strategyforge.engine.db.Db
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.dec
import app.strategyforge.engine.db.decOrNull
import app.strategyforge.engine.db.instant
import app.strategyforge.engine.db.instantOrNull
import app.strategyforge.engine.db.str
import app.strategyforge.engine.db.uuid
import app.strategyforge.engine.notifications.NotificationCategory
import app.strategyforge.engine.notifications.NotificationService
import app.strategyforge.engine.notifications.Severity
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

/** Instruments whose recent 1-minute volume the execution simulator needs (live mode ingestion). */
fun interface VolumeInterest {
    fun instrumentIds(): Set<UUID>
}

/** Instruments the ingestion job must keep fresh. Each module contributes its own set. */
fun interface MarketInterest {
    fun instrumentIds(): Set<UUID>
}

class WatchlistService(
    private val db: Db,
    private val instruments: InstrumentService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    fun list(): List<Watchlist> = db.sql("select * from watchlists where archived_at is null order by created_at").list { rs -> map(rs) }

    fun get(id: UUID): Watchlist =
        db
            .sql("select * from watchlists where id = :id")
            .param("id", id)
            .firstOrNull { rs -> map(rs) } ?: throw Problems.notFound("Watchlist", id)

    fun create(
        name: String,
        symbols: List<String>,
    ): Watchlist = db.tx { createInTx(name, symbols) }

    private fun createInTx(
        name: String,
        symbols: List<String>,
    ): Watchlist {
        if (name.isBlank() || name.length > 60) throw Problems.badRequest("invalid-name", "Watchlist name must be 1-60 characters")
        if (symbols.size > MAX_ITEMS) throw Problems.badRequest("too-many-items", "A watchlist holds at most $MAX_ITEMS instruments")
        val id = UUID.randomUUID()
        db
            .sql("insert into watchlists(id, name, created_at) values (:id, :n, :now)")
            .param("id", id)
            .param("n", name.trim())
            .param("now", (clock.instant()))
            .update()
        symbols.distinct().forEach { addItem(id, it, audited = false) }
        audit.record(AuditCategory.MARKET_DATA, "WATCHLIST_CREATED", entityType = "Watchlist", entityId = id, details = mapOf("name" to name, "symbols" to symbols))
        return get(id)
    }

    fun addItem(
        id: UUID,
        symbol: String,
        audited: Boolean = true,
    ): Watchlist {
        val w = get(id)
        if (w.archivedAt != null) throw Problems.conflict("watchlist-archived", "Watchlist is archived")
        if (w.items.size >= MAX_ITEMS) throw Problems.badRequest("too-many-items", "A watchlist holds at most $MAX_ITEMS instruments")
        val i = instruments.bySymbol(symbol)
        db
            .sql("insert or ignore into watchlist_items(watchlist_id, instrument_id, added_at) values (:w, :i, :now)")
            .param("w", id)
            .param("i", i.id)
            .param("now", (clock.instant()))
            .update()
        if (audited) audit.record(AuditCategory.MARKET_DATA, "WATCHLIST_ITEM_ADDED", entityType = "Watchlist", entityId = id, details = mapOf("symbol" to i.symbol))
        return get(id)
    }

    fun removeItem(
        id: UUID,
        symbol: String,
    ): Watchlist {
        val i = instruments.bySymbol(symbol)
        db
            .sql("delete from watchlist_items where watchlist_id = :w and instrument_id = :i")
            .param("w", id)
            .param("i", i.id)
            .update()
        audit.record(AuditCategory.MARKET_DATA, "WATCHLIST_ITEM_REMOVED", entityType = "Watchlist", entityId = id, details = mapOf("symbol" to i.symbol))
        return get(id)
    }

    fun archive(id: UUID) {
        get(id)
        db
            .sql("update watchlists set archived_at = :now, version = version + 1 where id = :id")
            .param("now", (clock.instant()))
            .param("id", id)
            .update()
        audit.record(AuditCategory.MARKET_DATA, "WATCHLIST_ARCHIVED", entityType = "Watchlist", entityId = id)
    }

    private fun map(rs: Row): Watchlist {
        val id = rs.uuid("id")
        val items =
            db
                .sql("select i.symbol, i.id, w.added_at from watchlist_items w join instruments i on i.id = w.instrument_id where w.watchlist_id = :w order by i.symbol")
                .param("w", id)
                .list { r -> WatchlistItem(r.str("symbol"), r.uuid("id"), r.instant("added_at")) }
        return Watchlist(id, rs.str("name"), items, rs.instant("created_at"), rs.instantOrNull("archived_at"), rs.long("version") ?: 0L)
    }

    companion object {
        const val MAX_ITEMS = 200
    }
}

class PriceAlertService(
    private val db: Db,
    private val instruments: InstrumentService,
    private val notifications: NotificationService,
    private val audit: AuditService,
    private val clock: Clock,
) {
    fun list(status: String?): List<PriceAlert> =
        db
            .sql("select a.*, i.symbol from price_alerts a join instruments i on i.id = a.instrument_id where (:s is null or a.status = :s) order by a.created_at desc")
            .param("s", status)
            .list { rs -> map(rs) }

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
        db
            .sql("insert into price_alerts(id, instrument_id, condition, threshold, status, note, created_at) values (:id, :i, :c, :t, 'ACTIVE', :n, :now)")
            .param("id", id)
            .param("i", i.id)
            .param("c", condition)
            .param("t", threshold)
            .param("n", note?.take(200))
            .param("now", (clock.instant()))
            .update()
        audit.record(AuditCategory.MARKET_DATA, "PRICE_ALERT_CREATED", entityType = "PriceAlert", entityId = id, details = mapOf("symbol" to i.symbol, "condition" to condition, "threshold" to threshold))
        return get(id)
    }

    fun get(id: UUID): PriceAlert =
        db
            .sql("select a.*, i.symbol from price_alerts a join instruments i on i.id = a.instrument_id where a.id = :id")
            .param("id", id)
            .firstOrNull { rs -> map(rs) } ?: throw Problems.notFound("Price alert", id)

    fun cancel(id: UUID): PriceAlert {
        val n = db.sql("update price_alerts set status = 'CANCELLED', version = version + 1 where id = :id and status = 'ACTIVE'").param("id", id).update()
        if (n == 0) throw Problems.conflict("alert-not-active", "Only active alerts can be cancelled")
        audit.record(AuditCategory.MARKET_DATA, "PRICE_ALERT_CANCELLED", entityType = "PriceAlert", entityId = id)
        return get(id)
    }

    /** Evaluated on every stored quote; each alert fires once. */
    fun evaluate(
        instrument: Instrument,
        quote: StoredQuote,
    ) {
        // Thresholds are decimal text, so the comparison happens here rather than in SQL.
        val triggered =
            db
                .sql("select id, condition, threshold from price_alerts where instrument_id = :i and status = 'ACTIVE'")
                .param("i", instrument.id)
                .list { rs -> Triple(rs.uuid("id"), rs.str("condition"), rs.dec("threshold")) }
                .filter { (_, cond, threshold) -> if (cond == "ABOVE") quote.last >= threshold else quote.last <= threshold }
        triggered.forEach { (id, _, _) ->
            db
                .sql("update price_alerts set status = 'TRIGGERED', triggered_at = :now, triggered_price = :p, version = version + 1 where id = :id and status = 'ACTIVE'")
                .param("now", clock.instant())
                .param("p", quote.last)
                .param("id", id)
                .update()
        }
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

    private fun map(rs: Row) =
        PriceAlert(
            rs.uuid("id"),
            rs.str("symbol"),
            rs.uuid("instrument_id"),
            rs.str("condition"),
            rs.dec("threshold"),
            rs.str("status"),
            rs.string("note"),
            rs.instant("created_at"),
            rs.instantOrNull("triggered_at"),
            rs.decOrNull("triggered_price"),
        )
}

class WatchlistInterest(
    private val db: Db,
) : MarketInterest {
    override fun instrumentIds(): Set<UUID> =
        db
            .sql(
                """
                select distinct instrument_id from watchlist_items wi join watchlists w on w.id = wi.watchlist_id where w.archived_at is null
                union select distinct instrument_id from price_alerts where status = 'ACTIVE'
                """.trimIndent(),
            ).list { it.uuid("instrument_id") }
            .toSet()
}
