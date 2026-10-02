package com.fitkingia.appcore

import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.SafetyQuestion
import com.fitkingia.core.model.*
import java.time.DayOfWeek

/**
 * Respostas do questionário inicial — tudo por toque (alternativas, botões +/−), sem digitar.
 * É um rascunho mutável que a tela preenche passo a passo; [toProfile] gera o perfil que o
 * motor determinístico usa para montar o programa.
 */
data class Answers(
    var consent: Boolean = false,
    var sex: Sex? = null,
    var age: Int = 30,
    var heightCm: Int = 170,
    var weightKg: Double = 75.0,
    /** Opcional (relação cintura/altura). */
    var waistCm: Int? = null,
    var primaryGoal: Goal? = null,
    var secondaryGoal: Goal? = null,
    var experience: ExperienceLevel? = null,
    var environment: EnvironmentId? = null,
    var equipment: MutableSet<EquipmentId> = mutableSetOf(),
    /** Minutos por dia; 0 = folga. */
    var minutesByDay: MutableMap<DayOfWeek, Int> = DayOfWeek.values().associateWith { 0 }.toMutableMap(),
    /** null = o motor decide. */
    var maxDays: Int? = null,
    var sports: MutableList<SportCommitment> = mutableListOf(),
    var activity: ActivityLevel? = null,
    var safety: MutableMap<String, Boolean> = mutableMapOf(),
    /** Articulação → intensidade da dor (0–10). */
    var pains: MutableMap<Joint, Int> = linkedMapOf(),
    var preferredSplit: SplitId? = null,
    var sweat: SweatLevel = SweatLevel.MODERATE,
    var hot: Boolean = false,
) {
    fun deepCopy(): Answers = copy(
        equipment = equipment.toMutableSet(), minutesByDay = minutesByDay.toMutableMap(), sports = sports.toMutableList(),
        safety = safety.toMutableMap(), pains = LinkedHashMap(pains),
    )

    val trainingDays: List<DayAvailability>
        get() = minutesByDay.filter { it.value > 0 }.map { DayAvailability(it.key, it.value) }.sortedBy { it.day }

    fun toProfile(
        history: Set<ExerciseId> = emptySet(),
        favorites: Set<ExerciseId> = emptySet(),
        excluded: Set<ExerciseId> = emptySet(),
    ): UserProfile = UserProfile(
        name = "Você",
        age = age,
        sex = sex ?: error("sexo não respondido"),
        heightCm = heightCm.toDouble(),
        weightKg = weightKg,
        primaryGoal = primaryGoal ?: error("objetivo não respondido"),
        secondaryGoal = secondaryGoal?.takeIf { it != primaryGoal },
        experience = experience ?: error("experiência não respondida"),
        environment = environment,
        equipment = equipment.toSet(),
        availability = trainingDays,
        sports = sports.toList(),
        limitations = pains.filter { it.value > 0 }.map { JointLimitation(it.key, it.value) },
        excludedExercises = excluded,
        favoriteExercises = favorites,
        exerciseHistory = history,
        preferredSplit = preferredSplit,
        maxTrainingDays = maxDays,
        activityLevel = activity ?: ActivityLevel.MODERATE,
    )

    companion object {
        fun from(profile: UserProfile, waistCm: Int?, safety: Map<String, Boolean>, sweat: SweatLevel, hot: Boolean) = Answers(
            consent = true, sex = profile.sex, age = profile.age, heightCm = profile.heightCm.toInt(), weightKg = profile.weightKg,
            waistCm = waistCm, primaryGoal = profile.primaryGoal, secondaryGoal = profile.secondaryGoal,
            experience = profile.experience, environment = profile.environment, equipment = profile.equipment.toMutableSet(),
            minutesByDay = DayOfWeek.values().associateWith { d -> profile.availability.firstOrNull { it.day == d }?.minutes ?: 0 }.toMutableMap(),
            maxDays = profile.maxTrainingDays, sports = profile.sports.toMutableList(), activity = profile.activityLevel,
            safety = safety.toMutableMap(), pains = profile.limitations.associate { it.joint to it.severity }.toMap(LinkedHashMap()),
            preferredSplit = profile.preferredSplit, sweat = sweat, hot = hot,
        )
    }
}

enum class Step(val title: String, val subtitle: String? = null) {
    WELCOME("Bem-vindo ao FitKingIA"),
    SEX("Sexo biológico", "Usado nas fórmulas de gasto energético e em perguntas de segurança específicas."),
    AGE("Sua idade"),
    HEIGHT("Sua altura"),
    WEIGHT("Seu peso atual"),
    WAIST("Circunferência da cintura", "Opcional. Medida na altura do umbigo, sem apertar. Serve para acompanhar a cintura e calcular a relação cintura/altura."),
    GOAL("Qual é o seu objetivo principal?"),
    SECONDARY_GOAL("Tem um objetivo secundário?", "Opcional."),
    EXPERIENCE("Você já treina?"),
    ENVIRONMENT("Onde você vai treinar?"),
    EQUIPMENT("Quais equipamentos você tem?", "Já marcamos o padrão do local. Toque para ajustar."),
    DAYS("Quanto tempo você tem em cada dia?", "Toque em − e + para escolher. Dias sem tempo ficam como folga."),
    MAX_DAYS("Quantos dias por semana você quer treinar no máximo?"),
    SPORTS("Pratica algum esporte?", "O motor afasta treinos pesados de pernas dos dias de esporte com muita demanda nas pernas."),
    ACTIVITY("Fora do treino, qual é o seu nível de atividade?", "Usado na estimativa de gasto energético."),
    SAFETY("Triagem de segurança", "Responda com sinceridade. O app não faz diagnóstico: sinais de alerta pedem avaliação profissional."),
    PAIN("Onde você sente dor?", "O motor evita exercícios que exigem muito dessas articulações. Isso não é diagnóstico."),
    SPLIT("Prefere alguma divisão de treino?", "Se não tiver preferência, o motor escolhe pela sua frequência e experiência."),
    HYDRATION("Hidratação"),
    SUMMARY("Resumo"),
}

object Questionnaire {
    /** Opções de tempo por dia (minutos). 0 = folga. */
    val DAY_MINUTES = listOf(0, 30, 40, 45, 50, 60, 75, 90, 120)

    fun steps(a: Answers): List<Step> = Step.values().filter { visible(it, a) }

    fun visible(step: Step, a: Answers): Boolean = when (step) {
        Step.PAIN -> a.safety["current_pain"] == true
        else -> true
    }

    fun safetyQuestions(kb: KnowledgeBase, a: Answers): List<SafetyQuestion> =
        kb.safetyQuestions.filter { it.appliesTo == null || it.appliesTo == a.sex }

    /** Mensagem do que falta para avançar; null = pode avançar. */
    fun blocker(step: Step, a: Answers, kb: KnowledgeBase): String? = when (step) {
        Step.WELCOME -> if (!a.consent) "Toque em \"Concordo\" para continuar." else null
        Step.SEX -> if (a.sex == null) "Escolha uma opção." else null
        Step.GOAL -> if (a.primaryGoal == null) "Escolha seu objetivo principal." else null
        Step.EXPERIENCE -> if (a.experience == null) "Escolha uma opção." else null
        Step.ENVIRONMENT -> if (a.environment == null) "Escolha onde vai treinar." else null
        Step.DAYS -> {
            val min = kb.ruleSet.frequency.params.minSessionMinutes
            if (a.minutesByDay.values.none { it >= min }) "Marque pelo menos um dia com $min minutos ou mais." else null
        }
        Step.ACTIVITY -> if (a.activity == null) "Escolha uma opção." else null
        Step.SAFETY -> {
            val missing = safetyQuestions(kb, a).count { it.id !in a.safety }
            if (missing > 0) "Responda todas as perguntas ($missing restante${if (missing > 1) "s" else ""})." else null
        }
        Step.PAIN -> if (a.pains.values.none { it > 0 }) "Toque na região da dor e escolha a intensidade." else null
        else -> null
    }

    /** Equipamento padrão do ambiente escolhido. */
    fun selectEnvironment(a: Answers, kb: KnowledgeBase, env: EnvironmentId) {
        a.environment = env
        a.equipment = kb.environment(env).equipment.toMutableSet()
    }

    fun selectSex(a: Answers, sex: Sex) {
        if (a.sex == sex) return
        val first = a.sex == null
        a.sex = sex
        if (first) {
            a.heightCm = if (sex == Sex.MALE) 175 else 162
            a.weightKg = if (sex == Sex.MALE) 78.0 else 65.0
        }
        // Pergunta de gestação só existe para o sexo feminino.
        if (sex == Sex.MALE) a.safety.remove("pregnancy")
    }

    /** Intensidade da dor por toque. */
    val PAIN_LEVELS = listOf(2 to "Leve (1–3)", 5 to "Moderada (4–6)", 8 to "Forte (7–10)")

    val SPORT_INTENSITY = listOf(1 to "Leve", 2 to "Moderado", 3 to "Pesado")

    /** Atalhos para preencher a semana. */
    val DAY_PRESETS: List<Pair<String, Map<DayOfWeek, Int>>> = listOf(
        "Seg/Qua/Sex · 60 min" to mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.WEDNESDAY to 60, DayOfWeek.FRIDAY to 60),
        "Seg a Sex · 45 min" to (1..5).associate { DayOfWeek.of(it) to 45 },
        "Seg a Sex · 60 min" to (1..5).associate { DayOfWeek.of(it) to 60 },
        "Ter/Qui · 60 min + Sáb · 90 min" to mapOf(DayOfWeek.TUESDAY to 60, DayOfWeek.THURSDAY to 60, DayOfWeek.SATURDAY to 90),
        "Todos os dias · 40 min" to DayOfWeek.values().associateWith { 40 },
    )

    fun applyPreset(a: Answers, preset: Map<DayOfWeek, Int>) {
        a.minutesByDay = DayOfWeek.values().associateWith { preset[it] ?: 0 }.toMutableMap()
    }

    fun stepMinutes(current: Int, delta: Int): Int {
        val i = DAY_MINUTES.indexOf(current).let { if (it < 0) DAY_MINUTES.indexOfFirst { m -> m >= current }.coerceAtLeast(0) else it }
        return DAY_MINUTES[(i + delta).coerceIn(0, DAY_MINUTES.lastIndex)]
    }
}
