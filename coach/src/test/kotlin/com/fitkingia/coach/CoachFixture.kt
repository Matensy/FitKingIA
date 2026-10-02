package com.fitkingia.coach

import com.fitkingia.core.model.*
import com.fitkingia.core.program.ProgramGenerator
import com.fitkingia.core.program.ProgramResult
import com.fitkingia.core.progression.ExerciseLog
import com.fitkingia.core.progression.SetLog
import com.fitkingia.core.safety.SafetyScreening
import com.fitkingia.knowledge.BundledKnowledge
import java.time.DayOfWeek.*
import java.time.LocalDate

/** Perfil e contexto reais (banco de conhecimento real) para testar a IA local de ponta a ponta. */
object CoachFixture {
    val kb = BundledKnowledge.load()
    /** Quarta-feira. */
    val TODAY: LocalDate = LocalDate.of(2026, 9, 30)

    val profile = UserProfile(
        name = "Matheus Teste", age = 27, sex = Sex.MALE, heightCm = 178.0, weightKg = 78.0,
        primaryGoal = Goal.HYPERTROPHY, secondaryGoal = Goal.WAIST_REDUCTION, experience = ExperienceLevel.YEARS_1_TO_2,
        environment = EnvironmentId("full_gym"), equipment = kb.environment(EnvironmentId("full_gym")).equipment,
        availability = listOf(MONDAY to 60, WEDNESDAY to 90, FRIDAY to 60, SATURDAY to 120).map { DayAvailability(it.first, it.second) },
        sports = listOf(SportCommitment(SportId("kickboxing"), TUESDAY, 3)),
    )

    val screening = SafetyScreening(kb).evaluate(profile, SafetyScreening(kb).questionsFor(profile.sex).associate { it.id to false })
    val program = (ProgramGenerator(kb).generate(profile, screening) as ProgramResult.Generated).program

    private val bench = ExerciseId("barbell_bench_press")
    val history = listOf(
        ExerciseLog(bench, TODAY.minusWeeks(3), List(3) { SetLog(60.0, 10, 2) }),
        ExerciseLog(bench, TODAY.minusWeeks(2), List(3) { SetLog(60.0, 12, 2) }),
        ExerciseLog(bench, TODAY.minusWeeks(1), List(3) { SetLog(62.5, 12, 2) }),
    )

    fun context() = CoachContext(profile, screening, program, TODAY, history)
}
