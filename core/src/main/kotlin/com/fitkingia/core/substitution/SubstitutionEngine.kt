package com.fitkingia.core.substitution

import com.fitkingia.core.knowledge.Exercise
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.*
import com.fitkingia.core.program.Eligibility
import com.fitkingia.core.program.IneligibleReason
import com.fitkingia.core.program.UserConstraints
import kotlin.math.abs

data class Substitution(
    val exercise: Exercise,
    val score: Double,
    val reasons: List<String>,
    val curatedRank: Int?,
)

data class SubstitutionResult(
    val original: Exercise,
    val originalStatus: IneligibleReason?,
    val options: List<Substitution>,
    val notes: List<String>,
)

/**
 * Substituição por similaridade, não por "outro exercício do mesmo músculo":
 * padrão de movimento, músculos, mecânica, estabilidade, dificuldade, equipamento disponível,
 * experiência, articulação com dor, objetivo, curadoria e histórico do usuário.
 */
class SubstitutionEngine(private val kb: KnowledgeBase) {

    fun find(
        originalId: ExerciseId,
        constraints: UserConstraints,
        focus: TrainingFocus? = null,
        painJoint: Joint? = null,
        limit: Int = 5,
        /** Dor relatada agora (0–10). Sem informação, assume leve: exclui só exercícios de alta demanda. */
        painSeverity: Int = 3,
    ): SubstitutionResult {
        val original = kb.exercise(originalId)
        val effective = if (painJoint != null && constraints.limitations.none { it.joint == painJoint }) {
            constraints.copy(limitations = constraints.limitations + JointLimitation(painJoint, painSeverity))
        } else constraints
        val relatedPatterns = kb.pattern(original.pattern).related

        val options = kb.exercises.asSequence()
            .filter { it.id != original.id && Eligibility.isAllowed(it, effective) }
            .mapNotNull { cand -> scoreCandidate(original, cand, relatedPatterns, effective, focus, painJoint) }
            .sortedWith(compareByDescending<Substitution> { it.score }.thenBy { it.exercise.id.value })
            .take(limit)
            .toList()

        val notes = buildList {
            Eligibility.check(original, effective)?.let { add("${original.name}: ${it.label}.") }
            if (painJoint != null) add(
                "Você relatou dor (${painJoint.label}). Considere interromper ou modificar movimentos que agravem " +
                    "os sintomas e procure avaliação profissional quando necessário. As opções abaixo têm demanda " +
                    "menor ou igual nessa articulação — isso não é diagnóstico nem tratamento."
            )
            if (options.isEmpty()) add("Nenhuma substituição compatível com seu equipamento e restrições foi encontrada.")
        }
        return SubstitutionResult(original, Eligibility.check(original, effective), options, notes)
    }

    private fun scoreCandidate(
        o: Exercise,
        c: Exercise,
        related: Set<PatternId>,
        constraints: UserConstraints,
        focus: TrainingFocus?,
        painJoint: Joint?,
    ): Substitution? {
        val reasons = mutableListOf<String>()
        var s = 0.0

        val samePattern = c.pattern == o.pattern
        val relatedPattern = c.pattern in related
        val primaryOverlap = jaccard(o.primaryMuscles, c.primaryMuscles)
        // Sem padrão em comum e sem músculo principal em comum, não é substituto.
        if (!samePattern && !relatedPattern && primaryOverlap == 0.0) return null
        if (!samePattern && primaryOverlap < 0.34) return null

        if (samePattern) { s += 40; reasons += "mesmo padrão de movimento (${kb.patternName(o.pattern).lowercase()})" }
        else if (relatedPattern) { s += 15; reasons += "padrão de movimento relacionado (${kb.patternName(c.pattern).lowercase()})" }

        s += primaryOverlap * 30
        val shared = o.primaryMuscles intersect c.primaryMuscles
        if (shared.isNotEmpty()) reasons += "músculos principais em comum: " + shared.joinToString { kb.muscleName(it).lowercase() }
        s += jaccard(o.secondaryMuscles, c.secondaryMuscles) * 5

        if (c.mechanic == o.mechanic) s += 10
        if (c.laterality == o.laterality) s += 3 else reasons += if (c.isUnilateral) "versão unilateral" else "versão bilateral"
        s -= abs(c.stability - o.stability) * 3.0
        s -= abs(c.difficulty - o.difficulty) * 2.0
        if (c.stability < o.stability) reasons += "exige menos estabilidade"

        val curated = o.curatedSubstitutes.indexOf(c.id).takeIf { it >= 0 }
        if (curated != null) { s += (25 - curated * 5).coerceAtLeast(5).toDouble(); reasons += "substituição curada nº ${curated + 1}" }

        if (painJoint != null) {
            val diff = o.demand(painJoint) - c.demand(painJoint)
            s += diff * 8.0
            if (diff > 0) reasons += "menor demanda no ${painJoint.label}"
        }
        if (focus == TrainingFocus.STRENGTH && c.mechanic == Mechanic.COMPOUND && c.loadType == LoadType.BARBELL) s += 5
        if (c.maxTier != null && constraints.tier > c.maxTier) s -= 10
        if (c.id in constraints.history) { s += 5; reasons += "você já executou este exercício" }
        if (c.id in constraints.favorites) { s += 8; reasons += "está entre seus favoritos" }

        if (c.equipment.isNotEmpty()) reasons += "usa equipamento disponível: " + c.equipment.joinToString { kb.equipment(it).name.lowercase() }
        else reasons += "não exige equipamento"
        return Substitution(c, s, reasons, curated)
    }

    private fun <T> jaccard(a: Set<T>, b: Set<T>): Double {
        if (a.isEmpty() && b.isEmpty()) return 0.0
        return (a intersect b).size.toDouble() / (a union b).size
    }
}
