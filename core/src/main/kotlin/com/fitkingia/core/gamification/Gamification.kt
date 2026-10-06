package com.fitkingia.core.gamification

import com.fitkingia.core.knowledge.GamificationRules
import com.fitkingia.core.knowledge.Rule
import java.time.LocalDate

/** Eventos que geram XP. Os pontos ficam no banco (regra `gamification.xp`). */
enum class XpEvent(val key: String, val label: String) {
    WORKOUT_COMPLETED("workout_completed", "Treino concluído"),
    MEAL_LOGGED("meal_logged", "Alimentação registrada"),
    PERSONAL_RECORD("personal_record", "Novo recorde"),
    HYDRATION_GOAL("hydration_goal", "Meta de hidratação"),
    WEEK_COMPLETED("week_completed", "Semana completa"),
    CHECK_IN("check_in", "Check-in de prontidão"),
}

data class XpStatus(val totalXp: Int, val level: Int, val xpIntoLevel: Int, val xpForNext: Int)

data class ActivityDay(val date: LocalDate)

class Gamification(private val rule: Rule<GamificationRules>) {
    fun points(event: XpEvent): Int = rule.params.points[event.key] ?: 0

    fun status(events: List<XpEvent>): XpStatus {
        val total = events.sumOf { points(it) }
        val per = rule.params.xpPerLevel
        return XpStatus(total, 1 + total / per, total % per, per - total % per)
    }

    /** 🔥 Streak: dias consecutivos com alguma atividade registrada, terminando hoje ou ontem. */
    fun streak(activeDays: Set<LocalDate>, today: LocalDate): Int {
        var day = if (today in activeDays) today else today.minusDays(1)
        var n = 0
        while (day in activeDays) { n++; day = day.minusDays(1) }
        return n
    }
}
