package com.fitkingia.knowledge

import com.fitkingia.core.model.*
import com.fitkingia.core.nutrition.MealParser
import com.fitkingia.core.program.ProgramGenerator
import com.fitkingia.core.program.ProgramResult
import com.fitkingia.core.safety.SafetyScreening
import com.fitkingia.core.safety.ScreeningStatus
import java.time.DayOfWeek
import kotlin.test.*

class SafetyAndNutritionRealTest {
    private val kb = BundledKnowledge.load()
    private val profile = UserProfile(
        name = "T", age = 35, sex = Sex.FEMALE, heightCm = 160.0, weightKg = 60.0, primaryGoal = Goal.FAT_LOSS,
        experience = ExperienceLevel.NONE, equipment = emptySet(), availability = listOf(DayAvailability(DayOfWeek.MONDAY, 45)),
    )
    private fun answers(vararg yes: String) = SafetyScreening(kb).questionsFor(profile.sex).associate { it.id to (it.id in yes) }

    @Test fun `cada red flag do banco bloqueia a prescrição`() {
        val blockers = kb.safetyQuestions.filter { it.outcomeIfYes.name == "BLOCK" && (it.appliesTo == null || it.appliesTo == profile.sex) }
        assertTrue(blockers.map { it.id }.containsAll(listOf("chest_pain", "fainting", "medical_restriction", "recent_surgery", "pregnancy")))
        for (q in blockers) {
            val r = SafetyScreening(kb).evaluate(profile, answers(q.id))
            assertEquals(ScreeningStatus.REFER, r.status, q.id)
            assertIs<ProgramResult.Refused>(ProgramGenerator(kb).generate(profile, r))
        }
    }

    @Test fun `respostas negativas liberam`() {
        assertEquals(ScreeningStatus.CLEAR, SafetyScreening(kb).evaluate(profile, answers()).status)
    }

    @Test fun `Hoje comi arroz, feijão, frango e banana`() {
        val m = MealParser(kb).parse("Hoje comi arroz, feijão, frango e banana.")
        assertEquals(listOf("arroz_branco_cozido", "feijao_carioca_cozido", "frango_peito_grelhado", "banana_prata"), m.items.map { it.food.id.value })
        assertTrue(m.unmatched.isEmpty())
        assertTrue(m.kcal in 400.0..500.0)
    }

    @Test fun `alimentos vêm da TACO e kcal bate com macronutrientes`() {
        assertTrue(kb.foods.all { it.sourceId.value == "taco_2011" })
        assertTrue(KnowledgeValidator.validate(kb).none { it.where.startsWith("food") })
    }

    @Test fun `suplementos são informativos e citam fontes`() {
        assertTrue(kb.supplements.isNotEmpty())
        for (s in kb.supplements) {
            assertTrue(s.sourceIds.isNotEmpty())
            assertTrue("não recomendação individual" in s.cautions, s.id.value)
        }
    }
}
