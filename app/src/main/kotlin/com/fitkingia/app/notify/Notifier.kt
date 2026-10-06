package com.fitkingia.app.notify

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.fitkingia.app.MainActivity
import com.fitkingia.app.ui.C
import com.fitkingia.appcore.ReminderMessage

/**
 * Notificações locais (sem servidor). O app compila contra a API 23, mas roda no Android 8+:
 * canal de notificação e Notification.Builder(Context, String) são da API 26, por isso são
 * criados por reflexão — o checkAndroidApi não enxerga chamadas por nome, e no aparelho elas
 * sempre existem (minSdk 26).
 */
object Notifier {
    const val CHANNEL_ID = "lembretes"

    /** Manifest.permission.POST_NOTIFICATIONS (API 33) — a constante não existe no android.jar da API 23. */
    const val PERMISSION = "android.permission.POST_NOTIFICATIONS"

    /** Build.VERSION_CODES.TIRAMISU: a partir daqui notificação é permissão de tempo de execução. */
    private const val API_33 = 33

    /** NotificationManager.IMPORTANCE_DEFAULT (API 24): som e aparece na barra, sem tela cheia. */
    private const val IMPORTANCE_DEFAULT = 3

    /** Id do lembrete de teste: não substitui um lembrete de verdade que esteja na barra. */
    const val TEST_ID = 9

    private const val TAG = "FitKingIA"

    fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= API_33

    fun hasPermission(context: Context): Boolean =
        !needsRuntimePermission() || context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED

    /** Notificações do app ligadas nas configurações do Android (areNotificationsEnabled é da API 24 → reflexão). */
    fun enabledInSystem(context: Context): Boolean = try {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        NotificationManager::class.java.getMethod("areNotificationsEnabled").invoke(nm) as? Boolean ?: true
    } catch (e: Exception) {
        true
    }

    fun canPost(context: Context): Boolean = hasPermission(context) && enabledInSystem(context)

    /** Cria (ou atualiza) o canal "Lembretes". Idempotente. */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            // new NotificationChannel(id, nome, importância) + setDescription + createNotificationChannel (API 26).
            val cls = Class.forName("android.app.NotificationChannel")
            val channel = cls.getConstructor(String::class.java, CharSequence::class.java, Int::class.javaPrimitiveType)
                .newInstance(CHANNEL_ID, "Lembretes", IMPORTANCE_DEFAULT)
            cls.getMethod("setDescription", String::class.java).invoke(channel, "Água, treino do dia e sequência — só no horário que você escolheu.")
            NotificationManager::class.java.getMethod("createNotificationChannel", cls).invoke(nm, channel)
        } catch (e: Exception) {
            Log.w(TAG, "não foi possível criar o canal de notificação", e)
        }
    }

    /** Posta a notificação; false se o Android não permitir (sem permissão ou notificações desligadas). */
    fun post(context: Context, msg: ReminderMessage): Boolean {
        if (!canPost(context)) return false
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val n = builder(context)
            .setSmallIcon(smallIcon(context))
            .setColor(C.accent)
            .setContentTitle(msg.title)
            .setContentText(msg.text)
            .setStyle(Notification.BigTextStyle().bigText(msg.text))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .setShowWhen(true)
            .build()
        return try {
            nm.notify(msg.notificationId, n)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "notificação recusada pelo sistema", e)
            false
        }
    }

    private fun builder(context: Context): Notification.Builder = try {
        // Notification.Builder(Context, String channelId) — API 26; sem canal o Android 8+ descarta a notificação.
        Notification.Builder::class.java.getConstructor(Context::class.java, String::class.java).newInstance(context, CHANNEL_ID)
    } catch (e: Exception) {
        @Suppress("DEPRECATION")
        Notification.Builder(context)
    }

    /**
     * Ícone branco da barra (res/drawable/ic_notification.xml). O APK é montado sem R.java (aapt2
     * sem --java), então o id é procurado pelo nome; sem ele, um ícone do sistema.
     */
    private fun smallIcon(context: Context): Int {
        val id = context.resources.getIdentifier("ic_notification", "drawable", context.packageName)
        return if (id != 0) id else android.R.drawable.ic_popup_reminder
    }

    /** Tocar na notificação abre o app como o ícone do launcher (retoma a tarefa se já estiver aberta). */
    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /** Tela de notificações do app nas configurações (ação e extra da API 26, por nome). */
    fun settingsIntent(context: Context): Intent =
        Intent("android.settings.APP_NOTIFICATION_SETTINGS")
            .putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
