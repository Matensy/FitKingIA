package com.fitkingia.app.screens

import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.ImageView
import android.widget.LinearLayout
import com.fitkingia.app.Screen
import com.fitkingia.app.Tab
import com.fitkingia.app.ui.*
import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.Exercise
import com.fitkingia.core.model.ClaimId
import com.fitkingia.core.model.Fmt
import com.fitkingia.core.progression.OneRepMax
import com.fitkingia.core.progression.PerformanceTrend
import com.fitkingia.core.progression.TrendDirection
import java.io.File

/** Progresso: consistência, volume da semana, recordes, tendências, corpo e fotos. */
class ProgressScreen : Screen() {
    override val title = "Progresso"
    override val tab = Tab.PROGRESS

    override fun build(root: LinearLayout) {
        val c = fit.consistency()
        root.card {
            label("Consistência")
            kv("Esta semana", "${c.weekDone} de ${c.weekPlanned} treinos")
            kv("Últimas 4 semanas", c.last4WeeksPct?.let { "$it%" } ?: "— (programa novo)")
            kv("🔥 Sequência", "${c.streak} dia(s) com registro")
            kv("Nível", "${c.xp.level} (${c.xp.totalXp} XP)", C.ai)
            bar(c.xp.xpIntoLevel.toDouble() / (c.xp.xpIntoLevel + c.xp.xpForNext), C.ai, bottom = 4)
        }

        val volume = fit.volumeWeek()
        if (volume.isNotEmpty()) {
            root.h2("Volume da semana")
            root.muted("Séries feitas vs planejadas por músculo (indiretas contam 0,5).", 13f)
            root.card {
                volume.forEach { v ->
                    row(bottom = 2) {
                        val t = text(v.muscle.name, 14f, bottom = 0)
                        t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text("${Fmt.num(v.doneSets)} / ${Fmt.num(v.plannedSets)}", 13f, C.muted, bottom = 0)
                    }
                    bar(if (v.plannedSets > 0) v.doneSets / v.plannedSets else 0.0, if (v.message != null && v.doneSets < v.expectedSoFar) C.warning else C.success, height = 6)
                }
                volume.mapNotNull { it.message }.forEach { explanation(it, 13f) }
            }
        }

        val trends = fit.trends()
        if (trends.any { it.second.e1rmBySession.isNotEmpty() }) {
            root.h2("Força (1RM estimado)")
            root.card {
                trends.filter { it.second.e1rmBySession.isNotEmpty() }.forEach { (ex, t) ->
                    val icon = when (t.direction) { TrendDirection.IMPROVING -> "📈"; TrendDirection.DECLINING -> "📉"; TrendDirection.STABLE -> "➡️"; else -> "·" }
                    row(bottom = 6) {
                        val name = text("$icon ${ex.name}", 14f, bottom = 0)
                        name.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text("~${Fmt.kg(t.e1rmBySession.last().second)}", 14f, C.muted, bottom = 0)
                        setOnClickListener { push(ExerciseHistoryScreen(ex, t)) }
                        isClickable = true
                    }
                }
                val d = fit.deloadAdvice()
                divider()
                explanation(d.explanation, 13f)
            }
        }

        val prs = fit.records()
        root.h2("Recordes pessoais")
        root.card {
            if (prs.isEmpty()) muted("Seus PRs aparecem aqui depois do segundo treino de cada exercício.")
            prs.take(10).forEach { kv(Dates.short(it.date), it.description.removePrefix("🏆 NOVO PR — ")) }
        }

        bodySection(root)
        root.button("📸 Fotos de progresso", Btn.SECONDARY) { push(PhotosScreen()) }

        val readiness = fit.repo.readiness(7)
        if (readiness.isNotEmpty()) {
            root.h2("Prontidão recente")
            root.card { readiness.forEach { kv(Dates.short(it.at.toLocalDate()), "Recovery Score ${it.score}") } }
        }
    }

    private fun bodySection(root: LinearLayout) {
        val b = fit.body()
        root.h2("Corpo")
        root.card {
            b.latest?.weightKg?.let { kv("Peso", "${Fmt.num(it)} kg") }
            b.waists.lastOrNull()?.let { kv("Cintura", "${Fmt.num(it.second)} cm") }
            b.bmi?.let { kv("IMC", "${Fmt.fixed(it.value, 1)} · ${it.label}") }
            b.waistToHeight?.let { kv("Cintura/altura", "${Fmt.fixed(it.value, 2)} · ${it.label}") }
            if (b.weights.size >= 2) {
                label("Peso (pontos) e média móvel (linha azul)")
                add(LineChart(context, b.weights.map { Dates.short(it.date) }, b.weights.map { it.kg }, b.trend.movingAverage.map { it.second }), bottom = 8)
            }
            b.trend.messages.forEach { explanation(it, 13f) }
            b.bmi?.let { explanation(it.explanation, 12f) }
            b.waistToHeight?.let { explanation(it.explanation, 12f) }
            button("Registrar peso / cintura", Btn.SECONDARY) { push(BodyLogScreen()) }
        }
    }
}

class ExerciseHistoryScreen(private val ex: Exercise, private val t: PerformanceTrend) : Screen() {
    override val title get() = ex.name

    override fun build(root: LinearLayout) {
        root.text("${t.direction.label}${if (t.direction != TrendDirection.INSUFFICIENT_DATA) " (${Fmt.signed(t.pctPerSession, 1)}% por sessão)" else ""}", 16f, bold = true)
        if (t.e1rmBySession.size >= 2) root.add(LineChart(root.context, t.e1rmBySession.map { Dates.short(it.first) }, t.e1rmBySession.map { it.second }), bottom = 10)
        root.muted("1RM estimado pela equação de Epley a partir das suas séries (reps + RIR). É uma aproximação.")
        root.card { fit.exerciseLogs(ex.id).asReversed().take(15).forEach { l -> kv(Dates.short(l.date), l.sets.joinToString(" · ") { "${Fmt.num(it.loadKg, 2)}×${it.reps}" }) } }
        root.explanation(Explanation.fact(fit.kb.claim(ClaimId("e1rm_equations")).statement, listOf(ClaimId("e1rm_equations"))), 13f)
    }
}

/** Registro de peso e cintura por toques (começa no último valor). */
class BodyLogScreen : Screen() {
    override val title = "Peso e cintura"
    private var weight: Double? = null
    private var waist: Double? = null
    private var withWaist = false

    override fun build(root: LinearLayout) {
        val b = fit.body()
        val w = weight ?: b.weights.lastOrNull()?.kg ?: fit.profile()?.weightKg ?: 70.0
        weight = w
        root.label("Peso de hoje")
        root.stepper(Fmt.num(w), "kg", big = true, steps = listOf(
            "−1" to { weight = (w - 1).coerceAtLeast(30.0); refresh() }, "−0,1" to { weight = ((w - 0.1) * 10).let { Math.round(it) / 10.0 }; refresh() },
            "+0,1" to { weight = ((w + 0.1) * 10).let { Math.round(it) / 10.0 }; refresh() }, "+1" to { weight = w + 1; refresh() },
        ))
        root.chips(listOf(false to "Só o peso", true to "Também a cintura"), { it == withWaist }) { withWaist = it; refresh() }
        if (withWaist) {
            val c = waist ?: b.waists.lastOrNull()?.second ?: 85.0
            waist = c
            root.stepper(Fmt.num(c), "cm", big = true, steps = listOf(
                "−1" to { waist = c - 1; refresh() }, "−0,5" to { waist = c - 0.5; refresh() },
                "+0,5" to { waist = c + 0.5; refresh() }, "+1" to { waist = c + 1; refresh() },
            ))
        }
        root.button("Salvar") {
            fit.logBody(weight, if (withWaist) waist else null)
            main.toast("Registrado")
            pop()
        }
        root.muted("Dica: pese-se em condições parecidas (ao acordar, após ir ao banheiro). Oscilações de um dia para o outro são normais.")
        root.explanation(Explanation.fact(fit.kb.claim(ClaimId("glycogen_water")).statement, listOf(ClaimId("glycogen_water"))), 13f)
    }
}

/** Fotos de progresso: ficam no armazenamento privado do app, nunca saem do aparelho. */
class PhotosScreen : Screen() {
    override val title = "Fotos de progresso"
    private var angle = "front"

    override fun build(root: LinearLayout) {
        root.muted("Para comparar de verdade: mesma luz, mesma distância, mesma pose e roupa parecida. As fotos ficam só neste aparelho.", 14f)
        root.chips(listOf("front" to "Frente", "side" to "Lado", "back" to "Costas"), { it == angle }) { angle = it; refresh() }
        root.button("Adicionar foto da galeria") {
            main.pickImage { uri -> save(uri) }
        }
        val photos = fit.photos()
        if (photos.isEmpty()) root.muted("Nenhuma foto ainda.")
        val names = mapOf("front" to "Frente", "side" to "Lado", "back" to "Costas")
        for ((date, list) in photos.groupBy { it.date }) {
            root.label(Dates.long(date))
            root.row(bottom = 12) {
                list.forEach { p ->
                    val col = column(bottom = 0) {
                        val img = ImageView(context)
                        img.scaleType = ImageView.ScaleType.CENTER_CROP
                        decode(p.uri)?.let { img.setImageBitmap(it) }
                        addView(img, LinearLayout.LayoutParams(dp(100), dp(130)))
                        text("${names[p.angle] ?: p.angle}  ✕", 12f, C.muted, bottom = 0).setOnClickListener {
                            main.confirm("Apagar foto?", "A foto será removida do aparelho.", "Apagar", danger = true) {
                                File(p.uri).delete(); fit.deletePhoto(p.id); refresh()
                            }
                        }
                    }
                    col.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
                }
            }
        }
    }

    private fun save(uri: Uri) {
        try {
            val dir = File(main.filesDir, "photos").also { it.mkdirs() }
            val f = File(dir, "${System.currentTimeMillis()}_$angle.jpg")
            main.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
            fit.addPhoto(angle, f.absolutePath)
            refresh()
        } catch (e: Exception) {
            main.toast("Não foi possível salvar a foto")
        }
    }

    private fun decode(path: String) = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        var sample = 1
        while (o.outWidth / sample > 400 || o.outHeight / sample > 520) sample *= 2
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    } catch (e: Exception) { null }
}

/** Helper: 1RM estimado (usado em Ferramentas). */
fun e1rmLine(load: Double, reps: Int, rir: Int?): OneRepMax.Estimate = OneRepMax.estimate(load, reps, rir)
