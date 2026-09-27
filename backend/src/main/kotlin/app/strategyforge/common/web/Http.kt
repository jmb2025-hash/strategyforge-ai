package app.strategyforge.common.web

import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Optimistic concurrency via weak ETags carrying the row version (API standards). */
object ETags {
    fun of(version: Long): String = "W/\"$version\""

    /** Parses If-Match; requires it to be present and equal to [current]. */
    fun require(
        ifMatch: String?,
        current: Long,
    ) {
        if (ifMatch.isNullOrBlank()) throw Problems.preconditionRequired("If-Match header with the current ETag is required")
        val v =
            ifMatch
                .trim()
                .removePrefix("W/")
                .trim('"')
                .toLongOrNull() ?: throw Problems.preconditionFailed("Malformed If-Match header")
        if (v != current) throw Problems.preconditionFailed("Resource version is $current but If-Match was $v; reload and retry")
    }

    fun <T> ok(
        body: T,
        version: Long,
    ): ResponseEntity<T> = ResponseEntity.ok().header(HttpHeaders.ETAG, of(version)).body(body)

    fun <T> created(
        body: T,
        version: Long,
    ): ResponseEntity<T> = ResponseEntity.status(HttpStatus.CREATED).header(HttpHeaders.ETAG, of(version)).body(body)
}

/** Opaque cursor for (timestamp, id) keyset pagination of histories. */
data class Cursor(
    val at: Instant,
    val id: String,
) {
    fun encode(): String = Base64.getUrlEncoder().withoutPadding().encodeToString("${at.toEpochMilli()}:${at.nano}:$id".toByteArray(StandardCharsets.UTF_8))

    companion object {
        fun decode(s: String?): Cursor? {
            if (s.isNullOrBlank()) return null
            return try {
                val parts = String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8).split(":", limit = 3)
                val millis = parts[0].toLong()
                val nanos = parts[1].toInt()
                Cursor(Instant.ofEpochSecond(Math.floorDiv(millis, 1000L), nanos.toLong()), parts[2])
            } catch (ignored: Exception) {
                throw Problems.badRequest("invalid-cursor", "Pagination cursor is invalid")
            }
        }
    }
}

data class PageResponse<T>(
    val items: List<T>,
    val nextCursor: String?,
)

object Paging {
    const val DEFAULT_LIMIT = 50
    const val MAX_LIMIT = 200

    fun limit(requested: Int?): Int = (requested ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)

    /** Given limit+1 fetched rows, returns a page and next cursor. */
    fun <T> page(
        rows: List<T>,
        limit: Int,
        cursorOf: (T) -> Cursor,
    ): PageResponse<T> {
        val items = rows.take(limit)
        val next = if (rows.size > limit && items.isNotEmpty()) cursorOf(items.last()).encode() else null
        return PageResponse(items, next)
    }
}

fun parseUuid(
    s: String,
    what: String = "id",
): UUID =
    try {
        UUID.fromString(s)
    } catch (e: IllegalArgumentException) {
        throw Problems.badRequest("invalid-id", "Invalid $what")
    }
