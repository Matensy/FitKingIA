package com.fitkingia.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.widget.TextView
import com.fitkingia.app.data.Graph
import com.fitkingia.app.screens.*
import com.fitkingia.app.ui.Motion
import com.fitkingia.appcore.AppClock
import com.fitkingia.appcore.FitKing
import com.fitkingia.appcore.Perceived
import com.fitkingia.appcore.Questionnaire
import com.fitkingia.appcore.UserDb
import com.fitkingia.core.model.*
import com.fitkingia.knowledge.BundledKnowledge
import com.fitkingia.knowledge.KnowledgeDbBuilder
import com.fitkingia.knowledge.sql.JdbcSqlDatabase
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowDialog
import java.io.File
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime

/**
 * Capturas da Semana (navegação entre semanas, virada de semana, treino em andamento, desfazer) em
 * build/screenshots. Rode com -Pscreenshots; no build normal é pulado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeekScreenshotTest {

    @Test fun capture() {
        assumeTrue(System.getProperty("screenshots") == "true")
        val out = File(System.getProperty("screenshotDir") ?: "build/screenshots").also { it.mkdirs() }
        var now = LocalDateTime.of(2026, 9, 28, 9, 0) // segunda
        MainActivity.synchronous = true
        Motion.enabled = false // capturas do estado final; animações são conferidas no ScreenshotTest
        Graph.fit = null
        Graph.override = {
            val db = JdbcSqlDatabase.inMemory().also { UserDb.migrate(it, KnowledgeDbBuilder.schema("user.sql")) }
            FitKing(BundledKnowledge.load(), db, AppClock { now })
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        fun shot(name: String) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            val root: View = a.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2340)
            idle()
            val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun shotDialog(name: String) {
            idle()
            val root = ShadowDialog.getLatestDialog().window!!.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.AT_MOST))
            root.layout(0, 0, 1080, root.measuredHeight)
            idle()
            val bmp = Bitmap.createBitmap(1080, maxOf(1, root.measuredHeight), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            canvas.drawColor(0xFF0B0D10.toInt())
            root.draw(canvas)
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        fun views(v: View): List<View> = if (v is android.view.ViewGroup) listOf(v) + (0 until v.childCount).flatMap { views(v.getChildAt(it)) } else listOf(v)
        fun click(root: View, label: String) {
            var v: View? = views(root).filterIsInstance<TextView>().first { it.text.toString().contains(label) }
            while (v != null && !v.isClickable) v = v.parent as? View
            v!!.performClick()
            idle()
        }
        idle()
        val fit = a.fit
        val ans = fit.currentAnswers()
        ans.consent = true
        Questionnaire.selectSex(ans, Sex.MALE)
        ans.age = 30
        ans.primaryGoal = Goal.HYPERTROPHY
        ans.experience = ExperienceLevel.YEARS_1_TO_2
        Questionnaire.selectEnvironment(ans, fit.kb, EnvironmentId("full_gym"))
        Questionnaire.applyPreset(ans, mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.WEDNESDAY to 60, DayOfWeek.SATURDAY to 60))
        ans.activity = ActivityLevel.MODERATE
        Questionnaire.safetyQuestions(fit.kb, ans).forEach { ans.safety[it.id] = false }
        fit.submit(ans)
        fun train() {
            val t = fit.todayView()!!
            val w = fit.startWorkout(t.session!!, t.sessionId, null)
            fit.logSet(w.id, t.session!!.exercises.first().exercise.id, 1, 60.0, 8, 2)
            fit.finishWorkout(w.id, Perceived.ADEQUATE)
        }
        train()
        now = LocalDateTime.of(2026, 9, 30, 18, 0) // quarta
        train()

        // Segunda seguinte: sábado ficou sem treino.
        now = LocalDateTime.of(2026, 10, 5, 8, 0)
        a.setRoot(HomeScreen()); shot("40_hoje_semana_passada")
        click(a.window.decorView, "Ver semana passada"); shot("41_semana_passada")
        click(a.window.decorView, "Sábado 03/10"); shot("42_dia_semana_passada")
        a.switchTab(Tab.WEEK); shot("43_semana_atual_navegacao")
        click(a.window.decorView, "Próxima ›"); shot("44_proxima_semana")
        click(a.window.decorView, "Segunda 12/10")
        click(a.window.decorView, "Trocar com outro dia"); shot("45_trocar_na_proxima_semana")
        click(a.window.decorView, "Terça 13/10"); shot("46_proxima_trocada_desfazer")

        // Treino em andamento: sem troca do treino de hoje; outro treino pergunta antes de começar.
        val t = fit.todayView()!!
        fit.startWorkout(t.session!!, t.sessionId, null)
        a.setRoot(HomeScreen()); shot("47_hoje_treino_aberto")
        now = LocalDateTime.of(2026, 10, 7, 18, 0) // quarta
        a.setRoot(HomeScreen())
        click(a.window.decorView, "Começar treino"); shotDialog("48_outro_treino_aberto_sheet")
        click(ShadowDialog.getLatestDialog().window!!.decorView, "Cancelar")
        fit.activeWorkout()?.let { fit.discardWorkout(it.id) }

        // Quinta (descanso): faz hoje o treino de segunda, que ficou sem fazer; aviso com “Desfazer” e “Por que hoje?”.
        now = LocalDateTime.of(2026, 10, 8, 9, 0)
        a.switchTab(Tab.WEEK)
        click(a.window.decorView, "Segunda 05/10"); shot("49_dia_perdido_fazer_hoje")
        click(a.window.decorView, "Fazer este treino hoje"); shot("50_hoje_reorganizado_desfazer")
        click(a.window.decorView, "Por que hoje?"); shot("51_por_que_hoje_trocado")
        controller.pause().stop().destroy()
        Graph.override = null; Graph.fit = null
    }
}
