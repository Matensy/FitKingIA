package com.fitkingia.core

import com.fitkingia.core.knowledge.*
import com.fitkingia.core.model.*
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Base de conhecimento mínima e estável para testar o motor sem depender do conteúdo real
 * (o conteúdo real é testado no módulo knowledge).
 */
object Fixtures {
    val TODAY: LocalDate = LocalDate.of(2026, 10, 2)

    fun m(id: String) = MuscleId(id)
    fun p(id: String) = PatternId(id)
    fun e(id: String) = EquipmentId(id)
    fun x(id: String) = ExerciseId(id)

    private fun ex(
        id: String, pattern: String, mech: Mechanic, load: LoadType, primary: List<String>, secondary: List<String> = emptyList(),
        equipment: List<String> = emptyList(), difficulty: Int = 2, tier: TrainingTier = TrainingTier.NOVICE, stability: Int = 2,
        joints: Map<Joint, Int> = emptyMap(), subs: List<String> = emptyList(), lat: Laterality = Laterality.BILATERAL,
        staple: Int = 2, timed: Boolean = false,
    ) = Exercise(
        ExerciseId(id), id.replace('_', ' '), emptyList(), p(pattern), mech, lat, load, primary.map(::m).toSet(), secondary.map(::m).toSet(),
        equipment.map(::e).toSet(), difficulty, tier, stability, 2, joints, listOf("passo"), emptyList(), emptyList(), emptyList(),
        subs.map(::x), staple, null, timed,
    )

    val exercises = listOf(
        ex("back_squat", "squat", Mechanic.COMPOUND, LoadType.BARBELL, listOf("quads", "glutes"), equipment = listOf("barbell", "rack"),
            difficulty = 3, stability = 3, joints = mapOf(Joint.KNEE to 2, Joint.LOWER_BACK to 2), subs = listOf("leg_press", "goblet_squat"), staple = 3),
        ex("leg_press", "squat", Mechanic.COMPOUND, LoadType.MACHINE, listOf("quads", "glutes"), equipment = listOf("leg_press"),
            difficulty = 1, stability = 1, joints = mapOf(Joint.KNEE to 2, Joint.LOWER_BACK to 1), staple = 3),
        ex("goblet_squat", "squat", Mechanic.COMPOUND, LoadType.DUMBBELL, listOf("quads", "glutes"), equipment = listOf("dumbbells"),
            difficulty = 1, joints = mapOf(Joint.KNEE to 2)),
        ex("lunge", "lunge", Mechanic.COMPOUND, LoadType.DUMBBELL, listOf("quads", "glutes"), equipment = listOf("dumbbells"),
            joints = mapOf(Joint.KNEE to 3), lat = Laterality.UNILATERAL),
        ex("rdl", "hinge", Mechanic.COMPOUND, LoadType.BARBELL, listOf("hamstrings", "glutes"), equipment = listOf("barbell"),
            difficulty = 3, joints = mapOf(Joint.LOWER_BACK to 2, Joint.KNEE to 1), staple = 3),
        ex("glute_bridge", "hinge", Mechanic.COMPOUND, LoadType.BODYWEIGHT, listOf("glutes"), listOf("hamstrings"), difficulty = 1, stability = 1),
        ex("leg_curl", "knee_flexion", Mechanic.ISOLATION, LoadType.MACHINE, listOf("hamstrings"), equipment = listOf("leg_curl"), difficulty = 1, stability = 1, staple = 3),
        ex("bench_press", "horizontal_push", Mechanic.COMPOUND, LoadType.BARBELL, listOf("chest"), listOf("triceps", "front_delts"),
            listOf("barbell", "bench"), difficulty = 3, stability = 3, staple = 3, subs = listOf("db_bench", "push_up")),
        ex("db_bench", "horizontal_push", Mechanic.COMPOUND, LoadType.DUMBBELL, listOf("chest"), listOf("triceps", "front_delts"),
            listOf("dumbbells", "bench"), staple = 3),
        ex("push_up", "horizontal_push", Mechanic.COMPOUND, LoadType.BODYWEIGHT, listOf("chest"), listOf("triceps"), difficulty = 1),
        ex("pull_up", "vertical_pull", Mechanic.COMPOUND, LoadType.BODYWEIGHT, listOf("lats"), listOf("biceps"), listOf("pull_up_bar"),
            difficulty = 4, tier = TrainingTier.INTERMEDIATE, stability = 3, staple = 3),
        ex("lat_pulldown", "vertical_pull", Mechanic.COMPOUND, LoadType.MACHINE, listOf("lats"), listOf("biceps"), listOf("lat_pulldown"), difficulty = 1, staple = 3),
        ex("straight_arm_pulldown", "vertical_pull", Mechanic.ISOLATION, LoadType.CABLE, listOf("lats"), equipment = listOf("cable")),
        ex("barbell_row", "horizontal_pull", Mechanic.COMPOUND, LoadType.BARBELL, listOf("lats", "mid_back"), listOf("biceps"), listOf("barbell"),
            difficulty = 3, staple = 3),
        ex("one_arm_row", "horizontal_pull", Mechanic.COMPOUND, LoadType.DUMBBELL, listOf("lats", "mid_back"), listOf("biceps"),
            listOf("dumbbells", "bench"), lat = Laterality.UNILATERAL),
        ex("cable_row", "horizontal_pull", Mechanic.COMPOUND, LoadType.CABLE, listOf("mid_back", "lats"), listOf("biceps"), listOf("cable"), difficulty = 1, staple = 3),
        ex("band_row", "horizontal_pull", Mechanic.COMPOUND, LoadType.BAND, listOf("mid_back", "lats"), listOf("biceps"), listOf("bands"), difficulty = 1, staple = 1),
        ex("db_curl", "elbow_flexion", Mechanic.ISOLATION, LoadType.DUMBBELL, listOf("biceps"), equipment = listOf("dumbbells"), difficulty = 1, staple = 3),
        ex("pushdown", "elbow_extension", Mechanic.ISOLATION, LoadType.CABLE, listOf("triceps"), equipment = listOf("cable"), difficulty = 1, staple = 3),
        ex("db_ohe", "elbow_extension", Mechanic.ISOLATION, LoadType.DUMBBELL, listOf("triceps"), equipment = listOf("dumbbells")),
        ex("lateral_raise", "shoulder_abduction", Mechanic.ISOLATION, LoadType.DUMBBELL, listOf("side_delts"), equipment = listOf("dumbbells"), difficulty = 1, staple = 3),
        ex("plank", "anti_extension", Mechanic.ISOLATION, LoadType.BODYWEIGHT, listOf("abs"), difficulty = 1, timed = true),
    )

    private fun muscle(id: String, region: String, tracked: Boolean, fill: String? = null) = Muscle(m(id), id, region, tracked, 1.0, fill?.let(::p))

    val muscles = listOf(
        muscle("chest", "upper", true, "horizontal_push"), muscle("lats", "upper", true, "vertical_pull"),
        muscle("mid_back", "upper", true, "horizontal_pull"), muscle("side_delts", "upper", true, "shoulder_abduction"),
        muscle("front_delts", "upper", false), muscle("biceps", "upper", true, "elbow_flexion"), muscle("triceps", "upper", true, "elbow_extension"),
        muscle("quads", "lower", true, "squat"), muscle("hamstrings", "lower", true, "knee_flexion"), muscle("glutes", "lower", true, "hinge"),
        muscle("abs", "core", true, "anti_extension"),
    )

    val patterns = listOf("squat", "hinge", "lunge", "knee_flexion", "horizontal_push", "vertical_pull", "horizontal_pull",
        "elbow_flexion", "elbow_extension", "shoulder_abduction", "anti_extension").map {
        MovementPattern(p(it), it, it, when (it) {
            "squat" -> setOf(p("lunge")); "lunge" -> setOf(p("squat"))
            "horizontal_pull" -> setOf(p("vertical_pull")); "vertical_pull" -> setOf(p("horizontal_pull"))
            else -> emptySet()
        })
    }

    val allEquipment = listOf("barbell", "rack", "bench", "dumbbells", "leg_press", "leg_curl", "pull_up_bar", "lat_pulldown", "cable", "bands")
    val fullGym = allEquipment.map(::e).toSet()
    val homeDumbbells = setOf(e("dumbbells"), e("bench"), e("bands"))

    private fun slot(p: String, role: SlotRole, sets: Int = 3, target: String? = null, lat: Laterality? = null) =
        Slot(p(p), role, sets, target?.let(::m), preferLaterality = lat)

    val splits = listOf(
        SplitTemplate(SplitId("fb2"), "Full Body A/B", 2, TrainingTier.NOVICE, emptySet(), 10, "duas sessões de corpo inteiro", listOf(
            SessionTemplate("a", "Full A", listOf(slot("squat", SlotRole.MAIN), slot("horizontal_push", SlotRole.MAIN), slot("vertical_pull", SlotRole.MAIN),
                slot("knee_flexion", SlotRole.ACCESSORY), slot("shoulder_abduction", SlotRole.ACCESSORY), slot("elbow_flexion", SlotRole.ACCESSORY, 2))),
            SessionTemplate("b", "Full B", listOf(slot("hinge", SlotRole.MAIN), slot("horizontal_pull", SlotRole.MAIN), slot("horizontal_push", SlotRole.SECONDARY),
                slot("lunge", SlotRole.SECONDARY), slot("elbow_extension", SlotRole.ACCESSORY, 2), slot("anti_extension", SlotRole.ACCESSORY, 2))),
        )),
        SplitTemplate(SplitId("fb3"), "Full Body A/B/C", 3, TrainingTier.NOVICE, emptySet(), 10, "três sessões de corpo inteiro", listOf(
            SessionTemplate("a", "Full A", listOf(slot("squat", SlotRole.MAIN), slot("horizontal_push", SlotRole.MAIN), slot("horizontal_pull", SlotRole.MAIN),
                slot("elbow_flexion", SlotRole.ACCESSORY, 2))),
            SessionTemplate("b", "Full B", listOf(slot("hinge", SlotRole.MAIN), slot("vertical_pull", SlotRole.MAIN), slot("horizontal_push", SlotRole.SECONDARY),
                slot("elbow_extension", SlotRole.ACCESSORY, 2))),
            SessionTemplate("c", "Full C", listOf(slot("lunge", SlotRole.MAIN), slot("horizontal_pull", SlotRole.SECONDARY), slot("shoulder_abduction", SlotRole.ACCESSORY),
                slot("knee_flexion", SlotRole.ACCESSORY))),
        )),
        SplitTemplate(SplitId("pull_only"), "Pull", 1, TrainingTier.NOVICE, emptySet(), 1, "sessão de costas (cenário do 'tenho 35 minutos')", listOf(
            SessionTemplate("pull", "Costas + Bíceps", listOf(slot("vertical_pull", SlotRole.MAIN), slot("horizontal_pull", SlotRole.SECONDARY),
                slot("vertical_pull", SlotRole.SECONDARY), slot("horizontal_pull", SlotRole.ACCESSORY, lat = Laterality.UNILATERAL),
                slot("vertical_pull", SlotRole.ACCESSORY), slot("elbow_flexion", SlotRole.ACCESSORY))),
        )),
    )

    val ruleSet = RuleSet(
        volume = Rule(RuleId("volume.weekly_sets"), VolumeRules(
            TrainingFocus.entries.associateWith { TrainingTier.entries.associateWith { VolumeTarget(4.0, 8.0, 14.0) } },
            1.0, 0.5, 2, mapOf(Mechanic.COMPOUND to 5, Mechanic.ISOLATION to 4),
        )),
        prescription = Rule(RuleId("prescription.reps_rir_rest"), PrescriptionRules(
            TrainingFocus.entries.associateWith {
                mapOf(
                    SlotRole.MAIN to RepPrescription(6..10, 2, 120..180, "controlado"),
                    SlotRole.SECONDARY to RepPrescription(8..12, 2, 90..150, "controlado"),
                    SlotRole.ACCESSORY to RepPrescription(10..15, 1, 60..90, "controlado"),
                )
            }, noviceExtraRir = 1, timedHoldSeconds = 20..45,
        )),
        frequency = Rule(RuleId("frequency.days"), FrequencyRules(mapOf(TrainingTier.NOVICE to 3, TrainingTier.INTERMEDIATE to 3, TrainingTier.ADVANCED to 3), 25)),
        timing = Rule(RuleId("timing.session_clock"), TimingRules(5, 180, 40, LoadType.entries.associateWith { 45 })),
        progression = Rule(RuleId("progression.double"), ProgressionRules(
            LoadType.entries.associateWith { if (it == LoadType.DUMBBELL) 2.0 else 2.5 }, 2, 0.05, 3, 10,
        )),
        recovery = Rule(RuleId("recovery.readiness_score"), RecoveryRules(0.25, 0.25, 0.2, 0.15, 0.15,
            mapOf(SleepQuality.POOR to 0.2, SleepQuality.NORMAL to 0.65, SleepQuality.EXCELLENT to 1.0), 75, 55, 35)),
        fatigue = Rule(RuleId("fatigue.model"), FatigueRules(36.0, 8.0, 3, 0.5, 10.0)),
        scheduling = Rule(RuleId("scheduling.week"), SchedulingRules(1.0, 1.5, 1.0, 5.0, 0.5)),
        hydration = Rule(RuleId("hydration.target"), HydrationRules(35.0, mapOf(SweatLevel.LOW to 400, SweatLevel.MODERATE to 700, SweatLevel.HIGH to 1000), 1.25, 50)),
        nutrition = Rule(RuleId("nutrition.targets"), NutritionRules(
            mapOf(ActivityLevel.SEDENTARY to 1.2, ActivityLevel.LIGHT to 1.375, ActivityLevel.MODERATE to 1.55, ActivityLevel.VERY_ACTIVE to 1.725, ActivityLevel.EXTREMELY_ACTIVE to 1.9),
            mapOf(EnergyGoal.DEFICIT to -0.15, EnergyGoal.MAINTENANCE to 0.0, EnergyGoal.SURPLUS to 0.08, EnergyGoal.RECOMPOSITION to -0.05),
            1.4, 1.6, 2.0, 0.5, 1.0,
        )),
        deload = Rule(RuleId("deload.advisor"), DeloadRules(3, 0.5, 55, 0.4)),
        weightTrend = Rule(RuleId("body.weight_trend"), WeightTrendRules(7, 0.5)),
        bodyMetrics = Rule(RuleId("body.metrics"), BodyMetricRules(
            listOf(BmiBand(18.5, "abaixo do peso"), BmiBand(25.0, "adequado"), BmiBand(30.0, "sobrepeso"), BmiBand(1000.0, "obesidade")), 0.5, 0.6,
        )),
        gamification = Rule(RuleId("gamification.xp"), GamificationRules(mapOf("workout_completed" to 100, "personal_record" to 200), 1000)),
    )

    val safetyQuestions = listOf(
        SafetyQuestion("chest_pain", "Dor no peito?", "sintomas", SafetyOutcome.BLOCK, "Procure avaliação médica."),
        SafetyQuestion("current_pain", "Dor articular?", "dor", SafetyOutcome.CAUTION, "Registre a dor."),
        SafetyQuestion("pregnancy", "Gestante?", "gestação", SafetyOutcome.BLOCK, "Acompanhamento do pré-natal.", Sex.FEMALE),
    )

    val foods = listOf(
        Food(FoodId("arroz"), "Arroz cozido", listOf("arroz"), 128.0, 2.5, 28.1, 0.2, 1.6, null, 125.0, "4 colheres", SourceId("taco"), "teste"),
        Food(FoodId("ovo"), "Ovo cozido", listOf("ovo", "ovos"), 146.0, 13.3, 0.6, 9.5, null, null, 50.0, "1 unidade", SourceId("taco"), "teste"),
    )

    val kb: KnowledgeBase by lazy {
        KnowledgeBase(
            muscles = muscles, patterns = patterns,
            equipment = allEquipment.map { Equipment(e(it), it, "free_weight") },
            environments = listOf(TrainingEnvironment(EnvironmentId("full"), "Completa", fullGym)),
            exercises = exercises, splits = splits,
            sports = listOf(Sport(SportId("kickboxing"), "Kickboxing", 3, 2, 3, 3)),
            safetyQuestions = safetyQuestions, foods = foods, supplements = emptyList(),
            sources = emptyList(), claims = emptyList(), rules = emptyList(), ruleSet = ruleSet,
        )
    }

    fun profile(
        experience: ExperienceLevel = ExperienceLevel.MONTHS_6_TO_12,
        equipment: Set<EquipmentId> = fullGym,
        days: Map<DayOfWeek, Int> = mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.WEDNESDAY to 60, DayOfWeek.FRIDAY to 60),
        goal: Goal = Goal.HYPERTROPHY,
        limitations: List<JointLimitation> = emptyList(),
        sports: List<SportCommitment> = emptyList(),
        age: Int = 30,
        sex: Sex = Sex.MALE,
    ) = UserProfile(
        name = "Teste", age = age, sex = sex, heightCm = 178.0, weightKg = 78.0, primaryGoal = goal, experience = experience,
        equipment = equipment, availability = days.map { DayAvailability(it.key, it.value) }, sports = sports, limitations = limitations,
    )
}
