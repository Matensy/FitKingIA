package com.fitkingia.core.nutrition

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.Food
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.knowledge.NutritionRules
import com.fitkingia.core.knowledge.Rule
import com.fitkingia.core.knowledge.normalized
import com.fitkingia.core.model.*
import kotlin.math.roundToInt

data class NutritionTargets(
    val bmrKcal: Int,
    val maintenanceKcal: Int,
    val targetKcal: Int,
    val energyGoal: EnergyGoal,
    val proteinG: IntRange,
    val proteinTargetG: Int,
    val explanations: List<Explanation>,
)

/** Estimativas de energia e proteína. Estimativas populacionais — individualização é com nutricionista. */
class EnergyEstimator(private val rule: Rule<NutritionRules>) {

    /** Mifflin-St Jeor (1990). */
    fun bmr(sex: Sex, weightKg: Double, heightCm: Double, age: Int): Double =
        10 * weightKg + 6.25 * heightCm - 5 * age + if (sex == Sex.MALE) 5 else -161

    fun targets(profile: UserProfile, goal: EnergyGoal = energyGoalFor(profile.primaryGoal)): NutritionTargets {
        val r = rule.params
        val bmr = bmr(profile.sex, profile.weightKg, profile.heightCm, profile.age)
        val factor = r.activityFactors[profile.activityLevel] ?: 1.55
        val tdee = bmr * factor
        val adj = r.energyAdjustment[goal] ?: 0.0
        var target = tdee * (1 + adj)
        val notes = mutableListOf<Explanation>()
        notes += Explanation.rule(
            "Gasto estimado: TMB ${Fmt.int(bmr.roundToInt())} kcal (Mifflin-St Jeor) × ${Fmt.num(factor, 3)} (${profile.activityLevel.label.lowercase()}) " +
                "≈ ${Fmt.int(tdee.roundToInt())} kcal. Ajuste para ${goal.label.lowercase()}: ${(adj * 100).roundToInt()}%. " +
                "Equações preditivas erram para muitas pessoas; ajuste pela tendência de peso real após 2–3 semanas.",
            rule.id,
        )
        if (target < bmr) {
            target = bmr
            notes += Explanation.rule("Meta limitada à taxa metabólica basal estimada (piso de segurança do app).", rule.id)
        }
        if (goal == EnergyGoal.DEFICIT) notes += Explanation.rule(
            "Ritmo de perda geralmente sugerido: ${Fmt.num(r.weeklyLossPctMin)}–${Fmt.num(r.weeklyLossPctMax)}% do peso corporal por semana.", rule.id,
        )
        val pMin = (r.proteinMinGPerKg * profile.weightKg).roundToInt()
        val pMax = (r.proteinMaxGPerKg * profile.weightKg).roundToInt()
        val pTarget = (r.proteinTargetGPerKg * profile.weightKg).roundToInt()
        notes += Explanation.rule(
            "Proteína: $pMin–$pMax g/dia (${Fmt.num(r.proteinMinGPerKg)}–${Fmt.num(r.proteinMaxGPerKg)} g/kg), referência $pTarget g. " +
                "Pessoas com doença renal ou outras condições devem seguir orientação profissional.", rule.id,
        )
        return NutritionTargets(bmr.roundToInt(), tdee.roundToInt(), target.roundToInt(), goal, pMin..pMax, pTarget, notes)
    }

    companion object {
        fun energyGoalFor(goal: Goal): EnergyGoal = when (goal) {
            Goal.FAT_LOSS, Goal.WAIST_REDUCTION, Goal.DEFINITION -> EnergyGoal.DEFICIT
            Goal.WEIGHT_GAIN, Goal.HYPERTROPHY -> EnergyGoal.SURPLUS
            Goal.RECOMPOSITION -> EnergyGoal.RECOMPOSITION
            else -> EnergyGoal.MAINTENANCE
        }
    }
}

data class MealItem(val text: String, val food: Food, val grams: Double) {
    private fun per(v: Double?) = v?.let { it * grams / 100 }
    val kcal get() = food.kcal * grams / 100
    val proteinG get() = per(food.proteinG)
    val carbsG get() = per(food.carbsG)
    val fatG get() = per(food.fatG)
    val fiberG get() = per(food.fiberG)
}

data class ParsedMeal(
    val items: List<MealItem>,
    val unmatched: List<String>,
    val kcal: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val fiberG: Double,
    /** Nutrientes sem valor verificado em algum item: o total é parcial. */
    val incomplete: Set<String>,
    val notes: List<Explanation>,
)

/**
 * Registro em linguagem natural, determinístico: "Hoje comi arroz, feijão, frango e banana."
 * Aceita quantidades ("200 g de arroz", "2 ovos"). Sem quantidade, usa a porção padrão editável.
 */
class MealParser(private val kb: KnowledgeBase) {

    fun parse(text: String): ParsedMeal {
        val cleaned = text.normalized()
            .replace(Regex("[.!?]"), " ")
            .replace(Regex("\\b(hoje|ontem|eu|comi|almocei|jantei|lanchei|tomei|bebi|no almoco|no jantar|no cafe da manha|de manha|a noite|um pouco de)\\b"), " ")
        val chunks = cleaned.split(Regex(",|;|\\+|\\n|\\s+e\\s+|\\s+com\\s+")).map { it.trim() }.filter { it.isNotBlank() }
        val items = mutableListOf<MealItem>()
        val unmatched = mutableListOf<String>()
        val notes = mutableListOf<Explanation>()
        for (chunk in chunks) {
            val food = match(chunk)
            if (food == null) { unmatched += chunk; continue }
            val q = Regex("^(\\d+(?:[.,]\\d+)?)\\s*(kg|g|gr|gramas)?\\b").find(chunk)
            val grams = when {
                q == null -> food.defaultServingG.also {
                    notes += Explanation.rule("\"$chunk\": porção padrão de ${food.servingLabel} (${it.roundToInt()} g) — ajuste se necessário.")
                }
                q.groupValues[2] == "kg" -> q.groupValues[1].replace(',', '.').toDouble() * 1000
                q.groupValues[2].isNotEmpty() -> q.groupValues[1].replace(',', '.').toDouble()
                else -> q.groupValues[1].replace(',', '.').toDouble() * food.defaultServingG
            }
            items += MealItem(chunk, food, grams)
        }
        val incomplete = buildSet {
            if (items.any { it.food.proteinG == null }) add("proteína")
            if (items.any { it.food.carbsG == null }) add("carboidratos")
            if (items.any { it.food.fatG == null }) add("gorduras")
            if (items.any { it.food.fiberG == null }) add("fibras")
        }
        if (unmatched.isNotEmpty()) notes += Explanation.rule("Não reconhecido no banco de alimentos: ${unmatched.joinToString()}.")
        return ParsedMeal(
            items, unmatched,
            items.sumOf { it.kcal }, items.sumOf { it.proteinG ?: 0.0 }, items.sumOf { it.carbsG ?: 0.0 },
            items.sumOf { it.fatG ?: 0.0 }, items.sumOf { it.fiberG ?: 0.0 }, incomplete, notes,
        )
    }

    private fun match(chunk: String): Food? {
        var best: Food? = null
        var bestLen = 0
        for (f in kb.foods) for (alias in f.aliases + f.name) {
            val a = alias.normalized()
            if (a.length > bestLen && Regex("\\b${Regex.escape(a)}").containsMatchIn(chunk)) { best = f; bestLen = a.length }
        }
        return best
    }
}
