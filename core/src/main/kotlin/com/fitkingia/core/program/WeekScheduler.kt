package com.fitkingia.core.program

import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.*
import java.time.DayOfWeek
import kotlin.math.min

/**
 * Escolhe os dias de treino e distribui as sessões na semana minimizando um custo explícito:
 * sobreposição muscular em dias consecutivos, conflito com esportes (ex.: perna pesada colada
 * no kickboxing), sessão maior que o tempo do dia e desvio da ordem canônica do template.
 * Busca exaustiva (≤ 7! combinações) — determinística e auditável.
 */
class WeekScheduler(private val kb: KnowledgeBase) {
    private val rules = kb.ruleSet.scheduling.params
    private val volume = VolumeCalculator(kb.ruleSet.volume.params)

    /** Escolhe até [maxDays] dias espaçados, preferindo dias não consecutivos e com mais tempo. */
    fun pickDays(available: List<DayAvailability>, maxDays: Int): List<DayAvailability> {
        val sorted = available.sortedBy { it.day }
        if (sorted.size <= maxDays) return sorted
        var best: List<DayAvailability>? = null
        var bestCost = Double.MAX_VALUE
        for (combo in combinations(sorted, maxDays)) {
            val days = combo.map { it.day }
            val cost = consecutivePairs(days) * 1000.0 - combo.sumOf { it.minutes }
            if (cost < bestCost) { bestCost = cost; best = combo }
        }
        return best!!
    }

    data class Assignment(val dayForSession: List<DayAvailability>, val cost: Double, val canonicalCost: Double)

    fun assign(sessions: List<PlannedSession>, days: List<DayAvailability>, sports: List<SportCommitment>): Assignment {
        require(sessions.size == days.size) { "sessões (${sessions.size}) e dias (${days.size}) devem ter o mesmo tamanho" }
        val loads = sessions.map { volume.ofExercises(it.exercises) }
        val minutes = sessions.map { SessionClock(kb.ruleSet.timing.params).estimateMinutes(it.exercises) }
        val ordered = days.sortedBy { it.day }

        fun cost(perm: List<Int>): Double {
            // perm[i] = índice da sessão no dia ordered[i]
            var c = 0.0
            val byDay = ordered.indices.associate { ordered[it].day to perm[it] }
            for ((day, s) in byDay) {
                byDay[day.plus(1)]?.let { next -> c += overlap(loads[s], loads[next]) * rules.consecutiveOverlapWeight }
                c += maxOf(0, minutes[s] - ordered.first { it.day == day }.minutes) * rules.overTimeWeight
                for (sp in sports) {
                    val sport = kb.sport(sp.sportId)
                    val k = sp.intensity / 2.0
                    val lower = regionLoad(loads[s], "lower") * sport.lowerBodyLoad * k
                    val upper = regionLoad(loads[s], "upper") * sport.upperBodyLoad * k
                    when (day) {
                        sp.day -> c += (lower + upper) * rules.sportSameDayWeight
                        sp.day.minus(1), sp.day.plus(1) -> c += (lower + upper) * rules.sportAdjacentWeight
                        else -> {}
                    }
                }
            }
            c += inversions(perm) * rules.orderDeviationWeight
            return c
        }

        val identity = sessions.indices.toList()
        var best = identity
        var bestCost = cost(identity)
        for (perm in permutations(identity)) {
            val pc = cost(perm)
            if (pc < bestCost - 1e-9) { best = perm; bestCost = pc }
        }
        val dayForSession = MutableList(sessions.size) { ordered[0] }
        best.forEachIndexed { dayIdx, sessionIdx -> dayForSession[sessionIdx] = ordered[dayIdx] }
        return Assignment(dayForSession, bestCost, cost(identity))
    }

    /**
     * Conflitos que sobraram depois da otimização (ex.: só há dias colados ao esporte).
     * O motor não esconde: devolve frases para o usuário decidir.
     */
    fun sportConflicts(sessions: List<PlannedSession>, days: List<DayOfWeek>, sports: List<SportCommitment>): List<String> {
        val out = mutableListOf<String>()
        for ((i, s) in sessions.withIndex()) {
            val load = volume.ofExercises(s.exercises)
            val lower = regionLoad(load, "lower")
            if (lower < LOWER_HEAVY_SETS) continue
            for (sp in sports) {
                val sport = kb.sport(sp.sportId)
                if (sport.lowerBodyLoad < 2) continue
                val rel = when (days[i]) {
                    sp.day -> "no mesmo dia do"
                    sp.day.minus(1) -> "um dia antes do"
                    sp.day.plus(1) -> "um dia depois do"
                    else -> null
                } ?: continue
                out += "${s.name} (${days[i].pt()}) ficou $rel ${sport.name} (${sp.day.pt()}) — com os dias disponíveis não havia " +
                    "distribuição sem esse encaixe. Se as pernas estiverem cansadas, use o ajuste por prontidão ou troque um dia."
            }
        }
        return out
    }

    private fun overlap(a: Map<MuscleId, Double>, b: Map<MuscleId, Double>): Double =
        a.entries.sumOf { (m, v) -> if (kb.muscle(m).volumeTracked) min(v, b[m] ?: 0.0) else 0.0 }

    private fun regionLoad(load: Map<MuscleId, Double>, region: String): Double =
        load.entries.sumOf { (m, v) -> if (kb.muscle(m).region == region) v else 0.0 }

    private fun inversions(p: List<Int>): Int {
        var n = 0
        for (i in p.indices) for (j in i + 1 until p.size) if (p[i] > p[j]) n++
        return n
    }

    companion object {
        /** Séries (fracionadas) de membros inferiores a partir das quais a sessão é "perna pesada". */
        const val LOWER_HEAVY_SETS = 8.0

        fun consecutivePairs(days: List<DayOfWeek>): Int {
            val set = days.toSet()
            return days.count { set.contains(it.plus(1)) && days.size > 1 }
        }

        fun <T> combinations(items: List<T>, k: Int): Sequence<List<T>> = sequence {
            if (k == 0) { yield(emptyList()); return@sequence }
            for (i in items.indices) {
                val head = items[i]
                for (tail in combinations(items.subList(i + 1, items.size), k - 1)) yield(listOf(head) + tail)
            }
        }

        /** Permutações em ordem lexicográfica (a identidade é a primeira). */
        fun permutations(items: List<Int>): Sequence<List<Int>> = sequence {
            if (items.size <= 1) { yield(items); return@sequence }
            for (i in items.indices) {
                val rest = items.toMutableList().also { it.removeAt(i) }
                for (p in permutations(rest)) yield(listOf(items[i]) + p)
            }
        }
    }
}
