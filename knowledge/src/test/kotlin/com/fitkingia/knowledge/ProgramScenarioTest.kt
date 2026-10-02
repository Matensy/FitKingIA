package com.fitkingia.knowledge

import com.fitkingia.core.model.*
import com.fitkingia.core.program.Program
import com.fitkingia.core.program.ProgramGenerator
import com.fitkingia.core.program.ProgramResult
import com.fitkingia.core.safety.SafetyScreening
import com.fitkingia.core.session.SessionAdapter
import com.fitkingia.core.substitution.SubstitutionEngine
import com.fitkingia.core.program.UserConstraints
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.time.DayOfWeek
import java.time.DayOfWeek.*
import kotlin.test.*

/**
 * Cenários com o conhecimento real. Propriedades que valem para QUALQUER perfil:
 * equipamento disponível, nível, volume ≤ teto, sessões no tempo, frequência, substituições válidas.
 */
class ProgramScenarioTest {
    companion object {
        val kb = BundledKnowledge.load()
        private val dayPlans = mapOf(
            2 to listOf(MONDAY, THURSDAY), 3 to listOf(MONDAY, WEDNESDAY, FRIDAY),
            4 to listOf(MONDAY, TUESDAY, THURSDAY, FRIDAY), 5 to listOf(MONDAY, TUESDAY, WEDNESDAY, FRIDAY, SATURDAY),
            6 to listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY),
        )

        @JvmStatic fun scenarios(): List<Arguments> = buildList {
            for (env in kb.environments) for (exp in listOf(ExperienceLevel.NONE, ExperienceLevel.YEARS_1_TO_2, ExperienceLevel.YEARS_2_PLUS))
                for (days in listOf(2, 3, 4, 6)) for (goal in listOf(Goal.HYPERTROPHY, Goal.STRENGTH, Goal.REDUCE_SEDENTARY))
                    add(Arguments.of(env.id.value, exp, days, goal, 60))
        }

        fun profile(env: String, exp: ExperienceLevel, days: Int, goal: Goal, minutes: Int, sports: List<SportCommitment> = emptyList()): UserProfile {
            val e = kb.environment(EnvironmentId(env))
            return UserProfile(
                name = "Cenário", age = 30, sex = Sex.FEMALE, heightCm = 165.0, weightKg = 62.0, primaryGoal = goal, experience = exp,
                environment = e.id, equipment = e.equipment, availability = dayPlans.getValue(days).map { DayAvailability(it, minutes) },
                sports = sports,
            )
        }

        fun generate(p: UserProfile): Program {
            val answers = SafetyScreening(kb).questionsFor(p.sex).associate { it.id to false }
            val r = ProgramGenerator(kb).generate(p, SafetyScreening(kb).evaluate(p, answers))
            return assertIs<ProgramResult.Generated>(r, r.toString()).program
        }
    }

    @ParameterizedTest(name = "{0} · {1} · {2} dias · {3}")
    @MethodSource("scenarios")
    fun `propriedades do programa`(env: String, exp: ExperienceLevel, days: Int, goal: Goal, minutes: Int) {
        val p = profile(env, exp, days, goal, minutes)
        val program = generate(p)
        val tierMax = kb.ruleSet.frequency.params.maxDaysByTier.getValue(p.tier)

        // frequência correta
        assertEquals(minOf(days, tierMax), program.sessions.size)
        assertEquals(program.sessions.size, program.trainingDays.toSet().size)
        assertTrue(program.trainingDays.all { d -> p.availability.any { it.day == d } })

        val all = program.sessions.flatMap { it.exercises }
        // nenhum equipamento indisponível; nenhum exercício acima do nível
        assertTrue(all.all { p.equipment.containsAll(it.exercise.equipment) })
        assertTrue(all.all { it.exercise.minTier <= p.tier })
        // volume semanal nunca excessivo sem aviso; quando inevitável (músculos co-treinados), o excesso é pequeno e explicado
        for ((m, t) in program.volumeTargets) {
            val v = program.weeklyVolume[m] ?: 0.0
            if (v > t.max + 1e-9) {
                assertTrue(v <= t.max * 1.25, "$m: $v muito acima de ${t.max}")
                assertTrue(program.warnings.any { "acima do teto" in it.text && kb.muscleName(m).lowercase() in it.text }, "$m acima do teto sem aviso")
            }
        }
        // sessões no tempo do dia, sem exercício repetido na sessão, séries dentro dos limites
        for (s in program.sessions) {
            assertTrue(s.estimatedMinutes <= s.budgetMinutes!!, "${s.name} ${s.estimatedMinutes} > ${s.budgetMinutes}")
            assertEquals(s.exercises.size, s.exercises.map { it.exercise.id }.toSet().size)
            assertTrue(s.exercises.all { it.sets in 1..5 })
        }
        // músculo abaixo do mínimo sempre vem com aviso explicando o motivo
        for ((m, t) in program.volumeTargets) if ((program.weeklyVolume[m] ?: 0.0) + 1e-9 < t.min)
            assertTrue(program.warnings.any { kb.muscleName(m).lowercase() in it.text }, "sem aviso para $m")
        // substituições válidas para todo exercício do programa
        val subs = SubstitutionEngine(kb)
        for (ex in all.map { it.exercise }.distinct()) {
            val r = subs.find(ex.id, UserConstraints.of(p))
            assertTrue(r.options.all { it.exercise.id != ex.id && p.equipment.containsAll(it.exercise.equipment) && it.exercise.minTier <= p.tier })
        }
    }

    @Test fun `Given 3 dias, home gym, iniciante, hipertrofia - When generate - Then regras da visão`() {
        val p = profile("home_gym", ExperienceLevel.NONE, 3, Goal.HYPERTROPHY, 60)
        val program = generate(p)
        assertEquals("fb3", program.split.id.value)
        assertEquals(listOf(MONDAY, WEDNESDAY, FRIDAY), program.trainingDays)
        val all = program.sessions.flatMap { it.exercises }
        assertTrue(all.all { p.equipment.containsAll(it.exercise.equipment) }, "no unavailable equipment")
        for ((m, t) in program.volumeTargets) assertTrue((program.weeklyVolume[m] ?: 0.0) <= t.max + 1e-9, "no excessive weekly volume")
        assertTrue(all.filter { it.role == SlotRole.MAIN }.all { it.prescription.rir >= 3 }, "iniciante longe da falha")
        // frequência: cada grande grupo muscular aparece em ≥ 2 sessões
        for (m in listOf("quads", "chest", "lats").map(::MuscleId))
            assertTrue(program.sessions.count { s -> s.exercises.any { m in it.exercise.primaryMuscles || m in it.exercise.secondaryMuscles } } >= 2, "$m com frequência < 2")
    }

    @Test fun `Kickboxing - perna pesada não fica colada ao esporte quando há alternativa`() {
        val p = profile("full_gym", ExperienceLevel.YEARS_1_TO_2, 4, Goal.HYPERTROPHY, 75,
            sports = listOf(SportCommitment(SportId("kickboxing"), WEDNESDAY, 3)))
            .copy(availability = listOf(MONDAY, TUESDAY, THURSDAY, SATURDAY).map { DayAvailability(it, 75) })
        val program = generate(p)
        val lowerDays = program.sessions.filter { s -> s.exercises.sumOf { e -> e.sets * if (kb.muscle(e.exercise.primaryMuscles.first()).region == "lower") 1 else 0 } >= 6 }
            .map { it.day!! }
        assertTrue(lowerDays.none { it == TUESDAY || it == THURSDAY }, "pernas em $lowerDays, colado à quarta")
        assertTrue(program.explanations.any { "Kickboxing" in it.text })
    }

    @Test fun `tenho 35 minutos no dia de costas`() {
        val p = profile("full_gym", ExperienceLevel.YEARS_1_TO_2, 3, Goal.HYPERTROPHY, 90).copy(preferredSplit = SplitId("ppl3"))
        val program = generate(p)
        val pull = program.sessions.first { it.key == "pull" }
        val quick = SessionAdapter(kb).forTime(pull, 35)
        assertTrue(quick.session.estimatedMinutes <= 35)
        assertTrue(quick.session.exercises.any { it.exercise.pattern == PatternId("vertical_pull") && it.role == SlotRole.MAIN })
        // Bíceps continua com trabalho direto: a rosca fica, ou sai só se uma barra supinada (bíceps principal) cobrir.
        assertTrue(quick.session.exercises.any { MuscleId("biceps") in it.exercise.primaryMuscles }, "bíceps sem trabalho direto")
        assertTrue(quick.changes.isNotEmpty() && quick.changes.all { it.reason.isNotBlank() })
    }

    @Test fun `objetivo perder barriga recebe o fato sobre perda localizada com a evidência conflitante`() {
        val program = generate(profile("full_gym", ExperienceLevel.NONE, 3, Goal.WAIST_REDUCTION, 60))
        val fact = program.explanations.single { it.claimIds.contains(ClaimId("spot_reduction")) }
        assertTrue("conflitante" in fact.text)
    }

    @Test fun `apenas peso corporal ainda gera programa e explica lacunas`() {
        val program = generate(profile("bodyweight", ExperienceLevel.NONE, 3, Goal.HYPERTROPHY, 45))
        assertTrue(program.sessions.all { s -> s.exercises.all { it.exercise.equipment.isEmpty() } })
        assertTrue(program.warnings.isNotEmpty(), "falta de remada sem equipamento deve ser avisada")
    }
}
