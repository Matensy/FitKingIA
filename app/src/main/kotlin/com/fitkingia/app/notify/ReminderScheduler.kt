package com.fitkingia.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.fitkingia.appcore.FitKing
import com.fitkingia.appcore.ReminderPlanner
import com.fitkingia.appcore.ReminderSlot
import com.fitkingia.appcore.Reminders
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Um único alarme pendente: o próximo horário de lembrete (de qualquer tipo). Quando ele chega, o
 * [ReminderReceiver] decide se há algo útil para dizer e agenda o seguinte. Sem lembretes ligados,
 * o alarme é cancelado.
 *
 * Inexato de propósito (setAndAllowWhileIdle, sem SCHEDULE_EXACT_ALARM): o Android agrupa alarmes para
 * poupar bateria e dispara mesmo em soneca. O atraso possível cresce com a antecedência (75% dela no
 * Android 8–11, até 1 h no 12+); por isso, com o horário longe, o alarme é uma passagem que só reagenda
 * mais perto ([ReminderPlanner.alarmAt]). O pendente leva sempre o horário do aviso no extra [EXTRA_SLOT].
 */
object ReminderScheduler {
    const val ACTION_REMINDER = "com.fitkingia.app.action.REMINDER"
    const val EXTRA_SLOT = "slot"
    private const val REQUEST_CODE = 7001

    fun schedule(context: Context, fit: FitKing, after: LocalDateTime = fit.clock.now()): ReminderSlot? {
        val app = context.applicationContext ?: context
        val next = Reminders.next(fit, after)
        if (next == null) {
            cancel(app)
            return null
        }
        arm(app, next.time, fit.clock.now())
        return next
    }

    /**
     * Arma (ou rearma, numa passagem) o alarme do aviso de [slot] estando em [now]: no próprio horário
     * quando ele está perto, numa passagem antes dele quando está longe. Não precisa abrir o banco.
     */
    fun arm(context: Context, slot: LocalDateTime, now: LocalDateTime) {
        val app = context.applicationContext ?: context
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(ReminderPlanner.alarmAt(now, slot)), pendingIntent(app, slot))
    }

    fun cancel(context: Context) {
        val app = context.applicationContext ?: context
        val pi = PendingIntent.getBroadcast(app, REQUEST_CODE, intent(app), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }

    /** Depois de "apagar todos os meus dados": sem alarme pendente e sem lembrete na barra. */
    fun clearAll(context: Context) {
        val app = context.applicationContext ?: context
        cancel(app)
        Notifier.clearAll(app)
    }

    /** Horário local do aparelho → instante (o user.db guarda tudo no fuso local). */
    fun millis(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun intent(context: Context) = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMINDER)

    private fun pendingIntent(context: Context, slot: LocalDateTime): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST_CODE, intent(context).putExtra(EXTRA_SLOT, slot.toString()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
