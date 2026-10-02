package com.fitkingia.app.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.fitkingia.knowledge.sql.SqlDatabase
import com.fitkingia.knowledge.sql.SqlRow
import com.fitkingia.knowledge.sql.SqlScript

/** [SqlDatabase] sobre o SQLite do Android — mesmo contrato da versão JDBC usada nos testes. */
class AndroidSqlDatabase(val db: SQLiteDatabase) : SqlDatabase {

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> {
        val bind = args.map { a ->
            requireNotNull(SqlScript.normalize(a)) { "argumento nulo em consulta: $sql" }.toString()
        }.toTypedArray()
        db.rawQuery(sql, bind).use { c ->
            val row = CursorRow(c)
            val out = ArrayList<T>(c.count)
            while (c.moveToNext()) out.add(map(row))
            return out
        }
    }

    override fun execute(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.map(SqlScript::normalize).toTypedArray())
    }

    override fun insert(sql: String, args: List<Any?>): Long {
        db.compileStatement(sql).use { st ->
            args.forEachIndexed { i, raw ->
                when (val a = SqlScript.normalize(raw)) {
                    null -> st.bindNull(i + 1)
                    is Long -> st.bindLong(i + 1, a)
                    is Double -> st.bindDouble(i + 1, a)
                    is Float -> st.bindDouble(i + 1, a.toDouble())
                    else -> st.bindString(i + 1, a.toString())
                }
            }
            return st.executeInsert()
        }
    }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val r = block()
            db.setTransactionSuccessful()
            return r
        } finally {
            db.endTransaction()
        }
    }

    private class CursorRow(private val c: Cursor) : SqlRow {
        private fun idx(col: String) = c.getColumnIndexOrThrow(col)
        override fun strOrNull(col: String): String? = idx(col).let { if (c.isNull(it)) null else c.getString(it) }
        override fun longOrNull(col: String): Long? = idx(col).let { if (c.isNull(it)) null else c.getLong(it) }
        override fun doubleOrNull(col: String): Double? = idx(col).let { if (c.isNull(it)) null else c.getDouble(it) }
    }
}
