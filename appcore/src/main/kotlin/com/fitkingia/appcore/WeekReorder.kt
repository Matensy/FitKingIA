package com.fitkingia.appcore

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.program.VolumeCalculator
import com.fitkingia.core.program.WeekScheduler
import com.fitkingia.core.program.pt
import com.fitkingia.core.program.ptCapitalized
import com.fitkingia.core.session.ChangeKind
import com.fitkingia.core.session.SessionChange
import com.fitkingia.core.session.SessionFitter
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.math.min

/** Resultado de reorganizar a semana. Nada é bloqueado: os avisos existem para o usuário decidir. */
data class ReorderResult(
    val week: WeekView,
    /** O que mudou, em frases curtas ("Inferiores A: quinta → hoje (~55 min)"). */
    val moves: List<String>,
    /** Pontos de atenção: mesmos músculos em dias seguidos, esporte colado, sessão reduzida, sessão fora da semana. */
    val warnings: List<Explanation>,
    /** Sessões que ficaram sem dia nesta semana. */
    val dropped: List<PlannedSession> = emptyList(),
)

/** O treino de hoje, deslocado quando outro treino é feito hoje; [to] nulo = fica fora desta semana. */
data class Displaced(val session: PlannedSession, val to: LocalDate?)

/** "na quinta", "no sábado". */
fun DayOfWeek.ptWithArticle(): String = if (this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY) "no ${pt()}" else "na ${pt()}"

/**
 * Reorganizar a semana atual: trocar dois dias ou fazer hoje o treino de outro dia.
 * Só orquestra — o reajuste ao tempo do dia é do [SessionFitter], o volume por músculo do
 * [VolumeCalculator] e os conflitos com esportes do [WeekScheduler] (todos do core).
 */
internal class WeekReorder(private val fit: FitKing) {
    private val kb = fit.kb
    private val repo = fit.repo
    private val fitter = SessionFitter(kb)
    private val scheduler = WeekScheduler(kb)
    private val volume = VolumeCalculator(kb.ruleSet.volume.params)
    private val minMinutes = kb.ruleSet.frequency.params.minSessionMinutes

    private fun now() = fit.clock.now()

    fun swapDays(a: LocalDate, b: LocalDate, permanent: Boolean): ReorderResult {
        val today = now().toLocalDate()
        val week = requireNotNull(fit.week()) { "Ainda não há programa de treino." }
        require(a != b) { "Escolha dois dias diferentes." }
        for (d in listOf(a, b)) {
            require(fit.weekStart(d) == week.weekStart) { "Só dá para trocar dias desta semana." }
            require(!d.isBefore(today)) { "${d.dayOfWeek.ptCapitalized()} já passou: use “Fazer este treino hoje”." }
            require(week.on(d).status != DayStatus.DONE) { "O treino ${d.dayOfWeek.ptWithArticle()} já foi feito." }
        }
        val sa = week.on(a).session
        val sb = week.on(b).session
        require(sa != null || sb != null) { "Os dois dias são de descanso: não há o que trocar." }

        val draft = Draft(week, today)
        val toB = sa?.let { draft.place(it, a, b) }
        val toA = sb?.let { draft.place(it, b, a) }
        val reason = if (sa != null && sb != null) "${sa.name} ⇄ ${sb.name} (${a.dayOfWeek.pt()} e ${b.dayOfWeek.pt()})"
        else "${(sa ?: sb)!!.name} passou para ${(if (sa != null) b else a).dayOfWeek.pt()}"

        if (permanent) {
            // O programa base passa a ter, nesses dois dias, o que a semana mostra depois da troca.
            val stored = week.program
            val changed = linkedMapOf<Long, PlannedSession>()
            for ((p, day) in programMoves(stored, a, b, sa, sb)) {
                val refitted = refit(p, day, draft.avail[day])
                if (refitted.exercises.size < p.exercises.size || refitted.exercises.sumOf { it.sets } < p.exercises.sumOf { it.sets }) {
                    draft.warnings += Explanation.rule(
                        "Como a troca vale para todas as semanas, ${p.name} fica na versão reduzida no programa. " +
                            "Para recuperar os exercícios cortados, use “Gerar o programa de novo” em Perfil e dados.",
                        kb.ruleSet.timing.id,
                    )
                }
                changed[stored.sessionIds.getValue(p.key)] = refitted
            }
            if (changed.isNotEmpty()) repo.rescheduleProgramSessions(stored.id, changed)
            draft.moves += "Vale também para as próximas semanas."
            // Sem replanejamento nesta semana, o programa base já mostra a troca.
            if (week.plan != null) savePlan(week, today, mapOf(a.dayOfWeek to toA, b.dayOfWeek to toB), "Troca permanente: $reason")
        } else {
            savePlan(week, today, mapOf(a.dayOfWeek to toA, b.dayOfWeek to toB), reason)
        }
        repo.logEvent("week_swap", "{\"a\":\"$a\",\"b\":\"$b\",\"permanent\":$permanent}", now())
        return draft.finish()
    }

    fun doToday(from: LocalDate): ReorderResult {
        val today = now().toLocalDate()
        val week = requireNotNull(fit.week()) { "Ainda não há programa de treino." }
        require(from != today) { "Esse já é o treino de hoje." }
        require(fit.weekStart(from) == week.weekStart) { "Só dá para trazer treinos desta semana." }
        val src = week.on(from)
        val s = requireNotNull(src.session) { "Não há treino ${from.dayOfWeek.ptWithArticle()}." }
        require(src.status != DayStatus.DONE) { "O treino ${from.dayOfWeek.ptWithArticle()} já foi feito." }
        rescheduled(week, src, today)?.let { now ->
            require(now.date != today) { "${s.name} já está marcado para hoje." }
            return doToday(now.date)
        }
        val todayPlan = week.on(today)
        require(todayPlan.status != DayStatus.DONE || todayPlan.session == null) { "Você já treinou hoje — aproveite para recuperar." }

        val draft = Draft(week, today)
        val replacements = linkedMapOf<DayOfWeek, PlannedSession?>()
        replacements[today.dayOfWeek] = draft.place(s, from, today)
        val current = todayPlan.session
        if (from.isAfter(today)) {
            replacements[from.dayOfWeek] = current?.let { draft.place(it, today, from) }
        } else if (current != null) {
            // A origem já passou: o treino de hoje vai para o próximo dia livre com tempo.
            val free = freeDay(week, today, draft.avail)
            if (free != null) {
                replacements[free.day] = draft.place(current, today, free.date)
            } else {
                draft.dropped += current
                draft.warnings += Explanation.rule(
                    "${current.name} ficou fora desta semana: não há outro dia livre com tempo até domingo. Na próxima semana o programa volta ao normal.",
                    kb.ruleSet.frequency.id,
                )
            }
        }
        savePlan(week, today, replacements, "${s.name} trazido de ${from.dayOfWeek.pt()} para ${today.dayOfWeek.pt()}")
        if (from.isBefore(today) && src.status == DayStatus.MISSED) repo.recordMissed(src.sessionId, from, 'A', now())
        repo.logEvent("week_do_today", "{\"from\":\"$from\",\"to\":\"$today\"}", now())
        return draft.finish()
    }

    /**
     * O que acontece com o treino de hoje se o treino de [from] for feito hoje: vai para [from] (se ainda
     * não passou), para o próximo dia livre com tempo, ou fica fora desta semana ([Displaced.to] nulo).
     * Nulo quando hoje é descanso ou quando não dá para trazer o treino.
     */
    fun displaced(from: LocalDate): Displaced? {
        val today = now().toLocalDate()
        val week = fit.week() ?: return null
        if (from == today || fit.weekStart(from) != week.weekStart) return null
        val src = week.on(from)
        val origin = rescheduled(week, src, today)?.date ?: from
        val current = week.on(today).takeIf { it.status == DayStatus.TODAY }?.session ?: return null
        if (origin == today) return null
        val avail = fit.profile()?.availability?.associate { it.day to it.minutes }.orEmpty()
        return Displaced(current, if (origin.isAfter(today)) origin else freeDay(week, today, avail)?.date)
    }

    // ---------------------------------------------------------------------------------------

    private fun WeekView.on(d: LocalDate): DayPlan = days.first { it.date == d }

    /** Treino perdido que já foi remarcado (ex.: opção A): o dia (hoje ou futuro) em que ele está agora. */
    private fun rescheduled(week: WeekView, src: DayPlan, today: LocalDate): DayPlan? {
        val s = src.session ?: return null
        if (!src.date.isBefore(today)) return null
        return week.days.firstOrNull { !it.date.isBefore(today) && it.session?.key == s.key }
    }

    /** Próximo dia desta semana, depois de hoje, sem treino e com tempo cadastrado suficiente. */
    private fun freeDay(week: WeekView, today: LocalDate, avail: Map<DayOfWeek, Int>): DayPlan? =
        week.days.firstOrNull { it.date.isAfter(today) && it.session == null && (avail[it.day] ?: 0) >= minMinutes }

    /**
     * Sessões do programa base que mudam de dia numa troca permanente entre [a] e [b], com o novo dia.
     * Se os dois dias mostram as mesmas sessões do programa (talvez já trocadas nesta semana), o programa
     * fica com o que a semana mostra depois da troca; se a semana trouxe sessões de outros dias, troca os
     * dias do programa.
     */
    private fun programMoves(stored: StoredProgram, a: LocalDate, b: LocalDate, sa: PlannedSession?, sb: PlannedSession?): List<Pair<PlannedSession, DayOfWeek>> {
        val pa = stored.program.sessionOn(a.dayOfWeek)
        val pb = stored.program.sessionOn(b.dayOfWeek)
        val sameSessions = setOf(pa?.key, pb?.key) == setOf(sa?.key, sb?.key)
        return listOfNotNull(pa?.to(a.dayOfWeek), pb?.to(b.dayOfWeek)).mapNotNull { (p, day) ->
            val to = when {
                !sameSessions -> if (day == a.dayOfWeek) b.dayOfWeek else a.dayOfWeek
                p.key == sa?.key -> b.dayOfWeek
                else -> a.dayOfWeek
            }
            if (to == day) null else p to to
        }
    }

    private fun refit(s: PlannedSession, day: DayOfWeek, minutes: Int?): PlannedSession {
        val budget = minutes ?: s.budgetMinutes ?: return s.copy(day = day)
        return fitter.fit(s, budget).session.copy(day = day)
    }

    /**
     * Grava o replanejamento da semana a partir do primeiro dia já replanejado (ou de hoje),
     * preservando o que já estava decidido; [replacements] diz a sessão de cada dia alterado (null = descanso).
     * Se o resultado ficar igual ao programa base, o replanejamento é apagado.
     */
    private fun savePlan(week: WeekView, today: LocalDate, replacements: Map<DayOfWeek, PlannedSession?>, reason: String) {
        val fromDay = minOf(week.plan?.fromDay ?: today.dayOfWeek, today.dayOfWeek)
        val sessions = week.days.filter { it.day >= fromDay }
            .mapNotNull { d -> if (replacements.containsKey(d.day)) replacements[d.day] else d.session }
        val base = week.program.program.sessions.filter { s -> s.day?.let { it >= fromDay } == true }.sortedBy { it.day }
        if (Codec.sessions(sessions) == Codec.sessions(base)) {
            repo.deleteWeekPlan(week.weekStart)
            return
        }
        val reasons = (week.plan?.reason?.split(REASON_SEPARATOR).orEmpty() + reason).takeLast(3)
        repo.saveWeekPlan(WeekPlan(week.weekStart, fromDay, reasons.joinToString(REASON_SEPARATOR), sessions), now())
    }

    /** Uma reorganização em andamento: sessões movidas, frases do que mudou e avisos. */
    private inner class Draft(val week: WeekView, val today: LocalDate) {
        val avail: Map<DayOfWeek, Int> = fit.profile()?.availability?.associate { it.day to it.minutes }.orEmpty()
        val moves = mutableListOf<String>()
        val warnings = mutableListOf<Explanation>()
        val dropped = mutableListOf<PlannedSession>()
        private val moved = linkedMapOf<LocalDate, PlannedSession>()

        fun name(d: LocalDate) = if (d == today) "hoje" else d.dayOfWeek.pt()

        fun ref(d: LocalDate) = when (d) {
            today -> "hoje"
            today.plusDays(1) -> "amanhã"
            today.minusDays(1) -> "ontem"
            else -> d.dayOfWeek.ptWithArticle()
        }

        /** Coloca [s] no dia [to], reajustada ao tempo desse dia (sem tempo cadastrado, mantém o orçamento original). */
        fun place(s: PlannedSession, from: LocalDate, to: LocalDate): PlannedSession {
            val source = fullVersion(s)
            val minutes = avail[to.dayOfWeek]
            val budget = minutes ?: s.budgetMinutes
            val placed = if (budget == null) source.copy(day = to.dayOfWeek) else {
                val f = fitter.fit(source, budget)
                if (f.changes.isNotEmpty()) warnings += Explanation.rule(cutSummary(s, to, budget, f.changes), kb.ruleSet.timing.id)
                f.session.copy(day = to.dayOfWeek)
            }
            moves += "${s.name}: ${name(from)} → ${name(to)} (~${placed.estimatedMinutes} min" +
                (if (minutes == null && budget != null) ", tempo original mantido" else "") + ")"
            moved[to] = placed
            return placed
        }

        /** "Inferiores B ajustado aos 30 min de sábado: sai Cadeira extensora e Leg press; 2 séries a menos; descanso no mínimo da faixa." */
        private fun cutSummary(s: PlannedSession, to: LocalDate, budget: Int, changes: List<SessionChange>): String {
            val removed = changes.filter { it.kind == ChangeKind.REMOVED }.mapNotNull { it.exerciseName }
            val fewerSets = changes.count { it.kind == ChangeKind.SETS_REDUCED }
            val parts = listOfNotNull(
                removed.takeIf { it.isNotEmpty() }?.let { "sai " + joinPt(it) },
                fewerSets.takeIf { it > 0 }?.let { if (it == 1) "1 série a menos" else "$it séries a menos" },
                "descanso no mínimo da faixa".takeIf { changes.any { it.kind == ChangeKind.REST_SHORTENED } },
                changes.firstOrNull { it.kind == ChangeKind.NOT_ENOUGH_TIME }?.let { "${it.description.replaceFirstChar { c -> c.lowercase() }} (${it.reason})" },
            )
            val day = if (to == today) "de hoje" else "de ${to.dayOfWeek.pt()}"
            return "${s.name} ajustado aos $budget min $day: ${parts.joinToString("; ")}."
        }

        private fun joinPt(items: List<String>) = if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + " e " + items.last()

        /** Se [s] é a sessão do programa só encurtada (para caber num dia mais curto), parte da versão completa. */
        private fun fullVersion(s: PlannedSession): PlannedSession {
            val base = week.program.program.sessions.firstOrNull { it.key == s.key } ?: return s
            var i = 0
            for (e in s.exercises) {
                while (i < base.exercises.size && (base.exercises[i].exercise.id != e.exercise.id || base.exercises[i].role != e.role)) i++
                if (i == base.exercises.size || e.sets > base.exercises[i].sets) return s
                i++
            }
            return base
        }

        fun finish(): ReorderResult {
            val after = fit.week()!!
            warnings += neighbours(after)
            warnings += sports()
            return ReorderResult(after, moves.toList(), warnings.distinctBy { it.text }, dropped.toList())
        }

        /** Mesmos músculos em dias seguidos, só nos pares que envolvem um dia alterado. */
        private fun neighbours(after: WeekView): List<Explanation> {
            val out = mutableListOf<Explanation>()
            val trained = setOf(DayStatus.DONE, DayStatus.TODAY, DayStatus.PLANNED)
            for ((x, y) in after.days.zipWithNext()) {
                if (x.date !in moved && y.date !in moved) continue
                if (y.date.isBefore(today) || x.status !in trained || y.status !in trained) continue
                val sx = x.session ?: continue
                val sy = y.session ?: continue
                val region = sharedRegion(sx, sy) ?: continue
                val hint = when (region) {
                    "pernas" -> "Se as pernas estiverem cansadas"
                    "corpo todo" -> "Se o corpo estiver cansado"
                    else -> "Se estiverem cansados"
                }
                out += Explanation.rule(
                    "${sx.name} ${ref(x.date)} e ${sy.name} ${ref(y.date)}: $region em dias seguidos. $hint, o check-in “Como estou” ajusta o treino.",
                    kb.ruleSet.scheduling.id,
                )
            }
            return out
        }

        /**
         * Região muito trabalhada nas duas sessões. Usa o mesmo critério de "perna pesada" do
         * WeekScheduler: séries fracionadas em comum (contagem do VolumeCalculator) a partir de
         * [WeekScheduler.LOWER_HEAVY_SETS].
         */
        private fun sharedRegion(a: PlannedSession, b: PlannedSession): String? {
            val va = volume.ofExercises(a.exercises)
            val vb = volume.ofExercises(b.exercises)
            fun shared(region: String) = va.entries.sumOf { (m, v) ->
                val muscle = kb.muscle(m)
                if (muscle.region == region && muscle.volumeTracked) min(v, vb[m] ?: 0.0) else 0.0
            }
            val lower = shared("lower") >= WeekScheduler.LOWER_HEAVY_SETS
            val upper = shared("upper") >= WeekScheduler.LOWER_HEAVY_SETS
            return when {
                lower && upper -> "corpo todo"
                lower -> "pernas"
                upper -> "tronco e braços"
                else -> null
            }
        }

        private fun sports(): List<Explanation> {
            val sports = fit.profile()?.sports.orEmpty()
            if (sports.isEmpty()) return emptyList()
            return moved.flatMap { (date, s) ->
                scheduler.sportConflicts(listOf(s), listOf(date.dayOfWeek), sports).map { msg ->
                    Explanation.rule(
                        // Corta pelo texto fixo do motor: nomes de sessão também têm " — " (ex.: "Inferiores A — quadríceps").
                        msg.substringBefore(" — com os dias") + ": perna pesada perto do esporte. Se as pernas estiverem cansadas, o check-in “Como estou” ajusta o treino.",
                        kb.ruleSet.scheduling.id,
                    )
                }
            }
        }
    }

    companion object {
        const val REASON_SEPARATOR = " · "
    }
}
