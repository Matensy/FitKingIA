package com.fitkingia.app.screens

import android.widget.LinearLayout
import com.fitkingia.app.Screen
import com.fitkingia.app.Tab
import com.fitkingia.app.data.Graph
import com.fitkingia.app.ui.*
import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.Food
import com.fitkingia.core.knowledge.Stance
import com.fitkingia.core.knowledge.VerificationStatus
import com.fitkingia.core.model.*
import com.fitkingia.core.planning.ProgramSimulator
import com.fitkingia.core.planning.Scenario
import com.fitkingia.core.planning.Simulation
import com.fitkingia.core.program.ProgramResult
import com.fitkingia.core.program.pt
import com.fitkingia.core.tools.WarmupGenerator
import java.io.File

private fun Screen.fact(id: String): Explanation =
    fit.kb.claim(ClaimId(id)).let { Explanation.fact(it.statement, listOf(it.id)) }

/** Fato clicável: abre as fontes. */
private fun Screen.factCard(root: LinearLayout, id: String) {
    root.card(onClick = { push(ClaimScreen(ClaimId(id))) }) { explanation(fact(id), 13f); muted("Toque para ver as fontes", 12f) }
}

class MoreScreen : Screen() {
    override val title = "Mais"
    override val tab = Tab.MORE

    override fun build(root: LinearLayout) {
        fun item(icon: String, name: String, desc: String, open: () -> Screen) =
            root.card(bottom = 8, onClick = { push(open()) }) { text("$icon  $name", 16f, bold = true, bottom = 2); muted(desc) }
        root.label("Rotina")
        item("🍽️", "Nutrição", "Metas de energia e proteína, registrar refeições por toque") { NutritionScreen() }
        item("💧", "Água", "Meta diária e histórico") { WaterScreen() }
        item("😴", "Sono", "Registro e média da semana") { SleepScreen() }
        item("🚴", "Cardio", "Caminhada, corrida, bike… e minutos da semana") { CardioScreen() }
        item("🧘", "Mobilidade", "Registrar sessões por região") { MobilityScreen() }
        item("🔔", "Lembretes", "Água, treino do dia e sequência, no seu horário") { NotificationsScreen() }
        root.label("Ferramentas")
        item("🧮", "Anilhas, 1RM e aquecimento", "Calculadoras rápidas") { ToolsScreen() }
        item("🧪", "Simulador \"e se?\"", "Compare 3×5 dias, 45×60 min, casa × academia") { SimulatorScreen() }
        root.label("Conhecimento")
        item("💊", "Suplementos", "Informação baseada em evidência (não é prescrição)") { SupplementsScreen() }
        item("🔬", "Evidências", "Fontes, afirmações e regras usadas pelo motor") { EvidenceScreen() }
        root.label("Você")
        item("👤", "Perfil e dados", "Refazer questionário, dores, apagar dados") { ProfileScreen() }
        item("ℹ️", "Sobre o FitKingIA", "Como funciona, avisos e privacidade") { AboutScreen() }
    }
}

class NutritionScreen : Screen() {
    override val title = "Nutrição"
    private var meal = "Almoço"
    private var food: Food? = null

    override fun build(root: LinearLayout) {
        val n = fit.nutrition() ?: return
        val t = n.targets
        root.card {
            label("Meta estimada · ${t.energyGoal.label}")
            kv("Energia", "${Fmt.int(n.kcal.toInt())} / ${Fmt.int(t.targetKcal)} kcal")
            bar(n.kcal / t.targetKcal, if (n.kcal > t.targetKcal * 1.05) C.warning else C.accent)
            kv("Proteína", "${Fmt.num(n.proteinG, 0)} g (meta ${t.proteinG.first}–${t.proteinG.last} g)")
            bar(n.proteinG / t.proteinTargetG, C.success)
            kv("Carboidratos · Gorduras · Fibras", "${Fmt.num(n.carbsG, 0)} · ${Fmt.num(n.fatG, 0)} · ${Fmt.num(n.fiberG, 0)} g")
        }
        root.h2("Adicionar")
        root.chips(listOf("Café da manhã", "Almoço", "Lanche", "Jantar", "Ceia").map { it to it }, { it == meal }, small = true) { meal = it; refresh() }
        root.chips(fit.kb.foods.map { it to it.name.substringBefore(",") }, { it == food }) { food = it; refresh() }
        food?.let { f ->
            root.card(stroke = C.accent) {
                h3(f.name)
                muted("1 porção = ${f.servingLabel} (${Fmt.num(f.defaultServingG, 0)} g) · ${Fmt.num(f.kcal * f.defaultServingG / 100, 0)} kcal")
                label("Quantas porções?")
                chips(listOf(0.5 to "½", 1.0 to "1", 1.5 to "1½", 2.0 to "2", 3.0 to "3"), { false }) { portions ->
                    fit.logFood(f, portions, meal)
                    main.toast("${f.name.substringBefore(",")} adicionado")
                    food = null
                    refresh()
                }
            }
        }
        if (n.meals.isNotEmpty()) {
            root.h2("Hoje")
            n.meals.forEach { m ->
                root.card(bottom = 6) {
                    row(bottom = 0) {
                        val names = m.items.joinToString { i -> (fit.kb.foods.firstOrNull { it.id == i.foodId }?.name?.substringBefore(",") ?: i.foodId.value) + " ${Fmt.num(i.grams, 0)} g" }
                        val tv = text("${m.label ?: ""} · ${Dates.time(m.at)}\n$names · ${Fmt.num(m.items.sumOf { it.kcal }, 0)} kcal", 14f, bottom = 0)
                        tv.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text("✕", 18f, C.muted, bottom = 0).setOnClickListener { fit.deleteMeal(m.id); refresh() }
                    }
                }
            }
        }
        root.h2("Por que estes números?")
        root.card { t.explanations.forEach { explanation(it, 13f) } }
        factCard(root, "dietary_guidelines_br")
        root.muted("Valores por 100 g da Tabela TACO (UNICAMP). Porções são estimativas. O banco de alimentos ainda é pequeno e cresce com valores verificados.", 12f)
    }
}

class WaterScreen : Screen() {
    override val title = "Água"

    override fun build(root: LinearLayout) {
        val w = fit.water() ?: return
        root.text("${Fmt.int(w.progress.consumedMl)} / ${Fmt.int(w.target.totalMl)} ml", 26f, bold = true)
        root.bar(w.progress.pct / 100.0, if (w.progress.pct >= 100) C.success else C.fact, height = 12, bottom = 12)
        root.buttonRow(
            Triple("+250", Btn.SECONDARY) { fit.addWater(250); refresh() },
            Triple("+500", Btn.SECONDARY) { fit.addWater(500); refresh() },
            Triple("+750", Btn.SECONDARY) { fit.addWater(750); refresh() },
            Triple("Desfazer", Btn.GHOST) { fit.undoWater(); refresh() },
        )
        root.h2("Últimos 7 dias")
        root.add(BarChart(root.context, w.last7.map { Dates.dayShort(it.first) }, w.last7.map { it.second.toDouble() }, w.target.totalMl.toDouble()), bottom = 12)
        root.h2("Sua meta")
        root.card { explanation(w.target.explanation, 13f) }
        root.label("Quanto você sua treinando?")
        root.chips(SweatLevel.values().map { it to it.label }, { it == fit.sweat() }, small = true) { fit.setHydrationPrefs(it, fit.hotClimate()); refresh() }
        root.label("Treina em lugar quente?")
        root.chips(listOf(false to "Não", true to "Sim"), { it == fit.hotClimate() }, small = true) { fit.setHydrationPrefs(fit.sweat(), it); refresh() }
        factCard(root, "sweat_variability")
        factCard(root, "water_adequate_intake")
    }
}

class SleepScreen : Screen() {
    override val title = "Sono"
    private var hours: Double? = null

    override fun build(root: LinearLayout) {
        val logs = fit.sleep()
        val week = logs.take(7)
        if (week.isNotEmpty()) root.card {
            kv("Média (${week.size} noites)", "${Fmt.num(week.map { it.hours }.average())} h")
            kv("Noites com 7 h ou mais", "${week.count { it.hours >= 7 }} de ${week.size}")
        }
        root.h2("Noite passada")
        root.chips(HOURS.map { it to hoursLabel(it) }, { it == hours }, small = true) { hours = it; refresh() }
        hours?.let { h ->
            root.label("Qualidade")
            root.chips(QUALITY.mapIndexed { i, q -> (i + 1) to q }, { false }) { q -> fit.logSleep(h, q); hours = null; main.toast("Sono registrado"); refresh() }
        }
        if (logs.isNotEmpty()) {
            root.h2("Histórico")
            root.card { logs.take(14).forEach { kv(Dates.short(it.nightOf), "${Fmt.num(it.hours)} h · ${QUALITY[it.quality - 1]}") } }
        }
        factCard(root, "sleep_duration")
    }

    companion object {
        val HOURS = listOf(4.0, 5.0, 5.5, 6.0, 6.5, 7.0, 7.5, 8.0, 8.5, 9.0, 10.0)
        val QUALITY = listOf("Péssima", "Ruim", "Regular", "Boa", "Ótima")
        fun hoursLabel(h: Double) = if (h <= 4.0) "≤4 h" else if (h >= 10.0) "10+ h" else "${Fmt.num(h)} h"
    }
}

class CardioScreen : Screen() {
    override val title = "Cardio"
    private var kind: String? = null
    private var minutes: Int? = null

    override fun build(root: LinearLayout) {
        val all = fit.cardio()
        val ws = fit.weekStart()
        val weekMin = all.filter { !it.at.toLocalDate().isBefore(ws) }.sumOf { it.minutes }.toInt()
        root.card {
            kv("Minutos nesta semana", "$weekMin min")
            bar(weekMin / 150.0, C.success)
            muted("Referência da OMS: 150–300 min/semana de atividade moderada.")
        }
        root.h2("Registrar")
        root.label("Atividade")
        root.chips(KINDS.map { it to it }, { it == kind }) { kind = it; refresh() }
        root.label("Duração")
        root.chips(listOf(10, 15, 20, 30, 45, 60, 90).map { it to "$it min" }, { it == minutes }, small = true) { minutes = it; refresh() }
        if (kind != null && minutes != null) {
            root.label("Intensidade")
            root.chips(listOf(3 to "Leve", 5 to "Moderada", 7 to "Forte", 9 to "Muito forte"), { false }) { rpe ->
                fit.logCardio(kind!!, minutes!!, rpe); kind = null; minutes = null; main.toast("Cardio registrado"); refresh()
            }
        }
        if (all.isNotEmpty()) {
            root.h2("Histórico")
            all.take(15).forEach { c ->
                root.card(bottom = 6) {
                    row(bottom = 0) {
                        val t = text("${Dates.short(c.at.toLocalDate())} · ${c.kind} · ${c.minutes.toInt()} min${c.rpe?.let { " · esforço $it/10" } ?: ""}", 14f, bottom = 0)
                        t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        text("✕", 18f, C.muted, bottom = 0).setOnClickListener { fit.deleteCardio(c.id); refresh() }
                    }
                }
            }
        }
        factCard(root, "who_activity")
    }

    companion object {
        val KINDS = listOf("Caminhada", "Corrida", "Bicicleta", "Natação", "Elíptico", "Corda", "Escada", "Esporte", "Outro")
    }
}

class MobilityScreen : Screen() {
    override val title = "Mobilidade"
    private var region: String? = null

    override fun build(root: LinearLayout) {
        root.muted("Registre o que fez. Movimentos lentos e confortáveis, sem dor. Dor articular persistente merece avaliação profissional.", 14f)
        root.label("Região")
        root.chips(REGIONS.map { it to it }, { it == region }) { region = it; refresh() }
        region?.let { r ->
            root.label("Duração")
            root.chips(listOf(5, 10, 15, 20, 30).map { it to "$it min" }, { false }) { m -> fit.logMobility(r, m); region = null; main.toast("Mobilidade registrada"); refresh() }
        }
        val logs = fit.mobility()
        if (logs.isNotEmpty()) {
            root.h2("Histórico")
            root.card { logs.take(15).forEach { kv(Dates.short(it.at.toLocalDate()), "${it.region} · ${it.minutes.toInt()} min") } }
        }
    }

    companion object {
        val REGIONS = listOf("Quadril", "Ombros", "Tornozelos", "Coluna torácica", "Posterior de coxa", "Punhos", "Corpo todo")
    }
}

class SupplementsScreen : Screen() {
    override val title = "Suplementos"
    private var open: SupplementId? = null

    override fun build(root: LinearLayout) {
        root.card(stroke = C.warning) { body("Informação educativa, não prescrição. Antes de usar qualquer suplemento, converse com médico ou nutricionista — especialmente se tiver alguma condição de saúde ou usar medicamentos.") }
        for (s in fit.kb.supplements) {
            val expanded = open == s.id
            root.card(onClick = { open = if (expanded) null else s.id; refresh() }) {
                row(bottom = 2) {
                    val t = text(s.name, 16f, bold = true, bottom = 0)
                    t.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    badge(s.evidenceLevel.label, C.fact, bottom = 0)
                }
                muted(s.whatIs)
                if (expanded) {
                    kv("Para que é estudado", "")
                    body(s.purpose)
                    label("O que a evidência mostra"); body(s.evidenceSummary)
                    label("Como foi estudado"); body(s.howStudied)
                    label("Efeitos conhecidos"); body(s.knownEffects)
                    label("Limitações"); body(s.limitations)
                    label("Cuidados"); text(s.cautions, 15f, C.warning)
                    label("Fontes")
                    s.sourceIds.forEach { muted("• ${fit.kb.source(it).citation()}", 12f) }
                } else text("Ver detalhes ▾", 13f, C.accent, bottom = 2)
            }
        }
    }
}

class EvidenceScreen : Screen() {
    override val title = "Evidências"
    private var tabSel = 0

    override fun build(root: LinearLayout) {
        val kb = fit.kb
        root.muted("Base de conhecimento ${kb.meta["content_version"] ?: ""} · revisada em ${kb.meta["last_reviewed"] ?: "?"}", 13f)
        root.chips(listOf(0 to "Afirmações (${kb.claims.size})", 1 to "Regras (${kb.rules.size})", 2 to "Fontes (${kb.sources.size})"), { it == tabSel }) { tabSel = it; refresh() }
        when (tabSel) {
            0 -> kb.claims.forEach { c ->
                root.card(bottom = 8, onClick = { push(ClaimScreen(c.id)) }) {
                    text("🔵 ${c.statement}", 14f)
                    row(bottom = 2) {
                        badge(c.evidenceLevel.label, C.fact, bottom = 0)
                        if (c.isConflicting) badge("Conflitante", C.warning, bottom = 0).let { (it.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6) }
                    }
                }
            }
            1 -> kb.rules.forEach { r ->
                root.card(bottom = 8) {
                    label(r.basis.label)
                    text("🟢 ${r.description}", 14f, bold = true)
                    muted(r.rationale, 13f)
                    if (r.claimIds.isNotEmpty()) muted("Apoiada em: ${r.claimIds.joinToString { it.value }}", 12f)
                }
            }
            else -> kb.sources.forEach { s ->
                root.card(bottom = 8) {
                    text(s.citation(), 13f)
                    row(bottom = 2) {
                        badge("Nível ${s.tier} · ${s.type.label}", C.fact, bottom = 0)
                        val v = badge(if (s.verification == VerificationStatus.VERIFIED) "verificada" else "pendente", if (s.verification == VerificationStatus.VERIFIED) C.success else C.warning, bottom = 0)
                        (v.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6)
                    }
                    (s.url ?: s.doi?.let { "https://doi.org/$it" })?.let { muted(it, 12f) }
                }
            }
        }
    }
}

class ClaimScreen(private val id: ClaimId) : Screen() {
    override val title = "Fontes"

    override fun build(root: LinearLayout) {
        val kb = fit.kb
        val c = kb.claim(id)
        root.text("🔵 ${c.statement}", 16f, bold = true)
        root.row(bottom = 10) {
            badge(c.evidenceLevel.label, C.fact, bottom = 0)
            if (c.isConflicting) badge("Evidência conflitante", C.warning, bottom = 0).let { (it.layoutParams as LinearLayout.LayoutParams).leftMargin = dp(6) }
        }
        if (c.isConflicting) root.muted("Há estudos com resultados diferentes. O app mostra os dois lados em vez de inventar um consenso.", 13f)
        for (st in Stance.values()) {
            val list = c.sources.filter { it.stance == st }
            if (list.isEmpty()) continue
            root.label(when (st) { Stance.SUPPORTS -> "Apoiam"; Stance.CONTRADICTS -> "Contradizem"; Stance.CONTEXT -> "Contexto" })
            list.forEach { cs ->
                val s = kb.source(cs.sourceId)
                root.card(bottom = 8) {
                    text(s.citation(), 13f)
                    muted(s.summary, 12f)
                    cs.note?.let { muted("Nota: $it", 12f) }
                    muted("Nível ${s.tier} · ${s.type.label} · ${if (s.verification == VerificationStatus.VERIFIED) "verificada em ${s.lastVerified}" else "verificação pendente"}", 12f)
                    (s.url ?: s.doi?.let { "https://doi.org/$it" })?.let { muted(it, 12f) }
                }
            }
        }
    }
}

class SimulatorScreen : Screen() {
    override val title = "Simulador \"e se?\""
    private val chosen = linkedSetOf<String>()
    private var result: Simulation? = null
    private var busy = false

    override fun build(root: LinearLayout) {
        root.muted("Roda o mesmo motor para outros cenários e compara volume e tempo. Nenhum cenário é \"o melhor\" para todo mundo.", 14f)
        val options = listOf("2 dias", "3 dias", "4 dias", "5 dias", "6 dias", "30 min", "45 min", "60 min", "90 min") + fit.kb.environments.map { it.name }
        root.chips(options.map { it to it }, { it in chosen }, small = true) { if (!chosen.add(it)) chosen.remove(it); refresh() }
        root.button(if (busy) "Simulando…" else "Comparar", enabled = chosen.isNotEmpty() && !busy) {
            busy = true; refresh()
            val scenarios = chosen.map { scenario(it) }
            main.background({ fit.simulate(scenarios) }) { r -> busy = false; result = r.getOrNull(); refresh() }
        }
        val sim = result ?: return
        val kb = fit.kb
        for (row in sim.rows) {
            root.card {
                h3(row.scenario)
                row.refusal?.let { muted(it); return@card }
                kv("Divisão", row.splitName ?: "—")
                kv("Treinos/semana", row.trainingDays.toString())
                kv("Tempo/semana", "${row.weeklyMinutes} min")
                kv("Abaixo do mínimo", if (row.belowMinimum.isEmpty()) "nenhum músculo" else row.belowMinimum.joinToString { kb.muscleName(it).lowercase() },
                    if (row.belowMinimum.isEmpty()) C.success else C.warning)
            }
        }
        root.explanation(sim.note)
    }

    private fun scenario(label: String): Scenario {
        Regex("(\\d) dias").matchEntire(label)?.let { return ProgramSimulator.days(it.groupValues[1].toInt()) }
        Regex("(\\d+) min").matchEntire(label)?.let { return ProgramSimulator.minutes(it.groupValues[1].toInt()) }
        return ProgramSimulator.environment(fit.kb.environments.first { it.name == label })
    }
}

class ToolsScreen : Screen() {
    override val title = "Ferramentas"
    private var target = 60.0
    private var bar = 20.0
    private var load = 60.0
    private var reps = 8
    private var rir = 2

    override fun build(root: LinearLayout) {
        root.h2("Calculadora de anilhas", top = 0)
        root.card {
            stepper(Fmt.num(target, 2), "kg", steps = listOf(
                "−10" to { target = (target - 10).coerceAtLeast(0.0); refresh() }, "−2,5" to { target = (target - 2.5).coerceAtLeast(0.0); refresh() },
                "+2,5" to { target += 2.5; refresh() }, "+10" to { target += 10; refresh() },
            ))
            label("Barra")
            chips(listOf(20.0 to "20 kg", 15.0 to "15 kg", 10.0 to "10 kg"), { it == bar }, small = true) { bar = it; fit.setBarKg(it); refresh() }
            body(fit.plates(target, bar).describe())
        }
        root.h2("1RM estimado")
        root.card {
            label("Carga")
            stepper(Fmt.num(load, 2), "kg", steps = listOf(
                "−5" to { load = (load - 5).coerceAtLeast(0.0); refresh() }, "−1" to { load = (load - 1).coerceAtLeast(0.0); refresh() },
                "+1" to { load += 1; refresh() }, "+5" to { load += 5; refresh() },
            ))
            label("Repetições")
            stepper(reps.toString(), repetitionsUnit(reps), steps = listOf("−1" to { reps = (reps - 1).coerceAtLeast(1); refresh() }, "+1" to { reps = (reps + 1).coerceAtMost(30); refresh() }))
            label("Repetições sobrando (RIR)")
            chips((0..5).map { it to it.toString() }, { it == rir }, small = true) { rir = it; refresh() }
            if (load > 0) {
                val e = e1rmLine(load, reps, rir)
                kv("Epley", Fmt.kg(e.epley)); kv("Brzycki", Fmt.kg(e.brzycki)); kv("Média", Fmt.kg(e.average), C.accent)
                e.note?.let { muted(it) }
            }
            explanation(fact("e1rm_equations"), 12f)
        }
        root.h2("Aquecimento para a carga de trabalho")
        root.card {
            muted("Usa a carga do 1RM acima como carga de trabalho (barra).", 12f)
            val ex = fit.kb.exercises.first { it.loadType == LoadType.BARBELL }
            WarmupGenerator.forWorkingLoad(ex, load, bar).forEach { s -> kv("${if (s.loadKg > 0) Fmt.kg(s.loadKg) else "leve"} × ${s.reps}", s.note) }
        }
    }
}

class ProfileScreen : Screen() {
    override val title = "Perfil e dados"

    override fun build(root: LinearLayout) {
        val p = fit.profile()
        if (p == null) { root.button("Responder questionário") { push(QuestionnaireScreen(fit.currentAnswers())) }; return }
        val kb = fit.kb
        root.card {
            kv("Idade", "${p.age} anos"); kv("Altura", "${p.heightCm.toInt()} cm"); kv("Peso", "${Fmt.num(p.weightKg)} kg")
            kv("Objetivo", p.primaryGoal.label); p.secondaryGoal?.let { kv("Secundário", it.label) }
            kv("Experiência", p.experience.label); kv("Local", p.environment?.let { kb.environment(it).name } ?: "—")
            kv("Dias", p.availability.joinToString { "${it.day.pt().take(3)} ${it.minutes}′" })
            if (p.sports.isNotEmpty()) kv("Esportes", p.sports.joinToString { "${kb.sport(it.sportId).name} (${it.day.pt().take(3)})" })
        }
        val s = fit.screening()
        if (s != null) root.card(stroke = if (s.status.name == "CLEAR") null else C.warning) {
            label("Triagem de segurança")
            text(s.status.label, 16f, bold = true)
            s.messages.forEach { explanation(it, 13f) }
        }
        if (p.limitations.isNotEmpty()) {
            root.h2("Dores registradas")
            p.limitations.forEach { l ->
                root.card(bottom = 8) {
                    text("${l.joint.label.replaceFirstChar { it.uppercase() }} · ${l.severity}/10", 15f, bold = true)
                    button("Dor melhorou — liberar exercícios", Btn.SECONDARY) {
                        main.background({ fit.resolvePain(l.joint) }) { r -> r.onSuccess { main.toast("Programa refeito") }; refresh() }
                    }
                }
            }
        }
        root.button("Refazer questionário") { push(QuestionnaireScreen(fit.currentAnswers())) }
        root.button("Gerar o programa de novo", Btn.SECONDARY) {
            main.background({ fit.regenerate() }) { r ->
                main.toast(if (r.getOrNull() is ProgramResult.Generated) "Programa atualizado" else "Não foi possível gerar — veja a triagem")
                refresh()
            }
        }
        root.space(16)
        root.button("Exportar meus dados (JSON)", Btn.SECONDARY) {
            val name = "fitkingia-dados-${fit.clock.now().toLocalDate()}.json"
            main.createDocument(name, "application/json") { uri ->
                try {
                    main.contentResolver.openOutputStream(uri)?.use { it.write(fit.exportJson().toByteArray(Charsets.UTF_8)) }
                    main.toast("Dados exportados")
                } catch (e: Exception) {
                    main.toast("Não foi possível exportar")
                }
            }
        }
        root.button("Apagar todos os meus dados", Btn.DANGER) {
            main.confirm("Apagar tudo?", "Perfil, treinos, medidas, fotos e registros serão apagados deste aparelho. Não dá para desfazer.", "Apagar tudo", danger = true) {
                fit.photos().forEach { File(it.uri).delete() }
                fit.deleteEverything()
                main.toast("Dados apagados")
                main.setRoot(QuestionnaireScreen(fit.currentAnswers(), firstRun = true))
            }
        }
        root.muted("Seus dados ficam só neste aparelho (user.db). O app não usa internet. A exportação gera um arquivo JSON com tudo o que está guardado, no lugar que você escolher.", 12f)
    }
}

class AboutScreen : Screen() {
    override val title = "Sobre"

    override fun build(root: LinearLayout) {
        val kb = fit.kb
        root.h1("FitKingIA")
        root.muted("Conhecimento ${kb.meta["content_version"] ?: "?"} · ${kb.exercises.size} exercícios · ${kb.sources.size} fontes · ${kb.rules.size} regras", 13f)
        root.card {
            h3("Como funciona")
            bullets(listOf(
                "Você responde o questionário só tocando nas alternativas.",
                "Um motor de regras determinístico monta o programa: mesmas respostas, mesmo programa.",
                "As regras ficam num banco de conhecimento com fontes e data de revisão.",
                "Nada é inventado por IA: o app não usa modelos de linguagem nem internet.",
                "Cada recomendação mostra o porquê: 🔵 fato com fonte · 🟢 regra do sistema.",
            ))
        }
        root.card(stroke = C.warning) {
            h3("Avisos")
            body("Ferramenta educativa. Não substitui médico, fisioterapeuta, nutricionista ou profissional de educação física. Não diagnostica dor nem doenças. Sinais de alerta (dor no peito, desmaio, falta de ar desproporcional) exigem avaliação médica.")
        }
        root.card {
            h3("Privacidade")
            body("Sem conta, sem internet, sem anúncios. Seus dados ficam no aparelho e podem ser apagados em Perfil e dados.")
        }
    }
}
