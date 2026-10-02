package com.fitkingia.knowledge.sql

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/** [SqlDatabase] sobre JDBC (sqlite-jdbc). Usado na JVM: geração do banco, CLI e testes. */
class JdbcSqlDatabase(private val connection: Connection) : SqlDatabase, AutoCloseable {

    override fun <T> query(sql: String, args: List<Any?>, map: (SqlRow) -> T): List<T> =
        connection.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, SqlScript.normalize(a)) }
            ps.executeQuery().use { rs ->
                val row = Row(rs)
                buildList { while (rs.next()) add(map(row)) }
            }
        }

    override fun execute(sql: String, args: List<Any?>) {
        connection.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, SqlScript.normalize(a)) }
            ps.execute()
        }
    }

    override fun insert(sql: String, args: List<Any?>): Long {
        execute(sql, args)
        return connection.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    override fun <T> transaction(block: () -> T): T {
        if (!connection.autoCommit) return block() // transação aninhada: participa da externa
        connection.autoCommit = false
        try {
            val r = block()
            connection.commit()
            return r
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    override fun close() = connection.close()

    private class Row(private val rs: ResultSet) : SqlRow {
        override fun strOrNull(col: String): String? = rs.getString(col)
        override fun longOrNull(col: String): Long? = rs.getLong(col).let { if (rs.wasNull()) null else it }
        override fun doubleOrNull(col: String): Double? = rs.getDouble(col).let { if (rs.wasNull()) null else it }
    }

    companion object {
        fun open(file: File): JdbcSqlDatabase = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"))
        fun inMemory(): JdbcSqlDatabase = JdbcSqlDatabase(DriverManager.getConnection("jdbc:sqlite::memory:"))
    }
}
