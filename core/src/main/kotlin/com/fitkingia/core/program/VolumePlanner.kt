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

        // Com prioridade, as demais regiões têm teto de manutenção: corta antes de somar, para o tempo
        // liberado ir para a região priorizada (redistribuir, não empilhar).
        if (preferFocus.isNotEmpty()) trimCeilings(s, targets, preferFocus, notes)

        var steps = 0
        while (steps++ < MAX_STEPS) {
            val weekly = calc.ofExercises(s.flatten())
            val deficits = targets.entries
                .filter { (m, t) -> (weekly[m] ?: 0.0) < t.target - EPS || focusShort(s, m, t, preferFocus) }
                // A prioridade é atendida primeiro; depois, quem está mais longe da meta.
                .sortedWith(compareBy<Map.Entry<MuscleId, VolumeTarget>> { if (it.key in preferFocus) 0 else 1 }
                    .thenByDescending { (m, t) -> (t.target - (weekly[m] ?: 0.0)) / t.target }
                    .thenBy { it.key.value })
            val progressed = deficits.any { (m, t) ->
                // Volume já na meta, mas faltam séries focadas: só vale série em exercício com foco no músculo.
                val focusOnly = (weekly[m] ?: 0.0) >= t.target - EPS
                addSet(s, budgets, m, weekly, targets, m in preferFocus, focusOnly) ||
                    ((weekly[m] ?: 0.0) < t.min - EPS || m in preferFocus || s.none { l -> l.any { m in it.exercise.primaryMuscles } }) &&
                    addFill(s, budgets, m, constraints, prescriptionFor, filled, notes)
            }
            if (!progressed) break
        }

        if (preferFocus.isNotEmpty()) redistribute(s, budgets, targets, preferFocus, notes)

        // Teto semanal é invariante.
        trimCeilings(s, targets, preferFocus, notes)
        return Outcome(s, notes)
    }

    /**
     * Sem tempo livre e com a prioridade abaixo da meta: no mesmo dia, uma série sai de um exercício cujos
     * músculos já estão acima da própria meta (outra região, ou a outra prioridade que sobrou) e vai para
     * um exercício da prioridade. Nunca deixa ninguém abaixo do mínimo nem o principal abaixo das séries-base.
     */
    private fun redistribute(
        s: List<MutableList<PlannedExercise>>, budgets: List<Int>, targets: Map<MuscleId, VolumeTarget>,
        preferFocus: Set<MuscleId>, notes: MutableList<String>,
    ) {
        var moved = 0
        var steps = 0
        while (steps++ < MAX_STEPS) {
            val weekly = calc.ofExercises(s.flatten())
            val needy = preferFocus.filter { m -> targets[m]?.let { (weekly[m] ?: 0.0) < it.target - EPS || focusShort(s, m, it, preferFocus) } == true }
                .sortedByDescending { m -> targets.getValue(m).let { (it.target - (weekly[m] ?: 0.0)) / it.target } }
            if (needy.isEmpty()) break
            var progressed = false
            loop@ for (m in needy) for ((si, list) in s.withIndex()) {
                val focusOnly = (weekly[m] ?: 0.0) >= targets.getValue(m).target - EPS
                val receivers = list.indices.filter { i ->
                    val e = list[i]
                    m in e.exercise.primaryMuscles && (!focusOnly || m in e.exercise.focus) &&
                        e.sets < (rules.maxSetsPerExercise[e.exercise.mechanic] ?: 4) &&
                        (e.exercise.primaryMuscles + e.exercise.secondaryMuscles).none { o ->
                            targets[o]?.let { (weekly[o] ?: 0.0) + calc.credit(e, o) > it.max + EPS } == true
                        }
                }.sortedWith(compareBy<Int> { if (m in list[it].exercise.focus) 0 else 1 }.thenBy { list[it].sets })
                if (receivers.isEmpty()) continue
                val donors = list.indices.filter { i ->
                    val e = list[i]
                    e.sets > rules.minSetsPerExercise && !(e.role == SlotRole.MAIN && e.sets <= (e.slot?.baseSets ?: 3)) &&
                        e.exercise.primaryMuscles.none { it in needy } &&
                        e.exercise.primaryMuscles.all { o -> targets[o]?.let { (weekly[o] ?: 0.0) - calc.credit(e, o) >= it.target - EPS } ?: true } &&
                        e.exercise.secondaryMuscles.all { o -> targets[o]?.let { (weekly[o] ?: 0.0) - calc.credit(e, o) >= it.min - EPS } ?: true }
                }.sortedWith(compareBy<Int> { list[it].role.keepPriority }.thenByDescending { list[it].sets })
                for (d in donors) for (r in receivers) {
                    if (d == r) continue
                    val trial = list.toMutableList()
                    trial[d] = trial[d].copy(sets = trial[d].sets - 1)
                    trial[r] = trial[r].copy(sets = trial[r].sets + 1)
                    if (!fits(trial, budgets[si])) continue
                    list[d] = trial[d]; list[r] = trial[r]
                    moved++
                    progressed = true
                    break@loop
                }
            }
            if (!progressed) break
        }
        if (moved > 0) notes += "Prioridade: $moved série(s) remanejada(s) de exercícios de músculos já na meta para exercícios da região priorizada, no mesmo tempo de treino."
    }

    /** Primeiro reduz séries; se não bastar, remove o exercício menos importante. */
    private fun trimCeilings(s: List<MutableList<PlannedExercise>>, targets: Map<MuscleId, VolumeTarget>, preferFocus: Set<MuscleId>, notes: MutableList<String>) {
        var steps = 0
        while (steps++ < MAX_STEPS) {
            val weekly = calc.ofExercises(s.flatten())
            val excess = targets.entries.filter { (m, t) -> (weekly[m] ?: 0.0) > t.max + EPS }
                .sortedByDescending { (m, t) -> (weekly[m] ?: 0.0) - t.max }
            if (excess.isEmpty()) break
            val progressed = excess.any { (m, _) -> removeSet(s, m, weekly, targets, m in preferFocus) } ||
                excess.any { (m, t) -> removeExercise(s, m, t, weekly, targets, notes, m in preferFocus) }
            if (!progressed) break
        }
    }

    private fun fits(list: List<PlannedExercise>, budget: Int) = clock.estimateMinutes(list) <= budget

    private fun addSet(
        s: List<MutableList<PlannedExercise>>, budgets: List<Int>, m: MuscleId,
        weekly: Map<MuscleId, Double>, targets: Map<MuscleId, VolumeTarget>, focusFirst: Boolean = false, focusOnly: Boolean = false,
    ): Boolean {
        data class Cand(val si: Int, val ei: Int, val spill: Int, val headroom: Int)
        val cands = mutableListOf<Cand>()
        for ((si, list) in s.withIndex()) for ((ei, ex) in list.withIndex()) {
            if (m !in ex.exercise.primaryMuscles) continue
            if (focusOnly && m !in ex.exercise.focus) continue
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
        val muscle = kb.muscle(m)
        // Padrão de preenchimento do músculo; se nenhum exercício dele for permitido (ex.: dor), outro padrão
        // em que o músculo é principal.
        val patterns = (listOfNotNull(muscle.fillPattern) +
            kb.exercises.filter { m in it.primaryMuscles && Eligibility.isAllowed(it, constraints) }.map { it.pattern }).distinct()
        if (patterns.isEmpty()) return false
        val usedInWeek = s.flatten().groupingBy { it.exercise.id }.eachCount()
        fun sameRegion(list: List<PlannedExercise>) = muscle.region == "core" ||
            list.any { e -> e.exercise.primaryMuscles.any { kb.muscle(it).region == muscle.region } }
        for (pattern in patterns) {
            val slot = Slot(pattern, SlotRole.ACCESSORY, rules.minSetsPerExercise, targetMuscle = m)
            // Prefere sessões que já treinam a mesma metade do corpo (bíceps no dia de superiores), que ainda
            // não têm esse padrão e, por fim, com mais folga de tempo.
            val order = s.indices.sortedWith(
                compareBy<Int> { si -> if (sameRegion(s[si])) 0 else 1 }
                    .thenBy { si -> if (s[si].any { it.exercise.pattern == pattern }) 1 else 0 }
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
        }
        return false
    }

    /**
     * Músculo priorizado com menos séries focadas que o mínimo da prioridade (= alvo normal): agachamento
     * conta para glúteos no volume, mas não substitui elevação pélvica — é isso que a checagem confere.
     */
    private fun focusShort(s: List<List<PlannedExercise>>, m: MuscleId, t: VolumeTarget, preferFocus: Set<MuscleId>) =
        m in preferFocus && focusedSets(s, m) < t.min - EPS

    /** Séries/semana em exercícios com foco no músculo (curadoria), sem contagem fracionada. */
    private fun focusedSets(s: List<List<PlannedExercise>>, m: MuscleId) = s.sumOf { l -> l.filter { m in it.exercise.focus }.sumOf { it.sets } }

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
            // Exercício principal não desce abaixo das séries-base do modelo: antes disso sai um acessório inteiro
            // (não faz sentido o principal do dia com 2 séries e acessórios com 4).
            .filter { (_, _, ex) -> !(ex.role == SlotRole.MAIN && ex.sets <= (ex.slot?.baseSets ?: 3)) }
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
        weekly: Map<MuscleId, Double>, targets: Map<MuscleId, VolumeTarget>, notes: MutableList<String>, keepFocused: Boolean = false,
    ): Boolean {
        val best = s.withIndex().flatMap { (si, list) -> list.withIndex().map { (ei, ex) -> Triple(si, ei, ex) } }
            .filter { (si, _, ex) ->
                // Exercício com foco na região priorizada só sai pelo teto dela se as séries focadas
                // continuarem na meta (ex.: com glúteos de sobra, o tempo volta para a 2ª prioridade).
                calc.credit(ex, m) > 0 && s[si].size > 1 &&
                    !(keepFocused && m in ex.exercise.focus && (ex.role == SlotRole.MAIN || focusedSets(s, m) - ex.sets < t.target - EPS)) &&
                    (ex.role != SlotRole.MAIN || s[si].count { it.role == SlotRole.MAIN } > 1) &&
                    !starves(ex, m, ex.sets, weekly, targets)
            }
            // Prioridade acima do teto: sai antes quem a trabalha de carona; os exercícios com foco nela
            // (elevação pélvica, abdução, coice para glúteos) ficam.
            .sortedWith(compareBy<Triple<Int, Int, PlannedExercise>> { if (keepFocused && m in it.third.exercise.focus) 1 else 0 }
                .thenBy { it.third.role.keepPriority }
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
