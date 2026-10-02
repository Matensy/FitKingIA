package com.fitkingia.appcore

import com.fitkingia.core.model.*
import com.fitkingia.core.program.ProgramResult
import com.fitkingia.core.recovery.ReadinessBand
import com.fitkingia.core.recovery.ReadinessCheck
import com.fitkingia.core.safety.ScreeningStatus
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import kotlin.test.*
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AppFlowTest {

    @Test fun `questionario so de toque gera e persiste o programa`() {
        val env = TestEnv()
        val a = TestEnv.answers(kb = env.kb)
        for (step in Questionnaire.steps(a)) assertNull(Questionnaire.blocker(step, a, env.kb), "passo $step bloqueado")
        val out = env.app.submit(a)
        val generated = assertIs<ProgramResult.Generated>(out.result).program
        assertEquals(ScreeningStatus.CLEAR, out.screening.status)

        val stored = assertNotNull(env.app.program())
        assertEquals(generated.split.id, stored.program.split.id)
        assertEquals(generated.sessions.map { it.key to it.day }, stored.program.sessions.map { it.key to it.day })
        assertEquals(
            generated.sessions.flatMap { s -> s.exercises.map { Triple(it.exercise.id, it.sets, it.prescription) } },
            stored.program.sessions.flatMap { s -> s.exercises.map { Triple(it.exercise.id, it.sets, it.prescription) } },
        )
        assertEquals(generated.weeklyVolume, stored.program.weeklyVolume)
        assertEquals(generated.explanations.map { it.text }, stored.program.explanations.map { it.text })

        // Perfil volta igual do banco, e as respostas reabrem o questionário preenchido.
        val p = assertNotNull(env.app.profile())
        assertEquals(out.profile.copy(name = p.name), p)
        val again = env.app.currentAnswers()
        assertEquals(a.primaryGoal, again.primaryGoal)
        assertEquals(a.minutesByDay, again.minutesByDay)
        assertEquals(a.equipment, again.equipment)
        assertEquals(a.safety, again.safety)
    }

    @Test fun `sinal de alerta na triagem bloqueia a prescricao`() {
        val env = TestEnv()
        val a = TestEnv.answers(kb = env.kb).apply { safety["chest_pain"] = true }
        val out = env.app.submit(a)
        assertIs<ProgramResult.Refused>(out.result)
        assertEquals(ScreeningStatus.REFER, out.screening.status)
        assertNull(env.app.program())
        assertTrue(env.app.hasProfile(), "respostas ficam salvas para refazer depois")
    }

    @Test fun `dor forte no joelho exclui exercicios que exigem o joelho`() {
        val env = TestEnv()
        val a = TestEnv.answers(kb = env.kb).apply { safety["current_pain"] = true; pains[Joint.KNEE] = 8 }
        assertTrue(Step.PAIN in Questionnaire.steps(a))
        val program = assertIs<ProgramResult.Generated>(env.app.submit(a).result).program
        val all = program.sessions.flatMap { it.exercises }
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.exercise.demand(Joint.KNEE) == 0 }, all.filter { it.exercise.demand(Joint.KNEE) > 0 }.joinToString { it.exercise.name })
        assertEquals(ScreeningStatus.CAUTION, env.app.screening()!!.status)
    }

    @Test fun `semana mostra treino perdido e opcao A move a sessao`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb, days = mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.WEDNESDAY to 60, DayOfWeek.FRIDAY to 60, DayOfWeek.SATURDAY to 60)))
        env.at(TestEnv.MONDAY_9H.plusDays(2)) // quarta
        val week = assertNotNull(env.app.week())
        val monday = week.days.first { it.day == DayOfWeek.MONDAY }
        assertEquals(DayStatus.MISSED, monday.status)
        assertEquals(DayStatus.TODAY, week.days.first { it.day == DayOfWeek.WEDNESDAY }.status)

        val today = assertNotNull(env.app.todayView())
        assertEquals(listOf(monday.date), today.pendingMissed.map { it.date })
        val report = assertNotNull(env.app.missedOptions(monday))
        assertEquals(listOf('A', 'B', 'C', 'D'), report.options.map { it.key })
        val a = report.options.first { it.key == 'A' }
        if (a.available) {
            env.app.applyMissed(monday, a)
            val after = assertNotNull(env.app.week())
            assertEquals(DayStatus.MISSED_RESOLVED, after.days.first { it.day == DayOfWeek.MONDAY }.status)
            assertNotNull(after.plan)
            assertTrue(after.days.filter { !it.date.isBefore(env.clock.now.toLocalDate()) }.any { it.session?.key == monday.session!!.key })
            assertTrue(env.app.todayView()!!.pendingMissed.isEmpty())
        }
    }

    @Test fun `treino registrado gera progressao e recorde`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        val today = assertNotNull(env.app.todayView())
        val session = assertNotNull(today.session)
        val main = session.exercises.first { it.role == SlotRole.MAIN && !it.exercise.timed && it.exercise.loadType != LoadType.BODYWEIGHT }
        val top = main.prescription.reps.last

        fun doWorkout(load: Double, reps: Int) {
            val w = env.app.startWorkout(session, today.sessionId, null)
            repeat(main.sets) { i -> env.app.logSet(w.id, main.exercise.id, i + 1, load, reps, main.prescription.rir) }
            env.app.finishWorkout(w.id, Perceived.ADEQUATE)
        }
        assertEquals(com.fitkingia.core.progression.ProgressionAction.START, env.app.suggestion(main).action)
        doWorkout(60.0, top)
        assertEquals(DayStatus.DONE, env.app.week()!!.days.first { it.day == DayOfWeek.MONDAY }.status)
        val s = env.app.suggestion(main)
        assertEquals(com.fitkingia.core.progression.ProgressionAction.INCREASE_LOAD, s.action)
        assertTrue(s.suggestedLoadKg!! > 60.0)

        env.at(TestEnv.MONDAY_9H.plusDays(7))
        val w = env.app.startWorkout(session, today.sessionId, null)
        repeat(main.sets) { i -> env.app.logSet(w.id, main.exercise.id, i + 1, s.suggestedLoadKg!!, main.prescription.reps.first, main.prescription.rir) }
        val summary = env.app.finishWorkout(w.id, Perceived.HARD)
        assertTrue(summary.records.isNotEmpty(), "carga maior = PR")
        assertTrue(env.app.records().isNotEmpty())
        assertTrue(summary.xpGained > 0)
        assertTrue(env.app.xpStatus().totalXp > 0)
    }

    @Test fun `treino interrompido pode ser retomado`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        val t = env.app.todayView()!!
        val w = env.app.startWorkout(t.session!!, t.sessionId, null)
        env.app.logSet(w.id, t.session!!.exercises[0].exercise.id, 1, 40.0, 10, 2)
        val resumed = assertNotNull(env.app.activeWorkout())
        assertEquals(w.id, resumed.id)
        assertEquals(t.session!!.exercises.map { it.exercise.id }, resumed.session.exercises.map { it.exercise.id })
        assertEquals(1, resumed.sets.size)
        assertEquals(w.id, env.app.startWorkout(t.session!!, t.sessionId, null).id, "não abre um segundo treino")
    }

    @Test fun `pouco tempo e prontidao baixa ajustam a sessao do dia`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        val normal = env.app.todayView()!!.session!!
        env.app.setQuickMinutes(30)
        val quick = env.app.todayView()!!
        assertTrue(quick.session!!.estimatedMinutes <= 30 || quick.changes.any { it.kind == com.fitkingia.core.session.ChangeKind.NOT_ENOUGH_TIME })
        assertTrue(quick.session!!.exercises.size <= normal.exercises.size)
        env.app.setQuickMinutes(null)

        val r = env.app.checkIn(ReadinessCheck(SleepQuality.POOR, energy = 2, soreness = 8, stress = 9, motivation = 2))
        assertTrue(r.band == ReadinessBand.LOW || r.band == ReadinessBand.VERY_LOW)
        val adapted = env.app.todayView()!!
        assertNotNull(adapted.readiness)
        assertTrue(adapted.changes.isNotEmpty())
        env.app.clearCheckIn()
        assertNull(env.app.todayView()!!.readiness)
    }

    @Test fun `troca de exercicio vale para o programa salvo`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        val session = env.app.program()!!.program.sessions.first()
        val target = session.exercises.first()
        val sub = assertNotNull(env.app.substitutes(target.exercise)).options.first()
        env.app.swap(target.exercise.id, sub.exercise.id, session.key)
        val after = env.app.program()!!.program.sessions.first { it.key == session.key }
        assertTrue(after.exercises.any { it.exercise.id == sub.exercise.id })
        assertTrue(after.exercises.none { it.exercise.id == target.exercise.id })
    }

    @Test fun `registrar dor refaz o programa com a restricao`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        val r = assertIs<ProgramResult.Generated>(env.app.reportPain(Joint.SHOULDER, 8))
        assertTrue(r.program.sessions.flatMap { it.exercises }.all { it.exercise.demand(Joint.SHOULDER) == 0 })
        assertEquals(listOf(Joint.SHOULDER), env.app.profile()!!.limitations.map { it.joint })
        env.app.resolvePain(Joint.SHOULDER)
        assertTrue(env.app.profile()!!.limitations.isEmpty())
    }

    @Test fun `agua, corpo, sono, comida, cardio e consistencia`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        val w0 = assertNotNull(env.app.water())
        assertTrue(w0.target.totalMl > 2000)
        repeat(20) { env.app.addWater(250) }
        val w = env.app.water()!!
        assertEquals(5000, w.progress.consumedMl)
        assertEquals(1, env.app.repo.xpEvents().count { it.first == "hydration_goal" }, "XP de hidratação uma vez por dia")
        env.app.undoWater()
        assertEquals(4750, env.app.water()!!.progress.consumedMl)

        env.app.logBody(80.0, 88.0)
        val body = env.app.body()
        assertNotNull(body.bmi)
        assertNotNull(body.waistToHeight)

        env.app.logSleep(7.5, 4)
        assertEquals(7.5, env.app.sleep().first().hours)

        val rice = env.kb.foods.first { it.id.value == "arroz_branco_cozido" }
        env.app.logFood(rice, 2.0, "Almoço")
        val n = env.app.nutrition()!!
        assertEquals(rice.kcal * rice.defaultServingG * 2 / 100, n.kcal, 1e-6)

        env.app.logCardio("Caminhada", 30, 4)
        env.app.logMobility("Quadril", 10)
        assertEquals(1, env.app.cardio().size)
        assertTrue(env.app.streak() >= 1)
        val c = env.app.consistency()
        assertEquals(3, c.weekPlanned)
    }

    @Test fun `exportar meus dados inclui todas as tabelas`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        env.app.addWater(500)
        val json = kotlinx.serialization.json.Json.parseToJsonElement(env.app.exportJson()).jsonObject
        assertEquals("FitKingIA", json["app"]!!.jsonPrimitive.content)
        assertEquals(1, json["users"]!!.jsonArray.size)
        assertEquals("500", json["water_logs"]!!.jsonArray[0].jsonObject["ml"]!!.jsonPrimitive.content)
        assertTrue(json["program_exercises"]!!.jsonArray.isNotEmpty())
    }

    @Test fun `apagar meus dados remove tudo`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        env.app.addWater(500)
        env.app.deleteEverything()
        assertFalse(env.app.hasProfile())
        assertNull(env.app.program())
        val left = env.db.single("SELECT (SELECT COUNT(*) FROM programs) + (SELECT COUNT(*) FROM program_exercises) + (SELECT COUNT(*) FROM water_logs) + (SELECT COUNT(*) FROM user_preferences) AS n") { it.int("n") }
        assertEquals(0, left)
    }

    @Test fun `simulador compara cenarios com o mesmo motor`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        val sim = assertNotNull(env.app.simulate(listOf(
            com.fitkingia.core.planning.ProgramSimulator.days(3), com.fitkingia.core.planning.ProgramSimulator.days(5),
        )))
        assertEquals(2, sim.rows.size)
        assertTrue(sim.rows.all { it.refusal == null })
    }
}
