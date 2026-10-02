package com.fitkingia.app.screens

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Vibrator
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.fitkingia.app.Screen
import com.fitkingia.app.ui.*
import com.fitkingia.appcore.ActiveWorkout
import com.fitkingia.appcore.Perceived
import com.fitkingia.appcore.WorkoutSummary
import com.fitkingia.core.model.ExerciseId
import com.fitkingia.core.model.Fmt
import com.fitkingia.core.model.LoadType
import com.fitkingia.core.model.SlotRole
import com.fitkingia.core.program.PlannedExercise
import com.fitkingia.core.progression.ProgressionAction

/**
 * Execução do treino: carga sugerida pelo histórico, aquecimento, anilhas, registro de cada
 * série por toques (carga, repetições, RIR), cronômetro de descanso e resumo com PRs.
 */
class WorkoutScreen(private var w: ActiveWorkout) : Screen() {
    override val title get() = w.session.name
    override val keepScreenOn = true

    private var index = -1
    private var finishing = false
    private var perceived: Perceived? = null
    private val load = HashMap<ExerciseId, Double>()
    private val reps = HashMap<ExerciseId, Int>()
    private val rir = HashMap<ExerciseId, Int>()
    private var showWarmup = false

    private var restLeft = 0
    private var restLabel: TextView? = null
    private val tick = Runnable { onTick() }

    override fun build(root: LinearLayout) {
        w = fit.activeWorkout()?.takeIf { it.id == w.id } ?: w
        val exercises = w.session.exercises
        if (exercises.isEmpty()) { root.body("Sessão sem exercícios."); return }
        if (index < 0) index = exercises.indexOfFirst { e -> setsDone(e.exercise.id) < e.sets }.let { if (it < 0) exercises.lastIndex else it }
        index = index.coerceIn(0, exercises.lastIndex)

        val totalSets = exercises.sumOf { it.sets }
        val doneSets = exercises.sumOf { setsDone(it.exercise.id).coerceAtMost(it.sets) }
        root.muted("$doneSets de $totalSets séries · começou às ${Dates.time(w.startedAt)}", 13f)
        root.bar(doneSets.toDouble() / totalSets, C.success, bottom = 12)

        if (finishing) { finishPanel(root); return }

        val pe = exercises[index]
        exerciseCard(root, pe, exercises.size)
        root.buttonRow(
            Triple("← Anterior", Btn.SECONDARY) { if (index > 0) { index--; showWarmup = false; main.refreshTop(this) } },
            Triple("🔁 Trocar", Btn.SECONDARY) { push(SwapScreen(null, pe, null, workoutId = w.id)) },
            Triple("Próximo →", Btn.SECONDARY) { if (index < exercises.lastIndex) { index++; showWarmup = false; main.refreshTop(this) } },
        )
        root.button("Finalizar treino", if (doneSets >= totalSets) Btn.PRIMARY else Btn.GHOST) { finishing = true; main.refreshTop(this) }

        root.h2("Sessão")
        exercises.forEachIndexed { i, e ->
            val done = setsDone(e.exercise.id)
            root.card(bottom = 6, color = if (i == index) C.accentDark else C.surface, onClick = { index = i; showWarmup = false; main.refreshTop(this) }) {
                row(bottom = 2) {
                    val t = text("${if (done >= e.sets) "✅" else "${i + 1}."} ${e.exercise.name}", 14f, bold = i == index, bottom = 0)
                    t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    text("$done/${e.sets}", 13f, C.muted, bottom = 0)
                }
            }
        }
    }

    private fun exerciseCard(root: LinearLayout, pe: PlannedExercise, count: Int) {
        val ex = pe.exercise
        val p = pe.prescription
        val id = ex.id
        val logged = w.sets.filter { it.exerciseId == id && !it.warmup }
        val suggestion = fit.suggestion(pe)
        val timed = ex.timed
        val step = fit.loadStep(ex)
        // Ao retomar um treino, continua de onde parou (última série registrada).
        if (id !in load) load[id] = logged.lastOrNull()?.loadKg ?: suggestion.suggestedLoadKg ?: fit.lastLog(id)?.workingLoad ?: fit.defaultLoad(ex)
        if (id !in reps) reps[id] = logged.lastOrNull()?.reps
            ?: if (timed) p.holdSeconds?.first ?: 30 else suggestion.repTargets.getOrNull(logged.size) ?: p.reps.last
        if (id !in rir) rir[id] = p.rir

        root.label("Exercício ${index + 1} de $count · ${pe.role.label}")
        root.h1(ex.name)
        root.text("${pe.sets} × ${p.target} · RIR ${p.rir} · descanso ${Dates.mmss(pe.restSeconds)}", 15f, C.accent, bold = true)
        pe.note?.let { root.muted(it) }

        root.card(stroke = C.rule) {
            text("🟢 ${suggestion.message}", 14f)
            suggestion.pattern?.let { muted(it) }
            if (suggestion.action != ProgressionAction.START) muted(suggestion.explanation.text, 12f)
        }

        val loaded = ex.loadType != LoadType.BODYWEIGHT && ex.loadType != LoadType.BAND
        if (pe.role == SlotRole.MAIN && loaded && !timed) {
            root.button(if (showWarmup) "Esconder aquecimento" else "Ver aquecimento", Btn.GHOST) { showWarmup = !showWarmup; refresh() }
            if (showWarmup) root.card {
                label("Aquecimento específico")
                fit.warmup(pe, load.getValue(id)).forEach { s -> kv("${if (s.loadKg > 0) Fmt.kg(s.loadKg) else "leve"} × ${s.reps}", s.note) }
            }
        }

        if (logged.isNotEmpty()) root.card {
            label("Séries feitas")
            logged.forEachIndexed { i, s ->
                row(bottom = 4) {
                    val what = (if (timed) "${s.reps} s" else (if (s.loadKg > 0 || loaded) "${Fmt.kg(s.loadKg)} × ${s.reps}" else "${s.reps} reps")) +
                        (s.rir?.let { " · RIR $it" } ?: "")
                    val t = text("Série ${i + 1}: $what", 15f, bottom = 0)
                    t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    val undo = text("✕", 18f, C.muted, bottom = 0)
                    undo.setPadding(dp(12), dp(2), dp(4), dp(2))
                    undo.contentDescription = "Apagar série ${i + 1}"
                    undo.setOnClickListener { fit.undoSet(s.id); refresh() }
                }
            }
        }

        val n = logged.size + 1
        root.card(stroke = C.accent) {
            label(if (logged.size >= pe.sets) "Série extra ($n)" else "Série $n de ${pe.sets}")
            if (!timed) {
                muted(if (loaded) "Carga" else "Carga extra (colete, mochila…)", 12f)
                val l = load.getValue(id)
                stepper(Fmt.num(l, 2), "kg", steps = listOf(
                    "−5" to { load[id] = (l - 5).coerceAtLeast(0.0); refresh() },
                    "−${Fmt.num(step, 2)}" to { load[id] = (l - step).coerceAtLeast(0.0); refresh() },
                    "+${Fmt.num(step, 2)}" to { load[id] = l + step; refresh() },
                    "+5" to { load[id] = l + 5; refresh() },
                ))
                if (ex.loadType == LoadType.BARBELL && l > fit.barKg()) muted("Anilhas: ${fit.plates(l).describe()}", 12f)
            }
            muted(if (timed) "Tempo (segundos)" else "Repetições", 12f)
            val r = reps.getValue(id)
            val d = if (timed) 5 else 1
            stepper(r.toString(), if (timed) "s" else "reps", steps = listOf(
                "−$d" to { reps[id] = (r - d).coerceAtLeast(0); refresh() },
                "+$d" to { reps[id] = r + d; refresh() },
            ))
            muted("Quantas repetições ainda sobravam? (RIR)", 12f)
            chips((0..5).map { it to if (it == 5) "5+" else it.toString() }, { it == rir[id] }, small = true) { rir[id] = it; refresh() }
            button("✓  Registrar série $n") {
                fit.logSet(w.id, id, n, if (timed) 0.0 else load.getValue(id), reps.getValue(id), rir[id])
                val next = suggestion.repTargets.getOrNull(n)
                if (next != null && !timed) reps[id] = next
                startRest(pe.restSeconds)
                if (n >= pe.sets && index < w.session.exercises.lastIndex) main.toast("Exercício concluído ✅ — toque em Próximo")
                refresh()
            }
        }
        root.button("ℹ️ Como fazer / por que", Btn.GHOST) { push(ExerciseScreen(null, pe)) }
    }

    private fun finishPanel(root: LinearLayout) {
        root.h2("Como foi o treino?", top = 0)
        root.chips(Perceived.values().map { it to it.label }, { it == perceived }) { perceived = it; refresh() }
        val total = w.sets.count { !it.warmup }
        root.muted("$total série(s) registrada(s).")
        if (total == 0) root.card(stroke = C.warning) { body("Nenhuma série registrada. Se quiser, descarte o treino em vez de concluir.") }
        root.button("Concluir treino", enabled = total > 0) {
            stopRest()
            val summary = fit.finishWorkout(w.id, perceived)
            main.setRoot(HomeScreen())
            main.push(WorkoutSummaryScreen(summary))
        }
        root.button("Voltar ao treino", Btn.SECONDARY) { finishing = false; refresh() }
        root.button("Descartar treino", Btn.DANGER) {
            main.confirm("Descartar treino?", "As séries registradas neste treino serão apagadas.", "Descartar", danger = true) {
                stopRest(); fit.discardWorkout(w.id); main.setRoot(HomeScreen())
            }
        }
    }

    override fun footer(root: LinearLayout) {
        if (restLeft <= 0) return
        root.card(bottom = 6, color = C.surface2, stroke = C.fact) {
            row(bottom = 6) {
                val t = text("⏱ Descanso ${Dates.mmss(restLeft)}", 18f, bold = true, bottom = 0)
                t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                restLabel = t
            }
            buttonRow(
                Triple("−15 s", Btn.SECONDARY) { restLeft = (restLeft - 15).coerceAtLeast(1); updateRest() },
                Triple("+15 s", Btn.SECONDARY) { restLeft += 15; updateRest() },
                Triple("Pular", Btn.GHOST) { stopRest(); refresh() },
                bottom = 2,
            )
        }
    }

    private fun startRest(seconds: Int) {
        stopRest()
        restLeft = seconds
        main.mainHandler.postDelayed(tick, 1000)
    }

    private fun stopRest() {
        main.mainHandler.removeCallbacks(tick)
        restLeft = 0
        restLabel = null
    }

    private fun updateRest() {
        restLabel?.text = "⏱ Descanso ${Dates.mmss(restLeft)}"
    }

    private fun onTick() {
        restLeft--
        if (restLeft <= 0) {
            stopRest()
            alert()
            if (main.current === this) refresh()
            return
        }
        updateRest()
        main.mainHandler.postDelayed(tick, 1000)
    }

    private fun alert() {
        try {
            @Suppress("DEPRECATION")
            (main.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)?.vibrate(longArrayOf(0, 300, 150, 300), -1)
        } catch (_: Exception) {}
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
            main.mainHandler.postDelayed({ tone.release() }, 800)
        } catch (_: Exception) {}
        main.toast("Descanso terminado — próxima série!")
    }

    private fun setsDone(id: ExerciseId) = w.sets.count { it.exerciseId == id && !it.warmup }

    override fun onBack(): Boolean {
        if (finishing) { finishing = false; refresh(); return true }
        main.sheet("Sair do treino?") { close ->
            muted("O treino fica salvo e você pode retomar pela tela Hoje.")
            button("Continuar treinando") { close() }
            button("Sair e retomar depois", Btn.SECONDARY) { close(); stopRest(); main.setRoot(HomeScreen()) }
        }
        return true
    }

    override fun onLeave() {
        main.mainHandler.removeCallbacks(tick)
    }
}

/** Resumo depois do treino: PRs, XP, volume. */
class WorkoutSummaryScreen(private val s: WorkoutSummary) : Screen() {
    override val title = "Treino concluído"

    override fun build(root: LinearLayout) {
        root.text("🏁", 48f, gravity = Gravity.CENTER)
        root.h1("Mandou bem!")
        root.card {
            kv("Duração", "${s.minutes} min")
            kv("Séries", s.setsDone.toString())
            kv("Volume", "${Fmt.int(s.volumeKg.toInt())} kg")
            kv("XP ganho", "+${s.xpGained}", C.ai)
        }
        if (s.levelAfter > s.levelBefore) root.card(stroke = C.ai) { h3("⬆️ Subiu para o nível ${s.levelAfter}!") }
        if (s.weekCompleted) root.card(stroke = C.success) { h3("📅 Semana completa! Todos os treinos planejados foram feitos.") }
        if (s.records.isNotEmpty()) {
            root.h2("Recordes pessoais")
            root.card(stroke = C.warning) { s.records.forEach { body(it.description) } }
        }
        root.h2("O que você fez")
        root.card { s.perExercise.forEach { (n, sets) -> kv(n, sets) } }
        root.muted("As próximas sugestões de carga usam estes registros (dupla progressão).")
        root.button("Voltar ao início") { main.setRoot(HomeScreen()) }
    }
}
