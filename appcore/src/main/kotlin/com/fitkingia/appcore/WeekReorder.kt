package com.fitkingia.appcore

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.model.MuscleId
import com.fitkingia.core.planning.MissedOption
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.program.Program
import com.fitkingia.core.program.VolumeCalculator
import com.fitkingia.core.program.WeekScheduler
import com.fitkingia.core.program.pt
import com.fitkingia.core.program.ptCapitalized
import com.fitkingia.core.session.ChangeKind
import com.fitkingia.core.session.SessionChange
import com.fitkingia.core.session.SessionFitter
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
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
    /** Como estava antes, para “Desfazer” ([FitKing.undoReorder]). */
    val undo: ReorderUndo? = null,
)

/** O treino de hoje, deslocado quando outro treino é feito hoje; [to] nulo = fica fora desta semana. */
data class Displaced(val session: PlannedSession, val to: LocalDate?)

/**
 * Como as semanas tocadas (e o programa, numa troca permanente) estavam antes de uma reorganização,
 * e as decisões de treino perdido que ela registrou. Guarda também o estado de depois: “Desfazer” só
 * vale enquanto nada mudou no meio.
 */
class ReorderUndo internal constructor(
    internal val at: LocalDateTime,
    internal val weeks: List<WeekSnapshot>,
    internal val program: ProgramSnapshot?,
    internal val missedIds: List<Long>,
)

internal class WeekSnapshot(val weekStart: LocalDate, val before: WeekPlan?, val after: String?)

internal class ProgramSnapshot(val programId: Long, val before: Map<Long, PlannedSession>, val after: Map<Long, String>?)

/** "na quinta", "no sábado". */
fun DayOfWeek.ptWithArticle(): String = if (this == DayOfWeek.SATURDAY || this == DayOfWeek.SUNDAY) "no ${pt()}" else "na ${pt()}"

/**
 * Reorganizar a semana atual (ou a próxima): trocar dois dias ou fazer hoje o treino de outro dia.
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

    /** Troca dois dias da semana atual (de hoje em diante) ou da próxima; [permanent] muda também o programa. */
    fun swapDays(a: LocalDate, b: LocalDate, permanent: Boolean): ReorderResult {
        val today = now().toLocalDate()
        require(a != b) { "Escolha dois dias diferentes." }
        require(fit.weekStart(a) == fit.weekStart(b)) { "Escolha dois dias da mesma semana." }
        val current = fit.weekStart(today)
        require(fit.weekStart(a) == current || fit.weekStart(a) == current.plusWeeks(1)) { "Só dá para trocar dias desta semana ou da próxima." }
        val week = requireNotNull(fit.week(a)) { "Ainda não há programa de treino." }
        for (d in listOf(a, b)) {
            require(!d.isBefore(today)) { "${d.dayOfWeek.ptCapitalized()} já passou: use “Fazer este treino hoje” ou troque na próxima semana." }
            require(week.on(d).status != DayStatus.DONE) { "O treino ${d.dayOfWeek.ptWithArticle()} já foi feito." }
        }
        val sa = week.on(a).session
        val sb = week.on(b).session
        require(sa != null || sb != null) { "Os dois dias são de descanso: não há o que trocar." }
        requireNoOpenWorkout(week, listOf(a, b))

        val draft = Draft(week, today)
        val toB = sa?.let { draft.place(it, a, b) }
        val toA = sb?.let { draft.place(it, b, a) }
        val reason = if (sa != null && sb != null) "${sa.name} ⇄ ${sb.name} (${a.dayOfWeek.pt()} e ${b.dayOfWeek.pt()})"
        else "${(sa ?: sb)!!.name} passou para ${(if (sa != null) b else a).dayOfWeek.pt()}"
        val replacements = mapOf(a.dayOfWeek to toA, b.dayOfWeek to toB)

        val undo = Snapshot()
        undo.week(week.weekStart)
        if (permanent) changeProgram(week, today, a.dayOfWeek, b.dayOfWeek, replacements, draft, undo)
        savePlan(week, today, replacements, if (permanent) "Troca permanente: $reason" else reason)
        repo.logEvent("week_swap", "{\"a\":\"$a\",\"b\":\"$b\",\"permanent\":$permanent}", now())
        return draft.finish(undo.done())
    }

    fun doToday(from: LocalDate): ReorderResult {
        val today = now().toLocalDate()
        val week = requireNotNull(fit.week()) { "Ainda não há programa de treino." }
        require(from != today) { "Esse já é o treino de hoje." }
        require(fit.weekStart(from) == week.weekStart) { "Só dá para trazer treinos desta semana." }
        val src = week.on(from)
        val s = requireNotNull(src.session) { "Não há treino ${from.dayOfWeek.ptWithArticle()}." }
        require(src.status != DayStatus.DONE) { "O treino ${from.dayOfWeek.ptWithArticle()} já foi feito." }
        rescheduled(week, src, today)?.let { copy ->
            require(copy.date != today) { "${s.name} já está marcado para hoje." }
            require(copy.status != DayStatus.DONE) { "${s.name} já foi feito ${copy.date.dayOfWeek.ptWithArticle()}." }
            // A cópia remarcada é que está pendente: trazer a cópia registra a decisão no dia certo.
            return doToday(copy.date)
        }
        val todayPlan = week.on(today)
        require(todayPlan.status != DayStatus.DONE || todayPlan.session == null) { "Você já treinou hoje — aproveite para recuperar." }
        requireNoOpenWorkout(week, listOf(today, from))

        val undo = Snapshot()
        undo.week(week.weekStart)
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
        if (from.isBefore(today) && src.status == DayStatus.MISSED) undo.missed += repo.recordMissed(src.sessionId, from, 'A', now())
        repo.logEvent("week_do_today", "{\"from\":\"$from\",\"to\":\"$today\"}", now())
        return draft.finish(undo.done())
    }

    /**
     * Opção de treino perdido (A, B, D mudam o resto da semana; C só registra). Grava pelo mesmo caminho das
     * trocas: o que já estava decidido antes de hoje (trocas, treinos remarcados) é preservado.
     */
    fun applyMissed(day: DayPlan, option: MissedOption) {
        val today = now().toLocalDate()
        val week = requireNotNull(fit.week()) { "Ainda não há programa de treino." }
        require(fit.weekStart(day.date) == week.weekStart && day.date.isBefore(today)) { "Só dá para replanejar treinos perdidos desta semana." }
        if (option.key != 'C' && option.available) {
            // Como em [FitKing.missedOptions]: se o treino de hoje já foi feito, a opção vale de amanhã em diante.
            val from = if (week.on(today).status == DayStatus.DONE) today.plusDays(1) else today
            val replacements = week.days.filter { !it.date.isBefore(from) }
                .associate { d -> d.day to option.remainingWeek.firstOrNull { it.day == d.day } }
            val changed = week.days.filter { d -> d.day in replacements && replacements[d.day]?.key != d.session?.key }
            // O próprio dia perdido conta: o treino dele pode estar aberto (começado e não concluído) e ir para outro dia.
            requireNoOpenWorkout(week, changed.map { it.date } + day.date, incoming = changed.mapNotNull { replacements[it.day]?.key })
            savePlan(week, today, replacements, "Treino de ${day.day.pt()} não realizado — opção ${option.key}")
        }
        repo.recordMissed(day.sessionId, day.date, option.key, now())
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
        val copy = rescheduled(week, src, today)
        if (copy?.status == DayStatus.DONE) return null
        val origin = copy?.date ?: from
        val current = week.on(today).takeIf { it.status == DayStatus.TODAY }?.session ?: return null
        if (origin == today) return null
        val avail = fit.profile()?.availability?.associate { it.day to it.minutes }.orEmpty()
        return Displaced(current, if (origin.isAfter(today)) origin else freeDay(week, today, avail)?.date)
    }

    /**
     * Treino de um dia que já passou, remarcado nesta semana (opção A, “Fazer hoje”): o dia em que a cópia está
     * agora. Nulo se não foi remarcado, se o dia não passou ou se é de outra semana.
     */
    fun copyOf(date: LocalDate): DayPlan? {
        val today = now().toLocalDate()
        val week = fit.week() ?: return null
        if (fit.weekStart(date) != week.weekStart) return null
        return rescheduled(week, week.on(date), today)
    }

    /**
     * Desfaz uma reorganização: volta o replanejamento das semanas, o programa (troca permanente) e as
     * decisões de treino perdido. Recusa se algo mudou depois (outra troca, treino registrado, programa refeito).
     */
    fun undo(u: ReorderUndo) {
        val changed = "A semana mudou depois dessa troca. Se quiser, troque de novo pelos dias."
        for (w in u.weeks) require(planKey(repo.weekPlan(w.weekStart)) == w.after) { changed }
        u.program?.let { p -> require(p.after != null && sessionKeys(p.programId, p.before.keys) == p.after) { changed } }
        val from = u.weeks.minOf { it.weekStart }
        val to = u.weeks.maxOf { it.weekStart }.plusDays(6)
        require(repo.workoutsBetween(from, to, finishedOnly = false).none { !it.startedAt.isBefore(u.at) }) {
            "Você registrou um treino depois dessa troca. Se quiser, troque de novo pelos dias."
        }
        repo.transaction {
            for (w in u.weeks) if (w.before == null) repo.deleteWeekPlan(w.weekStart) else repo.saveWeekPlan(w.before, now())
            u.program?.let { p ->
                repo.rescheduleProgramSessions(p.programId, p.before)
                saveOrder()
            }
            u.missedIds.forEach(repo::deleteMissed)
        }
        repo.logEvent("week_undo", null, now())
    }

    /**
     * Depois de gerar o programa de novo (mesmo perfil): se o usuário tinha escolhido outra ordem de dias
     * (troca “todas as semanas”) e o programa novo tem as mesmas sessões nos mesmos dias, a ordem dele volta.
     */
    fun reapplyOrder() {
        val order = repo.pref(PREF_DAY_ORDER)?.let(::parseOrder) ?: return
        val stored = repo.activeProgram() ?: return
        val sessions = stored.program.sessions.filter { it.day != null }
        if (order.keys != sessions.map { it.key }.toSet() || order.values.toSet() != sessions.map { it.day }.toSet() || order.values.toSet().size != order.size) return
        val avail = fit.profile()?.availability?.associate { it.day to it.minutes }.orEmpty()
        val protect = kb.priorityMuscles(stored.program.priorities)
        val changed = sessions.filter { order[it.key] != it.day }
            .associate { s -> stored.sessionIds.getValue(s.key) to refit(s, order.getValue(s.key), avail[order.getValue(s.key)], protect) }
        if (changed.isNotEmpty()) repo.rescheduleProgramSessions(stored.id, changed)
    }

    // ---------------------------------------------------------------------------------------

    private fun WeekView.on(d: LocalDate): DayPlan = days.first { it.date == d }

    /**
     * Treino perdido que já foi remarcado (opção A, “Fazer hoje”): o dia em que a cópia está agora — de hoje
     * em diante ou, se a origem já foi resolvida, também um dia que passou (a cópia também pode ter ficado sem fazer).
     */
    private fun rescheduled(week: WeekView, src: DayPlan, today: LocalDate): DayPlan? {
        val s = src.session ?: return null
        if (!src.date.isBefore(today)) return null
        val copies = week.days.filter { it.date != src.date && it.session?.key == s.key }
        return copies.firstOrNull { !it.date.isBefore(today) }
            ?: copies.lastOrNull { src.status == DayStatus.MISSED_RESOLVED && it.date.isAfter(src.date) }
    }

    /** Próximo dia desta semana, depois de hoje, sem treino e com tempo cadastrado suficiente. */
    private fun freeDay(week: WeekView, today: LocalDate, avail: Map<DayOfWeek, Int>): DayPlan? =
        week.days.firstOrNull { it.date.isAfter(today) && it.session == null && (avail[it.day] ?: 0) >= minMinutes }

    /**
     * Treino em andamento desta semana num dia (ou com a sessão de um dia) que a troca mexe, ou cuja sessão a
     * troca leva para outro dia ([incoming]: sessões que chegam aos dias alterados): ao concluir, ele marcaria
     * como feito o dia errado. Pede para concluir ou descartar antes.
     */
    private fun requireNoOpenWorkout(week: WeekView, days: Collection<LocalDate>, incoming: Collection<String> = emptyList()) {
        val w = repo.unfinishedWorkout() ?: return
        val started = w.startedAt.toLocalDate()
        if (fit.weekStart(started) != week.weekStart) return
        val key = w.sessionKey ?: w.plan?.key
        require(days.none { d -> d == started || (key != null && week.on(d).session?.key == key) } && (key == null || key !in incoming)) {
            "Você tem um treino em andamento (${w.plan?.name ?: "Treino"}). Conclua ou descarte esse treino antes de mudar a semana."
        }
    }

    /**
     * Troca “todas as semanas”: o programa passa a ter, nos dias trocados, o que a semana mostra depois da
     * troca ([programOrder]). As semanas anteriores (e a atual, se a troca é na próxima) continuam como estavam.
     */
    private fun changeProgram(
        week: WeekView, today: LocalDate, a: DayOfWeek, b: DayOfWeek, replacements: Map<DayOfWeek, PlannedSession?>, draft: Draft, undo: Snapshot,
    ) {
        val stored = week.program
        val thisWeek = week.weekStart == fit.weekStart(today)
        val base = stored.program.sessions.mapNotNull { s -> s.day?.let { s.key to it } }.toMap()
        val shown = week.days.associate { d -> d.day to (if (replacements.containsKey(d.day)) replacements[d.day] else d.session)?.key }
        val order = requireNotNull(programOrder(base, shown, a, b)) {
            "Nesta semana há treino remarcado nesses dias, então a ordem para as próximas semanas não fica clara. " +
                if (thisWeek) "Troque “só esta semana”, ou faça a troca “todas as semanas” na próxima semana." else "Troque “só nessa semana”."
        }
        // Antes de mudar o programa: as semanas que já passaram não podem mudar de cara.
        val frozen = fit.browsableWeeks().filter { it.isBefore(week.weekStart) }.associateWith { fit.week(it) }
        frozen.keys.forEach(undo::week)

        val byKey = stored.program.sessions.associateBy { it.key }
        val protect = kb.priorityMuscles(stored.program.priorities)
        val changed = linkedMapOf<Long, PlannedSession>()
        for ((day, key) in order) {
            val p = byKey[key ?: continue] ?: continue
            val refitted = refit(p, day, draft.avail[day], protect)
            if (refitted.exercises.size < p.exercises.size || refitted.exercises.sumOf { it.sets } < p.exercises.sumOf { it.sets }) {
                draft.warnings += Explanation.rule(
                    "Como a troca vale para todas as semanas, ${p.name} fica na versão reduzida no programa. " +
                        "Para voltar à versão completa, toque em “Desfazer”.",
                    kb.ruleSet.timing.id,
                )
            }
            changed[stored.sessionIds.getValue(p.key)] = refitted
        }
        if (changed.isNotEmpty()) {
            undo.program(stored, changed.keys)
            repo.rescheduleProgramSessions(stored.id, changed)
            saveOrder()
        }
        for ((ws, old) in frozen) freeze(ws, old ?: continue)

        val label = if (thisWeek) "Nas próximas semanas" else "A partir da semana de ${shortDate(week.weekStart)}"
        draft.moves += if (order.isEmpty()) "$label: o programa já tem essa ordem."
        else "$label: " + order.entries.sortedBy { it.key }.joinToString(", ") { (d, k) -> "${d.pt()} ${k?.let { byKey.getValue(it).name } ?: "descanso"}" } + "."
        val chain = order.filter { (d, k) -> d != a && d != b && k != null }.keys.sorted()
        if (chain.isNotEmpty()) {
            draft.moves += "Para não repetir nem perder treino, ${joinPt(chain.map { it.pt() })} também " +
                (if (chain.size == 1) "fica" else "ficam") + " como ${if (thisWeek) "nesta" else "nessa"} semana."
        }
        if (thisWeek) repo.weekPlan(week.weekStart.plusWeeks(1))?.let {
            draft.moves += "A semana de ${shortDate(it.weekStart)} tem trocas próprias e continua como você deixou."
        }
        draft.programChange = order.keys
    }

    /** Mantém uma semana anterior como estava antes de o programa mudar. */
    private fun freeze(ws: LocalDate, old: WeekView) {
        val now = fit.week(ws) ?: return
        val before = old.days.mapNotNull { it.session }
        if (Codec.sessions(now.days.mapNotNull { it.session }) == Codec.sessions(before)) return
        repo.saveWeekPlan(WeekPlan(ws, DayOfWeek.MONDAY, old.plan?.reason.orEmpty(), before), now())
    }

    /** Guarda a ordem de dias escolhida (para [reapplyOrder]). */
    private fun saveOrder() {
        val p = repo.activeProgram() ?: return
        repo.setPref(PREF_DAY_ORDER, p.program.sessions.mapNotNull { s -> s.day?.let { "${s.key}:${it.value}" } }.joinToString(","))
    }

    private fun parseOrder(raw: String): Map<String, DayOfWeek>? = runCatching {
        raw.split(",").filter { it.isNotBlank() }.associate { it.substringBeforeLast(":") to DayOfWeek.of(it.substringAfterLast(":").toInt()) }
    }.getOrNull()

    private fun refit(s: PlannedSession, day: DayOfWeek, minutes: Int?, protect: Set<MuscleId>): PlannedSession {
        val budget = minutes ?: s.budgetMinutes ?: return s.copy(day = day)
        return fitter.fit(s, budget, protect = protect).session.copy(day = day)
    }

    /**
     * Grava o replanejamento da semana a partir do primeiro dia já replanejado (ou de hoje; numa semana
     * futura, de segunda), preservando o que já estava decidido; [replacements] diz a sessão de cada dia
     * alterado (null = descanso). Se o resultado ficar igual ao programa (atual), o replanejamento é apagado.
     */
    private fun savePlan(week: WeekView, today: LocalDate, replacements: Map<DayOfWeek, PlannedSession?>, reason: String) {
        val program = repo.activeProgram() ?: week.program
        val start = if (week.weekStart.isAfter(today)) DayOfWeek.MONDAY else today.dayOfWeek
        var fromDay = minOf(week.plan?.fromDay ?: start, start)
        // O programa mudou (troca permanente) em dias antes do replanejamento: a semana continua como estava.
        val shownBefore = week.days.filter { it.day < fromDay }.mapNotNull { it.session }
        val programBefore = program.program.sessions.filter { s -> s.day?.let { it < fromDay } == true }.sortedBy { it.day }
        if (Codec.sessions(shownBefore) != Codec.sessions(programBefore)) fromDay = DayOfWeek.MONDAY
        val sessions = week.days.filter { it.day >= fromDay }
            .mapNotNull { d -> if (replacements.containsKey(d.day)) replacements[d.day] else d.session }
        val base = program.program.sessions.filter { s -> s.day?.let { it >= fromDay } == true }.sortedBy { it.day }
        if (Codec.sessions(sessions) == Codec.sessions(base)) {
            repo.deleteWeekPlan(week.weekStart)
            return
        }
        val reasons = (week.plan?.reason?.split(REASON_SEPARATOR).orEmpty().filter { it.isNotBlank() } + reason).takeLast(3)
        repo.saveWeekPlan(WeekPlan(week.weekStart, fromDay, reasons.joinToString(REASON_SEPARATOR), sessions), now())
    }

    private fun planKey(p: WeekPlan?): String? = p?.let { "${it.fromDay.value}|${it.reason}|${Codec.sessions(it.sessions)}" }

    private fun sessionKeys(programId: Long, ids: Set<Long>): Map<Long, String>? {
        val stored = repo.activeProgram()?.takeIf { it.id == programId } ?: return null
        return stored.program.sessions.mapNotNull { s -> stored.sessionIds[s.key]?.takeIf { it in ids }?.let { it to Codec.session(s).toString() } }.toMap()
    }

    /** Estado de antes de uma reorganização (para “Desfazer”). Registre as semanas antes de gravar. */
    private inner class Snapshot {
        private val at = now()
        private val weeks = linkedMapOf<LocalDate, WeekPlan?>()
        private var program: Pair<Long, Map<Long, PlannedSession>>? = null
        val missed = mutableListOf<Long>()

        fun week(ws: LocalDate) {
            if (ws !in weeks) weeks[ws] = repo.weekPlan(ws)
        }

        fun program(stored: StoredProgram, ids: Collection<Long>) {
            val keyOf = stored.sessionIds.entries.associate { (k, id) -> id to k }
            val byKey = stored.program.sessions.associateBy { it.key }
            program = stored.id to ids.associateWith { byKey.getValue(keyOf.getValue(it)) }
        }

        fun done() = ReorderUndo(
            at,
            weeks.map { (ws, before) -> WeekSnapshot(ws, before, planKey(repo.weekPlan(ws))) },
            program?.let { (id, before) -> ProgramSnapshot(id, before, sessionKeys(id, before.keys)) },
            missed.toList(),
        )
    }

    /** Uma reorganização em andamento: sessões movidas, frases do que mudou e avisos. */
    private inner class Draft(val week: WeekView, val today: LocalDate) {
        val avail: Map<DayOfWeek, Int> = fit.profile()?.availability?.associate { it.day to it.minutes }.orEmpty()
        val moves = mutableListOf<String>()
        val warnings = mutableListOf<Explanation>()
        val dropped = mutableListOf<PlannedSession>()
        /** Dias do programa que mudaram numa troca permanente. */
        var programChange: Set<DayOfWeek> = emptySet()
        private val moved = linkedMapOf<LocalDate, PlannedSession>()
        private val protect = kb.priorityMuscles(week.program.program.priorities)

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
                val f = fitter.fit(source, budget, protect = protect)
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

        fun finish(undo: ReorderUndo?): ReorderResult {
            val after = fit.week(week.weekStart)!!
            val hits = mutableSetOf<Hit>()
            warnings += neighbours(after, hits)
            warnings += sports(hits)
            if (programChange.isNotEmpty()) fit.program()?.let { warnings += programWarnings(it.program, hits) }
            return ReorderResult(after, moves.toList(), warnings.distinctBy { it.text }, dropped.toList(), undo)
        }

        /** Mesmos músculos em dias seguidos, só nos pares que envolvem um dia alterado. */
        private fun neighbours(after: WeekView, hits: MutableSet<Hit>): List<Explanation> {
            val out = mutableListOf<Explanation>()
            val trained = setOf(DayStatus.DONE, DayStatus.TODAY, DayStatus.PLANNED)
            for ((x, y) in after.days.zipWithNext()) {
                if (x.date !in moved && y.date !in moved) continue
                if (y.date.isBefore(today) || x.status !in trained || y.status !in trained) continue
                val sx = x.session ?: continue
                val sy = y.session ?: continue
                val region = sharedRegion(sx, sy) ?: continue
                hits += Hit(sx.key, x.day, sy.key, y.day)
                out += Explanation.rule("${sx.name} ${ref(x.date)} e ${sy.name} ${ref(y.date)}: $region em dias seguidos. ${hint(region)}", kb.ruleSet.scheduling.id)
            }
            return out
        }

        /** Troca permanente: os mesmos avisos sobre o programa novo (as próximas semanas), sem repetir os desta semana. */
        private fun programWarnings(program: Program, hits: Set<Hit>): List<Explanation> {
            val out = mutableListOf<Explanation>()
            val days = DayOfWeek.values()
            val label = if (week.weekStart == fit.weekStart(today)) "Nas próximas semanas" else "A partir da semana de ${shortDate(week.weekStart)}"
            for (i in days.indices) {
                val x = days[i]
                val y = days[(i + 1) % days.size]
                if (x !in programChange && y !in programChange) continue
                val sx = program.sessionOn(x) ?: continue
                val sy = program.sessionOn(y) ?: continue
                if (Hit(sx.key, x, sy.key, y) in hits) continue
                val region = sharedRegion(sx, sy) ?: continue
                out += Explanation.rule(
                    "$label, ${sx.name} ${x.ptWithArticle()} e ${sy.name} ${y.ptWithArticle()}: $region em dias seguidos. ${hint(region)}",
                    kb.ruleSet.scheduling.id,
                )
            }
            val sports = fit.profile()?.sports.orEmpty()
            if (sports.isNotEmpty()) for (d in programChange.sorted()) {
                val s = program.sessionOn(d) ?: continue
                if (Hit(s.key, d, s.key, d) in hits) continue
                scheduler.sportConflicts(listOf(s), listOf(d), sports).forEach { msg ->
                    out += Explanation.rule("$label, " + sportText(msg), kb.ruleSet.scheduling.id)
                }
            }
            return out
        }

        private fun hint(region: String): String {
            val who = when (region) {
                "pernas" -> "Se as pernas estiverem cansadas"
                "corpo todo" -> "Se o corpo estiver cansado"
                else -> "Se estiverem cansados"
            }
            return "$who, a avaliação do dia (“🙂 Como estou”) ajusta o treino."
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

        private fun sports(hits: MutableSet<Hit>): List<Explanation> {
            val sports = fit.profile()?.sports.orEmpty()
            if (sports.isEmpty()) return emptyList()
            return moved.flatMap { (date, s) ->
                scheduler.sportConflicts(listOf(s), listOf(date.dayOfWeek), sports).map { msg ->
                    hits += Hit(s.key, date.dayOfWeek, s.key, date.dayOfWeek)
                    Explanation.rule(sportText(msg), kb.ruleSet.scheduling.id)
                }
            }
        }

        // Corta pelo texto fixo do motor: nomes de sessão também têm " — " (ex.: "Inferiores A — quadríceps").
        private fun sportText(msg: String) =
            msg.substringBefore(" — com os dias") + ": perna pesada perto do esporte. Se as pernas estiverem cansadas, a avaliação do dia (“🙂 Como estou”) ajusta o treino."
    }

    /** Um aviso de dias seguidos (ou de esporte, com x = y) já dado — para não repetir o mesmo par. */
    private data class Hit(val keyX: String, val dayX: DayOfWeek, val keyY: String, val dayY: DayOfWeek)

    companion object {
        const val REASON_SEPARATOR = " · "

        /** Ordem de dias escolhida pelo usuário numa troca “todas as semanas” ("chave:dia,…"). */
        const val PREF_DAY_ORDER = "program_day_order"

        private fun shortDate(d: LocalDate) = "${d.dayOfMonth.toString().padStart(2, '0')}/${d.monthValue.toString().padStart(2, '0')}"

        private fun joinPt(items: List<String>) = if (items.size == 1) items[0] else items.dropLast(1).joinToString(", ") + " e " + items.last()

        /**
         * Nova ordem do programa numa troca permanente entre [a] e [b]: o programa passa a ter nesses dias o
         * que a semana mostra depois da troca ([shown]). Se a semana já tinha trocas “só desta semana” com
         * essas sessões, segue a semana também nos outros dias da cadeia, para nenhuma sessão sumir nem
         * aparecer duas vezes. Devolve só os dias que mudam (null = vira descanso); nulo quando a semana
         * não dá uma ordem clara (sessão repetida na semana, como um treino perdido remarcado).
         *
         * [base]: sessão do programa → dia. [shown]: dia → sessão mostrada na semana depois da troca.
         */
        internal fun programOrder(base: Map<String, DayOfWeek>, shown: Map<DayOfWeek, String?>, a: DayOfWeek, b: DayOfWeek): Map<DayOfWeek, String?>? {
            val baseOn = base.entries.associate { (k, d) -> d to k }
            val shownCount = shown.values.filterNotNull().groupingBy { it }.eachCount()
            val assign = linkedMapOf(a to shown[a], b to shown[b])
            if (assign.values.any { it != null && (it !in base || (shownCount[it] ?: 0) > 1) }) return null
            while (true) {
                val required = assign.values.filterNotNull()
                if (required.size != required.toSet().size) return null
                // Sessão do programa que estava num dia já decidido e ficou sem lugar.
                val displaced = assign.keys.mapNotNull { baseOn[it] }.firstOrNull { it !in required } ?: break
                val shownAt = DayOfWeek.values().filter { it !in assign && shown[it] == displaced }
                val vacated = required.map { base.getValue(it) }.filter { it !in assign }.sorted()
                val day = shownAt.singleOrNull() ?: vacated.firstOrNull() ?: return null
                assign[day] = displaced
            }
            for (k in assign.values.filterNotNull()) {
                val old = base.getValue(k)
                if (old !in assign) assign[old] = null
            }
            return assign.filter { (d, k) -> baseOn[d] != k }
        }
    }
}
