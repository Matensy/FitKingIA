package com.fitkingia.core

import com.fitkingia.core.Fixtures.x
import com.fitkingia.core.body.BodyMetrics
import com.fitkingia.core.body.WeightEntry
import com.fitkingia.core.body.WeightTrend
import com.fitkingia.core.gamification.Gamification
import com.fitkingia.core.gamification.XpEvent
import com.fitkingia.core.hydration.HydrationEngine
import com.fitkingia.core.hydration.HydrationInput
import com.fitkingia.core.hydration.WaterLog
import com.fitkingia.core.model.*
import com.fitkingia.core.nutrition.EnergyEstimator
import com.fitkingia.core.nutrition.MealParser
import com.fitkingia.core.progression.OneRepMax
import com.fitkingia.core.tools.PlateCalculator
import com.fitkingia.core.tools.WarmupGenerator
import kotlin.test.*

class PlateCalculatorTest {
    @Test fun `82,5 kg com barra de 20 usa 25 + 5 + 1,25 por lado`() {
        val r = PlateCalculator.compute(82.5)
        assertEquals(listOf(25.0, 5.0, 1.25), r.perSide)
        assertTrue(r.exact)
        assertEquals(82.5, r.achievedKg)
    }

    @Test fun `alvo menor ou igual à barra não usa anilhas`() {
        assertEquals(emptyList(), PlateCalculator.compute(20.0).perSide)
    }

    @Test fun `alvo impossível retorna o mais próximo abaixo`() {
        val r = PlateCalculator.compute(61.0)
        assertFalse(r.exact)
        assertEquals(60.0, r.achievedKg)
    }

    @Test fun `respeita a quantidade de anilhas disponível`() {
        val r = PlateCalculator.compute(80.0, platePairs = mapOf(10.0 to 3, 5.0 to 2))
        assertEquals(listOf(10.0, 10.0, 10.0), r.perSide)
    }
}

class OneRepMaxTest {
    @Test fun `Epley e Brzycki para 100 kg x 5`() {
        assertEquals(116.7, Math.round(OneRepMax.epley(100.0, 5) * 10) / 10.0)
        assertEquals(112.5, Math.round(OneRepMax.brzycki(100.0, 5) * 10) / 10.0)
    }

    @Test fun `RIR soma repetições até a falha e muitas repetições perdem confiabilidade`() {
        val e = OneRepMax.estimate(75.0, 8, rir = 2)
        assertEquals(100.0, e.epley)
        assertTrue(e.reliable)
        assertFalse(OneRepMax.estimate(50.0, 15).reliable)
    }

    @Test fun `uma repetição é o próprio 1RM`() = assertEquals(140.0, OneRepMax.epley(140.0, 1))
}

class WarmupTest {
    @Test fun `aquecimento com barra sobe progressivamente sem passar da carga de trabalho`() {
        val bench = Fixtures.kb.exercise(x("bench_press"))
        val sets = WarmupGenerator.forWorkingLoad(bench, 100.0)
        assertEquals(20.0, sets.first().loadKg)
        assertTrue(sets.zipWithNext().all { (a, b) -> b.loadKg > a.loadKg })
        assertTrue(sets.all { it.loadKg < 100.0 })
    }

    @Test fun `peso corporal recebe apenas série leve`() {
        assertEquals(1, WarmupGenerator.forWorkingLoad(Fixtures.kb.exercise(x("push_up")), 0.0).size)
    }
}

class BodyAndHydrationTest {
    private val rules = Fixtures.ruleSet

    @Test fun `IMC e relação cintura-altura com ressalvas`() {
        val bm = BodyMetrics(rules.bodyMetrics)
        val bmi = bm.bmi(70.0, 175.0)
        assertEquals(22.9, bmi.value)
        assertEquals("adequado", bmi.label)
        assertTrue("não diagnóstico" in bmi.explanation.text)
        assertEquals("aumentada", bm.waistToHeight(90.0, 175.0).label)
        assertEquals("abaixo do ponto de atenção", bm.waistToHeight(80.0, 175.0).label)
    }

    @Test fun `oscilação diária não vira conclusão sobre gordura`() {
        val r = WeightTrend(rules.weightTrend).analyze(listOf(WeightEntry(Fixtures.TODAY.minusDays(1), 70.2), WeightEntry(Fixtures.TODAY, 71.0)))
        assertEquals(0.8, r.latestChangeKg!!, 1e-9)
        val msg = r.messages.single().text
        assertTrue("não permitem concluir" in msg)
        assertNull(r.weeklyRateKg, "duas pesagens não bastam para tendência")
    }

    @Test fun `tendência semanal pela média móvel após 2 semanas`() {
        val entries = (0..20).map { WeightEntry(Fixtures.TODAY.minusDays(20L - it), 80.0 - it * 0.1) }
        val r = WeightTrend(rules.weightTrend).analyze(entries)
        assertTrue(r.weeklyRateKg!! < -0.5 && r.weeklyRateKg!! > -0.9)
    }

    @Test fun `meta de água = ml por kg + treino`() {
        val h = HydrationEngine(rules.hydration)
        val t = h.target(HydrationInput(70.0, exerciseMinutes = 60, sweat = SweatLevel.MODERATE))
        assertEquals(2450, t.baseMl)
        assertEquals(700, t.exerciseMl)
        assertEquals(3150, t.totalMl)
        val p = h.progress(t, listOf(WaterLog(Fixtures.TODAY, 250), WaterLog(Fixtures.TODAY, 500), WaterLog(Fixtures.TODAY.minusDays(1), 900)), Fixtures.TODAY)
        assertEquals(750, p.consumedMl)
        assertEquals("💧 750 / 3.150 ml", p.display)
    }
}

class NutritionTest {
    @Test fun `Mifflin-St Jeor reproduz a equação publicada`() {
        val est = EnergyEstimator(Fixtures.ruleSet.nutrition)
        assertEquals(1762.5, est.bmr(Sex.MALE, 78.0, 178.0, 27))
        assertEquals(1596.5, est.bmr(Sex.FEMALE, 78.0, 178.0, 27))
    }

    @Test fun `meta em déficit nunca fica abaixo da TMB`() {
        val est = EnergyEstimator(Fixtures.ruleSet.nutrition)
        val t = est.targets(Fixtures.profile(goal = Goal.FAT_LOSS))
        assertEquals(EnergyGoal.DEFICIT, t.energyGoal)
        assertTrue(t.targetKcal >= t.bmrKcal)
        assertEquals(109..156, t.proteinG)
    }

    @Test fun `parser de refeição reconhece itens, quantidades e o que não conhece`() {
        val m = MealParser(Fixtures.kb).parse("Hoje comi 200 g de arroz, 2 ovos e pizza.")
        assertEquals(listOf("arroz", "ovo"), m.items.map { it.food.id.value })
        assertEquals(200.0, m.items[0].grams)
        assertEquals(100.0, m.items[1].grams)
        assertEquals(listOf("pizza"), m.unmatched)
        assertEquals(256.0 + 146.0, m.kcal, 1e-6)
        assertTrue("fibras" in m.incomplete, "ovo sem fibra verificada deixa o total de fibras parcial")
    }
}

class GamificationTest {
    private val g = Gamification(Fixtures.ruleSet.gamification)

    @Test fun `XP e nível`() {
        val s = g.status(List(9) { XpEvent.WORKOUT_COMPLETED } + XpEvent.PERSONAL_RECORD)
        assertEquals(1100, s.totalXp)
        assertEquals(2, s.level)
        assertEquals(900, s.xpForNext)
    }

    @Test fun `streak conta dias consecutivos até hoje ou ontem`() {
        val t = Fixtures.TODAY
        assertEquals(3, g.streak(setOf(t, t.minusDays(1), t.minusDays(2), t.minusDays(4)), t))
        assertEquals(2, g.streak(setOf(t.minusDays(1), t.minusDays(2)), t))
        assertEquals(0, g.streak(setOf(t.minusDays(3)), t))
    }
}
