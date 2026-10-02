package com.fitkingia.core.program

import com.fitkingia.core.knowledge.TimingRules
import com.fitkingia.core.model.LoadType
import com.fitkingia.core.model.SlotRole
import kotlin.math.ceil

/** Estima a duração de uma sessão a partir de séries, descanso e preparação de equipamento. */
class SessionClock(private val timing: TimingRules) {

    fun estimateSeconds(exercises: List<PlannedExercise>): Int {
        if (exercises.isEmpty()) return 0
        var total = timing.generalWarmupMinutes * 60
        val needsRampUp = exercises.any { it.role == SlotRole.MAIN && it.exercise.loadType !in UNLOADED }
        if (needsRampUp) total += timing.rampUpSeconds
        for (ex in exercises) total += exerciseSeconds(ex)
        return total
    }

    fun estimateMinutes(exercises: List<PlannedExercise>): Int = ceil(estimateSeconds(exercises) / 60.0).toInt()

    fun exerciseSeconds(ex: PlannedExercise): Int {
        val setup = timing.setupSeconds[ex.exercise.loadType] ?: 60
        val work = timing.workSecondsPerSet * if (ex.exercise.isUnilateral) 2 else 1
        return setup + ex.sets * (work + ex.restSeconds)
    }

    /** Segundos adicionados por uma série extra neste exercício. */
    fun secondsPerSet(ex: PlannedExercise): Int =
        timing.workSecondsPerSet * (if (ex.exercise.isUnilateral) 2 else 1) + ex.restSeconds

    companion object {
        val UNLOADED = setOf(LoadType.BODYWEIGHT, LoadType.BAND)
    }
}
