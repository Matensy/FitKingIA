package com.fitkingia.core.planning

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.TrainingEnvironment
import com.fitkingia.core.model.DayAvailability
import com.fitkingia.core.model.MuscleId
import com.fitkingia.core.model.UserProfile
import com.fitkingia.core.program.ProgramGenerator
import com.fitkingia.core.program.ProgramResult
import com.fitkingia.core.safety.ScreeningResult
import java.time.DayOfWeek
import java.time.DayOfWeek.*

data class Scenario(val name: String, val transform: (UserProfile) -> UserProfile)

data class SimulationRow(
    val scenario: String,
    val splitName: String?,
    val trainingDays: Int,
    val weeklyMinutes: Int,
    val setsByMuscle: Map<MuscleId, Double>,
    val belowMinimum: List<MuscleId>,
    val refusal: String? = null,
)

data class Simulation(val rows: List<SimulationRow>, val note: Explanation)

/**
 * PROGRAM SIMULATOR / "WHAT IF?": roda o mesmo motor para cenários diferentes e compara
 * volume por músculo e tempo semanal — sem declarar um cenário universalmente "melhor".
 */
class ProgramSimulator(private val kb: KnowledgeBase) {
    private val generator = ProgramGenerator(kb)

    fun compare(profile: UserProfile, screening: ScreeningResult, scenarios: List<Scenario>): Simulation {
        val rows = scenarios.map { sc ->
            when (val r = generator.generate(sc.transform(profile), screening)) {
                is ProgramResult.Generated -> {
                    val p = r.program
                    SimulationRow(
                        sc.name, p.split.name, p.sessions.size, p.weeklyMinutes, p.weeklyVolume,
                        p.volumeTargets.filter { (m, t) -> (p.weeklyVolume[m] ?: 0.0) + 1e-9 < t.min }.keys.toList(),
                    )
                }
                is ProgramResult.Refused -> SimulationRow(sc.name, null, 0, 0, emptyMap(), emptyList(), r.reasons.joinToString { it.text })
            }
        }
        return Simulation(rows, Explanation.rule(
            "Escolha o programa que melhor se encaixa na sua rotina. Mais dias permitem mais volume por sessão mais curta, " +
                "mas consistência ao longo do tempo pesa mais que a configuração \"ideal\".",
        ))
    }

    companion object {
        private val SPREAD = mapOf(
            1 to listOf(MONDAY), 2 to listOf(MONDAY, THURSDAY), 3 to listOf(MONDAY, WEDNESDAY, FRIDAY),
            4 to listOf(MONDAY, TUESDAY, THURSDAY, FRIDAY), 5 to listOf(MONDAY, TUESDAY, WEDNESDAY, FRIDAY, SATURDAY),
            6 to listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY),
        )

        /** "E se eu passar de 3 para 5 dias?" — usa o tempo médio por dia do usuário, se não informado. */
        fun days(n: Int, minutesPerDay: Int? = null) = Scenario("${n}D") { p ->
            val minutes = minutesPerDay ?: p.availability.map { it.minutes }.average().toInt()
            p.copy(availability = SPREAD.getValue(n).map { DayAvailability(it, minutes) }, maxTrainingDays = n)
        }

        /** "E se eu só tiver 45 minutos?" */
        fun minutes(m: Int) = Scenario("${m} min") { p -> p.copy(availability = p.availability.map { it.copy(minutes = m) }) }

        /** "E se eu trocar academia por casa?" */
        fun environment(env: TrainingEnvironment) = Scenario(env.name) { p -> p.copy(environment = env.id, equipment = env.equipment) }

        fun onDays(name: String, days: List<DayOfWeek>, minutes: Int) =
            Scenario(name) { p -> p.copy(availability = days.map { DayAvailability(it, minutes) }) }
    }
}
