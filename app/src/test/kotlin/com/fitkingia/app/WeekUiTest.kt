package com.fitkingia.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.fitkingia.app.data.Graph
import com.fitkingia.app.screens.*
import com.fitkingia.app.ui.Motion
import com.fitkingia.appcore.AppClock
import com.fitkingia.appcore.DayStatus
import com.fitkingia.appcore.FitKing
import com.fitkingia.appcore.Perceived
import com.fitkingia.appcore.Questionnaire
import com.fitkingia.appcore.UserDb
import com.fitkingia.core.model.ActivityLevel
import com.fitkingia.core.model.EnvironmentId
import com.fitkingia.core.model.ExperienceLevel
import com.fitkingia.core.model.Goal
import com.fitkingia.core.model.Sex
import com.fitkingia.core.program.ProgramResult
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
import org.robolectric.shadows.ShadowDialog
import java.time.DayOfWeek
import java.time.DayOfWeek.*
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Regressões da revisão da semana, pela tela: navegar entre semanas (anterior só consulta, próxima com trocas),
 * treino perdido da semana que virou, treino em andamento, “Por que hoje?” e “Desfazer”.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WeekUiTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private var now = MONDAY_9H

    private val fit: FitKing get() = activity.fit

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

    // ------------------------------------------------------------------------------------------

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun texts(v: View = activity.window.decorView): List<TextView> = when (v) {
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        is TextView -> listOf(v)
        else -> emptyList()
    }

    private fun screenText() = texts().joinToString("\n") { it.text.toString() }

    private fun clickableAncestor(t: View): View? {
        var v: View? = t
        while (v != null && !v.isClickable) v = v.parent as? View
        return v
    }

    private fun tap(label: String) {
        val matches = texts().filter { it.text.toString().contains(label) }
        if (matches.isEmpty()) fail("'$label' não está na tela:\n${screenText()}")
        val target = matches.asSequence().mapNotNull { clickableAncestor(it) }.firstOrNull()
        assertNotNull("'$label' não é clicável", target)
        target!!.performClick()
        idle()
    }

    private fun dialogText() = texts(ShadowDialog.getLatestDialog().window!!.decorView).joinToString("\n") { it.text }

    private fun tapInDialog(label: String) {
        val t = texts(ShadowDialog.getLatestDialog().window!!.decorView).firstOrNull { it.text.toString().contains(label) }
            ?: fail("'$label' não está no diálogo:\n${dialogText()}")
        clickableAncestor(t as View)!!.performClick()
        idle()
    }

    private fun assertShows(s: String) = assertTrue("esperava '$s' na tela:\n${screenText()}", screenText().contains(s))
    private fun assertHides(s: String) = assertFalse("não esperava '$s' na tela:\n${screenText()}", screenText().contains(s))
    private fun hasText(s: String) = texts().any { it.text.toString() == s }

    /** Programa gerado por código (os toques do questionário já são cobertos no AppUiTest). */
    private fun program(days: Map<DayOfWeek, Int>, maxDays: Int? = null) {
        val a = fit.currentAnswers()
        a.consent = true
        Questionnaire.selectSex(a, Sex.MALE)
        a.age = 30
        a.primaryGoal = Goal.HYPERTROPHY
        a.experience = ExperienceLevel.YEARS_1_TO_2
        Questionnaire.selectEnvironment(a, fit.kb, EnvironmentId("full_gym"))
        Questionnaire.applyPreset(a, days)
        a.maxDays = maxDays
        a.activity = ActivityLevel.MODERATE
        Questionnaire.safetyQuestions(fit.kb, a).forEach { a.safety[it.id] = false }
        assertTrue(fit.submit(a).result is ProgramResult.Generated)
    }

    private fun trainToday() {
        val t = fit.todayView()!!
        val s = t.session!!
        val w = fit.startWorkout(s, t.sessionId, null)
        fit.logSet(w.id, s.exercises.first().exercise.id, 1, 40.0, 10, 2)
        fit.finishWorkout(w.id, Perceived.ADEQUATE)
    }

    private fun home() { activity.setRoot(HomeScreen()); idle() }

    private fun day(d: DayOfWeek, weeks: Long = 0): LocalDate = MONDAY_9H.toLocalDate().plusDays(d.value - 1L).plusWeeks(weeks)

    private fun baseKey(d: DayOfWeek) = fit.program()!!.program.sessionOn(d)?.key

    // ------------------------------------------------------------------------------------------
    // Virada de semana: o treino perdido de sábado e a semana anterior (só consulta)
    // ------------------------------------------------------------------------------------------

    @Test fun `na segunda a Home avisa do treino de sabado e a Semana mostra a semana passada`() {
        program(mapOf(MONDAY to 60, WEDNESDAY to 60, SATURDAY to 60))
        val saturday = fit.program()!!.program.sessionOn(SATURDAY)!!
        trainToday()
        now = MONDAY_9H.plusDays(2)
        trainToday()
        now = MONDAY_9H.plusWeeks(1) // segunda seguinte: sábado ficou sem treino
        home()
        assertShows("Semana passada: 1 treino ficou sem fazer")
        assertShows("Sábado 03/10: ${saturday.name}")
        assertShows("não passam para esta semana")
        assertHides("Treino de sábado não realizado") // as opções A–D são só da semana em curso

        tap("Ver semana passada")
        assertTrue(activity.current is WeekScreen)
        assertTrue(hasText("Semana passada"))
        assertShows("2 de 3 treinos feitos")
        assertShows("só consulta")
        tap("Sábado 03/10")
        assertTrue(activity.current is DayScreen)
        assertShows("Não realizado")
        assertShows(saturday.name)
        assertShows("Semana que passou: só consulta")
        assertHides("Fazer este treino hoje")
        assertHides("Opções de treino perdido")
        assertHides("Trocar com outro dia")
        activity.pop(); idle()

        // Navegação: ‹ não passa de quando o programa foi criado; › vai até a próxima semana.
        tap("‹ Anterior")
        assertTrue("a semana do programa é a primeira", hasText("Semana passada"))
        tap("Próxima ›")
        assertTrue(hasText("Esta semana"))
        assertShows("0 de 3 treinos feitos")
        tap("Próxima ›")
        assertTrue(hasText("Próxima semana"))
        assertShows("3 treinos planejados")
        tap("Próxima ›")
        assertTrue("não há semana depois da próxima", hasText("Próxima semana"))

        home()
        tap("Entendi")
        assertHides("Semana passada: 1 treino")
        assertEquals(DayStatus.MISSED, fit.week(day(SATURDAY))!!.days.last { it.day == SATURDAY }.status)
    }

    @Test fun `treino perdido da semana que virou explica que nao pode mais ser replanejado`() {
        program(mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60))
        now = MONDAY_9H.plusWeeks(1)
        val lastMonday = fit.week(day(MONDAY))!!.days.first()
        assertEquals(DayStatus.MISSED, lastMonday.status)
        activity.push(MissedScreen(lastMonday)); idle()
        assertShows("A semana virou")
        assertHides("Escolher A")
    }

    @Test fun `no domingo troca os dias da proxima semana e desfaz`() {
        program(mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60))
        val mon = baseKey(MONDAY)!!
        now = MONDAY_9H.plusDays(6).withHour(10) // domingo
        activity.switchTab(Tab.WEEK); idle()
        // Um dia desta semana que passou leva à troca na próxima semana.
        tap("Segunda 28/09")
        assertHides("Trocar com outro dia")
        tap("Mudar a ordem a partir da próxima semana")
        assertTrue(activity.current is DayScreen)
        assertShows("Trocar ")
        assertShows("Dessa semana em diante")
        tap("Só nessa semana")
        tap("Terça 06/10")
        assertShows("Semana reorganizada")
        assertEquals(mon, fit.week(day(TUESDAY, 1))!!.days[1].session?.key)
        assertNull("só nessa semana: o programa não muda", baseKey(TUESDAY))
        assertNull("esta semana não muda", fit.week()!!.plan)
        tap("Desfazer")
        assertHides("Semana reorganizada")
        assertNull(fit.week(day(MONDAY, 1))!!.plan)
        assertEquals(mon, fit.week(day(MONDAY, 1))!!.days[0].session?.key)
    }

    // ------------------------------------------------------------------------------------------
    // Treino em andamento
    // ------------------------------------------------------------------------------------------

    @Test fun `treino em andamento esconde a troca e comecar outro treino pergunta antes`() {
        program(mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60))
        home()
        tap("Começar treino")
        assertTrue(activity.current is WorkoutScreen)
        home() // “Sair e retomar depois”
        assertShows("Treino em andamento")
        assertHides("Trocar o treino de hoje")
        assertShows("conclua ou descarte antes o treino em andamento")
        activity.push(DayScreen(day(WEDNESDAY))); idle()
        assertHides("Fazer este treino hoje")
        assertShows("conclua ou descarte antes o treino em andamento")

        now = MONDAY_9H.plusDays(2) // quarta: o treino de segunda continua aberto
        val wed = fit.week()!!.days[2].session!!
        home()
        tap("Começar treino")
        assertFalse("não abre em silêncio o treino de segunda", activity.current is WorkoutScreen)
        val dialog = dialogText()
        assertTrue(dialog, dialog.contains("Retomar"))
        tapInDialog("Descartar e começar")
        assertTrue(activity.current is WorkoutScreen)
        assertEquals(wed.key, fit.activeWorkout()!!.session.key)
    }

    @Test fun `treino aberto da semana passada nao e retomado ao comecar o mesmo treino`() {
        program(mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60))
        home()
        tap("Começar treino")
        assertTrue(activity.current is WorkoutScreen)
        now = MONDAY_9H.plusWeeks(1) // segunda seguinte: o mesmo treino, e o da semana passada ficou aberto
        home()
        assertShows("começou em 28/09")
        tap("Começar treino")
        assertFalse("não retoma em silêncio o treino da semana passada", activity.current is WorkoutScreen)
        val dialog = dialogText()
        assertTrue(dialog, dialog.contains("em 28/09"))
        tapInDialog("Descartar e começar")
        assertTrue(activity.current is WorkoutScreen)
        assertEquals(now, fit.activeWorkout()!!.startedAt)
    }

    // ------------------------------------------------------------------------------------------
    // “Por que hoje?” e “Desfazer”
    // ------------------------------------------------------------------------------------------

    @Test fun `por que hoje explica o treino trazido de outro dia`() {
        program(mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60))
        val wed = fit.program()!!.program.sessionOn(WEDNESDAY)!!
        now = MONDAY_9H.plusDays(1) // terça, descanso
        home()
        tap("${wed.name} · quarta")
        assertShows("Semana reorganizada")
        assertEquals(wed.key, fit.todayView()!!.session?.key)
        tap("Por que hoje?")
        assertTrue(activity.current is WhyScreen)
        assertShows("Por que ${wed.name} hoje?")
        assertShows("no programa, fica na quarta")
        assertHides("Por que não treino")
    }

    @Test fun `fazer hoje sem dia livre pode ser desfeito pela Home`() {
        program(mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60))
        val wed = baseKey(WEDNESDAY)
        now = MONDAY_9H.plusDays(2) // quarta: segunda ficou sem treino
        activity.switchTab(Tab.WEEK); idle()
        tap("Segunda 28/09")
        tap("Fazer este treino hoje")
        assertTrue(activity.current is HomeScreen)
        assertShows("ficou fora desta semana")
        tap("Desfazer")
        assertHides("Semana reorganizada")
        assertEquals(wed, fit.todayView()!!.session?.key)
        assertShows("Treino de segunda não realizado")
        assertNull(fit.week()!!.plan)
    }

    @Test fun `troca permanente pela tela do dia pode ser desfeita`() {
        program(mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60))
        val before = fit.program()!!.program.sessions.map { it.key to it.day }
        activity.switchTab(Tab.WEEK); idle()
        tap("Sexta 02/10")
        tap("Trocar com outro dia")
        tap("Todas as semanas")
        tap("Sábado 03/10")
        assertShows("Nas próximas semanas")
        assertNotEquals(before, fit.program()!!.program.sessions.map { it.key to it.day })
        tap("Desfazer")
        assertHides("Semana reorganizada")
        assertEquals(before, fit.program()!!.program.sessions.map { it.key to it.day })
    }

    // ------------------------------------------------------------------------------------------
    // Treino perdido já remarcado
    // ------------------------------------------------------------------------------------------

    @Test fun `treino remarcado e feito nao oferece fazer hoje de novo`() {
        program(mapOf(MONDAY to 60, TUESDAY to 60, WEDNESDAY to 60, FRIDAY to 60, SATURDAY to 60), maxDays = 3)
        assertNull("terça livre", baseKey(TUESDAY))
        now = MONDAY_9H.plusDays(1) // terça
        fit.doToday(day(MONDAY))
        trainToday()
        now = MONDAY_9H.plusDays(3) // quinta
        activity.push(DayScreen(day(MONDAY))); idle()
        assertShows("Opção A escolhida · remarcado para terça (feito)")
        assertShows("Este treino já foi feito na terça")
        assertHides("Fazer este treino hoje")
    }

    @Test fun `treino remarcado que tambem passou pode ser feito hoje`() {
        program(mapOf(MONDAY to 60, TUESDAY to 60, WEDNESDAY to 60, FRIDAY to 60, SATURDAY to 60), maxDays = 3)
        val mon = baseKey(MONDAY)
        now = MONDAY_9H.plusDays(1) // terça
        fit.doToday(day(MONDAY))
        now = MONDAY_9H.plusDays(3) // quinta: terça também passou sem treino
        activity.push(DayScreen(day(MONDAY))); idle()
        assertShows("também não realizado")
        tap("Fazer este treino hoje")
        assertTrue(activity.current is HomeScreen)
        assertEquals(mon, fit.todayView()!!.session?.key)
        assertHides("Treino de terça não realizado")
        assertShows("Treino de quarta não realizado")
    }

    companion object {
        /** Segunda-feira. */
        val MONDAY_9H: LocalDateTime = LocalDateTime.of(2026, 9, 28, 9, 0)
    }
}
