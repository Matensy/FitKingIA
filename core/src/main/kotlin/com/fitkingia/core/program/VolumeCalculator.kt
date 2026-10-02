package com.fitkingia.core.program

import com.fitkingia.core.knowledge.VolumeRules
import com.fitkingia.core.model.MuscleId

/** Volume semanal por músculo com contagem fracionada (direta 1,0; indireta 0,5 — configurável). */
class VolumeCalculator(private val rules: VolumeRules) {

    fun credit(ex: PlannedExercise, muscle: MuscleId): Double = when (muscle) {
        in ex.exercise.primaryMuscles -> rules.primaryCredit
        in ex.exercise.secondaryMuscles -> rules.secondaryCredit
        else -> 0.0
    }

    fun ofExercises(exercises: List<PlannedExercise>): Map<MuscleId, Double> {
        val out = linkedMapOf<MuscleId, Double>()
        for (ex in exercises) {
            for (m in ex.exercise.primaryMuscles) out[m] = (out[m] ?: 0.0) + ex.sets * rules.primaryCredit
            for (m in ex.exercise.secondaryMuscles) out[m] = (out[m] ?: 0.0) + ex.sets * rules.secondaryCredit
        }
        return out
    }

    fun weekly(sessions: List<PlannedSession>): Map<MuscleId, Double> = ofExercises(sessions.flatMap { it.exercises })
}
