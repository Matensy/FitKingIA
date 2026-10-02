package com.fitkingia.coach.evidence

import com.fitkingia.coach.text.PtText
import com.fitkingia.core.knowledge.Claim
import com.fitkingia.core.knowledge.KnowledgeBase
import kotlin.math.ln
import kotlin.math.sqrt

data class EvidenceHit(val claim: Claim, val score: Double)

/**
 * Busca local nas afirmações do banco de evidências (TF-IDF + cosseno sobre radicais).
 * É a "memória científica" da IA local: ela só responde dúvidas com o que está no banco,
 * sempre com nível de evidência e fontes — nunca inventa.
 */
class EvidenceSearch(private val kb: KnowledgeBase) {
    private val docs: List<Pair<Claim, Map<String, Double>>>
    private val idf: Map<String, Double>

    /** Expansões de vocabulário do dia a dia para os termos usados nas afirmações. */
    private val synonyms = mapOf(
        "barriga" to "localizada abdominal gordura", "secar" to "gordura perda", "emagrecer" to "perda gordura deficit",
        "falha" to "falha proximidade", "rir" to "repeticoes reserva rpe", "series" to "volume series semanais",
        "descanso" to "descanso intervalo", "descansar" to "descanso intervalo", "dormir" to "sono horas", "sono" to "sono horas",
        "agua" to "agua hidratacao", "cardio" to "aerobica atividade minutos", "massa" to "hipertrofia massa muscular",
        "musculo" to "hipertrofia muscular", "forca" to "forca carga", "frequencia" to "frequencia sessoes semana",
        "vezes" to "frequencia sessoes", "deload" to "deload semana", "proteina" to "proteina ingestao", "1rm" to "1rm equacoes",
        "imc" to "imc obesidade", "cintura" to "cintura altura", "peso" to "peso glicogenio agua", "inchado" to "glicogenio agua peso",
    )

    init {
        val raw = kb.claims.map { c ->
            val sources = c.sources.joinToString(" ") { kb.source(it.sourceId).summary }
            c to tf(PtText.contentStems("${c.statement} ${c.topic} ${c.topic} $sources"))
        }
        val df = HashMap<String, Int>()
        raw.forEach { (_, t) -> t.keys.forEach { df.merge(it, 1, Int::plus) } }
        idf = df.mapValues { ln((raw.size + 1.0) / (it.value + 1.0)) + 1.0 }
        docs = raw.map { (c, t) -> c to normalize(t.mapValues { (k, v) -> v * (idf[k] ?: 0.0) }) }
    }

    fun search(query: String, limit: Int = 3, minScore: Double = 0.12): List<EvidenceHit> {
        val expanded = PtText.tokens(query).joinToString(" ") { t -> synonyms[t]?.let { "$t $it" } ?: t }
        val q = normalize(tf(PtText.contentStems(expanded)).mapValues { (k, v) -> v * (idf[k] ?: 0.0) })
        if (q.isEmpty()) return emptyList()
        return docs.map { (c, d) -> EvidenceHit(c, q.entries.sumOf { (k, v) -> v * (d[k] ?: 0.0) }) }
            .filter { it.score >= minScore }
            .sortedByDescending { it.score }
            .take(limit)
    }

    private fun tf(stems: List<String>): Map<String, Double> = stems.groupingBy { it }.eachCount().mapValues { 1.0 + ln(it.value.toDouble()) }

    private fun normalize(v: Map<String, Double>): Map<String, Double> {
        val norm = sqrt(v.values.sumOf { it * it })
        return if (norm == 0.0) emptyMap() else v.mapValues { it.value / norm }
    }
}
