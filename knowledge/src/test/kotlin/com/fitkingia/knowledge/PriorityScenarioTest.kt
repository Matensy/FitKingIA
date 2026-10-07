package com.fitkingia.knowledge

import com.fitkingia.core.model.*
import com.fitkingia.core.program.GoalAlignment
import com.fitkingia.core.program.Program
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import kotlin.test.*

/**
 * "O treino faz sentido com o que a pessoa quer?" — propriedades da prioridade por região com o
 * conhecimento real: frequência, volume e ordem a favor da região escolhida, ou um aviso dizendo
 * por que não deu (nunca uma falha silenciosa).
 */
class PriorityScenarioTest {
    companion object {
        private val kb = ProgramScenarioTest.kb
        private val rule = kb.ruleSet.priority.params
        private val gyms = setOf("full_gym", "basic_gym", "home_gym")

        @JvmStatic fun scenarios(): List<Arguments> = buildList {
            val prioritySets = listOf(
                setOf(BodyRegion.GLUTES), setOf(BodyRegion.LEGS), setOf(BodyRegion.GLUTES, BodyRegion.LEGS),
                setOf(BodyRegion.CHEST), setOf(BodyRegion.BACK), setOf(BodyRegion.ARMS), setOf(BodyRegion.SHOULDERS, BodyRegion.ARMS),
            )
            for (env in kb.environments) for (exp in listOf(ExperienceLevel.NONE, ExperienceLevel.YEARS_1_TO_2, ExperienceLevel.YEARS_2_PLUS))
                for (days in listOf(2, 3, 4, 5)) for ((i, pr) in prioritySets.withIndex())
                    if ((env.id.value.length + days + i + exp.ordinal) % 2 == 0 || env.id.value == "full_gym")
                        add(Arguments.of(env.id.value, exp, days, pr.joinToString(",") { it.name }))
        }

        fun regionMuscles(r: BodyRegion) = kb.trackedMuscles.filter { it.focusRegion == r }.map { it.id }.toSet()
        fun directSessions(p: Program, r: BodyRegion) = p.sessions.count { s -> s.exercises.any { e -> e.exercise.primaryMuscles.any { it in regionMuscles(r) } } }
        fun weekly(p: Program, r: BodyRegion) = regionMuscles(r).map { p.weeklyVolume[it] ?: 0.0 }.average()
        fun warned(p: Program, r: BodyRegion) =
            p.warnings.any { r.label in it.text } || p.goalCheck.any { it.text.startsWith("⚠️") && r.label in it.text }
        fun focused(p: Program, r: BodyRegion) = p.sessions.flatMap { it.exercises }.filter { e -> e.exercise.focus.any { it in regionMuscles(r) } }.sumOf { it.sets }
    }

    @ParameterizedTest(name = "{0} · {1} · {2} dias · {3}")
    @MethodSource("scenarios")
    fun `prioridade aparece no treino ou e explicada`(env: String, exp: ExperienceLevel, days: Int, regions: String) {
        val priorities = regions.split(",").map(BodyRegion::valueOf).toSet()
        val base = ProgramScenarioTest.profile(env, exp, days, Goal.HYPERTROPHY, 60)
        val p = base.copy(priorities = priorities)
        val program = ProgramScenarioTest.generate(p)
        val balanced = ProgramScenarioTest.generate(base)
        val n = program.sessions.size

        // invariantes gerais continuam valendo
        val all = program.sessions.flatMap { it.exercises }
        assertTrue(all.all { p.equipment.containsAll(it.exercise.equipment) && it.exercise.minTier <= p.tier })
        for (s in program.sessions) assertTrue(s.estimatedMinutes <= s.budgetMinutes!!, "${s.name}: ${s.estimatedMinutes} > ${s.budgetMinutes}")

        for (r in priorities) {
            val possible = kb.exercises.any { e -> e.primaryMuscles.any { it in regionMuscles(r) } && p.equipment.containsAll(e.equipment) && e.minTier <= p.tier }
            if (!possible) { assertTrue(warned(program, r), "$r impossível sem aviso"); continue }
            // frequência: atinge o mínimo da regra ou avisa por quê
            val need = rule.minFrequency(r, n)
            assertTrue(directSessions(program, r) >= need || warned(program, r), "$r em ${directSessions(program, r)}/$n treinos sem aviso")
            // séries focadas: nunca menos que no programa equilibrado (tolerância de 2 séries), salvo aviso
            assertTrue(focused(program, r) + 2 >= focused(balanced, r) || warned(program, r), "$r: focadas ${focused(program, r)} < equilibrado ${focused(balanced, r)}")
            // volume: a prioridade nunca recebe menos que no programa equilibrado (tolerância de 1 série)
            assertTrue(weekly(program, r) + 1.0 >= weekly(balanced, r) || warned(program, r), "$r: ${weekly(program, r)} < equilibrado ${weekly(balanced, r)}")
        }
        // ordem: composto da prioridade abre a sessão; acessório da prioridade abre o bloco de acessórios;
        // e nenhuma sessão começa por acessório se houver composto.
        val pm = priorities.flatMap { regionMuscles(it) }.toSet()
        for (s in program.sessions) {
            val compound = s.exercises.indexOfFirst { e -> e.role != SlotRole.ACCESSORY && e.exercise.focus.any { it in pm } }
            if (compound >= 0) assertTrue(compound < rule.leadingPositions, "${s.name}: composto da prioridade só na posição ${compound + 1}")
            if (s.exercises.any { it.role != SlotRole.ACCESSORY }) assertTrue(s.exercises.first().role != SlotRole.ACCESSORY, "${s.name} começa por acessório")
        }
        // a checagem objetivo × treino sempre aparece (✅ ou ⚠️) para cada prioridade
        for (r in priorities) assertTrue(program.goalCheck.any { r.label in it.text && it.ruleId == kb.ruleSet.priority.id }, "sem checagem para $r")
    }

    @Test fun `quem quer treinar gluteo treina inferiores 3x por semana`() {
        for (env in gyms) for (exp in listOf(ExperienceLevel.UNDER_3_MONTHS, ExperienceLevel.MONTHS_6_TO_12, ExperienceLevel.YEARS_2_PLUS)) for (days in 3..5) {
            val p = ProgramScenarioTest.profile(env, exp, days, Goal.HYPERTROPHY, 60).copy(priorities = setOf(BodyRegion.GLUTES))
            val program = ProgramScenarioTest.generate(p)
            val glutes = directSessions(program, BodyRegion.GLUTES)
            assertTrue(glutes >= 3, "$env/$exp/$days dias: glúteos só $glutes× (${program.split.name})")
            val report = GoalAlignment(kb).check(program, p)
            val c = report.coverage.single { it.region == BodyRegion.GLUTES }
            assertTrue(c.frequency >= 3)
            assertTrue(c.ok, "$env/$exp/$days: checagem de glúteos não passou: ${program.goalCheck.joinToString { it.text }}")
            assertTrue(program.split.emphasis.contains(BodyRegion.GLUTES), "$env/$exp/$days: modelo ${program.split.id} sem ênfase em glúteos")
        }
    }

    @Test fun `sem prioridade o motor escolhe modelo equilibrado`() {
        for (env in kb.environments) for (days in 1..6) {
            val p = ProgramScenarioTest.profile(env.id.value, ExperienceLevel.YEARS_2_PLUS, minOf(days, 6).coerceAtLeast(2), Goal.HYPERTROPHY, 60)
            val program = ProgramScenarioTest.generate(p)
            assertTrue(program.split.emphasis.isEmpty(), "${program.split.id} tem ênfase sem o usuário pedir")
        }
    }

    @Test fun `nomes de modelos e sessoes em portugues e inferiores diferenciados`() {
        val english = Regex("\\b(Full Body|Upper|Lower|Push|Pull|Legs)\\b")
        for (s in kb.splits) {
            assertFalse(english.containsMatchIn(s.name), "modelo ${s.id}: ${s.name}")
            for (se in s.sessions) assertFalse(english.containsMatchIn(se.name), "sessão ${s.id}/${se.key}: ${se.name}")
            // Duas sessões de inferiores no mesmo modelo precisam ter nomes e exercícios principais diferentes.
            val lower = s.sessions.filter { se -> se.slots.firstOrNull()?.pattern?.value in setOf("squat", "hinge", "hip_extension", "lunge") }
            assertEquals(lower.size, lower.map { it.name }.toSet().size, "${s.id}: sessões de inferiores com o mesmo nome")
        }
    }
}
