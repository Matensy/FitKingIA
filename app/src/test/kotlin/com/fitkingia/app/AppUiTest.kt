package com.fitkingia.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.fitkingia.app.data.Graph
import com.fitkingia.app.screens.*
import com.fitkingia.appcore.AppClock
import com.fitkingia.appcore.FitKing
import com.fitkingia.appcore.UserDb
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
import java.time.LocalDateTime

/**
 * Roda a Activity real (Robolectric) com o mesmo app do celular sobre SQLite em memória e
 * percorre o fluxo só com toques: questionário → programa → treino → registros → telas de Mais.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AppUiTest {
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private var now = LocalDateTime.of(2026, 9, 28, 9, 0) // segunda-feira

    @Before fun setUp() {
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

    private fun texts(v: View = activity.window.decorView): List<TextView> = when (v) {
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        is TextView -> listOf(v)
        else -> emptyList()
    }

    private fun screenText() = texts().joinToString("\n") { it.text.toString() }

    /** Toca no primeiro elemento clicável cujo texto contém [label] (ou num ancestral clicável). */
    private fun tap(label: String) {
        val matches = texts().filter { it.text.toString().contains(label) }
        if (matches.isEmpty()) fail("'$label' não está na tela:\n${screenText()}")
        val target = matches.asSequence().mapNotNull { t ->
            var v: View? = t
            while (v != null && !v.isClickable) v = v.parent as? View
            v
        }.firstOrNull()
        assertNotNull("'$label' não é clicável", target)
        target!!.performClick()
        idle()
    }

    /** Toca no [nth]-ésimo chip com texto exatamente igual a [label]. */
    private fun tapExact(label: String, nth: Int) {
        val all = texts().filter { it.text.toString() == label && it.isClickable }
        assertTrue("chip '$label' nº $nth não encontrado", all.size > nth)
        all[nth].performClick()
        idle()
    }

    private fun assertShows(s: String) = assertTrue("esperava '$s' na tela:\n${screenText()}", screenText().contains(s, ignoreCase = true))

    private fun answerQuestionnaire() {
        assertTrue("tela inicial: ${activity.current}\n${screenText()}", activity.current is QuestionnaireScreen)
        tap("Li e concordo"); tap("Começar")
        tap("Masculino")                  // avança sozinho
        tap("+1"); tap("Próximo")         // idade
        tap("Próximo")                    // altura
        tap("+½"); tap("Próximo")         // peso
        tap("Informar cintura"); tap("Próximo")
        tap("Hipertrofia"); tap("Próximo")
        tap("Pular")                      // objetivo secundário
        tap("Equilibrado")                // prioridade por região
        tap("1–2 anos")
        tap("Academia completa")
        tap("Próximo")                    // equipamentos
        tap("Seg/Qua/Sex · 60 min"); tap("Próximo")
        tap("O motor decide")
        tap("Não pratico"); tap("Próximo")
        tap("Moderadamente ativo")
        // Triagem: tocar "Não" em todas as perguntas.
        while (texts().any { it.text == "Não" && it.isClickable && !it.contentDescription.toString().contains("selecionado") }) {
            texts().first { it.text == "Não" && it.isClickable && !it.contentDescription.toString().contains("selecionado") }.performClick(); idle()
        }
        tap("Próximo")
        tap("O motor escolhe")
        tap("Próximo")                    // hidratação
        assertShows("Resumo")
        tap("Gerar meu programa")
    }

    @Test fun `questionario so de toque ate o treino concluido`() {
        answerQuestionnaire()
        assertTrue(activity.current is ProgramReadyScreen)
        assertShows("Programa pronto")
        tap("Ver meu treino de hoje")
        assertTrue(activity.current is HomeScreen)
        assertShows("Treino de hoje")

        // Ajustes por toque.
        tap("Pouco tempo")
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        texts(dialog.window!!.decorView).first { it.text == "30 min" }.performClick(); idle()
        assertShows("limite de 30 min")
        tap("Pouco tempo")
        texts(org.robolectric.shadows.ShadowDialog.getLatestDialog().window!!.decorView).first { it.text == "Tempo normal" }.performClick(); idle()
        assertFalse(screenText().contains("limite de 30 min"))

        tap("Como estou")
        assertTrue(activity.current is ReadinessScreen)
        tap("Normal")
        tapExact("7", 0)   // energia
        tapExact("3", 1)   // dor muscular
        tapExact("3", 2)   // estresse
        tapExact("8", 3)   // motivação
        tap("Ajustar meu treino")
        assertTrue(activity.current is HomeScreen)
        assertShows("Recovery Score")

        // Treino: registrar todas as séries do primeiro exercício e concluir.
        tap("Começar treino")
        assertTrue(activity.current is WorkoutScreen)
        assertShows("Série 1 de")
        repeat(5) { if (screenText().contains("Registrar série")) tap("Registrar série") }
        assertShows("Descanso")
        tap("Pular")
        tap("Finalizar treino")
        tap("Adequado")
        tap("Concluir treino")
        assertTrue(activity.current is WorkoutSummaryScreen)
        assertShows("XP ganho")
        tap("Voltar ao início")
        assertShows("Treino de hoje concluído")

        // Água e sono por toque.
        tap("+500 ml")
        assertShows("500 /")
        tap("7 h"); tap("Boa")
        assertShows("Sono: 7 h")
    }

    @Test fun `prioridade escolhida pela Home refaz o programa com a checagem`() {
        answerQuestionnaire()
        assertShows("Seu objetivo × seu treino")
        assertShows("Treino equilibrado")
        tap("Ver meu treino de hoje")
        assertShows("Quer focar numa parte do corpo?")
        tap("Escolher")
        assertTrue(activity.current is QuestionnaireScreen)
        assertShows("Quer dar prioridade a alguma parte do corpo?")
        tap("Glúteos")
        tap("Próximo")                    // volta direto ao resumo
        assertShows("Resumo")
        tap("Gerar meu programa")
        assertTrue(activity.current is ProgramReadyScreen)
        assertShows("Seu objetivo × seu treino")
        assertShows("Séries focadas por semana")
        assertShows("3 de 3 (mínimo 3)")
        tap("Ver meu treino de hoje")
        assertFalse(screenText().contains("Quer focar numa parte do corpo?"))
    }

    @Test fun `todas as abas e telas de Mais abrem sem erro`() {
        answerQuestionnaire()
        tap("Ver meu treino de hoje")
        for (tab in Tab.values()) {
            activity.switchTab(tab); idle()
            assertFalse(screenText().contains("Algo deu errado"))
        }
        activity.switchTab(Tab.WEEK); idle()
        assertShows("Por que esta divisão?")
        tap("Por que esta divisão?"); assertShows("Decisões do motor"); activity.pop(); idle()

        val screens = listOf(NutritionScreen(), WaterScreen(), SleepScreen(), CardioScreen(), MobilityScreen(), SupplementsScreen(),
            EvidenceScreen(), ToolsScreen(), SimulatorScreen(), ProfileScreen(), AboutScreen(), BodyLogScreen(), PhotosScreen())
        for (s in screens) {
            activity.push(s); idle()
            assertFalse("${s.title}:\n${screenText()}", screenText().contains("Algo deu errado"))
            activity.pop(); idle()
        }

        // Sessão → exercício → por que → trocar.
        val session = activity.fit.program()!!.program.sessions.first()
        activity.push(SessionScreen(session, null, null)); idle()
        tap(session.exercises.first().exercise.name)
        assertTrue(activity.current is ExerciseScreen)
        tap("Por que isso?"); assertShows("Por que"); activity.pop(); idle()
        tap("Trocar exercício"); assertTrue(activity.current is SwapScreen)
        tap("pts")
        assertFalse(activity.fit.program()!!.program.sessions.first().exercises.any { it.exercise.id == session.exercises.first().exercise.id })

        // Simulador e ferramentas por toque.
        activity.push(SimulatorScreen()); idle()
        tap("3 dias"); tap("5 dias"); tap("Comparar")
        assertShows("Treinos/semana")
        activity.push(NutritionScreen()); idle()
        tap("Arroz"); tap("1½")
        assertShows("Hoje")
    }

    @Test fun `treino perdido mostra opcoes A a D`() {
        answerQuestionnaire()
        tap("Ver meu treino de hoje")
        now = now.plusDays(2) // quarta, segunda ficou sem treino
        activity.switchTab(Tab.HOME); idle()
        assertShows("não realizado")
        tap("Ver opções")
        assertTrue(activity.current is MissedScreen)
        assertShows("A) "); assertShows("D) ")
        tap("Escolher C")
        activity.switchTab(Tab.WEEK); idle()
        assertShows("Opção C")
    }

    @Test fun `triagem com sinal de alerta nao gera treino`() {
        val fit = activity.fit
        val a = fit.currentAnswers()
        // Preenche por código o mesmo que os toques fariam, com dor no peito = sim.
        com.fitkingia.appcore.Questionnaire.selectSex(a, com.fitkingia.core.model.Sex.FEMALE)
        a.consent = true; a.primaryGoal = com.fitkingia.core.model.Goal.FAT_LOSS
        a.experience = com.fitkingia.core.model.ExperienceLevel.NONE
        com.fitkingia.appcore.Questionnaire.selectEnvironment(a, fit.kb, com.fitkingia.core.model.EnvironmentId("home"))
        com.fitkingia.appcore.Questionnaire.applyPreset(a, com.fitkingia.appcore.Questionnaire.DAY_PRESETS[0].second)
        a.activity = com.fitkingia.core.model.ActivityLevel.LIGHT
        com.fitkingia.appcore.Questionnaire.safetyQuestions(fit.kb, a).forEach { a.safety[it.id] = it.id == "chest_pain" }
        activity.setRoot(ProgramReadyScreen(fit.submit(a))); idle()
        assertShows("Procure um profissional")
        tap("Ir para o início")
        assertShows("Sem programa de treino")
    }
}
