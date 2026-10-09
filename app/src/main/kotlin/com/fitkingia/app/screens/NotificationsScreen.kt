package com.fitkingia.app.screens

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.fitkingia.app.Screen
import com.fitkingia.app.notify.Notifier
import com.fitkingia.app.notify.ReminderScheduler
import com.fitkingia.app.ui.*
import com.fitkingia.appcore.ReminderSettings
import com.fitkingia.appcore.Reminders

/** Lembretes locais (água, treino do dia, sequência) configurados só por toque. */
class NotificationsScreen : Screen() {
    override val title = "Lembretes"

    /** A pessoa negou a permissão nesta visita: explica e mantém tudo como estava. */
    private var denied = false

    override fun build(root: LinearLayout) {
        val s = Reminders.settings(fit)
        root.muted("Avisos do próprio aparelho, sem internet. O FitKingIA só lembra quando faz sentido — e nunca fora do horário que você escolher.", 14f)
        permissionCard(root, s)

        root.card {
            toggle("💧 Água", "Só avisa se você estiver abaixo do ritmo da sua meta para a hora. Meta batida, silêncio.", s.waterEnabled) {
                change(s.copy(waterEnabled = it), turningOn = it)
            }
            if (s.waterEnabled) {
                label("Conferir a cada")
                chips(ReminderSettings.WATER_INTERVALS.map { it to "$it h" }, { it == s.waterEveryHours }, small = true, bottom = 4) {
                    change(s.copy(waterEveryHours = it))
                }
            }
        }

        root.card {
            toggle("🏋️ Treino do dia", "Só em dia de treino que ainda não foi feito, com o nome do treino e o tempo estimado.", s.workoutEnabled) {
                change(s.copy(workoutEnabled = it), turningOn = it)
            }
            if (s.workoutEnabled) {
                label("Horário do lembrete")
                chips(s.workoutHoursInWindow.map { it to "${it}h" }, { it == s.workoutHour }, small = true, bottom = 4) {
                    change(s.copy(workoutHour = it))
                }
            }
        }

        root.card {
            toggle("🔥 Motivação e sequência", "Um recado perto do fim do dia para manter sua sequência e, no dia de descanso, uma mensagem leve.", s.motivationEnabled) {
                change(s.copy(motivationEnabled = it), turningOn = it)
            }
        }

        root.card {
            h3("🌙 Horário dos lembretes")
            muted("Nada é enviado fora deste horário.")
            label("Começa às")
            chips(ReminderSettings.WINDOW_STARTS.map { it to "${it}h" }, { it == s.windowStart }, small = true) { changeWindow(s, it, s.windowEnd) }
            label("Termina às")
            chips(ReminderSettings.WINDOW_ENDS.map { it to "${it}h" }, { it == s.windowEnd }, small = true, bottom = 4) { changeWindow(s, s.windowStart, it) }
        }

        Reminders.next(fit)?.let { next ->
            val today = fit.clock.now().toLocalDate()
            root.muted("⏰ Próximo horário: ${Dates.relative(next.time.toLocalDate(), today)} às ${Dates.time(next.time)}. Só vira notificação se fizer sentido naquela hora.", 13f)
            root.space(8)
        }
        root.button("Enviar um lembrete de teste agora", Btn.SECONDARY) { sendTest() }
        root.muted("Os horários são aproximados: o Android agrupa alarmes para economizar bateria. Lembretes são incentivos, não orientação médica.", 12f)
    }

    /**
     * Estado real do Android a cada montagem (a MainActivity remonta a tela ao voltar das configurações):
     * permissão negada nesta visita, notificações do app bloqueadas ou só a categoria "Lembretes" desligada.
     */
    private fun permissionCard(root: LinearLayout, s: ReminderSettings) {
        val ctx = main
        // Liberada depois (outro toque, configurações): o aviso de "negada" não vale mais.
        if (Notifier.hasPermission(ctx)) denied = false
        val blocked = s.anyEnabled && !Notifier.canPost(ctx)
        if (!denied && !blocked) return
        // Só a categoria "Lembretes" desligada (o resto das notificações do app liberado): texto e atalho próprios.
        val channelOff = !denied && Notifier.hasPermission(ctx) && Notifier.appNotificationsEnabled(ctx) && Notifier.channelBlocked(ctx)
        root.card(stroke = C.warning) {
            h3(if (denied) "Sem permissão, sem lembretes" else "Notificações bloqueadas no Android")
            body(when {
                denied -> "O Android só deixa o FitKingIA mostrar lembretes com a permissão de notificações. Por isso eles continuam desligados — tudo bem, o app funciona igual."
                channelOff -> "Seus lembretes estão ligados aqui, mas a categoria “Lembretes” do FitKingIA está desligada no Android. Enquanto ela estiver assim, nenhum aviso aparece."
                else -> "Seus lembretes estão ligados aqui, mas o Android não vai mostrá-los enquanto as notificações do FitKingIA estiverem bloqueadas."
            })
            muted(if (channelOff) "Para religar: Configurações › Apps › FitKingIA › Notificações › Lembretes."
                else "Se mudar de ideia: Configurações › Apps › FitKingIA › Notificações.")
            space(6)
            button("Abrir configurações de notificações", Btn.SECONDARY, bottom = 2) { openSettings(channelOff) }
        }
    }

    private fun openSettings(channel: Boolean) {
        val ctx = main
        val intents = listOfNotNull(
            if (channel) Notifier.channelSettingsIntent(ctx) else null,
            Notifier.settingsIntent(ctx),
            Notifier.appDetailsIntent(ctx),
        )
        for (intent in intents) {
            try {
                ctx.startActivity(intent)
                return
            } catch (e: Exception) {
                // tenta a próxima tela de configurações
            }
        }
        main.toast("Abra as configurações do Android › Apps › FitKingIA")
    }

    /** Ao ligar um lembrete, pede a permissão (Android 13+). Negada → nada muda e a tela explica. */
    private fun change(new: ReminderSettings, turningOn: Boolean = false) {
        if (turningOn && !Notifier.hasPermission(main)) {
            main.requestPermission(Notifier.PERMISSION) { granted ->
                denied = !granted
                if (granted) apply(new) else refresh()
            }
            return
        }
        apply(new)
    }

    private fun changeWindow(s: ReminderSettings, start: Int, end: Int) {
        val n = s.copy(windowStart = start, windowEnd = end).normalized()
        if (s.workoutEnabled && n.workoutHour != s.workoutHour) main.toast("Lembrete do treino passou para ${n.workoutHour}h para caber no horário")
        apply(n)
    }

    private fun apply(new: ReminderSettings) {
        Reminders.save(fit, new)
        ReminderScheduler.schedule(main, fit)
        refresh()
    }

    private fun sendTest() {
        if (!Notifier.hasPermission(main)) {
            main.requestPermission(Notifier.PERMISSION) { granted ->
                denied = !granted
                if (granted) postTest() else refresh()
            }
            return
        }
        postTest()
    }

    private fun postTest() {
        val msg = Reminders.preview(fit)
        val sent = Notifier.post(main, msg.copy(title = "Teste · ${msg.title}", notificationId = Notifier.TEST_ID), fit.clock.now())
        main.toast(if (sent) "Lembrete de teste enviado" else "O Android bloqueou as notificações do FitKingIA")
        refresh()
    }
}

/**
 * Convite na tela Hoje: os lembretes vêm desligados e só existiam em Mais › Lembretes. Depois do
 * primeiro programa, um cartão pergunta uma vez. "Ligar" pede a permissão (Android 13+) e só então liga
 * água e treino; "Agora não" (ou permissão negada) guarda a resposta e o cartão não volta.
 */
fun Screen.reminderInvite(root: LinearLayout) {
    if (!Reminders.showInvite(fit)) return
    val s = Reminders.settings(fit)
    root.card(stroke = C.accent) {
        h3("🔔 Quer lembretes de água e do treino?")
        muted("Avisos do próprio aparelho, sem internet: água só quando você estiver abaixo do ritmo da meta e o treino do dia às ${s.workoutHour}h, sempre entre ${s.windowStart}h e ${s.windowEnd}h. Dá para ajustar ou desligar em Mais › Lembretes.", 14f)
        buttonRow(
            Triple("Agora não", Btn.SECONDARY) { Reminders.dismissInvite(fit); refresh() },
            Triple("Ligar", Btn.PRIMARY) { acceptReminderInvite() },
            bottom = 2,
        )
    }
}

private fun Screen.acceptReminderInvite() {
    if (Notifier.hasPermission(main)) { turnOnFromInvite(); return }
    main.requestPermission(Notifier.PERMISSION) { granted ->
        if (granted) turnOnFromInvite()
        else {
            Reminders.dismissInvite(fit)
            main.toast("Sem a permissão de notificações os lembretes ficam desligados. Dá para ligar depois em Mais › Lembretes.")
            refresh()
        }
    }
}

private fun Screen.turnOnFromInvite() {
    Reminders.acceptInvite(fit)
    ReminderScheduler.schedule(main, fit)
    if (Notifier.canPost(main)) {
        main.toast("Lembretes de água e do treino ligados. Ajuste em Mais › Lembretes.")
        refresh()
    } else {
        // Ligados aqui, mas bloqueados no Android: a tela de Lembretes explica e leva às configurações.
        push(NotificationsScreen())
    }
}

/** Linha "título + explicação ........ chave liga/desliga"; a linha inteira é o alvo do toque. */
private fun LinearLayout.toggle(title: String, subtitle: String, on: Boolean, onChange: (Boolean) -> Unit) {
    val r = row(bottom = 6) {
        val col = column(bottom = 0) {
            text(title, 16f, bold = true, bottom = 2)
            muted(subtitle)
        }
        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) }
        addView(switchView(context, on), LinearLayout.LayoutParams(dp(50), dp(30)))
    }
    val radius = dp(10).toFloat()
    r.background = ripple(rounded(Color.TRANSPARENT, radius), radius)
    r.isClickable = true
    r.contentDescription = "$title: ${if (on) "ligado" else "desligado"}"
    r.setOnClickListener { onChange(!on) }
}

/** Chave desenhada com o kit (sem Switch do sistema, que destoa do tema escuro). */
private fun switchView(context: Context, on: Boolean): View {
    val track = FrameLayout(context)
    val h = context.dp(30).toFloat()
    track.background = rounded(if (on) C.accent else C.surface2, h / 2, if (on) null else C.stroke, context.dp(1))
    val knob = View(context)
    knob.background = rounded(if (on) C.onAccent else C.muted, context.dp(12).toFloat())
    val size = context.dp(24)
    track.addView(knob, FrameLayout.LayoutParams(size, size, Gravity.CENTER_VERTICAL or (if (on) Gravity.END else Gravity.START)).apply {
        marginStart = context.dp(3); marginEnd = context.dp(3)
    })
    track.isClickable = false
    track.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    return track
}
