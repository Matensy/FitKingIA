package com.fitkingia.app

import android.app.AlarmManager
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
import com.fitkingia.app.screens.NotificationsScreen
import com.fitkingia.appcore.Answers
import com.fitkingia.appcore.AppClock
import com.fitkingia.appcore.FitKing
import com.fitkingia.appcore.Questionnaire
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
import java.time.DayOfWeek
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
        assertEquals(ReminderScheduler.millis(MONDAY.atTime(10, 0)), alarm.triggerAtTime)
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertTrue("setAndAllowWhileIdle (inexato, dispara em soneca)", alarm.allowWhileIdle)
        assertShows("Próximo horário: hoje às 10:00")

        // Intervalo de 3 h → próximo às 11h. Desligar cancela o alarme.
        tap("3 h")
        assertEquals(3, Reminders.settings(fit).waterEveryHours)
        assertEquals(ReminderScheduler.millis(MONDAY.atTime(11, 0)), onlyAlarm().triggerAtTime)
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
        assertEquals(ReminderScheduler.millis(MONDAY.atTime(18, 0)), onlyAlarm().triggerAtTime)
        // A janela começa às 8h: o treino só oferece horários dentro dela.
        assertFalse(7 in Reminders.settings(fit).workoutHoursInWindow)
        exactChip("6h") // único "6h" na tela: "Começa às"
        assertEquals(6, Reminders.settings(fit).windowStart)
        exactChip("7h") // o primeiro "7h" agora é o do treino (o cartão do treino vem antes da janela)
        assertEquals(7, Reminders.settings(fit).workoutHour)
        assertEquals(ReminderScheduler.millis(MONDAY.plusDays(1).atTime(7, 0)), onlyAlarm().triggerAtTime)
        // Janela começando às 10h: o treino das 7h vai para o horário permitido mais próximo (12h).
        exactChip("10h")
        assertEquals(12, Reminders.settings(fit).workoutHour)
        assertEquals(ReminderScheduler.millis(MONDAY.atTime(12, 0)), onlyAlarm().triggerAtTime)
    }

    @Test fun `disparo do alarme posta a notificacao e agenda o proximo`() {
        grant()
        Reminders.save(fit, ReminderSettings(waterEnabled = true, waterEveryHours = 2))
        now = MONDAY.atTime(13, 0)
        fit.addWater(250)
        now = MONDAY.atTime(13, 30)
        ReminderScheduler.schedule(app, fit)
        val alarm = onlyAlarm()
        assertEquals(ReminderScheduler.millis(MONDAY.atTime(14, 0)), alarm.triggerAtTime)

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
        assertEquals(ReminderScheduler.millis(MONDAY.atTime(16, 0)), onlyAlarm().triggerAtTime)
    }

    @Test fun `meta batida ou fora da janela nao notifica mas continua agendando`() {
        grant()
        Reminders.save(fit, ReminderSettings(waterEnabled = true, waterEveryHours = 2))
        val intent = Intent(app, ReminderReceiver::class.java).setAction(ReminderScheduler.ACTION_REMINDER)

        now = MONDAY.atTime(20, 0).plusHours(2).plusMinutes(30) // 22h30: alarme das 20h muito atrasado e fora da janela
        ReminderReceiver().onReceive(app, Intent(intent).putExtra(ReminderScheduler.EXTRA_SLOT, MONDAY.atTime(20, 0).toString()))
        assertTrue(notifications.allNotifications.isEmpty())
        assertEquals(ReminderScheduler.millis(MONDAY.plusDays(1).atTime(10, 0)), onlyAlarm().triggerAtTime)

        now = MONDAY.plusDays(1).atTime(9, 0)
        repeat(20) { fit.addWater(250) }
        now = MONDAY.plusDays(1).atTime(10, 0)
        ReminderReceiver().onReceive(app, Intent(intent).putExtra(ReminderScheduler.EXTRA_SLOT, now.toString()))
        assertTrue("meta batida: silêncio", notifications.allNotifications.isEmpty())
        assertEquals(ReminderScheduler.millis(MONDAY.plusDays(1).atTime(12, 0)), onlyAlarm().triggerAtTime)
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
        assertEquals(ReminderScheduler.millis(MONDAY.plusDays(1).atTime(18, 0)), onlyAlarm().triggerAtTime)
    }

    @Test fun `boot e atualizacao do app reagendam`() {
        Reminders.save(fit, ReminderSettings(workoutEnabled = true, workoutHour = 18))
        assertTrue("salvar direto no banco não agenda", alarms.scheduledAlarms.isEmpty())
        BootReceiver().onReceive(app, Intent("com.exemplo.QUALQUER"))
        assertTrue("ação desconhecida é ignorada", alarms.scheduledAlarms.isEmpty())
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(ReminderScheduler.millis(MONDAY.atTime(18, 0)), onlyAlarm().triggerAtTime)
        now = MONDAY.atTime(19, 0)
        BootReceiver().onReceive(app, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertEquals(ReminderScheduler.millis(MONDAY.plusDays(1).atTime(18, 0)), onlyAlarm().triggerAtTime)
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
