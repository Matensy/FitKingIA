package com.fitkingia.appcore

import com.fitkingia.core.model.Fmt
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.*

class RemindersTest {
    private val monday: LocalDate = TestEnv.MONDAY_9H.toLocalDate()
    private fun at(h: Int, m: Int = 0, date: LocalDate = monday): LocalDateTime = date.atTime(h, m)
    private val water2h = ReminderSettings(waterEnabled = true, waterEveryHours = 2, windowStart = 8, windowEnd = 21)

    private fun waterCtx(now: LocalDateTime, consumed: Int, target: Int = 3000) =
        ReminderContext(now, waterConsumedMl = consumed, waterTargetMl = target, workoutDay = WorkoutDay.DONE)

    // ---------------------------------------------------------------------------------------
    // Preferências
    // ---------------------------------------------------------------------------------------

    @Test fun `padrao e tudo desligado e sem proximo horario`() {
        val env = TestEnv()
        assertEquals(ReminderSettings.DEFAULT, Reminders.settings(env.app))
        assertFalse(Reminders.settings(env.app).anyEnabled)
        assertFalse(Reminders.save(env.app, water2h), "sem perfil não há onde guardar")
        env.app.submit(TestEnv.answers(kb = env.kb))
        assertEquals(ReminderSettings.DEFAULT, Reminders.settings(env.app))
        assertNull(Reminders.next(env.app))
    }

    @Test fun `preferencias vao e voltam do banco com chaves reminder`() {
        val env = TestEnv()
        env.app.submit(TestEnv.answers(kb = env.kb))
        val s = ReminderSettings(waterEnabled = true, waterEveryHours = 3, windowStart = 6, windowEnd = 22,
            workoutEnabled = true, workoutHour = 6, motivationEnabled = true)
        assertTrue(Reminders.save(env.app, s))
        assertEquals(s, Reminders.settings(env.app))
        assertEquals("true", env.app.repo.pref(ReminderSettings.KEY_WATER))
        assertEquals("3", env.app.repo.pref(ReminderSettings.KEY_WATER_EVERY))
        assertEquals("6", env.app.repo.pref(ReminderSettings.KEY_WORKOUT_HOUR))
        // Desligar volta ao silêncio.
        Reminders.save(env.app, ReminderSettings.DEFAULT)
        assertNull(Reminders.next(env.app))
        // Apagar os dados apaga as preferências junto (ON DELETE CASCADE).
        Reminders.save(env.app, s)
        env.app.deleteEverything()
        assertEquals(ReminderSettings.DEFAULT, Reminders.settings(env.app))
    }

    @Test fun `horario do treino fica sempre dentro da janela`() {
        assertEquals(12, ReminderSettings(windowStart = 9, windowEnd = 20, workoutHour = 7).normalized().workoutHour)
        assertEquals(19, ReminderSettings(windowStart = 8, windowEnd = 19, workoutHour = 20).normalized().workoutHour)
        assertEquals(6, ReminderSettings(windowStart = 6, windowEnd = 21, workoutHour = 6).normalized().workoutHour)
        assertEquals(listOf(12, 17, 18, 19, 20), ReminderSettings(windowStart = 10, windowEnd = 22).workoutHoursInWindow)
        // Valores fora das opções voltam ao padrão.
        val odd = ReminderSettings(waterEveryHours = 5, windowStart = 3, windowEnd = 23).normalized()
        assertEquals(Triple(2, 8, 21), Triple(odd.waterEveryHours, odd.windowStart, odd.windowEnd))
    }

    // ---------------------------------------------------------------------------------------
    // Horários: janela, intervalo, virada do dia
    // ---------------------------------------------------------------------------------------

    @Test fun `agua respeita intervalo e janela`() {
        fun hours(every: Int, start: Int = 8, end: Int = 21) =
            ReminderPlanner.slots(water2h.copy(waterEveryHours = every, windowStart = start, windowEnd = end), monday).map { it.time.hour }
        assertEquals(listOf(10, 12, 14, 16, 18, 20), hours(2))
        assertEquals(listOf(11, 14, 17, 20), hours(3))
        assertEquals((9..21).toList(), hours(1))
        assertEquals(listOf(9, 12, 15, 18, 21), hours(3, start = 6, end = 22))
        assertTrue(ReminderPlanner.slots(ReminderSettings.DEFAULT, monday).isEmpty())
    }

    @Test fun `proximo horario e estritamente depois de agora e vira o dia`() {
        assertEquals(at(10), ReminderPlanner.next(water2h, at(9))!!.time)
        assertEquals(at(12), ReminderPlanner.next(water2h, at(10))!!.time, "o horário que acabou de disparar não se repete")
        assertEquals(at(12), ReminderPlanner.next(water2h, at(10, 1))!!.time)
        assertEquals(at(10, date = monday.plusDays(1)), ReminderPlanner.next(water2h, at(20, 30))!!.time)
        assertEquals(at(10, date = monday.plusDays(1)), ReminderPlanner.next(water2h, at(23, 59))!!.time)
        assertEquals(at(10), ReminderPlanner.next(water2h, at(0, 5))!!.time)

        val early = ReminderSettings(workoutEnabled = true, workoutHour = 7, windowStart = 7)
        assertEquals(at(7, date = monday.plusDays(1)), ReminderPlanner.next(early, at(21, 30))!!.time)
        assertNull(ReminderPlanner.next(ReminderSettings.DEFAULT, at(9)))
    }

    @Test fun `tipos no mesmo horario saem juntos`() {
        val s = water2h.copy(workoutEnabled = true, workoutHour = 18, motivationEnabled = true)
        val slot = ReminderPlanner.next(s, at(17))!!
        assertEquals(at(18), slot.time)
        assertEquals(listOf(ReminderKind.WORKOUT, ReminderKind.WATER), slot.kinds.toList())
        // Motivação: uma hora antes do fim da janela (21h → 20h), junto com a água das 20h.
        assertEquals(setOf(ReminderKind.WATER, ReminderKind.MOTIVATION), ReminderPlanner.due(s, at(20)))
        assertTrue(ReminderPlanner.due(s, at(19)).isEmpty())
    }

    @Test fun `nunca notifica fora da janela nem com alarme muito atrasado`() {
        val ctx = waterCtx(at(20), consumed = 500)
        assertNotNull(ReminderPlanner.messageAt(water2h, at(20), ctx))
        assertNull(ReminderPlanner.messageAt(water2h, at(20), ctx.copy(now = at(21, 30))), "21h30 já está fora da janela 8h–21h")
        assertNull(ReminderPlanner.messageAt(water2h, at(10), ctx.copy(now = at(12))), "chegou 2 h atrasado")
        assertNull(ReminderPlanner.messageAt(water2h, at(20), ctx.copy(now = at(7, date = monday.plusDays(1)))))
        assertNull(ReminderPlanner.messageAt(water2h, at(11), ctx.copy(now = at(11))), "11h não é horário de água a cada 2 h")
        // Janela que acaba mais cedo corta a noite.
        val short = water2h.copy(windowEnd = 19)
        assertTrue(ReminderPlanner.slots(short, monday).all { it.time.hour <= 19 })
    }

    // ---------------------------------------------------------------------------------------
    // Água abaixo/acima do ritmo
    // ---------------------------------------------------------------------------------------

    @Test fun `agua abaixo do ritmo avisa com os numeros`() {
        // 8h–21h, meta 3.000 ml: às 14h o ritmo esperado é 3.000 × 6/13 ≈ 1.384 ml.
        assertEquals(1384, ReminderPlanner.expectedWaterMl(water2h, 3000, LocalTime.of(14, 0)))
        val msg = ReminderPlanner.messageAt(water2h, at(14), waterCtx(at(14), consumed = 1250))
        assertNotNull(msg)
        assertEquals(ReminderKind.WATER, msg.kind)
        assertTrue(msg.text.startsWith("💧 1.250 de 3.000 ml até agora. "), msg.text)
        assertEquals("💧 1.250 de 3.000 ml até agora. Um copo agora te deixa no ritmo!",
            ReminderPlanner.water(water2h, waterCtx(LocalDate.of(2026, 1, 2).atTime(14, 0), 1250)))
        // Nada registrado ainda: mensagem de começo, sem culpa.
        val none = ReminderPlanner.water(water2h, waterCtx(at(10), consumed = 0))!!
        assertTrue(none.contains("meta: 3.000 ml"), none)
    }

    @Test fun `agua no ritmo ou com meta batida fica em silencio`() {
        assertNull(ReminderPlanner.messageAt(water2h, at(10), waterCtx(at(10), consumed = 1250)), "às 10h o ritmo é ~461 ml")
        assertNull(ReminderPlanner.messageAt(water2h, at(20), waterCtx(at(20), consumed = 3000)))
        assertNull(ReminderPlanner.messageAt(water2h, at(20), waterCtx(at(20), consumed = 3200)))
        assertNull(ReminderPlanner.water(water2h, ReminderContext(at(14), waterTargetMl = null)), "sem meta, sem aviso")
    }

    @Test fun `frases variam de forma deterministica`() {
        val texts = (8..21).map { h -> ReminderPlanner.water(water2h, waterCtx(at(h), 10)) }
        assertTrue(texts.filterNotNull().toSet().size > 1, "a frase deveria variar ao longo do dia")
        val a = ReminderPlanner.water(water2h, waterCtx(at(16), 500))
        assertEquals(a, ReminderPlanner.water(water2h, waterCtx(at(16), 500)))
        val days = (0L..3L).map { d -> ReminderPlanner.water(water2h, waterCtx(at(16).plusDays(d), 500)) }.toSet()
        assertTrue(days.size > 1, "a frase deveria variar de um dia para o outro")
    }

    // ---------------------------------------------------------------------------------------
    // Treino do dia, descanso, treino feito — com o app de verdade (TestEnv)
    // ---------------------------------------------------------------------------------------

    private val workout18 = ReminderSettings(workoutEnabled = true, workoutHour = 18)

    private fun envWithProgram(): TestEnv = TestEnv().also { it.app.submit(TestEnv.answers(kb = it.kb)) }

    @Test fun `dia de treino ainda nao feito avisa com nome tempo e sequencia`() {
        val env = envWithProgram()
        // Quatro dias seguidos com registro antes de hoje (segunda): sequência de 4.
        for (d in 4L downTo 1L) { env.at(TestEnv.MONDAY_9H.minusDays(d)); env.app.repo.addWater(250, env.clock.now) }
        env.at(at(18))
        Reminders.save(env.app, workout18)
        val ctx = Reminders.context(env.app)
        assertEquals(WorkoutDay.PENDING, ctx.workoutDay)
        assertEquals(4, ctx.streak)
        val today = env.app.todayView()!!
        val msg = assertNotNull(Reminders.messageAt(env.app, at(18)))
        assertEquals(ReminderKind.WORKOUT, msg.kind)
        assertTrue(msg.text.startsWith("🏋️ Hoje tem ${today.title} (~${today.session!!.estimatedMinutes} min). "), msg.text)
        assertTrue(msg.text.contains("sequência") && msg.text.contains("4 dias"), msg.text)
        assertEquals(
            "🏋️ Hoje tem Superiores A (~45 min). Bora manter a sequência de 4 dias?",
            ReminderPlanner.workout(workout18, ReminderContext(LocalDate.of(2026, 1, 4).atTime(18, 0), workoutDay = WorkoutDay.PENDING,
                sessionTitle = "Superiores A", sessionMinutes = 45, streak = 4))!!.second,
        )
    }

    @Test fun `treino feito nao gera lembrete`() {
        val env = envWithProgram()
        env.at(at(10))
        val t = env.app.todayView()!!
        val w = env.app.startWorkout(t.session!!, t.sessionId, null)
        env.at(at(10, 30))
        // Treino aberto: lembrete de retomar, não de começar.
        assertEquals(WorkoutDay.IN_PROGRESS, Reminders.context(env.app).workoutDay)
        env.app.finishWorkout(w.id, Perceived.ADEQUATE)
        env.at(at(18))
        Reminders.save(env.app, workout18.copy(motivationEnabled = true))
        assertEquals(WorkoutDay.DONE, Reminders.context(env.app).workoutDay)
        assertNull(Reminders.messageAt(env.app, at(18)))
    }

    @Test fun `treino em andamento lembra de retomar`() {
        val msg = ReminderPlanner.workout(workout18, ReminderContext(at(18), workoutDay = WorkoutDay.IN_PROGRESS, sessionTitle = "Inferiores A"))!!
        assertTrue(msg.second.contains("Inferiores A") && msg.second.contains("retomar"), msg.second)
    }

    @Test fun `dia de descanso so manda mensagem leve se a motivacao estiver ligada`() {
        val env = envWithProgram()
        env.at(at(18, date = monday.plusDays(1))) // terça: descanso no seg/qua/sex
        Reminders.save(env.app, workout18)
        assertEquals(WorkoutDay.REST, Reminders.context(env.app).workoutDay)
        assertNull(Reminders.messageAt(env.app, at(18, date = monday.plusDays(1))))
        Reminders.save(env.app, workout18.copy(motivationEnabled = true))
        val msg = assertNotNull(Reminders.messageAt(env.app, at(18, date = monday.plusDays(1))))
        assertTrue(msg.text.contains("descanso", ignoreCase = true), msg.text)
    }

    @Test fun `sem perfil ou sem programa nao ha lembrete de treino`() {
        val env = TestEnv()
        env.at(at(18))
        assertFalse(Reminders.context(env.app).hasProfile)
        assertNull(ReminderPlanner.messageAt(workout18, at(18), Reminders.context(env.app)))
        assertNull(ReminderPlanner.workout(workout18, ReminderContext(at(18), workoutDay = WorkoutDay.NO_PROGRAM)))
    }

    // ---------------------------------------------------------------------------------------
    // Motivação e sequência
    // ---------------------------------------------------------------------------------------

    @Test fun `motivacao protege a sequencia e comemora marcos`() {
        val base = ReminderContext(at(20))
        assertTrue(ReminderPlanner.motivation(base.copy(streak = 3))!!.second.contains("sequência está em 3 dias"))
        assertTrue(ReminderPlanner.motivation(base.copy(streak = 1))!!.second.contains("2 dias"))
        assertNotNull(ReminderPlanner.motivation(base.copy(streak = 0)))
        assertTrue(ReminderPlanner.motivation(base.copy(streak = 7, activeToday = true))!!.second.contains("7 dias seguidos"))
        assertNull(ReminderPlanner.motivation(base.copy(streak = 5, activeToday = true)), "dia garantido e sem marco: silêncio")
    }

    @Test fun `agua e motivacao no mesmo horario viram uma notificacao`() {
        val s = water2h.copy(motivationEnabled = true)
        val ctx = waterCtx(at(20), consumed = 1000).copy(streak = 3)
        val msg = assertNotNull(ReminderPlanner.messageAt(s, at(20), ctx))
        assertEquals(ReminderKind.WATER, msg.kind)
        val lines = msg.text.lines()
        assertEquals(2, lines.size, msg.text)
        assertTrue(lines[0].startsWith("💧 ${Fmt.int(1000)} de"))
        assertTrue(lines[1].startsWith("🔥"))
        // Com o treino no mesmo horário, a motivação sai (o treino já fala da sequência).
        val all = ReminderPlanner.compose(listOf(
            ReminderMessage(ReminderKind.MOTIVATION, "x", "🔥"), ReminderMessage(ReminderKind.WORKOUT, "Treino de hoje", "🏋️"),
        ))!!
        assertEquals(ReminderKind.WORKOUT, all.kind)
        assertEquals("🏋️", all.text)
    }

    @Test fun `lembrete de teste sempre tem texto`() {
        val env = TestEnv()
        assertTrue(Reminders.preview(env.app).text.isNotBlank())
        env.app.submit(TestEnv.answers(kb = env.kb))
        env.at(at(14))
        val p = Reminders.preview(env.app)
        assertTrue(p.text.isNotBlank())
        assertEquals(ReminderKind.WORKOUT, p.kind, "segunda é dia de treino: o teste mostra o lembrete do treino")
    }
}
