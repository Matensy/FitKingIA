package com.fitkingia.core.program

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.explain.Provenance
import com.fitkingia.core.knowledge.*
import com.fitkingia.core.model.*
import com.fitkingia.core.safety.ScreeningResult
import com.fitkingia.core.safety.ScreeningStatus
import com.fitkingia.core.session.SessionFitter

/**
 * Motor de prescrição. Pipeline determinístico (mesma entrada → mesmo programa):
 *  1. dias de treino (disponibilidade, limite por nível, espaçamento)
 *  2. divisão (template do banco por nº de dias, nível e foco; preferência do usuário)
 *  3. exercícios por slot (equipamento, nível, dor relatada, variedade, preferências)
 *  4. distribuição na semana (sobreposição muscular, esportes, tempo do dia)
 *  5. ajuste ao tempo de cada dia
 *  6. balanceamento de volume semanal por músculo
 *  7. avisos (músculos abaixo da meta e o motivo)
 * Nenhuma etapa usa LLM.
 */
class ProgramGenerator(private val kb: KnowledgeBase) {
    private val rules = kb.ruleSet
    private val selector = ExerciseSelector(kb)
    private val scheduler = WeekScheduler(kb)
    private val fitter = SessionFitter(kb)
    private val planner = VolumePlanner(kb, selector)
    private val clock = SessionClock(rules.timing.params)
    private val calc = VolumeCalculator(rules.volume.params)

    fun generate(profile: UserProfile, screening: ScreeningResult): ProgramResult {
        if (!screening.allowsProgram) {
            return ProgramResult.Refused(screening.messages.ifEmpty {
                listOf(Explanation.rule("Complete a triagem de segurança antes de gerar o programa."))
            })
        }
        val explanations = mutableListOf<Explanation>()
        val warnings = if (screening.status == ScreeningStatus.CAUTION) screening.messages.toMutableList() else mutableListOf()
        val focus = profile.focus
        val tier = profile.tier
        val constraints = UserConstraints.of(profile)

        // 1. Dias
        val freq = rules.frequency
        val usable = profile.availability.filter { it.minutes >= freq.params.minSessionMinutes }
        if (usable.isEmpty()) {
            return ProgramResult.Refused(listOf(Explanation.rule(
                "Nenhum dia disponível com pelo menos ${freq.params.minSessionMinutes} minutos. " +
                    "Marque ao menos um dia com esse tempo.", freq.id,
            )))
        }
        val tierMax = freq.params.maxDaysByTier[tier] ?: 6
        val maxTemplateDays = kb.splits.maxOf { it.daysPerWeek }
        val maxDays = minOf(profile.maxTrainingDays ?: 7, tierMax, maxTemplateDays)
        val days = scheduler.pickDays(usable, maxDays)
        explanations += Explanation.rule(
            "Dias de treino: ${days.sortedBy { it.day }.joinToString { it.day.pt() }} " +
                "(${days.size} de ${usable.size} dias com tempo suficiente; limite para ${tier.label.lowercase()}: $tierMax)." +
                if (usable.size > days.size) " Os dias foram escolhidos para evitar treinos em dias consecutivos." else "",
            freq.id,
        )

        // 2. Divisão
        val split = chooseSplit(profile, days.size, warnings)
            ?: return ProgramResult.Refused(listOf(Explanation.rule("Nenhuma divisão cadastrada para ${days.size} dias.")))
        explanations += Explanation.rule("Divisão ${split.name}: ${split.rationale}", SPLIT_RULE)

        // 3. Exercícios por slot
        val prescriptionFor = { role: SlotRole, ex: Exercise -> prescription(focus, role, tier, ex) }
        val usedInWeek = mutableMapOf<ExerciseId, Int>()
        val baseSessions = split.sessions.map { tpl ->
            val used = mutableSetOf<ExerciseId>()
            val items = tpl.slots.mapNotNull { slot ->
                val ex = selector.select(slot, constraints, used, usedInWeek)
                if (ex == null) {
                    warnings += Explanation.rule(
                        "${tpl.name}: nenhum exercício de ${kb.patternName(slot.pattern).lowercase()}" +
                            (slot.targetMuscle?.let { " para ${kb.muscleName(it).lowercase()}" } ?: "") +
                            " compatível com seu equipamento, nível e restrições — slot ignorado.",
                        SELECTION_RULE,
                    )
                    null
                } else {
                    used += ex.id
                    usedInWeek.merge(ex.id, 1, Int::plus)
                    PlannedExercise(ex, slot.role, slot.baseSets, prescriptionFor(slot.role, ex), slot = slot)
                }
            }
            PlannedSession(tpl.key, tpl.name, items)
        }

        // 4. Distribuição na semana
        val assignment = scheduler.assign(baseSessions, days, profile.sports)
        if (profile.sports.isNotEmpty()) {
            val sportsTxt = profile.sports.joinToString { "${kb.sport(it.sportId).name} (${it.day.pt()})" }
            explanations += Explanation.rule(
                if (assignment.cost < assignment.canonicalCost - 1e-9)
                    "As sessões foram reordenadas para afastar a maior demanda muscular dos dias de esporte: $sportsTxt."
                else "A ordem das sessões já respeita seus dias de esporte: $sportsTxt.",
                rules.scheduling.id,
            )
        }
        for (conflict in scheduler.sportConflicts(baseSessions, assignment.dayForSession.map { it.day }, profile.sports)) {
            warnings += Explanation.rule(conflict, rules.scheduling.id)
        }
        val budgets = baseSessions.indices.map { assignment.dayForSession[it].minutes }

        // 5. Tempo de cada dia
        val fitted = baseSessions.mapIndexed { i, s -> fitter.fit(s, budgets[i]) }
        fitted.forEachIndexed { i, f ->
            if (f.changes.isNotEmpty()) explanations += Explanation.rule(
                "${baseSessions[i].name} (${assignment.dayForSession[i].day.pt()}, ${budgets[i]} min): " +
                    f.changes.joinToString("; ") { it.description }, rules.timing.id,
            )
        }

        // 6. Volume semanal
        val volumeTarget = rules.volume.params.target(focus, tier)
        val targets = kb.trackedMuscles.associate { it.id to volumeTarget.scaled(it.volumeFactor) }
        val balanced = planner.balance(fitted.map { it.session.exercises }, budgets, targets, constraints, prescriptionFor)
        balanced.notes.forEach { explanations += Explanation.rule(it, rules.volume.id) }

        val sessions = baseSessions.indices.map { i ->
            val items = balanced.sessions[i]
            baseSessions[i].copy(
                exercises = items,
                day = assignment.dayForSession[i].day,
                budgetMinutes = budgets[i],
                estimatedMinutes = clock.estimateMinutes(items),
            )
        }.sortedBy { it.day }

        val weekly = calc.weekly(sessions)
        explanations += Explanation.rule(
            "Meta semanal por músculo (${focus.label.lowercase()}, ${tier.label.lowercase()}): " +
                "${fmt(volumeTarget.min)}–${fmt(volumeTarget.max)} séries, alvo ${fmt(volumeTarget.target)}, " +
                "com contagem fracionada (série direta = ${fmt(rules.volume.params.primaryCredit)}, indireta = ${fmt(rules.volume.params.secondaryCredit)}).",
            rules.volume.id,
        )
        val main = prescription(focus, SlotRole.MAIN, tier)
        explanations += Explanation.rule(
            "Prescrição para ${focus.label.lowercase()}: exercícios principais ${main.reps.first}–${main.reps.last} repetições, " +
                "RIR ${main.rir}, descanso ${fmt(main.restSeconds.first / 60.0)}–${fmt(main.restSeconds.last / 60.0)} min" +
                if (tier == TrainingTier.NOVICE) " (iniciantes ficam mais longe da falha enquanto aprendem a técnica)." else ".",
            rules.prescription.id,
        )

        // 7. Avisos de volume
        for ((m, t) in targets) {
            val v = weekly[m] ?: 0.0
            if (v > t.max + 1e-9) {
                // Só acontece quando reduzir mais deixaria músculos co-treinados abaixo do mínimo.
                val partners = sessions.flatMap { it.exercises }.filter { m in it.exercise.primaryMuscles }
                    .flatMap { it.exercise.primaryMuscles }.filter { it != m && it in targets }.distinct()
                warnings += Explanation.rule(
                    "Volume de ${kb.muscleName(m).lowercase()} planejado em ${fmt(v)} séries/semana, acima do teto de ${fmt(t.max)} — " +
                        "é trabalhado junto com ${partners.joinToString { kb.muscleName(it).lowercase() }}, e reduzir mais " +
                        "deixaria esses músculos abaixo do mínimo.",
                    rules.volume.id,
                )
            }
            if (v + 1e-9 < t.min) {
                val direct = sessions.any { s -> s.exercises.any { m in it.exercise.primaryMuscles } }
                val why = if (direct) "o tempo disponível limita o volume" else "nenhum exercício direto compatível com seu equipamento/restrições"
                warnings += Explanation.rule(
                    "Volume de ${kb.muscleName(m).lowercase()} planejado em ${fmt(v)} séries/semana, abaixo do mínimo de ${fmt(t.min)} — $why.",
                    rules.volume.id,
                )
            }
        }

        if (profile.primaryGoal.spotReductionNotice || profile.secondaryGoal?.spotReductionNotice == true) {
            explanations += spotReductionNotice()
        }

        return ProgramResult.Generated(
            Program(split, focus, tier, sessions, weekly, targets, explanations, warnings)
        )
    }

    fun prescription(focus: TrainingFocus, role: SlotRole, tier: TrainingTier, exercise: Exercise? = null): RepPrescription {
        val p = rules.prescription.params
        var base = p.forRole(focus, role)
        if (tier == TrainingTier.NOVICE) base = base.copy(rir = base.rir + p.noviceExtraRir)
        if (exercise?.timed == true) base = base.copy(holdSeconds = p.timedHoldSeconds)
        return base
    }

    private fun chooseSplit(profile: UserProfile, nDays: Int, warnings: MutableList<Explanation>): SplitTemplate? {
        profile.preferredSplit?.let { pref ->
            val s = kb.splits.firstOrNull { it.id == pref }
            when {
                s == null -> warnings += Explanation.rule("Divisão preferida \"$pref\" não existe; usando a escolha do motor.", SPLIT_RULE)
                s.daysPerWeek != nDays -> warnings += Explanation.rule(
                    "Divisão preferida ${s.name} pede ${s.daysPerWeek} dias, mas há $nDays dias de treino; usando a escolha do motor.", SPLIT_RULE,
                )
                else -> return s
            }
        }
        val forDays = kb.splits.filter { it.daysPerWeek == nDays }
        return forDays.filter { it.suits(profile.tier, profile.focus) }
            .sortedWith(compareByDescending<SplitTemplate> { it.priority }.thenBy { it.id.value })
            .firstOrNull()
            ?: forDays.sortedByDescending { it.priority }.firstOrNull()
    }

    private fun spotReductionNotice(): Explanation {
        val claim = kb.claims.firstOrNull { it.id == SPOT_REDUCTION_CLAIM }
        val text = buildString {
            append("\"Perder barriga\" é um objetivo válido de interface, mas o app não consegue direcionar a perda de gordura ")
            append("para uma região com exercícios abdominais específicos. Redução de cintura vem do conjunto: balanço energético, ")
            append("treino resistido, atividade física e sono.")
            if (claim?.isConflicting == true) append(" Há estudos isolados com resultado diferente — a evidência está marcada como conflitante e os estudos são exibidos.")
        }
        return Explanation(Provenance.FACT, text, claimIds = listOfNotNull(claim?.id))
    }

    companion object {
        val SPLIT_RULE = RuleId("split.selection")
        val SELECTION_RULE = RuleId("selection.scoring")
        val SPOT_REDUCTION_CLAIM = ClaimId("spot_reduction")
        /** Número em pt-BR (vírgula decimal, sem casas quando inteiro). */
        fun fmt(v: Double): String = Fmt.num(v)
    }
}
