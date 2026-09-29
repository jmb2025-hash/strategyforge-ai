package app.strategyforge.android.platform

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteProgram
import app.strategyforge.engine.db.Row
import app.strategyforge.engine.db.SqlBackend

/**
 * The engine's [SqlBackend] over Android's built-in SQLite (D-028). Arguments are bound with their
 * real types (text or integer), for queries too, so comparisons behave exactly as in the JDBC
 * backend the engine is tested with. Used only from the engine thread.
 */
class AndroidSqlBackend(
    context: Context,
    name: String = DB_NAME,
) : SqlBackend {
    private val helper =
        object : SQLiteOpenHelper(context, name, null, 1) {
            override fun onConfigure(db: SQLiteDatabase) {
                db.setForeignKeyConstraintsEnabled(true)
                db.enableWriteAheadLogging()
            }

            // The engine creates its own schema (Db.migrate) on first use.
            override fun onCreate(db: SQLiteDatabase) = Unit

            override fun onUpgrade(
                db: SQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = Unit
        }

    private val db: SQLiteDatabase by lazy { helper.writableDatabase }
    private var depth = 0

    override fun execute(
        sql: String,
        args: List<Any?>,
    ): Int =
        db.compileStatement(sql).use { st ->
            bind(st, args)
            st.executeUpdateDelete()
        }

    override fun <T> query(
        sql: String,
        args: List<Any?>,
        mapper: (Row) -> T,
    ): List<T> {
        val factory =
            SQLiteDatabase.CursorFactory { _, driver, editTable, query ->
                bind(query, args)
                SQLiteCursor(driver, editTable, query)
            }
        return db.rawQueryWithFactory(factory, sql, null, null).use { c ->
            val row = CursorRow(c)
            buildList { while (c.moveToNext()) add(mapper(row)) }
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
        db.beginTransaction()
        depth = 1
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            depth = 0
            db.endTransaction()
        }
    }

    fun close() = helper.close()

    private fun bind(
        p: SQLiteProgram,
        args: List<Any?>,
    ) {
        args.forEachIndexed { i, v ->
            when (v) {
                null -> p.bindNull(i + 1)
                is Long -> p.bindLong(i + 1, v)
                is String -> p.bindString(i + 1, v)
                else -> error("Unsupported bound type ${v::class.java}")
            }
        }
    }

    private class CursorRow(
        private val c: Cursor,
    ) : Row {
        override fun string(column: String): String? = c.getColumnIndexOrThrow(column).let { if (c.isNull(it)) null else c.getString(it) }

        override fun long(column: String): Long? = c.getColumnIndexOrThrow(column).let { if (c.isNull(it)) null else c.getLong(it) }

        override fun longAt(index: Int): Long? = if (c.isNull(index)) null else c.getLong(index)

        override fun columns(): List<String> = c.columnNames.toList()

        override fun valueAt(index: Int): Any? =
            when (c.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> null
                Cursor.FIELD_TYPE_INTEGER -> c.getLong(index)
                else -> c.getString(index)
            }
    }

    companion object {
        const val DB_NAME = "strategyforge-engine.db"
    }
}
