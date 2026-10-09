package com.fitkingia.app.screens

import android.view.ViewGroup
import android.widget.LinearLayout
import com.fitkingia.app.Screen
import com.fitkingia.app.Tab
import com.fitkingia.app.ui.*
import com.fitkingia.appcore.DayPlan
import com.fitkingia.appcore.DayStatus
import com.fitkingia.appcore.Displaced
import com.fitkingia.appcore.Perceived
import com.fitkingia.appcore.ReorderResult
import com.fitkingia.appcore.WeekView
import com.fitkingia.appcore.ptWithArticle
import com.fitkingia.core.model.Fmt
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.program.pt
import com.fitkingia.core.program.ptCapitalized
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Um dia — de uma semana que passou (só consulta), desta semana ou da próxima: o treino planejado, o que
 * foi feito e as ações para reorganizar (fazer hoje, trocar com outro dia, opções de treino perdido).
 */
class DayScreen(
    private val date: LocalDate,
    /** Abre já com a lista de dias para trocar. */
    private var swapping: Boolean = false,
    override val tab: Tab? = Tab.WEEK,
    /** Abre com “todas as semanas” marcado (ex.: vindo de um dia que já passou). */
    permanent: Boolean = false,
) : Screen() {
    override val title get() = "${date.dayOfWeek.ptCapitalized()} ${Dates.short(date)}"
    private var permanent = permanent
    private var result: ReorderResult? = null

    override fun build(root: LinearLayout) {
        val week = fit.week(date)
        if (week == null) {
            root.card { body("Ainda não há programa. Responda o questionário para gerar um."); button("Abrir questionário") { push(QuestionnaireScreen(fit.currentAnswers())) } }
            return
        }
        val today = fit.clock.now().toLocalDate()
        val d = week.days.first { it.date == date }
        val s = d.session
        val current = fit.weekStart(today)
        val thisWeek = week.weekStart == current
        val nextWeek = week.weekStart == current.plusWeeks(1)
        val pastWeek = week.weekStart.isBefore(current)
        val todayPlan = week.days.firstOrNull { it.date == today }
        // Treino que passou e foi remarcado: onde ele está agora (pode ser um dia que também passou).
        val movedTo = if (thisWeek && date.isBefore(today)) fit.rescheduledTo(date) else null

        root.row(bottom = 12) {
            badge("${HomeScreen.statusIcon(d.status, s != null)} ${statusLabel(d)}", statusColor(d.status), bottom = 0)
            relative(date, today)?.let { text("   $it", 13f, C.muted, bottom = 0) }
        }
        if (pastWeek) root.muted("Semana que passou: só consulta.", 13f)

        result?.let { r ->
            root.reorderNotice(r, onUndo = undoAction(r) { result = it; refresh() }) { result = null; refresh() }
        }

        if (s == null) {
            root.card {
                if (d.status == DayStatus.DONE) {
                    h2("💪 Treino extra", top = 0)
                    muted("Um treino fora do planejado foi registrado neste dia.")
                } else {
                    h2("😴 Descanso", top = 0)
                    muted("Nenhum treino de força planejado. Caminhada leve ou mobilidade ajudam a recuperar.")
                }
            }
        } else {
            root.card(stroke = statusColor(d.status).takeIf { d.status != DayStatus.PLANNED && d.status != DayStatus.REST }) {
                label(if (date == today) "Treino de hoje" else "Treino")
                h2(s.name, top = 0)
                muted(listOfNotNull("~${s.estimatedMinutes} min", "${s.exercises.size} exercícios", s.budgetMinutes?.let { "tempo do dia $it min" }).joinToString(" · "))
                if (d.status == DayStatus.MISSED_RESOLVED) {
                    val copy = movedTo?.let { m ->
                        "remarcado para ${relative(m.date, today) ?: m.day.pt()}" + when (m.status) {
                            DayStatus.DONE -> " (feito)"
                            DayStatus.MISSED -> " (também não realizado)"
                            else -> ""
                        }
                    }
                    muted(listOfNotNull(d.missedOption?.let { "Opção $it escolhida" }, copy).joinToString(" · ").replaceFirstChar { it.uppercase() })
                }
                // Numa semana que passou, o programa atual pode ser outro: “remarcado” só faz sentido desta semana em diante.
                val baseDay = week.program.program.sessions.firstOrNull { it.key == s.key }?.day
                if (!pastWeek && baseDay != null && baseDay != date.dayOfWeek && d.status != DayStatus.MISSED_RESOLVED) {
                    muted("↪️ Remarcado ${if (thisWeek) "nesta" else "nessa"} semana (no programa, ${baseDay.ptWithArticle()})")
                }
                if (d.status == DayStatus.DONE && d.workouts.none { it.sessionKey == s.key }) {
                    week.days.flatMap { it.workouts }.firstOrNull { it.sessionKey == s.key }?.let { muted("✅ Feito ${ref(it.startedAt.toLocalDate(), today)}") }
                }
            }
        }

        // Ações (semanas que passaram: só consulta)
        val todayDone = todayPlan?.status == DayStatus.DONE && todayPlan.session != null
        val blocking = if (thisWeek) fit.workoutBlockingToday() else null
        val canDoToday = thisWeek && s != null && date != today && d.status != DayStatus.DONE && !todayDone &&
            movedTo?.date != today && movedTo?.status != DayStatus.DONE
        val candidates = if ((thisWeek || nextWeek) && !date.isBefore(today) && d.status != DayStatus.DONE) swapTargets(week, d, today) else emptyList()
        if (date == today && s != null && d.status == DayStatus.TODAY) root.button("▶  Abrir o treino de hoje") { main.switchTab(Tab.HOME) }
        if (canDoToday && blocking != null) {
            root.muted("🔒 Para fazer este treino hoje, conclua ou descarte antes o treino em andamento (${blocking.plan?.name ?: "Treino"}).", 13f)
        } else if (canDoToday) {
            root.button("▶  Fazer este treino hoje", bottom = 4) { doToday() }
            root.displacedHint(fit.doTodayDisplaces(date), bottom = 10)
        } else if (todayDone && d.status == DayStatus.MISSED && thisWeek) {
            root.muted("Você já treinou hoje. Para remarcar este treino, use as opções de treino perdido.", 13f)
        } else if (movedTo?.status == DayStatus.DONE) {
            root.muted("Este treino já foi feito ${ref(movedTo.date, today)}.", 13f)
        }
        if (candidates.isNotEmpty()) {
            root.button(if (swapping) "✕  Fechar a troca" else "🔄  Trocar com outro dia", Btn.SECONDARY) { swapping = !swapping; refresh() }
        }
        if (d.status == DayStatus.MISSED && thisWeek) root.button("Opções de treino perdido (A–D)", Btn.SECONDARY) { push(MissedScreen(d)) }
        if (swapping && candidates.isNotEmpty()) swapSection(root, d, candidates, today, thisWeek, week.weekStart)
        if (thisWeek && candidates.isEmpty() && fit.browsableWeeks().contains(current.plusWeeks(1))) {
            // Dia que passou (ou já feito): a ordem ainda pode mudar a partir da próxima semana.
            root.button("🔄  Mudar a ordem a partir da próxima semana", Btn.GHOST) {
                push(DayScreen(date.plusWeeks(1), swapping = true, tab = tab, permanent = true))
            }
        }

        if (d.workouts.isNotEmpty()) {
            root.h2("O que você fez")
            d.workouts.forEach { w ->
                val sets = fit.setsOf(w.id).filter { !it.warmup }
                root.card(stroke = C.success) {
                    row(bottom = 4) {
                        val t = text(w.plan?.name ?: "Treino", 16f, bold = true, bottom = 0)
                        t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        text("às ${Dates.time(w.startedAt)}", 13f, C.muted, bottom = 0)
                    }
                    val minutes = w.finishedAt?.let { ChronoUnit.MINUTES.between(w.startedAt, it).coerceAtLeast(1) }
                    val volume = sets.sumOf { it.loadKg * it.reps }
                    muted(listOfNotNull(
                        minutes?.let { "$it min" },
                        if (sets.size == 1) "1 série" else "${sets.size} séries",
                        volume.takeIf { it > 0 }?.let { "${Fmt.num(it, 0)} kg levantados" },
                        w.perceived?.let { p -> Perceived.values().firstOrNull { it.name == p }?.label },
                    ).joinToString(" · "))
                    if (sets.isEmpty()) muted("Nenhuma série registrada.")
                    else space(4)
                    sets.groupBy { it.exerciseId }.forEach { (id, list) ->
                        kv(fit.kb.exerciseOrNull(id)?.name ?: id.value, list.joinToString(" · ") { (if (it.loadKg > 0) "${Fmt.num(it.loadKg, 2)}×" else "") + it.reps })
                    }
                }
            }
        }

        if (s != null && s.exercises.isNotEmpty()) {
            root.h2(if (d.workouts.isEmpty()) "Exercícios" else "Treino planejado")
            root.card {
                s.exercises.forEachIndexed { i, e ->
                    if (i > 0) divider()
                    val p = e.prescription
                    val line = row(bottom = 4) {
                        val col = column(bottom = 0) {
                            text("${i + 1}. ${e.exercise.name}", 15f, bold = true, bottom = 2)
                            muted("${e.sets} × ${p.target} · RIR ${p.rir} · descanso ${Fmt.num(e.restSeconds / 60.0)} min" + if (e.role.name == "MAIN") " · principal" else "")
                        }
                        col.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        text("›", 22f, C.muted, bottom = 0)
                    }
                    line.isClickable = true
                    line.contentDescription = e.exercise.name
                    line.background = ripple(rounded(C.surface, dp(8).toFloat()), dp(8).toFloat())
                    line.setOnClickListener { push(ExerciseScreen(s, e)) }
                }
            }
        }

        // Explica o treino que a semana mostra neste dia (trocado ou não). Semanas que passaram: o programa pode ser outro.
        if (s != null && !pastWeek) fit.whyOn(date)?.let { w ->
            root.button("🔬 ${w.title}", Btn.GHOST) { push(WhyScreen(w)) }
        }
    }

    /** Dias da semana (de hoje em diante, ainda não feitos) com que este dia pode trocar. */
    private fun swapTargets(week: WeekView, d: DayPlan, today: LocalDate): List<DayPlan> =
        week.days.filter { !it.date.isBefore(today) && it.date != d.date && it.status != DayStatus.DONE && (it.session != null || d.session != null) }

    private fun swapSection(
        root: LinearLayout, d: DayPlan, candidates: List<DayPlan>, today: LocalDate, thisWeek: Boolean, weekStart: LocalDate,
    ) = root.card(stroke = C.accent) {
        h3(d.session?.let { "Trocar ${it.name} com…" } ?: "Trazer um treino para ${d.day.pt()}")
        val labels = if (thisWeek) listOf(false to "Só esta semana", true to "Todas as semanas")
        else listOf(false to "Só nessa semana", true to "Dessa semana em diante")
        chips(labels, { it == permanent }, small = true, bottom = 4) { permanent = it; refresh() }
        val ws = Dates.short(weekStart)
        muted(when {
            thisWeek && permanent -> "Muda o programa: nas próximas semanas a ordem já vem trocada."
            thisWeek -> "Vale só para esta semana; na próxima, o programa volta ao normal."
            permanent -> "Muda o programa a partir da semana de $ws; esta semana continua como está."
            else -> "Vale só para a semana de $ws; depois, o programa volta ao normal."
        }, 12f)
        space(6)
        candidates.forEach { c ->
            card(bottom = 8, color = C.surface2, stroke = C.stroke, onClick = { swapWith(c.date) }) {
                row(bottom = 2) {
                    val t = text("${c.day.ptCapitalized()} ${Dates.short(c.date)}" + if (c.date == today) " · hoje" else "", 15f, bold = true, bottom = 0)
                    t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    text("Trocar →", 13f, C.accent, bold = true, bottom = 0)
                }
                muted(c.session?.let { "${it.name} · ~${it.estimatedMinutes} min" } ?: "Descanso — ${d.session?.name} passa para este dia")
            }
        }
    }

    private fun swapWith(other: LocalDate) {
        val r = reorderOrToast { fit.swapDays(date, other, permanent) } ?: return
        result = r
        swapping = false
        main.refreshTop(this)
    }

    private fun doToday() {
        val r = reorderOrToast { fit.doToday(date) } ?: return
        main.setRoot(HomeScreen(notice = r))
    }

    private fun statusLabel(d: DayPlan) = when {
        d.session == null && d.status == DayStatus.DONE -> "Treino extra"
        d.session == null -> "Descanso"
        else -> d.status.label
    }

    private fun relative(d: LocalDate, today: LocalDate): String? = when (d) {
        today -> "hoje"
        today.plusDays(1) -> "amanhã"
        today.minusDays(1) -> "ontem"
        else -> null
    }

    companion object {
        fun statusColor(s: DayStatus): Int = when (s) {
            DayStatus.DONE -> C.success
            DayStatus.MISSED -> C.warning
            DayStatus.TODAY -> C.accent
            DayStatus.MISSED_RESOLVED -> C.fact
            DayStatus.PLANNED, DayStatus.REST -> C.muted
        }

        /** "hoje", "amanhã", "ontem", "na quinta" ou, a uma semana ou mais, "em 28/09" (o dia da semana seria ambíguo). */
        fun ref(d: LocalDate, today: LocalDate): String = when {
            d == today -> "hoje"
            d == today.plusDays(1) -> "amanhã"
            d == today.minusDays(1) -> "ontem"
            Math.abs(ChronoUnit.DAYS.between(d, today)) >= 7 -> "em ${Dates.short(d)}"
            else -> d.dayOfWeek.ptWithArticle()
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Peças compartilhadas com a Home
// ---------------------------------------------------------------------------------------------

/** Executa uma reorganização; regra violada (ex.: dia que já passou) vira aviso curto em vez de erro. */
fun Screen.reorderOrToast(action: () -> ReorderResult): ReorderResult? = try {
    action()
} catch (e: IllegalArgumentException) {
    main.toast(e.message ?: "Não foi possível reorganizar a semana")
    null
}

/**
 * “Desfazer” de uma reorganização (troca de dias, fazer hoje, inclusive a troca permanente). [update] recebe
 * null quando desfez (o aviso some) ou o mesmo resultado sem “Desfazer” quando a semana mudou depois.
 */
fun Screen.undoAction(r: ReorderResult, update: (ReorderResult?) -> Unit): (() -> Unit)? {
    val u = r.undo ?: return null
    return {
        try {
            fit.undoReorder(u)
            main.toast("Troca desfeita")
            update(null)
        } catch (e: IllegalArgumentException) {
            main.toast(e.message ?: "Não foi possível desfazer")
            update(r.copy(undo = null))
        }
    }
}

/** Card com o resultado de uma reorganização: o que mudou e os avisos (🟢 regras do sistema). Nada é bloqueado. */
fun LinearLayout.reorderNotice(r: ReorderResult, onUndo: (() -> Unit)? = null, onDismiss: () -> Unit) =
    card(stroke = if (r.warnings.isEmpty()) C.success else C.warning) {
        h3("🔄 Semana reorganizada")
        r.moves.forEach { text("•  $it", 14f, bottom = 4) }
        if (r.warnings.isNotEmpty()) {
            space(4)
            label("Atenção")
            r.warnings.forEach { explanation(it, 13f) }
            muted("Você decide: dá para trocar de novo quando quiser.", 12f)
        }
        space(4)
        val actions = ArrayList<Triple<String, Btn, () -> Unit>>()
        if (onUndo != null) actions += Triple("↩️  Desfazer", Btn.SECONDARY, onUndo)
        actions += Triple("Entendi", Btn.GHOST, onDismiss)
        buttonRow(*actions.toTypedArray(), bottom = 2)
    }

/** Outros treinos desta semana ainda não feitos (próximos primeiro, depois os perdidos). */
fun pendingSessions(week: WeekView, today: LocalDate): List<DayPlan> =
    week.days.filter { it.date != today && it.session != null && (it.status == DayStatus.PLANNED || it.status == DayStatus.MISSED) }
        .sortedBy { if (it.date.isAfter(today)) 0 else 1 }

/** Sheet "Trocar o treino de hoje": um toque traz outro treino da semana para hoje (só esta semana). */
fun Screen.swapTodaySheet(onDone: (ReorderResult) -> Unit) {
    val week = fit.week() ?: return
    val today = fit.clock.now().toLocalDate()
    fit.workoutBlockingToday()?.let { w ->
        main.toast("Conclua ou descarte antes o treino em andamento (${w.plan?.name ?: "Treino"}).")
        return
    }
    val options = pendingSessions(week, today)
    main.sheet("Trocar o treino de hoje") { close ->
        muted("Escolha o treino que você quer fazer hoje. Vale só para esta semana.", 14f)
        space(4)
        if (options.isEmpty()) muted("Não há outros treinos pendentes nesta semana.")
        options.forEach { d ->
            sessionOption(d, today, "Trocar", fit.doTodayDisplaces(d.date)) { close(); reorderOrToast { fit.doToday(d.date) }?.let(onDone) }
        }
        if (week.days.any { it.date.isAfter(today) && it.session == null }) {
            button("Passar o de hoje para um dia de descanso…", Btn.GHOST, bottom = 2) { close(); push(DayScreen(today, swapping = true, tab = Tab.HOME)) }
        }
    }
}

/** Dia de descanso na Home: próximos treinos com "Fazer hoje". */
fun Screen.doTodayOptions(parent: LinearLayout, onDone: (ReorderResult) -> Unit) {
    val week = fit.week() ?: return
    val today = fit.clock.now().toLocalDate()
    val options = pendingSessions(week, today).take(4)
    if (options.isEmpty()) return
    if (fit.workoutBlockingToday() != null) {
        parent.space(4)
        parent.muted("🔒 Para trazer outro treino para hoje, conclua ou descarte antes o treino em andamento.", 13f)
        return
    }
    parent.space(4)
    parent.label("Quer treinar hoje?")
    options.forEach { d -> parent.sessionOption(d, today, "Fazer hoje", null) { reorderOrToast { fit.doToday(d.date) }?.let(onDone) } }
}

private fun LinearLayout.sessionOption(d: DayPlan, today: LocalDate, action: String, displaced: Displaced?, onClick: () -> Unit) {
    val s = d.session ?: return
    card(bottom = 8, color = C.surface2, stroke = C.stroke, onClick = onClick) {
        row(bottom = 2) {
            val t = text("${s.name} · ${d.day.pt()}", 15f, bold = true, bottom = 0)
            t.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            text("$action →", 13f, C.accent, bold = true, bottom = 0)
        }
        muted("~${s.estimatedMinutes} min · ${s.exercises.size} exercícios" + if (d.date.isBefore(today)) " · não realizado" else "")
        displacedHint(displaced, bottom = 2)
    }
}

/** Antes de trazer um treino para hoje: para onde vai o treino que estava hoje. */
fun LinearLayout.displacedHint(d: Displaced?, bottom: Int) {
    d ?: return
    val to = d.to
    if (to != null) text("↪️ ${d.session.name}, de hoje, vai para ${to.dayOfWeek.pt()}", 13f, C.muted, bottom = bottom)
    else text("⚠️ ${d.session.name}, de hoje, fica fora desta semana (não há outro dia livre com tempo)", 13f, C.warning, bottom = bottom)
}

/**
 * “Começar treino”: começa (ou retoma, se é o mesmo) o treino de [s]. Com outro treino em andamento, pergunta
 * antes — retomar aquele ou descartá-lo e começar este —, em vez de abrir o outro em silêncio.
 */
fun Screen.startOrAsk(s: PlannedSession, sessionId: Long?, readinessId: Long?) {
    val open = fit.openWorkoutConflict(s, sessionId)
    if (open == null) {
        push(WorkoutScreen(fit.startWorkout(s, sessionId, readinessId)))
        return
    }
    val name = open.plan?.name ?: "Treino"
    val today = fit.clock.now().toLocalDate()
    main.sheet("Treino em andamento") { close ->
        body("Você começou $name ${DayScreen.ref(open.startedAt.toLocalDate(), today)}, às ${Dates.time(open.startedAt)}, e ainda não concluiu.")
        muted("Para começar ${s.name}, retome e conclua aquele treino ou descarte-o (as séries registradas nele são apagadas).", 14f)
        space(4)
        button("▶  Retomar $name") { close(); fit.activeWorkout()?.let { push(WorkoutScreen(it)) } }
        button("Descartar e começar ${s.name}", Btn.DANGER) {
            close()
            fit.discardWorkout(open.id)
            push(WorkoutScreen(fit.startWorkout(s, sessionId, readinessId)))
        }
        button("Cancelar", Btn.GHOST, bottom = 2) { close() }
    }
}
