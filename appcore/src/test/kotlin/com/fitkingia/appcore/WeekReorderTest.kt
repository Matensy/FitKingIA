package com.fitkingia.appcore

import com.fitkingia.core.model.*
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.program.VolumeCalculator
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.*
import java.time.LocalDate
import kotlin.test.*

class WeekReorderTest {
    private val monday: LocalDate = TestEnv.MONDAY_9H.toLocalDate()
    private fun day(d: DayOfWeek): LocalDate = monday.plusDays(d.value - 1L)

    /** Superiores/Inferiores em seg/ter/qui/sex, 60 min. */
    private fun upperLower(sports: List<SportCommitment> = emptyList()): TestEnv {
        val env = TestEnv()
        val a = TestEnv.answers(kb = env.kb, days = mapOf(MONDAY to 60, TUESDAY to 60, THURSDAY to 60, FRIDAY to 60))
        a.preferredSplit = SplitId("ul4")
        a.sports.addAll(sports)
        env.app.submit(a)
        return env
    }

    private fun keys(w: WeekView): Map<DayOfWeek, String?> = w.days.associate { it.day to it.session?.key }
    private fun baseKeys(env: TestEnv): Map<DayOfWeek, String?> =
        DayOfWeek.values().associateWith { d -> env.app.program()!!.program.sessionOn(d)?.key }

    private fun isLower(env: TestEnv, s: PlannedSession): Boolean {
        val v = VolumeCalculator(env.kb.ruleSet.volume.params).ofExercises(s.exercises)
        fun region(r: String) = v.entries.sumOf { (m, x) -> if (env.kb.muscle(m).region == r) x else 0.0 }
        return region("lower") > region("upper")
    }

    @Test fun `troca de dois dias futuros so nesta semana preserva o programa`() {
        val env = upperLower()
        val base = baseKeys(env)
        val r = env.app.swapDays(day(THURSDAY), day(FRIDAY), permanent = false)

        val week = keys(r.week)
        assertEquals(base[FRIDAY], week[THURSDAY])
        assertEquals(base[THURSDAY], week[FRIDAY])
        assertEquals(base[MONDAY], week[MONDAY])
        assertNotNull(r.week.plan, "a semana fica replanejada")
        assertEquals(2, r.moves.count { "→" in it })
        assertEquals(base, baseKeys(env), "programa base inalterado")
        // Mesmo tempo nos dois dias: nenhum exercício cortado.
        val friday = r.week.days.first { it.day == FRIDAY }.session!!
        assertEquals(env.app.program()!!.program.sessionOn(THURSDAY)!!.exercises.map { it.exercise.id }, friday.exercises.map { it.exercise.id })

        // Próxima semana volta ao normal.
        env.at(TestEnv.MONDAY_9H.plusDays(7))
        val next = assertNotNull(env.app.week())
        assertNull(next.plan)
        assertEquals(base, keys(next))
    }

    @Test fun `troca permanente ja vem trocada na proxima semana`() {
        val env = upperLower()
        val base = baseKeys(env)
        val ids = env.app.program()!!.sessionIds
        val r = env.app.swapDays(day(TUESDAY), day(THURSDAY), permanent = true)

        assertEquals(base[THURSDAY], keys(r.week)[TUESDAY])
        assertEquals(base[TUESDAY], keys(r.week)[THURSDAY])
        assertTrue(r.moves.any { it.contains("próximas semanas") })
        val program = env.app.program()!!
        assertEquals(base[THURSDAY], program.program.sessionOn(TUESDAY)?.key)
        assertEquals(base[TUESDAY], program.program.sessionOn(THURSDAY)?.key)
        assertEquals(ids, program.sessionIds, "ids das sessões mantidos (treinos feitos continuam vinculados)")
        assertEquals(program.program.sessions.map { it.day }.sortedBy { it }, program.program.sessions.map { it.day }, "ordem segue os dias")

        env.at(TestEnv.MONDAY_9H.plusDays(7))
        val next = assertNotNull(env.app.week())
        assertEquals(base[THURSDAY], keys(next)[TUESDAY])
        assertEquals(base[TUESDAY], keys(next)[THURSDAY])
        assertEquals(base[MONDAY], keys(next)[MONDAY])
    }

    @Test fun `troca permanente depois de troca so desta semana fica como a semana mostra`() {
        val env = upperLower()
        val base = baseKeys(env)
        env.app.swapDays(day(THURSDAY), day(FRIDAY), permanent = false)
        // Trocar de novo, agora "todas as semanas": quinta e sexta voltam à ordem original, e o programa também.
        val r = env.app.swapDays(day(THURSDAY), day(FRIDAY), permanent = true)
        assertEquals(base, keys(r.week))
        assertNull(r.week.plan, "a semana voltou a ser igual ao programa")
        assertEquals(base, baseKeys(env), "o programa fica com o que a semana mostra")
        env.at(TestEnv.MONDAY_9H.plusDays(7))
        assertEquals(base, keys(env.app.week()!!))
    }

    @Test fun `troca permanente para um dia mais curto avisa que o corte fica no programa`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb, days = mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60, SATURDAY to 30)))
        val long = env.app.program()!!.program.sessionOn(WEDNESDAY)!!
        val r = env.app.swapDays(day(WEDNESDAY), day(SATURDAY), permanent = true)
        val sat = env.app.program()!!.program.sessionOn(SATURDAY)!!
        assertEquals(long.key, sat.key)
        assertEquals(30, sat.budgetMinutes)
        assertTrue(sat.exercises.size < long.exercises.size)
        assertTrue(r.warnings.any { it.text.contains("versão reduzida") }, r.warnings.joinToString { it.text })
    }

    @Test fun `treino perdido de segunda feito hoje na quarta sai das pendencias`() {
        val env = TestEnv()
        // Domingo tem tempo, mas o motor usa só 3 dias: fica livre para receber o treino de hoje.
        val a = TestEnv.answers(kb = env.kb, days = mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60, SUNDAY to 45))
        a.maxDays = 3
        env.app.submit(a)
        val base = baseKeys(env)
        assertNull(base[SUNDAY])
        env.at(TestEnv.MONDAY_9H.plusDays(2)) // quarta
        assertEquals(listOf(day(MONDAY)), env.app.todayView()!!.pendingMissed.map { it.date })
        val preview = assertNotNull(env.app.doTodayDisplaces(day(MONDAY)))
        assertEquals(base[WEDNESDAY], preview.session.key)
        assertEquals(day(SUNDAY), preview.to)

        val r = env.app.doToday(day(MONDAY))
        val days = r.week.days.associateBy { it.day }
        assertEquals(base[MONDAY], days.getValue(WEDNESDAY).session?.key, "o treino de segunda passa para hoje")
        assertEquals(base[WEDNESDAY], days.getValue(SUNDAY).session?.key, "o de hoje vai para o próximo dia livre com tempo")
        assertEquals(45, days.getValue(SUNDAY).session!!.budgetMinutes)
        assertTrue(r.dropped.isEmpty())
        assertEquals(DayStatus.MISSED_RESOLVED, days.getValue(MONDAY).status)
        assertEquals('A', days.getValue(MONDAY).missedOption)

        val today = assertNotNull(env.app.todayView())
        assertTrue(today.pendingMissed.isEmpty(), "não aparece mais como pendente")
        assertEquals(base[MONDAY], today.session?.key)

        // Feito hoje: hoje fica "feito" e a segunda continua "replanejada" (não foi treinada na segunda).
        val w = env.app.startWorkout(today.session!!, today.sessionId, null)
        env.app.logSet(w.id, today.session!!.exercises.first().exercise.id, 1, 40.0, 10, 2)
        env.app.finishWorkout(w.id, Perceived.ADEQUATE)
        val after = env.app.week()!!.days.associateBy { it.day }
        assertEquals(DayStatus.DONE, after.getValue(WEDNESDAY).status)
        assertEquals(DayStatus.MISSED_RESOLVED, after.getValue(MONDAY).status)
        assertEquals(1, env.app.week()!!.done)
        assertEquals(3, env.app.week()!!.planned, "a sessão remarcada conta uma vez")
    }

    @Test fun `sem dia livre o treino de hoje fica fora desta semana`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        val base = baseKeys(env)
        env.at(TestEnv.MONDAY_9H.plusDays(2)) // quarta
        val preview = assertNotNull(env.app.doTodayDisplaces(day(MONDAY)))
        assertEquals(base[WEDNESDAY], preview.session.key)
        assertNull(preview.to, "prévia avisa que o treino de hoje sai da semana")
        val r = env.app.doToday(day(MONDAY))
        assertEquals(listOf(base[WEDNESDAY]), r.dropped.map { it.key })
        assertTrue(r.warnings.any { it.text.contains("fora desta semana") }, r.warnings.joinToString { it.text })
        assertEquals(base[MONDAY], r.week.days.first { it.day == WEDNESDAY }.session?.key)
        assertEquals(base[FRIDAY], r.week.days.first { it.day == FRIDAY }.session?.key)
    }

    @Test fun `trazer treino futuro para hoje troca os dois dias`() {
        val env = upperLower()
        val base = baseKeys(env)
        assertEquals(day(THURSDAY), env.app.doTodayDisplaces(day(THURSDAY))?.to)
        val r = env.app.doToday(day(THURSDAY))
        assertEquals(base[THURSDAY], keys(r.week)[MONDAY])
        assertEquals(base[MONDAY], keys(r.week)[THURSDAY])
        assertEquals(base[THURSDAY], env.app.todayView()!!.session?.key)
    }

    @Test fun `cada sessao e reajustada ao tempo do novo dia`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb, days = mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60, SATURDAY to 30)))
        val program = env.app.program()!!.program
        val long = program.sessionOn(WEDNESDAY)!!
        val short = program.sessionOn(SATURDAY)!!
        assertEquals(30, short.budgetMinutes)

        val r = env.app.swapDays(day(WEDNESDAY), day(SATURDAY), permanent = false)
        val sat = r.week.days.first { it.day == SATURDAY }.session!!
        assertEquals(long.key, sat.key)
        assertEquals(30, sat.budgetMinutes)
        assertTrue(sat.estimatedMinutes <= 30 || r.warnings.any { it.text.contains("mesmo reduzida", ignoreCase = true) })
        assertTrue(sat.exercises.size < long.exercises.size, "sessão longa encurtada para 30 min")
        assertTrue(r.warnings.any { it.text.startsWith(long.name) && it.text.contains("30 min") }, r.warnings.joinToString { it.text })
        val wed = r.week.days.first { it.day == WEDNESDAY }.session!!
        assertEquals(short.key, wed.key)
        assertEquals(60, wed.budgetMinutes)

        // Desfazer (trocar de volta) recupera a sessão completa e apaga o replanejamento.
        val back = env.app.swapDays(day(WEDNESDAY), day(SATURDAY), permanent = false)
        val wedBack = back.week.days.first { it.day == WEDNESDAY }.session!!
        assertEquals(long.exercises.map { it.exercise.id to it.sets }, wedBack.exercises.map { it.exercise.id to it.sets })
        assertNull(back.week.plan)

        // Dia sem tempo cadastrado (quinta): mantém o orçamento original.
        val thu = env.app.swapDays(day(FRIDAY), day(THURSDAY), permanent = false)
        val moved = thu.week.days.first { it.day == THURSDAY }.session!!
        assertEquals(60, moved.budgetMinutes)
        assertEquals(program.sessionOn(FRIDAY)!!.exercises.map { it.exercise.id }, moved.exercises.map { it.exercise.id })
        assertTrue(thu.moves.any { it.contains("tempo original mantido") })
        assertNull(thu.week.days.first { it.day == FRIDAY }.session)
    }

    @Test fun `avisa quando pernas ficam em dias seguidos`() {
        val env = upperLower()
        val p = env.app.program()!!.program
        assertTrue(isLower(env, p.sessionOn(TUESDAY)!!) && isLower(env, p.sessionOn(FRIDAY)!!), "esperava inferiores na terça e na sexta")
        // Inferiores A vai para quinta, colado nos Inferiores B de sexta.
        val r = env.app.swapDays(day(TUESDAY), day(THURSDAY), permanent = false)
        val legs = r.warnings.filter { it.text.contains("pernas em dias seguidos") }
        assertEquals(1, legs.size, r.warnings.joinToString { it.text })
        assertTrue(legs[0].text.startsWith("${p.sessionOn(TUESDAY)!!.name} na quinta e ${p.sessionOn(FRIDAY)!!.name} na sexta"), legs[0].text)
        assertEquals(env.kb.ruleSet.scheduling.id, legs[0].ruleId)

        // Inferiores e superiores em dias seguidos não geram aviso.
        val env2 = upperLower()
        val ok = env2.app.swapDays(day(THURSDAY), day(SATURDAY), permanent = false)
        assertTrue(ok.warnings.none { it.text.contains("dias seguidos") }, ok.warnings.joinToString { it.text })
    }

    @Test fun `avisa perna pesada colada no esporte`() {
        val env = upperLower(listOf(SportCommitment(SportId("kickboxing"), WEDNESDAY, 3)))
        val p = env.app.program()!!.program
        // O motor afasta as pernas do kickboxing de quarta; trazer inferiores para quinta gera o aviso.
        val lowerDay = DayOfWeek.values().first { d -> p.sessionOn(d)?.let { isLower(env, it) } == true && d != TUESDAY && d != THURSDAY }
        val r = env.app.swapDays(day(lowerDay), day(THURSDAY), permanent = false)
        assertTrue(isLower(env, r.week.days.first { it.day == THURSDAY }.session!!))
        assertTrue(r.warnings.any { it.text.contains("Kickboxing") && it.text.contains("perto do esporte") }, r.warnings.joinToString { it.text })
    }

    @Test fun `dias invalidos sao recusados`() {
        val env = upperLower()
        env.at(TestEnv.MONDAY_9H.plusDays(2)) // quarta
        assertFailsWith<IllegalArgumentException> { env.app.swapDays(day(MONDAY), day(THURSDAY), false) }
        assertFailsWith<IllegalArgumentException> { env.app.swapDays(day(THURSDAY), day(THURSDAY), false) }
        assertFailsWith<IllegalArgumentException> { env.app.swapDays(day(THURSDAY), day(THURSDAY).plusDays(7), false) }
        assertFailsWith<IllegalArgumentException> { env.app.doToday(day(WEDNESDAY)) }
        assertFailsWith<IllegalArgumentException> { env.app.doToday(day(SATURDAY)) } // descanso
        assertNull(env.app.week()!!.plan)
    }

    @Test fun `troca preserva replanejamento anterior da semana`() {
        val env = upperLower()
        val base = baseKeys(env)
        env.app.swapDays(day(MONDAY), day(WEDNESDAY), permanent = false) // seg vira descanso, treino vai para quarta
        env.at(TestEnv.MONDAY_9H.plusDays(1)) // terça
        val r = env.app.swapDays(day(THURSDAY), day(FRIDAY), permanent = false)
        val k = keys(r.week)
        assertNull(k[MONDAY])
        assertEquals(base[MONDAY], k[WEDNESDAY])
        assertEquals(base[FRIDAY], k[THURSDAY])
        assertEquals(base[THURSDAY], k[FRIDAY])
        assertEquals(DayStatus.REST, r.week.days.first { it.day == MONDAY }.status, "segunda não vira treino perdido")
        assertTrue(r.week.plan!!.reason.contains("·"))
    }
}
