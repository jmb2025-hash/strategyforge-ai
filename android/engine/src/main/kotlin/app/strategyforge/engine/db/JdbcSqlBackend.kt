package app.strategyforge.engine.db

import java.sql.Connection
import java.sql.ResultSet
import java.sql.Types

/** [SqlBackend] over a JDBC connection to SQLite (tests and any JVM host). Not thread-safe by design: the engine is single-threaded. */
class JdbcSqlBackend(
    private val connection: Connection,
) : SqlBackend {
    private var depth = 0

    init {
        connection.createStatement().use {
            it.execute("PRAGMA foreign_keys = ON")
        }
    }

    override fun execute(
        sql: String,
        args: List<Any?>,
    ): Int =
        connection.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeUpdate()
        }

    override fun <T> query(
        sql: String,
        args: List<Any?>,
        mapper: (Row) -> T,
    ): List<T> =
        connection.prepareStatement(sql).use { ps ->
            bind(ps, args)
            ps.executeQuery().use { rs ->
                val row = ResultSetRow(rs)
                buildList { while (rs.next()) add(mapper(row)) }
            }
        }

    override fun <T> transaction(block: () -> T): T {
        if (depth > 0) {
            depth++
            try {
                return block()
            } finally {
                depth--
            }
        }
        connection.autoCommit = false
        depth = 1
        try {
            val r = block()
            connection.commit()
            return r
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            depth = 0
            connection.autoCommit = true
        }
    }

    private fun bind(
        ps: java.sql.PreparedStatement,
        args: List<Any?>,
    ) {
        args.forEachIndexed { i, v ->
            when (v) {
                null -> ps.setNull(i + 1, Types.NULL)
                is Long -> ps.setLong(i + 1, v)
                is String -> ps.setString(i + 1, v)
                else -> error("Unsupported bound type ${v::class.java}")
            }
        }
    }

    private class ResultSetRow(
        private val rs: ResultSet,
    ) : Row {
        override fun string(column: String): String? = rs.getString(column)

        override fun long(column: String): Long? = rs.getLong(column).let { if (rs.wasNull()) null else it }

        override fun longAt(index: Int): Long? = rs.getLong(index + 1).let { if (rs.wasNull()) null else it }

        override fun columns(): List<String> = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }

        override fun valueAt(index: Int): Any? =
            when (val v = rs.getObject(index + 1)) {
                null -> null
                is Int -> v.toLong()
                is Long, is String -> v
                is Number -> v.toLong()
                else -> v.toString()
            }
    }
}
