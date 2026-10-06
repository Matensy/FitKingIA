package com.fitkingia.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.fitkingia.appcore.FitKing
import com.fitkingia.appcore.ReminderSlot
import com.fitkingia.appcore.Reminders
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Um único alarme pendente: o próximo horário de lembrete (de qualquer tipo). Quando ele chega, o
 * [ReminderReceiver] decide se há algo útil para dizer e agenda o seguinte. Sem lembretes ligados,
 * o alarme é cancelado.
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
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // Inexato de propósito (sem SCHEDULE_EXACT_ALARM): o Android pode atrasar alguns minutos para
        // agrupar alarmes e poupar bateria; setAndAllowWhileIdle ainda dispara com o aparelho em soneca.
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis(next.time), pendingIntent(app, next.time))
        return next
    }

    fun cancel(context: Context) {
        val app = context.applicationContext ?: context
        val pi = PendingIntent.getBroadcast(app, REQUEST_CODE, intent(app), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) ?: return
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi)
        pi.cancel()
    }

    /** Horário local do aparelho → instante (o user.db guarda tudo no fuso local). */
    fun millis(t: LocalDateTime): Long = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun intent(context: Context) = Intent(context, ReminderReceiver::class.java).setAction(ACTION_REMINDER)

    private fun pendingIntent(context: Context, slot: LocalDateTime): PendingIntent =
        PendingIntent.getBroadcast(context, REQUEST_CODE, intent(context).putExtra(EXTRA_SLOT, slot.toString()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}
