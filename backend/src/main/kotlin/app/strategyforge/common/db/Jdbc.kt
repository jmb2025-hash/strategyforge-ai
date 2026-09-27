package app.strategyforge.common.db

import java.math.BigDecimal
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/** JDBC helpers: all timestamps are stored as timestamptz in UTC (NFR-001). */
fun ts(i: Instant?): OffsetDateTime? = i?.atOffset(ZoneOffset.UTC)

fun ResultSet.instant(col: String): Instant = getObject(col, OffsetDateTime::class.java).toInstant()

fun ResultSet.instantOrNull(col: String): Instant? = getObject(col, OffsetDateTime::class.java)?.toInstant()

fun ResultSet.uuid(col: String): UUID = getObject(col, UUID::class.java)

fun ResultSet.uuidOrNull(col: String): UUID? = getObject(col, UUID::class.java)

fun ResultSet.decimal(col: String): BigDecimal = getBigDecimal(col)

fun ResultSet.decimalOrNull(col: String): BigDecimal? = getBigDecimal(col)

fun ResultSet.stringOrNull(col: String): String? = getString(col)

fun ResultSet.intOrNull(col: String): Int? = getInt(col).let { if (wasNull()) null else it }

fun ResultSet.longOrNull(col: String): Long? = getLong(col).let { if (wasNull()) null else it }

fun ResultSet.boolOrNull(col: String): Boolean? = getBoolean(col).let { if (wasNull()) null else it }
