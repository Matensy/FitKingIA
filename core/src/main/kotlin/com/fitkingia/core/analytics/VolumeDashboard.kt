package com.fitkingia.core.analytics

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.Muscle
import com.fitkingia.core.knowledge.VolumeTarget
import com.fitkingia.core.progression.ExerciseLog
import com.fitkingia.core.program.Program
import com.fitkingia.core.program.VolumeCalculator
import com.fitkingia.core.program.pt
import com.fitkingia.core.model.MuscleId
import java.time.DayOfWeek
import kotlin.math.roundToInt

data class MuscleVolumeStatus(
    val muscle: Muscle,
    val doneSets: Double,
    val plannedSets: Double,
    val expectedSoFar: Double,
    val target: VolumeTarget,
    val pctOfPlanned: Int,
    val bar: String,
    val message: Explanation?,
)

/** Dashboard de volume da semana: realizado vs planejado, por músculo, com contagem fracionada. */
class VolumeDashboard(private val kb: KnowledgeBase) {
    private val v = kb.ruleSet.volume.params

    fun week(program: Program, logs: List<ExerciseLog>, asOf: DayOfWeek): List<MuscleVolumeStatus> {
        val calc = VolumeCalculator(v)
        val planned = calc.weekly(program.sessions)
        val expected = calc.weekly(program.sessions.filter { it.day != null && it.day <= asOf })
        val done = mutableMapOf<MuscleId, Double>()
        for (log in logs) {
            val ex = kb.exerciseOrNull(log.exerciseId) ?: continue
            val sets = log.sets.count { it.reps > 0 }
            ex.primaryMuscles.forEach { done.merge(it, sets * v.primaryCredit, Double::plus) }
            ex.secondaryMuscles.forEach { done.merge(it, sets * v.secondaryCredit, Double::plus) }
        }
        return kb.trackedMuscles.map { m ->
            val p = planned[m.id] ?: 0.0
            val d = done[m.id] ?: 0.0
            val e = expected[m.id] ?: 0.0
            val pct = if (p > 0) (d / p * 100).roundToInt() else 0
            val msg = when {
                e > 0 && d < e * LAG_THRESHOLD -> Explanation.rule(
                    "Seu volume de ${m.name.lowercase()} está menor que o planejado para este ciclo " +
                        "(${fmt(d)} de ${fmt(e)} séries esperadas até ${dayPt(asOf)}).", kb.ruleSet.volume.id,
                )
                p > 0 && d > program.volumeTargets.getValue(m.id).max -> Explanation.rule(
                    "Volume de ${m.name.lowercase()} acima do teto planejado (${fmt(d)} séries).", kb.ruleSet.volume.id,
                )
                else -> null
            }
            MuscleVolumeStatus(m, d, p, e, program.volumeTargets.getValue(m.id), pct, bar(d, p), msg)
        }
    }

    companion object {
        const val LAG_THRESHOLD = 0.8
        fun bar(done: Double, planned: Double, width: Int = 10): String {
            val filled = if (planned <= 0) 0 else ((done / planned) * width).roundToInt().coerceIn(0, width)
            return "█".repeat(filled) + "░".repeat(width - filled)
        }
        fun fmt(v: Double) = com.fitkingia.core.model.Fmt.num(v)
        private fun dayPt(d: DayOfWeek) = d.pt()
    }
}

/** Consistência: sessões feitas / planejadas no período. */
object Consistency {
    fun pct(planned: Int, done: Int): Int = if (planned == 0) 0 else (done.coerceAtMost(planned) * 100.0 / planned).roundToInt()
}
