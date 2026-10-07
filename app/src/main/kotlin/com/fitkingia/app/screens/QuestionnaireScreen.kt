package com.fitkingia.app.screens

import android.view.Gravity
import android.widget.LinearLayout
import com.fitkingia.app.Screen
import com.fitkingia.app.ui.*
import com.fitkingia.appcore.Answers
import com.fitkingia.appcore.Questionnaire
import com.fitkingia.appcore.Step
import com.fitkingia.appcore.SubmitOutcome
import com.fitkingia.core.model.*
import com.fitkingia.core.program.GoalAlignment
import com.fitkingia.core.program.ProgramResult
import com.fitkingia.core.program.pt
import com.fitkingia.core.program.ptCapitalized
import com.fitkingia.core.safety.ScreeningStatus
import java.time.DayOfWeek

/**
 * Questionário inicial: uma pergunta por página, respostas só por toque. No fim, o motor
 * determinístico gera o programa a partir das respostas.
 */
/**
 * Questionário por toque. [startAt] abre direto numa pergunta (ex.: a Home sugerindo escolher uma
 * prioridade); nesse caso "Próximo" volta ao resumo em vez de passar por todas as perguntas de novo.
 */
class QuestionnaireScreen(private val a: Answers, private val firstRun: Boolean = false, startAt: Step? = null) : Screen() {
    private var index = startAt?.let { Questionnaire.steps(a).indexOf(it).coerceAtLeast(0) } ?: 0
    private var backToSummary = startAt != null
    private var busy = false
    private var hint: String? = null

    private val steps get() = Questionnaire.steps(a)
    private val step get() = steps[index.coerceAtMost(steps.lastIndex)]

    override val title: String get() = if (firstRun) "Vamos montar seu treino" else "Questionário"
    override val page get() = index

    override fun build(root: LinearLayout) {
        val kb = fit.kb
        val total = steps.size
        root.muted("Passo ${index + 1} de $total")
        root.bar((index + 1.0) / total, bottom = 16)
        root.h1(step.title)
        step.subtitle?.let { root.muted(it, 14f) }
        root.space(8)
        when (step) {
            Step.WELCOME -> welcome(root)
            Step.SEX -> {
                root.option("Masculino", null, a.sex == Sex.MALE) { Questionnaire.selectSex(a, Sex.MALE); next() }
                root.option("Feminino", null, a.sex == Sex.FEMALE) { Questionnaire.selectSex(a, Sex.FEMALE); next() }
            }
            Step.AGE -> {
                root.stepper(a.age.toString(), "anos", big = true, steps = listOf(
                    "−5" to { a.age = (a.age - 5).coerceAtLeast(14); refresh() }, "−1" to { a.age = (a.age - 1).coerceAtLeast(14); refresh() },
                    "+1" to { a.age = (a.age + 1).coerceAtMost(90); refresh() }, "+5" to { a.age = (a.age + 5).coerceAtMost(90); refresh() },
                ))
                if (a.age < 18) root.card(stroke = C.warning) { body("O FitKingIA foi feito para adultos (18+). Menores de idade devem treinar com acompanhamento profissional.") }
            }
            Step.HEIGHT -> root.stepper(a.heightCm.toString(), "cm", big = true, steps = listOf(
                "−5" to { a.heightCm = (a.heightCm - 5).coerceAtLeast(120); refresh() }, "−1" to { a.heightCm = (a.heightCm - 1).coerceAtLeast(120); refresh() },
                "+1" to { a.heightCm = (a.heightCm + 1).coerceAtMost(230); refresh() }, "+5" to { a.heightCm = (a.heightCm + 5).coerceAtMost(230); refresh() },
            ))
            Step.WEIGHT -> root.stepper(Fmt.num(a.weightKg), "kg", big = true, steps = listOf(
                "−5" to { a.weightKg = (a.weightKg - 5).coerceAtLeast(30.0); refresh() }, "−½" to { a.weightKg = (a.weightKg - 0.5).coerceAtLeast(30.0); refresh() },
                "+½" to { a.weightKg = (a.weightKg + 0.5).coerceAtMost(300.0); refresh() }, "+5" to { a.weightKg = (a.weightKg + 5).coerceAtMost(300.0); refresh() },
            ))
            Step.WAIST -> {
                root.chips(listOf(false to "Não sei / pular", true to "Informar cintura"), { (a.waistCm != null) == it }) { yes ->
                    a.waistCm = if (yes) a.waistCm ?: (if (a.sex == Sex.FEMALE) 80 else 90) else null; refresh()
                }
                a.waistCm?.let { w ->
                    root.stepper(w.toString(), "cm", big = true, steps = listOf(
                        "−5" to { a.waistCm = (w - 5).coerceAtLeast(40); refresh() }, "−1" to { a.waistCm = (w - 1).coerceAtLeast(40); refresh() },
                        "+1" to { a.waistCm = (w + 1).coerceAtMost(200); refresh() }, "+5" to { a.waistCm = (w + 5).coerceAtMost(200); refresh() },
                    ))
                }
            }
            Step.GOAL -> goals(root, primary = true)
            Step.SECONDARY_GOAL -> goals(root, primary = false)
            Step.PRIORITY -> {
                root.option("Nenhuma — treino equilibrado", "O volume fica distribuído entre todas as regiões", a.priorities.isEmpty()) {
                    a.priorities.clear(); refresh()
                }
                root.chips(BodyRegion.values().map { it to "${it.emoji} ${it.label}" }, { it in a.priorities }) { r ->
                    Questionnaire.togglePriority(a, r); refresh()
                }
                if (a.priorities.isNotEmpty()) root.card(stroke = C.rule) {
                    explanation(com.fitkingia.core.explain.Explanation.rule(
                        "Prioridade: ${a.priorities.joinToString(" e ") { it.label.lowercase() }}. O motor dá mais séries e mais dias " +
                            "de treino para essa região e começa os treinos por ela — mas não dá para \"escolher\" de onde a gordura sai.",
                    ))
                }
            }
            Step.EXPERIENCE -> ExperienceLevel.values().forEach { e ->
                root.option(e.label, e.tier.label, a.experience == e) { a.experience = e; next() }
            }
            Step.ENVIRONMENT -> kb.environments.forEach { env ->
                root.option(env.name, if (env.equipment.isEmpty()) "Sem equipamento" else "${env.equipment.size} equipamentos", a.environment == env.id) {
                    Questionnaire.selectEnvironment(a, kb, env.id); next()
                }
            }
            Step.EQUIPMENT -> {
                val byCat = kb.equipment.groupBy { it.category }
                val catNames = mapOf("free_weight" to "Pesos livres", "accessory" to "Acessórios", "rack" to "Rack", "machine" to "Máquinas", "station" to "Estações")
                for ((cat, items) in byCat) {
                    root.label(catNames[cat] ?: cat)
                    root.chips(items.map { it.id to it.name }, { it in a.equipment }, small = true) { id ->
                        if (!a.equipment.add(id)) a.equipment.remove(id); refresh()
                    }
                }
                root.muted("${a.equipment.size} selecionados")
            }
            Step.DAYS -> days(root)
            Step.MAX_DAYS -> {
                val available = a.trainingDays.count { it.minutes >= kb.ruleSet.frequency.params.minSessionMinutes }
                root.option("O motor decide (recomendado)", "Usa seus dias disponíveis respeitando o limite do seu nível", a.maxDays == null) { a.maxDays = null; next() }
                root.chips((1..available.coerceAtMost(6)).map { it to "$it dia${if (it > 1) "s" else ""}" }, { a.maxDays == it }) { a.maxDays = it; refresh() }
            }
            Step.SPORTS -> sports(root)
            Step.ACTIVITY -> {
                val desc = mapOf(
                    ActivityLevel.SEDENTARY to "Trabalho sentado, pouca caminhada no dia a dia",
                    ActivityLevel.LIGHT to "Caminhadas leves, em pé parte do dia",
                    ActivityLevel.MODERATE to "Em pé boa parte do dia ou exercício 3–5×/semana",
                    ActivityLevel.VERY_ACTIVE to "Trabalho físico ou exercício intenso quase todo dia",
                    ActivityLevel.EXTREMELY_ACTIVE to "Trabalho braçal pesado + treino",
                )
                ActivityLevel.values().forEach { l -> root.option(l.label, desc[l], a.activity == l) { a.activity = l; next() } }
            }
            Step.SAFETY -> Questionnaire.safetyQuestions(kb, a).forEach { q ->
                root.card {
                    body(q.question)
                    chips(listOf(false to "Não", true to "Sim"), { a.safety[q.id] == it }, bottom = 4) { yes -> a.safety[q.id] = yes; refresh() }
                }
            }
            Step.PAIN -> {
                root.chips(Joint.values().map { it to it.label.replaceFirstChar { c -> c.uppercase() } }, { (a.pains[it] ?: 0) > 0 }) { j ->
                    if ((a.pains[j] ?: 0) > 0) a.pains.remove(j) else a.pains[j] = 2; refresh()
                }
                a.pains.filter { it.value > 0 }.forEach { (j, sev) ->
                    root.card {
                        h3("Dor no ${j.label}")
                        chips(Questionnaire.PAIN_LEVELS, { it == sev }, small = true, bottom = 4) { v -> a.pains[j] = v; refresh() }
                        if (sev >= 7) muted("Dor forte: exercícios que exigem essa articulação ficam de fora e o app recomenda avaliação profissional.")
                    }
                }
            }
            Step.SPLIT -> {
                val n = Questionnaire.expectedTrainingDays(a, kb)
                root.option("O motor escolhe (recomendado)", "Pela frequência, nível e objetivo", a.preferredSplit == null) { a.preferredSplit = null; next() }
                if (n > 0) root.muted("Opções para $n treino(s) por semana:")
                kb.splits.filter { it.daysPerWeek == n && (a.experience?.tier ?: TrainingTier.NOVICE) >= it.minTier }.forEach { s ->
                    root.option(s.name, "${s.daysPerWeek} dia(s) por semana · a partir de ${s.minTier.label.lowercase()}", a.preferredSplit == s.id) { a.preferredSplit = s.id; next() }
                }
            }
            Step.HYDRATION -> {
                root.label("Quanto você sua treinando?")
                root.chips(SweatLevel.values().map { it to it.label }, { a.sweat == it }) { a.sweat = it; refresh() }
                root.label("Treina em lugar quente?")
                root.chips(listOf(false to "Não", true to "Sim"), { a.hot == it }) { a.hot = it; refresh() }
                root.muted("Usado só para estimar sua meta diária de água.")
            }
            Step.SUMMARY -> summary(root)
        }
        hint?.let { root.card(stroke = C.warning) { body(it) } }
    }

    override fun footer(root: LinearLayout) {
        if (busy) {
            root.text("Gerando seu programa com o motor determinístico…", 14f, C.muted, gravity = Gravity.CENTER)
            return
        }
        val last = step == Step.SUMMARY
        val nextLabel = when (step) {
            Step.WELCOME -> "Começar"
            Step.SUMMARY -> "Gerar meu programa"
            Step.WAIST -> if (a.waistCm == null) "Pular" else "Próximo"
            Step.SECONDARY_GOAL -> if (a.secondaryGoal == null) "Pular" else "Próximo"
            Step.PRIORITY -> if (a.priorities.isEmpty()) "Equilibrado" else "Próximo"
            else -> "Próximo"
        }
        if (index == 0 && firstRun) root.button(nextLabel) { next() }
        else root.buttonRow(
            Triple("Voltar", Btn.SECONDARY) { back() },
            Triple(nextLabel, Btn.PRIMARY) { if (last) submit() else next() },
        )
    }

    override fun onBack(): Boolean {
        if (busy) return true
        if (index > 0) { back(); return true }
        return if (firstRun) false else { pop(); true }
    }

    private fun back() {
        hint = null
        if (index > 0) index-- else if (!firstRun) { pop(); return }
        main.refreshTop(this)
    }

    private fun next() {
        val blocker = Questionnaire.blocker(step, a, fit.kb)
        if (blocker != null) { hint = blocker; refresh(); return }
        hint = null
        if (backToSummary) { backToSummary = false; index = steps.lastIndex }
        else if (index < steps.lastIndex) index++
        main.refreshTop(this)
    }

    private fun welcome(root: LinearLayout) {
        root.card {
            bullets(listOf(
                "Você responde tocando nas alternativas — nada para digitar.",
                "O treino é montado por um motor de regras com base em evidências, não inventado por IA.",
                "Cada recomendação tem um botão \"Por que isso?\" com a regra e as fontes.",
                "Funciona offline. Seus dados ficam só neste aparelho.",
            ))
        }
        root.card(stroke = C.warning) {
            h3("Aviso importante")
            body("O FitKingIA é uma ferramenta educativa de apoio. Não substitui avaliação médica, fisioterapêutica, nutricional ou de um profissional de educação física. Não faz diagnóstico.")
        }
        root.option("Li e concordo em guardar minhas respostas neste aparelho", null, a.consent) { a.consent = !a.consent; refresh() }
    }

    private fun goals(root: LinearLayout, primary: Boolean) {
        if (!primary) root.option("Nenhum", null, a.secondaryGoal == null) { a.secondaryGoal = null; next() }
        for (cat in GoalCategory.values()) {
            root.label(cat.label)
            val goals = Goal.values().filter { it.category == cat && (primary || it != a.primaryGoal) }
            root.chips(goals.map { it to it.label }, { if (primary) a.primaryGoal == it else a.secondaryGoal == it }) { g ->
                if (primary) a.primaryGoal = g else a.secondaryGoal = g
                refresh()
            }
        }
        val chosen = if (primary) a.primaryGoal else a.secondaryGoal
        if (chosen?.spotReductionNotice == true) root.card(stroke = C.fact) {
            explanation(com.fitkingia.core.explain.Explanation(com.fitkingia.core.explain.Provenance.FACT,
                "Objetivo válido! Só um aviso honesto: não dá para escolher de onde a gordura sai fazendo abdominais. A cintura diminui com o conjunto — alimentação, treino de força, atividade física e sono. O app acompanha sua cintura para mostrar o progresso real."))
        }
        chosen?.let { root.muted("Foco do treino: ${it.focus.label}") }
    }

    private fun days(root: LinearLayout) {
        root.label("Atalhos")
        root.chips(Questionnaire.DAY_PRESETS.mapIndexed { i, p -> i to p.first }, { Questionnaire.DAY_PRESETS[it].second.let { p -> DayOfWeek.values().all { d -> (p[d] ?: 0) == a.minutesByDay[d] } } }, small = true) {
            Questionnaire.applyPreset(a, Questionnaire.DAY_PRESETS[it].second); refresh()
        }
        for (d in DayOfWeek.values()) {
            val m = a.minutesByDay[d] ?: 0
            root.card(bottom = 8) {
                row(bottom = 0) {
                    val name = text(d.ptCapitalized(), 16f, bold = true, bottom = 0)
                    name.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                stepper(if (m == 0) "Folga" else "$m min", bottom = 2, steps = listOf(
                    "−" to { a.minutesByDay[d] = Questionnaire.stepMinutes(m, -1); refresh() },
                    "+" to { a.minutesByDay[d] = Questionnaire.stepMinutes(m, +1); refresh() },
                ))
            }
        }
        val total = a.trainingDays
        root.muted(if (total.isEmpty()) "Nenhum dia marcado ainda." else "${total.size} dia(s) disponível(is) · ${total.sumOf { it.minutes }} min por semana")
    }

    private fun sports(root: LinearLayout) {
        val kb = fit.kb
        val selected = a.sports.map { it.sportId }.toSet()
        root.option("Não pratico", null, a.sports.isEmpty()) { a.sports.clear(); refresh() }
        root.chips(kb.sports.map { it.id to it.name }, { it in selected }) { id ->
            if (id in selected) a.sports.removeAll { it.sportId == id } else a.sports.add(SportCommitment(id, DayOfWeek.TUESDAY, 2))
            refresh()
        }
        for (id in a.sports.map { it.sportId }.distinct()) {
            val entries = a.sports.filter { it.sportId == id }
            val intensity = entries.first().intensity
            root.card {
                h3(kb.sport(id).name)
                label("Dias")
                chips(DayOfWeek.values().map { it to it.pt().take(3).replaceFirstChar { c -> c.uppercase() } }, { d -> entries.any { it.day == d } }, small = true) { d ->
                    val has = entries.any { it.day == d }
                    if (has && entries.size > 1) a.sports.removeAll { it.sportId == id && it.day == d }
                    else if (!has) a.sports.add(SportCommitment(id, d, intensity))
                    refresh()
                }
                label("Intensidade")
                chips(Questionnaire.SPORT_INTENSITY, { it == intensity }, small = true, bottom = 4) { v ->
                    a.sports = a.sports.map { if (it.sportId == id) it.copy(intensity = v) else it }.toMutableList(); refresh()
                }
            }
        }
    }

    private fun summary(root: LinearLayout) {
        val kb = fit.kb
        root.card {
            fun item(k: String, v: String, step: Step, color: Int = C.text) {
                val r = kv(k, "$v  ›", color)
                r.isClickable = true
                r.setOnClickListener { goTo(step) }
                r.setPadding(0, dp(4), 0, dp(4))
            }
            item("Sexo", if (a.sex == Sex.MALE) "Masculino" else "Feminino", Step.SEX)
            item("Idade", "${a.age} anos", Step.AGE)
            item("Altura / peso", "${a.heightCm} cm · ${Fmt.num(a.weightKg)} kg", Step.WEIGHT)
            item("Cintura", a.waistCm?.let { "$it cm" } ?: "não informada", Step.WAIST)
            item("Objetivo", a.primaryGoal?.label ?: "—", Step.GOAL)
            item("Secundário", a.secondaryGoal?.label ?: "nenhum", Step.SECONDARY_GOAL)
            item("Prioridade", a.priorities.joinToString { it.label }.ifEmpty { "equilibrado" }, Step.PRIORITY)
            item("Experiência", a.experience?.label ?: "—", Step.EXPERIENCE)
            item("Local", a.environment?.let { kb.environment(it).name } ?: "—", Step.ENVIRONMENT)
            item("Equipamentos", "${a.equipment.size}", Step.EQUIPMENT)
            item("Dias", a.trainingDays.joinToString { "${it.day.pt().take(3)} ${it.minutes}′" }.ifEmpty { "—" }, Step.DAYS)
            item("Máx. de dias", a.maxDays?.toString() ?: "motor decide", Step.MAX_DAYS)
            item("Esportes", a.sports.groupBy { it.sportId }.keys.joinToString { kb.sport(it).name }.ifEmpty { "nenhum" }, Step.SPORTS)
            item("Atividade", a.activity?.label ?: "—", Step.ACTIVITY)
            val flagged = Questionnaire.safetyQuestions(kb, a).count { a.safety[it.id] == true }
            item("Triagem", if (flagged == 0) "sem alertas" else "$flagged resposta(s) \"sim\"", Step.SAFETY, if (flagged == 0) C.success else C.warning)
            if (a.pains.isNotEmpty()) item("Dor", a.pains.entries.joinToString { "${it.key.label} ${it.value}/10" }, Step.PAIN)
        }
        root.muted("Toque numa linha para mudar a resposta. O mesmo conjunto de respostas sempre gera o mesmo programa.")
    }

    /** Volta direto a uma pergunta (ex.: a partir do resumo). */
    fun goTo(step: Step) {
        val i = steps.indexOf(step)
        if (i >= 0) { index = i; hint = null; main.refreshTop(this) }
    }

    private fun submit() {
        for ((i, s) in steps.withIndex()) {
            val b = Questionnaire.blocker(s, a, fit.kb)
            if (b != null) { index = i; hint = b; refresh(); return }
        }
        busy = true
        refresh()
        main.background({ fit.submit(a.deepCopy()) }) { r ->
            busy = false
            r.onSuccess { main.setRoot(ProgramReadyScreen(it)) }
                .onFailure { hint = "Erro ao gerar: ${it.message}"; refresh() }
        }
    }
}

/** Resultado do questionário: programa pronto (com o porquê) ou recusa explicada. */
class ProgramReadyScreen(private val outcome: SubmitOutcome) : Screen() {
    override val title = "Seu programa"

    override fun build(root: LinearLayout) {
        when (val r = outcome.result) {
            is ProgramResult.Generated -> {
                val p = r.program
                root.text("✅", 44f, gravity = Gravity.CENTER)
                root.h1("Programa pronto")
                root.body("${p.split.name} · ${p.sessions.size} treino(s) por semana · ~${p.weeklyMinutes} min/semana")
                root.card {
                    p.sessions.forEach { s -> kv(s.day?.ptCapitalized() ?: "—", "${s.name} · ${s.estimatedMinutes} min") }
                }
                if (outcome.screening.status == ScreeningStatus.CAUTION) root.card(stroke = C.warning) {
                    h3("Liberado com cautela")
                    outcome.screening.messages.forEach { explanation(it) }
                }
                val kb = fit.kb
                root.goalSection(GoalAlignment(kb).check(p, outcome.profile), kb)
                val warnings = p.warnings
                if (warnings.isNotEmpty()) {
                    root.h2("Avisos do motor")
                    root.card(stroke = C.warning) { warnings.forEach { explanation(it) } }
                }
                root.h2("Por que este programa?")
                root.card { p.explanations.forEach { explanation(it) } }
                root.button("Ver meu treino de hoje") { main.setRoot(HomeScreen()) }
            }
            is ProgramResult.Refused -> {
                root.text("⚠️", 44f, gravity = Gravity.CENTER)
                root.h1(if (outcome.screening.status == ScreeningStatus.REFER) "Procure um profissional antes" else "Não foi possível gerar")
                root.card(stroke = C.warning) { r.reasons.forEach { explanation(it) } }
                root.body("Suas respostas ficaram salvas. Água, sono, peso, nutrição e o conteúdo educativo continuam disponíveis.")
                root.button("Revisar respostas") { main.setRoot(QuestionnaireScreen(fit.currentAnswers())) }
                root.button("Ir para o início", Btn.SECONDARY) { main.setRoot(HomeScreen()) }
            }
        }
    }
}
