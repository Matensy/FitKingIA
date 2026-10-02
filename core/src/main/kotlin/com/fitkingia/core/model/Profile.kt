package com.fitkingia.core.model

import java.time.DayOfWeek

data class DayAvailability(val day: DayOfWeek, val minutes: Int)

/** Esporte praticado num dia da semana. intensity: 1 leve, 2 moderado, 3 pesado. */
data class SportCommitment(val sportId: SportId, val day: DayOfWeek, val intensity: Int = 2)

/** Dor/limitação relatada pelo usuário. severity 0–10 (escala de dor percebida). Não é diagnóstico. */
data class JointLimitation(val joint: Joint, val severity: Int, val note: String? = null) {
    /** Demanda articular máxima tolerada pelos filtros do motor (regra conservadora). */
    val maxAllowedDemand: Int
        get() = when {
            severity >= 7 -> 0
            severity >= 4 -> 1
            severity >= 1 -> 2
            else -> 3
        }
}

data class UserProfile(
    val name: String,
    val age: Int,
    val sex: Sex,
    val heightCm: Double,
    val weightKg: Double,
    val primaryGoal: Goal,
    val secondaryGoal: Goal? = null,
    val experience: ExperienceLevel,
    val environment: EnvironmentId? = null,
    val equipment: Set<EquipmentId>,
    /** Somente os dias disponíveis, com o tempo de cada um. */
    val availability: List<DayAvailability>,
    val sports: List<SportCommitment> = emptyList(),
    val limitations: List<JointLimitation> = emptyList(),
    val excludedExercises: Set<ExerciseId> = emptySet(),
    val favoriteExercises: Set<ExerciseId> = emptySet(),
    /** Exercícios já executados pelo usuário (continuidade na seleção). */
    val exerciseHistory: Set<ExerciseId> = emptySet(),
    val preferredSplit: SplitId? = null,
    val maxTrainingDays: Int? = null,
    val activityLevel: ActivityLevel = ActivityLevel.MODERATE,
) {
    val tier: TrainingTier get() = experience.tier
    val focus: TrainingFocus get() = primaryGoal.focus
}
