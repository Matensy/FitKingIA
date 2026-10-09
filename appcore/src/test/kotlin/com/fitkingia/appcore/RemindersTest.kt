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

    // Achado 1: o horário marcado no fim da janela (treino às 20h com janela até 20h, água de 1 em 1 h às
    // 21h…) se perdia se o alarme inexato chegasse 1 minuto atrasado.
    @Test fun `horario no fim da janela ainda avisa se o alarme chegar uns minutos atrasado`() {
        val workout20 = ReminderSettings(workoutEnabled = true, workoutHour = 20, windowEnd = 20)
        val pending = ReminderContext(at(20), workoutDay = WorkoutDay.PENDING, sessionTitle = "Treino A", sessionMinutes = 50)
        assertEquals(listOf(at(20)), ReminderPlanner.slots(workout20, monday).map { it.time })
        for (late in listOf(0L, 1L, 5L, ReminderPlanner.LATE_GRACE_MINUTES)) {
            assertNotNull(ReminderPlanner.messageAt(workout20, at(20), pending.copy(now = at(20).plusMinutes(late))), "chegou $late min depois")
        }
        assertNull(ReminderPlanner.messageAt(workout20, at(20), pending.copy(now = at(20).plusMinutes(ReminderPlanner.LATE_GRACE_MINUTES + 1))),
            "passou da tolerância: já é noite fora da janela")
        // Janela até 19h com o treino às 20h: normalized() leva o treino para 19h, o próprio fim.
        val moved = ReminderSettings(workoutEnabled = true, workoutHour = 20, windowEnd = 19)
        assertEquals(listOf(at(19)), ReminderPlanner.slots(moved, monday).map { it.time })
        assertNotNull(ReminderPlanner.messageAt(moved, at(19), pending.copy(now = at(19, 2))))
        // Água de 1 em 1 h (8h–21h): o aviso das 21h chegando 21h04.
        val hourly = water2h.copy(waterEveryHours = 1)
        assertNotNull(ReminderPlanner.messageAt(hourly, at(21), waterCtx(at(21, 4), consumed = 500)))
        // Água de 3 em 3 h com o último horário no fim (9h–21h → 12, 15, 18, 21).
        val every3 = water2h.copy(waterEveryHours = 3, windowStart = 9, windowEnd = 21)
        assertEquals(21, ReminderPlanner.slots(every3, monday).last().time.hour)
        assertNotNull(ReminderPlanner.messageAt(every3, at(21), waterCtx(at(21, 3), consumed = 500)))
        // Nunca antes do início da janela (relógio mudou, por exemplo).
        assertNull(ReminderPlanner.messageAt(water2h, at(10), waterCtx(at(7, 59), consumed = 0)))
        assertFalse(ReminderPlanner.arrivedInWindow(water2h, at(10), at(7, 59)))
        assertTrue(ReminderPlanner.arrivedInWindow(water2h, at(21), at(21, 20)))
    }

    // Achado 1 (Android 8–11): o alarme inexato pode atrasar 75% da antecedência, sem teto. O primeiro aviso
    // do dia, armado na noite anterior, chegava horas depois e era descartado. Agora, longe do horário, o
    // alarme é uma passagem que só reagenda, e o aviso em si é armado com antecedência curta.
    @Test fun `alarme longe do horario vira passagens e o aviso chega no maximo 15 min depois`() {
        val slot = at(10, date = monday.plusDays(1))
        val direct = java.time.Duration.ofMinutes(ReminderPlanner.DIRECT_LEAD_MINUTES)
        // Perto do horário: direto nele.
        assertEquals(slot, ReminderPlanner.alarmAt(slot.minus(direct), slot))
        assertEquals(slot, ReminderPlanner.alarmAt(slot.minusMinutes(1), slot))
        // Longe: 4/7 da antecedência (mesmo com o atraso máximo de 75%, chega até o horário).
        assertEquals(at(9, 34, date = monday).plusSeconds(17), ReminderPlanner.alarmAt(at(9), at(10)))
        // Sem passagem, 13 h de antecedência deixavam o aviso chegar até ~9 h 45 depois: descartado.
        val late = slot.plusMinutes((13 * 60 * ReminderPlanner.INEXACT_WINDOW_FRACTION).toLong())
        assertNull(ReminderPlanner.messageAt(water2h, slot, waterCtx(late, consumed = 0)))

        // Simula a corrente com o Android atrasando cada alarme em 0%, 25%, 50%, 75% da antecedência (e
        // alternando): passagens nunca chegam depois do horário e o aviso chega no máximo 15 min atrasado.
        val patterns = listOf(listOf(0.0), listOf(0.75), listOf(0.25), listOf(0.5), listOf(0.0, 0.75), listOf(0.75, 0.0, 0.5))
        for (start in listOf(at(21), at(20, 1), at(9), at(10, 30))) for (p in patterns) {
            val target = ReminderPlanner.next(water2h, start)!!.time
            var now = start
            var hops = 0
            while (true) {
                val armedFor = ReminderPlanner.alarmAt(now, target)
                val lead = java.time.Duration.between(now, armedFor).seconds
                val arrival = armedFor.plusSeconds((lead * p[hops % p.size]).toLong())
                if (!ReminderPlanner.isRelay(target, arrival)) {
                    val delay = java.time.Duration.between(target, arrival).toMinutes()
                    assertTrue(delay <= 15, "de $start com $p: aviso chegou $delay min depois")
                    break
                }
                assertTrue(armedFor.isBefore(target), "passagem é armada antes do horário")
                now = arrival
                hops++
                assertTrue(hops <= 6, "de $start com $p: passagens demais")
            }
        }
        // A passagem não avisa nada (chegou antes do horário).
        assertTrue(ReminderPlanner.isRelay(at(10), at(9, 40)))
        assertNull(ReminderPlanner.messageAt(water2h, at(10), waterCtx(at(9, 40), consumed = 0)))
        assertFalse(ReminderPlanner.isRelay(at(10), at(10)))
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
        // Quatro dias seguidos com registro antes de hoje (segunda) + o peso do questionário de hoje: sequência de 5.
        for (d in 4L downTo 1L) { env.at(TestEnv.MONDAY_9H.minusDays(d)); env.app.repo.addWater(250, env.clock.now) }
        env.at(at(18))
        Reminders.save(env.app, workout18)
        val ctx = Reminders.context(env.app)
        assertEquals(WorkoutDay.PENDING, ctx.workoutDay)
        assertEquals(5, ctx.streak)
        val today = env.app.todayView()!!
        val msg = assertNotNull(Reminders.messageAt(env.app, at(18)))
        assertEquals(ReminderKind.WORKOUT, msg.kind)
        assertTrue(msg.text.startsWith("🏋️ Hoje tem ${today.title} (~${today.session!!.estimatedMinutes} min). "), msg.text)
        assertTrue(msg.text.contains("sequência") && msg.text.contains("5 dias"), msg.text)
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
        assertEquals(setOf(ReminderKind.WATER, ReminderKind.MOTIVATION), msg.kinds)
        // Com o treino pendente no mesmo horário, a motivação sai (o treino já chama para a sequência).
        val all = ReminderPlanner.compose(listOf(
            ReminderMessage(ReminderKind.MOTIVATION, "x", "🔥"), ReminderMessage(ReminderKind.WORKOUT, "Treino de hoje", "🏋️", coversStreak = true),
        ))!!
        assertEquals(ReminderKind.WORKOUT, all.kind)
        assertEquals("🏋️", all.text)
        assertEquals(setOf(ReminderKind.WORKOUT), all.kinds)
    }

    // Achado 4: o recado da sequência sumia quando caía junto com o treino num dia de descanso ou com
    // o treino aberto; e com só a motivação ligada a nota de descanso prometida nunca chegava.
    private val at20 = ReminderSettings(workoutEnabled = true, workoutHour = 20, motivationEnabled = true)

    @Test fun `descanso e motivacao no mesmo horario mantem o recado da sequencia`() {
        assertEquals(setOf(ReminderKind.WORKOUT, ReminderKind.MOTIVATION), ReminderPlanner.due(at20, at(20)))
        val msg = assertNotNull(ReminderPlanner.messageAt(at20, at(20), ReminderContext(at(20), workoutDay = WorkoutDay.REST, streak = 5)))
        assertEquals("Dia de descanso", msg.title)
        val lines = msg.text.lines()
        assertEquals(2, lines.size, msg.text)
        assertTrue(lines[0].contains("descanso", ignoreCase = true), msg.text)
        assertTrue(lines[1].contains("Sua sequência está em 5 dias"), msg.text)
        assertEquals(setOf(ReminderKind.WORKOUT, ReminderKind.MOTIVATION), msg.kinds)
    }

    @Test fun `treino aberto e motivacao no mesmo horario mantem o recado da sequencia`() {
        val ctx = ReminderContext(at(20), workoutDay = WorkoutDay.IN_PROGRESS, sessionTitle = "Inferiores A", streak = 5)
        val msg = assertNotNull(ReminderPlanner.messageAt(at20, at(20), ctx))
        assertEquals(2, msg.text.lines().size, msg.text)
        assertTrue(msg.text.contains("retomar") && msg.text.contains("5 dias"), msg.text)
        // Treino pendente: uma linha só, que já fala da sequência.
        val pending = assertNotNull(ReminderPlanner.messageAt(at20, at(20), ctx.copy(workoutDay = WorkoutDay.PENDING)))
        assertEquals(1, pending.text.lines().size, pending.text)
        assertTrue(pending.text.contains("5 dias"), pending.text)
    }

    @Test fun `so motivacao ligada manda a nota de descanso no horario da motivacao`() {
        val s = ReminderSettings(motivationEnabled = true) // 8h–21h: motivação às 20h
        val rest = ReminderContext(at(20), workoutDay = WorkoutDay.REST, streak = 5)
        val msg = assertNotNull(ReminderPlanner.messageAt(s, at(20), rest))
        assertEquals("Dia de descanso", msg.title)
        assertTrue(msg.text.lines()[0].contains("descanso", ignoreCase = true), msg.text)
        assertTrue(msg.text.contains("5 dias"), msg.text)
        // Dia já garantido (sem marco): a nota de descanso chega mesmo assim, sozinha.
        val calm = assertNotNull(ReminderPlanner.messageAt(s, at(20), rest.copy(activeToday = true)))
        assertEquals(1, calm.text.lines().size, calm.text)
        assertTrue(calm.text.contains("descanso", ignoreCase = true), calm.text)
        // Dia de treino: só o recado da sequência (sem lembrete de treino ligado, nada de "Hoje tem").
        val training = assertNotNull(ReminderPlanner.messageAt(s, at(20), rest.copy(workoutDay = WorkoutDay.PENDING, sessionTitle = "Treino A")))
        assertEquals(ReminderKind.MOTIVATION, training.kind)
        assertFalse(training.text.contains("Hoje tem"), training.text)
    }

    // Achado 5: a notificação promete "qualquer registro", mas sono, peso/medidas e fotos não contavam.
    @Test fun `sono peso e foto contam para a sequencia`() {
        val env = envWithProgram()                       // segunda: o questionário já registra o peso
        env.at(TestEnv.MONDAY_9H.plusDays(1))             // terça
        env.app.addWater(250)
        env.at(TestEnv.MONDAY_9H.plusDays(2).plusHours(11)) // quarta, 20h: só o sono da noite passada
        env.app.logSleep(7.5, 4)
        assertTrue(Reminders.context(env.app).activeToday, "o sono registrado hoje conta para hoje")
        assertEquals(3, env.app.streak())
        env.at(TestEnv.MONDAY_9H.plusDays(3))             // quinta: só o peso
        env.app.logBody(80.0, null)
        assertEquals(4, env.app.streak())
        env.at(TestEnv.MONDAY_9H.plusDays(4))             // sexta: só uma foto
        env.app.addPhoto("front", "/tmp/foto.jpg")
        assertEquals(5, env.app.streak())
        assertNull(ReminderPlanner.motivation(Reminders.context(env.app)), "dia garantido (5 não é marco): sem recado")
    }

    // Achado 6: notificações velhas ficavam na barra ("Hoje tem…" depois do treino feito, no dia seguinte,
    // depois de apagar os dados).
    @Test fun `notificacao sai da barra quando o motivo acaba`() {
        val s = water2h.copy(workoutEnabled = true, motivationEnabled = true)
        val pending = ReminderContext(at(18), workoutDay = WorkoutDay.PENDING, waterConsumedMl = 500, waterTargetMl = 3000, streak = 2)
        val workout = setOf(ReminderKind.WORKOUT)
        assertTrue(ReminderPlanner.stillRelevant(workout, monday, s, pending))
        assertTrue(ReminderPlanner.stillRelevant(workout, monday, s, pending.copy(workoutDay = WorkoutDay.IN_PROGRESS)))
        assertFalse(ReminderPlanner.stillRelevant(workout, monday, s, pending.copy(workoutDay = WorkoutDay.DONE)), "treino feito")
        assertFalse(ReminderPlanner.stillRelevant(workout, monday.minusDays(1), s, pending), "\"Hoje tem…\" de ontem")
        assertFalse(ReminderPlanner.stillRelevant(workout, monday, s.copy(workoutEnabled = false), pending), "lembrete desligado")
        assertFalse(ReminderPlanner.stillRelevant(workout, monday, s, pending.copy(hasProfile = false)), "dados apagados")
        assertFalse(ReminderPlanner.stillRelevant(workout, null, s, pending))
        assertFalse(ReminderPlanner.stillRelevant(emptySet(), monday, s, pending))
        // Água: vale enquanto estiver abaixo do ritmo; meta batida ou em dia → sai.
        val water = setOf(ReminderKind.WATER)
        assertTrue(ReminderPlanner.stillRelevant(water, monday, s, pending))
        assertFalse(ReminderPlanner.stillRelevant(water, monday, s, pending.copy(waterConsumedMl = 3000)))
        assertFalse(ReminderPlanner.stillRelevant(water, monday, s, pending.copy(waterConsumedMl = 2400)), "às 18h o ritmo é ~2.307 ml")
        // Vale o tipo principal (título e id): "Hoje tem…" + água sai quando o treino é feito, não quando a água fica em dia.
        val workoutAndWater = setOf(ReminderKind.WORKOUT, ReminderKind.WATER)
        assertTrue(ReminderPlanner.stillRelevant(workoutAndWater, monday, s, pending.copy(waterConsumedMl = 3000)))
        assertFalse(ReminderPlanner.stillRelevant(workoutAndWater, monday, s, pending.copy(workoutDay = WorkoutDay.DONE)))
        // Água + sequência: sai quando a água volta ao ritmo; só registrar algo (sequência garantida) não basta.
        val waterAndStreak = setOf(ReminderKind.WATER, ReminderKind.MOTIVATION)
        assertTrue(ReminderPlanner.stillRelevant(waterAndStreak, monday, s, pending.copy(activeToday = true)))
        assertFalse(ReminderPlanner.stillRelevant(waterAndStreak, monday, s, pending.copy(waterConsumedMl = 2400, activeToday = true)))
        // Só a sequência: um registro hoje resolve o recado.
        val streak = setOf(ReminderKind.MOTIVATION)
        assertTrue(ReminderPlanner.stillRelevant(streak, monday, s, pending))
        assertFalse(ReminderPlanner.stillRelevant(streak, monday, s, pending.copy(activeToday = true)))
        // Nota de descanso só com a motivação ligada continua valendo o dia todo.
        val onlyMotivation = ReminderSettings(motivationEnabled = true)
        assertTrue(ReminderPlanner.stillRelevant(workout, monday, onlyMotivation, pending.copy(workoutDay = WorkoutDay.REST, activeToday = true)))
        // Validade na barra: até a meia-noite.
        assertEquals(java.time.Duration.ofHours(6), ReminderPlanner.lifetime(at(18)))
    }

    @Test fun `stillRelevant le o app de verdade`() {
        val env = envWithProgram()
        env.at(at(18))
        Reminders.save(env.app, workout18)
        assertTrue(Reminders.stillRelevant(env.app, setOf(ReminderKind.WORKOUT), monday))
        val t = env.app.todayView()!!
        val w = env.app.startWorkout(t.session!!, t.sessionId, null)
        env.app.finishWorkout(w.id, Perceived.ADEQUATE)
        assertFalse(Reminders.stillRelevant(env.app, setOf(ReminderKind.WORKOUT), monday), "treino concluído")
        // Apagar os dados avisa o app (que cancela o alarme e tira as notificações da barra).
        val deleted = mutableListOf<String>()
        env.app.onEverythingDeleted = { deleted += "avisado" }
        env.app.deleteEverything()
        assertEquals(listOf("avisado"), deleted)
        assertFalse(Reminders.stillRelevant(env.app, setOf(ReminderKind.WATER), monday))
    }

    // Revisão do achado 6: com a motivação ligada, o tipo "treino" de um dia de descanso vira a nota de descanso,
    // então "Hoje tem Treino A" continuava "valendo" depois de o treino de hoje ir para outro dia; e o recado
    // "qualquer registro hoje mantém ela viva" ficava na barra depois do registro que fechou um marco.
    @Test fun `notificacao sai da barra quando o mesmo tipo passa a dizer outra coisa`() {
        val s = ReminderSettings(workoutEnabled = true, workoutHour = 18, motivationEnabled = true)
        val pending = ReminderContext(at(18), workoutDay = WorkoutDay.PENDING, sessionTitle = "Treino A", streak = 6)
        val posted = assertNotNull(ReminderPlanner.messageAt(s, at(18), pending))
        assertEquals("Treino de hoje", posted.title)
        assertTrue(ReminderPlanner.stillRelevant(posted.kinds, monday, s, pending, posted.title))
        assertTrue(ReminderPlanner.stillRelevant(posted.kinds, monday, s, pending.copy(workoutDay = WorkoutDay.IN_PROGRESS), posted.title),
            "treino começado: o lembrete do treino continua valendo")
        val rest = pending.copy(now = at(18, 30), workoutDay = WorkoutDay.REST)
        assertFalse(ReminderPlanner.stillRelevant(posted.kinds, monday, s, rest, posted.title), "hoje virou descanso: \"Hoje tem…\" sai")
        // E o contrário: a nota de descanso sai quando a pessoa decide treinar hoje.
        val restNote = assertNotNull(ReminderPlanner.messageAt(s, at(18), rest.copy(now = at(18))))
        assertEquals("Dia de descanso", restNote.title)
        assertFalse(ReminderPlanner.stillRelevant(restNote.kinds, monday, s, pending.copy(now = at(19)), restNote.title))
        // Sequência: o recado "mantém ela viva" sai quando o registro de hoje fecha o marco de 7 dias.
        val motivation = ReminderSettings(motivationEnabled = true)
        val streak = assertNotNull(ReminderPlanner.messageAt(motivation, at(20), pending.copy(now = at(20))))
        assertEquals(setOf(ReminderKind.MOTIVATION), streak.kinds)
        val milestone = pending.copy(now = at(20, 10), streak = 7, activeToday = true)
        assertNotNull(ReminderPlanner.message(ReminderKind.MOTIVATION, motivation, milestone), "o tipo ainda tem o que dizer (comemoração)")
        assertFalse(ReminderPlanner.stillRelevant(streak.kinds, monday, motivation, milestone, streak.title))
        // A comemoração postada continua valendo.
        val party = assertNotNull(ReminderPlanner.messageAt(motivation, at(20), milestone.copy(now = at(20))))
        assertTrue(ReminderPlanner.stillRelevant(party.kinds, monday, motivation, milestone, party.title))
    }

    @Test fun `treino de hoje trocado para outro dia tira o lembrete do treino`() {
        val env = envWithProgram()
        env.at(at(18))
        val s = ReminderSettings(workoutEnabled = true, workoutHour = 18, motivationEnabled = true)
        Reminders.save(env.app, s)
        val posted = assertNotNull(Reminders.messageAt(env.app, at(18)))
        assertTrue(posted.text.startsWith("🏋️ Hoje tem "), posted.text)
        assertTrue(Reminders.stillRelevant(env.app, posted.kinds, monday, posted.title))
        val rest = env.app.week()!!.days.first { it.session == null && it.date.isAfter(monday) }.date
        env.app.swapDays(monday, rest, permanent = false)
        assertEquals(WorkoutDay.REST, Reminders.context(env.app).workoutDay)
        assertFalse(Reminders.stillRelevant(env.app, posted.kinds, monday, posted.title))
    }

    // Extra 1: os lembretes vêm desligados e só existiam em Mais › Lembretes.
    @Test fun `convite aparece depois do programa e some com a resposta`() {
        val env = TestEnv()
        assertFalse(Reminders.showInvite(env.app), "sem perfil")
        env.app.submit(TestEnv.answers(kb = env.kb))
        assertTrue(Reminders.showInvite(env.app))
        assertFalse(Reminders.settings(env.app).anyEnabled, "mostrar o convite não liga nada")
        Reminders.dismissInvite(env.app)
        assertFalse(Reminders.showInvite(env.app))
        assertFalse(Reminders.settings(env.app).anyEnabled)
    }

    @Test fun `aceitar o convite liga agua e treino e nao pergunta de novo`() {
        val env = envWithProgram()
        val s = assertNotNull(Reminders.acceptInvite(env.app))
        assertTrue(s.waterEnabled && s.workoutEnabled)
        assertFalse(s.motivationEnabled)
        assertEquals(Triple(8, 21, 18), Triple(s.windowStart, s.windowEnd, s.workoutHour))
        assertFalse(Reminders.showInvite(env.app))
        // Desligar tudo depois não traz o convite de volta.
        Reminders.save(env.app, ReminderSettings.DEFAULT)
        assertFalse(Reminders.showInvite(env.app))
        // Sem perfil não há onde guardar.
        assertNull(Reminders.acceptInvite(TestEnv().app))
    }

    @Test fun `quem ja ligou por Mais nao ve o convite e apagar os dados zera a resposta`() {
        val env = envWithProgram()
        Reminders.save(env.app, water2h)
        Reminders.save(env.app, ReminderSettings.DEFAULT)
        assertFalse(Reminders.showInvite(env.app), "a pessoa já conhece os lembretes")
        env.app.deleteEverything()
        assertFalse(Reminders.showInvite(env.app))
        env.app.submit(TestEnv.answers(kb = env.kb))
        assertTrue(Reminders.showInvite(env.app), "novo perfil: pergunta de novo")
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
