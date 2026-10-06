package com.fitkingia.core

import com.fitkingia.core.Fixtures.e
import com.fitkingia.core.Fixtures.kb
import com.fitkingia.core.Fixtures.profile
import com.fitkingia.core.Fixtures.x
import com.fitkingia.core.analytics.VolumeDashboard
import com.fitkingia.core.explain.WhyService
import com.fitkingia.core.knowledge.SplitTemplate
import com.fitkingia.core.model.*
import com.fitkingia.core.planning.MissedWorkoutPlanner
import com.fitkingia.core.planning.ProgramSimulator
import com.fitkingia.core.program.*
import com.fitkingia.core.progression.ExerciseLog
import com.fitkingia.core.progression.SetLog
import com.fitkingia.core.recovery.*
import com.fitkingia.core.safety.SafetyScreening
import com.fitkingia.core.safety.ScreeningStatus
import com.fitkingia.core.session.ChangeKind
import com.fitkingia.core.session.SessionAdapter
import com.fitkingia.core.session.SessionFitter
import com.fitkingia.core.substitution.SubstitutionEngine
import java.time.DayOfWeek.*
import java.time.LocalDateTime
import kotlin.test.*

private val noFlags = mapOf("chest_pain" to false, "current_pain" to false)

private fun generate(p: UserProfile): Program {
    val screening = SafetyScreening(kb).evaluate(p, noFlags)
    return (ProgramGenerator(kb).generate(p, screening) as ProgramResult.Generated).program
}

class SafetyScreeningTest {
    private val s = SafetyScreening(kb)

    @Test fun `red flag bloqueia a prescrição normal`() {
        val r = s.evaluate(profile(), mapOf("chest_pain" to true, "current_pain" to false))
        assertEquals(ScreeningStatus.REFER, r.status)
        val result = ProgramGenerator(kb).generate(profile(), r)
        assertIs<ProgramResult.Refused>(result)
        assertTrue(result.reasons.any { "não vai gerar uma prescrição normal" in it.text })
    }

    @Test fun `questionário incompleto não libera`() {
        assertEquals(ScreeningStatus.INCOMPLETE, s.evaluate(profile(), mapOf("chest_pain" to false)).status)
    }

    @Test fun `dor relatada libera com cautela`() {
        assertEquals(ScreeningStatus.CAUTION, s.evaluate(profile(), mapOf("chest_pain" to false, "current_pain" to true)).status)
    }

    @Test fun `menores de 18 são encaminhados`() {
        assertEquals(ScreeningStatus.REFER, s.evaluate(profile(age = 16), noFlags).status)
    }

    @Test fun `pergunta de gestação só aparece para quem se aplica`() {
        assertTrue(s.questionsFor(Sex.MALE).none { it.id == "pregnancy" })
        assertTrue(s.questionsFor(Sex.FEMALE).any { it.id == "pregnancy" })
        assertEquals(ScreeningStatus.INCOMPLETE, s.evaluate(profile(sex = Sex.FEMALE), noFlags).status)
    }
}

class ProgramGeneratorTest {
    @Test fun `mesma entrada gera exatamente o mesmo programa`() {
        assertEquals(generate(profile()), generate(profile()))
    }

    @Test fun `nunca usa equipamento indisponível`() {
        val p = generate(profile(equipment = Fixtures.homeDumbbells))
        val used = p.sessions.flatMap { s -> s.exercises.flatMap { it.exercise.equipment } }.toSet()
        assertTrue(Fixtures.homeDumbbells.containsAll(used), "usou $used")
    }

    @Test fun `iniciante não recebe exercício acima do seu nível e tem RIR maior`() {
        val p = generate(profile(experience = ExperienceLevel.NONE))
        assertTrue(p.sessions.flatMap { it.exercises }.none { it.exercise.minTier > TrainingTier.NOVICE })
        assertTrue(p.sessions.flatMap { it.exercises }.filter { it.role == SlotRole.MAIN }.all { it.prescription.rir == 3 })
    }

    @Test fun `volume semanal nunca passa do teto e sessões cabem no tempo`() {
        val p = generate(profile())
        for ((m, t) in p.volumeTargets) assertTrue((p.weeklyVolume[m] ?: 0.0) <= t.max + 1e-9, "$m acima do teto")
        for (s in p.sessions) assertTrue(s.estimatedMinutes <= s.budgetMinutes!!, "${s.name}: ${s.estimatedMinutes} > ${s.budgetMinutes}")
    }

    @Test fun `principais são compostos e não há exercício repetido na mesma sessão`() {
        val p = generate(profile())
        for (s in p.sessions) {
            assertTrue(s.exercises.filter { it.role == SlotRole.MAIN }.all { it.exercise.mechanic == Mechanic.COMPOUND })
            assertEquals(s.exercises.size, s.exercises.map { it.exercise.id }.distinct().size)
        }
    }

    @Test fun `limite de dias por nível e dias espaçados`() {
        val p = generate(profile(days = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY).associateWith { 60 }))
        assertEquals(3, p.sessions.size)
        assertEquals(listOf(MONDAY, WEDNESDAY, FRIDAY), p.trainingDays)
    }

    @Test fun `dias curtos demais não recebem sessão`() {
        val p = generate(profile(days = mapOf(MONDAY to 60, WEDNESDAY to 15, FRIDAY to 60)))
        assertEquals(listOf(MONDAY, FRIDAY), p.trainingDays)
        assertEquals("fb2", p.split.id.value)
    }

    @Test fun `nenhum dia disponível recusa com motivo`() {
        val r = ProgramGenerator(kb).generate(profile(days = mapOf(MONDAY to 10)), SafetyScreening(kb).evaluate(profile(), noFlags))
        assertIs<ProgramResult.Refused>(r)
    }

    @Test fun `dor no joelho moderada exclui exercícios de alta demanda no joelho`() {
        val p = generate(profile(limitations = listOf(JointLimitation(Joint.KNEE, 5))))
        assertTrue(p.sessions.flatMap { it.exercises }.all { it.exercise.demand(Joint.KNEE) <= 1 })
    }

    @Test fun `exercício em tempo recebe prescrição em segundos`() {
        val p = generate(profile(days = mapOf(MONDAY to 90, THURSDAY to 90)))
        val plank = p.sessions.flatMap { it.exercises }.firstOrNull { it.exercise.id == x("plank") }
        assertNotNull(plank)
        assertEquals(20..45, plank.prescription.holdSeconds)
        assertEquals("20–45 s", plank.prescription.target)
    }

    @Test fun `todo programa explica dias, divisão, volume e prescrição`() {
        val p = generate(profile())
        val text = p.explanations.joinToString("\n") { it.text }
        listOf("Dias de treino", "Divisão", "Meta semanal", "Prescrição").forEach { assertTrue(it in text, "falta '$it'") }
    }
}

class WeekSchedulerTest {
    private val scheduler = WeekScheduler(kb)

    @Test fun `escolhe dias não consecutivos quando há mais dias que o limite`() {
        val days = listOf(MONDAY, TUESDAY, WEDNESDAY, THURSDAY).map { DayAvailability(it, 60) }
        assertEquals(listOf(MONDAY, WEDNESDAY), scheduler.pickDays(days, 2).map { it.day })
    }

    @Test fun `afasta a sessão de pernas do dia de kickboxing quando possível`() {
        val legs = PlannedSession("legs", "Pernas", listOf(PlannedExercise(kb.exercise(x("back_squat")), SlotRole.MAIN, 5,
            Fixtures.ruleSet.prescription.params.forRole(TrainingFocus.HYPERTROPHY, SlotRole.MAIN))))
        val upper = PlannedSession("upper", "Superiores", listOf(PlannedExercise(kb.exercise(x("bench_press")), SlotRole.MAIN, 5,
            Fixtures.ruleSet.prescription.params.forRole(TrainingFocus.HYPERTROPHY, SlotRole.MAIN))))
        val days = listOf(MONDAY, THURSDAY).map { DayAvailability(it, 60) }
        val sport = listOf(SportCommitment(SportId("kickboxing"), TUESDAY, 3))
        val a = scheduler.assign(listOf(legs, upper), days, sport)
        assertEquals(THURSDAY, a.dayForSession[0].day, "pernas deveriam ir para quinta, longe da terça")
    }
}

class SessionFitterTest {
    private val pull: PlannedSession by lazy {
        val p = profile(days = mapOf(MONDAY to 120))
        val split = kb.split(SplitId("pull_only"))
        val g = ProgramGenerator(kb)
        val sel = ExerciseSelector(kb)
        val used = mutableSetOf<ExerciseId>()
        val items = split.sessions.single().slots.map { slot ->
            val ex = sel.select(slot, UserConstraints.of(p), used)!!
            used += ex.id
            PlannedExercise(ex, slot.role, 3, g.prescription(TrainingFocus.HYPERTROPHY, slot.role, p.tier, ex), slot = slot)
        }
        PlannedSession("pull", "Costas + Bíceps", items)
    }

    @Test fun `tenho 35 minutos - remove redundantes antes de mexer nos principais`() {
        val fit = SessionFitter(kb).fit(pull, 35)
        assertTrue(fit.fits)
        assertTrue(fit.session.estimatedMinutes <= 35)
        val removed = fit.changes.filter { it.kind == ChangeKind.REMOVED }
        assertTrue(removed.isNotEmpty())
        assertEquals(ChangeKind.REMOVED, fit.changes.first().kind, "primeiro corte é o de redundância")
        assertTrue(fit.session.exercises.any { it.role == SlotRole.MAIN }, "o principal é mantido")
        assertTrue(fit.session.exercises.any { it.exercise.pattern == Fixtures.p("elbow_flexion") }, "bíceps não tem redundância e fica")
        assertTrue(fit.changes.all { it.reason.isNotBlank() })
    }

    @Test fun `tempo impossível é informado, nunca escondido`() {
        val fit = SessionFitter(kb).fit(pull, 8)
        assertFalse(fit.fits)
        assertEquals(1, fit.session.exercises.size)
        assertEquals(ChangeKind.NOT_ENOUGH_TIME, fit.changes.last().kind)
    }

    @Test fun `sessão que já cabe não muda`() {
        val fit = SessionFitter(kb).fit(pull, 300)
        assertTrue(fit.changes.isEmpty())
        assertEquals(pull.exercises, fit.session.exercises)
    }
}

class SubstitutionTest {
    private val engine = SubstitutionEngine(kb)

    @Test fun `substitutos usam só equipamento disponível e mantêm o padrão`() {
        val c = UserConstraints.of(profile(equipment = Fixtures.homeDumbbells))
        val r = engine.find(x("back_squat"), c)
        assertNotNull(r.originalStatus, "agachamento com barra não está disponível em casa")
        assertTrue(r.options.isNotEmpty())
        assertTrue(r.options.all { Fixtures.homeDumbbells.containsAll(it.exercise.equipment) })
        assertEquals(x("goblet_squat"), r.options.first().exercise.id)
    }

    @Test fun `curadoria e similaridade definem a ordem`() {
        val r = engine.find(x("back_squat"), UserConstraints.of(profile()))
        assertEquals(x("leg_press"), r.options.first().exercise.id)
        assertTrue(r.options.first().reasons.any { "curada" in it })
    }

    @Test fun `dor no joelho filtra e avisa sem diagnosticar`() {
        val r = engine.find(x("back_squat"), UserConstraints.of(profile()), painJoint = Joint.KNEE, painSeverity = 3)
        assertTrue(r.options.none { it.exercise.id == x("lunge") }, "afundo tem demanda alta no joelho")
        assertTrue(r.notes.any { "não é diagnóstico" in it })
    }

    @Test fun `nunca sugere o próprio exercício nem algo sem relação`() {
        val r = engine.find(x("db_curl"), UserConstraints.of(profile()))
        assertTrue(r.options.none { it.exercise.id == x("db_curl") })
        assertTrue(r.options.none { it.exercise.pattern == Fixtures.p("squat") })
    }
}

class ReadinessTest {
    private val scorer = RecoveryScorer(Fixtures.ruleSet.recovery)

    @Test fun `extremos do indice de recuperacao`() {
        assertEquals(100, scorer.score(ReadinessCheck(SleepQuality.EXCELLENT, 10, 0, 1, 10)).score)
        val worst = scorer.score(ReadinessCheck(SleepQuality.POOR, 1, 10, 10, 1))
        assertEquals(5, worst.score)
        assertEquals(ReadinessBand.VERY_LOW, worst.band)
    }

    @Test fun `entradas fora da escala são rejeitadas`() {
        assertFailsWith<IllegalArgumentException> { ReadinessCheck(SleepQuality.NORMAL, 11, 0, 1, 5) }
    }

    @Test fun `prontidão baixa reduz volume, aumenta RIR e mantém os principais`() {
        val program = generate(profile())
        val session = program.sessions.first()
        val low = scorer.score(ReadinessCheck(SleepQuality.NORMAL, 5, 5, 5, 6))
        assertEquals(ReadinessBand.LOW, low.band)
        val adapted = SessionAdapter(kb).forReadiness(session, low, UserConstraints.of(profile()))
        assertTrue(adapted.session.exercises.sumOf { it.sets } < session.exercises.sumOf { it.sets })
        assertTrue(adapted.session.exercises.all { a -> a.prescription.rir >= 3 })
        assertEquals(session.exercises.count { it.role == SlotRole.MAIN }, adapted.session.exercises.count { it.role == SlotRole.MAIN })
    }

    @Test fun `fadiga decai com o tempo e soma esportes`() {
        val now = LocalDateTime.of(2026, 10, 2, 18, 0)
        val model = FatigueModel(kb)
        val fresh = model.estimate(List(6) { SetEvent(x("back_squat"), now.minusHours(2), 1) }, emptyList(), now)
        val old = model.estimate(List(6) { SetEvent(x("back_squat"), now.minusHours(72), 1) }, emptyList(), now)
        assertTrue(fresh.getValue(Fixtures.m("quads")) > old.getValue(Fixtures.m("quads")))
        val withSport = model.estimate(emptyList(), listOf(SportEvent(SportId("kickboxing"), now.minusHours(12), 3)), now)
        assertTrue(withSport.getValue(Fixtures.m("quads")) > 0)
        assertEquals(0, withSport.getValue(Fixtures.m("abs")))
    }
}

class PlanningTest {
    private val p = profile(days = mapOf(MONDAY to 60, WEDNESDAY to 60, FRIDAY to 60, SATURDAY to 60))
    private val program = generate(p)

    @Test fun `treino perdido oferece A-D com tom neutro`() {
        val r = MissedWorkoutPlanner(kb).options(program, p, MONDAY, TUESDAY)
        assertTrue(r.message.startsWith("Você não realizou o treino"))
        assertEquals(listOf('A', 'B', 'C', 'D'), r.options.map { it.key })
        val move = r.options.first()
        assertTrue(move.available)
        assertTrue(move.remainingWeek.any { it.day == SATURDAY && it.key == program.sessionOn(MONDAY)!!.key })
    }

    @Test fun `recalcular semana nunca usa dia indisponível`() {
        val d = MissedWorkoutPlanner(kb).options(program, p, MONDAY, THURSDAY).options.last()
        val allowed = setOf(FRIDAY, SATURDAY)
        assertTrue(d.remainingWeek.all { it.day in allowed })
    }

    @Test fun `simulador compara cenários sem declarar vencedor`() {
        val sim = ProgramSimulator(kb).compare(p, SafetyScreening(kb).evaluate(p, noFlags), listOf(1, 2, 3).map { ProgramSimulator.days(it) })
        assertEquals(listOf("1D", "2D", "3D"), sim.rows.map { it.scenario })
        assertTrue(sim.rows.zipWithNext().all { (a, b) -> a.weeklyMinutes <= b.weeklyMinutes })
        assertTrue("melhor se encaixa na sua rotina" in sim.note.text)
    }
}

class DashboardAndWhyTest {
    private val program = generate(profile())

    @Test fun `dashboard compara realizado com o esperado até o dia`() {
        val monday = program.sessionOn(MONDAY)!!
        val logs = monday.exercises.filter { Fixtures.m("quads") !in it.exercise.primaryMuscles }
            .map { ExerciseLog(it.exercise.id, Fixtures.TODAY, List(it.sets) { SetLog(20.0, 10) }) }
        val status = VolumeDashboard(kb).week(program, logs, MONDAY)
        val quads = status.first { it.muscle.id == Fixtures.m("quads") }
        assertEquals(0.0, quads.doneSets)
        assertNotNull(quads.message)
        assertTrue("quads" in quads.message!!.text && "menor que o planejado" in quads.message!!.text)
    }

    @Test fun `por que isso mostra objetivo, prescrição e volume`() {
        val first = program.sessions.first().exercises.first()
        val why = WhyService(kb).forExercise(program, first.exercise.id)
        assertTrue(why.lines.any { it.startsWith("Objetivo:") })
        assertTrue(why.lines.any { it.startsWith("Prescrição:") && "RIR" in it })
        assertTrue(why.lines.any { it.startsWith("Volume semanal considerado") })
        val day = WhyService(kb).forDay(program, MONDAY)
        assertTrue("distribuir os grupos musculares" in day.lines.first())
    }
}

class TimeAdapterTest {
    @Test fun `quick session descreve o que foi removido e por quê`() {
        val program = generate(profile(days = mapOf(MONDAY to 90, WEDNESDAY to 90, FRIDAY to 90)))
        val s = program.sessionOn(MONDAY)!!
        val a = SessionAdapter(kb).forTime(s, 30)
        assertTrue(a.title.startsWith("⚡ TREINO RÁPIDO"))
        assertTrue(a.session.estimatedMinutes <= 30)
        assertTrue(a.changes.isNotEmpty())
    }
}
