package com.fitkingia.core.program

import com.fitkingia.core.knowledge.Exercise
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.RepPrescription
import com.fitkingia.core.knowledge.Slot
import com.fitkingia.core.knowledge.VolumeTarget
import com.fitkingia.core.model.*

/**
 * Ajusta séries para aproximar o volume semanal das metas por músculo, respeitando
 * o tempo de cada dia, o máximo de séries por exercício e evitando estourar o teto
 * de outros músculos. Quando nenhum exercício do plano trabalha um músculo
 * diretamente, adiciona um acessório do "padrão de preenchimento" daquele músculo.
 */
class VolumePlanner(private val kb: KnowledgeBase, private val selector: ExerciseSelector) {
    private val rules = kb.ruleSet.volume.params
    private val clock = SessionClock(kb.ruleSet.timing.params)
    private val calc = VolumeCalculator(rules)

    data class Outcome(val sessions: List<List<PlannedExercise>>, val notes: List<String>)

    fun balance(
        sessions: List<List<PlannedExercise>>,
        budgets: List<Int>,
        targets: Map<MuscleId, VolumeTarget>,
        constraints: UserConstraints,
        prescriptionFor: (SlotRole, Exercise) -> RepPrescription,
        /** Músculos priorizados: ao somar séries para eles, preferir exercícios com foco neles. */
        preferFocus: Set<MuscleId> = emptySet(),
    ): Outcome {
        val s = sessions.map { it.toMutableList() }
        val notes = mutableListOf<String>()
        val filled = mutableSetOf<Pair<Int, MuscleId>>()

        var steps = 0
        while (steps++ < MAX_STEPS) {
            val weekly = calc.ofExercises(s.flatten())
            val deficits = targets.entries
                .filter { (m, t) -> (weekly[m] ?: 0.0) < t.target - EPS }
                .sortedWith(compareByDescending<Map.Entry<MuscleId, VolumeTarget>> { (m, t) -> (t.target - (weekly[m] ?: 0.0)) / t.target }
                    .thenBy { it.key.value })
            val progressed = deficits.any { (m, t) ->
                addSet(s, budgets, m, weekly, targets, m in preferFocus) ||
                    ((weekly[m] ?: 0.0) < t.min - EPS || s.none { l -> l.any { m in it.exercise.primaryMuscles } }) &&
                    addFill(s, budgets, m, constraints, prescriptionFor, filled, notes)
            }
            if (!progressed) break
        }

        // Teto semanal é invariante: primeiro reduz séries; se não bastar, remove o exercício menos importante.
        steps = 0
        while (steps++ < MAX_STEPS) {
            val weekly = calc.ofExercises(s.flatten())
            val excess = targets.entries.filter { (m, t) -> (weekly[m] ?: 0.0) > t.max + EPS }
                .sortedByDescending { (m, t) -> (weekly[m] ?: 0.0) - t.max }
            if (excess.isEmpty()) break
            val progressed = excess.any { (m, _) -> removeSet(s, m, weekly, targets, m in preferFocus) } ||
                excess.any { (m, t) -> removeExercise(s, m, t, weekly, targets, notes) }
            if (!progressed) break
        }
        return Outcome(s, notes)
    }

    private fun fits(list: List<PlannedExercise>, budget: Int) = clock.estimateMinutes(list) <= budget

    private fun addSet(
        s: List<MutableList<PlannedExercise>>, budgets: List<Int>, m: MuscleId,
        weekly: Map<MuscleId, Double>, targets: Map<MuscleId, VolumeTarget>, focusFirst: Boolean = false,
    ): Boolean {
        data class Cand(val si: Int, val ei: Int, val spill: Int, val headroom: Int)
        val cands = mutableListOf<Cand>()
        for ((si, list) in s.withIndex()) for ((ei, ex) in list.withIndex()) {
            if (m !in ex.exercise.primaryMuscles) continue
            if (ex.sets >= (rules.maxSetsPerExercise[ex.exercise.mechanic] ?: 4)) continue
            val trial = list.toMutableList().also { it[ei] = ex.copy(sets = ex.sets + 1) }
            if (!fits(trial, budgets[si])) continue
            val overshoots = (ex.exercise.primaryMuscles + ex.exercise.secondaryMuscles).any { o ->
                val t = targets[o] ?: return@any false
                (weekly[o] ?: 0.0) + calc.credit(ex, o) > t.max + EPS
            }
            if (overshoots) continue
            val spill = ex.exercise.primaryMuscles.count { o -> o != m && targets[o]?.let { (weekly[o] ?: 0.0) >= it.target } == true }
            cands += Cand(si, ei, spill, budgets[si] - clock.estimateMinutes(trial))
        }
        val best = cands.sortedWith(
            compareBy<Cand> { if (focusFirst && m !in s[it.si][it.ei].exercise.focus) 1 else 0 }
                .thenBy { if (s[it.si][it.ei].exercise.mechanic == Mechanic.ISOLATION) 0 else 1 }
                .thenBy { it.spill }
                .thenByDescending { it.headroom }
                .thenBy { s[it.si][it.ei].sets }
                .thenBy { it.si }.thenBy { it.ei }
        ).firstOrNull() ?: return false
        val ex = s[best.si][best.ei]
        s[best.si][best.ei] = ex.copy(sets = ex.sets + 1)
        return true
    }

    private fun addFill(
        s: List<MutableList<PlannedExercise>>, budgets: List<Int>, m: MuscleId, constraints: UserConstraints,
        prescriptionFor: (SlotRole, Exercise) -> RepPrescription, filled: MutableSet<Pair<Int, MuscleId>>, notes: MutableList<String>,
    ): Boolean {
        val pattern = kb.muscle(m).fillPattern ?: return false
        val slot = Slot(pattern, SlotRole.ACCESSORY, rules.minSetsPerExercise, targetMuscle = m)
        val usedInWeek = s.flatten().groupingBy { it.exercise.id }.eachCount()
        // Prefere sessões que ainda não têm esse padrão; depois, as com mais folga de tempo.
        val order = s.indices.sortedWith(
            compareBy<Int> { si -> if (s[si].any { it.exercise.pattern == pattern }) 1 else 0 }
                .thenByDescending { budgets[it] - clock.estimateMinutes(s[it]) }
        )
        for (si in order) {
            if ((si to m) in filled) continue
            val used = s[si].map { it.exercise.id }.toSet()
            val ex = selector.select(slot, constraints, used, usedInWeek) ?: continue
            val planned = PlannedExercise(ex, SlotRole.ACCESSORY, rules.minSetsPerExercise, prescriptionFor(SlotRole.ACCESSORY, ex), slot = slot)
            if (!fits(s[si] + planned, budgets[si])) continue
            s[si].add(planned)
            filled += si to m
            notes += "Adicionado: ${ex.name}, para completar o volume de ${kb.muscleName(m).lowercase()}"
            return true
        }
        return false
    }

    /** Deixar outro músculo abaixo do mínimo ao tirar [sets] séries deste exercício? */
    private fun starves(ex: PlannedExercise, m: MuscleId, sets: Int, weekly: Map<MuscleId, Double>, targets: Map<MuscleId, VolumeTarget>) =
        (ex.exercise.primaryMuscles + ex.exercise.secondaryMuscles).any { o ->
            o != m && targets[o]?.let { (weekly[o] ?: 0.0) - sets * calc.credit(ex, o) < it.min - EPS } == true
        }

    private fun removeSet(
        s: List<MutableList<PlannedExercise>>, m: MuscleId,
        weekly: Map<MuscleId, Double>, targets: Map<MuscleId, VolumeTarget>, keepFocused: Boolean = false,
    ): Boolean {
        val best = s.withIndex().flatMap { (si, list) -> list.withIndex().map { (ei, ex) -> Triple(si, ei, ex) } }
            .filter { (_, _, ex) -> calc.credit(ex, m) > 0 && ex.sets > rules.minSetsPerExercise && !starves(ex, m, 1, weekly, targets) }
            // O composto principal da prioridade não desce abaixo das séries-base do modelo: antes disso sai um acessório inteiro.
            .filter { (_, _, ex) -> !(keepFocused && m in ex.exercise.focus && ex.role == SlotRole.MAIN && ex.sets <= (ex.slot?.baseSets ?: 3)) }
            // Músculo priorizado acima do teto: tira primeiro de quem só o trabalha de carona (ex.: agachamento para glúteos).
            .sortedWith(compareBy<Triple<Int, Int, PlannedExercise>> { if (keepFocused && m in it.third.exercise.focus) 1 else 0 }
                .thenByDescending { calc.credit(it.third, m) }
                .thenBy { it.third.role.keepPriority }
                .thenByDescending { it.third.sets }
                .thenBy { it.first }.thenBy { it.second })
            .firstOrNull() ?: return false
        val (si, ei, ex) = best
        s[si][ei] = ex.copy(sets = ex.sets - 1)
        return true
    }

    private fun removeExercise(
        s: List<MutableList<PlannedExercise>>, m: MuscleId, t: VolumeTarget,
        weekly: Map<MuscleId, Double>, targets: Map<MuscleId, VolumeTarget>, notes: MutableList<String>,
    ): Boolean {
        val best = s.withIndex().flatMap { (si, list) -> list.withIndex().map { (ei, ex) -> Triple(si, ei, ex) } }
            .filter { (si, _, ex) ->
                calc.credit(ex, m) > 0 && s[si].size > 1 &&
                    (ex.role != SlotRole.MAIN || s[si].count { it.role == SlotRole.MAIN } > 1) &&
                    !starves(ex, m, ex.sets, weekly, targets)
            }
            .sortedWith(compareBy<Triple<Int, Int, PlannedExercise>> { it.third.role.keepPriority }
                .thenByDescending { calc.credit(it.third, m) }
                .thenByDescending { it.first }.thenByDescending { it.second })
            .firstOrNull() ?: return false
        val (si, ei, ex) = best
        s[si].removeAt(ei)
        notes += "Removido: ${ex.exercise.name}, para não exceder o teto semanal de ${kb.muscleName(m).lowercase()} (${ProgramGenerator.fmt(t.max)} séries)"
        return true
    }

    private companion object {
        const val MAX_STEPS = 400
        const val EPS = 1e-9
    }
}
