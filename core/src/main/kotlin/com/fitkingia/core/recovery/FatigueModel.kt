package com.fitkingia.core.recovery

import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.ExerciseId
import com.fitkingia.core.model.MuscleId
import com.fitkingia.core.model.SportId
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

data class SetEvent(val exerciseId: ExerciseId, val at: LocalDateTime, val rir: Int?)
data class SportEvent(val sportId: SportId, val at: LocalDateTime, val intensity: Int = 2)

/**
 * Fadiga estimada por músculo (0–100) com decaimento exponencial. Modelo heurístico para
 * ordenar prioridades da próxima sessão — não mede dano muscular nem recuperação real.
 */
class FatigueModel(private val kb: KnowledgeBase) {
    private val r = kb.ruleSet.fatigue.params
    private val v = kb.ruleSet.volume.params

    fun estimate(sets: List<SetEvent>, sports: List<SportEvent>, now: LocalDateTime): Map<MuscleId, Int> {
        val acc = mutableMapOf<MuscleId, Double>()
        fun decay(at: LocalDateTime): Double {
            val h = Duration.between(at, now).toMinutes() / 60.0
            return if (h < 0) 0.0 else 0.5.pow(h / r.halfLifeHours)
        }
        for (s in sets) {
            val ex = kb.exerciseOrNull(s.exerciseId) ?: continue
            val hard = s.rir == null || s.rir <= r.hardSetMaxRir
            val base = r.pointsPerHardSet * (if (hard) 1.0 else r.easySetFactor) * decay(s.at)
            ex.primaryMuscles.forEach { acc.merge(it, base * v.primaryCredit, Double::plus) }
            ex.secondaryMuscles.forEach { acc.merge(it, base * v.secondaryCredit, Double::plus) }
        }
        for (e in sports) {
            val sport = kb.sport(e.sportId)
            val k = r.sportPointsPerLevel * (e.intensity / 2.0) * decay(e.at)
            for (m in kb.trackedMuscles) {
                val level = when (m.region) { "lower" -> sport.lowerBodyLoad; "upper" -> sport.upperBodyLoad; else -> 0 }
                if (level > 0) acc.merge(m.id, level * k, Double::plus)
            }
        }
        return kb.trackedMuscles.associate { it.id to min(100.0, acc[it.id] ?: 0.0).roundToInt() }
    }
}
