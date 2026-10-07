package com.fitkingia.core.session

import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.MuscleId
import com.fitkingia.core.model.SlotRole
import com.fitkingia.core.program.PlannedExercise
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.program.SessionClock
import com.fitkingia.core.program.VolumeCalculator
import kotlin.math.min

enum class ChangeKind { REMOVED, SETS_REDUCED, REST_SHORTENED, REST_EXTENDED, INTENSITY_REDUCED, SUBSTITUTED, NOT_ENOUGH_TIME }

data class SessionChange(val kind: ChangeKind, val exerciseName: String?, val description: String, val reason: String) {
    override fun toString() = "$description — $reason"
}

data class FitResult(
    val session: PlannedSession,
    val originalMinutes: Int,
    val changes: List<SessionChange>,
) {
    val fits: Boolean get() = changes.none { it.kind == ChangeKind.NOT_ENOUGH_TIME }
}

/**
 * Faz a sessão caber no tempo disponível ("Tenho só 40 minutos hoje").
 * Ordem de cortes (do que menos perde estímulo para o que mais perde):
 *  1. remover exercícios não principais totalmente redundantes (músculos já cobertos na sessão);
 *  2. encurtar descanso para o limite inferior da faixa prescrita;
 *  3. reduzir séries de acessórios, depois de secundários;
 *  4. remover não principais (mais redundante primeiro);
 *  5. reduzir séries dos principais;
 *  6. remover principais do fim, mantendo pelo menos [keepMain].
 * Cada mudança é registrada com o motivo — o usuário vê o que foi removido e por quê.
 */
class SessionFitter(private val kb: KnowledgeBase) {
    private val clock = SessionClock(kb.ruleSet.timing.params)
    private val volume = VolumeCalculator(kb.ruleSet.volume.params)
    private val minSets = kb.ruleSet.volume.params.minSetsPerExercise

    /**
     * [protect]: músculos priorizados pelo usuário — exercícios que os têm como principais são os últimos a
     * perder séries ou sair (quem prioriza braços não perde a rosca para caber no tempo).
     */
    fun fit(session: PlannedSession, budgetMinutes: Int, keepMain: Int = 1, protect: Set<MuscleId> = emptySet()): FitResult {
        val guarded = { e: PlannedExercise -> e.exercise.primaryMuscles.any { it in protect } }
        val items = session.exercises.toMutableList()
        val changes = mutableListOf<SessionChange>()
        val original = clock.estimateMinutes(items)
        fun over() = clock.estimateMinutes(items) > budgetMinutes

        var restShortened = false
        while (over()) {
            if (removeFullyRedundant(items, changes, guarded)) continue
            if (!restShortened) {
                restShortened = true
                if (shortenRest(items, changes)) continue
            }
            if (reduceSets(items, changes, SlotRole.ACCESSORY, guarded)) continue
            if (reduceSets(items, changes, SlotRole.SECONDARY, guarded)) continue
            if (removeMostRedundant(items, changes, guarded)) continue
            if (reduceSets(items, changes, SlotRole.MAIN, guarded)) continue
            if (removeLastMain(items, changes, keepMain)) continue
            changes += SessionChange(
                ChangeKind.NOT_ENOUGH_TIME, null,
                "Mesmo reduzida, a sessão estimada tem ${clock.estimateMinutes(items)} min",
                "o mínimo útil não cabe em $budgetMinutes min",
            )
            break
        }
        val fitted = session.copy(
            exercises = items,
            budgetMinutes = budgetMinutes,
            estimatedMinutes = clock.estimateMinutes(items),
        )
        return FitResult(fitted, original, changes)
    }

    /**
     * 0..1 — quanto dos músculos principais do exercício já é coberto pelo resto da sessão (3 séries = coberto).
     * [directOnly]: conta só trabalho direto (músculo principal em outro exercício). Bíceps coberto apenas
     * indiretamente por remadas não torna a rosca "totalmente redundante".
     */
    fun redundancy(target: PlannedExercise, all: List<PlannedExercise>, directOnly: Boolean = false): Double {
        val others = all.filter { it !== target }
        val prim = target.exercise.primaryMuscles
        if (prim.isEmpty()) return 1.0
        return prim.sumOf { m ->
            min(1.0, others.sumOf { o ->
                o.sets * if (m in o.exercise.primaryMuscles || !directOnly) volume.credit(o, m) else 0.0
            } / 3.0)
        } / prim.size
    }

    private fun coveredBy(target: PlannedExercise, all: List<PlannedExercise>): String =
        all.filter { o -> o !== target && target.exercise.primaryMuscles.any { it in o.exercise.primaryMuscles } }
            .joinToString { it.exercise.name }

    private fun removeFullyRedundant(items: MutableList<PlannedExercise>, changes: MutableList<SessionChange>, guarded: (PlannedExercise) -> Boolean): Boolean {
        val victim = items.withIndex()
            .filter { it.value.role != SlotRole.MAIN && !guarded(it.value) && redundancy(it.value, items, directOnly = true) >= 0.999 }
            .sortedWith(compareBy<IndexedValue<PlannedExercise>> { it.value.role.keepPriority }.thenByDescending { it.index })
            .firstOrNull() ?: return false
        val ex = victim.value
        changes += SessionChange(
            ChangeKind.REMOVED, ex.exercise.name, "Removido: ${ex.exercise.name}",
            "músculos principais (${ex.exercise.primaryMuscles.joinToString { kb.muscleName(it).lowercase() }}) já trabalhados por ${coveredBy(ex, items)}",
        )
        items.removeAt(victim.index)
        return true
    }

    private fun shortenRest(items: MutableList<PlannedExercise>, changes: MutableList<SessionChange>): Boolean {
        var changed = false
        for (i in items.indices) {
            val ex = items[i]
            val low = ex.prescription.restSeconds.first
            if (ex.restSeconds > low) { items[i] = ex.copy(restSeconds = low); changed = true }
        }
        if (changed) changes += SessionChange(
            ChangeKind.REST_SHORTENED, null, "Descanso no limite inferior da faixa prescrita",
            "mantém o volume; diferenças de hipertrofia acima de ~60–90 s de descanso tendem a ser pequenas",
        )
        return changed
    }

    private fun reduceSets(items: MutableList<PlannedExercise>, changes: MutableList<SessionChange>, role: SlotRole, guarded: (PlannedExercise) -> Boolean): Boolean {
        val idx = items.indices.filter { items[it].role == role && items[it].sets > minSets }
            .maxWithOrNull(compareBy<Int> { if (guarded(items[it])) 0 else 1 }.thenBy { redundancy(items[it], items) }.thenBy { it }) ?: return false
        val ex = items[idx]
        items[idx] = ex.copy(sets = ex.sets - 1)
        changes += SessionChange(
            ChangeKind.SETS_REDUCED, ex.exercise.name, "${ex.exercise.name}: ${ex.sets} → ${ex.sets - 1} séries",
            "papel ${role.label.lowercase()} cede tempo antes dos exercícios principais",
        )
        return true
    }

    private fun removeMostRedundant(items: MutableList<PlannedExercise>, changes: MutableList<SessionChange>, guarded: (PlannedExercise) -> Boolean): Boolean {
        val victim = items.withIndex().filter { it.value.role != SlotRole.MAIN }
            .sortedWith(
                compareBy<IndexedValue<PlannedExercise>> { if (guarded(it.value)) 1 else 0 }
                    .thenBy { it.value.role.keepPriority }
                    .thenByDescending { redundancy(it.value, items) }
                    .thenByDescending { it.index }
            ).firstOrNull() ?: return false
        val ex = victim.value
        changes += SessionChange(
            ChangeKind.REMOVED, ex.exercise.name, "Removido: ${ex.exercise.name}",
            "prioridade ${ex.role.label.lowercase()} — o tempo disponível foi reservado aos exercícios principais",
        )
        items.removeAt(victim.index)
        return true
    }

    private fun removeLastMain(items: MutableList<PlannedExercise>, changes: MutableList<SessionChange>, keepMain: Int): Boolean {
        val mains = items.withIndex().filter { it.value.role == SlotRole.MAIN }
        if (mains.size <= keepMain) return false
        val victim = mains.last()
        changes += SessionChange(
            ChangeKind.REMOVED, victim.value.exercise.name, "Removido: ${victim.value.exercise.name}",
            "tempo insuficiente para todos os exercícios principais; mantidos os primeiros da sessão",
        )
        items.removeAt(victim.index)
        return true
    }
}
