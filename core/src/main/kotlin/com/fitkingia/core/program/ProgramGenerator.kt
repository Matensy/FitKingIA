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
    private val alignment = GoalAlignment(kb)
    private val volumeTargets = VolumeTargets(kb)

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
        val priorityMuscles = kb.trackedMuscles.filter { it.focusRegion != null && it.focusRegion in profile.priorities }.map { it.id }.toSet()
        val constraints = UserConstraints.of(profile).copy(priorityMuscles = priorityMuscles)

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
        var days = scheduler.pickDays(usable, maxDays)
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
        var baseSessions = split.sessions.map { tpl ->
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

        // Sessão sem nenhum exercício compatível (ex.: "puxar" sem nenhum equipamento) não vai para a semana.
        val empty = baseSessions.filter { it.exercises.isEmpty() }
        if (empty.isNotEmpty()) {
            empty.forEach {
                warnings += Explanation.rule(
                    "${it.name}: nenhum exercício compatível com seu equipamento e restrições — sessão retirada da semana e o dia fica livre. " +
                        "Adicionar equipamento (ex.: elástico ou barra fixa) permite incluí-la.", SELECTION_RULE,
                )
            }
            baseSessions = baseSessions - empty.toSet()
            if (baseSessions.isEmpty()) return ProgramResult.Refused(listOf(Explanation.rule(
                "Nenhum exercício compatível com seu equipamento e restrições.", SELECTION_RULE,
            )))
            days = scheduler.pickDays(days, baseSessions.size)
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
        val fitted = baseSessions.mapIndexed { i, s -> fitter.fit(s, budgets[i], protect = priorityMuscles) }
        fitted.forEachIndexed { i, f ->
            if (f.changes.isNotEmpty()) explanations += Explanation.rule(
                "${baseSessions[i].name} (${assignment.dayForSession[i].day.pt()}, ${budgets[i]} min): " +
                    f.changes.joinToString("; ") { it.description }, rules.timing.id,
            )
        }

        // 6. Volume semanal (músculos prioritários com meta maior)
        val volumeTarget = rules.volume.params.target(focus, tier)
        val targets = volumeTargets.forProgram(focus, tier, profile.priorities)
        val balanced = planner.balance(fitted.map { it.session.exercises }, budgets, targets, constraints, prescriptionFor, preferFocus = priorityMuscles)
        balanced.notes.forEach { explanations += Explanation.rule(it, rules.volume.id) }

        // 6b. Região priorizada em mais sessões da semana (ex.: glúteos 3×), só com o tempo que sobrou;
        // depois, o tempo que ainda restar volta para quem está abaixo da meta (prioridade primeiro).
        var finalSessions = balanced.sessions
        if (profile.priorities.isNotEmpty()) {
            val withFrequency = balanced.sessions.map { it.toMutableList() }
            for (region in profile.priorities.sortedBy { it.ordinal }) {
                for (note in ensureFrequency(withFrequency, budgets, region, targets, constraints, prescriptionFor)) explanations += Explanation.rule(note, rules.priority.id)
            }
            val again = planner.balance(withFrequency, budgets, targets, constraints, prescriptionFor, preferFocus = priorityMuscles)
            again.notes.filter { n -> explanations.none { it.text == n } }.forEach { explanations += Explanation.rule(it, rules.volume.id) }
            finalSessions = again.sessions
        }

        val sessions = baseSessions.indices.map { i ->
            val items = prioritizeOrder(finalSessions[i], priorityMuscles)
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

        // 7. Avisos de volume. Com prioridade, músculos de fora dela que ficaram um pouco abaixo da
        // manutenção viram um aviso só (é o preço esperado da prioridade, não um problema por músculo).
        val priorityRegionMuscles = kb.musclesOf(profile.priorities)
        val belowMaintenance = linkedMapOf<String, MutableList<String>>()
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
                val possible = kb.exercises.any { m in it.primaryMuscles && Eligibility.isAllowed(it, constraints) }
                val why = when {
                    direct -> "o tempo disponível limita o volume"
                    possible -> "não coube um exercício direto no tempo das sessões"
                    else -> "nenhum exercício direto compatível com seu equipamento/restrições"
                }
                if (profile.priorities.isNotEmpty() && m !in priorityRegionMuscles) {
                    belowMaintenance.getOrPut(why) { mutableListOf() } += "${kb.muscleName(m).lowercase()} ${fmt(v)} de ${fmt(t.min)}"
                    continue
                }
                warnings += Explanation.rule(
                    "Volume de ${kb.muscleName(m).lowercase()} planejado em ${fmt(v)} séries/semana, abaixo do mínimo de ${fmt(t.min)} — $why.",
                    rules.volume.id,
                )
            }
        }
        // Fora da prioridade: um aviso por motivo, sem culpar a prioridade pelo que já faltaria sem ela.
        for ((why, items) in belowMaintenance) {
            warnings += Explanation.rule(
                "Fora da prioridade, abaixo do mínimo de manutenção (séries/semana planejadas de mínimo): ${items.joinToString()} — $why.",
                rules.volume.id,
            )
        }

        if (profile.primaryGoal.spotReductionNotice || profile.secondaryGoal?.spotReductionNotice == true) {
            explanations += spotReductionNotice()
        }

        val draft = Program(split, focus, tier, sessions, weekly, targets, explanations, warnings, profile.priorities)
        // 8. Seu objetivo × seu treino: confere o que foi gerado e mostra em números (vem primeiro).
        val report = alignment.check(draft, profile)
        return ProgramResult.Generated(
            draft.copy(goalCheck = report.warnings + report.lines)
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
        // Com região priorizada, prefere o modelo cuja ênfase cobre a prioridade (ex.: inferiores 3× para glúteos);
        // sem prioridade, prefere os modelos equilibrados (sem ênfase).
        // Pontos por região pedida que o modelo enfatiza, menos 1 por ênfase que ninguém pediu (pernas (coxas) não
        // cai no modelo de glúteos) e menos 3 se a ênfase não tem nada a ver com o pedido.
        fun fit(s: SplitTemplate): Int {
            if (profile.priorities.isEmpty()) return if (s.emphasis.isEmpty()) 1 else -1
            val hit = (s.emphasis intersect profile.priorities).size
            return hit * 2 - (s.emphasis - profile.priorities).size - (if (s.emphasis.isNotEmpty() && hit == 0) 3 else 0)
        }
        return forDays.filter { it.suits(profile.tier, profile.focus) }
            .sortedWith(compareByDescending<SplitTemplate> { fit(it) }.thenByDescending { it.priority }.thenBy { it.id.value })
            .firstOrNull()
            ?: forDays.filter { it.emphasis.isEmpty() }.sortedByDescending { it.priority }.firstOrNull()
            ?: forDays.sortedByDescending { it.priority }.firstOrNull()
    }

    /**
     * Garante trabalho direto na região priorizada em mais sessões (até a frequência mínima da regra):
     * um acessório por sessão que ainda não treina a região, para o músculo da região mais longe da meta,
     * só onde cabe no tempo do dia e sem passar do teto semanal de nenhum músculo.
     */
    private fun ensureFrequency(
        sessions: List<MutableList<PlannedExercise>>, budgets: List<Int>, region: BodyRegion, targets: Map<MuscleId, VolumeTarget>,
        constraints: UserConstraints, prescriptionFor: (SlotRole, Exercise) -> RepPrescription,
    ): List<String> {
        val muscles = kb.trackedMuscles.filter { it.focusRegion == region && it.fillPattern != null }
        if (muscles.isEmpty()) return emptyList()
        val ids = muscles.map { it.id }.toSet()
        fun direct(list: List<PlannedExercise>) = list.any { e -> e.exercise.primaryMuscles.any { it in ids } }
        val need = rules.priority.params.minFrequency(region, sessions.size)
        val minSets = rules.volume.params.minSetsPerExercise
        val notes = mutableListOf<String>()
        var have = sessions.count { direct(it) }
        val order = sessions.indices.filter { !direct(sessions[it]) }.sortedByDescending { budgets[it] - clock.estimateMinutes(sessions[it]) }
        for (si in order) {
            if (have >= need) break
            val weekly = calc.ofExercises(sessions.flatten())
            // Músculo da região mais longe da meta primeiro.
            val byDeficit = muscles.sortedByDescending { m -> targets[m.id]?.let { t -> (t.target - (weekly[m.id] ?: 0.0)) / t.target } ?: 0.0 }
            for (m in byDeficit) {
                val slot = Slot(m.fillPattern!!, SlotRole.ACCESSORY, minSets, targetMuscle = m.id)
                val usedInWeek = sessions.flatten().groupingBy { it.exercise.id }.eachCount()
                val ex = selector.select(slot, constraints, sessions[si].map { it.exercise.id }.toSet(), usedInWeek) ?: continue
                val planned = PlannedExercise(ex, SlotRole.ACCESSORY, minSets, prescriptionFor(SlotRole.ACCESSORY, ex), slot = slot)
                if (clock.estimateMinutes(sessions[si] + planned) > budgets[si]) continue
                val overshoots = (ex.primaryMuscles + ex.secondaryMuscles).any { o ->
                    targets[o]?.let { t -> (weekly[o] ?: 0.0) + minSets * calc.credit(planned, o) > t.max + 1e-9 } == true
                }
                if (overshoots) continue
                sessions[si].add(planned)
                have++
                notes += "Prioridade ${region.label.lowercase()}: ${ex.name} incluído em mais um treino da semana (${have}×/semana)."
                break
            }
        }
        return notes
    }

    /**
     * Ordem dentro da hierarquia do treino: os compostos (principal/secundário) da região priorizada abrem a
     * sessão; os acessórios da prioridade abrem o bloco de acessórios. Assim ninguém começa o dia de perna
     * com rosca, e quem prioriza glúteos começa pela elevação pélvica ou pelo agachamento.
     */
    private fun prioritizeOrder(items: List<PlannedExercise>, priority: Set<MuscleId>): List<PlannedExercise> {
        if (priority.isEmpty()) return items
        // Foco, não só músculo principal: para glúteos, a elevação pélvica abre o dia antes do agachamento.
        fun hits(e: PlannedExercise) = e.exercise.focus.any { it in priority }
        // Duas regiões priorizadas no mesmo dia: intercala (1º composto de cada região antes do 2º de qualquer uma),
        // para as duas abrirem o treino.
        fun region(e: PlannedExercise) = e.exercise.focus.firstOrNull { it in priority }?.let { kb.muscle(it).focusRegion }
        val rankInRegion = mutableMapOf<Int, Int>()
        val seen = mutableMapOf<BodyRegion?, Int>()
        items.withIndex().filter { it.value.role != SlotRole.ACCESSORY && hits(it.value) }.forEach { (i, e) ->
            val r = region(e)
            rankInRegion[i] = seen.getOrDefault(r, 0)
            seen[r] = (seen[r] ?: 0) + 1
        }
        return items.withIndex().sortedWith(compareBy<IndexedValue<PlannedExercise>> { (_, e) ->
            when {
                e.role != SlotRole.ACCESSORY && hits(e) -> 0
                e.role != SlotRole.ACCESSORY -> 1
                hits(e) -> 2
                else -> 3
            }
        }.thenBy { rankInRegion[it.index] ?: 0 }.thenBy { it.index }).map { it.value }
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
