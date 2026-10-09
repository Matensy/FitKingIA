package com.fitkingia.coach

import com.fitkingia.coach.evidence.EvidenceSearch
import com.fitkingia.coach.nlu.Entities
import com.fitkingia.coach.nlu.EntityExtractor
import com.fitkingia.coach.nlu.Intent
import com.fitkingia.coach.nlu.IntentModel
import com.fitkingia.coach.text.PtText
import com.fitkingia.core.analytics.VolumeDashboard
import com.fitkingia.core.body.BodyMetrics
import com.fitkingia.core.body.WeightTrend
import com.fitkingia.core.explain.WhyService
import com.fitkingia.core.hydration.HydrationEngine
import com.fitkingia.core.hydration.HydrationInput
import com.fitkingia.core.hydration.WaterLog
import com.fitkingia.core.knowledge.Exercise
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.*
import com.fitkingia.core.nutrition.EnergyEstimator
import com.fitkingia.core.nutrition.MealParser
import com.fitkingia.core.planning.MissedWorkoutPlanner
import com.fitkingia.core.planning.ProgramSimulator
import com.fitkingia.core.planning.Scenario
import com.fitkingia.core.program.*
import com.fitkingia.core.progression.OneRepMax
import com.fitkingia.core.progression.ProgressionEngine
import com.fitkingia.core.progression.Trends
import com.fitkingia.core.recovery.ReadinessCheck
import com.fitkingia.core.recovery.RecoveryScorer
import com.fitkingia.core.session.SessionAdapter
import com.fitkingia.core.substitution.ExerciseFinder
import com.fitkingia.core.substitution.SubstitutionEngine
import com.fitkingia.core.tools.PlateCalculator
import java.time.DayOfWeek
import java.time.temporal.TemporalAdjusters
import kotlin.math.roundToInt

/**
 * IA local do FitKingIA — roda no aparelho, sem internet e sem custo por uso.
 *
 *  1. Camada de segurança (sinais de alerta → orientação imediata, sem conselho de treino).
 *  2. Entendimento: classificador Naive Bayes de intenções + extração de entidades.
 *  3. Diálogo: memória curta, perguntas de esclarecimento e de dados faltantes; aprende com as escolhas.
 *  4. Resposta: cada intenção chama um motor determinístico do core; fatos vêm do banco de evidências.
 *
 * A IA nunca inventa prescrição, carga ou referência: ela interpreta o pedido e apresenta o que o motor decidiu.
 */
class LocalCoach(
    private val kb: KnowledgeBase,
    private val extractor: EntityExtractor = EntityExtractor(kb),
    private val model: IntentModel = IntentModel.default(extractor::tags),
) {
    private val evidence = EvidenceSearch(kb)
    private val generator = ProgramGenerator(kb)

    fun reply(message: String, ctx: CoachContext, state: ConversationState): CoachReply {
        val text = message.trim()
        if (text.isBlank()) return help(ctx)
        val n = PtText.normalize(text)
        safety(n)?.let { state.pending = null; return it }

        val preferred = ctx.program?.sessions?.flatMap { s -> s.exercises.map { it.exercise.id } }?.toSet().orEmpty()
        val e = extractor.extract(text, ctx.today, preferred)

        // Resposta a uma pergunta que a IA fez no turno anterior.
        when (val p = state.pending) {
            is Pending.Clarify -> chooseOption(n, p.options)?.let { chosen ->
                state.pending = null
                model.learn(p.original, chosen) // aprende: da próxima vez, a frase original já cai aqui
                return handle(chosen, p.original, merge(extractor.extract(p.original, ctx.today, preferred), e), ctx, state, 1.0)
            }
            is Pending.AwaitSlot -> if (fills(p.slot, e)) {
                state.pending = null
                return handle(p.intent, "${p.original} $text", merge(extractor.extract(p.original, ctx.today, preferred), e), ctx, state, 1.0)
            }
            null -> {}
        }
        state.pending = null

        val prediction = model.predict(text)
        val ruled = override(n, e, text)
        val intent = ruled ?: prediction.intent
        val confidence = if (ruled != null) 1.0 else prediction.confidence
        if (ruled == null) {
            val second = prediction.runnerUp
            if (confidence < CLARIFY_BELOW && second != null && second.second >= 0.12) {
                val options = listOf(prediction.intent, second.first)
                state.pending = Pending.Clarify(options, text)
                return CoachReply(
                    Say.me("Não tenho certeza do que você quer. Você quer:\n  1 — ${options[0].label}\n  2 — ${options[1].label}"),
                    null, confidence, quickReplies = listOf("1", "2"),
                )
            }
            if (confidence < FALLBACK_BELOW) return fallback(ctx, confidence)
        }
        return handle(intent, text, e, ctx, state, confidence)
    }

    // ------------------------------------------------------------------------------------------ segurança
    private val redFlags = Regex(
        "dor no peito|aperto no peito|pressao no peito|peito (doendo|apertado)|desmai|apaguei|tontura forte|quase desmai|" +
            "falta de ar|nao consigo respirar|palpitac|coracao (disparad|acelerado demais)|batimento irregular|visao escura|" +
            "formigamento no braco|dormencia no braco|braco esquerdo (doendo|dormente)",
    )
    private val selfHarm = Regex("me matar|suicid|nao quero (mais )?viver|me machucar de proposito|acabar com (a minha|minha) vida")
    private val drugs = Regex("anabolizante|esteroide|testosterona|hormonio|oxandrolona|\\bdeca\\b|trembolona|clembuterol|sibutramina|ozempic|semaglutida|tirzepatida|remedio (pra|para) emagrecer")
    private val disordered = Regex("vomitar depois|provocar vomito|laxante (pra|para) emagrecer|ficar (dias )?sem comer|jejum de (\\d+ )?dias")

    private fun safety(n: String): CoachReply? = when {
        selfHarm.containsMatchIn(n) -> CoachReply(
            Say.me("Sinto muito que você esteja passando por isso. Você não precisa lidar com isso sozinho: ligue para o CVV no 188 " +
                "(24 horas, gratuito) ou procure um serviço de saúde. Em risco imediato, ligue 192 (SAMU)."), null, 1.0,
        )
        redFlags.containsMatchIn(n) -> CoachReply(
            Say.me("⚠️ Pare o exercício agora. Dor ou aperto no peito, desmaio, falta de ar desproporcional, palpitações ou " +
                "formigamento no braço são sinais de alerta. Procure atendimento médico — em emergência, ligue 192 (SAMU). " +
                "Não vou sugerir treino até que um profissional avalie."), null, 1.0,
        )
        disordered.containsMatchIn(n) -> CoachReply(
            Say.me("Esse tipo de estratégia pode fazer mal à saúde, e eu não oriento nada nessa linha. Se estiver difícil lidar com " +
                "comida ou com o corpo, conversar com um profissional ajuda muito — e o CVV (188) atende 24 horas."), null, 1.0,
        )
        drugs.containsMatchIn(n) -> CoachReply(
            Say.me("Não oriento sobre hormônios, anabolizantes ou medicamentos para emagrecer: isso exige avaliação e acompanhamento " +
                "médico. Posso ajudar com treino, alimentação baseada no Guia Alimentar e informações sobre suplementos com evidência."),
            null, 1.0,
        )
        else -> null
    }

    // ------------------------------------------------------------------------------------------ entendimento
    /** Regras de alta precisão que corrigem o classificador em padrões inequívocos. */
    private fun override(n: String, e: Entities, original: String): Intent? = when {
        Regex("^(oi|ola|opa|e ai|eai|eae|salve|bom dia|boa tarde|boa noite|fala)( coach| tudo bem| tudo certo| tudo bom)?$").matches(n) -> Intent.GREETING
        Regex("^(obrigad[oa]|valeu|vlw|brigad[oa]|tmj|agradeco|show|top)( mesmo| coach| demais| pela ajuda)?$").matches(n) -> Intent.THANKS
        Regex("\\b(perdi|faltei|pulei|furei|deixei de treinar|nao fui (treinar|na academia)|nao consegui treinar|nao deu (pra|para) (ir )?treinar|fiquei sem treinar)\\b").containsMatchIn(n) -> Intent.MISSED
        Regex("\\b(meu )?peso (subiu|aumentou|baixou|caiu|oscil|variou|nao baixa)|\\bbalanca\\b|\\bengordei\\b|\\bemagreci\\b|\\binchad").containsMatchIn(n) -> Intent.WEIGHT_CHANGE
        e.readiness.any && !e.whatIf -> Intent.TIRED
        e.kg != null && Regex("\\b(anilha|anilhas|barra|cada lado|montar|monta)\\b").containsMatchIn(n) && e.reps == null -> Intent.PLATES
        e.kg != null && e.reps != null && Regex("\\b(1rm|rm|maximo|maxima|uma repeticao|carga maxima)\\b|\\bx\\s*\\d").containsMatchIn(n) -> Intent.ONE_RM
        e.whatIf && (e.daysCount != null || e.minutes != null || e.atHome) -> Intent.SIMULATE
        e.joint != null && Regex("\\b(dor|doi|doendo|dolorid|fisgada|machuq|lesion|tendinite|estal|incomod|travou|travad|torci|distend)").containsMatchIn(n) &&
            !Regex("dor muscular").containsMatchIn(n) -> Intent.PAIN
        e.minutes != null && !e.whatIf && Regex("\\b(tenho|so|apenas|somente|consigo|sobrou|tempo|rapido|curto)\\b").containsMatchIn(n) -> Intent.SHORT_ON_TIME
        Regex("\\b(comi|almocei|jantei|lanchei|merendei)\\b").containsMatchIn(n) && MealParser(kb).parse(original).items.isNotEmpty() -> Intent.MEAL
        e.ml != null && Regex("\\b(bebi|tomei)\\b").containsMatchIn(n) && Regex("\\bagua\\b|\\bml\\b|copo|garrafa|litro").containsMatchIn(n) -> Intent.WATER
        e.exercise != null && Regex("\\b(por que|porque|pq|motivo|justificativa|razao)\\b").containsMatchIn(n) -> Intent.EXPLAIN_EXERCISE
        e.exercise != null && Regex("\\b(como (faco|faz|fazer|executo|executa|executar)|execucao|tecnica|ensina|postura|tutorial)\\b").containsMatchIn(n) -> Intent.EXERCISE_HOWTO
        else -> null
    }

    private fun chooseOption(n: String, options: List<Intent>): Intent? {
        Regex("^\\s*(?:opcao\\s*)?([12])\\b").find(n)?.let { return options.getOrNull(it.groupValues[1].toInt() - 1) }
        if (Regex("\\bprimeir").containsMatchIn(n)) return options[0]
        if (Regex("\\b(segunda opcao|a segunda|o segundo)\\b").containsMatchIn(n)) return options.getOrNull(1)
        return null
    }

    private fun fills(slot: Slot, e: Entities): Boolean = when (slot) {
        Slot.EXERCISE -> e.exercise != null
        Slot.MINUTES -> e.minutes != null
        Slot.JOINT -> e.joint != null
        Slot.MUSCLE -> e.muscle != null
        Slot.KG -> e.kg != null
        Slot.KG_REPS -> e.kg != null && e.reps != null
        Slot.DAY -> e.day != null
    }

    private fun merge(a: Entities, b: Entities) = Entities(
        day = b.day ?: a.day, dayWord = b.dayWord ?: a.dayWord, minutes = b.minutes ?: a.minutes, exercise = b.exercise ?: a.exercise,
        muscle = b.muscle ?: a.muscle, joint = b.joint ?: a.joint, painSeverity = b.painSeverity ?: a.painSeverity,
        kg = b.kg ?: a.kg, reps = b.reps ?: a.reps, rir = b.rir ?: a.rir, ml = b.ml ?: a.ml, daysCount = b.daysCount ?: a.daysCount,
        withoutEquipment = a.withoutEquipment + b.withoutEquipment, withEquipment = a.withEquipment + b.withEquipment,
        atHome = a.atHome || b.atHome, readiness = if (b.readiness.any) b.readiness else a.readiness,
        supplement = b.supplement ?: a.supplement, whatIf = a.whatIf || b.whatIf,
    )

    private fun ask(intent: Intent, slot: Slot, original: String, state: ConversationState, prefix: String? = null): CoachReply {
        state.pending = Pending.AwaitSlot(intent, slot, original)
        return CoachReply(Say.me(listOfNotNull(prefix, slot.question).joinToString(" ")), intent, 1.0)
    }

    // ------------------------------------------------------------------------------------------ respostas
    private fun handle(intent: Intent, text: String, e: Entities, ctx: CoachContext, state: ConversationState, conf: Double): CoachReply {
        state.lastIntent = intent
        e.exercise?.let { state.lastExercise = it.id }
        e.day?.let { state.lastDay = it }
        val body: CoachReply = when (intent) {
            Intent.TODAY_WORKOUT -> today(text, e, ctx)
            Intent.SHORT_ON_TIME -> shortOnTime(text, e, ctx, state)
            Intent.SUBSTITUTE -> substitute(text, e, ctx, state)
            Intent.PAIN -> pain(text, e, ctx, state)
            Intent.TIRED -> tired(e, ctx)
            Intent.EXPLAIN_EXERCISE -> explainExercise(e, ctx, state)
            Intent.EXERCISE_HOWTO -> howTo(text, e, ctx, state)
            Intent.EXPLAIN_DAY -> explainDay(e, ctx)
            Intent.PROGRESSION -> progression(text, e, ctx, state)
            Intent.FIND_EXERCISE -> find(text, e, ctx, state)
            Intent.SIMULATE -> simulate(e, ctx)
            Intent.MISSED -> missed(text, e, ctx, state)
            Intent.PLATES -> if (e.kg == null) ask(intent, Slot.KG, text, state) else plates(e.kg)
            Intent.ONE_RM -> if (e.kg == null || e.reps == null) ask(intent, Slot.KG_REPS, text, state) else oneRm(e.kg, e.reps, e.rir)
            Intent.MEAL -> meal(text)
            Intent.WATER -> water(e, ctx)
            Intent.NUTRITION -> nutrition(ctx)
            Intent.SUPPLEMENT -> supplement(e)
            Intent.SPOT_REDUCTION -> spotReduction(ctx)
            Intent.EVIDENCE -> evidenceAnswer(text)
            Intent.BODY -> body(ctx)
            Intent.WEIGHT_CHANGE -> weightChange(ctx)
            Intent.VOLUME -> volume(ctx)
            Intent.GREETING -> greeting(ctx)
            Intent.HELP -> help(ctx)
            Intent.THANKS -> CoachReply(Say.me("Tamo junto! Bons treinos. 💪"), intent, conf)
        }
        return body.copy(intent = body.intent ?: intent, confidence = conf)
    }

    private fun reply(intent: Intent, vararg lines: String?, quick: List<String> = emptyList(), actions: List<CoachAction> = emptyList()) =
        CoachReply(lines.filterNotNull().joinToString("\n"), intent, 1.0, quick, actions)

    private fun noProgram(intent: Intent) = reply(intent, Say.me("Você ainda não tem um programa ativo. Gere o programa (questionário + triagem de segurança) e eu te acompanho."))

    /** Sessão do dia pedido; sem dia, a de hoje; sem treino hoje, a próxima. */
    private fun sessionFor(day: DayOfWeek?, ctx: CoachContext): Pair<PlannedSession?, String?> {
        val program = ctx.program ?: return null to null
        if (day != null) {
            val s = program.sessionOn(day)
            return s to if (s == null) "${day.ptCapitalized()} não tem treino no seu programa (dias: ${program.trainingDays.joinToString { it.pt() }})." else null
        }
        val todayDow = ctx.today.dayOfWeek
        program.sessionOn(todayDow)?.let { return it to null }
        val next = (1..7).map { todayDow.plus(it.toLong()) }.firstNotNullOfOrNull { program.sessionOn(it) }
        return next to "Hoje (${todayDow.pt()}) é dia livre. O próximo treino é ${next?.day?.pt()}:"
    }

    private fun today(text: String, e: Entities, ctx: CoachContext): CoachReply {
        val program = ctx.program ?: return noProgram(Intent.TODAY_WORKOUT)
        if (e.day == null && Regex("\\b(semana|quais dias|programa|programacao|rotina)\\b").containsMatchIn(PtText.normalize(text))) {
            return reply(Intent.TODAY_WORKOUT, Say.rule("${program.split.name} — ${program.sessions.size} treinos, ~${program.weeklyMinutes} min/semana:"),
                program.sessions.joinToString("\n") { s -> "  • ${s.day?.ptCapitalized()}: ${s.name} (~${s.estimatedMinutes} min, ${s.exercises.size} exercícios)" })
        }
        val (s, note) = sessionFor(e.day, ctx)
        if (s == null) return reply(Intent.TODAY_WORKOUT, Say.rule(note ?: "Sem treino."),
            Say.me("Quer ver a semana inteira? Pergunte \"quais dias eu treino\"."))
        return reply(Intent.TODAY_WORKOUT, note?.let(Say::rule), Say.rule(Say.session(s)),
            Say.me("Pergunte \"por que isso?\" sobre qualquer exercício, ou diga quanto tempo você tem para eu adaptar."),
            quick = listOf("Tenho só 40 minutos", "Por que esse treino?", "Hoje estou cansado"))
    }

    private fun shortOnTime(text: String, e: Entities, ctx: CoachContext, state: ConversationState): CoachReply {
        val program = ctx.program ?: return noProgram(Intent.SHORT_ON_TIME)
        val minutes = e.minutes ?: return ask(Intent.SHORT_ON_TIME, Slot.MINUTES, text, state)
        val (s, note) = sessionFor(e.day, ctx)
        s ?: return reply(Intent.SHORT_ON_TIME, Say.rule(note ?: "Sem treino nesse dia."))
        // Como o app ("⚡ Pouco tempo") e o gerador: a região priorizada sai por último.
        val adapted = SessionAdapter(kb).forTime(s, minutes, protect = kb.priorityMuscles(program.priorities))
        val changes = adapted.changes.joinToString("\n") { "  • $it" }
        return reply(Intent.SHORT_ON_TIME,
            Say.me("Entendi: você tem $minutes minutos. ${note ?: ""}".trim()),
            Say.rule("⚡ TREINO RÁPIDO — ${Say.session(adapted.session)}"),
            adapted.explanations.joinToString("\n") { Say.of(it) },
            if (changes.isNotEmpty()) "O que mudou e por quê:\n$changes" else null,
        )
    }

    private fun resolveExercise(e: Entities, state: ConversationState): Exercise? =
        e.exercise ?: state.lastExercise?.let { kb.exerciseOrNull(it) }

    private fun substitute(text: String, e: Entities, ctx: CoachContext, state: ConversationState): CoachReply {
        val ex = resolveExercise(e, state) ?: return ask(Intent.SUBSTITUTE, Slot.EXERCISE, text, state, "Posso sugerir substitutos.")
        state.lastExercise = ex.id
        val constraints = UserConstraints.of(ctx.profile).let { c ->
            if (e.withoutEquipment.isNotEmpty()) c.copy(equipment = c.equipment - e.withoutEquipment) else c
        }.let { c -> if (Regex("ocupad|quebrad|nao tem|sem").containsMatchIn(PtText.normalize(text))) c.copy(excluded = c.excluded + ex.id) else c }
        val r = SubstitutionEngine(kb).find(ex.id, constraints, ctx.profile.focus, e.joint, painSeverity = e.painSeverity ?: 3, limit = 3)
        if (r.options.isEmpty()) return reply(Intent.SUBSTITUTE, Say.rule("Não encontrei substituto para ${ex.name} com seu equipamento e restrições."))
        return reply(Intent.SUBSTITUTE,
            Say.me("Opções no lugar de ${ex.name}, da mais parecida para a menos:"),
            r.options.mapIndexed { i, o -> "  ${i + 1}. ${o.exercise.name} — ${o.reasons.take(3).joinToString("; ")}" }.joinToString("\n"),
            r.notes.joinToString("\n") { Say.rule(it) }.ifBlank { null },
            Say.rule("Mantenha a mesma faixa de repetições e RIR; a carga recomeça pela primeira sessão do novo exercício."),
        )
    }

    private fun pain(text: String, e: Entities, ctx: CoachContext, state: ConversationState): CoachReply {
        val joint = e.joint ?: return ask(Intent.PAIN, Slot.JOINT, text, state, "Sinto muito pela dor.")
        val severity = e.painSeverity
        val lines = mutableListOf(Say.me(
            "Você relatou dor no ${joint.label}. Não consigo diagnosticar — considere interromper ou modificar movimentos que " +
                "agravem os sintomas e procure avaliação profissional quando necessário."))
        if (severity != null && severity >= 7) lines += Say.me("⚠️ Dor forte (${severity}/10): pare o treino dessa região hoje e procure um profissional de saúde.")
        val (s, note) = sessionFor(e.day, ctx)
        val limitation = JointLimitation(joint, severity ?: 3)
        val constraints = UserConstraints.of(ctx.profile).copy(limitations = ctx.profile.limitations + limitation)
        val targets = when {
            e.exercise != null -> listOf(e.exercise)
            s != null -> s.exercises.map { it.exercise }.filter { it.demand(joint) > limitation.maxAllowedDemand }
            else -> emptyList()
        }
        if (s != null && e.exercise == null) {
            note?.let { lines += Say.rule(it) }
            lines += if (targets.isEmpty()) Say.rule("Na sessão ${s.name}, nenhum exercício tem demanda alta no ${joint.label} para o nível de dor informado.")
            else Say.rule("Na sessão ${s.name}, exercícios que exigem o ${joint.label}:")
        }
        for (ex in targets) {
            val alt = SubstitutionEngine(kb).find(ex.id, constraints, ctx.profile.focus, joint, painSeverity = severity ?: 3, limit = 2).options
            lines += "  • ${ex.name} → " + (alt.joinToString(" ou ") { it.exercise.name }.ifBlank { "sem alternativa segura com seu equipamento; pule hoje" })
        }
        lines += Say.me("Se quiser, registro essa dor no seu perfil para o programa evitar esses movimentos até melhorar.")
        state.lastExercise = e.exercise?.id ?: state.lastExercise
        return reply(Intent.PAIN, *lines.toTypedArray())
    }

    private fun tired(e: Entities, ctx: CoachContext): CoachReply {
        ctx.program ?: return noProgram(Intent.TIRED)
        val h = e.readiness
        val check = ReadinessCheck(
            sleep = h.sleep ?: SleepQuality.NORMAL,
            energy = h.energy ?: if (h.any) 6 else 4,
            soreness = h.soreness ?: 3, stress = h.stress ?: 4, motivation = h.motivation ?: 6,
        )
        val result = RecoveryScorer(kb.ruleSet.recovery).score(check)
        val (s, note) = sessionFor(e.day, ctx)
        s ?: return reply(Intent.TIRED, Say.rule(note ?: "Sem treino."))
        val adapted = SessionAdapter(kb).forReadiness(s, result, UserConstraints.of(ctx.profile))
        return reply(Intent.TIRED,
            Say.me("Estimei sua prontidão pelo que você escreveu (sono ${check.sleep.label.substringAfter(' ').lowercase()}, energia ${check.energy}/10, " +
                "dor muscular ${check.soreness}/10, estresse ${check.stress}/10, motivação ${check.motivation}/10). Faça a avaliação do dia completa (“🙂 Como estou”, na tela Hoje) para um ajuste mais preciso."),
            Say.rule("🔥 Índice de recuperação: ${result.score}/100 — ${result.band.label}"),
            note?.let(Say::rule),
            Say.rule(Say.session(adapted.session)),
            adapted.changes.joinToString("\n") { "  • $it" }.ifBlank { null },
            adapted.explanations.drop(1).joinToString("\n") { Say.of(it) }.ifBlank { null },
        )
    }

    private fun explainExercise(e: Entities, ctx: CoachContext, state: ConversationState): CoachReply {
        val program = ctx.program ?: return noProgram(Intent.EXPLAIN_EXERCISE)
        val inProgram = program.sessions.flatMap { it.exercises }.map { it.exercise.id }.toSet()
        val ex = resolveExercise(e, state)
            ?: sessionFor(e.day, ctx).first?.exercises?.firstOrNull()?.exercise
            ?: return reply(Intent.EXPLAIN_EXERCISE, Say.me("Sobre qual exercício?"))
        state.lastExercise = ex.id
        if (ex.id !in inProgram) return howToReply(ex, Say.me("${ex.name} não está no seu programa atual. Veja como ele funciona:"))
        val why = WhyService(kb).forExercise(program, ex.id, e.day?.takeIf { d -> program.sessionOn(d)?.exercises?.any { it.exercise.id == ex.id } == true })
        val rules = why.rules.take(2).joinToString("\n") { r ->
            val claims = r.evidence.take(2).joinToString("\n") { ev ->
                "   🔵 ${ev.statement} (${ev.level.label}${if (ev.conflicting) ", conflitante" else ""})" +
                    ev.supports.take(1).joinToString("") { "\n      ✔ ${it.citation.take(140)}" }
            }
            Say.rule("Regra ${r.id} (${r.basis.label.lowercase()}): ${r.description}") + if (claims.isNotBlank()) "\n$claims" else ""
        }
        return reply(Intent.EXPLAIN_EXERCISE, Say.me(why.title), why.lines.joinToString("\n") { "  • $it" }, rules)
    }

    private fun howTo(text: String, e: Entities, ctx: CoachContext, state: ConversationState): CoachReply {
        val ex = resolveExercise(e, state) ?: return ask(Intent.EXERCISE_HOWTO, Slot.EXERCISE, text, state)
        state.lastExercise = ex.id
        return howToReply(ex, null)
    }

    private fun howToReply(ex: Exercise, head: String?): CoachReply = reply(Intent.EXERCISE_HOWTO,
        head,
        Say.rule("${ex.name} — ${kb.patternName(ex.pattern).lowercase()}; músculos principais: ${ex.primaryMuscles.joinToString { kb.muscleName(it).lowercase() }}"),
        ex.instructions.mapIndexed { i, s -> "  ${i + 1}. $s" }.joinToString("\n").ifBlank { null },
        ex.commonMistakes.takeIf { it.isNotEmpty() }?.let { "Erros comuns: " + it.joinToString("; ") },
        ex.safetyNotes.takeIf { it.isNotEmpty() }?.let { "Segurança: " + it.joinToString("; ") },
        ex.progressionMethods.firstOrNull()?.let { "Progressão: $it" },
    )

    private fun explainDay(e: Entities, ctx: CoachContext): CoachReply {
        val program = ctx.program ?: return noProgram(Intent.EXPLAIN_DAY)
        val why = WhyService(kb).forDay(program, e.day ?: ctx.today.dayOfWeek)
        return reply(Intent.EXPLAIN_DAY, Say.me(why.title), why.lines.joinToString("\n") { Say.rule(it) },
            why.rules.firstOrNull()?.let { Say.rule("Regra ${it.id}: ${it.rationale}") })
    }

    private fun progression(text: String, e: Entities, ctx: CoachContext, state: ConversationState): CoachReply {
        val ex = resolveExercise(e, state) ?: return ask(Intent.PROGRESSION, Slot.EXERCISE, text, state, "Posso calcular a próxima carga.")
        state.lastExercise = ex.id
        val planned = ctx.program?.sessions?.flatMap { it.exercises }?.firstOrNull { it.exercise.id == ex.id }
        val presc = planned?.prescription ?: generator.prescription(ctx.profile.focus, SlotRole.SECONDARY, ctx.profile.tier, ex)
        val s = ProgressionEngine(kb).suggest(ex, presc, planned?.sets ?: 3, ctx.history)
        val trend = Trends.of(ex.id, ctx.history)
        return reply(Intent.PROGRESSION,
            Say.me("${ex.name} — meta ${presc.target}${if (presc.holdSeconds == null) " repetições" else ""}, RIR ${presc.rir}."),
            Say.rule(s.message),
            s.pattern?.let { Say.rule(it) },
            Say.rule(s.explanation.text),
            trend.takeIf { it.direction.name != "INSUFFICIENT_DATA" }?.let {
                Say.rule("Tendência do 1RM estimado: ${it.direction.label.lowercase()} (" +
                    it.e1rmBySession.joinToString(" → ") { p -> Say.kg((p.second * 10).roundToInt() / 10.0) } + ")")
            },
        )
    }

    private fun find(text: String, e: Entities, ctx: CoachContext, state: ConversationState): CoachReply {
        val muscle = e.muscle ?: return ask(Intent.FIND_EXERCISE, Slot.MUSCLE, text, state)
        val hypothetical = e.atHome || e.withEquipment.isNotEmpty() || e.withoutEquipment.isNotEmpty()
        val constraints = if (hypothetical) UserConstraints(ctx.profile.tier, kb.equipment.map { it.id }.toSet())
        else UserConstraints.of(ctx.profile)
        val q = ExerciseFinder.Query(muscle = muscle, withoutEquipment = e.withoutEquipment,
            onlyEquipment = e.withEquipment.ifEmpty { null }, homeFriendly = e.atHome)
        val found = ExerciseFinder(kb).find(q, constraints, limit = 6)
        if (found.isEmpty()) return reply(Intent.FIND_EXERCISE, Say.rule("Não achei exercício de ${kb.muscleName(muscle).lowercase()} com essas condições."))
        state.lastExercise = found.first().id
        return reply(Intent.FIND_EXERCISE,
            Say.me("Exercícios para ${kb.muscleName(muscle).lowercase()}${if (e.atHome) " em casa" else ""}" +
                (if (e.withoutEquipment.isNotEmpty()) " sem o equipamento citado" else "") + ":"),
            found.mapIndexed { i, ex ->
                "  ${i + 1}. ${ex.name} (${ex.equipment.joinToString { kb.equipment(it).name.lowercase() }.ifBlank { "sem equipamento" }})"
            }.joinToString("\n"),
            Say.me("Quer a execução de algum? Pergunte \"como faço ${found.first().name.lowercase()}\"."),
        )
    }

    private fun simulate(e: Entities, ctx: CoachContext): CoachReply {
        val currentDays = ctx.program?.sessions?.size
        val scenarios = mutableListOf(Scenario("Atual") { it })
        when {
            e.daysCount != null -> scenarios += ProgramSimulator.days(e.daysCount)
            e.minutes != null -> scenarios += ProgramSimulator.minutes(e.minutes)
            e.atHome -> kb.environments.firstOrNull { it.id.value == "home" }?.let { scenarios += ProgramSimulator.environment(it) }
            else -> listOf(3, 4, 5).filter { it != currentDays }.forEach { scenarios += ProgramSimulator.days(it) }
        }
        val sim = ProgramSimulator(kb).compare(ctx.profile, ctx.screening, scenarios)
        val header = "".padEnd(26) + sim.rows.joinToString("") { it.scenario.padStart(9) }
        val rows = kb.trackedMuscles.joinToString("\n") { m ->
            m.name.take(24).padEnd(26) + sim.rows.joinToString("") { Say.num(it.setsByMuscle[m.id] ?: 0.0).padStart(9) }
        }
        val time = "Tempo/semana".padEnd(26) + sim.rows.joinToString("") { "${it.weeklyMinutes}m".padStart(9) }
        val splits = sim.rows.joinToString("\n") { r -> "  ${r.scenario}: ${r.splitName ?: r.refusal}" +
            if (r.belowMinimum.isEmpty()) "" else " (abaixo do mínimo: ${r.belowMinimum.joinToString { kb.muscleName(it).lowercase() }})" }
        return reply(Intent.SIMULATE, Say.me("Simulei com o mesmo motor (séries semanais por músculo):"), "$header\n$rows\n$time", splits, Say.of(sim.note))
    }

    private fun missed(text: String, e: Entities, ctx: CoachContext, state: ConversationState): CoachReply {
        val program = ctx.program ?: return noProgram(Intent.MISSED)
        val day = e.day ?: return ask(Intent.MISSED, Slot.DAY, text, state, "Sem problema, a gente reorganiza.")
        val today = ctx.today.dayOfWeek
        if (program.sessionOn(day) == null) return reply(Intent.MISSED, Say.rule("${day.ptCapitalized()} não tinha treino no seu programa — nada a reorganizar."))
        if (day == today) return reply(Intent.MISSED, Say.me("Ainda é hoje! Se tiver pouco tempo, me diga quantos minutos e eu adapto a sessão."))
        if (day > today) return reply(Intent.MISSED, Say.me("Esse treino era da semana passada. A semana nova já começou: siga o programa normalmente."))
        val r = MissedWorkoutPlanner(kb).options(program, ctx.profile, day, today)
        val opts = r.options.joinToString("\n") { o ->
            "  ${o.key} — ${o.title}${if (!o.available) " (indisponível)" else ""}: ${o.description}" +
                o.details.take(4).joinToString("") { "\n       • $it" }
        }
        return reply(Intent.MISSED, Say.me(r.message + " Sem culpa — escolha como seguir:"), opts,
            quick = r.options.filter { it.available }.map { "${it.key} — ${it.title}" })
    }

    private fun plates(kg: Double): CoachReply {
        val p = PlateCalculator.compute(kg)
        return reply(Intent.PLATES, Say.rule(p.describe()))
    }

    private fun oneRm(kg: Double, reps: Int, rir: Int?): CoachReply {
        val est = OneRepMax.estimate(kg, reps, rir, kb.ruleSet.progression.params.maxRepsForE1rm)
        return reply(Intent.ONE_RM,
            Say.rule("1RM estimado para ${Say.kg(kg)} × $reps${rir?.let { " (RIR $it)" } ?: ""}: Epley ${Say.kg(est.epley)} · Brzycki ${Say.kg(est.brzycki)} · média ${Say.kg(est.average)}"),
            est.note?.let { "⚠️ $it" },
            kb.claims.firstOrNull { it.id.value == "e1rm_equations" }?.let { Say.fact(it.statement) },
        )
    }

    private fun meal(text: String): CoachReply {
        val m = MealParser(kb).parse(text)
        if (m.items.isEmpty()) return reply(Intent.MEAL,
            Say.me("Não reconheci os alimentos. Conheço: ${kb.foods.joinToString { it.aliases.firstOrNull() ?: it.name }}. O banco de alimentos cresce a cada versão."))
        val items = m.items.joinToString("\n") { "  • ${it.food.name}: ${it.grams.roundToInt()} g — ${it.kcal.roundToInt()} kcal, ${Fmt.fixed(it.proteinG ?: 0.0, 1)} g de proteína" }
        return reply(Intent.MEAL,
            Say.me("Anotei (confirme para salvar):"), items,
            Say.rule("Total: ${m.kcal.roundToInt()} kcal · proteína ${Fmt.fixed(m.proteinG, 1)} g · carboidratos ${Fmt.fixed(m.carbsG, 1)} g · gorduras ${Fmt.fixed(m.fatG, 1)} g · fibras ${Fmt.fixed(m.fiberG, 1)} g"),
            m.unmatched.takeIf { it.isNotEmpty() }?.let { Say.me("Não reconheci: ${it.joinToString()}.") },
            m.items.filter { it.grams == it.food.defaultServingG }.takeIf { it.isNotEmpty() }?.let {
                Say.me("Usei porções padrão para ${it.joinToString { i -> i.food.aliases.firstOrNull() ?: i.food.name }} — ajuste se quiser.")
            },
            Say.fact("Valores da Tabela Brasileira de Composição de Alimentos (TACO, NEPA-UNICAMP)."),
            actions = listOf(CoachAction.LogMeal(text, m.kcal.roundToInt(), m.proteinG.roundToInt())),
        )
    }

    private fun water(e: Entities, ctx: CoachContext): CoachReply {
        val session = ctx.program?.sessionOn(ctx.today.dayOfWeek)
        val engine = HydrationEngine(kb.ruleSet.hydration)
        val target = engine.target(HydrationInput(ctx.profile.weightKg, session?.estimatedMinutes ?: 0))
        val logs = ctx.water + listOfNotNull(e.ml?.let { WaterLog(ctx.today, it) })
        val progress = engine.progress(target, logs, ctx.today)
        return reply(Intent.WATER,
            e.ml?.let { Say.me("Anotado: +${Fmt.int(it)} ml.") },
            Say.rule(progress.display + " (${progress.pct}%)"),
            Say.of(target.explanation),
            actions = listOfNotNull(e.ml?.let { CoachAction.LogWater(it) }),
            quick = listOf("+250 ml", "+500 ml"),
        )
    }

    private fun nutrition(ctx: CoachContext): CoachReply {
        val t = EnergyEstimator(kb.ruleSet.nutrition).targets(ctx.profile)
        return reply(Intent.NUTRITION,
            Say.rule("Meta estimada (${t.energyGoal.label.lowercase()}): ~${Fmt.int(t.targetKcal)} kcal/dia · proteína ${t.proteinG.first}–${t.proteinG.last} g/dia"),
            t.explanations.joinToString("\n") { Say.of(it) },
            kb.claims.firstOrNull { it.id.value == "dietary_guidelines_br" }?.let { Say.fact(it.statement) },
            Say.me("São estimativas; para um plano individual, procure um(a) nutricionista."),
        )
    }

    private fun supplement(e: Entities): CoachReply {
        val s = e.supplement?.let { id -> kb.supplements.first { it.id == id } }
            ?: return reply(Intent.SUPPLEMENT, Say.me("Tenho informações sobre: ${kb.supplements.joinToString { it.name }}. Sobre qual quer saber?"),
                quick = kb.supplements.map { it.name.substringBefore(" (") })
        return reply(Intent.SUPPLEMENT,
            Say.fact("${s.name} — ${s.evidenceLevel.label}"),
            "  O que é: ${s.whatIs}", "  Para que: ${s.purpose}", "  Evidência: ${s.evidenceSummary}",
            "  Efeitos conhecidos: ${s.knownEffects}", "  Limitações: ${s.limitations}", "  Cautelas: ${s.cautions}",
            s.sourceIds.joinToString("\n") { "   ✔ ${kb.source(it).citation().take(150)}" },
        )
    }

    private fun spotReduction(ctx: CoachContext): CoachReply {
        val claim = kb.claims.firstOrNull { it.id.value == "spot_reduction" }
        val deficit = EnergyEstimator(kb.ruleSet.nutrition).targets(ctx.profile, EnergyGoal.DEFICIT)
        return reply(Intent.SPOT_REDUCTION,
            Say.me("\"Perder barriga\" é um objetivo totalmente válido — só não dá para escolher de onde a gordura sai fazendo abdominal."),
            claim?.let { Say.claim(kb, it) },
            Say.rule("O que reduz a cintura no seu plano: déficit calórico moderado (~${Fmt.int(deficit.targetKcal)} kcal/dia estimadas), " +
                "proteína ${deficit.proteinG.first}–${deficit.proteinG.last} g/dia, seu treino resistido, 150–300 min/semana de atividade aeróbica moderada e sono ≥ 7 h."),
            Say.me("Acompanhe pela cintura e pela média de peso semanal, não pela balança de um dia."),
        )
    }

    private fun evidenceAnswer(text: String): CoachReply {
        val hits = evidence.search(text, limit = 2)
        if (hits.isEmpty()) return reply(Intent.EVIDENCE,
            Say.me("Não tenho isso no banco de evidências ainda — e prefiro não inventar. Posso falar sobre volume, frequência, falha, descanso, " +
                "RIR, semana de descarga, proteína, sono, hidratação, gordura localizada e suplementos."))
        return reply(Intent.EVIDENCE, Say.me("O que o banco de evidências diz:"), hits.joinToString("\n") { Say.claim(kb, it.claim) })
    }

    private fun body(ctx: CoachContext): CoachReply {
        val bm = BodyMetrics(kb.ruleSet.bodyMetrics)
        val waist = ctx.measurements.sortedBy { it.date }.lastOrNull { it.waistCm != null }?.waistCm
        val weight = ctx.weights.maxByOrNull { it.date }?.kg ?: ctx.profile.weightKg
        return reply(Intent.BODY,
            Say.of(bm.bmi(weight, ctx.profile.heightCm).explanation),
            waist?.let { Say.of(bm.waistToHeight(it, ctx.profile.heightCm).explanation) }
                ?: Say.me("Registre sua cintura (fita na altura do umbigo) para eu calcular a relação cintura/altura."),
        )
    }

    private fun weightChange(ctx: CoachContext): CoachReply {
        val glycogen = kb.claims.firstOrNull { it.id.value == "glycogen_water" }
        if (ctx.weights.size < 2) return reply(Intent.WEIGHT_CHANGE,
            Say.me("Oscilações de um dia para o outro são normais e quase nunca são gordura."), glycogen?.let { Say.claim(kb, it) },
            Say.me("Registre o peso diariamente (mesmo horário, em jejum) e eu mostro a média móvel de 7 dias."))
        val r = WeightTrend(kb.ruleSet.weightTrend).analyze(ctx.weights)
        return reply(Intent.WEIGHT_CHANGE, r.messages.joinToString("\n") { Say.of(it) }.ifBlank { Say.rule("Sem variação relevante nas últimas pesagens.") },
            glycogen?.let { Say.claim(kb, it, maxSources = 1) })
    }

    private fun volume(ctx: CoachContext): CoachReply {
        val program = ctx.program ?: return noProgram(Intent.VOLUME)
        val monday = ctx.today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val logs = ctx.history.filter { !it.date.isBefore(monday) && !it.date.isAfter(ctx.today) }
        val status = VolumeDashboard(kb).week(program, logs, ctx.today.dayOfWeek)
        val lines = status.joinToString("\n") { s ->
            "  ${s.muscle.name.take(24).padEnd(25)} ${s.bar} ${Say.num(s.doneSets).padStart(4)}/${Say.num(s.plannedSets)}"
        }
        val messages = status.mapNotNull { it.message }.joinToString("\n") { Say.of(it) }
        return reply(Intent.VOLUME, Say.rule("📊 Volume da semana (feito/planejado, séries):"), lines, messages.ifBlank { Say.me("Tudo dentro do esperado até agora.") })
    }

    private fun greeting(ctx: CoachContext): CoachReply {
        val first = ctx.profile.name.substringBefore(' ')
        val (s, _) = sessionFor(null, ctx)
        val todayLine = when {
            s == null -> null
            s.day == ctx.today.dayOfWeek -> "Hoje é ${s.name} (~${s.estimatedMinutes} min)."
            else -> "Hoje é dia livre; o próximo treino é ${s.name} na ${s.day?.pt()}."
        }
        return reply(Intent.GREETING, Say.me(listOfNotNull("Oi, $first!", todayLine, "Como posso ajudar?").joinToString(" ")),
            quick = listOf("Qual meu treino hoje?", "Tenho só 40 minutos", "Hoje estou cansado"))
    }

    private fun help(ctx: CoachContext): CoachReply = reply(Intent.HELP,
        Say.me("Sou a IA local do FitKingIA: funciono offline, no seu aparelho, e sempre uso o motor de regras e o banco de evidências. Exemplos:"),
        listOf(
            "\"Qual meu treino hoje?\"", "\"Tenho só 35 minutos\"", "\"Posso trocar o agachamento?\"", "\"Meu joelho está doendo\"",
            "\"Hoje estou cansado, dormi mal\"", "\"Por que faço supino 3x?\"", "\"Como faço stiff?\"", "\"Quanto peso coloco no supino?\"",
            "\"Exercício de glúteo em casa\"", "\"E se eu treinar 5 dias?\"", "\"Faltei o treino de quarta\"", "\"Quero colocar 82,5 kg na barra\"",
            "\"Comi arroz, feijão e frango\"", "\"Creatina funciona?\"", "\"Treinar até a falha é melhor?\"", "\"Como perder barriga?\"",
        ).joinToString("\n") { "  • $it" },
    )

    private fun fallback(ctx: CoachContext, conf: Double): CoachReply =
        help(ctx).copy(text = Say.me("Não entendi bem. ") + "\n" + help(ctx).text, intent = null, confidence = conf)

    companion object {
        /** Abaixo disso, pergunta entre as duas intenções mais prováveis. */
        const val CLARIFY_BELOW = 0.42
        /** Abaixo disso (e sem segunda opção plausível), mostra o que sabe fazer. */
        const val FALLBACK_BELOW = 0.25
    }
}
