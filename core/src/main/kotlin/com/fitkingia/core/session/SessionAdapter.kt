package com.fitkingia.core.session

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.Joint
import com.fitkingia.core.model.MuscleId
import com.fitkingia.core.model.SlotRole
import com.fitkingia.core.program.PlannedExercise
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.program.SessionClock
import com.fitkingia.core.program.UserConstraints
import com.fitkingia.core.recovery.ReadinessBand
import com.fitkingia.core.recovery.ReadinessResult
import com.fitkingia.core.substitution.SubstitutionEngine

data class AdaptedSession(
    val title: String,
    val session: PlannedSession,
    val changes: List<SessionChange>,
    val explanations: List<Explanation>,
)

/** Adapta a sessão do dia a tempo disponível e a prontidão (autorregulação). */
class SessionAdapter(private val kb: KnowledgeBase) {
    private val fitter = SessionFitter(kb)
    private val clock = SessionClock(kb.ruleSet.timing.params)
    private val substitutions = SubstitutionEngine(kb)
    private val minSets = kb.ruleSet.volume.params.minSetsPerExercise

    /** "Tenho só 35 minutos hoje." [protect]: músculos priorizados (cortados por último, como no gerador). */
    fun forTime(session: PlannedSession, minutes: Int, protect: Set<MuscleId> = emptySet()): AdaptedSession {
        val fit = fitter.fit(session, minutes, protect = protect)
        val head = if (fit.changes.isEmpty())
            "A sessão estimada (${fit.originalMinutes} min) já cabe em $minutes min."
        else "Sessão original estimada em ${fit.originalMinutes} min; versão rápida: ${fit.session.estimatedMinutes} min."
        return AdaptedSession(
            "⚡ TREINO RÁPIDO — ${session.name}", fit.session, fit.changes,
            listOf(Explanation.rule(head, kb.ruleSet.timing.id)),
        )
    }

    /** Ajuste por Índice de recuperação: reduz volume, mantém principais, aumenta RIR/descanso, troca exercícios exigentes. */
    fun forReadiness(session: PlannedSession, readiness: ReadinessResult, constraints: UserConstraints): AdaptedSession {
        val items = session.exercises.toMutableList()
        val changes = mutableListOf<SessionChange>()
        val rule = kb.ruleSet.recovery.id
        fun rirPlus(k: Int) {
            for (i in items.indices) items[i] = items[i].let { it.copy(prescription = it.prescription.copy(rir = it.prescription.rir + k)) }
            changes += SessionChange(ChangeKind.INTENSITY_REDUCED, null, "RIR +$k em todos os exercícios", "termine as séries mais longe da falha hoje")
        }

        when (readiness.band) {
            ReadinessBand.NORMAL -> {}
            ReadinessBand.REDUCED -> {
                reduce(items, changes, setOf(SlotRole.ACCESSORY), "prontidão um pouco abaixo do normal")
                rirPlus(1)
            }
            ReadinessBand.LOW -> {
                items.removeAll { ex ->
                    (ex.role == SlotRole.ACCESSORY && fitter.redundancy(ex, items) >= 0.5).also {
                        if (it) changes += SessionChange(ChangeKind.REMOVED, ex.exercise.name, "Removido: ${ex.exercise.name}", "acessório redundante em dia de baixa prontidão")
                    }
                }
                reduce(items, changes, setOf(SlotRole.MAIN, SlotRole.SECONDARY, SlotRole.ACCESSORY), "baixa prontidão")
                rirPlus(2)
                for (i in items.indices) items[i] = items[i].let { it.copy(restSeconds = it.prescription.restSeconds.last) }
                changes += SessionChange(ChangeKind.REST_EXTENDED, null, "Descanso no limite superior da faixa", "mais recuperação entre séries")
                for (i in items.indices) {
                    val ex = items[i]
                    if (ex.exercise.stability >= 4 || ex.exercise.demand(Joint.LOWER_BACK) >= 3) {
                        val alt = substitutions.find(ex.exercise.id, constraints).options
                            .firstOrNull { it.exercise.stability < ex.exercise.stability && it.exercise.demand(Joint.LOWER_BACK) < 3 }
                        if (alt != null) {
                            items[i] = ex.copy(exercise = alt.exercise)
                            changes += SessionChange(
                                ChangeKind.SUBSTITUTED, ex.exercise.name, "Substituído: ${ex.exercise.name} → ${alt.exercise.name}",
                                "menor exigência de estabilidade/carga axial em dia de baixa prontidão",
                            )
                        }
                    }
                }
            }
            ReadinessBand.VERY_LOW -> {
                val kept = items.filter { it.role == SlotRole.MAIN }.map { it.copy(sets = minSets) }
                items.filter { it.role != SlotRole.MAIN }.forEach {
                    changes += SessionChange(ChangeKind.REMOVED, it.exercise.name, "Removido: ${it.exercise.name}", "sessão leve")
                }
                items.clear(); items.addAll(kept)
                rirPlus(3)
            }
        }
        val adapted = session.copy(exercises = items, estimatedMinutes = clock.estimateMinutes(items))
        val notes = mutableListOf(readiness.explanation)
        if (readiness.band == ReadinessBand.VERY_LOW) notes += Explanation.rule(
            "Considere trocar o treino por mobilidade leve, caminhada ou descanso hoje. Se houver dor, tontura ou mal-estar, não treine.", rule,
        )
        return AdaptedSession("Treino ajustado — ${session.name}", adapted, changes, notes)
    }

    private fun reduce(items: MutableList<PlannedExercise>, changes: MutableList<SessionChange>, roles: Set<SlotRole>, why: String) {
        for (i in items.indices) {
            val ex = items[i]
            if (ex.role in roles && ex.sets > minSets) {
                items[i] = ex.copy(sets = ex.sets - 1)
                changes += SessionChange(ChangeKind.SETS_REDUCED, ex.exercise.name, "${ex.exercise.name}: ${ex.sets} → ${ex.sets - 1} séries", why)
            }
        }
    }
}
