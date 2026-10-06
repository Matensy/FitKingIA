package com.fitkingia.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import com.fitkingia.app.data.Graph
import com.fitkingia.app.screens.*
import com.fitkingia.appcore.AppClock
import com.fitkingia.appcore.FitKing
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
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Gera capturas das telas principais em build/screenshots (gráficos nativos do Robolectric).
 * Rode com -Dscreenshots=true; no build normal é pulado.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {

    @Test fun capture() {
        assumeTrue(System.getProperty("screenshots") == "true")
        val out = File(System.getProperty("screenshotDir") ?: "build/screenshots").also { it.mkdirs() }
        var now = LocalDateTime.of(2026, 9, 30, 18, 0) // quarta
        MainActivity.synchronous = true
        Graph.fit = null
        Graph.override = {
            val db = JdbcSqlDatabase.inMemory().also { UserDb.migrate(it, KnowledgeDbBuilder.schema("user.sql")) }
            FitKing(BundledKnowledge.load(), db, AppClock { now })
        }
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val a = controller.get()
        fun idle() = shadowOf(Looper.getMainLooper()).idle()
        fun shot(name: String) {
            idle()
            val root: View = a.window.decorView
            root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
            root.layout(0, 0, 1080, 2340)
            idle()
            val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        idle()
        shot("01_boas_vindas")
        val fit = a.fit
        val ans = fit.currentAnswers()
        a.setRoot(QuestionnaireScreen(ans.apply { consent = true; Questionnaire.selectSex(this, Sex.MALE); primaryGoal = Goal.WAIST_REDUCTION }))
        val qs = a.current as QuestionnaireScreen
        qs.goTo(com.fitkingia.appcore.Step.GOAL); shot("02_objetivo")
        ans.experience = ExperienceLevel.YEARS_1_TO_2
        Questionnaire.selectEnvironment(ans, fit.kb, EnvironmentId("full_gym"))
        Questionnaire.applyPreset(ans, mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.TUESDAY to 45, DayOfWeek.THURSDAY to 60, DayOfWeek.FRIDAY to 60))
        qs.goTo(com.fitkingia.appcore.Step.DAYS); shot("03_dias")
        qs.goTo(com.fitkingia.appcore.Step.SAFETY); shot("03b_triagem")
        ans.activity = ActivityLevel.MODERATE
        ans.sports.add(SportCommitment(SportId("kickboxing"), DayOfWeek.WEDNESDAY, 3))
        Questionnaire.safetyQuestions(fit.kb, ans).forEach { ans.safety[it.id] = false }
        ans.waistCm = 94
        qs.goTo(com.fitkingia.appcore.Step.SUMMARY); shot("03c_resumo")
        val outcome = fit.submit(ans)
        a.setRoot(ProgramReadyScreen(outcome)); shot("04_programa_pronto")
        now = LocalDateTime.of(2026, 10, 1, 18, 0) // quinta
        fit.addWater(1250)
        a.setRoot(HomeScreen()); shot("05_hoje")
        a.switchTab(Tab.WEEK); shot("06_semana")
        val t = fit.todayView()!!
        val w = fit.startWorkout(t.session!!, t.sessionId, null)
        val pe = t.session!!.exercises.first()
        fit.logSet(w.id, pe.exercise.id, 1, 60.0, 8, 2)
        a.push(WorkoutScreen(fit.activeWorkout()!!)); shot("07_treino")
        val summary = fit.finishWorkout(w.id, com.fitkingia.appcore.Perceived.ADEQUATE)
        a.setRoot(WorkoutSummaryScreen(summary)); shot("07b_resumo")
        a.setRoot(HomeScreen())
        a.push(MissedScreen(fit.week()!!.days.first { it.status == com.fitkingia.appcore.DayStatus.MISSED })); shot("07c_faltei")
        a.setRoot(ReadinessScreen()); shot("07d_prontidao")
        fit.logBody(83.0, 93.0)
        a.switchTab(Tab.PROGRESS); shot("08_progresso")
        a.switchTab(Tab.MORE); shot("09_mais")
        a.push(NutritionScreen()); shot("10_nutricao")
        a.push(ExerciseScreen(t.session, pe)); shot("11_exercicio")
        a.push(WhyScreen(fit.why.forExercise(fit.program()!!.program, pe.exercise.id))); shot("12_por_que")
        a.setRoot(WaterScreen()); shot("13_agua")
        a.setRoot(ToolsScreen()); shot("14_ferramentas")
        val sim = SimulatorScreen(); a.setRoot(sim)
        a.setRoot(ProfileScreen()); shot("15_perfil")
        // Prioridade por região: a amiga que quer treinar glúteo (4 dias, academia completa).
        val g = fit.currentAnswers()
        Questionnaire.selectSex(g, Sex.FEMALE)
        g.primaryGoal = Goal.HYPERTROPHY; g.secondaryGoal = null; g.sports.clear()
        Questionnaire.applyPreset(g, mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.TUESDAY to 60, DayOfWeek.THURSDAY to 60, DayOfWeek.FRIDAY to 60))
        Questionnaire.togglePriority(g, BodyRegion.GLUTES)
        Questionnaire.safetyQuestions(fit.kb, g).forEach { g.safety[it.id] = false }
        val gq = QuestionnaireScreen(g, startAt = com.fitkingia.appcore.Step.PRIORITY)
        a.setRoot(gq); shot("30_prioridade")
        a.setRoot(ProgramReadyScreen(fit.submit(g))); shot("31_programa_gluteos")
        a.setRoot(ProgramWhyScreen()); shot("32_por_que_programa")
        controller.pause().stop().destroy()
        Graph.override = null; Graph.fit = null
    }
}
