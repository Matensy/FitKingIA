package com.fitkingia.cli

import com.fitkingia.core.analytics.VolumeDashboard
import com.fitkingia.core.body.BodyMetrics
import com.fitkingia.core.body.WeightEntry
import com.fitkingia.core.body.WeightTrend
import com.fitkingia.core.explain.WhyService
import com.fitkingia.core.hydration.HydrationEngine
import com.fitkingia.core.hydration.HydrationInput
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.*
import com.fitkingia.core.nutrition.EnergyEstimator
import com.fitkingia.core.nutrition.MealParser
import com.fitkingia.core.planning.MissedWorkoutPlanner
import com.fitkingia.core.planning.ProgramSimulator
import com.fitkingia.core.program.*
import com.fitkingia.core.progression.*
import com.fitkingia.core.recovery.ReadinessCheck
import com.fitkingia.core.recovery.RecoveryScorer
import com.fitkingia.core.safety.SafetyScreening
import com.fitkingia.core.session.SessionAdapter
import com.fitkingia.core.substitution.SubstitutionEngine
import com.fitkingia.core.tools.PlateCalculator
import com.fitkingia.core.tools.WarmupGenerator
import com.fitkingia.coach.CoachContext
import com.fitkingia.coach.ConversationState
import com.fitkingia.coach.LocalCoach
import com.fitkingia.knowledge.BundledKnowledge
import com.fitkingia.knowledge.KnowledgeValidator
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.system.exitProcess

private const val HELP = """
fitking — motor determinístico do FitKingIA (sem LLM)

Uso: fitking [--perfil arquivo.json] <comando> [argumentos]

  programa                       gera o programa semanal do perfil
  hoje <dia>                     mostra a sessão de um dia (ex.: hoje segunda)
  rapido <minutos> <dia>         ⚡ adapta a sessão do dia ao tempo disponível
  prontidao <sono> <energia> <dor> <estresse> <motivação> <dia>
                                 ajusta a sessão pelo Índice de recuperação (sono: ruim|normal|excelente)
  substituir <exercício> [articulação-com-dor] [dor 0–10]
                                 substituições ranqueadas com motivos
  porque <exercício>             🔬 "Por que isso?" com regra → evidência → fonte
  porque-dia <dia>               "Por que treino isso na segunda?"
  faltei <dia-perdido> <hoje>    opções A–D para treino perdido
  simular                        compara 3, 4 e 5 dias por semana
  progresso                      demonstra dupla progressão, PRs e tendência
  anilhas <kg> [barra]           calculadora de anilhas
  1rm <kg> <reps> [rir]          estimativa de 1RM
  aquecimento <exercício> <kg>   séries de aquecimento
  refeicao "<texto>"             registro de refeição em linguagem natural
  agua <minutos-treino> [suor: pouco|moderado|muito] [calor]
  nutricao                       metas estimadas de energia e proteína
  corpo <cintura-cm>             IMC e relação cintura/altura
  suplementos                    banco de suplementos (informação, não prescrição)
  fontes                         fontes científicas do banco
  validar                        relatório de integridade do banco de conhecimento
  coach                          💬 conversa com a IA local (offline, sem custo): digite e tecle Enter
  pergunta "<texto>"             uma pergunta para a IA local
  demo                           passeio por todas as funções
"""

fun main(args: Array<String>) {
    val argv = args.toMutableList()
    val profileFile = argv.indexOf("--perfil").takeIf { it >= 0 }?.let { i ->
        val f = File(argv[i + 1]); argv.removeAt(i); argv.removeAt(i); ProfileFile.read(f)
    } ?: ProfileFile.default()
    val cmd = argv.firstOrNull() ?: run { println(HELP); return }
    val kb = BundledKnowledge.load()
    val app = App(kb, profileFile)
    try {
        app.run(cmd, argv.drop(1))
    } catch (e: IllegalArgumentException) {
        System.err.println("Erro: ${e.message}")
        exitProcess(2)
    } catch (e: IllegalStateException) {
        System.err.println("Erro: ${e.message}")
        exitProcess(2)
    }
}

class App(private val kb: KnowledgeBase, private val pf: ProfileFile) {
    private val out = Printer(kb)
    private val profile = pf.toProfile(kb)
    private val screening by lazy { SafetyScreening(kb).evaluate(profile, pf.safetyAnswers) }
    private val program: Program by lazy {
        when (val r = ProgramGenerator(kb).generate(profile, screening)) {
            is ProgramResult.Generated -> r.program
            is ProgramResult.Refused -> {
                out.rule("PRESCRIÇÃO NÃO GERADA")
                out.explanations(r.reasons)
                exitProcess(3)
            }
        }
    }

    fun run(cmd: String, a: List<String>) {
        when (cmd) {
            "programa" -> { screeningSummary(); out.program(program) }
            "hoje" -> out.session(sessionOn(day(a, 0)))
            "rapido" -> quick(a.int(0), day(a, 1))
            "prontidao" -> readiness(a)
            "substituir" -> substitute(a.getOrNull(0) ?: usage("substituir <exercício>"), a.getOrNull(1), a.getOrNull(2)?.toIntOrNull())
            "porque" -> out.why(WhyService(kb).forExercise(program, exercise(a.getOrNull(0) ?: usage("porque <exercício>")).id))
            "porque-dia" -> out.why(WhyService(kb).forDay(program, day(a, 0)))
            "faltei" -> missed(day(a, 0), day(a, 1))
            "simular" -> simulate()
            "progresso" -> progressDemo()
            "anilhas" -> println(PlateCalculator.compute(a.dbl(0), a.getOrNull(1)?.toDouble() ?: 20.0).describe())
            "1rm" -> oneRm(a.dbl(0), a.int(1), a.getOrNull(2)?.toInt())
            "aquecimento" -> warmup(exercise(a.getOrNull(0) ?: usage("aquecimento <exercício> <kg>")), a.dbl(1))
            "refeicao" -> meal(a.joinToString(" "))
            "agua" -> water(a)
            "nutricao" -> nutrition()
            "corpo" -> body(a.dbl(0))
            "suplementos" -> supplements()
            "fontes" -> sources()
            "validar" -> validate()
            "coach" -> chat()
            "pergunta" -> ask(a.joinToString(" "))
            "demo" -> demo()
            else -> { println(HELP); exitProcess(1) }
        }
    }

    private val coach by lazy { LocalCoach(kb) }
    private val chatState = ConversationState()
    private val coachContext by lazy {
        val bench = ExerciseId("barbell_bench_press")
        // -Dfitking.today=2026-10-02 fixa a data (testes e demonstrações reproduzíveis).
        val today = System.getProperty("fitking.today")?.let(LocalDate::parse) ?: LocalDate.now()
        // Histórico de exemplo para a demonstração (o app real lê do user.db).
        val history = listOf(
            ExerciseLog(bench, today.minusWeeks(3), List(3) { SetLog(60.0, 10, 2) }),
            ExerciseLog(bench, today.minusWeeks(2), List(3) { SetLog(60.0, 12, 2) }),
            ExerciseLog(bench, today.minusWeeks(1), List(3) { SetLog(62.5, 11, 2) }),
        )
        CoachContext(profile, screening, if (screening.allowsProgram) program else null, today, history)
    }

    private fun ask(text: String) {
        val r = coach.reply(text, coachContext, chatState)
        println(r.text)
        if (r.quickReplies.isNotEmpty()) println("   ↳ sugestões: " + r.quickReplies.joinToString(" | "))
        r.actions.forEach { println("   ↳ ação proposta ao app: $it") }
    }

    private fun chat() {
        println("💬 FitKingIA Coach — IA local, offline. Digite sua mensagem (\"sair\" para encerrar).")
        ask("oi")
        while (true) {
            print("\nvocê › ")
            val line = readlnOrNull() ?: break
            if (line.trim().lowercase() in setOf("sair", "exit", "tchau")) break
            if (line.isBlank()) continue
            println()
            ask(line)
        }
    }

    private fun screeningSummary() {
        out.rule("TRIAGEM DE SEGURANÇA — ${screening.status.label}")
        if (screening.messages.isEmpty()) out.line("Nenhum sinal de alerta nas respostas.") else out.explanations(screening.messages)
    }

    private fun sessionOn(d: DayOfWeek) = program.sessionOn(d)
        ?: throw IllegalArgumentException("Sem treino na ${d.pt()}. Dias de treino: ${program.trainingDays.joinToString { it.pt() }}")

    private fun quick(minutes: Int, d: DayOfWeek) {
        val adapted = SessionAdapter(kb).forTime(sessionOn(d), minutes, protect = kb.priorityMuscles(program.priorities))
        out.rule(adapted.title)
        out.explanations(adapted.explanations)
        out.session(adapted.session)
        if (adapted.changes.isNotEmpty()) { out.line(""); out.line("O que mudou e por quê:"); adapted.changes.forEach { out.line("  • $it") } }
    }

    private fun readiness(a: List<String>) {
        val sleep = when (a.getOrNull(0)) { "ruim" -> SleepQuality.POOR; "excelente" -> SleepQuality.EXCELLENT; "normal" -> SleepQuality.NORMAL; else -> usage("prontidao <ruim|normal|excelente> ...") }
        val check = ReadinessCheck(sleep, a.int(1), a.int(2), a.int(3), a.int(4))
        val result = RecoveryScorer(kb.ruleSet.recovery).score(check)
        val adapted = SessionAdapter(kb).forReadiness(sessionOn(day(a, 5)), result, UserConstraints.of(profile))
        out.rule("🔥 Índice de recuperação: ${result.score}/100 — ${result.band.label}")
        out.explanations(adapted.explanations)
        out.session(adapted.session)
        adapted.changes.forEach { out.line("  • $it") }
    }

    private fun substitute(name: String, painJoint: String?, severity: Int? = null) {
        val ex = exercise(name)
        val joint = painJoint?.let { j -> Joint.entries.firstOrNull { it.label == j.lowercase() || it.name == j.uppercase() } ?: usage("articulação: ${Joint.entries.joinToString { it.label }}") }
        val r = SubstitutionEngine(kb).find(ex.id, UserConstraints.of(profile), profile.focus, joint, painSeverity = severity ?: 3)
        out.rule("🔄 Substituições para ${r.original.name}")
        r.notes.forEach { out.line("🟢 $it") }
        r.options.forEachIndexed { i, s -> out.line("  ${i + 1}. ${s.exercise.name} (pontuação ${s.score.toInt()})"); out.line("     ${s.reasons.joinToString("; ")}") }
    }

    private fun missed(missedDay: DayOfWeek, today: DayOfWeek) {
        val r = MissedWorkoutPlanner(kb).options(program, profile, missedDay, today)
        out.rule(r.message)
        for (o in r.options) {
            out.line("")
            out.line("${o.key} — ${o.title}${if (!o.available) " (indisponível)" else ""}")
            out.line("   ${o.description}")
            o.details.forEach { out.line("   • $it") }
        }
    }

    private fun simulate() {
        val sim = ProgramSimulator(kb).compare(profile, screening, listOf(3, 4, 5).map { ProgramSimulator.days(it) })
        out.rule("🤯 SIMULADOR \"E SE?\"")
        out.line("".padEnd(42) + sim.rows.joinToString("") { it.scenario.padStart(8) })
        for (m in kb.trackedMuscles) out.line(m.name.padEnd(42) + sim.rows.joinToString("") { ProgramGenerator.fmt(it.setsByMuscle[m.id] ?: 0.0).padStart(8) })
        out.line("Tempo/semana".padEnd(42) + sim.rows.joinToString("") { "${it.weeklyMinutes}m".padStart(8) })
        out.line("")
        sim.rows.forEach { out.line("${it.scenario}: ${it.splitName ?: it.refusal}" + if (it.belowMinimum.isEmpty()) "" else " — abaixo do mínimo: ${it.belowMinimum.joinToString { m -> kb.muscleName(m).lowercase() }}") }
        out.line("")
        out.line(sim.note.toString())
    }

    private fun progressDemo() {
        val bench = kb.exercise(ExerciseId("barbell_bench_press"))
        val d = LocalDate.of(2026, 9, 7)
        val history = listOf(
            ExerciseLog(bench.id, d, List(3) { SetLog(60.0, 10, 2) }),
            ExerciseLog(bench.id, d.plusWeeks(1), List(3) { SetLog(60.0, 12, 2) }),
            ExerciseLog(bench.id, d.plusWeeks(2), List(3) { SetLog(62.5, 9, 2) }),
            ExerciseLog(bench.id, d.plusWeeks(3), List(3) { SetLog(62.5, 11, 2) }),
        )
        out.rule("📈 PROGRESSIVE OVERLOAD — ${bench.name}")
        history.forEach { l -> out.line("  ${l.date}: ${l.sets.joinToString(" / ") { "${ProgressionEngine.fmtKg(it.loadKg)} × ${it.reps}" }}") }
        val trend = Trends.of(bench.id, history)
        out.line("Tendência (1RM estimado): ${trend.direction.label} — " + trend.e1rmBySession.joinToString(" → ") { ProgramGenerator.fmt(Math.round(it.second * 10) / 10.0) + " kg" })
        val presc = ProgramGenerator(kb).prescription(TrainingFocus.HYPERTROPHY, SlotRole.SECONDARY, profile.tier)
        val s = ProgressionEngine(kb).suggest(bench, presc, 3, history)
        out.line(""); out.line("Próxima sessão (${presc.reps.first}–${presc.reps.last} reps, RIR ${presc.rir}):")
        out.line("🟢 ${s.message}"); s.pattern?.let { out.line("   $it") }; out.line("   ${s.explanation.text}")
        val newLog = ExerciseLog(bench.id, d.plusWeeks(4), listOf(SetLog(65.0, 12, 1), SetLog(65.0, 11, 1), SetLog(65.0, 10, 0)))
        out.line(""); out.line("Sessão nova: 65 kg × 12 / 11 / 10")
        PersonalRecords.detect(kb, newLog, history).forEach { out.line("  ${it.description}") }
    }

    private fun oneRm(kg: Double, reps: Int, rir: Int?) {
        val e = OneRepMax.estimate(kg, reps, rir, kb.ruleSet.progression.params.maxRepsForE1rm)
        out.line("1RM estimado: Epley ${Fmt.kg(e.epley)} · Brzycki ${Fmt.kg(e.brzycki)} · média ${Fmt.kg(e.average)}")
        e.note?.let { out.line("⚠️ $it") }
        out.line("🔵 Estimativa — a precisão varia por exercício (LeSuer et al., 1997).")
    }

    private fun warmup(ex: com.fitkingia.core.knowledge.Exercise, kg: Double) {
        out.line("Aquecimento para ${ex.name} com ${ProgressionEngine.fmtKg(kg)}:")
        WarmupGenerator.forWorkingLoad(ex, kg).forEach { out.line("  • ${ProgressionEngine.fmtKg(it.loadKg)} × ${it.reps} — ${it.note}") }
    }

    private fun meal(text: String) {
        val m = MealParser(kb).parse(text)
        out.rule("🥗 Refeição registrada")
        m.items.forEach { out.line("  • ${it.food.name}: ${it.grams.toInt()} g — ${it.kcal.toInt()} kcal, ${Fmt.fixed(it.proteinG ?: 0.0, 1)} g proteína") }
        out.line("  Total: ${m.kcal.toInt()} kcal · P ${Fmt.fixed(m.proteinG, 1)} g · C ${Fmt.fixed(m.carbsG, 1)} g · G ${Fmt.fixed(m.fatG, 1)} g · fibras ${Fmt.fixed(m.fiberG, 1)} g")
        if (m.incomplete.isNotEmpty()) out.line("  ⚠️ Total parcial para: ${m.incomplete.joinToString()} (valores não verificados).")
        out.explanations(m.notes)
        out.line("🔵 Fonte dos valores: ${kb.source(com.fitkingia.core.model.SourceId("taco_2011")).citation()}")
    }

    private fun water(a: List<String>) {
        val sweat = when (a.getOrNull(1)) { "pouco" -> SweatLevel.LOW; "muito" -> SweatLevel.HIGH; else -> SweatLevel.MODERATE }
        val t = HydrationEngine(kb.ruleSet.hydration).target(HydrationInput(profile.weightKg, a.getOrNull(0)?.toInt() ?: 0, sweat, a.contains("calor")))
        out.line("💧 Meta de hoje: ${Fmt.int(t.totalMl)} ml"); out.line(t.explanation.toString())
    }

    private fun nutrition() {
        val t = EnergyEstimator(kb.ruleSet.nutrition).targets(profile)
        out.rule("🥗 Metas estimadas — ${t.energyGoal.label}")
        out.line("  Energia: ~${Fmt.int(t.targetKcal)} kcal/dia (manutenção estimada ${Fmt.int(t.maintenanceKcal)})")
        out.line("  Proteína: ${t.proteinG.first}–${t.proteinG.last} g/dia")
        out.explanations(t.explanations)
    }

    private fun body(waist: Double) {
        val bm = BodyMetrics(kb.ruleSet.bodyMetrics)
        out.line(bm.bmi(profile.weightKg, profile.heightCm).explanation.toString())
        out.line(bm.waistToHeight(waist, profile.heightCm).explanation.toString())
        val today = LocalDate.of(2026, 10, 2)
        val trend = WeightTrend(kb.ruleSet.weightTrend).analyze(listOf(WeightEntry(today.minusDays(1), 70.2), WeightEntry(today, 71.0)))
        out.line(""); out.line("Exemplo do acompanhamento de oscilação do peso:"); out.explanations(trend.messages)
    }

    private fun supplements() {
        for (s in kb.supplements) {
            out.rule("💊 ${s.name} — ${s.evidenceLevel.label}")
            out.line("  O que é: ${s.whatIs}"); out.line("  Finalidade: ${s.purpose}"); out.line("  Evidência: ${s.evidenceSummary}")
            out.line("  Como é estudado: ${s.howStudied}"); out.line("  Limitações: ${s.limitations}"); out.line("  Cautelas: ${s.cautions}")
            s.sourceIds.forEach { out.line("  🔵 ${kb.source(it).citation()}") }
        }
    }

    private fun sources() {
        for (s in kb.sources.sortedWith(compareBy({ it.tier }, { it.id.value }))) {
            out.line("[${s.tier}] ${s.citation()}")
            out.line("     ${s.type.label} · verificação: ${s.verification} ${s.lastVerified ?: ""} · ${s.url ?: s.doi?.let { "https://doi.org/$it" } ?: ""}")
        }
        val conflicts = kb.claims.filter { it.isConflicting }
        if (conflicts.isNotEmpty()) {
            out.rule("🧪 Evidência conflitante (o sistema não inventa consenso)")
            conflicts.forEach { c ->
                out.line("• ${c.statement} (${c.evidenceLevel.label})")
                c.sources.forEach { cs -> out.line("    ${cs.stance}: ${kb.source(cs.sourceId).citation()}${cs.note?.let { " — $it" } ?: ""}") }
            }
        }
    }

    private fun validate() {
        val issues = KnowledgeValidator.validate(kb)
        out.line("Base de conhecimento ${kb.meta["content_version"]}: ${kb.exercises.size} exercícios, ${kb.sources.size} fontes, ${kb.claims.size} afirmações, ${kb.rules.size} regras")
        if (issues.isEmpty()) out.line("Sem problemas.") else issues.forEach { out.line(it.toString()) }
    }

    private fun demo() {
        run("programa", emptyList())
        val first = program.sessions.first()
        quick(35, first.day!!)
        out.why(WhyService(kb).forExercise(program, first.exercises.first().exercise.id))
        substitute(first.exercises.first().exercise.id.value, null)
        substitute("barbell_back_squat", "joelho")
        readiness(listOf("ruim", "4", "6", "7", "5", first.day!!.name))
        missed(program.sessions[1].day!!, program.sessions[1].day!!.plus(1))
        simulate()
        progressDemo()
        out.rule("🔧 Ferramentas")
        println(PlateCalculator.compute(82.5).describe())
        oneRm(75.0, 8, 2)
        meal("Hoje comi arroz, feijão, frango e banana.")
        water(listOf("60"))
        nutrition()
        body(82.0)
        out.rule("💬 IA LOCAL — conversa de exemplo (offline, sem custo)")
        for (m in listOf("oi", "hoje estou sem tempo", "uns 35 minutos", "posso trocar o agachamento?", "meu joelho está doendo",
            "treinar até a falha é melhor?", "como perder barriga?", "bebi 500 ml de água")) {
            println("\nvocê › $m"); ask(m)
        }
    }

    private fun exercise(q: String) = kb.findExercise(q) ?: throw IllegalArgumentException("Exercício não encontrado: $q")

    private fun day(a: List<String>, i: Int): DayOfWeek {
        val s = a.getOrNull(i) ?: throw IllegalArgumentException("Informe o dia (segunda, terça, ...)")
        return DayOfWeek.entries.firstOrNull { it.pt() == s.lowercase() || it.name == s.uppercase() || it.pt().replace("ç", "c").replace("á", "a") == s.lowercase() }
            ?: throw IllegalArgumentException("Dia inválido: $s")
    }

    private fun List<String>.int(i: Int) = getOrNull(i)?.toIntOrNull() ?: throw IllegalArgumentException("Argumento ${i + 1} deve ser um número inteiro")
    private fun List<String>.dbl(i: Int) = getOrNull(i)?.replace(',', '.')?.toDoubleOrNull() ?: throw IllegalArgumentException("Argumento ${i + 1} deve ser um número")
    private fun usage(s: String): Nothing = throw IllegalArgumentException("uso: fitking $s")
}
