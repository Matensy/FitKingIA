package com.fitkingia.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.fitkingia.app.MainActivity
import com.fitkingia.app.data.Graph
import com.fitkingia.appcore.Reminders
import java.time.LocalDateTime

/**
 * Chegou a hora de um lembrete: carrega o app, pergunta ao ReminderPlanner se há algo útil para
 * dizer agora (água abaixo do ritmo, treino ainda não feito, sequência), notifica e agenda o próximo.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ReminderScheduler.ACTION_REMINDER) return
        val app = context.applicationContext ?: context
        val slotText = intent.getStringExtra(ReminderScheduler.EXTRA_SLOT)
        runAsync(this) {
            val fit = Graph.load(app)
            val now = fit.clock.now()
            val slot = slotText?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() } ?: now
            try {
                Reminders.messageAt(fit, slot)?.let { Notifier.post(app, it) }
            } finally {
                // Mesmo se algo falhar ao montar a mensagem, a corrente de lembretes continua.
                ReminderScheduler.schedule(app, fit, if (slot.isAfter(now)) slot else now)
            }
        }
    }
}

/** Alarmes não sobrevivem a reinício, atualização do app nem troca de hora/fuso: reagenda. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val app = context.applicationContext ?: context
        runAsync(this) { ReminderScheduler.schedule(app, Graph.load(app)) }
    }

    companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
        )
    }
}

/**
 * Abrir o app pode levar alguns segundos (na primeira vez copia o fitness.db): o trabalho sai da
 * thread principal com goAsync(). Nos testes ([MainActivity.synchronous]) roda na hora.
 */
private fun runAsync(receiver: BroadcastReceiver, work: () -> Unit) {
    if (MainActivity.synchronous) { safely(work); return }
    val pending = receiver.goAsync()
    Thread {
        try { safely(work) } finally { pending?.finish() }
    }.start()
}

private fun safely(work: () -> Unit) {
    try {
        work()
    } catch (e: Exception) {
        Log.e("FitKingIA", "falha ao processar lembrete", e)
    }
}
