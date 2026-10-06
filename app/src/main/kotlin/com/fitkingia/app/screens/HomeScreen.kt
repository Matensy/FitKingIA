package com.fitkingia.app.screens

import android.view.Gravity
import android.widget.LinearLayout
import com.fitkingia.app.Screen
import com.fitkingia.app.Tab
import com.fitkingia.app.ui.*
import com.fitkingia.appcore.DayPlan
import com.fitkingia.appcore.DayStatus
import com.fitkingia.appcore.Step
import com.fitkingia.core.model.Fmt
import com.fitkingia.core.model.SleepQuality
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.program.pt
import com.fitkingia.core.recovery.ReadinessBand
import com.fitkingia.core.recovery.ReadinessCheck
import com.fitkingia.core.safety.ScreeningStatus

/** "Hoje": treino do dia (já ajustado), atalhos de ajuste por toque, água, sono e semana. */
class HomeScreen(
    /** Resultado da última reorganização da semana (troca de treino), mostrado até o usuário dispensar. */
    private var notice: com.fitkingia.appcore.ReorderResult? = null,
) : Screen() {
    override val title = "Hoje"
    override val tab = Tab.HOME
    private var sleepHours: Double? = null

    override fun build(root: LinearLayout) {
        val today = fit.clock.now().toLocalDate()
        val xp = fit.xpStatus()
        root.muted(Dates.long(today), 14f)
        root.row(bottom = 14) {
            badge("🔥 ${fit.streak()} dia(s)", C.warning, bottom = 0)
            val lvl = badge("Nível ${xp.level} · ${xp.xpIntoLevel}/${xp.xpIntoLevel + xp.xpForNext} XP", C.ai, bottom = 0)
            (lvl.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(8)
        }

        val view = fit.todayView()
        if (view == null) {
            noProgram(root)
        } else {
            view.unfinished?.let { w ->
                root.card(stroke = C.accent) {
                    h3("Treino em andamento")
                    muted("${w.plan?.name ?: "Treino"} · começou às ${Dates.time(w.startedAt)}")
                    button("Retomar treino") { fit.activeWorkout()?.let { push(WorkoutScreen(it)) } }
                }
            }
            for (missed in view.pendingMissed) {
                root.card(stroke = C.warning, onClick = { push(MissedScreen(missed)) }) {
                    h3("Treino de ${missed.day.pt()} não realizado")
                    muted("${missed.session?.name}. Sem problema — escolha como seguir a semana.")
                    text("Ver opções →", 14f, C.accent, bold = true, bottom = 2)
                }
            }
            if (fit.screening()?.status == ScreeningStatus.CAUTION) root.card(stroke = C.warning, onClick = { push(ProfileScreen()) }) {
                text("⚠️ Liberado com cautela — toque para ver os cuidados", 14f, C.warning, bottom = 2)
            }
            notice?.let { n -> root.reorderNotice(n) { notice = null; refresh() } }
            todayCard(root, view)
            if (fit.showPriorityHint()) priorityHint(root)
        }
        waterCard(root)
        sleepCard(root)
        fit.week()?.let { weekStrip(root, it.days) }
    }

    /** Novidade para quem já tinha programa: escolher uma região para priorizar (ex.: glúteos). */
    private fun priorityHint(root: LinearLayout) = root.card(stroke = C.accent) {
        h3("🍑 Quer focar numa parte do corpo?")
        muted("Agora o motor monta o treino com prioridade: glúteos, pernas, costas, braços… Mais séries e mais dias para a região escolhida, sem largar o resto.", 14f)
        buttonRow(
            Triple("Agora não", Btn.SECONDARY) { fit.dismissPriorityHint(); refresh() },
            Triple("Escolher", Btn.PRIMARY) {
                fit.dismissPriorityHint()
                push(QuestionnaireScreen(fit.currentAnswers(), startAt = Step.PRIORITY))
            },
            bottom = 2,
        )
    }

    private fun noProgram(root: LinearLayout) {
        root.card(stroke = C.warning) {
            h3("Sem programa de treino")
            val s = fit.screening()
            if (s != null && !s.allowsProgram) s.messages.take(4).forEach { explanation(it) }
            else body("Responda o questionário para o motor montar seu programa.")
            button("Abrir questionário") { push(QuestionnaireScreen(fit.currentAnswers())) }
        }
    }

    private fun todayCard(root: LinearLayout, v: com.fitkingia.appcore.TodayView) {
        val plan = v.today
        when {
            plan.status == DayStatus.DONE -> root.card(stroke = C.success) {
                h2("✅ Treino de hoje concluído", top = 0)
                muted("Recuperação também é parte do treino. Hidrate-se e durma bem.")
                plan.session?.let { s -> button("Ver sessão", Btn.SECONDARY) { push(SessionScreen(s, plan.sessionId, plan.date)) } }
            }
            v.session != null -> sessionCard(root, v, v.session!!)
            else -> root.card {
                h2("😴 Dia de descanso", top = 0)
                body("Nenhum treino de força planejado para hoje. Caminhada leve ou mobilidade ajudam sem atrapalhar a recuperação.")
                doTodayOptions(this) { r -> notice = r; main.refreshTop(this@HomeScreen) }
                buttonRow(
                    Triple("🧘 Mobilidade", Btn.SECONDARY) { push(MobilityScreen()) },
                    Triple("🚶 Cardio", Btn.SECONDARY) { push(CardioScreen()) },
                )
            }
        }
    }

    private fun sessionCard(root: LinearLayout, v: com.fitkingia.appcore.TodayView, s: PlannedSession) {
        root.card(stroke = C.accent) {
            label("Treino de hoje")
            h2(v.title ?: s.name, top = 0)
            muted("~${s.estimatedMinutes} min · ${s.exercises.size} exercícios" + (v.quickMinutes?.let { " · limite de $it min" } ?: ""))
            v.readiness?.let { r ->
                val color = when (r.band) { ReadinessBand.NORMAL -> C.success; ReadinessBand.REDUCED -> C.warning; else -> C.danger }
                badge("Índice de recuperação ${r.score} · ${r.band.label}", color)
            }
            space(4)
            s.exercises.forEach { e ->
                val p = e.prescription
                row(bottom = 6) {
                    val t = text(e.exercise.name, 15f, bottom = 0)
                    t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text("${e.sets} × ${p.target}", 14f, C.muted, bottom = 0)
                }
            }
            if (v.changes.isNotEmpty()) {
                divider()
                label("Ajustes de hoje")
                v.changes.take(8).forEach { muted("• ${it.description} — ${it.reason}") }
            }
            v.notes.forEach { explanation(it, 13f) }
            space(6)
            button("▶  Começar treino") { start(s, v.sessionId, v.readinessId) }
            buttonRow(
                Triple("⚡ Pouco tempo", Btn.SECONDARY) { quickTime(v.quickMinutes) },
                Triple(if (v.readiness == null) "🙂 Como estou" else "🙂 Refazer", Btn.SECONDARY) { push(ReadinessScreen()) },
            )
            button("🔄  Trocar o treino de hoje", Btn.SECONDARY) { swapTodaySheet { r -> notice = r; main.refreshTop(this@HomeScreen) } }
            buttonRow(
                Triple("📋 Detalhes", Btn.GHOST) { push(SessionScreen(s, v.sessionId, v.date)) },
                Triple("🔬 Por que hoje?", Btn.GHOST) { fit.program()?.let { push(WhyScreen(fit.why.forDay(it.program, v.date.dayOfWeek))) } },
            )
        }
    }

    private fun start(s: PlannedSession, sessionId: Long?, readinessId: Long?) {
        val active = fit.startWorkout(s, sessionId, readinessId)
        push(WorkoutScreen(active))
    }

    private fun quickTime(current: Int?) {
        main.sheet("Quanto tempo você tem hoje?") { close ->
            muted("O motor corta primeiro o que é redundante e mantém os exercícios principais.")
            chips(listOf(20, 25, 30, 35, 40, 45, 50, 60).map { it to "$it min" }, { it == current }) { m ->
                fit.setQuickMinutes(m); close(); refresh()
            }
            button("Tempo normal", Btn.SECONDARY) { fit.setQuickMinutes(null); close(); refresh() }
        }
    }

    private fun waterCard(root: LinearLayout) {
        val w = fit.water() ?: return
        root.card(onClick = { push(WaterScreen()) }) {
            row(bottom = 6) {
                val t = text("💧 Água", 16f, bold = true, bottom = 0)
                t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text("${Fmt.int(w.progress.consumedMl)} / ${Fmt.int(w.target.totalMl)} ml", 14f, C.muted, bottom = 0)
            }
            bar(w.progress.pct / 100.0, if (w.progress.pct >= 100) C.success else C.fact)
            buttonRow(
                Triple("+250 ml", Btn.SECONDARY) { fit.addWater(250); refresh() },
                Triple("+500 ml", Btn.SECONDARY) { fit.addWater(500); refresh() },
                Triple("Desfazer", Btn.GHOST) { fit.undoWater(); refresh() },
            )
        }
    }

    private fun sleepCard(root: LinearLayout) {
        val last = fit.sleep().firstOrNull()
        val night = fit.clock.now().toLocalDate().minusDays(1)
        if (last != null && last.nightOf == night) {
            root.card(onClick = { push(SleepScreen()) }) {
                text("😴 Sono: ${Fmt.num(last.hours)} h · ${SleepScreen.QUALITY[last.quality - 1]}", 15f, bottom = 2)
            }
            return
        }
        root.card {
            h3("😴 Como você dormiu?")
            val h = sleepHours
            if (h == null) {
                chips(SleepScreen.HOURS.map { it to SleepScreen.hoursLabel(it) }, { false }, small = true) { sleepHours = it; refresh() }
            } else {
                muted("${SleepScreen.hoursLabel(h)} — e a qualidade?")
                chips(SleepScreen.QUALITY.mapIndexed { i, q -> (i + 1) to q }, { false }, small = true) { q ->
                    fit.logSleep(h, q); sleepHours = null; refresh()
                }
            }
        }
    }

    private fun weekStrip(root: LinearLayout, days: List<DayPlan>) {
        root.card(onClick = { main.switchTab(Tab.WEEK) }) {
            label("Semana")
            row(bottom = 4) {
                days.forEach { d ->
                    val cell = column(bottom = 0) {
                        setGravity(Gravity.CENTER_HORIZONTAL)
                        text(Dates.dayShort(d.date), 12f, C.muted, bottom = 2, gravity = Gravity.CENTER)
                        text(statusIcon(d.status, d.session != null), 16f, bottom = 0, gravity = Gravity.CENTER)
                    }
                    cell.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
            }
        }
    }

    companion object {
        fun statusIcon(s: DayStatus, hasSession: Boolean): String = when (s) {
            DayStatus.DONE -> "✅"
            DayStatus.MISSED -> "⚠️"
            DayStatus.MISSED_RESOLVED -> "↪️"
            DayStatus.TODAY -> "🔥"
            DayStatus.PLANNED -> "🏋️"
            DayStatus.REST -> if (hasSession) "🏋️" else "·"
        }
    }
}

/** Check-in "Como você está hoje?" — tudo por toque. */
class ReadinessScreen : Screen() {
    override val title = "Como você está hoje?"
    private var sleep: SleepQuality? = null
    private var energy: Int? = null
    private var soreness: Int? = null
    private var stress: Int? = null
    private var motivation: Int? = null

    override fun build(root: LinearLayout) {
        root.muted("Suas respostas ajustam o treino de hoje: volume, distância da falha (RIR), descanso e exercícios mais exigentes.", 14f)
        root.label("Sono")
        root.chips(SleepQuality.values().map { it to it.label }, { it == sleep }) { sleep = it; refresh() }
        scale(root, "Energia", "1 = sem energia · 10 = máxima", 1..10, energy) { energy = it }
        scale(root, "Dor muscular", "0 = nenhuma · 10 = muita", 0..10, soreness) { soreness = it }
        scale(root, "Estresse", "1 = tranquilo · 10 = muito estressado", 1..10, stress) { stress = it }
        scale(root, "Motivação", "1 = nenhuma · 10 = total", 1..10, motivation) { motivation = it }
        val ready = sleep != null && energy != null && soreness != null && stress != null && motivation != null
        root.button("Ajustar meu treino", enabled = ready) {
            val r = fit.checkIn(ReadinessCheck(sleep!!, energy!!, soreness!!, stress!!, motivation!!))
            main.toast("Índice de recuperação ${r.score} — ${r.band.label}")
            pop()
        }
        if (fit.todayView()?.readiness != null) root.button("Apagar check-in de hoje", Btn.GHOST) { fit.clearCheckIn(); pop() }
        root.muted("O Índice de recuperação é uma heurística do app para comparar seus próprios dias — não é medida clínica.")
    }

    private fun scale(root: LinearLayout, title: String, hint: String, range: IntRange, value: Int?, set: (Int) -> Unit) {
        root.label(title)
        root.muted(hint, 12f)
        root.chips(range.map { it to it.toString() }, { it == value }, small = true) { set(it); refresh() }
    }
}
