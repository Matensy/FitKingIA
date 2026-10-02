package com.fitkingia.coach

import com.fitkingia.coach.nlu.Intent
import com.fitkingia.coach.nlu.IntentModel
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Avaliação do entendimento em frases que NÃO estão no corpus de treino.
 * Mede o classificador puro e o pipeline completo (classificador + regras + entidades).
 */
abstract class IntentEvalBase(private val file: String) {
    protected val cases: List<Pair<String, Intent>> =
        javaClass.getResourceAsStream(file)!!.bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { it.split('\t').let { (t, i) -> t to Intent.valueOf(i) } }

    @Test fun `classificador puro acerta a maioria das frases nunca vistas`() {
        val model = IntentModel.default(com.fitkingia.coach.nlu.EntityExtractor(CoachFixture.kb)::tags)
        val wrong = cases.filter { (t, i) -> model.predict(t).intent != i }
        val acc = 1.0 - wrong.size.toDouble() / cases.size
        println("[$file] Acurácia do classificador: ${"%.1f".format(acc * 100)}% (${cases.size - wrong.size}/${cases.size})")
        wrong.forEach { (t, i) -> println("  ✗ \"$t\" → ${model.predict(t).intent} (esperado $i)") }
        assertTrue(acc >= MIN_MODEL_ACCURACY, "acurácia ${acc} < $MIN_MODEL_ACCURACY")
    }

    @Test fun `pipeline completo entende ou pergunta, quase nunca erra calado`() {
        val coach = LocalCoach(CoachFixture.kb)
        var correct = 0; var asked = 0
        val wrong = mutableListOf<String>()
        for ((t, expected) in cases) {
            val r = coach.reply(t, CoachFixture.context(), ConversationState())
            when (r.intent) {
                expected -> correct++
                null -> asked++
                else -> wrong += "\"$t\" → ${r.intent} (esperado $expected)"
            }
        }
        val acc = correct.toDouble() / cases.size
        println("[$file] Pipeline: ${correct} certas, ${asked} esclarecimentos, ${wrong.size} erradas de ${cases.size}")
        wrong.forEach { println("  ✗ $it") }
        assertTrue(acc >= MIN_PIPELINE_ACCURACY, "acurácia do pipeline $acc")
        assertTrue(wrong.size.toDouble() / cases.size <= MAX_SILENT_ERRORS, "erros silenciosos demais: ${wrong.size}")
    }

    companion object {
        const val MIN_MODEL_ACCURACY = 0.85
        const val MIN_PIPELINE_ACCURACY = 0.85
        const val MAX_SILENT_ERRORS = 0.08
    }
}

/** Conjunto usado na análise de erros durante o desenvolvimento. */
class IntentDevSetTest : IntentEvalBase("/coach/intent_dev.tsv")

/** Conjunto de teste independente: o número que vale para reportar. */
class IntentTestSetTest : IntentEvalBase("/coach/intent_test.tsv")
