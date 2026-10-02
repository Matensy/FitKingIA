package com.fitkingia.core.substitution

import com.fitkingia.core.knowledge.Exercise
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.normalized
import com.fitkingia.core.model.*
import com.fitkingia.core.program.Eligibility
import com.fitkingia.core.program.UserConstraints

/** Exercise Finder: "Preciso de exercício para quadríceps sem barra." */
class ExerciseFinder(private val kb: KnowledgeBase) {

    data class Query(
        val muscle: MuscleId? = null,
        val pattern: PatternId? = null,
        val withoutEquipment: Set<EquipmentId> = emptySet(),
        val onlyEquipment: Set<EquipmentId>? = null,
        val homeFriendly: Boolean = false,
    )

    fun find(q: Query, constraints: UserConstraints? = null, limit: Int = 10): List<Exercise> =
        kb.exercises.asSequence()
            .filter { q.muscle == null || q.muscle in it.primaryMuscles }
            .filter { q.pattern == null || it.pattern == q.pattern }
            .filter { it.equipment.none { e -> e in q.withoutEquipment } }
            .filter { q.onlyEquipment == null || q.onlyEquipment.containsAll(it.equipment) }
            .filter { !q.homeFriendly || it.equipment.all { e -> kb.equipment(e).category in HOME_CATEGORIES } }
            .filter { constraints == null || Eligibility.isAllowed(it, constraints) }
            .sortedWith(compareBy<Exercise> { if (q.muscle != null && q.muscle in it.primaryMuscles) 0 else 1 }
                .thenBy { it.difficulty }.thenBy { it.id.value })
            .take(limit).toList()

    /** Resolve um músculo pelo nome em português ("quadríceps", "posterior"). */
    fun muscleByName(text: String): MuscleId? {
        val q = text.normalized()
        return kb.muscles.firstOrNull { it.name.normalized() == q || it.id.value == q }?.id
            ?: kb.muscles.firstOrNull { it.name.normalized().contains(q) || q.contains(it.name.normalized()) }?.id
    }

    companion object {
        val HOME_CATEGORIES = setOf("free_weight", "accessory", "bodyweight")
    }
}
