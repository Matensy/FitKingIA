package com.fitkingia.app.screens

import android.widget.LinearLayout
import com.fitkingia.app.Screen
import com.fitkingia.app.Tab
import com.fitkingia.app.figure.exerciseFigureCard
import com.fitkingia.app.ui.*
import com.fitkingia.appcore.DayPlan
import com.fitkingia.appcore.DayStatus
import com.fitkingia.appcore.Step
import com.fitkingia.core.explain.WhyReport
import com.fitkingia.core.knowledge.VerificationStatus
import com.fitkingia.core.model.Fmt
import com.fitkingia.core.model.Joint
import com.fitkingia.core.program.PlannedExercise
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.program.ProgramResult
import com.fitkingia.core.program.pt
import com.fitkingia.core.program.ptCapitalized
import java.time.LocalDate

/** Semana: os 7 dias com status (feito, perdido, hoje, planejado, descanso). */
class WeekScreen : Screen() {
    override val title = "Semana"
    override val tab = Tab.WEEK

    override fun build(root: LinearLayout) {
        val week = fit.week()
        if (week == null) {
            root.card { body("Ainda não há programa. Responda o questionário para gerar um."); button("Abrir questionário") { push(QuestionnaireScreen(fit.currentAnswers())) } }
            return
        }
        val p = week.program.program
        root.muted("${Dates.short(week.weekStart)} a ${Dates.short(week.weekStart.plusDays(6))} · ${p.split.name}", 14f)
        root.text("${week.done} de ${week.planned} treinos feitos", 16f, bold = true)
        root.bar(if (week.planned == 0) 0.0 else week.done.toDouble() / week.planned, C.success, bottom = 14)
        week.plan?.let { root.card(stroke = C.fact) { text("↪️ Semana replanejada: ${it.reason}", 14f, bottom = 2) } }
        for (d in week.days) dayCard(root, d)
        root.buttonRow(
            Triple("🔬 Por que esta divisão?", Btn.SECONDARY) { push(ProgramWhyScreen()) },
            Triple("🧪 Simular", Btn.SECONDARY) { push(SimulatorScreen()) },
        )
    }

    private fun dayCard(root: LinearLayout, d: DayPlan) {
        val s = d.session
        val color = when (d.status) {
            DayStatus.DONE -> C.success; DayStatus.MISSED -> C.warning; DayStatus.TODAY -> C.accent; else -> null
        }
        root.card(bottom = 8, stroke = color, onClick = when {
            d.status == DayStatus.MISSED -> ({ push(MissedScreen(d)) })
            s != null -> ({ push(SessionScreen(s, d.sessionId, d.date)) })
            else -> null
        }) {
            row(bottom = 2) {
                val t = text("${d.day.ptCapitalized()} ${Dates.short(d.date)}", 15f, bold = true, bottom = 0)
                t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                text("${HomeScreen.statusIcon(d.status, s != null)} ${if (s == null && d.status == DayStatus.REST) "Descanso" else d.status.label}", 13f, color ?: C.muted, bottom = 0)
            }
            if (s != null) muted("${s.name} · ~${s.estimatedMinutes} min · ${s.exercises.size} exercícios")
            if (d.status == DayStatus.MISSED_RESOLVED) muted("Opção ${d.missedOption} escolhida")
            if (s == null && d.workouts.isNotEmpty()) muted("Treino extra registrado")
        }
    }
}

/** Sessão: exercícios com prescrição; toque para detalhes, troca e "por que". */
class SessionScreen(private val session: PlannedSession, private val sessionId: Long?, private val date: LocalDate?) : Screen() {
    override val title get() = session.name

    override fun build(root: LinearLayout) {
        root.muted(listOfNotNull(date?.let { Dates.long(it) }, "~${session.estimatedMinutes} min", session.budgetMinutes?.let { "tempo do dia $it min" }).joinToString(" · "), 14f)
        session.exercises.forEachIndexed { i, e ->
            val p = e.prescription
            root.card(bottom = 8, onClick = { push(ExerciseScreen(session, e)) }) {
                row(bottom = 2) {
                    val t = text("${i + 1}. ${e.exercise.name}", 16f, bold = true, bottom = 0)
                    t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    badge(e.role.label, if (e.role.name == "MAIN") C.accent else C.muted, bottom = 0)
                }
                muted("${e.sets} séries × ${p.target} · RIR ${p.rir} · descanso ${Fmt.num(e.restSeconds / 60.0)} min · cadência ${p.tempo}")
                e.note?.let { muted(it) }
            }
        }
        val today = fit.clock.now().toLocalDate()
        if (date == null || !date.isAfter(today)) root.button("▶  Fazer este treino agora") {
            push(WorkoutScreen(fit.startWorkout(session, sessionId, null)))
        }
        date?.let { d ->
            fit.program()?.let { sp -> root.button("🔬 Por que ${session.name} na ${d.dayOfWeek.pt()}?", Btn.GHOST) { push(WhyScreen(fit.why.forDay(sp.program, d.dayOfWeek))) } }
        }
    }
}

/** Detalhe do exercício: como fazer, músculos, histórico, por que, trocar, dor. */
class ExerciseScreen(private val session: PlannedSession?, private val pe: PlannedExercise) : Screen() {
    override val title get() = pe.exercise.name

    override fun build(root: LinearLayout) {
        val kb = fit.kb
        val ex = pe.exercise
        val p = pe.prescription
        root.exerciseFigureCard(ex)
        root.card(stroke = C.accent) {
            label("Prescrição")
            text("${pe.sets} × ${p.target}", 22f, bold = true, bottom = 2)
            muted("RIR ${p.rir} (repetições sobrando) · descanso ${Fmt.num(p.restSeconds.first / 60.0)}–${Fmt.num(p.restSeconds.last / 60.0)} min · cadência ${p.tempo}")
        }
        root.card {
            kv("Padrão", kb.patternName(ex.pattern))
            kv("Principais", ex.primaryMuscles.joinToString { kb.muscleName(it) })
            if (ex.secondaryMuscles.isNotEmpty()) kv("Secundários", ex.secondaryMuscles.joinToString { kb.muscleName(it) })
            kv("Equipamento", ex.equipment.joinToString { kb.equipment(it).name }.ifEmpty { "nenhum" })
            kv("Dificuldade", "${ex.difficulty}/5")
        }
        if (ex.instructions.isNotEmpty()) { root.h2("Passo a passo"); root.card { ex.instructions.forEachIndexed { i, s -> body("${i + 1}. $s") } } }
        if (ex.commonMistakes.isNotEmpty()) { root.h2("Erros comuns"); root.card { bullets(ex.commonMistakes) } }
        if (ex.safetyNotes.isNotEmpty()) { root.h2("Segurança"); root.card(stroke = C.warning) { bullets(ex.safetyNotes) } }
        if (ex.progressionMethods.isNotEmpty()) { root.h2("Como progredir"); root.card { bullets(ex.progressionMethods) } }

        val logs = fit.exerciseLogs(ex.id)
        if (logs.isNotEmpty()) {
            root.h2("Seu histórico")
            root.card {
                logs.takeLast(5).asReversed().forEach { l ->
                    kv(Dates.short(l.date), l.sets.joinToString(" · ") { s -> (if (s.loadKg > 0) "${Fmt.num(s.loadKg, 2)}×" else "") + s.reps })
                }
            }
        }
        val sp = fit.program()
        if (sp != null && sp.program.sessions.any { s -> s.exercises.any { it.exercise.id == ex.id } }) {
            val day = session?.day?.takeIf { d -> sp.program.sessions.any { s -> s.day == d && s.exercises.any { it.exercise.id == ex.id } } }
            root.button("🔬 Por que isso?", Btn.SECONDARY) { push(WhyScreen(fit.why.forExercise(sp.program, ex.id, day))) }
        }
        root.button("🔁 Trocar exercício", Btn.SECONDARY) { push(SwapScreen(session, pe, null)) }
        root.button("🤕 Sinto dor neste exercício", Btn.SECONDARY) { push(PainScreen(session, pe)) }
        val profile = fit.profile()
        val fav = profile?.favoriteExercises?.contains(ex.id) == true
        root.buttonRow(
            Triple(if (fav) "★ Favorito" else "☆ Favoritar", Btn.GHOST) { fit.setExercisePreference(ex.id, if (fav) null else "favorite"); refresh() },
            Triple("🚫 Não quero", Btn.GHOST) {
                main.confirm("Excluir ${ex.name}?", "O motor não vai mais escolher este exercício. Escolha um substituto agora.", "Excluir e trocar") {
                    fit.setExercisePreference(ex.id, "excluded")
                    push(SwapScreen(session, pe, null))
                }
            },
        )
    }
}

/** "Por que isso?": recomendação → regra → evidência → fonte. */
class WhyScreen(private val report: WhyReport) : Screen() {
    override val title = "Por que isso?"

    override fun build(root: LinearLayout) {
        root.h2(report.title, top = 0)
        root.card { report.lines.forEach { text("🟢 $it", 14f) } }
        for (r in report.rules) {
            root.card {
                label(r.basis.label)
                h3(r.description)
                muted(r.rationale, 14f)
                muted("Revisada em ${Dates.short(r.lastReviewed)}/${r.lastReviewed.year}", 12f)
                for (e in r.evidence) {
                    divider()
                    text("🔵 ${e.statement}", 14f)
                    row(bottom = 6) {
                        badge(e.level.label, C.fact, bottom = 0)
                        if (e.conflicting) badge("Evidência conflitante", C.warning, bottom = 0).let { (it.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6) }
                    }
                    e.supports.forEach { s -> muted("✔ ${s.citation} [${s.tier}${if (s.verification == VerificationStatus.PENDING) ", verificação pendente" else ""}]", 12f) }
                    e.contradicts.forEach { s -> muted("✖ ${s.citation} [${s.tier}]", 12f) }
                    e.context.forEach { s -> muted("◦ ${s.citation} [${s.tier}]", 12f) }
                }
            }
        }
        root.muted("🔵 fato com fonte · 🟢 regra do sistema. Nenhuma prescrição é gerada por IA.")
    }
}

class ProgramWhyScreen : Screen() {
    override val title = "Por que este programa?"

    override fun build(root: LinearLayout) {
        val sp = fit.program() ?: return
        val p = sp.program
        root.h2("${p.split.name} · ${p.focus.label} · ${p.tier.label}", top = 0)
        root.muted("Gerado em ${Dates.short(sp.createdAt.toLocalDate())} com o conhecimento ${sp.kbVersion}.")
        val kb = fit.kb
        fit.goalCheck()?.let { report ->
            root.goalSection(report, kb) { push(QuestionnaireScreen(fit.currentAnswers(), startAt = Step.PRIORITY)) }
        }
        val warnings = p.warnings.filterNot { kb.isGoalCheck(it) }
        if (warnings.isNotEmpty()) { root.h2("Avisos"); root.card(stroke = C.warning) { warnings.forEach { explanation(it) } } }
        root.h2("Decisões do motor")
        root.card { p.explanations.filterNot { kb.isGoalCheck(it) }.forEach { explanation(it) } }
        root.h2("Volume semanal planejado")
        root.card {
            fit.kb.trackedMuscles.forEach { m ->
                val v = p.weeklyVolume[m.id] ?: 0.0
                val t = p.volumeTargets[m.id] ?: return@forEach
                kv(m.name, "${Fmt.num(v)} séries (${Fmt.num(t.min)}–${Fmt.num(t.max)})", if (v + 1e-9 < t.min) C.warning else C.text)
            }
        }
        root.button("Gerar o programa de novo", Btn.SECONDARY) {
            main.confirm("Gerar de novo?", "O motor refaz o programa a partir do seu perfil atual. Trocas manuais de exercício serão perdidas.", "Gerar") {
                main.background({ fit.regenerate() }) { r -> r.onSuccess { main.toast(if (it is ProgramResult.Generated) "Programa atualizado" else "Não foi possível gerar") }; refresh() }
            }
        }
    }
}

/** Substituição por similaridade, com os motivos de cada opção. */
class SwapScreen(
    private val session: PlannedSession?,
    private val pe: PlannedExercise,
    private val painJoint: Joint?,
    private val painSeverity: Int = 3,
    /** Troca durante o treino: vale para o treino em andamento (e, se escolhido, para o programa). */
    private val workoutId: Long? = null,
) : Screen() {
    override val title = "Trocar exercício"
    private var allSessions = false

    override fun build(root: LinearLayout) {
        val result = fit.substitutes(pe.exercise, painJoint, painSeverity) ?: return
        root.muted("Substitutos para ${pe.exercise.name}, por padrão de movimento, músculos, equipamento, nível e dor relatada.", 14f)
        result.notes.forEach { root.card(stroke = C.warning) { text("🟢 $it", 14f, bottom = 2) } }
        if (session != null || workoutId != null) {
            root.label("Aplicar em")
            val labels = if (workoutId != null) listOf(false to "Só neste treino", true to "Neste treino e no programa")
            else listOf(false to "Só nesta sessão", true to "Todo o programa")
            root.chips(labels, { it == allSessions }) { allSessions = it; refresh() }
        }
        for (o in result.options) {
            root.card(onClick = {
                if (workoutId != null) {
                    fit.swapInWorkout(workoutId, pe.exercise.id, o.exercise.id)
                    if (allSessions) fit.swap(pe.exercise.id, o.exercise.id, null)
                } else fit.swap(pe.exercise.id, o.exercise.id, if (allSessions || session == null) null else session.key)
                main.toast("${pe.exercise.name} → ${o.exercise.name}")
                main.popTo { it is SessionScreen || it is WeekScreen || it is HomeScreen || it is WorkoutScreen }
            }) {
                row(bottom = 2) {
                    val t = text(o.exercise.name, 16f, bold = true, bottom = 0)
                    t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text("${o.score.toInt()} pts", 12f, C.muted, bottom = 0)
                }
                o.reasons.take(4).forEach { muted("• $it", 12f) }
            }
        }
    }
}

/** "Sinto dor": registra a região e mostra opções com menor demanda — sem diagnosticar. */
class PainScreen(private val session: PlannedSession?, private val pe: PlannedExercise) : Screen() {
    override val title = "Sinto dor"
    private var joint: Joint? = Joint.values().maxByOrNull { pe.exercise.demand(it) }?.takeIf { pe.exercise.demand(it) > 0 }
    private var severity = 2

    override fun build(root: LinearLayout) {
        root.card(stroke = C.danger) {
            body("Dor forte, súbita, com inchaço, formigamento ou que piora durante o movimento: pare o exercício e procure avaliação profissional. O app não diagnostica.")
        }
        root.label("Onde dói?")
        root.chips(Joint.values().map { it to it.label.replaceFirstChar { c -> c.uppercase() } }, { it == joint }) { joint = it; refresh() }
        root.label("Intensidade")
        root.chips(com.fitkingia.appcore.Questionnaire.PAIN_LEVELS, { it == severity }) { severity = it; refresh() }
        val j = joint ?: return
        root.button("Ver exercícios com menor demanda no ${j.label}") { push(SwapScreen(session, pe, j, severity)) }
        root.button("Registrar dor e refazer o programa", Btn.SECONDARY) {
            main.background({ fit.reportPain(j, severity) }) { r ->
                r.onSuccess { main.toast("Dor registrada; programa refeito com a restrição") }
                main.switchTab(Tab.WEEK)
            }
        }
        root.muted("A dor registrada fica no seu perfil. Quando melhorar, marque \"Dor melhorou\" em Mais → Perfil.")
    }
}

/** "Faltei um treino": opções A–D com o efeito de cada uma na semana. */
class MissedScreen(private val day: DayPlan) : Screen() {
    override val title = "Treino não realizado"

    override fun build(root: LinearLayout) {
        val report = fit.missedOptions(day)
        if (report == null) { root.body("Não há programa ativo."); return }
        root.body(report.message)
        root.muted("Sem culpa: escolha a opção que encaixa na sua semana.", 14f)
        for (o in report.options) {
            root.card(stroke = if (o.available) C.stroke else null, color = if (o.available) C.surface else C.bg) {
                h3("${o.key}) ${o.title}")
                body(o.description)
                o.details.forEach { muted("• $it", 13f) }
                if (o.key != 'C' && o.available) {
                    label("Como fica a semana")
                    o.remainingWeek.forEach { s -> muted("${s.day?.ptCapitalized()}: ${s.name} (~${s.estimatedMinutes} min)", 13f) }
                }
                if (o.available) button("Escolher ${o.key}", if (o.key == 'A') Btn.PRIMARY else Btn.SECONDARY) {
                    fit.applyMissed(day, o)
                    main.toast("Semana atualizada")
                    pop()
                } else muted("Indisponível nesta semana.")
            }
        }
    }
}
