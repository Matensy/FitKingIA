package com.fitkingia.knowledge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.JsonObject
import java.io.File

// DTOs dos arquivos-fonte (knowledge/src/main/resources/knowledge/*.json).
// Os JSON são a fonte revisável em git; o fitness.db é artefato gerado.

@Serializable data class MetaSeed(val schemaVersion: String, val contentVersion: String, val lastReviewed: String, val description: String)

@Serializable data class SourceSeed(
    val id: String, val title: String, val authors: String, val organization: String? = null, val year: Int? = null,
    val venue: String? = null, val url: String? = null, val doi: String? = null, val type: String, val tier: String,
    val topic: String, val summary: String, val lastVerified: String? = null, val verification: String, val verificationNote: String,
)

@Serializable data class ClaimSourceSeed(val sourceId: String, val stance: String, val note: String? = null)

@Serializable data class ClaimSeed(
    val id: String, val statement: String, val topic: String, val evidenceLevel: String,
    val sources: List<ClaimSourceSeed>, val lastReviewed: String, val reviewStatus: String,
)

@Serializable data class RuleSeed(
    val id: String, val category: String, val description: String, val basis: String, val rationale: String,
    val claims: List<String>, val params: JsonObject, val lastReviewed: String,
)

@Serializable data class MuscleSeed(
    val id: String, val name: String, val region: String, val volumeTracked: Boolean,
    val volumeFactor: Double = 1.0, val fillPattern: String? = null, val focusRegion: String? = null,
)

@Serializable data class PatternSeed(val id: String, val name: String, val description: String, val related: List<String> = emptyList())

@Serializable data class EquipmentSeed(val id: String, val name: String, val category: String)

@Serializable data class EnvironmentSeed(val id: String, val name: String, val equipment: List<String>)

@Serializable data class ExerciseSeed(
    val id: String, val name: String, val aliases: List<String> = emptyList(), val pattern: String,
    val mechanic: String, val laterality: String, val loadType: String,
    val primary: List<String>, val secondary: List<String> = emptyList(), val equipment: List<String> = emptyList(),
    val difficulty: Int, val minTier: String, val stability: Int, val mobility: Int,
    val jointDemand: Map<String, Int> = emptyMap(),
    val instructions: List<String> = emptyList(), val commonMistakes: List<String> = emptyList(),
    val safetyNotes: List<String> = emptyList(), val progressionMethods: List<String> = emptyList(),
    val substitutes: List<String> = emptyList(),
    val staple: Int = 2,
    val maxTier: String? = null,
    val timed: Boolean = false,
    /** Subconjunto de `primary` que justifica o exercício (ex.: agachamento → quads). Vazio = todos os principais. */
    val focus: List<String> = emptyList(),
)

@Serializable data class SlotSeed(
    val pattern: String, val role: String, val baseSets: Int, val targetMuscle: String? = null,
    val preferMechanic: String? = null, val preferLaterality: String? = null,
)

@Serializable data class SessionSeed(val key: String, val name: String, val slots: List<SlotSeed>)

@Serializable data class SplitSeed(
    val id: String, val name: String, val daysPerWeek: Int, val minTier: String, val focuses: List<String>,
    val priority: Int, val rationale: String, val sessions: List<SessionSeed>, val emphasis: List<String> = emptyList(),
)

@Serializable data class SportSeed(
    val id: String, val name: String, val lowerBodyLoad: Int, val upperBodyLoad: Int, val cardioDemand: Int, val recoveryDemand: Int,
)

@Serializable data class SafetyQuestionSeed(
    val id: String, val question: String, val category: String, val outcomeIfYes: String, val message: String, val appliesTo: String? = null,
)

@Serializable data class FoodSeed(
    val id: String, val name: String, val aliases: List<String>, val kcal: Double, val proteinG: Double?, val carbsG: Double?,
    val fatG: Double?, val fiberG: Double?, val sodiumMg: Double?, val defaultServingG: Double, val servingLabel: String,
    val sourceId: String, val verification: String,
)

@Serializable data class SupplementSeed(
    val id: String, val name: String, val whatIs: String, val purpose: String, val evidenceSummary: String,
    val howStudied: String, val knownEffects: String, val limitations: String, val cautions: String,
    val evidenceLevel: String, val sources: List<String>,
)

data class Seeds(
    val meta: MetaSeed,
    val sources: List<SourceSeed>,
    val claims: List<ClaimSeed>,
    val rules: List<RuleSeed>,
    val muscles: List<MuscleSeed>,
    val patterns: List<PatternSeed>,
    val equipment: List<EquipmentSeed>,
    val environments: List<EnvironmentSeed>,
    val exercises: List<ExerciseSeed>,
    val splits: List<SplitSeed>,
    val sports: List<SportSeed>,
    val safetyQuestions: List<SafetyQuestionSeed>,
    val foods: List<FoodSeed>,
    val supplements: List<SupplementSeed>,
) {
    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        val json = Json {
            namingStrategy = JsonNamingStrategy.SnakeCase
            ignoreUnknownKeys = false
            explicitNulls = false
        }

        /** Lê os seeds empacotados no classpath (resources/knowledge). */
        fun fromClasspath(): Seeds = read { name ->
            Seeds::class.java.getResourceAsStream("/knowledge/$name.json")?.bufferedReader()?.readText()
                ?: error("Seed /knowledge/$name.json não encontrado no classpath")
        }

        /** Lê os seeds de um diretório (útil para revisar conteúdo antes de empacotar). */
        fun fromDirectory(dir: File): Seeds = read { name -> File(dir, "$name.json").readText() }

        private fun read(text: (String) -> String): Seeds = Seeds(
            meta = json.decodeFromString(text("meta")),
            sources = json.decodeFromString(text("sources")),
            claims = json.decodeFromString(text("claims")),
            rules = json.decodeFromString(text("rules")),
            muscles = json.decodeFromString(text("muscles")),
            patterns = json.decodeFromString(text("patterns")),
            equipment = json.decodeFromString(text("equipment")),
            environments = json.decodeFromString(text("environments")),
            exercises = json.decodeFromString(text("exercises")),
            splits = json.decodeFromString(text("splits")),
            sports = json.decodeFromString(text("sports")),
            safetyQuestions = json.decodeFromString(text("safety_questions")),
            foods = json.decodeFromString(text("foods")),
            supplements = json.decodeFromString(text("supplements")),
        )
    }
}
