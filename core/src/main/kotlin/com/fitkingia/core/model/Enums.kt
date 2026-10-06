package com.fitkingia.core.model

enum class Sex { MALE, FEMALE }

/** Respostas do questionário "Você já treina?". */
enum class ExperienceLevel(val label: String, val tier: TrainingTier) {
    NONE("Nunca treinei", TrainingTier.NOVICE),
    UNDER_3_MONTHS("Menos de 3 meses", TrainingTier.NOVICE),
    MONTHS_3_TO_6("3–6 meses", TrainingTier.NOVICE),
    MONTHS_6_TO_12("6–12 meses", TrainingTier.INTERMEDIATE),
    YEARS_1_TO_2("1–2 anos", TrainingTier.INTERMEDIATE),
    YEARS_2_PLUS("2+ anos", TrainingTier.ADVANCED),
}

/** Agrupamento usado pelas regras (volume, dificuldade, progressão). */
enum class TrainingTier(val label: String) {
    NOVICE("Iniciante"),
    INTERMEDIATE("Intermediário"),
    ADVANCED("Avançado"),
}

/** Como o treino resistido é prescrito. Cada objetivo de interface mapeia para um foco. */
enum class TrainingFocus(val label: String) {
    HYPERTROPHY("Hipertrofia"),
    STRENGTH("Força"),
    POWER("Potência"),
    MUSCULAR_ENDURANCE("Resistência muscular"),
    GENERAL_FITNESS("Saúde e condicionamento geral"),
}

enum class GoalCategory(val label: String) {
    BODY_COMPOSITION("Composição corporal"),
    PERFORMANCE("Performance"),
    HEALTH("Saúde e qualidade de vida"),
}

enum class Goal(
    val label: String,
    val category: GoalCategory,
    val focus: TrainingFocus,
    /** Objetivos que costumam carregar a ideia de "perda localizada" recebem um aviso baseado em evidência. */
    val spotReductionNotice: Boolean = false,
) {
    HYPERTROPHY("Hipertrofia", GoalCategory.BODY_COMPOSITION, TrainingFocus.HYPERTROPHY),
    FAT_LOSS("Perda de gordura", GoalCategory.BODY_COMPOSITION, TrainingFocus.HYPERTROPHY),
    RECOMPOSITION("Recomposição corporal", GoalCategory.BODY_COMPOSITION, TrainingFocus.HYPERTROPHY),
    MAINTENANCE("Manutenção", GoalCategory.BODY_COMPOSITION, TrainingFocus.HYPERTROPHY),
    WEIGHT_GAIN("Ganho de peso", GoalCategory.BODY_COMPOSITION, TrainingFocus.HYPERTROPHY),
    WAIST_REDUCTION("Redução de cintura / perder barriga", GoalCategory.BODY_COMPOSITION, TrainingFocus.HYPERTROPHY, spotReductionNotice = true),
    DEFINITION("Melhorar definição", GoalCategory.BODY_COMPOSITION, TrainingFocus.HYPERTROPHY, spotReductionNotice = true),
    APPEARANCE("Melhorar aparência física", GoalCategory.BODY_COMPOSITION, TrainingFocus.HYPERTROPHY),

    STRENGTH("Força", GoalCategory.PERFORMANCE, TrainingFocus.STRENGTH),
    POWER("Potência", GoalCategory.PERFORMANCE, TrainingFocus.POWER),
    MUSCULAR_ENDURANCE("Resistência muscular", GoalCategory.PERFORMANCE, TrainingFocus.MUSCULAR_ENDURANCE),
    CONDITIONING("Condicionamento", GoalCategory.PERFORMANCE, TrainingFocus.MUSCULAR_ENDURANCE),
    RUNNING("Corrida", GoalCategory.PERFORMANCE, TrainingFocus.MUSCULAR_ENDURANCE),
    SPORT_SPECIFIC("Esporte específico", GoalCategory.PERFORMANCE, TrainingFocus.STRENGTH),
    MARTIAL_ARTS("Artes marciais", GoalCategory.PERFORMANCE, TrainingFocus.STRENGTH),
    GENERAL_PERFORMANCE("Performance geral", GoalCategory.PERFORMANCE, TrainingFocus.STRENGTH),

    CARDIO_HEALTH("Melhorar condicionamento cardiovascular", GoalCategory.HEALTH, TrainingFocus.GENERAL_FITNESS),
    MOBILITY("Aumentar mobilidade", GoalCategory.HEALTH, TrainingFocus.GENERAL_FITNESS),
    BALANCE("Melhorar equilíbrio", GoalCategory.HEALTH, TrainingFocus.GENERAL_FITNESS),
    REDUCE_SEDENTARY("Reduzir sedentarismo", GoalCategory.HEALTH, TrainingFocus.GENERAL_FITNESS),
    SLEEP_ROUTINE("Melhorar rotina de sono", GoalCategory.HEALTH, TrainingFocus.GENERAL_FITNESS),
    CONSISTENCY("Criar consistência", GoalCategory.HEALTH, TrainingFocus.GENERAL_FITNESS),
}

/**
 * Regiões que o usuário pode priorizar ("quero treinar mais o bumbum"). O mapeamento região →
 * músculos vem do banco (muscles.json, campo focus_region), não do código.
 */
enum class BodyRegion(val label: String, val emoji: String) {
    // Só emojis do Emoji 5.0 ou anteriores: o Android 8 (minSdk) não desenha os mais novos (ex.: 🦵).
    GLUTES("Glúteos", "🍑"), LEGS("Pernas (coxas)", "🏃"), BACK("Costas", "🧗"), CHEST("Peito", "🏋️"),
    SHOULDERS("Ombros", "🤸"), ARMS("Braços", "💪"), CORE("Abdômen", "🧘"),
}

enum class Mechanic { COMPOUND, ISOLATION }

enum class Laterality { BILATERAL, UNILATERAL }

/** Define incrementos de carga e tempo de preparação. */
enum class LoadType { BARBELL, DUMBBELL, MACHINE, CABLE, SMITH, KETTLEBELL, BODYWEIGHT, BAND }

/** Articulações usadas para filtrar/substituir exercícios quando o usuário relata dor. Não é diagnóstico. */
enum class Joint(val label: String) {
    KNEE("joelho"),
    HIP("quadril"),
    LOWER_BACK("lombar"),
    SHOULDER("ombro"),
    ELBOW("cotovelo"),
    WRIST("punho"),
    ANKLE("tornozelo"),
    NECK("pescoço"),
}

/** Papel do exercício dentro da sessão; guia volume, descanso e o que cortar primeiro. */
enum class SlotRole(val label: String, val keepPriority: Int) {
    MAIN("Principal", 3),
    SECONDARY("Secundário", 2),
    ACCESSORY("Acessório", 1),
}

enum class EvidenceLevel(val label: String) {
    HIGH("Alta evidência"),
    MODERATE("Evidência moderada"),
    LIMITED("Evidência limitada"),
    INCONCLUSIVE("Evidência inconclusiva"),
}

/** Hierarquia de fontes descrita em docs/EVIDENCIAS.md. */
enum class SourceTier { S, A, B }

enum class SleepQuality(val label: String) { POOR("😴 Sono ruim"), NORMAL("😐 Normal"), EXCELLENT("🔥 Excelente") }

enum class SweatLevel(val label: String) { LOW("Pouco suor"), MODERATE("Suor moderado"), HIGH("Muito suor") }

enum class ActivityLevel(val label: String) {
    SEDENTARY("Sedentário"),
    LIGHT("Levemente ativo"),
    MODERATE("Moderadamente ativo"),
    VERY_ACTIVE("Muito ativo"),
    EXTREMELY_ACTIVE("Extremamente ativo"),
}

enum class EnergyGoal(val label: String) { DEFICIT("Déficit"), MAINTENANCE("Manutenção"), SURPLUS("Superávit"), RECOMPOSITION("Recomposição") }
