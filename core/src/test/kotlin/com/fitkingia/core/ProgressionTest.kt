package com.fitkingia.core

import com.fitkingia.core.Fixtures.TODAY
import com.fitkingia.core.Fixtures.x
import com.fitkingia.core.knowledge.RepPrescription
import com.fitkingia.core.progression.*
import kotlin.test.*

class ProgressionEngineTest {
    private val engine = ProgressionEngine(Fixtures.kb)
    private val bench = Fixtures.kb.exercise(x("bench_press"))
    private val presc = RepPrescription(8..12, 2, 120..180, "controlado")

    private fun log(weeksAgo: Long, load: Double, vararg reps: Int, rir: Int? = 2) =
        ExerciseLog(bench.id, TODAY.minusWeeks(weeksAgo), reps.map { SetLog(load, it, rir) })

    @Test fun `sem histórico pede carga autosselecionada`() {
        val s = engine.suggest(bench, presc, 3, emptyList())
        assertEquals(ProgressionAction.START, s.action)
        assertNull(s.suggestedLoadKg)
    }

    @Test fun `12-12-12 com RIR adequado aumenta a carga e volta ao início da faixa`() {
        val s = engine.suggest(bench, presc, 3, listOf(log(0, 60.0, 12, 12, 12)))
        assertEquals(ProgressionAction.INCREASE_LOAD, s.action)
        assertEquals(62.5, s.suggestedLoadKg)
        assertEquals(listOf(8, 8, 8), s.repTargets)
        assertTrue(s.explanation.text.startsWith("Sugestão baseada nas suas sessões anteriores"))
    }

    @Test fun `muito longe da falha dá salto duplo`() {
        val s = engine.suggest(bench, presc, 3, listOf(log(0, 60.0, 12, 12, 12, rir = 5)))
        assertEquals(65.0, s.suggestedLoadKg)
    }

    @Test fun `topo da faixa mas perto demais da falha consolida`() {
        val s = engine.suggest(bench, presc, 3, listOf(log(0, 60.0, 12, 12, 12, rir = 0)))
        assertEquals(ProgressionAction.CONSOLIDATE, s.action)
        assertEquals(60.0, s.suggestedLoadKg)
    }

    @Test fun `dentro da faixa mantém e pede mais uma repetição`() {
        val s = engine.suggest(bench, presc, 3, listOf(log(0, 60.0, 10, 10, 9)))
        assertEquals(ProgressionAction.HOLD, s.action)
        assertEquals(listOf(11, 11, 10), s.repTargets)
    }

    @Test fun `duas sessões seguidas abaixo da faixa reduzem a carga`() {
        val once = engine.suggest(bench, presc, 3, listOf(log(1, 70.0, 10, 9, 8), log(0, 70.0, 7, 6, 6)))
        assertEquals(ProgressionAction.HOLD, once.action)
        val twice = engine.suggest(bench, presc, 3, listOf(log(1, 70.0, 7, 7, 6), log(0, 70.0, 7, 6, 6)))
        assertEquals(ProgressionAction.DECREASE_LOAD, twice.action)
        assertEquals(67.5, twice.suggestedLoadKg)
    }

    @Test fun `aprende o padrão do usuário com a carga atual`() {
        val s = engine.suggest(bench, presc, 3, listOf(log(1, 62.5, 10, 10, 9), log(0, 62.5, 11, 10, 10)))
        assertEquals("Você normalmente completa 9–11 repetições com 62,5 kg (2 sessões).", s.pattern)
    }

    @Test fun `exercício em tempo progride por duração`() {
        val plank = Fixtures.kb.exercise(x("plank"))
        val s = engine.suggest(plank, presc.copy(holdSeconds = 20..45), 3, listOf(ExerciseLog(plank.id, TODAY, listOf(SetLog(0.0, 1)))))
        assertTrue("aumente a duração" in s.message)
    }
}

class TrendsAndRecordsTest {
    private val bench = x("bench_press")
    private val d = TODAY.minusWeeks(4)
    // Exemplo da visão: 60×10 → 60×12 → 62,5×9 → 62,5×11
    private val history = listOf(
        ExerciseLog(bench, d, listOf(SetLog(60.0, 10))),
        ExerciseLog(bench, d.plusWeeks(1), listOf(SetLog(60.0, 12))),
        ExerciseLog(bench, d.plusWeeks(2), listOf(SetLog(62.5, 9))),
        ExerciseLog(bench, d.plusWeeks(3), listOf(SetLog(62.5, 11))),
    )

    @Test fun `reconhece progresso mesmo com carga e repetições alternando`() {
        val t = Trends.of(bench, history)
        assertEquals(TrendDirection.IMPROVING, t.direction)
        assertEquals(0, t.decliningStreak)
    }

    @Test fun `queda persistente vira sequência de declínio`() {
        val down = (0..4).map { ExerciseLog(bench, d.plusWeeks(it.toLong()), listOf(SetLog(80.0 - it * 2.5, 8))) }
        val t = Trends.of(bench, down)
        assertEquals(TrendDirection.DECLINING, t.direction)
        assertEquals(4, t.decliningStreak)
        val advice = DeloadAdvisor(Fixtures.kb).advise(listOf(t), recentReadiness = listOf(40, 45, 50))
        assertTrue(advice.recommended)
        assertTrue("evidência sobre deloads programados é limitada" in advice.explanation.text)
    }

    @Test fun `sem prontidão baixa e com poucos exercícios em queda não sugere deload`() {
        val t = Trends.of(bench, history)
        assertFalse(DeloadAdvisor(Fixtures.kb).advise(listOf(t), listOf(80, 85)).recommended)
    }

    @Test fun `detecta PRs de carga, repetições, 1RM estimado e volume`() {
        val new = ExerciseLog(bench, TODAY, listOf(SetLog(65.0, 8), SetLog(62.5, 12), SetLog(62.5, 11)))
        val types = PersonalRecords.detect(Fixtures.kb, new, history).map { it.type }.toSet()
        assertEquals(setOf(PrType.HEAVIEST_LOAD, PrType.MOST_REPS_AT_LOAD, PrType.BEST_E1RM, PrType.BEST_SESSION_VOLUME), types)
    }

    @Test fun `primeira sessão é linha de base, sem PR`() {
        assertTrue(PersonalRecords.detect(Fixtures.kb, history.first(), emptyList()).isEmpty())
    }
}
