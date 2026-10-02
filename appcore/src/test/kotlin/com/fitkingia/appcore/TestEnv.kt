package com.fitkingia.appcore

import com.fitkingia.core.model.*
import com.fitkingia.knowledge.BundledKnowledge
import com.fitkingia.knowledge.KnowledgeDbBuilder
import com.fitkingia.knowledge.sql.JdbcSqlDatabase
import java.time.DayOfWeek
import java.time.LocalDateTime

class MutableClock(var now: LocalDateTime) : AppClock {
    override fun now() = now
}

/** App completo sobre SQLite em memória — o mesmo caminho de código do Android, com JDBC. */
class TestEnv(start: LocalDateTime = MONDAY_9H) {
    val kb = BundledKnowledge.load()
    val db = JdbcSqlDatabase.inMemory().also { UserDb.migrate(it, KnowledgeDbBuilder.schema("user.sql")) }
    val clock = MutableClock(start)
    val app = FitKing(kb, db, clock)

    fun at(dt: LocalDateTime) { clock.now = dt }

    companion object {
        /** Segunda-feira. */
        val MONDAY_9H: LocalDateTime = LocalDateTime.of(2026, 9, 28, 9, 0)

        /** Perfil típico respondido só com toques: academia completa, seg/qua/sex 60 min. */
        fun answers(
            env: String = "full_gym",
            experience: ExperienceLevel = ExperienceLevel.YEARS_1_TO_2,
            goal: Goal = Goal.HYPERTROPHY,
            days: Map<DayOfWeek, Int> = mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.WEDNESDAY to 60, DayOfWeek.FRIDAY to 60),
            sex: Sex = Sex.MALE,
            kb: com.fitkingia.core.knowledge.KnowledgeBase = BundledKnowledge.load(),
        ): Answers {
            val a = Answers(consent = true)
            Questionnaire.selectSex(a, sex)
            a.age = 30
            a.primaryGoal = goal
            a.experience = experience
            Questionnaire.selectEnvironment(a, kb, EnvironmentId(env))
            Questionnaire.applyPreset(a, days)
            a.activity = ActivityLevel.MODERATE
            Questionnaire.safetyQuestions(kb, a).forEach { a.safety[it.id] = false }
            return a
        }
    }
}
