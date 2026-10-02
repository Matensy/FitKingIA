package com.fitkingia.knowledge.sql

/**
 * Acesso mínimo a SQLite, comum à JVM (JDBC, testes e CLI) e ao Android (SQLiteDatabase do
 * sistema). Os leitores de banco dependem só disto — o mesmo código carrega o fitness.db e
 * grava o user.db nas duas plataformas.
 */
interface SqlRow {
    fun strOrNull(col: String): String?
    fun longOrNull(col: String): Long?
    fun doubleOrNull(col: String): Double?

    fun str(col: String): String = strOrNull(col) ?: error("Coluna $col nula")
    fun intOrNull(col: String): Int? = longOrNull(col)?.toInt()
    fun int(col: String): Int = intOrNull(col) ?: error("Coluna $col nula")
    fun long(col: String): Long = longOrNull(col) ?: error("Coluna $col nula")
    fun double(col: String): Double = doubleOrNull(col) ?: error("Coluna $col nula")
    fun bool(col: String): Boolean = int(col) != 0
}

interface SqlDatabase {
    /** Argumentos aceitos: String, Int, Long, Double, Boolean (0/1) e null. */
    fun <T> query(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): List<T>

    fun execute(sql: String, args: List<Any?> = emptyList())

    /** Executa um INSERT e devolve o rowid gerado. */
    fun insert(sql: String, args: List<Any?> = emptyList()): Long

    fun <T> transaction(block: () -> T): T

    fun <T> single(sql: String, args: List<Any?> = emptyList(), map: (SqlRow) -> T): T? = query(sql, args, map).firstOrNull()

    fun runScript(script: String) = SqlScript.statements(script).forEach { execute(it) }
}

object SqlScript {
    /** Separa um script em comandos (sem triggers com ';' internos); ignora linhas de comentário. */
    fun statements(script: String): List<String> =
        script.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n")
            .split(";").map { it.trim() }.filter { it.isNotEmpty() }

    /** Converte argumentos para os tipos que os drivers SQLite entendem. */
    fun normalize(arg: Any?): Any? = when (arg) {
        is Boolean -> if (arg) 1L else 0L
        is Int -> arg.toLong()
        is Enum<*> -> arg.name
        else -> arg
    }
}
