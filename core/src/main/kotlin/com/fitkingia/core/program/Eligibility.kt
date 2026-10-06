package com.fitkingia.core.program

import com.fitkingia.core.knowledge.Exercise
import com.fitkingia.core.model.*

/** Restrições do usuário que valem para seleção e para substituição. */
data class UserConstraints(
    val tier: TrainingTier,
    val equipment: Set<EquipmentId>,
    val limitations: List<JointLimitation> = emptyList(),
    val excluded: Set<ExerciseId> = emptySet(),
    val favorites: Set<ExerciseId> = emptySet(),
    val history: Set<ExerciseId> = emptySet(),
    val focus: TrainingFocus? = null,
    /** Músculos das regiões que o usuário priorizou (resolvidos pelo banco). */
    val priorityMuscles: Set<MuscleId> = emptySet(),
) {
    companion object {
        fun of(p: UserProfile) = UserConstraints(
            tier = p.tier,
            equipment = p.equipment,
            limitations = p.limitations,
            excluded = p.excludedExercises,
            favorites = p.favoriteExercises,
            history = p.exerciseHistory,
            focus = p.focus,
        )
    }
}

enum class IneligibleReason(val label: String) {
    EQUIPMENT("equipamento indisponível"),
    EXPERIENCE("exige mais experiência"),
    EXCLUDED("excluído pelo usuário"),
    JOINT("alta demanda na articulação com dor relatada"),
}

object Eligibility {
    fun check(ex: Exercise, c: UserConstraints): IneligibleReason? = when {
        ex.id in c.excluded -> IneligibleReason.EXCLUDED
        !c.equipment.containsAll(ex.equipment) -> IneligibleReason.EQUIPMENT
        ex.minTier > c.tier -> IneligibleReason.EXPERIENCE
        c.limitations.any { ex.demand(it.joint) > it.maxAllowedDemand } -> IneligibleReason.JOINT
        else -> null
    }

    fun isAllowed(ex: Exercise, c: UserConstraints) = check(ex, c) == null
}
