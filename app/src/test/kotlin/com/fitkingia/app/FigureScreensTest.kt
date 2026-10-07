package com.fitkingia.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.fitkingia.app.data.Graph
import com.fitkingia.app.figure.ExerciseFigureView
import com.fitkingia.app.figure.FigureMapping
import com.fitkingia.app.screens.ExerciseScreen
import com.fitkingia.app.screens.WorkoutScreen
import com.fitkingia.appcore.AppClock
import com.fitkingia.appcore.FitKing
import com.fitkingia.appcore.Questionnaire
import com.fitkingia.appcore.UserDb
import com.fitkingia.core.model.EnvironmentId
import com.fitkingia.core.model.ExperienceLevel
import com.fitkingia.core.model.Goal
import com.fitkingia.core.model.Sex
import com.fitkingia.knowledge.BundledKnowledge
import com.fitkingia.knowledge.KnowledgeDbBuilder
import com.fitkingia.knowledge.sql.JdbcSqlDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Ilustrações nas telas reais: card "Como fazer" na tela do exercício e o botão
 * "Ver o movimento" no treino (estado que sobrevive às atualizações da tela).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FigureScreensTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private val now = LocalDateTime.of(2026, 10, 1, 18, 0) // quinta

    @Before fun setUp() {
        MainActivity.synchronous = true
        com.fitkingia.app.ui.Motion.enabled = false // estados finais imediatos (outros testes ligam as animações)
        Graph.fit = null
        Graph.override = {
            val db = JdbcSqlDatabase.inMemory().also { UserDb.migrate(it, KnowledgeDbBuilder.schema("user.sql")) }
            FitKing(BundledKnowledge.load(), db, AppClock { now })
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        idle()
        val fit = activity.fit
        val ans = fit.currentAnswers()
        ans.consent = true
        Questionnaire.selectSex(ans, Sex.FEMALE)
        ans.primaryGoal = Goal.HYPERTROPHY
        ans.experience = ExperienceLevel.YEARS_1_TO_2
        Questionnaire.selectEnvironment(ans, fit.kb, EnvironmentId("full_gym"))
        Questionnaire.applyPreset(ans, mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.TUESDAY to 60, DayOfWeek.THURSDAY to 60, DayOfWeek.FRIDAY to 60))
        Questionnaire.safetyQuestions(fit.kb, ans).forEach { ans.safety[it.id] = false }
        fit.submit(ans)
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        Graph.fit = null
        Graph.override = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun all(v: View = activity.window.decorView): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun figures() = all().filterIsInstance<ExerciseFigureView>()
    private fun texts() = all().filterIsInstance<TextView>().map { it.text.toString() }

    private fun tap(label: String) {
        val t = all().filterIsInstance<TextView>().firstOrNull { it.text.toString() == label && it.isClickable }
        assertTrue("botão '$label' não está na tela: ${texts()}", t != null)
        t!!.performClick()
        idle()
    }

    @Test fun workoutShowsAndHidesTheMovement() {
        val fit = activity.fit
        val today = fit.todayView()!!
        val session = today.session!!
        fit.startWorkout(session, today.sessionId, null)
        activity.push(WorkoutScreen(fit.activeWorkout()!!)); idle()
        val first = session.exercises.first().exercise
        assertTrue("figura não deve aparecer antes do toque", figures().isEmpty())

        tap("👁 Ver o movimento")
        assertEquals(1, figures().size)
        assertEquals(FigureMapping.motionFor(first).id, figures().single().motion.id)
        assertTrue(texts().contains("Esconder o movimento"))

        // Qualquer toque reconstrói a tela: a figura continua aberta e a pausa é mantida.
        val before = figures().single()
        before.performClick(); idle()
        assertTrue(before.paused)
        val plus = all().filterIsInstance<TextView>().first { it.isClickable && it.text.startsWith("+") }
        plus.performClick(); idle()
        val after = figures().single()
        assertTrue("a tela devia ter sido reconstruída", after !== before)
        assertTrue("pausa perdida ao atualizar a tela", after.paused)
        after.togglePause()
        assertFalse(after.paused)

        if (System.getProperty("screenshots") == "true") shot("figuras_tela_treino")

        tap("Esconder o movimento")
        assertTrue(figures().isEmpty())
        assertTrue(texts().contains("👁 Ver o movimento"))
    }

    @Test fun exerciseScreenHasHowToCard() {
        val fit = activity.fit
        val today = fit.todayView()!!
        val pe = today.session!!.exercises.first()
        activity.push(ExerciseScreen(today.session, pe)); idle()
        assertEquals(1, figures().size)
        assertEquals(FigureMapping.motionFor(pe.exercise).id, figures().single().motion.id)
        assertTrue(texts().contains("COMO FAZER"))
        assertTrue(texts().any { it.startsWith("Ilustração animada") })
        if (pe.exercise.instructions.isNotEmpty()) assertTrue(texts().contains("Passo a passo"))
        assertTrue(figures().single().contentDescription.toString().contains(pe.exercise.name))
    }

    private fun shot(name: String) {
        val out = File(System.getProperty("screenshotDir") ?: "build/screenshots").also { it.mkdirs() }
        val root: View = activity.window.decorView
        root.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(2340, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1080, 2340)
        idle()
        val bmp = Bitmap.createBitmap(1080, 2340, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bmp))
        File(out, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
