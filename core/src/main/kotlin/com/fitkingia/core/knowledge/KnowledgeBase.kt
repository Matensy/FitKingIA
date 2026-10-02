package com.fitkingia.core.knowledge

import com.fitkingia.core.model.*

/**
 * Visão em memória do banco de conhecimento (fitness.db). O core não sabe de onde os
 * dados vieram: JDBC na JVM, Room no Android ou fixtures em testes.
 */
class KnowledgeBase(
    val muscles: List<Muscle>,
    val patterns: List<MovementPattern>,
    val equipment: List<Equipment>,
    val environments: List<TrainingEnvironment>,
    val exercises: List<Exercise>,
    val splits: List<SplitTemplate>,
    val sports: List<Sport>,
    val safetyQuestions: List<SafetyQuestion>,
    val foods: List<Food>,
    val supplements: List<Supplement>,
    val sources: List<EvidenceSource>,
    val claims: List<Claim>,
    val rules: List<RuleMeta>,
    val ruleSet: RuleSet,
    val meta: Map<String, String> = emptyMap(),
) {
    private val musclesById = muscles.associateBy { it.id }
    private val patternsById = patterns.associateBy { it.id }
    private val equipmentById = equipment.associateBy { it.id }
    private val environmentsById = environments.associateBy { it.id }
    private val exercisesById = exercises.associateBy { it.id }
    private val splitsById = splits.associateBy { it.id }
    private val sportsById = sports.associateBy { it.id }
    private val sourcesById = sources.associateBy { it.id }
    private val claimsById = claims.associateBy { it.id }
    private val rulesById = rules.associateBy { it.id }

    val trackedMuscles: List<Muscle> = muscles.filter { it.volumeTracked }

    fun muscle(id: MuscleId) = musclesById[id] ?: error("Músculo desconhecido: $id")
    fun pattern(id: PatternId) = patternsById[id] ?: error("Padrão desconhecido: $id")
    fun equipment(id: EquipmentId) = equipmentById[id] ?: error("Equipamento desconhecido: $id")
    fun environment(id: EnvironmentId) = environmentsById[id] ?: error("Ambiente desconhecido: $id")
    fun exercise(id: ExerciseId) = exercisesById[id] ?: error("Exercício desconhecido: $id")
    fun exerciseOrNull(id: ExerciseId) = exercisesById[id]
    fun split(id: SplitId) = splitsById[id] ?: error("Divisão desconhecida: $id")
    fun sport(id: SportId) = sportsById[id] ?: error("Esporte desconhecido: $id")
    fun source(id: SourceId) = sourcesById[id] ?: error("Fonte desconhecida: $id")
    fun claim(id: ClaimId) = claimsById[id] ?: error("Afirmação desconhecida: $id")
    fun rule(id: RuleId) = rulesById[id] ?: error("Regra desconhecida: $id")
    fun ruleOrNull(id: RuleId) = rulesById[id]

    fun muscleName(id: MuscleId) = musclesById[id]?.name ?: id.value
    fun patternName(id: PatternId) = patternsById[id]?.name ?: id.value

    /** Busca por nome ou apelido, sem acento/caixa. */
    fun findExercise(query: String): Exercise? {
        val q = query.normalized()
        return exercisesById[ExerciseId(query)]
            ?: exercises.firstOrNull { it.name.normalized() == q || it.aliases.any { a -> a.normalized() == q } }
            ?: exercises.firstOrNull { it.name.normalized().contains(q) || it.aliases.any { a -> a.normalized().contains(q) } }
    }

    /** Cadeia recomendação → regra → evidência → fonte. */
    fun evidenceChain(ruleId: RuleId): EvidenceChain {
        val rule = rule(ruleId)
        val claims = rule.claimIds.map { claim(it) }
        return EvidenceChain(rule, claims.map { c -> c to c.sources.map { source(it.sourceId) to it } })
    }
}

data class EvidenceChain(
    val rule: RuleMeta,
    val claims: List<Pair<Claim, List<Pair<EvidenceSource, ClaimSource>>>>,
)

private val accents = Regex("\\p{InCombiningDiacriticalMarks}+")

/** Minúsculas, sem acentos e espaços extras — usado em buscas tolerantes. */
fun String.normalized(): String =
    java.text.Normalizer.normalize(lowercase().trim(), java.text.Normalizer.Form.NFD)
        .replace(accents, "")
        .replace(Regex("\\s+"), " ")
