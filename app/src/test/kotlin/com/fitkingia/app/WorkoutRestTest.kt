package com.fitkingia.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.fitkingia.app.data.Graph
import com.fitkingia.app.figure.ExerciseFigureView
import com.fitkingia.app.screens.ExerciseScreen
import com.fitkingia.app.screens.SwapScreen
import com.fitkingia.app.screens.WorkoutScreen
import com.fitkingia.app.ui.Motion
import com.fitkingia.app.ui.RestRing
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime

/**
 * Tela de treino: o descanso continua contando (e avisa) com outra tela por cima, a ilustração
 * começa aberta para iniciantes e "Concluir treino" sem séries é um botão desativado de verdade.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WorkoutRestTest {
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
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        Graph.fit = null
        Graph.override = null
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun advance(seconds: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(seconds))

    private fun all(v: View = activity.window.decorView): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun texts() = all().filterIsInstance<TextView>().map { it.text.toString() }

    private fun tap(label: String) {
        val t = all().filterIsInstance<TextView>().firstOrNull { it.text.toString().contains(label) && it.isClickable }
        assertNotNull("botão '$label' não está na tela: ${texts()}", t)
        t!!.performClick()
        idle()
    }

    /** O que o anel mostra ("Descanso 1:45"), ou null sem descanso na tela. */
    private fun ring(): String? = all().filterIsInstance<RestRing>().singleOrNull()?.contentDescription?.toString()

    private fun startWorkout(level: ExperienceLevel = ExperienceLevel.YEARS_1_TO_2): WorkoutScreen {
        val fit = activity.fit
        val ans = fit.currentAnswers()
        ans.consent = true
        Questionnaire.selectSex(ans, Sex.FEMALE)
        ans.primaryGoal = Goal.HYPERTROPHY
        ans.experience = level
        Questionnaire.selectEnvironment(ans, fit.kb, EnvironmentId("full_gym"))
        Questionnaire.applyPreset(ans, mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.TUESDAY to 60, DayOfWeek.THURSDAY to 60, DayOfWeek.FRIDAY to 60))
        Questionnaire.safetyQuestions(fit.kb, ans).forEach { ans.safety[it.id] = false }
        fit.submit(ans)
        val today = fit.todayView()!!
        fit.startWorkout(today.session!!, today.sessionId, null)
        val screen = WorkoutScreen(fit.activeWorkout()!!)
        activity.push(screen); idle()
        return screen
    }

    private fun restSeconds(): Int = activity.fit.todayView()!!.session!!.exercises.first().restSeconds

    @Test fun restKeepsCountingWhileAnotherScreenIsOnTop() {
        startWorkout()
        val total = restSeconds()
        tap("Registrar série")
        assertEquals("Descanso " + mmss(total), ring())

        tap("Como fazer / por que")
        assertTrue(activity.current is ExerciseScreen)
        advance(5)
        activity.onBackPressed(); idle()
        assertTrue(activity.current is WorkoutScreen)
        assertEquals("o tempo coberto conta", "Descanso " + mmss(total - 5), ring())
        advance(10)
        assertEquals("o anel continua andando depois de voltar", "Descanso " + mmss(total - 15), ring())

        tap("+15 s")
        assertEquals("Descanso " + mmss(total), ring())
        advance(3)
        assertEquals("+15 s não trava o cronômetro", "Descanso " + mmss(total - 3), ring())

        advance(total.toLong())
        assertNull("descanso terminado some da tela", ring())
        assertEquals("Descanso terminado — próxima série!", ShadowToast.getTextOfLatestToast())
    }

    @Test fun restAlarmRingsEvenWhileReadingAnotherScreen() {
        val screen = startWorkout()
        val total = restSeconds()
        tap("Registrar série")
        activity.push(SwapScreen(null, activity.fit.todayView()!!.session!!.exercises.first(), null, workoutId = activity.fit.activeWorkout()!!.id)); idle()
        assertFalse(activity.current === screen)
        advance(total.toLong() + 1)
        assertEquals("o alarme toca com a troca de exercício aberta", "Descanso terminado — próxima série!", ShadowToast.getTextOfLatestToast())
        activity.onBackPressed(); idle()
        assertNull(ring())
    }

    @Test fun leavingTheWorkoutStopsTheTimer() {
        startWorkout()
        tap("Registrar série")
        activity.switchTab(Tab.HOME); idle() // nova raiz: a tela de treino sai de vez
        ShadowToast.reset()
        advance(restSeconds().toLong() + 5)
        assertNull("tela que saiu não toca alarme", ShadowToast.getTextOfLatestToast())
    }

    @Test fun beginnersSeeTheMovementOpenAndCanHideIt() {
        startWorkout(ExperienceLevel.NONE)
        assertEquals("iniciante vê a figura sem precisar tocar", 1, all().filterIsInstance<ExerciseFigureView>().size)
        tap("Esconder o movimento")
        assertTrue(all().none { it is ExerciseFigureView })
        tap("Próximo")
        assertEquals("o próximo exercício também abre com a figura", 1, all().filterIsInstance<ExerciseFigureView>().size)
        tap("Anterior")
        assertTrue("a escolha de esconder vale enquanto o treino está aberto", all().none { it is ExerciseFigureView })
    }

    @Test fun repetitionsAreWrittenInPortuguese() {
        startWorkout()
        assertTrue(texts().none { Regex("\\breps\\b").containsMatchIn(it) })
        assertTrue(texts().any { it.endsWith("repetições") })
    }

    @Test fun finishWithoutSetsIsAReallyDisabledButton() {
        startWorkout()
        tap("Finalizar treino")
        val finish = all().filterIsInstance<TextView>().single { it.text == "Concluir treino" }
        assertFalse("leitor de tela precisa anunciar como desativado", finish.isEnabled)
        tap("Voltar ao treino")
        tap("Registrar série")
        tap("Pular")
        tap("Finalizar treino")
        assertTrue(all().filterIsInstance<TextView>().single { it.text == "Concluir treino" }.isEnabled)
    }

    private fun mmss(s: Int) = "%d:%02d".format(s / 60, s % 60)
}
