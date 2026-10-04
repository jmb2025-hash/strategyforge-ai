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

    /** Column names of the result, for whole-table copies (backups). */
    fun columns(): List<String>

    /** Raw value by position: String, Long or null (the schema stores only TEXT and INTEGER). */
    fun valueAt(index: Int): Any?
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

    /**
     * Creates the base schema (db/schema.sql) on first use, then applies every newer [Migration] in
     * order. New installs and upgraded phones take the same path, each step in its own transaction.
     */
    fun migrate() {
        val exists = sql("select count(*) n from sqlite_master where type = 'table' and name = 'settings'").long() > 0
        if (!exists) tx { schemaStatements().forEach { backend.execute(it, emptyList()) } }
        val current = schemaVersion()
        MIGRATIONS.filter { it.version > current }.sortedBy { it.version }.forEach { m ->
            tx {
                m.apply(this)
                setSchemaVersion(m.version)
            }
        }
    }

    fun schemaVersion(): Int = sql("select value from settings where key = :k").param("k", SCHEMA_VERSION_KEY).firstOrNull { it.str("value").toIntOrNull() } ?: 1

    fun setSchemaVersion(version: Int) {
        sql("insert or replace into settings(key, value) values (:k, :v)").param("k", SCHEMA_VERSION_KEY).param("v", version.toString()).update()
    }

    /** Adds a column unless it exists already (restored backups and re-runs stay safe). */
    fun addColumn(
        table: String,
        column: String,
        definition: String,
    ) {
        val present = sql("pragma table_info($table)").list { it.str("name") }
        if (column !in present) sql("alter table $table add column $column $definition").update()
    }

    /** One forward-only schema change; [version] must increase with every new migration. */
    class Migration(
        val version: Int,
        val description: String,
        val apply: (Db) -> Unit,
    )

    companion object {
        const val SCHEMA_VERSION_KEY = "schema_version"

        /** Changes after the base schema (version 1). Never edit a released migration; add a new one. */
        val MIGRATIONS: List<Migration> =
            listOf(
                Migration(2, "Research conversations: the owner's message for each research turn") { db ->
                    db.addColumn("research_runs", "owner_message", "TEXT")
                },
                Migration(3, "Strategy slots: open positions handed over to the strategy that replaced their opener") { db ->
                    db.addColumn("position_lots", "managed_by_strategy_id", "TEXT")
                },
                Migration(4, "Imported research: the part size used to split long pasted research") { db ->
                    db.addColumn("research_sessions", "import_chunk", "INTEGER")
                },
                Migration(5, "Simulated crypto shorts (perpetual-style), when the portfolio enables shorting") { db ->
                    db.sql("update instruments set shortable = 1 where asset_class = 'CRYPTO'").update()
                },
                Migration(6, "Imported strategies: the readback and research notes the owner's AI wrote with them") { db ->
                    db.addColumn("strategies", "import_notes", "TEXT")
                },
                Migration(7, "Trading plans: the setup behind each trade, and the single strategies retired in favour of plans") { db ->
                    db.addColumn("backtest_trades", "setup_id", "TEXT")
                    db.addColumn("signals", "setup_id", "TEXT")
                    // D-045: the owner chose to start fresh with trading plans. Single strategies from earlier
                    // versions stop and leave the library; their trades and portfolio history are kept.
                    db
                        .sql("update strategy_activations set status = 'ENDED', ended_at = :now, end_reason = 'Retired: replaced by trading plans (1.8.0)' where status = 'ACTIVE'")
                        .param("now", java.time.Instant.now())
                        .update()
                    db
                        .sql("update strategies set status = 'ARCHIVED', status_reason = 'Retired: replaced by trading plans (1.8.0)', archived_at = :now, updated_at = :now where status <> 'ARCHIVED'")
                        .param("now", java.time.Instant.now())
                        .update()
                },
                Migration(8, "Chart-level exits: the stop and targets fixed when an entry signal fires") { db ->
                    db.addColumn("signals", "exit_plan", "TEXT")
                },
                Migration(9, "Ten slots per asset class: the slot an activation runs in") { db ->
                    db.addColumn("strategy_activations", "slot", "INTEGER")
                    // Before this, one strategy per asset class ran; number any active ones per asset class in activation order.
                    val active =
                        db
                            .sql("select a.id, s.asset_class from strategy_activations a join strategies s on s.id = a.strategy_id where a.status = 'ACTIVE' order by a.created_at")
                            .list { it.str("id") to it.str("asset_class") }
                    active.groupBy { it.second }.values.forEach { rows ->
                        rows.forEachIndexed { i, (id, _) ->
                            db
                                .sql("update strategy_activations set slot = :n where id = :id")
                                .param("n", minOf(i + 1, 10))
                                .param("id", id)
                                .update()
                        }
                    }
                },
                Migration(10, "TSX portfolio plans: cached TSX history, plan runs in ten slots, their values, events and backtests") { db ->
                    listOf(
                        """
                        create table if not exists tsx_history (
                          symbol TEXT PRIMARY KEY, bars TEXT NOT NULL, dividends TEXT NOT NULL,
                          first_day TEXT, last_day TEXT, updated_at INTEGER NOT NULL)
                        """,
                        """
                        create table if not exists tsx_runs (
                          id TEXT PRIMARY KEY, plan_id TEXT NOT NULL, slot INTEGER NOT NULL, mode TEXT NOT NULL, drip INTEGER NOT NULL,
                          starting_cash TEXT NOT NULL, cash TEXT NOT NULL, holdings TEXT NOT NULL, sleeve_state TEXT NOT NULL,
                          start_day TEXT, last_day TEXT, last_period INTEGER, pending TEXT, pending_day TEXT,
                          status TEXT NOT NULL, created_at INTEGER NOT NULL, stopped_at INTEGER)
                        """,
                        "create index if not exists tsx_runs_active on tsx_runs (status, slot)",
                        """
                        create table if not exists tsx_run_values (
                          run_id TEXT NOT NULL, day TEXT NOT NULL, value TEXT NOT NULL, PRIMARY KEY (run_id, day))
                        """,
                        """
                        create table if not exists tsx_run_events (
                          id TEXT PRIMARY KEY, run_id TEXT NOT NULL, day TEXT NOT NULL, kind TEXT NOT NULL, symbol TEXT,
                          shares TEXT, price TEXT, amount TEXT, created_at INTEGER NOT NULL)
                        """,
                        "create index if not exists tsx_run_events_run on tsx_run_events (run_id, day)",
                        """
                        create table if not exists tsx_backtests (
                          id TEXT PRIMARY KEY, plan_id TEXT NOT NULL, from_day TEXT NOT NULL, to_day TEXT NOT NULL, starting_cash TEXT NOT NULL,
                          status TEXT NOT NULL, result TEXT, error TEXT, created_at INTEGER NOT NULL)
                        """,
                    ).forEach { db.sql(it.trimIndent()).update() }
                },
            )

        val SCHEMA_VERSION: Int get() = MIGRATIONS.maxOfOrNull { it.version } ?: 1

        /** Splits the schema into statements; a statement ends at a line ending with ';'. */
        fun schemaStatements(): List<String> {
            val text =
                Db::class.java
                    .getResourceAsStream("/db/schema.sql")!!
                    .use { String(it.readBytes(), Charsets.UTF_8) }
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
