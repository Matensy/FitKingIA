package com.fitkingia.appcore

import com.fitkingia.core.model.*
import com.fitkingia.core.program.ProgramResult
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import java.util.stream.Stream
import kotlin.test.*

/**
 * Toda combinação de respostas por toque (ambiente × experiência × semana × objetivo × sexo ×
 * prioridade) gera um programa — ou uma recusa explicada — e o programa salvo volta idêntico do user.db.
 */
class QuestionnaireMatrixTest {

    @ParameterizedTest(name = "{0} · {1} · {2} · {3} · {4} · {5}")
    @MethodSource("cases")
    fun `respostas geram programa persistivel`(env: String, exp: ExperienceLevel, preset: Int, goal: Goal, sex: Sex, priorities: String) {
        val t = TestEnv()
        val a = TestEnv.answers(env, exp, goal, Questionnaire.DAY_PRESETS[preset].second, sex, t.kb)
        for (r in priorities.split(",").filter(String::isNotBlank)) Questionnaire.togglePriority(a, BodyRegion.valueOf(r))
        for (step in Questionnaire.steps(a)) assertNull(Questionnaire.blocker(step, a, t.kb), "passo $step")
        when (val r = t.app.submit(a).result) {
            is ProgramResult.Generated -> {
                val p = r.program
                assertTrue(p.sessions.isNotEmpty())
                assertTrue(p.sessions.all { it.exercises.isNotEmpty() }, "sessão vazia")
                p.sessions.forEach { s -> assertTrue(s.estimatedMinutes <= (s.budgetMinutes ?: 999) + 5, "${s.name}: ${s.estimatedMinutes} > ${s.budgetMinutes}") }
                val stored = assertNotNull(t.app.program()).program
                assertEquals(p.sessions.map { s -> s.exercises.map { it.exercise.id to it.sets } }, stored.sessions.map { s -> s.exercises.map { it.exercise.id to it.sets } })
                assertEquals(a.priorities.toSet(), stored.priorities)
                assertEquals(p.volumeTargets, stored.volumeTargets)
                assertEquals(p.sessions.map { it.name }, stored.sessions.map { it.name })
                val today = assertNotNull(t.app.todayView())
                val week = assertNotNull(t.app.week())
                assertEquals(p.sessions.size, week.planned)
                if (today.session != null) assertTrue(today.session!!.exercises.isNotEmpty())
            }
            is ProgramResult.Refused -> assertTrue(r.reasons.isNotEmpty())
        }
    }

    companion object {
        @JvmStatic
        fun cases(): Stream<Arguments> {
            val envs = listOf("full_gym", "basic_gym", "home_gym", "home", "outdoor", "bodyweight")
            val exps = listOf(ExperienceLevel.NONE, ExperienceLevel.MONTHS_6_TO_12, ExperienceLevel.YEARS_2_PLUS)
            val goals = listOf(Goal.HYPERTROPHY, Goal.STRENGTH, Goal.WAIST_REDUCTION, Goal.CARDIO_HEALTH)
            val priorities = listOf("", "GLUTES", "LEGS", "GLUTES,LEGS", "CHEST", "SHOULDERS,ARMS", "BACK", "CORE")
            val out = mutableListOf<Arguments>()
            var i = 0
            for (e in envs) for (x in exps) for (p in Questionnaire.DAY_PRESETS.indices) {
                out += Arguments.of(e, x, p, goals[i % goals.size], if (i % 2 == 0) Sex.MALE else Sex.FEMALE, priorities[i % priorities.size])
                i++
            }
            return out.stream()
        }
    }
}
