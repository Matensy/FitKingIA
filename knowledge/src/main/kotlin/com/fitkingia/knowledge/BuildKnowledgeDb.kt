package com.fitkingia.knowledge

import com.fitkingia.core.knowledge.KnowledgeBase
import java.io.File
import kotlin.system.exitProcess

/** Carrega o conhecimento empacotado: seeds do classpath → SQLite → domínio. */
object BundledKnowledge {
    @Volatile private var cached: KnowledgeBase? = null

    fun load(): KnowledgeBase = cached ?: synchronized(this) {
        cached ?: run {
            val tmp = File.createTempFile("fitness", ".db").also { it.deleteOnExit() }
            KnowledgeDbBuilder.build(Seeds.fromClasspath(), tmp)
            SqliteKnowledgeRepository(tmp).load().also { cached = it }
        }
    }
}

/** `./gradlew :knowledge:buildKnowledgeDb` — gera o fitness.db e imprime o relatório de integridade. */
fun main(args: Array<String>) {
    val target = File(args.firstOrNull() ?: "build/fitness.db")
    val seeds = Seeds.fromClasspath()
    KnowledgeDbBuilder.build(seeds, target)
    val kb = SqliteKnowledgeRepository(target).load()
    val issues = KnowledgeValidator.validate(kb)
    println("fitness.db gerado em ${target.absolutePath}")
    println("conteúdo ${kb.meta["content_version"]}: ${kb.exercises.size} exercícios, ${kb.sources.size} fontes, " +
        "${kb.claims.size} afirmações (${kb.claims.count { it.isConflicting }} com evidência conflitante), ${kb.rules.size} regras, " +
        "${kb.splits.size} divisões, ${kb.foods.size} alimentos, ${kb.supplements.size} suplementos")
    issues.forEach(::println)
    if (issues.any { it.severity == Severity.ERROR }) exitProcess(1)
}
