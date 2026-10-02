package com.fitkingia.coach.nlu

import com.fitkingia.coach.text.PtText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.ln

@Serializable
data class IntentExamples(val intent: String, val examples: List<String>)

@Serializable
data class IntentCorpus(val version: String, val intents: List<IntentExamples>)

data class Prediction(val intent: Intent, val confidence: Double, val ranking: List<Pair<Intent, Double>>) {
    /** Segunda opção quando a dúvida é real (usado para perguntar "você quis dizer…?"). */
    val runnerUp: Pair<Intent, Double>? get() = ranking.getOrNull(1)
}

/**
 * Classificador de intenções Naive Bayes multinomial — pequeno, rápido, determinístico e treinável no
 * próprio aparelho. Atributos: radicais (unigramas), bigramas de radicais e trigramas de caracteres
 * (estes deixam o modelo robusto a erros de digitação como "agaxamento" ou "joleho").
 *
 * Também aprende com o usuário: [learn] adiciona frases confirmadas ao modelo (ex.: quando ele escolhe
 * uma opção depois de uma pergunta de esclarecimento).
 */
class IntentModel private constructor(private val alpha: Double, private val tagger: (String) -> Set<String>) {
    private val featureCounts = HashMap<Intent, HashMap<String, Double>>()
    private val totalPerClass = HashMap<Intent, Double>()
    private val docsPerClass = HashMap<Intent, Int>()
    private val vocabulary = HashSet<String>()
    private var totalDocs = 0

    fun train(intent: Intent, utterance: String) {
        val counts = featureCounts.getOrPut(intent) { HashMap() }
        for ((f, w) in features(utterance, tagger(utterance))) {
            counts.merge(f, w, Double::plus)
            totalPerClass.merge(intent, w, Double::plus)
            vocabulary += f
        }
        docsPerClass.merge(intent, 1, Int::plus)
        totalDocs++
    }

    /** Aprendizado on-device: a frase passa a contar como exemplo da intenção confirmada. */
    fun learn(utterance: String, intent: Intent) = train(intent, utterance)

    fun predict(utterance: String): Prediction {
        val feats = features(utterance, tagger(utterance))
        val v = vocabulary.size.toDouble()
        val logScores = Intent.entries.filter { (docsPerClass[it] ?: 0) > 0 }.associateWith { c ->
            val counts = featureCounts.getValue(c)
            val denom = totalPerClass.getValue(c) + alpha * v
            var s = ln(docsPerClass.getValue(c).toDouble() / totalDocs)
            for ((f, w) in feats) {
                if (f !in vocabulary) continue // atributo nunca visto não informa nada
                s += w * ln(((counts[f] ?: 0.0) + alpha) / denom)
            }
            s
        }
        // Softmax com temperatura calibrada no conjunto de avaliação (ver IntentEvalTest).
        val max = logScores.values.max()
        val exps = logScores.mapValues { exp((it.value - max) / TEMPERATURE) }
        val z = exps.values.sum()
        val ranking = exps.map { it.key to it.value / z }.sortedByDescending { it.second }
        return Prediction(ranking.first().first, ranking.first().second, ranking)
    }

    val trainedIntents: Set<Intent> get() = docsPerClass.keys
    val exampleCount: Int get() = totalDocs

    companion object {
        private const val TEMPERATURE = 1.5
        private const val TAG_WEIGHT = 2.0

        /**
         * Atributos: radicais, bigramas, trigramas de caracteres e "etiquetas" de entidades detectadas
         * (ex.: `t:supplement` quando a frase cita um suplemento) — o que a frase *contém* ajuda a decidir o que ela *pede*.
         */
        fun features(utterance: String, tags: Set<String> = emptySet()): List<Pair<String, Double>> {
            val stems = PtText.contentStems(utterance)
            val out = ArrayList<Pair<String, Double>>()
            tags.forEach { out += "t:$it" to TAG_WEIGHT }
            stems.forEach { out += "w:$it" to 1.0 }
            stems.zipWithNext().forEach { (a, b) -> out += "b:${a}_$b" to 1.0 }
            PtText.tokens(utterance).filter { it !in PtText.STOPWORDS && it.length >= 3 && it.none(Char::isDigit) }
                .forEach { tok -> PtText.charGrams(tok).forEach { out += "c:$it" to 0.3 } }
            return out
        }

        fun fromCorpus(corpus: IntentCorpus, alpha: Double = 0.5, tagger: (String) -> Set<String> = { emptySet() }): IntentModel {
            val m = IntentModel(alpha, tagger)
            for (group in corpus.intents) {
                val intent = Intent.valueOf(group.intent)
                group.examples.forEach { m.train(intent, it) }
            }
            return m
        }

        fun loadCorpus(): IntentCorpus {
            val text = IntentModel::class.java.getResourceAsStream("/coach/intents.json")?.bufferedReader()?.readText()
                ?: error("Corpus /coach/intents.json não encontrado")
            return Json.decodeFromString(text)
        }

        /** Modelo padrão treinado com o corpus embutido (centenas de frases em português). */
        fun default(tagger: (String) -> Set<String> = { emptySet() }): IntentModel = fromCorpus(loadCorpus(), tagger = tagger)
    }
}
