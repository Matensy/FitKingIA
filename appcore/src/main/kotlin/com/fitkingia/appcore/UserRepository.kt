package com.fitkingia.appcore

import com.fitkingia.core.body.BodyMeasurement
import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.*
import com.fitkingia.core.program.PlannedSession
import com.fitkingia.core.program.Program
import com.fitkingia.core.program.VolumeCalculator
import com.fitkingia.core.progression.ExerciseLog
import com.fitkingia.core.progression.SetLog
import com.fitkingia.knowledge.sql.SqlDatabase
import com.fitkingia.knowledge.sql.SqlRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/** Programa ativo guardado no user.db, com os ids das sessões (para vincular treinos realizados). */
data class StoredProgram(val id: Long, val program: Program, val createdAt: LocalDateTime, val kbVersion: String, val sessionIds: Map<String, Long>)

data class WeekPlan(val weekStart: LocalDate, val fromDay: DayOfWeek, val reason: String, val sessions: List<PlannedSession>)

data class WorkoutRow(
    val id: Long,
    val programSessionId: Long?,
    val sessionKey: String?,
    val startedAt: LocalDateTime,
    val finishedAt: LocalDateTime?,
    val perceived: String?,
    val plan: PlannedSession?,
)

data class LoggedSet(val id: Long, val workoutId: Long, val exerciseId: ExerciseId, val setIndex: Int, val loadKg: Double, val reps: Int, val rir: Int?, val warmup: Boolean)

data class ReadinessRow(val id: Long, val at: LocalDateTime, val score: Int, val sleep: SleepQuality, val energy: Int, val soreness: Int, val stress: Int, val motivation: Int)

data class PrRow(val exerciseId: ExerciseId, val type: String, val value: Double, val date: LocalDate, val description: String)

data class SleepRow(val id: Long, val nightOf: LocalDate, val hours: Double, val quality: Int)

data class CardioRow(val id: Long, val at: LocalDateTime, val kind: String, val minutes: Double, val rpe: Int?)

data class MobilityRow(val id: Long, val at: LocalDateTime, val region: String, val minutes: Double)

data class MealItemRow(val foodId: FoodId, val grams: Double, val kcal: Double, val proteinG: Double?, val carbsG: Double?, val fatG: Double?, val fiberG: Double?)

data class MealRow(val id: Long, val at: LocalDateTime, val label: String?, val items: List<MealItemRow>)

data class PhotoRow(val id: Long, val date: LocalDate, val angle: String, val uri: String)

data class MissedRow(val programSessionId: Long?, val missedOn: LocalDate, val option: Char?)

/**
 * Todo acesso ao user.db. Um único usuário local (id 1). Datas e horas são gravadas em ISO
 * no fuso do aparelho — o app é local e não sincroniza com servidor.
 */
class UserRepository(private val db: SqlDatabase, private val kb: KnowledgeBase) {

    // -------------------------------------------------------------------------------------
    // Perfil
    // -------------------------------------------------------------------------------------

    fun hasUser(): Boolean = db.single("SELECT id FROM users WHERE id = ?", listOf(USER)) { it.long("id") } != null

    fun saveProfile(p: UserProfile, safety: Map<String, Boolean>, waistCm: Int?, sweat: SweatLevel, hot: Boolean, now: LocalDateTime) = db.transaction {
        val birthYear = now.year - p.age
        val args = listOf(p.name, birthYear, p.sex.name, p.heightCm, p.experience.name, p.environment?.value, p.activityLevel.name)
        if (hasUser()) {
            db.execute("UPDATE users SET name=?, birth_year=?, sex=?, height_cm=?, experience=?, environment=?, activity=? WHERE id=$USER", args)
        } else {
            db.execute("INSERT INTO users(id, name, birth_year, sex, height_cm, experience, environment, activity, created_at) VALUES ($USER,?,?,?,?,?,?,?,?)", args + now.toString())
            db.execute("INSERT INTO consents(user_id, kind, granted, decided_at) VALUES ($USER, 'data_storage', 1, ?)", listOf(now.toString()))
        }
        db.execute("DELETE FROM user_goals WHERE user_id=$USER")
        db.execute("INSERT INTO user_goals(user_id, goal, priority, since) VALUES ($USER, ?, 1, ?)", listOf(p.primaryGoal.name, now.toLocalDate().toString()))
        p.secondaryGoal?.let { db.execute("INSERT INTO user_goals(user_id, goal, priority, since) VALUES ($USER, ?, 2, ?)", listOf(it.name, now.toLocalDate().toString())) }

        db.execute("DELETE FROM user_availability WHERE user_id=$USER")
        for (d in DayOfWeek.values()) {
            val m = p.availability.firstOrNull { it.day == d }?.minutes ?: 0
            db.execute("INSERT INTO user_availability(user_id, day_of_week, minutes) VALUES ($USER, ?, ?)", listOf(d.value, m))
        }
        db.execute("DELETE FROM user_equipment WHERE user_id=$USER")
        p.equipment.forEach { db.execute("INSERT INTO user_equipment(user_id, equipment_id) VALUES ($USER, ?)", listOf(it.value)) }

        replaceLimitations(p.limitations, now)

        db.execute("DELETE FROM user_sports WHERE user_id=$USER")
        p.sports.forEach {
            db.execute("INSERT OR IGNORE INTO user_sports(user_id, sport_id, day_of_week, intensity) VALUES ($USER, ?, ?, ?)", listOf(it.sportId.value, it.day.value, it.intensity))
        }
        val previous = safetyAnswers()
        safety.filter { (q, yes) -> previous[q] != yes }.forEach { (q, yes) ->
            db.execute("INSERT OR REPLACE INTO safety_answers(user_id, question_id, answer, answered_at) VALUES ($USER, ?, ?, ?)", listOf(q, yes, now.toString()))
        }
        setPref(PREF_MAX_DAYS, p.maxTrainingDays?.toString())
        setPref(PREF_SPLIT, p.preferredSplit?.value)
        setPref(PREF_SWEAT, sweat.name)
        setPref(PREF_HOT, hot.toString())

        val last = measurements().lastOrNull()
        if (last?.weightKg != p.weightKg || (waistCm != null && last.waistCm != waistCm.toDouble())) {
            addMeasurement(BodyMeasurement(now.toLocalDate(), weightKg = p.weightKg, waistCm = waistCm?.toDouble() ?: last?.waistCm))
        }
    }

    fun replaceLimitations(limitations: List<JointLimitation>, now: LocalDateTime) {
        db.execute("UPDATE user_limitations SET resolved_at=? WHERE user_id=$USER AND resolved_at IS NULL", listOf(now.toString()))
        limitations.forEach {
            db.execute("INSERT INTO user_limitations(user_id, joint, severity, note, reported_at) VALUES ($USER, ?, ?, ?, ?)",
                listOf(it.joint.name, it.severity, it.note, now.toString()))
        }
    }

    private class UserHead(val name: String, val birthYear: Int, val sex: String, val height: Double, val experience: String, val environment: String?, val activity: String)

    fun loadProfile(today: LocalDate): UserProfile? {
        val r = db.single("SELECT * FROM users WHERE id=$USER") {
            UserHead(it.str("name"), it.int("birth_year"), it.str("sex"), it.double("height_cm"), it.str("experience"), it.strOrNull("environment"), it.str("activity"))
        } ?: return null
        val goals = db.query("SELECT goal, priority FROM user_goals WHERE user_id=$USER ORDER BY priority") { Goal.valueOf(it.str("goal")) }
        val availability = db.query("SELECT day_of_week, minutes FROM user_availability WHERE user_id=$USER AND minutes > 0 ORDER BY day_of_week") {
            DayAvailability(DayOfWeek.of(it.int("day_of_week")), it.int("minutes"))
        }
        val equipment = db.query("SELECT equipment_id FROM user_equipment WHERE user_id=$USER") { EquipmentId(it.str("equipment_id")) }.toSet()
        val sports = db.query("SELECT * FROM user_sports WHERE user_id=$USER ORDER BY day_of_week") {
            SportCommitment(SportId(it.str("sport_id")), DayOfWeek.of(it.int("day_of_week")), it.int("intensity"))
        }.filter { s -> kb.sports.any { it.id == s.sportId } }
        val prefs = db.query("SELECT exercise_id, preference FROM user_exercise_preferences WHERE user_id=$USER") { ExerciseId(it.str("exercise_id")) to it.str("preference") }
        val weight = measurements().lastOrNull { it.weightKg != null }?.weightKg ?: 70.0
        return UserProfile(
            name = r.name,
            age = today.year - r.birthYear,
            sex = Sex.valueOf(r.sex),
            heightCm = r.height,
            weightKg = weight,
            primaryGoal = goals.first(),
            secondaryGoal = goals.getOrNull(1),
            experience = ExperienceLevel.valueOf(r.experience),
            environment = r.environment?.let(::EnvironmentId),
            equipment = equipment,
            availability = availability,
            sports = sports,
            limitations = activeLimitations(),
            excludedExercises = prefs.filter { it.second == "excluded" }.map { it.first }.toSet(),
            favoriteExercises = prefs.filter { it.second == "favorite" }.map { it.first }.toSet(),
            exerciseHistory = db.query("SELECT DISTINCT exercise_id FROM workout_sets") { ExerciseId(it.str("exercise_id")) }.toSet(),
            preferredSplit = pref(PREF_SPLIT)?.let(::SplitId),
            maxTrainingDays = pref(PREF_MAX_DAYS)?.toIntOrNull(),
            activityLevel = ActivityLevel.valueOf(r.activity),
        )
    }

    fun activeLimitations(): List<JointLimitation> =
        db.query("SELECT joint, severity, note FROM user_limitations WHERE user_id=$USER AND resolved_at IS NULL ORDER BY id") {
            JointLimitation(Joint.valueOf(it.str("joint")), it.int("severity"), it.strOrNull("note"))
        }

    /** Última resposta de cada pergunta de segurança. */
    fun safetyAnswers(): Map<String, Boolean> =
        db.query("SELECT question_id, answer FROM safety_answers WHERE user_id=$USER ORDER BY answered_at, rowid") { it.str("question_id") to it.bool("answer") }.toMap()

    fun setExercisePreference(id: ExerciseId, preference: String?) {
        db.execute("DELETE FROM user_exercise_preferences WHERE user_id=$USER AND exercise_id=?", listOf(id.value))
        if (preference != null) db.execute("INSERT INTO user_exercise_preferences(user_id, exercise_id, preference) VALUES ($USER, ?, ?)", listOf(id.value, preference))
    }

    // -------------------------------------------------------------------------------------
    // Preferências (chave/valor)
    // -------------------------------------------------------------------------------------

    fun pref(key: String): String? = db.single("SELECT value FROM user_preferences WHERE user_id=$USER AND key=?", listOf(key)) { it.str("value") }

    fun setPref(key: String, value: String?) {
        db.execute("DELETE FROM user_preferences WHERE user_id=$USER AND key=?", listOf(key))
        if (value != null) db.execute("INSERT INTO user_preferences(user_id, key, value) VALUES ($USER, ?, ?)", listOf(key, value))
    }

    // -------------------------------------------------------------------------------------
    // Programa
    // -------------------------------------------------------------------------------------

    fun saveProgram(program: Program, now: LocalDateTime): Long = db.transaction {
        db.execute("UPDATE programs SET active=0 WHERE user_id=$USER")
        val explanations = buildJsonObject {
            put("explanations", Codec.explanations(program.explanations))
            put("warnings", Codec.explanations(program.warnings))
        }.toString()
        val version = kb.meta["content_version"] ?: "?"
        val pid = db.insert(
            "INSERT INTO programs(user_id, split_id, focus, tier, created_at, kb_content_version, active, explanations_json) VALUES ($USER,?,?,?,?,?,1,?)",
            listOf(program.split.id.value, program.focus.name, program.tier.name, now.toString(), version, explanations),
        )
        program.sessions.forEachIndexed { i, s -> insertSession(pid, i, s) }
        db.execute(
            "INSERT INTO recommendation_log(user_id, shown_at, kind, provenance, rule_ids, kb_content_version, payload_json) VALUES ($USER,?,?,?,?,?,?)",
            listOf(now.toString(), "program", "SYSTEM_RULE", (program.explanations + program.warnings).mapNotNull { it.ruleId?.value }.distinct().joinToString(","),
                version, buildJsonObject { put("program_id", JsonPrimitive(pid)); put("split", JsonPrimitive(program.split.id.value)) }.toString()),
        )
        pid
    }

    private fun insertSession(programId: Long, position: Int, s: PlannedSession) {
        val sid = db.insert(
            "INSERT INTO program_sessions(program_id, position, key, name, day_of_week, budget_minutes, estimated_minutes) VALUES (?,?,?,?,?,?,?)",
            listOf(programId, position, s.key, s.name, s.day?.value, s.budgetMinutes, s.estimatedMinutes),
        )
        s.exercises.forEachIndexed { j, e -> insertExercise(sid, j, e) }
    }

    private fun insertExercise(sessionId: Long, position: Int, e: com.fitkingia.core.program.PlannedExercise) {
        val p = e.prescription
        db.execute(
            "INSERT INTO program_exercises(session_id, position, exercise_id, role, sets, reps_min, reps_max, rir, rest_seconds, rest_min, rest_max, hold_min, hold_max, tempo, note) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
            listOf(sessionId, position, e.exercise.id.value, e.role.name, e.sets, p.reps.first, p.reps.last, p.rir, e.restSeconds,
                p.restSeconds.first, p.restSeconds.last, p.holdSeconds?.first, p.holdSeconds?.last, p.tempo, e.note),
        )
    }

    /** Substitui as sessões do programa ativo (ex.: troca de exercício), mantendo ids das sessões. */
    fun replaceSessionExercises(sessionId: Long, session: PlannedSession) = db.transaction {
        db.execute("DELETE FROM program_exercises WHERE session_id=?", listOf(sessionId))
        session.exercises.forEachIndexed { j, e -> insertExercise(sessionId, j, e) }
        db.execute("UPDATE program_sessions SET estimated_minutes=? WHERE id=?", listOf(session.estimatedMinutes, sessionId))
    }

    fun activeProgram(): StoredProgram? {
        data class Head(val id: Long, val split: String, val focus: String, val tier: String, val created: String, val version: String, val json: String?)
        val h = db.single("SELECT * FROM programs WHERE user_id=$USER AND active=1 ORDER BY id DESC LIMIT 1") {
            Head(it.long("id"), it.str("split_id"), it.str("focus"), it.str("tier"), it.str("created_at"), it.str("kb_content_version"), it.strOrNull("explanations_json"))
        } ?: return null
        val split = kb.splits.firstOrNull { it.id.value == h.split } ?: return null
        data class SRow(val id: Long, val key: String, val name: String, val day: Int?, val budget: Int?, val est: Int)
        val sessionRows = db.query("SELECT * FROM program_sessions WHERE program_id=? ORDER BY position", listOf(h.id)) {
            SRow(it.long("id"), it.str("key"), it.str("name"), it.intOrNull("day_of_week"), it.intOrNull("budget_minutes"), it.int("estimated_minutes"))
        }
        val sessions = sessionRows.map { s ->
            val items = db.query("SELECT * FROM program_exercises WHERE session_id=? ORDER BY position", listOf(s.id)) { r ->
                val ex = kb.exerciseOrNull(ExerciseId(r.str("exercise_id")))
                ex?.let {
                    com.fitkingia.core.program.PlannedExercise(
                        it, SlotRole.valueOf(r.str("role")), r.int("sets"),
                        com.fitkingia.core.knowledge.RepPrescription(
                            r.int("reps_min")..r.int("reps_max"), r.int("rir"), r.int("rest_min")..r.int("rest_max"), r.str("tempo"),
                            r.intOrNull("hold_min")?.let { a -> a..r.int("hold_max") },
                        ),
                        r.int("rest_seconds"), note = r.strOrNull("note"),
                    )
                }
            }.filterNotNull()
            PlannedSession(s.key, s.name, items, s.day?.let(DayOfWeek::of), s.budget, s.est)
        }
        val focus = TrainingFocus.valueOf(h.focus)
        val tier = TrainingTier.valueOf(h.tier)
        val volume = kb.ruleSet.volume.params
        val target = volume.target(focus, tier)
        val json = h.json?.let { Json.parseToJsonElement(it).jsonObject }
        val program = Program(
            split, focus, tier, sessions, VolumeCalculator(volume).weekly(sessions),
            kb.trackedMuscles.associate { it.id to target.scaled(it.volumeFactor) },
            Codec.explanationsFrom(json?.get("explanations")?.jsonArray), Codec.explanationsFrom(json?.get("warnings")?.jsonArray),
        )
        return StoredProgram(h.id, program, LocalDateTime.parse(h.created), h.version, sessionRows.associate { it.key to it.id })
    }

    fun saveWeekPlan(plan: WeekPlan, now: LocalDateTime) {
        db.execute("DELETE FROM week_plans WHERE user_id=$USER AND week_start=?", listOf(plan.weekStart.toString()))
        db.execute("INSERT INTO week_plans(user_id, week_start, from_day, reason, sessions_json, created_at) VALUES ($USER,?,?,?,?,?)",
            listOf(plan.weekStart.toString(), plan.fromDay.value, plan.reason, Codec.sessions(plan.sessions), now.toString()))
    }

    fun weekPlan(weekStart: LocalDate): WeekPlan? =
        db.single("SELECT * FROM week_plans WHERE user_id=$USER AND week_start=?", listOf(weekStart.toString())) {
            WeekPlan(weekStart, DayOfWeek.of(it.int("from_day")), it.str("reason"), Codec.sessionsFrom(it.str("sessions_json"), kb))
        }

    fun clearWeekPlans() = db.execute("DELETE FROM week_plans WHERE user_id=$USER")

    // -------------------------------------------------------------------------------------
    // Treinos
    // -------------------------------------------------------------------------------------

    fun startWorkout(programSessionId: Long?, readinessId: Long?, plan: PlannedSession, now: LocalDateTime): Long =
        db.insert("INSERT INTO workouts(user_id, program_session_id, readiness_check_id, started_at, plan_json) VALUES ($USER,?,?,?,?)",
            listOf(programSessionId, readinessId, now.toString(), Codec.session(plan).toString()))

    fun updateWorkoutPlan(workoutId: Long, plan: PlannedSession) =
        db.execute("UPDATE workouts SET plan_json=? WHERE id=?", listOf(Codec.session(plan).toString(), workoutId))

    fun logSet(workoutId: Long, exerciseId: ExerciseId, setIndex: Int, loadKg: Double, reps: Int, rir: Int?, warmup: Boolean, now: LocalDateTime): Long =
        db.insert("INSERT INTO workout_sets(workout_id, exercise_id, set_index, load_kg, reps, rir, is_warmup, completed_at) VALUES (?,?,?,?,?,?,?,?)",
            listOf(workoutId, exerciseId.value, setIndex, loadKg, reps, rir, warmup, now.toString()))

    fun deleteSet(setId: Long) = db.execute("DELETE FROM workout_sets WHERE id=?", listOf(setId))

    fun finishWorkout(workoutId: Long, perceived: String?, now: LocalDateTime) =
        db.execute("UPDATE workouts SET finished_at=?, perceived=? WHERE id=?", listOf(now.toString(), perceived, workoutId))

    fun discardWorkout(workoutId: Long) = db.execute("DELETE FROM workouts WHERE id=?", listOf(workoutId))

    fun workout(id: Long): WorkoutRow? = db.single("$WORKOUT_SELECT WHERE w.id=?", listOf(id), ::workoutFrom)

    fun unfinishedWorkout(): WorkoutRow? = db.single("$WORKOUT_SELECT WHERE w.user_id=$USER AND w.finished_at IS NULL ORDER BY w.id DESC LIMIT 1", map = ::workoutFrom)

    fun workoutsBetween(from: LocalDate, toInclusive: LocalDate, finishedOnly: Boolean = true): List<WorkoutRow> =
        db.query("$WORKOUT_SELECT WHERE w.user_id=$USER AND substr(w.started_at,1,10) BETWEEN ? AND ?" +
            (if (finishedOnly) " AND w.finished_at IS NOT NULL" else "") + " ORDER BY w.started_at",
            listOf(from.toString(), toInclusive.toString()), ::workoutFrom)

    private fun workoutFrom(r: SqlRow) = WorkoutRow(
        r.long("id"), r.longOrNull("program_session_id"), r.strOrNull("session_key"), LocalDateTime.parse(r.str("started_at")),
        r.strOrNull("finished_at")?.let(LocalDateTime::parse), r.strOrNull("perceived"),
        r.strOrNull("plan_json")?.let { Codec.sessionFrom(Json.parseToJsonElement(it).jsonObject, kb) },
    )

    fun sets(workoutId: Long): List<LoggedSet> =
        db.query("SELECT * FROM workout_sets WHERE workout_id=? ORDER BY id", listOf(workoutId), ::setFrom)

    private fun setFrom(r: SqlRow) = LoggedSet(r.long("id"), r.long("workout_id"), ExerciseId(r.str("exercise_id")), r.int("set_index"),
        r.double("load_kg"), r.int("reps"), r.intOrNull("rir"), r.bool("is_warmup"))

    /** Histórico de exercícios (séries de trabalho), um registro por exercício por treino concluído. */
    fun exerciseLogs(exercise: ExerciseId? = null): List<ExerciseLog> {
        data class R(val workout: Long, val date: LocalDate, val set: LoggedSet)
        val rows = db.query(
            "SELECT s.*, w.started_at FROM workout_sets s JOIN workouts w ON w.id = s.workout_id " +
                "WHERE w.user_id=$USER AND w.finished_at IS NOT NULL AND s.is_warmup = 0" +
                (if (exercise != null) " AND s.exercise_id = ?" else "") + " ORDER BY w.started_at, s.id",
            listOfNotNull(exercise?.value),
        ) { R(it.long("workout_id"), LocalDate.parse(it.str("started_at").substring(0, 10)), setFrom(it)) }
        return rows.groupBy { it.workout to it.set.exerciseId }.map { (k, v) ->
            ExerciseLog(k.second, v.first().date, v.map { SetLog(it.set.loadKg, it.set.reps, it.set.rir) })
        }
    }

    // -------------------------------------------------------------------------------------
    // Prontidão, faltas, PRs, XP
    // -------------------------------------------------------------------------------------

    fun saveReadiness(r: ReadinessRow): Long =
        db.insert("INSERT INTO readiness_checks(user_id, checked_at, sleep, energy, soreness, stress, motivation, score) VALUES ($USER,?,?,?,?,?,?,?)",
            listOf(r.at.toString(), r.sleep.name, r.energy, r.soreness, r.stress, r.motivation, r.score))

    fun readiness(limit: Int = 30): List<ReadinessRow> =
        db.query("SELECT * FROM readiness_checks WHERE user_id=$USER ORDER BY checked_at DESC, id DESC LIMIT $limit") {
            ReadinessRow(it.long("id"), LocalDateTime.parse(it.str("checked_at")), it.int("score"), SleepQuality.valueOf(it.str("sleep")),
                it.int("energy"), it.int("soreness"), it.int("stress"), it.int("motivation"))
        }

    fun deleteReadiness(id: Long) = db.execute("DELETE FROM readiness_checks WHERE id=?", listOf(id))

    fun recordMissed(programSessionId: Long?, missedOn: LocalDate, option: Char, now: LocalDateTime) =
        db.execute("INSERT INTO missed_workouts(user_id, program_session_id, missed_on, chosen_option, decided_at) VALUES ($USER,?,?,?,?)",
            listOf(programSessionId, missedOn.toString(), option.toString(), now.toString()))

    fun missedBetween(from: LocalDate, to: LocalDate): List<MissedRow> =
        db.query("SELECT * FROM missed_workouts WHERE user_id=$USER AND missed_on BETWEEN ? AND ?", listOf(from.toString(), to.toString())) {
            MissedRow(it.longOrNull("program_session_id"), LocalDate.parse(it.str("missed_on")), it.strOrNull("chosen_option")?.firstOrNull())
        }

    fun savePr(exerciseId: ExerciseId, type: String, value: Double, date: LocalDate, workoutId: Long?, description: String) =
        db.execute("INSERT INTO personal_records(user_id, exercise_id, type, value, achieved_on, workout_id, description) VALUES ($USER,?,?,?,?,?,?)",
            listOf(exerciseId.value, type, value, date.toString(), workoutId, description))

    fun prs(): List<PrRow> = db.query("SELECT * FROM personal_records WHERE user_id=$USER ORDER BY achieved_on DESC, id DESC") {
        PrRow(ExerciseId(it.str("exercise_id")), it.str("type"), it.double("value"), LocalDate.parse(it.str("achieved_on")), it.str("description"))
    }

    fun addXp(event: String, points: Int, at: LocalDateTime) =
        db.execute("INSERT INTO xp_events(user_id, event, points, at) VALUES ($USER,?,?,?)", listOf(event, points, at.toString()))

    fun xpEvents(): List<Pair<String, LocalDateTime>> =
        db.query("SELECT event, at FROM xp_events WHERE user_id=$USER ORDER BY at") { it.str("event") to LocalDateTime.parse(it.str("at")) }

    fun hasXpOn(event: String, date: LocalDate): Boolean =
        db.single("SELECT COUNT(*) AS n FROM xp_events WHERE user_id=$USER AND event=? AND substr(at,1,10)=?", listOf(event, date.toString())) { it.int("n") }!! > 0

    fun hasXpBetween(event: String, from: LocalDate, to: LocalDate): Boolean =
        db.single("SELECT COUNT(*) AS n FROM xp_events WHERE user_id=$USER AND event=? AND substr(at,1,10) BETWEEN ? AND ?",
            listOf(event, from.toString(), to.toString())) { it.int("n") }!! > 0

    /** Dias com qualquer registro (treino, check-in, água, refeição, sono, cardio, mobilidade). */
    fun activeDays(): Set<LocalDate> {
        val q = listOf(
            "SELECT DISTINCT substr(started_at,1,10) AS d FROM workouts WHERE user_id=$USER AND finished_at IS NOT NULL",
            "SELECT DISTINCT substr(checked_at,1,10) AS d FROM readiness_checks WHERE user_id=$USER",
            "SELECT DISTINCT substr(logged_at,1,10) AS d FROM water_logs WHERE user_id=$USER",
            "SELECT DISTINCT substr(eaten_at,1,10) AS d FROM meals WHERE user_id=$USER",
            "SELECT DISTINCT substr(started_at,1,10) AS d FROM cardio_sessions WHERE user_id=$USER",
            "SELECT DISTINCT substr(done_at,1,10) AS d FROM mobility_sessions WHERE user_id=$USER",
        )
        return q.flatMap { sql -> db.query(sql) { LocalDate.parse(it.str("d")) } }.toSet()
    }

    // -------------------------------------------------------------------------------------
    // Corpo, água, sono, refeições, cardio, mobilidade, fotos
    // -------------------------------------------------------------------------------------

    /** Uma medição por dia: registrar de novo no mesmo dia atualiza os campos informados. */
    fun addMeasurement(m: BodyMeasurement) = db.transaction {
        val existing = db.single("SELECT id FROM body_measurements WHERE user_id=$USER AND measured_on=?", listOf(m.date.toString())) { it.long("id") }
        if (existing == null) {
            db.execute("INSERT INTO body_measurements(user_id, measured_on, weight_kg, waist_cm, abdomen_cm, hip_cm) VALUES ($USER,?,?,?,?,?)",
                listOf(m.date.toString(), m.weightKg, m.waistCm, m.abdomenCm, m.hipCm))
        } else {
            db.execute("UPDATE body_measurements SET weight_kg=COALESCE(?, weight_kg), waist_cm=COALESCE(?, waist_cm), abdomen_cm=COALESCE(?, abdomen_cm), hip_cm=COALESCE(?, hip_cm) WHERE id=?",
                listOf(m.weightKg, m.waistCm, m.abdomenCm, m.hipCm, existing))
        }
    }

    fun measurements(): List<BodyMeasurement> = db.query("SELECT * FROM body_measurements WHERE user_id=$USER ORDER BY measured_on") {
        BodyMeasurement(LocalDate.parse(it.str("measured_on")), it.doubleOrNull("weight_kg"), it.doubleOrNull("waist_cm"), it.doubleOrNull("abdomen_cm"), it.doubleOrNull("hip_cm"))
    }

    fun addWater(ml: Int, at: LocalDateTime) = db.execute("INSERT INTO water_logs(user_id, logged_at, ml) VALUES ($USER,?,?)", listOf(at.toString(), ml))

    fun undoLastWater(date: LocalDate) =
        db.execute("DELETE FROM water_logs WHERE id = (SELECT id FROM water_logs WHERE user_id=$USER AND substr(logged_at,1,10)=? ORDER BY id DESC LIMIT 1)", listOf(date.toString()))

    fun waterByDay(from: LocalDate, to: LocalDate): Map<LocalDate, Int> =
        db.query("SELECT substr(logged_at,1,10) AS d, SUM(ml) AS ml FROM water_logs WHERE user_id=$USER AND substr(logged_at,1,10) BETWEEN ? AND ? GROUP BY d",
            listOf(from.toString(), to.toString())) { LocalDate.parse(it.str("d")) to it.int("ml") }.toMap()

    fun logSleep(nightOf: LocalDate, hours: Double, quality: Int) = db.transaction {
        db.execute("DELETE FROM sleep_logs WHERE user_id=$USER AND night_of=?", listOf(nightOf.toString()))
        db.execute("INSERT INTO sleep_logs(user_id, night_of, hours, quality) VALUES ($USER,?,?,?)", listOf(nightOf.toString(), hours, quality))
    }

    fun sleep(limit: Int = 30): List<SleepRow> = db.query("SELECT * FROM sleep_logs WHERE user_id=$USER ORDER BY night_of DESC LIMIT $limit") {
        SleepRow(it.long("id"), LocalDate.parse(it.str("night_of")), it.double("hours"), it.int("quality"))
    }

    fun logMeal(label: String?, items: List<MealItemRow>, at: LocalDateTime): Long = db.transaction {
        val id = db.insert("INSERT INTO meals(user_id, eaten_at, label) VALUES ($USER,?,?)", listOf(at.toString(), label))
        items.forEach {
            db.execute("INSERT INTO meal_items(meal_id, food_id, grams, kcal, protein_g, carbs_g, fat_g, fiber_g) VALUES (?,?,?,?,?,?,?,?)",
                listOf(id, it.foodId.value, it.grams, it.kcal, it.proteinG, it.carbsG, it.fatG, it.fiberG))
        }
        id
    }

    fun deleteMeal(id: Long) = db.execute("DELETE FROM meals WHERE id=?", listOf(id))

    fun mealsOn(date: LocalDate): List<MealRow> {
        val meals = db.query("SELECT * FROM meals WHERE user_id=$USER AND substr(eaten_at,1,10)=? ORDER BY eaten_at", listOf(date.toString())) {
            Triple(it.long("id"), LocalDateTime.parse(it.str("eaten_at")), it.strOrNull("label"))
        }
        return meals.map { (id, at, label) ->
            MealRow(id, at, label, db.query("SELECT * FROM meal_items WHERE meal_id=? ORDER BY id", listOf(id)) {
                MealItemRow(FoodId(it.str("food_id")), it.double("grams"), it.double("kcal"), it.doubleOrNull("protein_g"),
                    it.doubleOrNull("carbs_g"), it.doubleOrNull("fat_g"), it.doubleOrNull("fiber_g"))
            })
        }
    }

    fun logCardio(kind: String, minutes: Double, rpe: Int?, at: LocalDateTime) =
        db.execute("INSERT INTO cardio_sessions(user_id, started_at, kind, duration_min, rpe) VALUES ($USER,?,?,?,?)", listOf(at.toString(), kind, minutes, rpe))

    fun cardio(limit: Int = 30): List<CardioRow> = db.query("SELECT * FROM cardio_sessions WHERE user_id=$USER ORDER BY started_at DESC LIMIT $limit") {
        CardioRow(it.long("id"), LocalDateTime.parse(it.str("started_at")), it.str("kind"), it.double("duration_min"), it.intOrNull("rpe"))
    }

    fun deleteCardio(id: Long) = db.execute("DELETE FROM cardio_sessions WHERE id=?", listOf(id))

    fun logMobility(region: String, minutes: Double, at: LocalDateTime) =
        db.execute("INSERT INTO mobility_sessions(user_id, done_at, region, duration_min) VALUES ($USER,?,?,?)", listOf(at.toString(), region, minutes))

    fun mobility(limit: Int = 30): List<MobilityRow> = db.query("SELECT * FROM mobility_sessions WHERE user_id=$USER ORDER BY done_at DESC LIMIT $limit") {
        MobilityRow(it.long("id"), LocalDateTime.parse(it.str("done_at")), it.str("region"), it.double("duration_min"))
    }

    fun addPhoto(date: LocalDate, angle: String, uri: String) =
        db.execute("INSERT INTO progress_photos(user_id, taken_on, angle, file_uri) VALUES ($USER,?,?,?)", listOf(date.toString(), angle, uri))

    fun photos(): List<PhotoRow> = db.query("SELECT * FROM progress_photos WHERE user_id=$USER ORDER BY taken_on DESC, id DESC") {
        PhotoRow(it.long("id"), LocalDate.parse(it.str("taken_on")), it.str("angle"), it.str("file_uri"))
    }

    fun deletePhoto(id: Long) = db.execute("DELETE FROM progress_photos WHERE id=?", listOf(id))

    fun saveNutritionTargets(validFrom: LocalDate, kcal: Int, proteinMin: Int, proteinMax: Int, goal: EnergyGoal) {
        db.execute("DELETE FROM nutrition_targets WHERE user_id=$USER AND valid_from=?", listOf(validFrom.toString()))
        db.execute("INSERT INTO nutrition_targets(user_id, valid_from, kcal, protein_min_g, protein_max_g, energy_goal) VALUES ($USER,?,?,?,?,?)",
            listOf(validFrom.toString(), kcal, proteinMin, proteinMax, goal.name))
    }

    fun logEvent(kind: String, payloadJson: String?, at: LocalDateTime) =
        db.execute("INSERT INTO user_events(user_id, at, kind, payload_json) VALUES ($USER,?,?,?)", listOf(at.toString(), kind, payloadJson))

    /**
     * "Exportar meus dados": todas as tabelas do user.db em JSON (portabilidade, LGPD).
     * Genérico de propósito — tabela nova no schema entra na exportação sem código novo.
     */
    fun exportJson(exportedAt: LocalDateTime): String {
        val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name") { it.str("name") }
        return buildJsonObject {
            put("app", JsonPrimitive("FitKingIA"))
            put("exported_at", JsonPrimitive(exportedAt.toString()))
            put("kb_content_version", JsonPrimitive(kb.meta["content_version"] ?: "?"))
            for (t in tables) {
                val cols = db.query("PRAGMA table_info($t)") { it.str("name") }
                put(t, kotlinx.serialization.json.JsonArray(db.query("SELECT * FROM $t") { r ->
                    buildJsonObject { cols.forEach { c -> put(c, r.strOrNull(c)?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull) } }
                }))
            }
        }.toString()
    }

    /** "Apagar meus dados": a cascata do schema remove tudo que pertence ao usuário. */
    fun deleteEverything() = db.transaction {
        db.execute("DELETE FROM users WHERE id=$USER")
    }

    companion object {
        const val USER = 1L
        const val PREF_MAX_DAYS = "max_training_days"
        const val PREF_SPLIT = "preferred_split"
        const val PREF_SWEAT = "sweat_level"
        const val PREF_HOT = "hot_climate"
        const val PREF_BAR_KG = "bar_kg"

        private const val WORKOUT_SELECT =
            "SELECT w.*, ps.key AS session_key FROM workouts w LEFT JOIN program_sessions ps ON ps.id = w.program_session_id"
    }
}

/** Criação e migração do user.db (PRAGMA user_version). */
object UserDb {
    const val VERSION = 1

    fun migrate(db: SqlDatabase, schemaSql: String) {
        db.execute("PRAGMA foreign_keys = ON")
        val v = db.single("PRAGMA user_version") { it.int("user_version") } ?: 0
        if (v < 1) {
            db.transaction { db.runScript(schemaSql) }
            db.execute("PRAGMA user_version = $VERSION")
        }
    }
}
