package com.fitkingia.core.program

import com.fitkingia.core.knowledge.Exercise
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.Slot
import com.fitkingia.core.model.*
import kotlin.math.max

/**
 * Escolhe o exercício de um slot do template. Pontuação determinística e explicável:
 * encaixe no papel do slot, adequação ao nível, variedade na semana, preferências e histórico.
 * Empates são resolvidos por menor dificuldade e depois pelo id (estável entre execuções).
 */
class ExerciseSelector(private val kb: KnowledgeBase) {

    data class Scored(val exercise: Exercise, val score: Double)

    fun matches(ex: Exercise, slot: Slot): Boolean =
        ex.pattern == slot.pattern && (slot.targetMuscle == null || slot.targetMuscle in ex.primaryMuscles)

    fun rank(
        slot: Slot,
        constraints: UserConstraints,
        usedInSession: Set<ExerciseId> = emptySet(),
        usedInWeek: Map<ExerciseId, Int> = emptyMap(),
    ): List<Scored> =
        kb.exercises.asSequence()
            .filter { matches(it, slot) && it.id !in usedInSession && Eligibility.isAllowed(it, constraints) }
            .map { Scored(it, score(it, slot, constraints, usedInWeek)) }
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.exercise.difficulty }.thenBy { it.exercise.id.value })
            .toList()

    fun select(
        slot: Slot,
        constraints: UserConstraints,
        usedInSession: Set<ExerciseId> = emptySet(),
        usedInWeek: Map<ExerciseId, Int> = emptyMap(),
    ): Exercise? = rank(slot, constraints, usedInSession, usedInWeek).firstOrNull()?.exercise

    private fun score(ex: Exercise, slot: Slot, c: UserConstraints, usedInWeek: Map<ExerciseId, Int>): Double {
        var s = 0.0
        if (slot.role == SlotRole.MAIN && ex.mechanic == Mechanic.COMPOUND) s += 30
        if (slot.role == SlotRole.ACCESSORY && ex.mechanic == Mechanic.ISOLATION) s += 10
        if (slot.preferMechanic != null && ex.mechanic == slot.preferMechanic) s += 20
        if (slot.preferLaterality != null && ex.laterality == slot.preferLaterality) s += 12
        s -= when (c.tier) {
            TrainingTier.NOVICE -> (ex.difficulty - 1) * 6.0 + max(0, ex.stability - 2) * 4.0
            TrainingTier.INTERMEDIATE -> max(0, ex.difficulty - 3) * 4.0 + max(0, ex.stability - 4) * 2.0
            TrainingTier.ADVANCED -> 0.0
        }
        s += ex.staple * 4.0
        if (ex.maxTier != null && c.tier > ex.maxTier) s -= 20
        // Compostos com barra permitem mais carga e progressão fina: preferidos nos slots principais
        // de quem já tem base técnica, e sempre no foco de força/potência.
        if (ex.mechanic == Mechanic.COMPOUND && ex.loadType == LoadType.BARBELL) {
            val strengthFocus = c.focus == TrainingFocus.STRENGTH || c.focus == TrainingFocus.POWER
            if (c.tier != TrainingTier.NOVICE || strengthFocus) s += when (slot.role) {
                SlotRole.MAIN -> 6.0
                SlotRole.SECONDARY -> 3.0
                SlotRole.ACCESSORY -> 0.0
            }
        }
        if (ex.id in c.favorites) s += 25
        if (ex.id in c.history) s += 8
        // Variedade: variações diferentes dos compostos ao longo da semana; acessórios podem repetir. Para a
        // região priorizada, repetir o melhor exercício com carga vale mais que trocar por uma regressão
        // (ex.: elevação pélvica com barra de novo, em vez de ponte só com o peso do corpo).
        val focusPriority = slot.role != SlotRole.ACCESSORY && ex.focus.any { it in c.priorityMuscles }
        s -= (usedInWeek[ex.id] ?: 0) * if (slot.role == SlotRole.ACCESSORY) 5.0 else if (focusPriority) 6.0 else 12.0
        // Região priorizada: no mesmo slot, prefere o exercício que tem o músculo prioritário como principal
        // (ex.: no slot de dobradiça, stiff/elevação pélvica antes de extensão lombar para quem prioriza glúteos).
        if (c.priorityMuscles.isNotEmpty()) {
            val p = kb.ruleSet.priority.params
            val bonus = when {
                ex.focus.any { it in c.priorityMuscles } -> p.focusBonus
                ex.primaryMuscles.any { it in c.priorityMuscles } -> p.primaryBonus
                else -> 0.0
            }
            s += bonus * (if (slot.role == SlotRole.ACCESSORY) 0.5 else 1.0)
        }
        // Ao relatar dor, preferir quem poupa a articulação mesmo dentro do permitido.
        for (lim in c.limitations) s -= ex.demand(lim.joint) * 4.0
        return s
    }
}
