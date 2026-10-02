package com.fitkingia.core.planning

import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.DayAvailability
import com.fitkingia.core.model.SlotRole
import com.fitkingia.core.model.UserProfile
import com.fitkingia.core.program.*
import com.fitkingia.core.session.SessionFitter
import java.time.DayOfWeek

data class MissedOption(
    val key: Char,
    val title: String,
    val available: Boolean,
    val description: String,
    /** Sessões restantes da semana se esta opção for escolhida. */
    val remainingWeek: List<PlannedSession>,
    val details: List<String> = emptyList(),
)

data class MissedWorkoutReport(val message: String, val options: List<MissedOption>)

/**
 * Sistema de "missed workout": tom neutro, nada de culpa. Oferece A–D e mostra o efeito
 * de cada escolha no restante da semana. Considera a semana de segunda a domingo.
 */
class MissedWorkoutPlanner(private val kb: KnowledgeBase) {
    private val fitter = SessionFitter(kb)
    private val scheduler = WeekScheduler(kb)
    private val calc = VolumeCalculator(kb.ruleSet.volume.params)
    private val minMinutes = kb.ruleSet.frequency.params.minSessionMinutes

    fun options(program: Program, profile: UserProfile, missedDay: DayOfWeek, today: DayOfWeek): MissedWorkoutReport {
        val missed = program.sessionOn(missedDay) ?: error("Não há sessão planejada em ${missedDay.pt()}")
        require(today > missedDay) { "hoje deve ser depois do dia perdido" }
        val upcoming = program.sessions.filter { it.day != null && it.day >= today }
        val freeDays = profile.availability
            .filter { it.day >= today && it.minutes >= minMinutes && program.sessionOn(it.day) == null }
            .sortedBy { it.day }
        val message = "Você não realizou o treino ${missed.name} de ${missedDay.pt()}."
        return MissedWorkoutReport(message, listOf(
            move(missed, upcoming, freeDays),
            merge(program, missed, upcoming),
            ignore(program, missed, upcoming),
            recalc(program, missed, upcoming, profile, today),
        ))
    }

    private fun move(missed: PlannedSession, upcoming: List<PlannedSession>, free: List<DayAvailability>): MissedOption {
        val day = free.firstOrNull()
            ?: return MissedOption('A', "Mover para outro dia", false, "Não há dia livre e disponível até domingo.", upcoming)
        val fit = fitter.fit(missed, day.minutes)
        val moved = fit.session.copy(day = day.day)
        val week = (upcoming + moved).sortedBy { it.day }
        val details = fit.changes.map { it.toString() }.toMutableList()
        val neighbor = upcoming.firstOrNull { it.day == day.day.plus(1) || it.day == day.day.minus(1) }
        if (neighbor != null && overlap(moved, neighbor) > 6) details += "Atenção: ${neighbor.name} em dia vizinho trabalha músculos parecidos."
        return MissedOption('A', "Mover para ${day.day.pt()}", true, "${missed.name} passa para ${day.day.pt()} (${day.minutes} min).", week, details)
    }

    private fun merge(program: Program, missed: PlannedSession, upcoming: List<PlannedSession>): MissedOption {
        val next = upcoming.firstOrNull()
            ?: return MissedOption('B', "Incorporar parte no próximo treino", false, "Não há próximo treino nesta semana.", upcoming)
        val present = next.exercises.map { it.exercise.id }.toSet()
        // Músculos que ficariam abaixo do mínimo sem a sessão perdida guiam o que vale recuperar.
        val without = calc.weekly(program.sessions.filter { it !== missed })
        val short = program.volumeTargets.filter { (m, t) -> (without[m] ?: 0.0) < t.min }.keys
        val extra = missed.exercises
            .filter { it.role == SlotRole.MAIN && it.exercise.id !in present }
            .sortedByDescending { e -> e.exercise.primaryMuscles.count { it in short } }
            .take(2)
        val mains = next.exercises.count { it.role == SlotRole.MAIN }
        val combined = next.copy(exercises = next.exercises.take(mains) + extra + next.exercises.drop(mains))
        val fit = fitter.fit(combined, next.budgetMinutes ?: 60)
        val week = upcoming.map { if (it === next) fit.session else it }
        return MissedOption(
            'B', "Incorporar parte no próximo treino", extra.isNotEmpty(),
            if (extra.isEmpty()) "Os principais de ${missed.name} já estão no próximo treino."
            else "Adiciona ${extra.joinToString { it.exercise.name }} ao treino de ${next.day?.pt()}, ajustando ao tempo do dia.",
            week, fit.changes.map { it.toString() },
        )
    }

    private fun ignore(program: Program, missed: PlannedSession, upcoming: List<PlannedSession>): MissedOption {
        val without = calc.weekly(program.sessions.filter { it !== missed })
        val affected = program.volumeTargets.filter { (m, t) -> (without[m] ?: 0.0) < t.min && (program.weeklyVolume[m] ?: 0.0) >= t.min }
        val details = affected.map { (m, t) -> "${kb.muscleName(m)}: ${fmt(without[m] ?: 0.0)} séries (mínimo planejado ${fmt(t.min)})" }
        return MissedOption('C', "Ignorar esta sessão", true, "O programa segue normalmente na próxima sessão.", upcoming,
            if (details.isEmpty()) listOf("Nenhum músculo fica abaixo do mínimo semanal.") else listOf("Ficam abaixo do mínimo semanal:") + details)
    }

    private fun recalc(program: Program, missed: PlannedSession, upcoming: List<PlannedSession>, profile: UserProfile, today: DayOfWeek): MissedOption {
        val days = profile.availability.filter { it.day >= today && it.minutes >= minMinutes }
        var toPlace = (listOf(missed) + upcoming).map { it.copy(day = null) }
        val dropped = mutableListOf<String>()
        while (toPlace.size > days.size && toPlace.isNotEmpty()) {
            // Remove a sessão cuja ausência menos reduz o volume abaixo das metas.
            val victim = toPlace.minBy { s -> deficit(program, toPlace.filter { it !== s }) }
            dropped += victim.name
            toPlace = toPlace.filter { it !== victim }
        }
        if (toPlace.isEmpty()) return MissedOption('D', "Recalcular semana", false, "Não há dias disponíveis no restante da semana.", upcoming)
        val chosen = scheduler.pickDays(days, toPlace.size)
        val assignment = scheduler.assign(toPlace, chosen, profile.sports)
        val week = toPlace.mapIndexed { i, s ->
            val d = assignment.dayForSession[i]
            fitter.fit(s, d.minutes).session.copy(day = d.day)
        }.sortedBy { it.day }
        val details = week.map { "${it.day?.pt()}: ${it.name} (~${it.estimatedMinutes} min)" } +
            dropped.map { "Fora desta semana: $it (menor impacto no volume)" }
        return MissedOption('D', "Recalcular semana", true, "Redistribui as sessões restantes nos dias disponíveis.", week, details)
    }

    private fun deficit(program: Program, sessions: List<PlannedSession>): Double {
        val w = calc.weekly(sessions)
        return program.volumeTargets.entries.sumOf { (m, t) -> maxOf(0.0, t.min - (w[m] ?: 0.0)) }
    }

    private fun overlap(a: PlannedSession, b: PlannedSession): Double {
        val va = calc.ofExercises(a.exercises); val vb = calc.ofExercises(b.exercises)
        return va.entries.sumOf { (m, v) -> minOf(v, vb[m] ?: 0.0) }
    }

    private fun fmt(v: Double) = ProgramGenerator.fmt(v)
}
