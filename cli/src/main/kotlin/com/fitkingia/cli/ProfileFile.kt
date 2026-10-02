package com.fitkingia.cli

import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import java.io.File
import java.time.DayOfWeek

@Serializable
data class SportEntry(val sport: String, val day: String, val intensity: Int = 2)

@Serializable
data class LimitationEntry(val joint: String, val severity: Int, val note: String? = null)

/** Formato do perfil em JSON (ver examples/perfil-exemplo.json). */
@Serializable
data class ProfileFile(
    val name: String,
    val age: Int,
    val sex: String,
    val heightCm: Double,
    val weightKg: Double,
    val primaryGoal: String,
    val secondaryGoal: String? = null,
    val experience: String,
    val environment: String? = null,
    val equipment: List<String>? = null,
    val availability: Map<String, Int>,
    val sports: List<SportEntry> = emptyList(),
    val limitations: List<LimitationEntry> = emptyList(),
    val excludedExercises: List<String> = emptyList(),
    val favoriteExercises: List<String> = emptyList(),
    val preferredSplit: String? = null,
    val activityLevel: String = "MODERATE",
    val safetyAnswers: Map<String, Boolean> = emptyMap(),
) {
    fun toProfile(kb: KnowledgeBase): UserProfile {
        val env = environment?.let { kb.environment(EnvironmentId(it)) }
        return UserProfile(
            name = name, age = age, sex = Sex.valueOf(sex), heightCm = heightCm, weightKg = weightKg,
            primaryGoal = Goal.valueOf(primaryGoal), secondaryGoal = secondaryGoal?.let(Goal::valueOf),
            experience = ExperienceLevel.valueOf(experience), environment = env?.id,
            equipment = equipment?.map(::EquipmentId)?.toSet() ?: env?.equipment ?: emptySet(),
            availability = availability.map { (d, m) -> DayAvailability(DayOfWeek.valueOf(d), m) },
            sports = sports.map { SportCommitment(SportId(it.sport), DayOfWeek.valueOf(it.day), it.intensity) },
            limitations = limitations.map { JointLimitation(Joint.valueOf(it.joint), it.severity, it.note) },
            excludedExercises = excludedExercises.map(::ExerciseId).toSet(),
            favoriteExercises = favoriteExercises.map(::ExerciseId).toSet(),
            preferredSplit = preferredSplit?.let(::SplitId),
            activityLevel = ActivityLevel.valueOf(activityLevel),
        )
    }

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val json = Json { namingStrategy = JsonNamingStrategy.SnakeCase; ignoreUnknownKeys = false }
        fun read(file: File): ProfileFile = json.decodeFromString(file.readText())
        fun default(): ProfileFile = json.decodeFromString(
            ProfileFile::class.java.getResourceAsStream("/perfil-exemplo.json")!!.bufferedReader().readText()
        )
    }
}
