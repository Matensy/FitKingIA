package com.fitkingia.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fitkingia.app.data.Graph
import com.fitkingia.app.screens.*
import com.fitkingia.app.ui.BarFill
import com.fitkingia.app.ui.ConfettiView
import com.fitkingia.app.ui.Motion
import com.fitkingia.app.ui.RestRing
import com.fitkingia.appcore.AppClock
import com.fitkingia.appcore.FitKing
import com.fitkingia.appcore.Perceived
import com.fitkingia.appcore.Questionnaire
import com.fitkingia.appcore.UserDb
import com.fitkingia.core.model.*
import com.fitkingia.knowledge.BundledKnowledge
import com.fitkingia.knowledge.KnowledgeDbBuilder
import com.fitkingia.knowledge.sql.JdbcSqlDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDateTime

/**
 * Animações com o interruptor ligado: a navegação anima (e chega ao estado final quando o
 * relógio do looper avança); um refresh() comum não reinicia a entrada da tela.
 *
 * O render() é síncrono: logo depois da ação a tela está no estado inicial da animação. No
 * Robolectric, idle() avança o relógio enquanto houver quadros de animação pendentes, então
 * depois dele (ou de [settle]) tudo deve estar no estado final.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MotionTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private val now = LocalDateTime.of(2026, 9, 28, 9, 0) // segunda-feira

    @Before fun setUp() {
        Motion.enabled = true
        MainActivity.synchronous = true
        Graph.fit = null
        Graph.override = {
            val db = JdbcSqlDatabase.inMemory().also { UserDb.migrate(it, KnowledgeDbBuilder.schema("user.sql")) }
            FitKing(BundledKnowledge.load(), db, AppClock { now })
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
        settle()
    }

    @After fun tearDown() {
        controller.pause().stop().destroy()
        Graph.fit = null
        Graph.override = null
        Motion.enabled = true
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun settle(ms: Long = 2000) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun all(v: View = activity.window.decorView): List<View> =
        if (v is ViewGroup) listOf(v) + (0 until v.childCount).flatMap { all(v.getChildAt(it)) } else listOf(v)

    private fun content(): LinearLayout = all().filterIsInstance<ScrollView>().first().getChildAt(0) as LinearLayout
    private fun blocks(): List<View> = (0 until content().childCount).map { content().getChildAt(it) }
    private fun bars(): List<BarFill> = all(content()).filterIsInstance<BarFill>()

    /** Toca sem deixar o looper rodar: a tela fica no primeiro quadro da animação. */
    private fun click(label: String) {
        val t = all().filterIsInstance<TextView>().firstOrNull { it.text.toString().contains(label) }
            ?: throw AssertionError("'$label' não está na tela")
        var v: View? = t
        while (v != null && !v.isClickable) v = v.parent as? View
        v!!.performClick()
    }

    private fun tap(label: String) { click(label); idle() }

    private fun assertSettled() {
        val c = content()
        assertEquals(0f, c.translationX, 0.01f)
        assertEquals(1f, c.alpha, 0.01f)
        for (b in blocks()) {
            assertEquals("bloco ${b.javaClass.simpleName} transparente", 1f, b.alpha, 0.01f)
            assertEquals("bloco ${b.javaClass.simpleName} deslocado", 0f, b.translationY, 0.01f)
        }
        for (f in bars()) assertEquals(f.fraction, f.shown, 0.001f)
    }

    private fun withProgram(): FitKing {
        val fit = activity.fit
        val a = fit.currentAnswers()
        Questionnaire.selectSex(a, Sex.MALE)
        a.consent = true
        a.primaryGoal = Goal.HYPERTROPHY
        a.experience = ExperienceLevel.YEARS_1_TO_2
        Questionnaire.selectEnvironment(a, fit.kb, EnvironmentId("full_gym"))
        Questionnaire.applyPreset(a, Questionnaire.DAY_PRESETS[0].second)
        a.activity = ActivityLevel.MODERATE
        Questionnaire.safetyQuestions(fit.kb, a).forEach { a.safety[it.id] = false }
        fit.submit(a)
        return fit
    }

    private fun pill(label: String): View =
        ((all().first { it.contentDescription == label && it.isClickable } as ViewGroup).getChildAt(0) as FrameLayout).getChildAt(0)

    @Test fun `push e pop animam e terminam no estado final`() {
        activity.push(QuestionnaireScreen(activity.fit.currentAnswers()))
        assertTrue("conteúdo entra da direita", content().translationX > 0f)
        assertEquals("primeiro bloco começa invisível", 0f, blocks().first().alpha, 0.01f)
        assertTrue("barra cresce do zero na navegação", bars().first().shown < bars().first().fraction)
        settle()
        assertSettled()

        activity.pop()
        assertTrue("ao voltar, entra da esquerda", content().translationX < 0f)
        settle()
        assertSettled()
    }

    @Test fun `refresh simples nao reinicia a entrada`() {
        activity.push(AboutScreen())
        settle()
        assertSettled()
        activity.current!!.refresh()
        assertSettled() // sem avançar o relógio: nada voltou ao estado inicial da animação
        idle()
        assertSettled()
    }

    @Test fun `pagina do questionario desliza na direcao certa`() {
        assertTrue(activity.current is QuestionnaireScreen)
        click("Li e concordo") // refresh: sem animar a tela
        assertSettled()
        idle()
        click("Começar")
        assertTrue("avançar entra da direita", content().translationX > 0f)
        settle()
        assertSettled()
        click("Voltar")
        assertTrue("voltar entra da esquerda", content().translationX < 0f)
        settle()
        assertSettled()
    }

    @Test fun `toque no stepper da retorno sem animar a tela`() {
        tap("Li e concordo"); tap("Começar")
        tap("Masculino") // avança para a idade
        settle()
        val before = content().getChildAt(0)
        click("+1")
        assertNotSame("refresh reconstrói a tela", before, content().getChildAt(0))
        assertSettled() // nenhum bloco voltou a ficar transparente, barra sem reanimar
        val plus = all().filterIsInstance<TextView>().first { it.text == "+1" }
        assertTrue("botão tocado volta com mola", plus.scaleX < 1f)
        settle()
        assertEquals(1f, plus.scaleX, 0.001f)
        assertSettled()
    }

    @Test fun `aba nova ganha o indicador animado`() {
        withProgram()
        activity.setRoot(HomeScreen())
        settle()
        assertEquals(1f, pill("Hoje").alpha, 0.01f)
        activity.switchTab(Tab.WEEK)
        assertTrue("indicador ainda abrindo", pill("Semana").alpha < 1f)
        settle()
        assertEquals(1f, pill("Semana").alpha, 0.01f)
        assertEquals(0f, pill("Hoje").alpha, 0.01f)
        assertSettled()
    }

    @Test fun `descanso mostra o anel e o resumo comemora`() {
        val fit = withProgram()
        val t = fit.todayView()!!
        val w = fit.startWorkout(t.session!!, t.sessionId, null)
        activity.setRoot(HomeScreen())
        activity.push(WorkoutScreen(fit.activeWorkout()!!))
        settle()
        click("Registrar série")
        val ring = all().filterIsInstance<RestRing>().single()
        assertTrue("anel enche do zero quando o descanso começa", ring.fraction < 0.5f)
        settle(600)
        assertEquals(1f, ring.fraction, 0.01f)
        settle(3000)
        assertSame("o segundo a segundo não reconstrói a tela", ring, all().filterIsInstance<RestRing>().single())
        assertTrue("anel esvazia com o tempo", ring.fraction < 1f && ring.fraction > 0f)
        click("+15 s")
        idle()
        activity.current!!.refresh() // ex.: tocar num chip durante o descanso
        val again = all().filterIsInstance<RestRing>().single()
        assertNotSame(ring, again)
        assertTrue("refresh não reenche o anel do zero", again.fraction > 0.5f)

        val summary = fit.finishWorkout(w.id, Perceived.ADEQUATE)
        activity.setRoot(WorkoutSummaryScreen(summary))
        assertEquals("confete por cima da tela", 1, all().filterIsInstance<ConfettiView>().size)
        val xp = all().filterIsInstance<TextView>().first { it.text.startsWith("+") }
        assertEquals("XP começa do zero", "+0", xp.text.toString())
        settle(3000)
        assertEquals("+${summary.xpGained}", xp.text.toString())
        assertTrue("confete some depois da comemoração", all().none { it is ConfettiView })
        assertSettled()
    }

    @Test fun `interruptor desligado mostra o estado final na hora`() {
        Motion.enabled = false
        activity.push(QuestionnaireScreen(activity.fit.currentAnswers()))
        assertSettled()
        activity.switchTab(Tab.HOME)
        assertSettled()
        assertEquals(1f, pill("Hoje").alpha, 0.01f)
    }
}
