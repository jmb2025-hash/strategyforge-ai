package app.strategyforge.engine.db

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One result row; columns are read by name (the SQL layer used by every engine service). */
interface Row {
    fun string(column: String): String?

    fun long(column: String): Long?

    /** First-column access for scalar queries (counts), so they need no column alias. */
    fun longAt(index: Int): Long?
}

/**
 * Minimal SQL backend. The engine is written against this interface so the same code runs on
 * SQLite through JDBC (tests, any JVM) and on Android's built-in SQLite (the app).
 * Arguments are already converted to String, Long or null by [Db].
 */
interface SqlBackend {
    fun execute(
        sql: String,
        args: List<Any?>,
    ): Int

    fun <T> query(
        sql: String,
        args: List<Any?>,
        mapper: (Row) -> T,
    ): List<T>

    /** Runs [block] in a transaction; a nested call joins the enclosing transaction. */
    fun <T> transaction(block: () -> T): T
}

/**
 * Named-parameter SQL on top of [SqlBackend], shaped like the server's JdbcClient so ported code
 * stays readable. Money and quantities are stored as exact decimal text and never summed in SQL.
 */
class Db(
    private val backend: SqlBackend,
) {
    fun sql(sql: String) = Statement(backend, sql)

    fun <T> tx(block: () -> T): T = backend.transaction(block)

    /** Creates the schema on first use; statements come from db/schema.sql. */
    fun migrate() {
        val exists = sql("select count(*) n from sqlite_master where type = 'table' and name = 'settings'").long() > 0
        if (exists) return
        tx { schemaStatements().forEach { backend.execute(it, emptyList()) } }
    }

    companion object {
        /** Splits the schema into statements; a statement ends at a line ending with ';'. */
        fun schemaStatements(): List<String> {
            val text =
                Db::class.java
                    .getResourceAsStream("/db/schema.sql")!!
                    .use { String(it.readAllBytes(), Charsets.UTF_8) }
            val out = mutableListOf<String>()
            val current = StringBuilder()
            text.lineSequence().forEach { raw ->
                val line = raw.trimEnd()
                if (line.trimStart().startsWith("--") || line.isBlank()) return@forEach
                current.append(line).append('\n')
                if (line.endsWith(";")) {
                    out += current.toString().trim().removeSuffix(";")
                    current.clear()
                }
            }
            check(current.isBlank()) { "Unterminated statement in schema.sql" }
            return out
        }

        /** Converts a Kotlin value to the storage representation (see schema conventions). */
        fun toSql(v: Any?): Any? =
            when (v) {
                null -> null
                is String -> v
                is BigDecimal -> v.toPlainString()
                is Instant -> v.toEpochMilli()
                is UUID -> v.toString()
                is Boolean -> if (v) 1L else 0L
                is Int -> v.toLong()
                is Long -> v
                is Enum<*> -> v.name
                is LocalDate -> v.toString()
                else -> error("Unsupported SQL parameter type ${v::class.java.simpleName}")
            }
    }
}

class Statement(
    private val backend: SqlBackend,
    private val sql: String,
) {
    private val params = mutableMapOf<String, Any?>()

    fun param(
        name: String,
        value: Any?,
    ): Statement {
        params[name] = value
        return this
    }

    private fun bound(): Pair<String, List<Any?>> {
        UNSUPPORTED.firstOrNull { it.containsMatchIn(sql) }?.let { error("SQL uses syntax newer than SQLite 3.22 (Android 10): ${it.pattern}") }
        val args = mutableListOf<Any?>()
        val out = StringBuilder()
        var i = 0
        var quoted = false
        while (i < sql.length) {
            val c = sql[i]
            if (c == '\'') quoted = !quoted
            if (!quoted && c == ':' && i + 1 < sql.length && (sql[i + 1].isLetter() || sql[i + 1] == '_')) {
                var j = i + 1
                while (j < sql.length && (sql[j].isLetterOrDigit() || sql[j] == '_')) j++
                val name = sql.substring(i + 1, j)
                require(params.containsKey(name)) { "Missing SQL parameter :$name" }
                val v = params[name]
                if (v is Collection<*>) {
                    require(v.isNotEmpty()) { "Empty collection for :$name" }
                    out.append(v.joinToString(",") { "?" })
                    v.forEach { args += Db.toSql(it) }
                } else {
                    out.append('?')
                    args += Db.toSql(v)
                }
                i = j
                continue
            }
            out.append(c)
            i++
        }
        return out.toString() to args
    }

    fun update(): Int = bound().let { (s, a) -> backend.execute(s, a) }

    fun <T> list(mapper: (Row) -> T): List<T> = bound().let { (s, a) -> backend.query(s, a, mapper) }

    fun <T> firstOrNull(mapper: (Row) -> T): T? = list(mapper).firstOrNull()

    fun <T> single(mapper: (Row) -> T): T =
        list(mapper).let {
            check(it.size == 1) { "Expected one row, got ${it.size}" }
            it[0]
        }

    /** First column of a single row as a number (counts). */
    fun long(): Long = single { it.longAt(0) ?: 0L }

    fun int(): Int = long().toInt()

    companion object {
        /** minSdk 29 ships SQLite 3.22: no RETURNING (3.35), no upsert clauses (3.24), no window functions (3.25). */
        private val UNSUPPORTED =
            listOf(
                Regex("(?i)\\breturning\\b"),
                Regex("(?i)\\bon\\s+conflict\\b"),
                Regex("(?i)\\bover\\s*\\("),
                Regex("(?i)\\bnulls\\s+(first|last)\\b"),
                Regex("::"),
            )
    }
}

// ------------------------------------------------------------------ typed column readers

fun Row.str(c: String): String = string(c) ?: error("Column $c is null")

fun Row.dec(c: String): BigDecimal = BigDecimal(str(c))

fun Row.decOrNull(c: String): BigDecimal? = string(c)?.let(::BigDecimal)

fun Row.instant(c: String): Instant = Instant.ofEpochMilli(long(c) ?: error("Column $c is null"))

fun Row.instantOrNull(c: String): Instant? = long(c)?.let(Instant::ofEpochMilli)

fun Row.uuid(c: String): UUID = UUID.fromString(str(c))

fun Row.uuidOrNull(c: String): UUID? = string(c)?.let(UUID::fromString)

fun Row.bool(c: String): Boolean = (long(c) ?: 0L) != 0L

fun Row.int(c: String): Int = (long(c) ?: error("Column $c is null")).toInt()

fun Row.intOrNull(c: String): Int? = long(c)?.toInt()

fun Row.date(c: String): LocalDate = LocalDate.parse(str(c))

fun Row.dateOrNull(c: String): LocalDate? = string(c)?.let(LocalDate::parse)
