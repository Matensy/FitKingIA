package com.fitkingia.appcore

import com.fitkingia.core.analytics.Consistency
import com.fitkingia.core.analytics.MuscleVolumeStatus
import com.fitkingia.core.analytics.VolumeDashboard
import com.fitkingia.core.body.*
import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.explain.WhyService
import com.fitkingia.core.gamification.Gamification
import com.fitkingia.core.gamification.XpEvent
import com.fitkingia.core.gamification.XpStatus
import com.fitkingia.core.hydration.*
import com.fitkingia.core.knowledge.Exercise
import com.fitkingia.core.knowledge.Food
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.*
import com.fitkingia.core.nutrition.EnergyEstimator
import com.fitkingia.core.nutrition.MealItem
import com.fitkingia.core.nutrition.NutritionTargets
import com.fitkingia.core.planning.MissedOption
import com.fitkingia.core.planning.MissedWorkoutPlanner
import com.fitkingia.core.planning.MissedWorkoutReport
import com.fitkingia.core.planning.ProgramSimulator
import com.fitkingia.core.planning.Scenario
import com.fitkingia.core.planning.Simulation
import com.fitkingia.core.program.*
import com.fitkingia.core.progression.*
import com.fitkingia.core.recovery.ReadinessCheck
import com.fitkingia.core.recovery.ReadinessResult
import com.fitkingia.core.recovery.RecoveryScorer
import com.fitkingia.core.safety.SafetyScreening
import com.fitkingia.core.safety.ScreeningResult
import com.fitkingia.core.session.SessionAdapter
import com.fitkingia.core.session.SessionChange
import com.fitkingia.core.substitution.SubstitutionEngine
import com.fitkingia.core.substitution.SubstitutionResult
import com.fitkingia.core.tools.PlateCalculator
import com.fitkingia.core.tools.PlateLoad
import com.fitkingia.core.tools.WarmupGenerator
import com.fitkingia.core.tools.WarmupSet
import com.fitkingia.knowledge.sql.SqlDatabase
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

fun interface AppClock {
    fun now(): LocalDateTime
}

val SystemAppClock = AppClock { LocalDateTime.now().withNano(0) }

enum class DayStatus(val label: String) {
    DONE("Feito"), MISSED("Não realizado"), MISSED_RESOLVED("Replanejado"), TODAY("Hoje"), PLANNED("Planejado"), REST("Descanso"),
}

data class DayPlan(
    val date: LocalDate,
    val session: PlannedSession?,
    val sessionId: Long?,
    val status: DayStatus,
    val workouts: List<WorkoutRow>,
    val missedOption: Char?,
) {
    val day: DayOfWeek get() = date.dayOfWeek
}

data class WeekView(val weekStart: LocalDate, val days: List<DayPlan>, val plan: WeekPlan?, val program: StoredProgram) {
    /** Sessões distintas: um treino remarcado aparece no dia original (replanejado) e no novo, mas conta uma vez. */
    val planned: Int get() = days.mapNotNull { it.session?.key }.distinct().size
    val done: Int get() = days.filter { it.status == DayStatus.DONE }.mapNotNull { it.session?.key }.distinct().size
}

data class TodayView(
    val date: LocalDate,
    val today: DayPlan,
    /** Sessão a executar hoje, já com ajustes de tempo e prontidão. Nula em dia de descanso ou se já treinou. */
    val session: PlannedSession?,
    val sessionId: Long?,
    val title: String?,
    val changes: List<SessionChange>,
    val notes: List<Explanation>,
    val quickMinutes: Int?,
    val readiness: ReadinessResult?,
    val readinessId: Long?,
    val next: DayPlan?,
    val pendingMissed: List<DayPlan>,
    val unfinished: WorkoutRow?,
)

data class ActiveWorkout(val id: Long, val session: PlannedSession, val sessionId: Long?, val startedAt: LocalDateTime, val sets: List<LoggedSet>)

enum class Perceived(val label: String) { VERY_EASY("Muito fácil"), ADEQUATE("Adequado"), HARD("Difícil"), EXTREMELY_HARD("Extremamente difícil") }

data class WorkoutSummary(
    val minutes: Int,
    val setsDone: Int,
    val volumeKg: Double,
    val records: List<PersonalRecord>,
    val xpGained: Int,
    val levelBefore: Int,
    val levelAfter: Int,
    val weekCompleted: Boolean,
    val perExercise: List<Pair<String, String>>,
)

data class ConsistencyView(
    val weekDone: Int,
    val weekPlanned: Int,
    /** Null enquanto o programa não tem semanas anteriores completas. */
    val last4WeeksPct: Int?,
    val streak: Int,
    val xp: XpStatus,
)

data class BodyView(
    val latest: BodyMeasurement?,
    val bmi: MetricResult?,
    val waistToHeight: MetricResult?,
    val trend: FluctuationReport,
    val weights: List<WeightEntry>,
    val waists: List<Pair<LocalDate, Double>>,
)

data class WaterView(val target: HydrationTarget, val progress: HydrationProgress, val last7: List<Pair<LocalDate, Int>>)

data class NutritionView(
    val targets: NutritionTargets,
    val meals: List<MealRow>,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
)

data class SubmitOutcome(val profile: UserProfile, val screening: ScreeningResult, val result: ProgramResult)

/**
 * Casos de uso do aplicativo. A tela só chama isto; isto só chama os motores do core e o
 * user.db. Nenhuma prescrição é criada aqui — apenas orquestração e persistência.
 */
class FitKing(val kb: KnowledgeBase, db: SqlDatabase, val clock: AppClock = SystemAppClock) {
    val repo = UserRepository(db, kb)
    val why = WhyService(kb)
    private val generator = ProgramGenerator(kb)
    private val screening = SafetyScreening(kb)
    private val adapter = SessionAdapter(kb)
    private val progression = ProgressionEngine(kb)
    private val scorer = RecoveryScorer(kb.ruleSet.recovery)
    private val gamification = Gamification(kb.ruleSet.gamification)
    private val hydration = HydrationEngine(kb.ruleSet.hydration)
    private val energy = EnergyEstimator(kb.ruleSet.nutrition)
    private val bodyMetrics = BodyMetrics(kb.ruleSet.bodyMetrics)
    private val weightTrend = WeightTrend(kb.ruleSet.weightTrend)
    private val substitutions = SubstitutionEngine(kb)
    private val missedPlanner = MissedWorkoutPlanner(kb)
    private val dashboard = VolumeDashboard(kb)
    private val deload = DeloadAdvisor(kb)
    private val simulator = ProgramSimulator(kb)
    private val sessionClock = SessionClock(kb.ruleSet.timing.params)

    private fun now() = clock.now()
    private fun today() = clock.now().toLocalDate()

    // =====================================================================================
    // Perfil e questionário
    // =====================================================================================

    fun hasProfile(): Boolean = repo.hasUser()

    fun profile(): UserProfile? = repo.loadProfile(today())

    fun sweat(): SweatLevel = repo.pref(UserRepository.PREF_SWEAT)?.let(SweatLevel::valueOf) ?: SweatLevel.MODERATE
    fun hotClimate(): Boolean = repo.pref(UserRepository.PREF_HOT) == "true"

    /** Respostas atuais (para refazer o questionário já preenchido). */
    fun currentAnswers(): Answers {
        val p = profile() ?: return Answers()
        val waist = repo.measurements().lastOrNull { it.waistCm != null }?.waistCm?.roundToInt()
        return Answers.from(p, waist, repo.safetyAnswers(), sweat(), hotClimate())
    }

    fun submit(a: Answers): SubmitOutcome {
        val old = profile()
        val profile = a.toProfile(old?.exerciseHistory.orEmpty(), old?.favoriteExercises.orEmpty(), old?.excludedExercises.orEmpty())
        val result = screening.evaluate(profile, a.safety)
        val generated = generator.generate(profile, result)
        repo.saveProfile(profile, a.safety, a.waistCm, a.sweat, a.hot, now())
        dismissPriorityHint() // o questionário atual já tem a pergunta de prioridade
        saveNutritionTargets(profile)
        if (generated is ProgramResult.Generated) {
            repo.saveProgram(generated.program, now())
            repo.clearWeekPlans()
        }
        repo.logEvent("questionnaire", null, now())
        return SubmitOutcome(profile, result, generated)
    }

    fun screening(): ScreeningResult? = profile()?.let { screening.evaluate(it, repo.safetyAnswers()) }

    /** Gera de novo o programa a partir do perfil salvo (ex.: depois de registrar dor). */
    fun regenerate(): ProgramResult? {
        val p = profile() ?: return null
        val r = generator.generate(p, screening.evaluate(p, repo.safetyAnswers()))
        if (r is ProgramResult.Generated) {
            repo.saveProgram(r.program, now())
            repo.clearWeekPlans()
        }
        return r
    }

    private fun saveNutritionTargets(p: UserProfile) {
        val t = energy.targets(p)
        repo.saveNutritionTargets(today(), t.targetKcal, t.proteinG.first, t.proteinG.last, t.energyGoal)
    }

    /** Dor relatada depois do questionário: vira limitação ativa e o programa é refeito com os filtros. */
    fun reportPain(joint: Joint, severity: Int): ProgramResult? {
        val current = repo.activeLimitations().filter { it.joint != joint }
        repo.replaceLimitations(current + JointLimitation(joint, severity), now())
        if (severity > 0 && repo.safetyAnswers()["current_pain"] != true) {
            // Mantém a triagem coerente com a dor registrada.
            val answers = repo.safetyAnswers().toMutableMap().apply { put("current_pain", true) }
            profile()?.let { repo.saveProfile(it, answers, null, sweat(), hotClimate(), now()) }
        }
        return regenerate()
    }

    fun resolvePain(joint: Joint): ProgramResult? {
        repo.replaceLimitations(repo.activeLimitations().filter { it.joint != joint }, now())
        return regenerate()
    }

    // =====================================================================================
    // Programa e semana
    // =====================================================================================

    fun program(): StoredProgram? = repo.activeProgram()

    /**
     * Checagem "seu objetivo × seu treino" recalculada no programa atual — continua valendo depois de
     * trocas de exercício. Usa as prioridades com que o programa foi gerado.
     */
    fun goalCheck(): AlignmentReport? {
        val p = profile() ?: return null
        val program = program()?.program ?: return null
        return GoalAlignment(kb).check(program, p.copy(priorities = program.priorities))
    }

    /** Programa salvo sem prioridade e a pessoa ainda não viu a novidade → sugerir na Home. */
    fun showPriorityHint(): Boolean =
        repo.pref(UserRepository.PREF_PRIORITY_HINT) == null && program()?.program?.priorities?.isEmpty() == true

    fun dismissPriorityHint() = repo.setPref(UserRepository.PREF_PRIORITY_HINT, "dismissed")

    fun weekStart(date: LocalDate = today()): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    private fun effectiveSessions(stored: StoredProgram, plan: WeekPlan?): List<PlannedSession> {
        plan ?: return stored.program.sessions
        val before = stored.program.sessions.filter { s -> s.day.let { it != null && it < plan.fromDay } }
        return (before + plan.sessions.filter { it.day != null }).sortedBy { it.day }
    }

    fun week(date: LocalDate = today()): WeekView? {
        val stored = program() ?: return null
        val ws = weekStart(date)
        val plan = repo.weekPlan(ws)
        val sessions = effectiveSessions(stored, plan)
        val workouts = repo.workoutsBetween(ws, ws.plusDays(6))
        val missed = repo.missedBetween(ws, ws.plusDays(6))
        val doneKeys = workouts.mapNotNull { it.sessionKey }.toSet()
        val today = today()
        val days = (0L..6L).map { i ->
            val d = ws.plusDays(i)
            val s = sessions.firstOrNull { it.day == d.dayOfWeek }
            val wk = workouts.filter { it.startedAt.toLocalDate() == d }
            val decision = missed.firstOrNull { it.missedOn == d }
            val status = when {
                s == null -> if (wk.isNotEmpty()) DayStatus.DONE else DayStatus.REST
                wk.any { it.sessionKey == s.key } -> DayStatus.DONE
                // Perdido e já decidido (remarcado/ignorado): não vira "feito" quando a sessão é feita em outro dia.
                d.isBefore(today) && decision != null -> DayStatus.MISSED_RESOLVED
                s.key in doneKeys -> DayStatus.DONE
                d == today -> DayStatus.TODAY
                d.isBefore(today) -> if (decision != null) DayStatus.MISSED_RESOLVED else DayStatus.MISSED
                else -> DayStatus.PLANNED
            }
            DayPlan(d, s, s?.let { stored.sessionIds[it.key] }, status, wk, decision?.option)
        }
        return WeekView(ws, days, plan, stored)
    }

    fun todayView(): TodayView? {
        val week = week() ?: return null
        val date = today()
        val todayPlan = week.days.first { it.date == date }
        val readiness = repo.readiness(1).firstOrNull { it.at.toLocalDate() == date }
        val readinessResult = readiness?.let {
            scorer.score(ReadinessCheck(it.sleep, it.energy, it.soreness, it.stress, it.motivation))
        }
        val quick = repo.pref(quickKey(date))?.toIntOrNull()
        var session: PlannedSession? = null
        var title: String? = null
        val changes = mutableListOf<SessionChange>()
        val notes = mutableListOf<Explanation>()
        if (todayPlan.status == DayStatus.TODAY && todayPlan.session != null) {
            val adapted = adapt(todayPlan.session, readinessResult, quick)
            session = adapted.first
            title = adapted.second
            changes += adapted.third.first
            notes += adapted.third.second
        }
        val next = week.days.firstOrNull { it.date.isAfter(date) && it.status == DayStatus.PLANNED }
        val pending = week.days.filter { it.status == DayStatus.MISSED }
        return TodayView(date, todayPlan, session, todayPlan.sessionId, title, changes, notes, quick, readinessResult, readiness?.id,
            next, pending, repo.unfinishedWorkout())
    }

    /** Aplica prontidão (autorregulação) e depois o limite de tempo do dia. */
    fun adapt(base: PlannedSession, readiness: ReadinessResult?, minutes: Int?): Triple<PlannedSession, String, Pair<List<SessionChange>, List<Explanation>>> {
        var s = base
        var title = base.name
        val changes = mutableListOf<SessionChange>()
        val notes = mutableListOf<Explanation>()
        if (readiness != null) {
            val p = profile()
            val a = adapter.forReadiness(s, readiness, p?.let { UserConstraints.of(it) } ?: UserConstraints(TrainingTier.NOVICE, emptySet()))
            s = a.session; changes += a.changes; notes += a.explanations
            if (a.changes.isNotEmpty()) title = a.title
        }
        if (minutes != null) {
            val a = adapter.forTime(s, minutes)
            s = a.session; changes += a.changes; notes += a.explanations
            title = a.title
        }
        return Triple(s, title, changes to notes)
    }

    private fun quickKey(d: LocalDate) = "quick:$d"

    /** "Tenho só X minutos hoje" (null volta ao tempo normal). */
    fun setQuickMinutes(minutes: Int?) = repo.setPref(quickKey(today()), minutes?.toString())

    fun checkIn(c: ReadinessCheck): ReadinessResult {
        val r = scorer.score(c)
        repo.saveReadiness(ReadinessRow(0, now(), r.score, c.sleep, c.energy, c.soreness, c.stress, c.motivation))
        award(XpEvent.CHECK_IN, oncePerDay = true)
        return r
    }

    fun clearCheckIn() {
        repo.readiness(5).filter { it.at.toLocalDate() == today() }.forEach { repo.deleteReadiness(it.id) }
    }

    fun missedOptions(day: DayPlan): MissedWorkoutReport? {
        val week = week() ?: return null
        val p = profile() ?: return null
        val program = week.program.program.copy(sessions = week.days.mapNotNull { it.session })
        return missedPlanner.options(program, p, day.day, today().dayOfWeek)
    }

    fun applyMissed(day: DayPlan, option: MissedOption) {
        val ws = weekStart()
        if (option.key != 'C' && option.available) {
            repo.saveWeekPlan(
                WeekPlan(ws, today().dayOfWeek, "Treino de ${day.day.pt()} não realizado — opção ${option.key}", option.remainingWeek), now(),
            )
        }
        repo.recordMissed(day.sessionId, day.date, option.key, now())
    }

    // =====================================================================================
    // Reorganizar a semana (trocar dias, fazer hoje o treino de outro dia)
    // =====================================================================================

    private val reorder by lazy { WeekReorder(this) }

    /**
     * Troca os treinos de dois dias (hoje ou futuros) da semana atual; um deles pode ser descanso.
     * Cada sessão é reajustada ao tempo do novo dia. [permanent] = muda também o programa base.
     */
    fun swapDays(a: LocalDate, b: LocalDate, permanent: Boolean): ReorderResult = reorder.swapDays(a, b, permanent)

    /** Faz hoje o treino de outro dia desta semana (inclusive um treino perdido). Só esta semana. */
    fun doToday(from: LocalDate): ReorderResult = reorder.doToday(from)

    /** Prévia de [doToday]: para onde iria o treino de hoje (nulo se hoje é descanso). */
    fun doTodayDisplaces(from: LocalDate): Displaced? = reorder.displaced(from)

    // =====================================================================================
    // Exercícios: por que, substituir, dor
    // =====================================================================================

    fun constraints(): UserConstraints? = profile()?.let { UserConstraints.of(it) }

    fun substitutes(exercise: Exercise, painJoint: Joint? = null, painSeverity: Int = 3): SubstitutionResult? {
        val p = profile() ?: return null
        return substitutions.find(exercise.id, UserConstraints.of(p), p.focus, painJoint, limit = 6, painSeverity = painSeverity)
    }

    /** Troca um exercício na sessão indicada (ou em todo o programa). A prescrição segue a regra do papel do slot. */
    fun swap(from: ExerciseId, to: ExerciseId, sessionKey: String?) {
        val stored = program() ?: return
        val newEx = kb.exercise(to)
        fun swapIn(s: PlannedSession): PlannedSession {
            if (s.exercises.none { it.exercise.id == from }) return s
            val items = s.exercises.map { pe ->
                if (pe.exercise.id != from) pe else {
                    val p = generator.prescription(stored.program.focus, pe.role, stored.program.tier, newEx)
                    PlannedExercise(newEx, pe.role, pe.sets, p, note = "Substitui ${pe.exercise.name}")
                }
            }
            return s.copy(exercises = items, estimatedMinutes = sessionClock.estimateMinutes(items))
        }
        for (s in stored.program.sessions) {
            if (sessionKey != null && s.key != sessionKey) continue
            val swapped = swapIn(s)
            if (swapped !== s) repo.replaceSessionExercises(stored.sessionIds.getValue(s.key), swapped)
        }
        val ws = weekStart()
        repo.weekPlan(ws)?.let { plan ->
            repo.saveWeekPlan(plan.copy(sessions = plan.sessions.map { if (sessionKey == null || it.key == sessionKey) swapIn(it) else it }), now())
        }
        repo.logEvent("swap", "{\"from\":\"${from.value}\",\"to\":\"${to.value}\"}", now())
    }

    fun setExercisePreference(id: ExerciseId, preference: String?) = repo.setExercisePreference(id, preference)

    /** Troca só no treino em andamento (ex.: aparelho ocupado). */
    fun swapInWorkout(workoutId: Long, from: ExerciseId, to: ExerciseId) {
        val w = repo.workout(workoutId) ?: return
        val plan = w.plan ?: return
        val stored = program()
        val newEx = kb.exercise(to)
        val items = plan.exercises.map { pe ->
            if (pe.exercise.id != from) pe else {
                val p = generator.prescription(stored?.program?.focus ?: TrainingFocus.HYPERTROPHY, pe.role, stored?.program?.tier ?: TrainingTier.NOVICE, newEx)
                PlannedExercise(newEx, pe.role, pe.sets, p, note = "Substitui ${pe.exercise.name}")
            }
        }
        repo.updateWorkoutPlan(workoutId, plan.copy(exercises = items, estimatedMinutes = sessionClock.estimateMinutes(items)))
    }

    /** Carga inicial sugerida quando não há histórico (o usuário ajusta pelo RIR). */
    fun defaultLoad(ex: Exercise): Double = when (ex.loadType) {
        LoadType.BARBELL -> barKg()
        LoadType.SMITH, LoadType.MACHINE, LoadType.CABLE -> 20.0
        LoadType.DUMBBELL -> 8.0
        LoadType.KETTLEBELL -> 12.0
        LoadType.BODYWEIGHT, LoadType.BAND -> 0.0
    }

    // =====================================================================================
    // Execução do treino
    // =====================================================================================

    fun startWorkout(session: PlannedSession, sessionId: Long?, readinessId: Long?): ActiveWorkout {
        repo.unfinishedWorkout()?.let { return activeFrom(it) }
        val id = repo.startWorkout(sessionId, readinessId, session, now())
        return ActiveWorkout(id, session, sessionId, now(), emptyList())
    }

    fun activeWorkout(): ActiveWorkout? = repo.unfinishedWorkout()?.let(::activeFrom)

    private fun activeFrom(w: WorkoutRow) = ActiveWorkout(w.id, w.plan ?: PlannedSession("?", "Treino", emptyList()), w.programSessionId, w.startedAt, repo.sets(w.id))

    fun suggestion(pe: PlannedExercise): ProgressionSuggestion =
        progression.suggest(pe.exercise, pe.prescription, pe.sets, repo.exerciseLogs(pe.exercise.id))

    fun lastLog(id: ExerciseId): ExerciseLog? = repo.exerciseLogs(id).lastOrNull()

    fun loadStep(ex: Exercise): Double = kb.ruleSet.progression.params.increments[ex.loadType] ?: 2.5

    fun barKg(): Double = repo.pref(UserRepository.PREF_BAR_KG)?.toDoubleOrNull() ?: 20.0
    fun setBarKg(kg: Double) = repo.setPref(UserRepository.PREF_BAR_KG, kg.toString())

    fun warmup(pe: PlannedExercise, workingKg: Double): List<WarmupSet> = WarmupGenerator.forWorkingLoad(pe.exercise, workingKg, barKg())

    fun plates(kg: Double, bar: Double = barKg()): PlateLoad = PlateCalculator.compute(kg, bar)

    fun logSet(workoutId: Long, exercise: ExerciseId, setIndex: Int, loadKg: Double, reps: Int, rir: Int?, warmup: Boolean = false): Long =
        repo.logSet(workoutId, exercise, setIndex, loadKg, reps, rir, warmup, now())

    fun undoSet(setId: Long) = repo.deleteSet(setId)

    fun setsOf(workoutId: Long): List<LoggedSet> = repo.sets(workoutId)

    fun discardWorkout(workoutId: Long) = repo.discardWorkout(workoutId)

    fun finishWorkout(workoutId: Long, perceived: Perceived?): WorkoutSummary {
        val w = repo.workout(workoutId) ?: error("treino $workoutId não existe")
        val previous = repo.exerciseLogs()
        val sets = repo.sets(workoutId).filter { !it.warmup }
        val levelBefore = xpStatus().level
        repo.finishWorkout(workoutId, perceived?.name, now())
        val date = w.startedAt.toLocalDate()
        val records = mutableListOf<PersonalRecord>()
        val per = mutableListOf<Pair<String, String>>()
        for ((exId, list) in sets.groupBy { it.exerciseId }) {
            val log = ExerciseLog(exId, date, list.map { SetLog(it.loadKg, it.reps, it.rir) })
            val name = kb.exerciseOrNull(exId)?.name ?: exId.value
            per += name to list.joinToString(" · ") { s -> (if (s.loadKg > 0) "${Fmt.num(s.loadKg, 2)}×" else "") + s.reps }
            for (pr in PersonalRecords.detect(kb, log, previous)) {
                records += pr
                val value = when (pr.type) {
                    PrType.HEAVIEST_LOAD -> log.topLoad
                    PrType.MOST_REPS_AT_LOAD -> list.maxOf { it.reps }.toDouble()
                    PrType.BEST_E1RM -> OneRepMax.bestOf(log) ?: 0.0
                    PrType.BEST_SESSION_VOLUME -> log.volumeKg
                }
                repo.savePr(exId, pr.type.name, value, date, workoutId, pr.description)
            }
        }
        var xp = award(XpEvent.WORKOUT_COMPLETED)
        repeat(records.size) { xp += award(XpEvent.PERSONAL_RECORD) }
        val week = week()
        val weekCompleted = week != null && week.planned > 0 && week.done >= week.planned &&
            !repo.hasXpBetween(XpEvent.WEEK_COMPLETED.key, week.weekStart, week.weekStart.plusDays(6))
        if (weekCompleted) xp += award(XpEvent.WEEK_COMPLETED)
        val minutes = ChronoUnit.MINUTES.between(w.startedAt, now()).toInt().coerceAtLeast(1)
        return WorkoutSummary(minutes, sets.size, sets.sumOf { it.loadKg * it.reps }, records, xp, levelBefore, xpStatus().level, weekCompleted, per)
    }

    // =====================================================================================
    // Progresso
    // =====================================================================================

    fun volumeWeek(): List<MuscleVolumeStatus> {
        val week = week() ?: return emptyList()
        val program = week.program.program.copy(sessions = week.days.mapNotNull { it.session })
        val logs = repo.exerciseLogs().filter { !it.date.isBefore(week.weekStart) && !it.date.isAfter(week.weekStart.plusDays(6)) }
        return dashboard.week(program, logs, today().dayOfWeek)
    }

    /** Tendência dos exercícios principais do programa. */
    fun trends(): List<Pair<Exercise, PerformanceTrend>> {
        val program = program()?.program ?: return emptyList()
        val logs = repo.exerciseLogs()
        val mains = program.sessions.flatMap { it.exercises }.filter { it.role == SlotRole.MAIN }.map { it.exercise }.distinctBy { it.id }
        val logged = logs.map { it.exerciseId }.toSet()
        val extra = logged.filter { id -> mains.none { it.id == id } }.mapNotNull { kb.exerciseOrNull(it) }
        return (mains + extra).map { it to Trends.of(it.id, logs) }
    }

    fun deloadAdvice(): DeloadAdvice {
        val trends = trends().filter { it.second.direction != TrendDirection.INSUFFICIENT_DATA }.map { it.second }
        val recent = repo.readiness(7).filter { !it.at.toLocalDate().isBefore(today().minusDays(7)) }.map { it.score }
        return deload.advise(trends, recent)
    }

    fun records(): List<PrRow> = repo.prs()

    fun exerciseLogs(id: ExerciseId): List<ExerciseLog> = repo.exerciseLogs(id)

    fun xpStatus(): XpStatus = gamification.status(repo.xpEvents().mapNotNull { (k, _) -> XpEvent.values().firstOrNull { it.key == k } })

    fun streak(): Int = gamification.streak(repo.activeDays(), today())

    fun consistency(): ConsistencyView {
        val week = week()
        val stored = program()
        var planned = 0
        var done = 0
        if (stored != null) {
            for (w in 1..4) {
                val ws = weekStart().minusWeeks(w.toLong())
                if (ws.plusDays(6).isBefore(stored.createdAt.toLocalDate())) continue
                val view = week(ws) ?: continue
                planned += view.planned; done += view.done
            }
        }
        return ConsistencyView(week?.done ?: 0, week?.planned ?: 0, if (planned == 0) null else Consistency.pct(planned, done), streak(), xpStatus())
    }

    private fun award(e: XpEvent, oncePerDay: Boolean = false): Int {
        if (oncePerDay && repo.hasXpOn(e.key, today())) return 0
        val pts = gamification.points(e)
        repo.addXp(e.key, pts, now())
        return pts
    }

    // =====================================================================================
    // Corpo
    // =====================================================================================

    fun logBody(weightKg: Double?, waistCm: Double?) {
        repo.addMeasurement(BodyMeasurement(today(), weightKg = weightKg, waistCm = waistCm))
    }

    fun body(): BodyView {
        val all = repo.measurements()
        val height = profile()?.heightCm
        val weights = all.mapNotNull { m -> m.weightKg?.let { WeightEntry(m.date, it) } }
        val waists = all.mapNotNull { m -> m.waistCm?.let { m.date to it } }
        val lastW = weights.lastOrNull()?.kg
        val lastWaist = waists.lastOrNull()?.second
        return BodyView(
            all.lastOrNull(),
            if (lastW != null && height != null) bodyMetrics.bmi(lastW, height) else null,
            if (lastWaist != null && height != null) bodyMetrics.waistToHeight(lastWaist, height) else null,
            weightTrend.analyze(weights), weights, waists,
        )
    }

    fun photos() = repo.photos()
    fun addPhoto(angle: String, uri: String) = repo.addPhoto(today(), angle, uri)
    fun deletePhoto(id: Long) = repo.deletePhoto(id)

    // =====================================================================================
    // Água, sono, nutrição, cardio, mobilidade
    // =====================================================================================

    fun water(): WaterView? {
        val p = profile() ?: return null
        val today = today()
        val session = todayView()?.session?.estimatedMinutes ?: 0
        val cardio = repo.cardio(20).filter { it.at.toLocalDate() == today }.sumOf { it.minutes }.roundToInt()
        val target = hydration.target(HydrationInput(p.weightKg, session + cardio, sweat(), hotClimate()))
        val byDay = repo.waterByDay(today.minusDays(6), today)
        val logs = byDay.map { WaterLog(it.key, it.value) }
        return WaterView(target, hydration.progress(target, logs, today), (6L downTo 0L).map { today.minusDays(it).let { d -> d to (byDay[d] ?: 0) } })
    }

    fun addWater(ml: Int): WaterView? {
        repo.addWater(ml, now())
        val v = water()
        if (v != null && v.progress.consumedMl >= v.target.totalMl) award(XpEvent.HYDRATION_GOAL, oncePerDay = true)
        return v
    }

    fun undoWater() = repo.undoLastWater(today())

    fun setHydrationPrefs(sweat: SweatLevel, hot: Boolean) {
        repo.setPref(UserRepository.PREF_SWEAT, sweat.name)
        repo.setPref(UserRepository.PREF_HOT, hot.toString())
    }

    /** Sono da noite passada. */
    fun logSleep(hours: Double, quality: Int) = repo.logSleep(today().minusDays(1), hours, quality)

    fun sleep() = repo.sleep()

    fun nutrition(): NutritionView? {
        val p = profile() ?: return null
        val meals = repo.mealsOn(today())
        val items = meals.flatMap { it.items }
        return NutritionView(energy.targets(p), meals, items.sumOf { it.kcal }, items.sumOf { it.proteinG ?: 0.0 },
            items.sumOf { it.carbsG ?: 0.0 }, items.sumOf { it.fatG ?: 0.0 }, items.sumOf { it.fiberG ?: 0.0 })
    }

    fun logFood(food: Food, portions: Double, label: String?) {
        val item = MealItem(food.name, food, food.defaultServingG * portions)
        repo.logMeal(label, listOf(MealItemRow(food.id, item.grams, item.kcal, item.proteinG, item.carbsG, item.fatG, item.fiberG)), now())
        if (repo.mealsOn(today()).size <= MAX_MEAL_XP_PER_DAY) award(XpEvent.MEAL_LOGGED)
    }

    fun deleteMeal(id: Long) = repo.deleteMeal(id)

    fun logCardio(kind: String, minutes: Int, rpe: Int?) = repo.logCardio(kind, minutes.toDouble(), rpe, now())
    fun cardio() = repo.cardio()
    fun deleteCardio(id: Long) = repo.deleteCardio(id)

    fun logMobility(region: String, minutes: Int) = repo.logMobility(region, minutes.toDouble(), now())
    fun mobility() = repo.mobility()

    // =====================================================================================
    // Simulador e dados
    // =====================================================================================

    fun simulate(scenarios: List<Scenario>): Simulation? {
        val p = profile() ?: return null
        return simulator.compare(p, screening.evaluate(p, repo.safetyAnswers()), scenarios)
    }

    /**
     * Chamado depois de [deleteEverything]. O app Android usa para cancelar o alarme dos lembretes e
     * tirar da barra as notificações com nome de treino e números (o appcore não conhece o Android).
     */
    @Volatile var onEverythingDeleted: (() -> Unit)? = null

    fun deleteEverything() {
        repo.deleteEverything()
        onEverythingDeleted?.invoke()
    }

    fun exportJson(): String = repo.exportJson(now())

    companion object {
        const val MAX_MEAL_XP_PER_DAY = 5
    }
}
