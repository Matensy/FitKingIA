package com.fitkingia.core.knowledge

import com.fitkingia.core.model.*

data class Muscle(
    val id: MuscleId,
    val name: String,
    val region: String,
    /** Músculos rastreados recebem meta semanal de séries no dashboard de volume. */
    val volumeTracked: Boolean,
    /** Multiplicador da meta semanal (ex.: panturrilha 0,8). Regra do sistema, não fato. */
    val volumeFactor: Double = 1.0,
    /** Padrão usado para "completar" volume quando nenhum exercício do plano trabalha este músculo diretamente. */
    val fillPattern: PatternId? = null,
)

data class MovementPattern(
    val id: PatternId,
    val name: String,
    val description: String,
    /** Padrões aparentados recebem pontuação parcial no motor de substituição (ex.: agachar ↔ afundo). */
    val related: Set<PatternId> = emptySet(),
)

data class Equipment(val id: EquipmentId, val name: String, val category: String)

/** Ambiente de treino com equipamento padrão (o usuário pode ajustar a lista). */
data class TrainingEnvironment(val id: EnvironmentId, val name: String, val equipment: Set<EquipmentId>)

data class Exercise(
    val id: ExerciseId,
    val name: String,
    val aliases: List<String> = emptyList(),
    val pattern: PatternId,
    val mechanic: Mechanic,
    val laterality: Laterality,
    val loadType: LoadType,
    val primaryMuscles: Set<MuscleId>,
    val secondaryMuscles: Set<MuscleId> = emptySet(),
    /** Todos os itens são necessários (AND). Variações com outro equipamento são exercícios distintos. */
    val equipment: Set<EquipmentId> = emptySet(),
    /** 1 (simples) a 5 (técnico). */
    val difficulty: Int,
    val minTier: TrainingTier = TrainingTier.NOVICE,
    /** Demanda de estabilidade 1–5. */
    val stability: Int,
    /** Demanda de mobilidade 1–5. */
    val mobility: Int,
    /** Demanda por articulação 0 (nenhuma) a 3 (alta). Usado para dor relatada — não é diagnóstico. */
    val jointDemand: Map<Joint, Int> = emptyMap(),
    val instructions: List<String> = emptyList(),
    val commonMistakes: List<String> = emptyList(),
    val safetyNotes: List<String> = emptyList(),
    val progressionMethods: List<String> = emptyList(),
    /** Substituições curadas, em ordem de preferência. O motor combina com a similaridade calculada. */
    val curatedSubstitutes: List<ExerciseId> = emptyList(),
    /** 0–3: quão indicado é como escolha padrão quando há equipamento para tudo (curadoria). */
    val staple: Int = 2,
    /** Regressões (ex.: barra assistida): acima deste nível só entram se não houver alternativa. */
    val maxTier: TrainingTier? = null,
    /** Isometria/carregamento: prescrito em segundos, não em repetições. */
    val timed: Boolean = false,
) {
    val isUnilateral get() = laterality == Laterality.UNILATERAL
    fun demand(joint: Joint): Int = jointDemand[joint] ?: 0
    fun works(muscle: MuscleId): Double = when (muscle) {
        in primaryMuscles -> 1.0
        in secondaryMuscles -> 0.5
        else -> 0.0
    }
}

data class Slot(
    val pattern: PatternId,
    val role: SlotRole,
    val baseSets: Int,
    /** Opcional: exige que o músculo esteja entre os principais do exercício. */
    val targetMuscle: MuscleId? = null,
    val preferMechanic: Mechanic? = null,
    val preferLaterality: Laterality? = null,
)

data class SessionTemplate(val key: String, val name: String, val slots: List<Slot>)

data class SplitTemplate(
    val id: SplitId,
    val name: String,
    val daysPerWeek: Int,
    val minTier: TrainingTier,
    /** Focos para os quais o template é indicado; vazio = qualquer. */
    val focuses: Set<TrainingFocus>,
    /** Maior = preferido quando mais de um template serve. */
    val priority: Int,
    val rationale: String,
    val sessions: List<SessionTemplate>,
) {
    fun suits(tier: TrainingTier, focus: TrainingFocus) =
        tier >= minTier && (focuses.isEmpty() || focus in focuses)
}

/** Esporte praticado fora do app; influencia o agendamento (ex.: kickboxing tem alta demanda de pernas). */
data class Sport(
    val id: SportId,
    val name: String,
    /** 0–3 */
    val lowerBodyLoad: Int,
    /** 0–3 */
    val upperBodyLoad: Int,
    /** 0–3 */
    val cardioDemand: Int,
    /** 0–3 */
    val recoveryDemand: Int,
)

enum class SafetyOutcome { BLOCK, CAUTION, INFO }

data class SafetyQuestion(
    val id: String,
    val question: String,
    val category: String,
    /** O que acontece quando a resposta é "sim". */
    val outcomeIfYes: SafetyOutcome,
    val message: String,
    /** Pergunta aplicável apenas a um sexo (ex.: gestação). */
    val appliesTo: Sex? = null,
)

data class Food(
    val id: FoodId,
    val name: String,
    val aliases: List<String>,
    /** Valores por 100 g. Campos nulos = não verificados ainda (não inventamos números). */
    val kcal: Double,
    val proteinG: Double?,
    val carbsG: Double?,
    val fatG: Double?,
    val fiberG: Double?,
    val sodiumMg: Double?,
    /** Porção padrão sugerida pela interface (estimativa editável, não vem da tabela de composição). */
    val defaultServingG: Double,
    val servingLabel: String,
    val sourceId: SourceId,
    val verification: String,
)

data class Supplement(
    val id: SupplementId,
    val name: String,
    val whatIs: String,
    val purpose: String,
    val evidenceSummary: String,
    val howStudied: String,
    val knownEffects: String,
    val limitations: String,
    val cautions: String,
    val evidenceLevel: EvidenceLevel,
    val sourceIds: List<SourceId>,
)
