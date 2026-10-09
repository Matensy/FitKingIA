package com.fitkingia.app

import android.database.sqlite.SQLiteDatabase
import com.fitkingia.app.data.AndroidSqlDatabase
import com.fitkingia.appcore.*
import com.fitkingia.core.model.*
import com.fitkingia.core.program.ProgramResult
import com.fitkingia.knowledge.BundledKnowledge
import com.fitkingia.knowledge.KnowledgeDbBuilder
import com.fitkingia.knowledge.KnowledgeReader
import com.fitkingia.knowledge.Seeds
import com.fitkingia.app.ui.Motion
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDateTime

/**
 * O mesmo fluxo dos testes do appcore, mas sobre o SQLite do Android (classe SQLiteDatabase do
 * framework, como no celular) em vez de JDBC: pega diferenças de binding e de dialeto SQL.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class AndroidSqlTest {

    @Before fun setUp() {
        Motion.enabled = false
    }

    @Test fun conhecimentoLidoPeloSqliteDoAndroidIgualAoJdbc() {
        val file = File.createTempFile("fitness", ".db").also { it.deleteOnExit() }
        KnowledgeDbBuilder.build(Seeds.fromClasspath(), file)
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        val kb = try { KnowledgeReader(AndroidSqlDatabase(db)).load() } finally { db.close() }
        val jvm = BundledKnowledge.load()
        assertEquals(jvm.exercises, kb.exercises)
        assertEquals(jvm.ruleSet, kb.ruleSet)
        assertEquals(jvm.splits, kb.splits)
        assertEquals(jvm.claims, kb.claims)
        assertEquals(jvm.sources, kb.sources)
        assertEquals(jvm.foods, kb.foods)
    }

    @Test fun fluxoCompletoNoSqliteDoAndroid() {
        val kb = BundledKnowledge.load()
        val raw = SQLiteDatabase.create(null)
        raw.setForeignKeyConstraintsEnabled(true)
        val sql = AndroidSqlDatabase(raw)
        UserDb.migrate(sql, KnowledgeDbBuilder.schema("user.sql"))
        UserDb.migrate(sql, KnowledgeDbBuilder.schema("user.sql")) // idempotente (user_version)
        var now = LocalDateTime.of(2026, 9, 28, 9, 0)
        val app = FitKing(kb, sql, AppClock { now })

        val a = Answers(consent = true)
        Questionnaire.selectSex(a, Sex.FEMALE)
        a.primaryGoal = Goal.RECOMPOSITION; a.secondaryGoal = Goal.STRENGTH
        a.experience = ExperienceLevel.MONTHS_6_TO_12
        Questionnaire.selectEnvironment(a, kb, EnvironmentId("basic_gym"))
        Questionnaire.applyPreset(a, mapOf(DayOfWeek.MONDAY to 60, DayOfWeek.WEDNESDAY to 45, DayOfWeek.FRIDAY to 60, DayOfWeek.SATURDAY to 50))
        a.sports.add(SportCommitment(SportId("running"), DayOfWeek.TUESDAY, 2))
        a.activity = ActivityLevel.LIGHT
        a.waistCm = 78
        Questionnaire.safetyQuestions(kb, a).forEach { a.safety[it.id] = it.id == "current_pain" }
        a.pains[Joint.WRIST] = 5
        val out = app.submit(a)
        val generated = (out.result as ProgramResult.Generated).program

        val stored = app.program()!!.program
        assertEquals(generated.sessions.map { s -> s.exercises.map { it.exercise.id to it.prescription } }, stored.sessions.map { s -> s.exercises.map { it.exercise.id to it.prescription } })
        val p = app.profile()!!
        assertEquals(out.profile.copy(name = p.name), p)
        assertEquals(a.safety, app.repo.safetyAnswers())

        // Treino com PR na segunda semana.
        val t = app.todayView()!!
        val s = t.session!!
        val main = s.exercises.first { it.role == SlotRole.MAIN && !it.exercise.timed }
        fun workout(load: Double) {
            val w = app.startWorkout(s, t.sessionId, null)
            repeat(main.sets) { i -> app.logSet(w.id, main.exercise.id, i + 1, load, main.prescription.reps.last, 2) }
            assertEquals(main.sets, app.activeWorkout()!!.sets.size)
            app.finishWorkout(w.id, Perceived.ADEQUATE)
        }
        workout(40.0)
        assertEquals(DayStatus.DONE, app.week()!!.days.first().status)
        now = now.plusDays(7)
        workout(45.0)
        assertTrue(app.records().isNotEmpty())
        assertTrue(app.exerciseLogs(main.exercise.id).size == 2)

        // Semana seguinte: faltou segunda, replaneja na quarta.
        now = now.plusDays(2)
        val missed = app.todayView()!!.pendingMissed.firstOrNull()
        if (missed != null) {
            val opt = app.missedOptions(missed)!!.options.first { it.available && it.key != 'C' }
            app.applyMissed(missed, opt)
            assertNotNull(app.week()!!.plan)
            assertEquals(DayStatus.MISSED_RESOLVED, app.week()!!.days.first { it.date == missed.date }.status)
        }

        app.addWater(750); app.undoWater(); app.addWater(500)
        assertEquals(500, app.water()!!.progress.consumedMl)
        app.logSleep(6.5, 3); app.logBody(64.5, 77.0)
        // Sono conta para a sequência no dia em que foi registrado (night_of + 1 dia, com date() do SQLite).
        assertEquals(now.toLocalDate().toString(), sql.single("SELECT date(night_of, '+1 day') AS d FROM sleep_logs") { it.str("d") })
        assertTrue(now.toLocalDate() in app.repo.activeDays())
        app.logFood(kb.foods.first(), 1.0, "Almoço")
        app.logCardio("Corrida", 30, 6); app.logMobility("Quadril", 10)
        app.checkIn(com.fitkingia.core.recovery.ReadinessCheck(SleepQuality.NORMAL, 6, 3, 4, 7))
        assertNotNull(app.todayView()!!.readiness)
        assertTrue(app.exportJson().contains("\"water_logs\""))

        app.deleteEverything()
        assertFalse(app.hasProfile())
        val left = sql.single("SELECT (SELECT COUNT(*) FROM workouts) + (SELECT COUNT(*) FROM workout_sets) + (SELECT COUNT(*) FROM programs) + (SELECT COUNT(*) FROM water_logs) AS n") { it.int("n") }
        assertEquals(0, left)
        raw.close()
    }
}
