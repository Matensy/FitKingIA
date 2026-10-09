package com.fitkingia.app

import android.animation.ValueAnimator
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.fitkingia.app.data.Graph
import com.fitkingia.app.figure.ExerciseFigureView
import com.fitkingia.app.screens.ExerciseScreen
import com.fitkingia.app.screens.WorkoutScreen
import com.fitkingia.app.ui.Motion
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
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * Leitor de tela e "remover animações": aba ativa anunciada, figura que diz se está pausada e,
 * sem animação no sistema, figura parada mostrando início e fim (sem prometer animação/pausa).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AccessibilityTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private val now = LocalDateTime.of(2026, 10, 1, 18, 0) // quinta

    @Before fun setUp() {
        Motion.enabled = false
        MainActivity.synchronous = true
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
        Questionnaire.selectSex(ans, Sex.MALE)
        ans.primaryGoal = Goal.HYPERTROPHY
        ans.experience = ExperienceLevel.NONE
        Questionnaire.selectEnvironment(ans, fit.kb, EnvironmentId("full_gym"))
        Questionnaire.applyPreset(ans, mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.TUESDAY to 60, DayOfWeek.THURSDAY to 60, DayOfWeek.FRIDAY to 60))
        Questionnaire.safetyQuestions(fit.kb, ans).forEach { ans.safety[it.id] = false }
        fit.submit(ans)
    }

    @After fun tearDown() {
        setSystemAnimations(true)
        controller.pause().stop().destroy()
        Graph.fit = null
        Graph.override = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun all(v: View = activity.window.decorView): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun texts() = all().filterIsInstance<TextView>().map { it.text.toString() }
    private fun figure() = all().filterIsInstance<ExerciseFigureView>().single()

    /** "Remover animações" do sistema (escala de duração 0), como o Android aplica aos animadores. */
    private fun setSystemAnimations(on: Boolean) {
        val scale = if (on) 1f else 0f
        Settings.Global.putFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
        ValueAnimator::class.java.getMethod("setDurationScale", java.lang.Float.TYPE).invoke(null, scale)
    }

    @Test fun bottomBarAnnouncesTheSelectedTab() {
        activity.switchTab(Tab.WEEK); idle()
        fun item(label: String) = all().single { it.contentDescription == label && it.isClickable }
        assertTrue("aba ativa precisa estar selecionada para o leitor de tela", item("Semana").isSelected)
        assertFalse(item("Hoje").isSelected)
        activity.switchTab(Tab.PROGRESS); idle()
        assertTrue(item("Progresso").isSelected)
        assertFalse(item("Semana").isSelected)
        // Emojis de calendário aparecem na fonte do Android com o texto "July 17" (inglês).
        assertFalse(Tab.values().any { it.icon in setOf("📅", "🗓", "🗓️", "📆") })
    }

    @Test fun figureDescriptionSaysWhetherItIsPaused() {
        val pe = activity.fit.todayView()!!.session!!.exercises.first()
        activity.push(ExerciseScreen(null, pe)); idle()
        val view = figure()
        assertTrue(view.animated)
        assertTrue(view.contentDescription.toString().contains("toque para pausar"))
        view.performClick(); idle()
        assertTrue(view.contentDescription.toString().contains("Pausada"))
        view.performClick(); idle()
        assertTrue(view.contentDescription.toString().contains("Em movimento"))
    }

    @Test fun withoutSystemAnimationsTheFigureShowsStartAndEnd() {
        setSystemAnimations(false)
        val pe = activity.fit.todayView()!!.session!!.exercises.first()
        activity.push(ExerciseScreen(null, pe)); idle()
        var view = figure()
        assertFalse(view.animated)
        assertFalse("figura parada não é botão de pausa", view.isClickable)
        assertTrue(view.contentDescription.toString().contains("Início do movimento à esquerda"))
        assertTrue(texts().none { it.contains("animada") })
        assertTrue(texts().any { it.startsWith("Início e fim do movimento") })
        activity.pop(); idle()

        val fit = activity.fit
        val today = fit.todayView()!!
        fit.startWorkout(today.session!!, today.sessionId, null)
        activity.push(WorkoutScreen(fit.activeWorkout()!!)); idle()
        view = figure() // iniciante: aberta por padrão
        assertFalse(view.animated)
        assertFalse(texts().contains("Toque na figura para pausar"))
        assertTrue(texts().contains("Início à esquerda, fim à direita"))
        assertEquals(1f, view.currentFrame(), 0f)
    }
}
