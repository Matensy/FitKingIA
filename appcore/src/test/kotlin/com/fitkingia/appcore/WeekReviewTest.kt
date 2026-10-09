package com.fitkingia.appcore

import com.fitkingia.core.model.BodyRegion
import com.fitkingia.core.model.MuscleId
import com.fitkingia.core.model.SplitId
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.session.SessionFitter
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.*
import java.time.LocalDate
import kotlin.test.*

/** Regressões da revisão da semana: trocas, treino perdido, treino em andamento, virada de semana e desfazer. */
class WeekReviewTest {
    private val monday: LocalDate = TestEnv.MONDAY_9H.toLocalDate()
    private fun day(d: DayOfWeek, weeks: Long = 0): LocalDate = monday.plusDays(d.value - 1L).plusWeeks(weeks)

    /** Superiores/Inferiores em seg/ter/qui/sex, 60 min: seg upper_a, ter lower_a, qui upper_b, sex lower_b. */
    private fun upperLower(): TestEnv {
        val env = TestEnv()
        val a = TestEnv.answers(kb = env.kb, days = mapOf(MONDAY to 60, TUESDAY to 60, THURSDAY to 60, FRIDAY to 60))
        a.preferredSplit = SplitId("ul4")
        env.app.submit(a)
        return env
    }

    /** Corpo inteiro em seg/qua/sex; terça e sábado têm tempo, mas ficam livres. */
    private fun fullBodyWithFreeDays(): TestEnv {
        val env = TestEnv()
        val a = TestEnv.answers(kb = env.kb, days = mapOf(MONDAY to 60, TUESDAY to 60, WEDNESDAY to 60, FRIDAY to 60, SATURDAY to 60))
        a.maxDays = 3
        env.app.submit(a)
        val base = baseKeys(env)
        assertNull(base[TUESDAY], "esperava terça livre: $base")
        assertNull(base[SATURDAY], "esperava sábado livre: $base")
        return env
    }

    private fun keys(w: WeekView): Map<DayOfWeek, String?> = w.days.associate { it.day to it.session?.key }
    private fun baseKeys(env: TestEnv): Map<DayOfWeek, String?> =
        DayOfWeek.values().associateWith { d -> env.app.program()!!.program.sessionOn(d)?.key }

    private fun trainToday(env: TestEnv) {
        val t = env.app.todayView()!!
        val s = t.session!!
        val w = env.app.startWorkout(s, t.sessionId, null)
        env.app.logSet(w.id, s.exercises.first().exercise.id, 1, 40.0, 10, 2)
        env.app.finishWorkout(w.id, Perceived.ADEQUATE)
    }

    // ------------------------------------------------------------------------------------------
    // 1. Opção de treino perdido não apaga as trocas anteriores da semana
    // ------------------------------------------------------------------------------------------

    /** Segunda: troca seg⇄qui só nesta semana e treina; terça: falta; quarta: escolhe uma opção para a terça. */
    private fun swappedThenMissed(): Pair<TestEnv, Map<DayOfWeek, String?>> {
        val env = upperLower()
        val base = baseKeys(env)
        env.app.swapDays(day(MONDAY), day(THURSDAY), permanent = false)
        assertEquals(base[THURSDAY], env.app.todayView()!!.session!!.key)
        trainToday(env)
        env.at(TestEnv.MONDAY_9H.plusDays(2)) // quarta
        return env to base
    }

    @Test fun `opcao de treino perdido preserva a troca e o treino feito antes`() {
        val (probe, _) = swappedThenMissed()
        val tuesday = probe.app.week()!!.days.first { it.day == TUESDAY }
        assertEquals(DayStatus.MISSED, tuesday.status)
        val keysToTry = probe.app.missedOptions(tuesday)!!.options.filter { it.available }.map { it.key }
        assertTrue('B' in keysToTry || 'D' in keysToTry, keysToTry.toString())
        for (key in keysToTry) {
            val (env, base) = swappedThenMissed()
            val tue = env.app.week()!!.days.first { it.day == TUESDAY }
            val option = env.app.missedOptions(tue)!!.options.first { it.key == key }
            env.app.applyMissed(tue, option)
            val week = env.app.week()!!
            val days = week.days.associateBy { it.day }
            assertEquals(DayStatus.DONE, days.getValue(MONDAY).status, "opção $key: o treino feito na segunda continua feito")
            assertEquals(base[THURSDAY], days.getValue(MONDAY).session?.key, "opção $key: segunda continua com o treino trocado")
            assertEquals(DayStatus.MISSED_RESOLVED, days.getValue(TUESDAY).status, "opção $key")
            assertEquals(1, week.done, "opção $key")
            assertTrue(env.app.todayView()!!.pendingMissed.isEmpty(), "opção $key: nada volta a ficar pendente")
            if (key != 'C') {
                val future = week.days.filter { !it.date.isBefore(day(WEDNESDAY)) }
                val expected: Map<DayOfWeek?, String> = option.remainingWeek.associate { it.day to it.key }
                val shown: Map<DayOfWeek?, String> = future.filter { it.session != null }.associate { it.day to it.session!!.key }
                assertEquals(expected, shown, "opção $key")
            }
        }
    }

    /** Seg/qua/sex (terça e sábado livres); quarta: treina e depois abre as opções da segunda, que ficou sem treino. */
    private fun trainedTodayWithMissedMonday(): TestEnv {
        val env = fullBodyWithFreeDays()
        env.at(TestEnv.MONDAY_9H.plusDays(2)) // quarta
        trainToday(env)
        return env
    }

    @Test fun `opcao de treino perdido depois de treinar hoje nao mexe no treino feito`() {
        val probe = trainedTodayWithMissedMonday()
        val base = baseKeys(probe)
        val mon = probe.app.week()!!.days.first { it.day == MONDAY }
        val options = probe.app.missedOptions(mon)!!.options
        // Guarda do cenário: a opção D (recalcular) existe — antes ela punha outro treino hoje e o de hoje mais à frente.
        assertTrue(options.any { it.key == 'D' && it.available }, options.joinToString { "${it.key}=${it.available}" })
        for (o in options) {
            assertTrue(o.remainingWeek.none { it.day == WEDNESDAY }, "opção ${o.key} não remarca nada para hoje (já treinou)")
        }
        for (key in options.filter { it.available }.map { it.key }) {
            val env = trainedTodayWithMissedMonday()
            val monday = env.app.week()!!.days.first { it.day == MONDAY }
            env.app.applyMissed(monday, env.app.missedOptions(monday)!!.options.first { it.key == key })
            val week = env.app.week()!!
            val wed = week.days.first { it.day == WEDNESDAY }
            assertEquals(base[WEDNESDAY], wed.session?.key, "opção $key: o treino feito hoje continua hoje")
            assertEquals(DayStatus.DONE, wed.status, "opção $key")
            assertEquals(DayStatus.DONE, env.app.todayView()!!.today.status, "opção $key: a Home continua com ‘treino de hoje concluído’")
            assertTrue(week.days.none { it.date.isAfter(day(WEDNESDAY)) && it.status == DayStatus.DONE }, "opção $key: ${week.days.map { it.day to it.status }}")
            assertEquals(1, week.done, "opção $key")
        }
    }

    @Test fun `opcao A depois de remarcar mantem a copia e nao duplica a sessao`() {
        val env = fullBodyWithFreeDays()
        val base = baseKeys(env)
        env.at(TestEnv.MONDAY_9H.plusDays(1)) // terça: segunda ficou sem treino
        env.app.doToday(day(MONDAY))
        assertEquals(base[MONDAY], keys(env.app.week()!!)[TUESDAY])
        env.at(TestEnv.MONDAY_9H.plusDays(3)) // quinta: terça e quarta passaram sem treino
        val tuesday = env.app.week()!!.days.first { it.day == TUESDAY }
        assertEquals(DayStatus.MISSED, tuesday.status)
        val a = env.app.missedOptions(tuesday)!!.options.first { it.key == 'A' }
        assertTrue(a.available, a.description)
        env.app.applyMissed(tuesday, a)

        val week = env.app.week()!!
        val k = keys(week)
        assertEquals(base[MONDAY], k[TUESDAY], "a terça (que recebeu o treino de segunda) não vira descanso")
        assertEquals(1, week.days.count { !it.date.isBefore(day(THURSDAY)) && it.session?.key == base[MONDAY] }, "o treino remarcado aparece uma vez daqui em diante: $k")
        assertEquals(listOf(WEDNESDAY), env.app.todayView()!!.pendingMissed.map { it.day }, "só a quarta continua pendente")
    }

    // ------------------------------------------------------------------------------------------
    // 5. Trazer para hoje um treino perdido que já tinha sido remarcado (e a cópia também passou)
    // ------------------------------------------------------------------------------------------

    @Test fun `fazer hoje um treino perdido ja remarcado resolve a copia pendente`() {
        val env = fullBodyWithFreeDays()
        val base = baseKeys(env)
        env.at(TestEnv.MONDAY_9H.plusDays(1)) // terça
        env.app.doToday(day(MONDAY))
        env.at(TestEnv.MONDAY_9H.plusDays(3)) // quinta (descanso)
        assertEquals(listOf(TUESDAY, WEDNESDAY), env.app.todayView()!!.pendingMissed.map { it.day })
        assertEquals(day(TUESDAY), env.app.rescheduledTo(day(MONDAY))?.date, "a cópia de segunda está na terça (que também passou)")
        assertNull(env.app.rescheduledTo(day(WEDNESDAY)), "quarta não foi remarcada")

        // Semana → segunda (replanejada) → “Fazer este treino hoje”.
        val preview = env.app.doTodayDisplaces(day(MONDAY))
        assertNull(preview, "quinta é descanso: nada é deslocado")
        env.app.doToday(day(MONDAY))
        val week = env.app.week()!!
        val days = week.days.associateBy { it.day }
        assertEquals(base[MONDAY], days.getValue(THURSDAY).session?.key)
        assertEquals(DayStatus.MISSED_RESOLVED, days.getValue(TUESDAY).status, "a cópia da terça fica resolvida")
        assertEquals('A', days.getValue(TUESDAY).missedOption)
        assertEquals(listOf(WEDNESDAY), env.app.todayView()!!.pendingMissed.map { it.day }, "o card de terça some da Home")
        assertEquals(day(THURSDAY), env.app.rescheduledTo(day(MONDAY))?.date)
        assertEquals(1, week.days.count { !it.date.isBefore(day(THURSDAY)) && it.session?.key == base[MONDAY] })
        // De novo: já está hoje.
        assertFailsWith<IllegalArgumentException> { env.app.doToday(day(MONDAY)) }
    }

    // ------------------------------------------------------------------------------------------
    // 2. Treino em andamento
    // ------------------------------------------------------------------------------------------

    @Test fun `com treino em andamento a troca do treino de hoje e recusada`() {
        val env = upperLower()
        val base = baseKeys(env)
        val t = env.app.todayView()!!
        val w = env.app.startWorkout(t.session!!, t.sessionId, null)
        env.app.logSet(w.id, t.session!!.exercises.first().exercise.id, 1, 40.0, 10, 2) // “Sair e retomar depois”
        assertEquals(w.id, env.app.workoutBlockingToday()?.id)

        val e = assertFailsWith<IllegalArgumentException> { env.app.doToday(day(THURSDAY)) }
        assertTrue(e.message!!.contains("em andamento"), e.message)
        assertFailsWith<IllegalArgumentException> { env.app.swapDays(day(MONDAY), day(THURSDAY), permanent = false) }
        assertNull(env.app.week()!!.plan, "nada mudou")
        // Dias que não mexem no treino aberto continuam livres para trocar.
        env.app.swapDays(day(THURSDAY), day(FRIDAY), permanent = false)

        // Ao concluir, quem fica feito é a segunda (o treino aberto), e a quinta não.
        env.app.finishWorkout(w.id, Perceived.ADEQUATE)
        val days = env.app.week()!!.days.associateBy { it.day }
        assertEquals(DayStatus.DONE, days.getValue(MONDAY).status)
        assertEquals(base[MONDAY], days.getValue(MONDAY).session?.key)
        assertEquals(DayStatus.PLANNED, days.getValue(THURSDAY).status)
        assertNull(env.app.workoutBlockingToday())
    }

    @Test fun `opcao de treino perdido nao leva para outro dia o treino em andamento`() {
        val env = fullBodyWithFreeDays()
        val base = baseKeys(env)
        val t = env.app.todayView()!!
        val w = env.app.startWorkout(t.session!!, t.sessionId, null)
        env.app.logSet(w.id, t.session!!.exercises.first().exercise.id, 1, 40.0, 10, 2) // “Sair e retomar depois”
        env.at(TestEnv.MONDAY_9H.plusDays(2)) // quarta: o treino de segunda continua aberto
        val mon = env.app.week()!!.days.first { it.day == MONDAY }
        assertEquals(DayStatus.MISSED, mon.status)
        val a = env.app.missedOptions(mon)!!.options.first { it.key == 'A' }
        assertTrue(a.available, a.description)
        assertTrue(a.remainingWeek.any { it.key == base[MONDAY] }, "a opção A remarca o treino de segunda")

        val e = assertFailsWith<IllegalArgumentException> { env.app.applyMissed(mon, a) }
        assertTrue(e.message!!.contains("em andamento"), e.message)
        assertNull(env.app.week()!!.plan, "nada mudou")

        // Ao concluir o treino aberto, só a segunda fica feita — nenhum dia futuro aparece como feito.
        env.app.finishWorkout(w.id, Perceived.ADEQUATE)
        val days = env.app.week()!!.days
        assertEquals(DayStatus.DONE, days.first { it.day == MONDAY }.status)
        assertTrue(days.none { it.date.isAfter(day(WEDNESDAY)) && it.status == DayStatus.DONE }, days.map { it.day to it.status }.toString())
    }

    @Test fun `comecar outro treino nao abre em silencio o treino em andamento`() {
        val env = upperLower()
        val stored = env.app.program()!!
        val t = env.app.todayView()!!
        val open = env.app.startWorkout(t.session!!, t.sessionId, null)
        val other = stored.program.sessionOn(THURSDAY)!!
        val otherId = stored.sessionIds.getValue(other.key)
        assertEquals(open.id, env.app.openWorkoutConflict(other, otherId)?.id)
        assertNull(env.app.openWorkoutConflict(t.session!!, t.sessionId), "o mesmo treino é só retomado")
        assertFailsWith<IllegalArgumentException> { env.app.startWorkout(other, otherId, null) }
        assertEquals(open.id, env.app.startWorkout(t.session!!, t.sessionId, null).id)

        env.app.discardWorkout(open.id)
        val started = env.app.startWorkout(other, otherId, null)
        assertEquals(other.key, started.session.key)
        assertEquals(otherId, started.sessionId)
        assertTrue(started.sets.isEmpty(), "treino novo, sem as séries do descartado")
    }

    @Test fun `treino aberto de outra semana nao e retomado em silencio`() {
        val env = upperLower()
        val t = env.app.todayView()!!
        val old = env.app.startWorkout(t.session!!, t.sessionId, null)
        env.app.logSet(old.id, t.session!!.exercises.first().exercise.id, 1, 40.0, 10, 2) // nunca concluído
        env.at(TestEnv.MONDAY_9H.plusWeeks(1)) // segunda seguinte: a mesma sessão
        val now = env.app.todayView()!!
        assertEquals(t.session!!.key, now.session!!.key, "guarda do cenário: mesma sessão nas duas segundas")
        assertEquals(old.id, env.app.openWorkoutConflict(now.session!!, now.sessionId)?.id, "a tela pergunta antes")
        assertFailsWith<IllegalArgumentException> { env.app.startWorkout(now.session!!, now.sessionId, null) }

        env.app.discardWorkout(old.id)
        val w = env.app.startWorkout(now.session!!, now.sessionId, null)
        assertEquals(TestEnv.MONDAY_9H.plusWeeks(1), w.startedAt, "treino novo, desta segunda")
        assertTrue(w.sets.isEmpty())
        env.app.logSet(w.id, now.session!!.exercises.first().exercise.id, 1, 40.0, 10, 2)
        env.app.finishWorkout(w.id, Perceived.ADEQUATE)
        assertEquals(DayStatus.DONE, env.app.todayView()!!.today.status, "o treino desta segunda fica feito")
    }

    // ------------------------------------------------------------------------------------------
    // 3. Troca permanente depois de troca só desta semana
    // ------------------------------------------------------------------------------------------

    @Test fun `troca permanente segue o que a semana mostra mesmo com troca anterior so desta semana`() {
        val env = upperLower()
        val base = baseKeys(env)
        env.app.swapDays(day(THURSDAY), day(FRIDAY), permanent = false) // qui lower_b, sex upper_b
        val r = env.app.swapDays(day(TUESDAY), day(THURSDAY), permanent = true)
        val week = keys(r.week)
        assertEquals(base[FRIDAY], week[TUESDAY])
        assertEquals(base[TUESDAY], week[THURSDAY])
        val program = baseKeys(env)
        assertEquals(week, program, "o programa fica como a semana mostra")
        assertEquals(base.values.filterNotNull().sorted(), program.values.filterNotNull().sorted(), "nenhuma sessão some nem se repete")
        val names = env.app.program()!!.program.sessions.associate { it.key to it.name }
        val summary = r.moves.first { it.startsWith("Nas próximas semanas") }
        assertTrue(summary.contains("terça ${names[base[FRIDAY]]}") && summary.contains("quinta ${names[base[TUESDAY]]}"), summary)
        assertTrue(r.moves.any { it.contains("sexta também fica como nesta semana") }, r.moves.toString())

        env.at(TestEnv.MONDAY_9H.plusWeeks(1))
        assertEquals(week, keys(env.app.week()!!), "a próxima semana vem como esta")
    }

    @Test fun `troca permanente para o sabado leva a sessao que a semana mostra`() {
        val env = upperLower()
        val base = baseKeys(env)
        env.app.swapDays(day(THURSDAY), day(FRIDAY), permanent = false) // qui lower_b, sex upper_b
        val r = env.app.swapDays(day(FRIDAY), day(SATURDAY), permanent = true)
        assertEquals(base[THURSDAY], keys(r.week)[SATURDAY])
        assertEquals(base[THURSDAY], baseKeys(env)[SATURDAY], "Superiores B fica no sábado nas próximas semanas")
        assertEquals(base[FRIDAY], baseKeys(env)[THURSDAY])
        assertNull(baseKeys(env)[FRIDAY])
        env.at(TestEnv.MONDAY_9H.plusWeeks(1))
        assertEquals(keys(r.week), keys(env.app.week()!!))
    }

    @Test fun `troca permanente avisa sobre o programa novo mesmo que esta semana nao mostre`() {
        val env = upperLower()
        env.app.swapDays(day(FRIDAY), day(SATURDAY), permanent = false) // nesta semana, sexta descansa
        val r = env.app.swapDays(day(TUESDAY), day(THURSDAY), permanent = true)
        // Programa: qui Inferiores A e sex Inferiores B — pernas em dias seguidos nas próximas semanas.
        val legs = r.warnings.filter { it.text.contains("pernas em dias seguidos") }
        assertTrue(legs.any { it.text.startsWith("Nas próximas semanas") && it.text.contains("na quinta") && it.text.contains("na sexta") }, r.warnings.joinToString { it.text })
    }

    @Test fun `troca permanente com treino remarcado na semana e recusada com explicacao`() {
        val env = fullBodyWithFreeDays()
        env.at(TestEnv.MONDAY_9H.plusDays(2)) // quarta: segunda ficou sem treino
        val mon = env.app.week()!!.days.first { it.day == MONDAY }
        val a = env.app.missedOptions(mon)!!.options.first { it.key == 'A' }
        env.app.applyMissed(mon, a)
        val copy = env.app.week()!!.days.first { it.date.isAfter(day(WEDNESDAY)) && it.session?.key == mon.session!!.key }
        val other = if (copy.day == SUNDAY) day(SATURDAY) else day(SUNDAY)
        val before = baseKeys(env)
        val e = assertFailsWith<IllegalArgumentException> { env.app.swapDays(copy.date, other, permanent = true) }
        assertTrue(e.message!!.contains("próxima semana"), e.message)
        assertEquals(before, baseKeys(env))
        // Só nesta semana continua possível.
        env.app.swapDays(copy.date, other, permanent = false)
    }

    @Test fun `ordem do programa na troca permanente`() {
        val base = mapOf("ua" to MONDAY, "la" to TUESDAY, "ub" to THURSDAY, "lb" to FRIDAY)
        fun shown(vararg p: Pair<DayOfWeek, String>) = DayOfWeek.values().associateWith { d -> p.firstOrNull { it.first == d }?.second }
        // Sem replanejamento: troca simples.
        assertEquals(mapOf(TUESDAY to "ub", THURSDAY to "la"),
            WeekReorder.programOrder(base, shown(MONDAY to "ua", TUESDAY to "ub", THURSDAY to "la", FRIDAY to "lb"), TUESDAY, THURSDAY))
        // Já trocado só nesta semana e trocado de volta: o programa não muda.
        assertEquals(emptyMap(), WeekReorder.programOrder(base, base.entries.associate { (k, d) -> d to k }.let { m -> DayOfWeek.values().associateWith { m[it] } }, THURSDAY, FRIDAY))
        // Cadeia: qui⇄sex só nesta semana, depois ter⇄qui para sempre.
        assertEquals(mapOf(TUESDAY to "lb", THURSDAY to "la", FRIDAY to "ub"),
            WeekReorder.programOrder(base, shown(MONDAY to "ua", TUESDAY to "lb", THURSDAY to "la", FRIDAY to "ub"), TUESDAY, THURSDAY))
        // Para um dia de descanso.
        assertEquals(mapOf(FRIDAY to null, SATURDAY to "lb"),
            WeekReorder.programOrder(base, shown(MONDAY to "ua", TUESDAY to "la", THURSDAY to "ub", SATURDAY to "lb"), FRIDAY, SATURDAY))
        // Sessão repetida na semana (treino remarcado): ordem ambígua.
        assertNull(WeekReorder.programOrder(base, shown(MONDAY to "ua", TUESDAY to "la", THURSDAY to "ub", FRIDAY to "lb", SUNDAY to "ua"), SATURDAY, SUNDAY))
    }

    // ------------------------------------------------------------------------------------------
    // 4. Região priorizada protegida no reajuste ao tempo
    // ------------------------------------------------------------------------------------------

    private fun glutes(env: TestEnv): Set<MuscleId> = env.kb.trackedMuscles.filter { it.focusRegion == BodyRegion.GLUTES }.map { it.id }.toSet()

    private fun gluteSets(s: PlannedSession, protect: Set<MuscleId>) =
        s.exercises.filter { e -> e.exercise.primaryMuscles.any { it in protect } }.sumOf { it.sets }

    @Test fun `troca para dia curto protege a regiao priorizada como o gerador`() {
        val env = TestEnv()
        val a = TestEnv.answers(kb = env.kb, days = mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60, SATURDAY to 30))
        Questionnaire.togglePriority(a, BodyRegion.GLUTES)
        env.app.submit(a)
        val program = env.app.program()!!.program
        assertEquals(setOf(BodyRegion.GLUTES), program.priorities)
        val protect = glutes(env)
        val wed = program.sessionOn(WEDNESDAY)!!
        val fitter = SessionFitter(env.kb)
        val expected = fitter.fit(wed, 30, protect = protect).session
        val unprotected = fitter.fit(wed, 30).session
        // Guarda do cenário: aqui a proteção faz diferença (senão o teste não provaria nada).
        assertTrue(gluteSets(expected, protect) > gluteSets(unprotected, protect), "${gluteSets(expected, protect)} × ${gluteSets(unprotected, protect)}")

        // Só esta semana: a sessão no sábado é a versão protegida.
        val temp = env.app.swapDays(day(WEDNESDAY), day(SATURDAY), permanent = false)
        val sat = temp.week.days.first { it.day == SATURDAY }.session!!
        assertEquals(expected.exercises.map { it.exercise.id to it.sets }, sat.exercises.map { it.exercise.id to it.sets })
        env.app.swapDays(day(WEDNESDAY), day(SATURDAY), permanent = false) // volta

        // Todas as semanas: o corte que fica no programa também protege os glúteos.
        env.app.swapDays(day(WEDNESDAY), day(SATURDAY), permanent = true)
        val stored = env.app.program()!!.program.sessionOn(SATURDAY)!!
        assertEquals(expected.exercises.map { it.exercise.id to it.sets }, stored.exercises.map { it.exercise.id to it.sets })
    }

    @Test fun `pouco tempo protege a regiao priorizada`() {
        val env = TestEnv()
        val a = TestEnv.answers(kb = env.kb)
        Questionnaire.togglePriority(a, BodyRegion.GLUTES)
        env.app.submit(a)
        val base = env.app.todayView()!!.session!!
        val minutes = 40
        env.app.setQuickMinutes(minutes)
        val quick = env.app.todayView()!!.session!!
        val protect = glutes(env)
        val fitter = SessionFitter(env.kb)
        val expected = fitter.fit(base, minutes, protect = protect).session
        // Guarda do cenário: com esses minutos a proteção muda o resultado (com 25 min não mudava).
        assertTrue(gluteSets(expected, protect) > gluteSets(fitter.fit(base, minutes).session, protect))
        assertEquals(expected.exercises.map { it.exercise.id to it.sets }, quick.exercises.map { it.exercise.id to it.sets })
    }

    @Test fun `treino perdido remarcado para dia curto protege a regiao priorizada`() {
        val env = TestEnv()
        val a = TestEnv.answers(kb = env.kb, days = mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60, SATURDAY to 30))
        a.maxDays = 3
        Questionnaire.togglePriority(a, BodyRegion.GLUTES)
        env.app.submit(a)
        assertNull(baseKeys(env)[SATURDAY], "sábado livre (30 min)")
        env.at(TestEnv.MONDAY_9H.plusDays(3)) // quinta: quarta ficou sem treino
        val wed = env.app.week()!!.days.first { it.day == WEDNESDAY }
        val protect = glutes(env)
        val fitter = SessionFitter(env.kb)
        val expected = fitter.fit(wed.session!!, 30, protect = protect).session
        assertTrue(gluteSets(expected, protect) > gluteSets(fitter.fit(wed.session!!, 30).session, protect), "guarda do cenário")

        val moveA = env.app.missedOptions(wed)!!.options.first { it.key == 'A' }
        val sat = moveA.remainingWeek.first { it.day == SATURDAY }
        assertEquals(expected.exercises.map { it.exercise.id to it.sets }, sat.exercises.map { it.exercise.id to it.sets })
        val recalc = env.app.missedOptions(wed)!!.options.first { it.key == 'D' }
        recalc.remainingWeek.firstOrNull { it.day == SATURDAY && it.key == wed.session!!.key }?.let { s ->
            assertEquals(expected.exercises.map { it.exercise.id to it.sets }, s.exercises.map { it.exercise.id to it.sets })
        }
    }

    // ------------------------------------------------------------------------------------------
    // 6. “Por que hoje?” explica o treino que a semana mostra
    // ------------------------------------------------------------------------------------------

    @Test fun `por que hoje explica o treino trocado e nao o dia do programa`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb)) // seg/qua/sex
        val base = baseKeys(env)
        env.at(TestEnv.MONDAY_9H.plusDays(1)) // terça, descanso
        env.app.doToday(day(WEDNESDAY))
        val today = env.app.todayView()!!
        assertEquals(base[WEDNESDAY], today.session?.key)
        val why = assertNotNull(env.app.whyOn(day(TUESDAY)))
        assertEquals("Por que ${today.session!!.name} hoje?", why.title)
        assertTrue(why.lines.first().contains("no programa, fica na quarta"), why.lines.first())
        assertFalse(why.title.contains("não treino"))
        // Dia sem troca: explica o dia do programa, com o artigo certo.
        val fri = assertNotNull(env.app.whyOn(day(FRIDAY)))
        assertTrue(fri.title.endsWith("na sexta?"), fri.title)
    }

    @Test fun `por que no sabado usa o artigo certo`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb, days = mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60, SATURDAY to 30)))
        val p = env.app.program()!!.program
        val title = env.app.why.forDay(p, SATURDAY).title
        assertTrue(title.endsWith("no sábado?"), title)
        assertTrue(env.app.whyOn(day(SATURDAY))!!.title.endsWith("no sábado?"))
    }

    // ------------------------------------------------------------------------------------------
    // 7 e extra. Virada de semana: semana anterior (consulta), próxima (trocas) e o treino perdido do fim de semana
    // ------------------------------------------------------------------------------------------

    /** Seg/qua/sáb; treina segunda e quarta, falta no sábado. */
    private fun missedSaturday(): TestEnv {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb, days = mapOf(MONDAY to 60, WEDNESDAY to 60, SATURDAY to 60)))
        trainToday(env)
        env.at(TestEnv.MONDAY_9H.plusDays(2))
        trainToday(env)
        env.at(TestEnv.MONDAY_9H.plusWeeks(1)) // segunda seguinte
        return env
    }

    @Test fun `na segunda o treino perdido de sabado aparece e a semana anterior pode ser vista`() {
        val env = missedSaturday()
        val lastSaturday = day(SATURDAY)
        assertEquals(listOf(lastSaturday), env.app.lastWeekMissed().map { it.date })
        assertTrue(env.app.todayView()!!.pendingMissed.isEmpty(), "as opções A–D são só desta semana")
        assertNull(env.app.missedOptions(env.app.week(lastSaturday)!!.days.first { it.date == lastSaturday }))
        assertEquals(listOf(monday, monday.plusWeeks(1), monday.plusWeeks(2)), env.app.browsableWeeks())
        val last = env.app.week(monday)!!
        assertEquals(DayStatus.DONE, last.days.first { it.day == MONDAY }.status)
        assertEquals(DayStatus.MISSED, last.days.first { it.day == SATURDAY }.status)

        env.app.dismissLastWeekMissed()
        assertTrue(env.app.lastWeekMissed().isEmpty())
        assertEquals(DayStatus.MISSED, env.app.week(monday)!!.days.first { it.day == SATURDAY }.status, "dispensar não inventa uma decisão")
    }

    @Test fun `semana anterior so aparece desde o programa atual`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        assertEquals(listOf(monday, monday.plusWeeks(1)), env.app.browsableWeeks())
        env.at(TestEnv.MONDAY_9H.plusWeeks(10))
        val weeks = env.app.browsableWeeks()
        assertEquals(monday.plusWeeks(10 - FitKing.HISTORY_WEEKS), weeks.first())
        assertEquals(monday.plusWeeks(11), weeks.last())
    }

    @Test fun `no domingo da para trocar os dias da proxima semana`() {
        val env = upperLower()
        val base = baseKeys(env)
        env.at(TestEnv.MONDAY_9H.plusDays(6)) // domingo
        val r = env.app.swapDays(day(MONDAY, 1), day(TUESDAY, 1), permanent = false)
        assertEquals(day(MONDAY, 1), r.week.weekStart)
        assertEquals(base[TUESDAY], keys(r.week)[MONDAY])
        assertEquals(base, baseKeys(env), "só nessa semana")
        assertNull(env.app.week()!!.plan, "esta semana não muda")
        env.at(TestEnv.MONDAY_9H.plusWeeks(1))
        assertEquals(base[TUESDAY], env.app.todayView()!!.session?.key)
        env.at(TestEnv.MONDAY_9H.plusWeeks(2))
        assertEquals(base, keys(env.app.week()!!), "duas semanas depois, o programa normal")
    }

    @Test fun `troca permanente na proxima semana nao muda esta semana nem as anteriores`() {
        val env = upperLower()
        val base = baseKeys(env)
        trainToday(env)
        env.at(TestEnv.MONDAY_9H.plusWeeks(1).plusDays(3)) // quinta da semana seguinte
        trainToday(env)
        val thisWeek = keys(env.app.week()!!)
        val lastWeek = env.app.week(monday)!!
        // Na quinta, segunda ⇄ terça de vez: pela próxima semana.
        val r = env.app.swapDays(day(MONDAY, 2), day(TUESDAY, 2), permanent = true)
        assertEquals(base[TUESDAY], baseKeys(env)[MONDAY])
        assertEquals(base[MONDAY], baseKeys(env)[TUESDAY])
        assertTrue(r.moves.any { it.startsWith("A partir da semana de") }, r.moves.toString())
        assertEquals(thisWeek, keys(env.app.week()!!), "esta semana continua como estava")
        val lastAfter = env.app.week(monday)!!
        assertEquals(keys(lastWeek), keys(lastAfter), "a semana passada também")
        assertEquals(lastWeek.days.map { it.status }, lastAfter.days.map { it.status })
        assertEquals(DayStatus.DONE, env.app.week()!!.days.first { it.day == THURSDAY }.status)
        env.at(TestEnv.MONDAY_9H.plusWeeks(2))
        assertEquals(base[TUESDAY], env.app.todayView()!!.session?.key)
    }

    // ------------------------------------------------------------------------------------------
    // 8. Desfazer
    // ------------------------------------------------------------------------------------------

    @Test fun `fazer hoje sem dia livre pode ser desfeito`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        val base = baseKeys(env)
        env.at(TestEnv.MONDAY_9H.plusDays(2)) // quarta
        val r = env.app.doToday(day(MONDAY))
        assertEquals(listOf(base[WEDNESDAY]), r.dropped.map { it.key })
        env.app.undoReorder(assertNotNull(r.undo))
        val week = env.app.week()!!
        assertNull(week.plan)
        assertEquals(base, keys(week))
        assertEquals(DayStatus.MISSED, week.days.first { it.day == MONDAY }.status, "a decisão de treino perdido também volta")
        assertEquals(listOf(day(MONDAY)), env.app.todayView()!!.pendingMissed.map { it.date })
        assertEquals(base[WEDNESDAY], env.app.todayView()!!.session?.key)
    }

    @Test fun `troca permanente com corte pode ser desfeita`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb, days = mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60, SATURDAY to 30)))
        val before = env.app.program()!!.program.sessions.map { Triple(it.key, it.day, it.exercises.map { e -> e.exercise.id to e.sets }) }
        val r = env.app.swapDays(day(WEDNESDAY), day(SATURDAY), permanent = true)
        assertTrue(r.warnings.any { it.text.contains("Desfazer") }, r.warnings.joinToString { it.text })
        env.app.undoReorder(r.undo!!)
        val after = env.app.program()!!.program.sessions.map { Triple(it.key, it.day, it.exercises.map { e -> e.exercise.id to e.sets }) }
        assertEquals(before, after, "programa volta inteiro, inclusive os exercícios cortados")
        assertNull(env.app.week()!!.plan)
    }

    @Test fun `desfazer e recusado se a semana mudou depois`() {
        val env = upperLower()
        val first = env.app.swapDays(day(THURSDAY), day(FRIDAY), permanent = false)
        env.app.swapDays(day(TUESDAY), day(SATURDAY), permanent = false)
        assertFailsWith<IllegalArgumentException> { env.app.undoReorder(first.undo!!) }
        val second = env.app.doToday(day(THURSDAY))
        trainToday(env)
        assertFailsWith<IllegalArgumentException> { env.app.undoReorder(second.undo!!) }
    }

    // ------------------------------------------------------------------------------------------
    // 9. Gerar o programa de novo mantém a ordem de dias escolhida
    // ------------------------------------------------------------------------------------------

    @Test fun `gerar de novo mantem a ordem escolhida e o questionario recomeca`() {
        val env = upperLower()
        val base = baseKeys(env)
        env.app.swapDays(day(TUESDAY), day(THURSDAY), permanent = true)
        val chosen = baseKeys(env)
        assertNotEquals(base, chosen)
        env.app.regenerate()
        assertEquals(chosen, baseKeys(env), "dor registrada / gerar de novo não desfaz a ordem dos dias")
        env.app.reportPain(com.fitkingia.core.model.Joint.WRIST, 2)
        assertEquals(chosen, baseKeys(env))

        val a = env.app.currentAnswers()
        a.preferredSplit = SplitId("ul4")
        env.app.submit(a)
        assertEquals(base, baseKeys(env), "questionário refeito: o motor decide os dias de novo")
    }
}
