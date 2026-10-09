package com.fitkingia.app

import android.app.AlarmManager
import android.app.AlertDialog
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.fitkingia.app.data.Graph
import com.fitkingia.app.notify.BootReceiver
import com.fitkingia.app.notify.Notifier
import com.fitkingia.app.notify.ReminderReceiver
import com.fitkingia.app.notify.ReminderScheduler
import com.fitkingia.app.screens.HomeScreen
import com.fitkingia.app.screens.NotificationsScreen
import com.fitkingia.appcore.Answers
import com.fitkingia.appcore.AppClock
import com.fitkingia.appcore.FitKing
import com.fitkingia.appcore.Perceived
import com.fitkingia.appcore.Questionnaire
import com.fitkingia.appcore.ReminderKind
import com.fitkingia.appcore.ReminderPlanner
import com.fitkingia.appcore.ReminderSettings
import com.fitkingia.appcore.Reminders
import com.fitkingia.appcore.UserDb
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.*
import com.fitkingia.knowledge.BundledKnowledge
import com.fitkingia.knowledge.KnowledgeDbBuilder
import com.fitkingia.knowledge.sql.JdbcSqlDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.shadows.ShadowAlertDialog
import org.robolectric.shadows.ShadowToast
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime

/**
 * Lembretes no Android de verdade (Robolectric, API 34): permissão de notificação, alarme
 * agendado, receiver que posta a notificação com o texto do ReminderPlanner e reagendamento no boot.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class RemindersUiTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var app: Application
    private var now: LocalDateTime = MONDAY.atTime(9, 0)

    @Before fun setUp() {
        MainActivity.synchronous = true
        com.fitkingia.app.ui.Motion.enabled = false // estados finais imediatos (outros testes ligam as animações)
        Graph.fit = null
        Graph.override = {
            val db = JdbcSqlDatabase.inMemory().also { UserDb.migrate(it, KnowledgeDbBuilder.schema("user.sql")) }
            val kb = BundledKnowledge.load()
            FitKing(kb, db, AppClock { now }).also { it.submit(answers(kb)) }
        }
        app = RuntimeEnvironment.getApplication()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        idle()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        Graph.fit = null
        Graph.override = null
    }

    private val fit: FitKing get() = activity.fit
    private val alarms get() = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val notifications get() = shadowOf(app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun texts(v: View = activity.window.decorView): List<TextView> = when (v) {
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        is TextView -> listOf(v)
        else -> emptyList()
    }

    private fun screenText() = texts().joinToString("\n") { it.text.toString() }

    private fun tap(label: String) {
        val t = texts().firstOrNull { it.text.toString().contains(label) } ?: run { fail("'$label' não está na tela:\n${screenText()}"); return }
        var v: View? = t
        while (v != null && !v.isClickable) v = v.parent as? View
        assertNotNull("'$label' não é clicável", v)
        v!!.performClick()
        idle()
    }

    /** Toca no primeiro chip com texto exatamente igual a [label]. */
    private fun exactChip(label: String) {
        val chip = texts().firstOrNull { it.text.toString() == label && it.isClickable } ?: run { fail("chip '$label' não está na tela:\n${screenText()}"); return }
        chip.performClick()
        idle()
    }

    private fun assertShows(s: String) = assertTrue("esperava '$s' na tela:\n${screenText()}", screenText().contains(s, ignoreCase = true))

    private fun grant() = shadowOf(app).grantPermissions(Notifier.PERMISSION)

    private fun onlyAlarm(): ShadowAlarmManager.ScheduledAlarm {
        val all = alarms.scheduledAlarms
        assertEquals("um único alarme pendente: $all", 1, all.size)
        return all[0]
    }

    /** Horário do aviso que o alarme pendente leva (o alarme em si pode ser uma passagem antes dele). */
    private fun ShadowAlarmManager.ScheduledAlarm.slot(): LocalDateTime =
        LocalDateTime.parse(shadowOf(operation).savedIntent.getStringExtra(ReminderScheduler.EXTRA_SLOT))

    private fun ShadowAlarmManager.ScheduledAlarm.at(): LocalDateTime =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(triggerAtTime), java.time.ZoneId.systemDefault())

    /**
     * O único alarme pendente é o do aviso de [slot], armado agora: no próprio horário se ele está perto,
     * numa passagem antes dele (ReminderPlanner.alarmAt) se está longe. Sempre inexato.
     */
    private fun assertArmed(slot: LocalDateTime) {
        val a = onlyAlarm()
        assertEquals(slot, a.slot())
        assertEquals(ReminderPlanner.alarmAt(now, slot), a.at())
        assertTrue("setAndAllowWhileIdle", a.allowWhileIdle)
    }

    private fun answerPermission(granted: Boolean) {
        val req = shadowOf(activity).lastRequestedPermission
        assertNotNull("a permissão de notificação deveria ter sido pedida", req)
        assertArrayEquals(arrayOf(Notifier.PERMISSION), req.requestedPermissions)
        if (granted) grant()
        activity.onRequestPermissionsResult(req.requestCode, req.requestedPermissions,
            intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED))
        idle()
    }

    private fun Notification.text(): String = extras.getCharSequence(Notification.EXTRA_TEXT).toString()
    private fun Notification.title(): String = extras.getCharSequence(Notification.EXTRA_TITLE).toString()

    @Test fun `ligar lembrete de agua pede permissao e agenda o alarme`() {
        activity.switchTab(Tab.MORE)
        tap("Lembretes")
        assertTrue(activity.current is NotificationsScreen)
        assertTrue("sem lembretes ligados não há alarme", alarms.scheduledAlarms.isEmpty())

        tap("💧 Água")
        assertFalse("só liga depois da permissão", Reminders.settings(fit).waterEnabled)
        answerPermission(granted = true)

        assertTrue(Reminders.settings(fit).waterEnabled)
        val alarm = onlyAlarm()
        assertArmed(MONDAY.atTime(10, 0))
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue("setAndAllowWhileIdle (inexato, dispara em soneca)", alarm.allowWhileIdle)
        assertShows("Próximo horário: hoje às 10:00")

        // Intervalo de 3 h → próximo às 11h. Desligar cancela o alarme.
        tap("3 h")
        assertEquals(3, Reminders.settings(fit).waterEveryHours)
        assertArmed(MONDAY.atTime(11, 0))
        tap("💧 Água")
        assertFalse(Reminders.settings(fit).waterEnabled)
        assertTrue(alarms.scheduledAlarms.isEmpty())
    }

    @Test fun `permissao negada deixa desligado e explica`() {
        activity.push(NotificationsScreen()); idle()
        tap("🏋️ Treino do dia")
        answerPermission(granted = false)
        assertFalse(Reminders.settings(fit).workoutEnabled)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertShows("Sem permissão, sem lembretes")
        assertShows("Abrir configurações de notificações")
    }

    @Test fun `treino do dia com horario e janela pelo toque`() {
        grant()
        activity.push(NotificationsScreen()); idle()
        tap("🏋️ Treino do dia")
        assertTrue(Reminders.settings(fit).workoutEnabled)
        assertArmed(MONDAY.atTime(18, 0))
        // A janela começa às 8h: o treino só oferece horários dentro dela.
        assertFalse(7 in Reminders.settings(fit).workoutHoursInWindow)
        exactChip("6h") // único "6h" na tela: "Começa às"
        assertEquals(6, Reminders.settings(fit).windowStart)
        exactChip("7h") // o primeiro "7h" agora é o do treino (o cartão do treino vem antes da janela)
        assertEquals(7, Reminders.settings(fit).workoutHour)
        assertArmed(MONDAY.plusDays(1).atTime(7, 0))
        // Janela começando às 10h: o treino das 7h vai para o horário permitido mais próximo (12h).
        exactChip("10h")
        assertEquals(12, Reminders.settings(fit).workoutHour)
        assertArmed(MONDAY.atTime(12, 0))
    }

    @Test fun `disparo do alarme posta a notificacao e agenda o proximo`() {
        grant()
        Reminders.save(fit, ReminderSettings(waterEnabled = true, waterEveryHours = 2))
        now = MONDAY.atTime(13, 0)
        fit.addWater(250)
        now = MONDAY.atTime(13, 30)
        ReminderScheduler.schedule(app, fit)
        val alarm = onlyAlarm()
        assertArmed(MONDAY.atTime(14, 0))

        now = MONDAY.atTime(14, 3) // alarme inexato: chega uns minutos depois
        val target = fit.water()!!.target.totalMl
        ReminderReceiver().onReceive(app, shadowOf(alarm.operation).savedIntent)

        val posted = notifications.allNotifications
        assertEquals(1, posted.size)
        val n = posted[0]
        assertEquals("Água", n.title())
        assertTrue(n.text(), n.text().startsWith("💧 250 de ${Fmt.int(target)} ml até agora. "))
        assertEquals(Notifier.CHANNEL_ID, Notification::class.java.getMethod("getChannelId").invoke(n))
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertNotNull("canal criado", NotificationManager::class.java.getMethod("getNotificationChannel", String::class.java).invoke(nm, Notifier.CHANNEL_ID))
        val open = shadowOf(n.contentIntent).savedIntent
        assertEquals(MainActivity::class.java.name, open.component!!.className)
        // Próximo: 16h.
        assertArmed(MONDAY.atTime(16, 0))
    }

    @Test fun `meta batida ou fora da janela nao notifica mas continua agendando`() {
        grant()
        Reminders.save(fit, ReminderSettings(waterEnabled = true, waterEveryHours = 2))
        val intent = Intent(app, ReminderReceiver::class.java).setAction(ReminderScheduler.ACTION_REMINDER)

        now = MONDAY.atTime(20, 0).plusHours(2).plusMinutes(30) // 22h30: alarme das 20h muito atrasado e fora da janela
        ReminderReceiver().onReceive(app, Intent(intent).putExtra(ReminderScheduler.EXTRA_SLOT, MONDAY.atTime(20, 0).toString()))
        assertTrue(notifications.allNotifications.isEmpty())
        assertArmed(MONDAY.plusDays(1).atTime(10, 0))

        now = MONDAY.plusDays(1).atTime(9, 0)
        repeat(20) { fit.addWater(250) }
        now = MONDAY.plusDays(1).atTime(10, 0)
        ReminderReceiver().onReceive(app, Intent(intent).putExtra(ReminderScheduler.EXTRA_SLOT, now.toString()))
        assertTrue("meta batida: silêncio", notifications.allNotifications.isEmpty())
        assertArmed(MONDAY.plusDays(1).atTime(12, 0))
    }

    @Test fun `lembrete do treino no dia de treino`() {
        grant()
        Reminders.save(fit, ReminderSettings(workoutEnabled = true, workoutHour = 18))
        now = MONDAY.atTime(18, 0)
        val title = fit.todayView()!!.title!!
        ReminderReceiver().onReceive(app, Intent(app, ReminderReceiver::class.java).setAction(ReminderScheduler.ACTION_REMINDER)
            .putExtra(ReminderScheduler.EXTRA_SLOT, now.toString()))
        val n = notifications.allNotifications.single()
        assertEquals("Treino de hoje", n.title())
        assertTrue(n.text(), n.text().startsWith("🏋️ Hoje tem $title (~"))
        // Próximo: terça 18h (o conteúdo é decidido na hora: terça é descanso).
        assertArmed(MONDAY.plusDays(1).atTime(18, 0))
    }

    @Test fun `boot e atualizacao do app reagendam`() {
        Reminders.save(fit, ReminderSettings(workoutEnabled = true, workoutHour = 18))
        assertTrue("salvar direto no banco não agenda", alarms.scheduledAlarms.isEmpty())
        BootReceiver().onReceive(app, Intent("com.exemplo.QUALQUER"))
        assertTrue("ação desconhecida é ignorada", alarms.scheduledAlarms.isEmpty())
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertArmed(MONDAY.atTime(18, 0))
        now = MONDAY.atTime(19, 0)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertArmed(MONDAY.plusDays(1).atTime(18, 0))
    }

    @Test fun `lembrete de teste aparece na hora`() {
        grant()
        activity.push(NotificationsScreen()); idle()
        tap("Enviar um lembrete de teste agora")
        val n = notifications.getNotification(Notifier.TEST_ID)
        assertNotNull(n)
        assertTrue(n.title(), n.title().startsWith("Teste · "))
        assertTrue(n.text().isNotBlank())
    }

    // ---------------------------------------------------------------------------------------
    // Regressões da revisão final (lente lembretes)
    // ---------------------------------------------------------------------------------------

    private fun fire(slot: LocalDateTime) = ReminderReceiver().onReceive(app,
        Intent(app, ReminderReceiver::class.java).setAction(ReminderScheduler.ACTION_REMINDER).putExtra(ReminderScheduler.EXTRA_SLOT, slot.toString()))

    private fun assertHidden(s: String) = assertFalse("não esperava '$s' na tela:\n${screenText()}", screenText().contains(s, ignoreCase = true))

    /** Sai do app (ex.: abre as configurações do Android) e volta. */
    private fun leaveAndComeBack() {
        controller.pause().stop(); idle()
        controller.restart().start().resume(); idle()
    }

    private val nm get() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** A pessoa desliga só a categoria "Lembretes" (IMPORTANCE_NONE). NotificationChannel é da API 26 → reflexão. */
    private fun blockChannel() {
        Notifier.ensureChannel(app)
        val cls = Class.forName("android.app.NotificationChannel")
        val ch = NotificationManager::class.java.getMethod("getNotificationChannel", String::class.java).invoke(nm, Notifier.CHANNEL_ID)
        cls.getMethod("setImportance", Int::class.javaPrimitiveType).invoke(ch, 0)
        NotificationManager::class.java.getMethod("createNotificationChannel", cls).invoke(nm, ch)
    }

    // Achado 1
    @Test fun `ultimo horario da janela chegando atrasado ainda notifica`() {
        grant()
        Reminders.save(fit, ReminderSettings(workoutEnabled = true, workoutHour = 20, windowEnd = 20))
        now = MONDAY.atTime(20, 4) // alarme inexato das 20h (= fim da janela) chegou 4 min depois
        fire(MONDAY.atTime(20, 0))
        val n = notifications.allNotifications.single()
        assertEquals("Treino de hoje", n.title())
        assertTrue(n.text(), n.text().startsWith("🏋️ Hoje tem "))
    }

    // Achado 1 (Android 8–11): armado às 9h para as 18h, o alarme inexato podia chegar até 75% de 9 h depois
    // (~0h45 do dia seguinte) e o aviso se perdia. Agora o alarme é sempre inexato, mas longe do horário vira
    // passagens que só reagendam; mesmo com o Android atrasando cada uma ao máximo, o aviso sai em até 15 min.
    @Test fun `horario longe vira passagens inexatas e o aviso chega mesmo com atraso maximo`() {
        grant()
        Reminders.save(fit, ReminderSettings(workoutEnabled = true, workoutHour = 18))
        val slot = MONDAY.atTime(18, 0)
        ReminderScheduler.schedule(app, fit) // 9h
        assertArmed(slot)
        assertTrue("armado antes do horário (passagem)", onlyAlarm().at().isBefore(slot))
        assertTrue("inexato: o Android escolhe quando entregar dentro da janela", onlyAlarm().windowLengthMs != 0L)
        var armedAt = now
        var relays = 0
        while (notifications.allNotifications.isEmpty()) {
            val a = onlyAlarm()
            assertEquals("toda passagem leva o horário do aviso", slot, a.slot())
            val lead = Duration.between(armedAt, a.at())
            // Atraso máximo do inexato: 75% da antecedência (em milissegundos, como o AlarmManager).
            now = a.at().plus(lead.multipliedBy(3).dividedBy(4)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
            ReminderReceiver().onReceive(app, shadowOf(a.operation).savedIntent)
            if (now.isBefore(slot)) {
                relays++
                assertTrue("passagem não notifica", notifications.allNotifications.isEmpty())
                assertTrue("passagens demais: $relays", relays <= 6)
            } else {
                assertTrue("aviso chegou ${Duration.between(slot, now).toMinutes()} min depois", !now.isAfter(slot.plusMinutes(15)))
            }
            armedAt = now
        }
        assertTrue(relays >= 1)
        assertEquals("Treino de hoje", notifications.allNotifications.single().title())
        assertArmed(MONDAY.plusDays(1).atTime(18, 0))
    }

    @Test fun `alarme que chega antes do horario nao avisa nem pula o horario`() {
        grant()
        Reminders.save(fit, ReminderSettings(workoutEnabled = true, workoutHour = 18))
        now = MONDAY.atTime(17, 50) // relógio voltou, ou passagem
        fire(MONDAY.atTime(18, 0))
        assertTrue(notifications.allNotifications.isEmpty())
        assertArmed(MONDAY.atTime(18, 0))
    }

    // Achado 2
    @Test fun `aviso de permissao negada some quando a permissao e liberada depois`() {
        activity.push(NotificationsScreen()); idle()
        tap("💧 Água")
        answerPermission(granted = false)
        assertShows("Sem permissão, sem lembretes")
        // Configurações do Android › permitir › voltar.
        grant()
        leaveAndComeBack()
        assertTrue(activity.current is NotificationsScreen)
        assertHidden("Sem permissão")
        tap("💧 Água")
        assertTrue(Reminders.settings(fit).waterEnabled)
        assertShows("💧 Água")
        assertHidden("Sem permissão")
        assertHidden("continuam desligados")
    }

    @Test fun `aviso negado some no proximo toque mesmo sem sair do app`() {
        activity.push(NotificationsScreen()); idle()
        tap("💧 Água")
        answerPermission(granted = false)
        grant() // liberada por fora, sem passar pelo onRestart
        tap("💧 Água")
        assertTrue(Reminders.settings(fit).waterEnabled)
        assertHidden("Sem permissão")
    }

    @Test fun `aviso de bloqueio some ao voltar das configuracoes`() {
        grant()
        shadowOf(nm).setNotificationsEnabled(false)
        try {
            activity.push(NotificationsScreen()); idle()
            tap("💧 Água")
            assertShows("Notificações bloqueadas no Android")
            shadowOf(nm).setNotificationsEnabled(true)
            leaveAndComeBack()
            assertHidden("Notificações bloqueadas")
        } finally {
            shadowOf(nm).setNotificationsEnabled(true)
        }
    }

    // Achado 3
    @Test fun `categoria lembretes desligada mostra o aviso e o teste nao diz enviado`() {
        grant()
        Reminders.save(fit, ReminderSettings(waterEnabled = true))
        blockChannel()
        assertTrue(Notifier.channelBlocked(app))
        assertFalse(Notifier.canPost(app))
        activity.push(NotificationsScreen()); idle()
        assertShows("Notificações bloqueadas no Android")
        assertShows("categoria “Lembretes”")
        tap("Enviar um lembrete de teste agora")
        assertEquals("O Android bloqueou as notificações do FitKingIA", ShadowToast.getTextOfLatestToast())
        assertNull(notifications.getNotification(Notifier.TEST_ID))
        // O botão leva direto à categoria.
        tap("Abrir configurações de notificações")
        val opened = shadowOf(activity).nextStartedActivity
        assertEquals("android.settings.CHANNEL_NOTIFICATION_SETTINGS", opened.action)
        assertEquals(Notifier.CHANNEL_ID, opened.getStringExtra("android.provider.extra.CHANNEL_ID"))
        // Disparo do alarme com a categoria desligada: nada é postado (e a corrente continua).
        now = MONDAY.atTime(10, 5)
        fire(MONDAY.atTime(10, 0))
        assertTrue(notifications.allNotifications.isEmpty())
        assertArmed(MONDAY.atTime(12, 0))
    }

    // Achado 6
    @Test fun `lembrete do treino sai da barra quando o treino acaba`() {
        grant()
        Reminders.save(fit, ReminderSettings(workoutEnabled = true, workoutHour = 18))
        now = MONDAY.atTime(18, 2)
        fire(MONDAY.atTime(18, 0))
        val id = ReminderKind.WORKOUT.notificationId
        val n = assertNotNullAndGet(notifications.getNotification(id))
        assertEquals("some sozinha à meia-noite", Duration.ofHours(5).plusMinutes(58).toMillis(),
            Notification::class.java.getMethod("getTimeoutAfter").invoke(n))
        assertEquals("WORKOUT", n.extras.getString(Notifier.EXTRA_KINDS))
        assertEquals(MONDAY.toString(), n.extras.getString(Notifier.EXTRA_DAY))
        // Ainda pendente: sair e voltar não tira.
        leaveAndComeBack()
        assertNotNull(notifications.getNotification(id))
        // Treina pelo app e sai: o "Hoje tem…" sai da barra.
        val t = fit.todayView()!!
        val w = fit.startWorkout(t.session!!, t.sessionId, null)
        now = MONDAY.atTime(19, 0)
        fit.finishWorkout(w.id, Perceived.ADEQUATE)
        leaveAndComeBack()
        assertNull("treino concluído", notifications.getNotification(id))
    }

    // Revisão do achado 6: com a motivação ligada, o "Hoje tem…" ficava na barra depois de o treino de hoje ir
    // para outro dia (o tipo "treino" de um dia de descanso vira a nota de descanso e parecia continuar valendo).
    @Test fun `lembrete do treino sai da barra quando o treino de hoje vai para outro dia`() {
        grant()
        Reminders.save(fit, ReminderSettings(workoutEnabled = true, workoutHour = 18, motivationEnabled = true))
        now = MONDAY.atTime(18, 2)
        fire(MONDAY.atTime(18, 0))
        val id = ReminderKind.WORKOUT.notificationId
        assertTrue(assertNotNullAndGet(notifications.getNotification(id)).text().startsWith("🏋️ Hoje tem "))
        val rest = fit.week()!!.days.first { it.session == null && it.date.isAfter(MONDAY) }.date
        fit.swapDays(MONDAY, rest, permanent = false)
        leaveAndComeBack()
        assertNull("hoje virou descanso: \"Hoje tem…\" não vale mais", notifications.getNotification(id))
    }

    @Test fun `lembrete de ontem sai no proximo disparo ou ao abrir o app`() {
        grant()
        Reminders.save(fit, ReminderSettings(workoutEnabled = true, workoutHour = 18, waterEnabled = true))
        now = MONDAY.atTime(18, 0)
        fire(MONDAY.atTime(18, 0))
        assertNotNull(notifications.getNotification(ReminderKind.WORKOUT.notificationId))
        // Terça (descanso), 10h: o aviso de água de terça chega; o "Hoje tem…" de segunda sai.
        now = MONDAY.plusDays(1).atTime(10, 1)
        fire(MONDAY.plusDays(1).atTime(10, 0))
        assertNull(notifications.getNotification(ReminderKind.WORKOUT.notificationId))
        val water = notifications.getNotification(ReminderKind.WATER.notificationId)
        assertNotNull(water)
        // Quarta: ao abrir o app, o de terça também sai.
        now = MONDAY.plusDays(2).atTime(8, 0)
        leaveAndComeBack()
        assertTrue(notifications.allNotifications.isEmpty())
    }

    @Test fun `agua em dia pelo app tira o aviso de agua ao sair`() {
        grant()
        Reminders.save(fit, ReminderSettings(waterEnabled = true, waterEveryHours = 2))
        now = MONDAY.atTime(14, 3)
        fire(MONDAY.atTime(14, 0))
        assertNotNull(notifications.getNotification(ReminderKind.WATER.notificationId))
        // Copos registrados no app até ficar no ritmo esperado para a hora.
        val target = fit.water()!!.target.totalMl
        val expected = ReminderPlanner.expectedWaterMl(Reminders.settings(fit), target, now.toLocalTime())
        while (fit.water()!!.progress.consumedMl < expected) fit.addWater(250)
        leaveAndComeBack()
        assertNull(notifications.getNotification(ReminderKind.WATER.notificationId))
    }

    @Test fun `apagar todos os dados cancela o alarme e tira os lembretes da barra`() {
        grant()
        Reminders.save(fit, ReminderSettings(waterEnabled = true, workoutEnabled = true))
        now = MONDAY.atTime(18, 1)
        fire(MONDAY.atTime(18, 0))
        assertFalse(notifications.allNotifications.isEmpty())
        assertEquals(1, alarms.scheduledAlarms.size)
        activity.switchTab(Tab.MORE)
        tap("Perfil e dados")
        tap("Apagar todos os meus dados")
        val dialog = ShadowAlertDialog.getLatestAlertDialog()
        assertNotNull("pede confirmação", dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertFalse(fit.hasProfile())
        assertTrue("nenhum alarme pendente", alarms.scheduledAlarms.isEmpty())
        assertTrue("nenhum lembrete com nome de treino ou números na barra", notifications.allNotifications.isEmpty())
    }

    // Extra 1: convite visível e opcional na tela Hoje.
    @Test fun `convite na tela hoje liga agua e treino so depois da permissao`() {
        assertTrue(activity.current is HomeScreen)
        assertShows("Quer lembretes de água e do treino?")
        assertFalse("mostrar o convite não liga nada", Reminders.settings(fit).anyEnabled)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        exactChip("Ligar")
        assertFalse("só liga depois da permissão", Reminders.settings(fit).anyEnabled)
        answerPermission(granted = true)
        val s = Reminders.settings(fit)
        assertTrue(s.waterEnabled && s.workoutEnabled)
        assertFalse(s.motivationEnabled)
        assertArmed(MONDAY.atTime(10, 0))
        assertHidden("Quer lembretes")
        assertTrue(ShadowToast.getTextOfLatestToast().startsWith("Lembretes de água e do treino ligados"))
    }

    @Test fun `convite agora nao guarda a resposta e nao liga nada`() {
        assertShows("Quer lembretes de água e do treino?")
        exactChip("Agora não")
        assertNull("nem pede a permissão", shadowOf(activity).lastRequestedPermission)
        assertFalse(Reminders.settings(fit).anyEnabled)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertHidden("Quer lembretes")
        activity.switchTab(Tab.MORE); activity.switchTab(Tab.HOME); idle()
        assertHidden("Quer lembretes")
    }

    @Test fun `convite com permissao negada nao liga nada`() {
        exactChip("Ligar")
        answerPermission(granted = false)
        assertFalse(Reminders.settings(fit).anyEnabled)
        assertTrue(alarms.scheduledAlarms.isEmpty())
        assertHidden("Quer lembretes")
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Mais › Lembretes"))
    }

    @Test fun `convite com notificacoes bloqueadas abre a tela que explica`() {
        grant()
        shadowOf(nm).setNotificationsEnabled(false)
        try {
            exactChip("Ligar")
            assertTrue(Reminders.settings(fit).waterEnabled)
            assertTrue(activity.current is NotificationsScreen)
            assertShows("Notificações bloqueadas no Android")
        } finally {
            shadowOf(nm).setNotificationsEnabled(true)
        }
    }

    private fun <T> assertNotNullAndGet(v: T?): T { assertNotNull(v); return v!! }

    companion object {
        /** Segunda-feira. */
        val MONDAY: java.time.LocalDate = java.time.LocalDate.of(2026, 9, 28)

        fun answers(kb: KnowledgeBase): Answers {
            val a = Answers(consent = true)
            Questionnaire.selectSex(a, Sex.MALE)
            a.primaryGoal = Goal.HYPERTROPHY
            a.experience = ExperienceLevel.YEARS_1_TO_2
            Questionnaire.selectEnvironment(a, kb, EnvironmentId("full_gym"))
            Questionnaire.applyPreset(a, mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.WEDNESDAY to 60, DayOfWeek.FRIDAY to 60))
            a.activity = ActivityLevel.MODERATE
            Questionnaire.safetyQuestions(kb, a).forEach { a.safety[it.id] = false }
            return a
        }
    }
}
