package com.fitkingia.coach.nlu

import com.fitkingia.coach.text.PtText
import com.fitkingia.core.knowledge.Exercise
import com.fitkingia.core.knowledge.KnowledgeBase
import com.fitkingia.core.model.*
import java.time.DayOfWeek
import java.time.LocalDate

/** Pistas de prontidão tiradas do texto ("dormi mal", "to exausto"). Nulos = não mencionado. */
data class ReadinessHints(
    val sleep: SleepQuality? = null,
    val energy: Int? = null,
    val soreness: Int? = null,
    val stress: Int? = null,
    val motivation: Int? = null,
) {
    val any: Boolean get() = listOf(sleep, energy, soreness, stress, motivation).any { it != null }
}

data class Entities(
    val day: DayOfWeek? = null,
    val dayWord: String? = null,
    val minutes: Int? = null,
    val exercise: Exercise? = null,
    val muscle: MuscleId? = null,
    val joint: Joint? = null,
    val painSeverity: Int? = null,
    val kg: Double? = null,
    val reps: Int? = null,
    val rir: Int? = null,
    val ml: Int? = null,
    val daysCount: Int? = null,
    val withoutEquipment: Set<EquipmentId> = emptySet(),
    val withEquipment: Set<EquipmentId> = emptySet(),
    val atHome: Boolean = false,
    val readiness: ReadinessHints = ReadinessHints(),
    val supplement: SupplementId? = null,
    val whatIf: Boolean = false,
)

/**
 * Extração de entidades por regras + dicionários do próprio banco de conhecimento
 * (nomes e apelidos de exercícios, músculos, equipamentos). Tolerante a erros de digitação.
 */
class EntityExtractor(private val kb: KnowledgeBase) {

    fun extract(text: String, today: LocalDate, preferred: Set<ExerciseId> = emptySet()): Entities {
        val n = PtText.normalize(text)
        val tokens = n.split(' ').filter { it.isNotEmpty() }
        val (day, dayWord) = day(n, today)
        val kgReps = kgAndReps(n)
        return Entities(
            day = day, dayWord = dayWord,
            minutes = minutes(n),
            exercise = exercise(tokens, preferred),
            muscle = muscle(tokens),
            joint = joint(n),
            painSeverity = painSeverity(n),
            kg = kgReps.first, reps = kgReps.second,
            rir = Regex("\\brir\\s*(\\d)\\b").find(n)?.groupValues?.get(1)?.toInt(),
            ml = ml(n),
            daysCount = daysCount(n),
            withoutEquipment = withoutEquipment(n),
            withEquipment = withEquipment(n),
            atHome = Regex("\\b(em casa|de casa|home)\\b").containsMatchIn(n),
            readiness = readiness(n),
            supplement = supplement(n),
            whatIf = Regex("\\be se\\b|\\bsimul|\\bcompar").containsMatchIn(n),
        )
    }

    /** Etiquetas usadas como atributos do classificador de intenções. */
    fun tags(text: String, today: LocalDate = LocalDate.of(2026, 1, 5)): Set<String> {
        val e = extract(text, today)
        val n = PtText.normalize(text)
        return buildSet {
            if (e.day != null) add("day")
            if (e.minutes != null) add("minutes")
            if (e.exercise != null) add("exercise")
            if (e.muscle != null) add("muscle")
            if (e.joint != null) add("joint")
            if (e.kg != null) add("kg")
            if (e.kg != null && e.reps != null) add("kg_reps")
            if (e.ml != null) add("ml")
            if (e.daysCount != null) add("days_count")
            if (e.supplement != null) add("supplement")
            if (e.readiness.any) add("readiness")
            if (e.whatIf) add("what_if")
            if (e.atHome || e.withoutEquipment.isNotEmpty() || e.withEquipment.isNotEmpty()) add("equipment")
            if (Regex("\\b(por que|porque|pq|motivo|justificativa|razao)\\b").containsMatchIn(n)) add("why")
            if (Regex("\\b(como (faco|faz|fazer|executo|executa|executar)|execucao|tecnica|ensina|postura)\\b").containsMatchIn(n)) add("how")
            if (Regex("\\b(dor|doi|doendo|dolorid|machuq|lesion|travou|travad|torci|distend|tendinite)").containsMatchIn(n)) add("pain_word")
            if (Regex("\\b(voce|vc|te |sabe fazer|consegue fazer|funciona voce)").containsMatchIn(n)) add("you")
        }
    }

    // ---------------------------------------------------------------- dias
    private val dayWords = listOf(
        DayOfWeek.MONDAY to "segunda|seg", DayOfWeek.TUESDAY to "terca|ter", DayOfWeek.WEDNESDAY to "quarta|qua",
        DayOfWeek.THURSDAY to "quinta|qui", DayOfWeek.FRIDAY to "sexta|sex", DayOfWeek.SATURDAY to "sabado|sab",
        DayOfWeek.SUNDAY to "domingo|dom",
    )

    fun day(n: String, today: LocalDate): Pair<DayOfWeek?, String?> {
        when {
            Regex("\\bhoje\\b").containsMatchIn(n) -> return today.dayOfWeek to "hoje"
            Regex("\\bamanha\\b").containsMatchIn(n) -> return today.plusDays(1).dayOfWeek to "amanhã"
            Regex("\\bontem\\b").containsMatchIn(n) -> return today.minusDays(1).dayOfWeek to "ontem"
        }
        for ((d, pattern) in dayWords) {
            if (Regex("\\b($pattern)(-feira| feira)?\\b").containsMatchIn(n)) return d to null
        }
        return null to null
    }

    // ---------------------------------------------------------------- tempo
    fun minutes(n: String): Int? {
        Regex("\\b(\\d{1,2})\\s*h(?:oras?)?\\s*(?:e\\s*)?(\\d{1,2})\\s*(?:min|minutos|m)?\\b").find(n)?.let {
            return it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt()
        }
        Regex("\\b(\\d{1,3})\\s*(?:min|mins|minuto|minutos)\\b").find(n)?.let { return it.groupValues[1].toInt() }
        if (Regex("\\bhora e meia\\b").containsMatchIn(n)) return 90
        if (Regex("\\bmeia hora\\b").containsMatchIn(n)) return 30
        if (Regex("\\b(uma|1) hora\\b|\\b1\\s*h\\b").containsMatchIn(n)) return 60
        Regex("\\b(\\d)\\s*h(?:oras?)?\\b").find(n)?.let { return it.groupValues[1].toInt() * 60 }
        return null
    }

    // ---------------------------------------------------------------- números
    fun kgAndReps(n: String): Pair<Double?, Int?> {
        // "100 kg x 5", "100kg x5", "100 x 5", "100 kg 5 vezes", "80 kg por 8"
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:kg|quilos?|kilos?)?\\s*(?:x|por)\\s*(\\d{1,2})\\b").find(n)?.let {
            return it.groupValues[1].replace(',', '.').toDouble() to it.groupValues[2].toInt()
        }
        val kg = Regex("(\\d+(?:[.,]\\d+)?)\\s*(?:kg|quilos?|kilos?)\\b").find(n)?.groupValues?.get(1)?.replace(',', '.')?.toDouble()
        val reps = Regex("\\b(\\d{1,2})\\s*(?:reps?|repeticoes|repeticao|vezes)\\b").find(n)?.groupValues?.get(1)?.toInt()
        return kg to reps
    }

    fun ml(n: String): Int? {
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(ml|l|litros?)\\b").find(n)?.let {
            val v = it.groupValues[1].replace(',', '.').toDouble()
            return if (it.groupValues[2] == "ml") v.toInt() else (v * 1000).toInt()
        }
        if (Regex("\\bum copo\\b|\\b1 copo\\b").containsMatchIn(n)) return 250
        if (Regex("\\buma garrafa\\b|\\b1 garrafa\\b").containsMatchIn(n)) return 500
        return null
    }

    private val numberWords = mapOf("um" to 1, "uma" to 1, "dois" to 2, "duas" to 2, "tres" to 3, "quatro" to 4, "cinco" to 5, "seis" to 6)

    fun daysCount(n: String): Int? {
        Regex("\\b(\\d)\\s*(?:dias|vezes|treinos|x)\\b(?:\\s*(?:por|na|a)\\s*semana|\\s*semanais)?").find(n)?.let {
            val v = it.groupValues[1].toInt(); if (v in 1..7 && !Regex("\\d+\\s*(kg|min)").containsMatchIn(it.value)) return v
        }
        Regex("\\b(um|uma|dois|duas|tres|quatro|cinco|seis)\\s+(?:dias|vezes|treinos)\\b").find(n)?.let { return numberWords[it.groupValues[1]] }
        return null
    }

    // ---------------------------------------------------------------- corpo
    private val muscleWords: List<Pair<MuscleId, Regex>> = listOf(
        "chest" to "peito|peitoral|peitorais",
        "lats" to "costas|dorsal|dorsais|latissimo|lats|grande dorsal",
        "mid_back" to "romboides|trapezio medio|meio das costas",
        "side_delts" to "ombro|ombros|deltoide|deltoides|deltoide lateral",
        "rear_delts" to "deltoide posterior|posterior de ombro",
        "biceps" to "biceps|bicep|bices",
        "triceps" to "triceps|tricep",
        "quads" to "quadriceps|quadricep|coxa|coxas|perna|pernas|frente da coxa",
        "hamstrings" to "posterior|posteriores|posterior de coxa|isquiotibiais",
        "glutes" to "gluteo|gluteos|bunda|bumbum",
        "calves" to "panturrilha|panturrilhas|batata da perna",
        "abs" to "abdomen|abdominal|abdominais|barriga|core",
        "forearms" to "antebraco|antebracos",
    ).map { (id, p) -> MuscleId(id) to Regex("\\b($p)\\b") }

    fun muscle(tokens: List<String>): MuscleId? {
        val n = tokens.joinToString(" ")
        // Expressões mais longas primeiro ("deltoide posterior" antes de "posterior").
        return muscleWords.sortedByDescending { it.second.pattern.length }.firstOrNull { (id, r) ->
            r.containsMatchIn(n) && kb.muscles.any { it.id == id }
        }?.first
    }

    private val jointWords = listOf(
        Joint.LOWER_BACK to "lombar|coluna|costas baixa|parte de baixo das costas|lombo",
        Joint.KNEE to "joelho|joelhos|patela",
        Joint.SHOULDER to "ombro|ombros|manguito",
        Joint.ELBOW to "cotovelo|cotovelos",
        Joint.WRIST to "punho|punhos|pulso|pulsos",
        Joint.HIP to "quadril|virilha",
        Joint.ANKLE to "tornozelo|tornozelos",
        Joint.NECK to "pescoco|cervical",
    )

    fun joint(n: String): Joint? {
        jointWords.firstOrNull { (_, p) -> Regex("\\b($p)\\b").containsMatchIn(n) }?.let { return it.first }
        // "dor nas costas" sem especificar: tratada como lombar (mais comum e mais conservador).
        if (Regex("\\b(dor|doi|doendo|fisgada|lesion|travou|travad|torci|distend).{0,20}\\bcostas\\b|\\bcostas.{0,15}\\b(doi|doendo|dor|travad)").containsMatchIn(n)) return Joint.LOWER_BACK
        return null
    }

    fun painSeverity(n: String): Int? {
        Regex("\\b(\\d{1,2})\\s*/\\s*10\\b").find(n)?.let { return it.groupValues[1].toInt().coerceIn(0, 10) }
        Regex("\\bdor\\s*(?:de|nivel|nota)?\\s*(\\d{1,2})\\b").find(n)?.let { return it.groupValues[1].toInt().coerceIn(0, 10) }
        return when {
            Regex("\\b(insuportavel|muito forte|aguda|intensa|forte demais|nao consigo mexer)\\b").containsMatchIn(n) -> 8
            Regex("\\b(forte|muita dor|bastante)\\b").containsMatchIn(n) -> 6
            Regex("\\b(moderada|media)\\b").containsMatchIn(n) -> 5
            Regex("\\b(leve|pouca|incomodo|incomodando|desconforto)\\b").containsMatchIn(n) -> 3
            else -> null
        }
    }

    fun readiness(n: String): ReadinessHints {
        fun has(p: String) = Regex("\\b($p)").containsMatchIn(n)
        val sleep = when {
            has("dormi mal|dormi pouco|nao dormi|sono ruim|insonia|noite mal dormida|dormi so|dormi (umas |uns |tipo )?[1-5] ?h") -> SleepQuality.POOR
            has("dormi muito bem|dormi super bem|sono otimo|dormi otimo") -> SleepQuality.EXCELLENT
            has("dormi bem") -> SleepQuality.NORMAL
            else -> null
        }
        val energy = when {
            has("exaust|morto|podre|quebrad|moid|acabad|destruid|esgotad|baquead|sem energia|sem pique|sem gas|sem forca|sem disposic|indispost") -> 2
            has("cansad") -> 4
            has("dispost|com energia|animad|pilhad") -> 8
            else -> null
        }
        val soreness = when {
            has("todo dolorid|muita dor muscular|muito dolorid") -> 8
            has("dolorid|dor muscular") -> 6
            else -> null
        }
        val stress = when {
            has("muito estressad|estresse alto|ansios") -> 8
            has("estressad|semana puxada|preocupad") -> 7
            else -> null
        }
        val motivation = when {
            has("desanimad|sem vontade|desmotivad|preguica") -> 3
            has("animad|motivad|com vontade") -> 8
            else -> null
        }
        return ReadinessHints(sleep, energy, soreness, stress, motivation)
    }

    // ---------------------------------------------------------------- equipamento
    private val equipmentWords: List<Pair<Regex, Set<String>>> = listOf(
        "barras?" to setOf("barbell", "ez_bar"),
        "halter|halteres|haltere" to setOf("dumbbells"),
        "maquinas?|aparelhos?" to setOf("leg_press", "hack_squat", "leg_extension", "leg_curl", "pec_deck", "chest_press_machine",
            "shoulder_press_machine", "calf_machine", "hip_abduction_machine", "hip_adduction_machine", "seated_row_machine",
            "lat_pulldown", "smith_machine", "back_extension_bench"),
        "polia|cabo|crossover" to setOf("cable_station", "lat_pulldown"),
        "banco" to setOf("flat_bench", "adjustable_bench"),
        "elastico|elasticos|faixa" to setOf("resistance_bands"),
        "kettlebell" to setOf("kettlebell"),
        "smith" to setOf("smith_machine"),
        "barra fixa" to setOf("pull_up_bar"),
    ).map { (p, ids) -> Regex(p) to ids }

    fun withoutEquipment(n: String): Set<EquipmentId> {
        if (Regex("\\bsem (equipamento|nada|peso|aparelhos?)\\b|\\bso (o )?peso (do )?corpo|\\bpeso corporal\\b").containsMatchIn(n))
            return kb.equipment.map { it.id }.toSet()
        return equipmentAfter(n, "sem|nao tenho|nao tem|sem ter")
    }

    fun withEquipment(n: String): Set<EquipmentId> = equipmentAfter(n, "com|usando|so tenho|tenho")

    private fun equipmentAfter(n: String, lead: String): Set<EquipmentId> {
        val out = mutableSetOf<String>()
        for ((r, ids) in equipmentWords) if (Regex("\\b($lead)\\s+(um |uma |o |a |os |as )?(${r.pattern})\\b").containsMatchIn(n)) out += ids
        return out.map(::EquipmentId).filter { id -> kb.equipment.any { it.id == id } }.toSet()
    }

    // ---------------------------------------------------------------- suplementos
    fun supplement(n: String): SupplementId? = listOf(
        "creatine" to "creatina", "whey" to "whey|proteina do soro|proteina em po", "caffeine" to "cafeina|cafe|pre treino|pre-treino",
        "beta_alanine" to "beta alanina|beta-alanina|betaalanina", "electrolytes" to "eletrolito|eletrolitos|isotonico|sais",
    ).firstOrNull { (_, p) -> Regex("\\b($p)\\b").containsMatchIn(n) }?.first?.let(::SupplementId)
        ?.takeIf { id -> kb.supplements.any { it.id == id } }

    // ---------------------------------------------------------------- exercícios
    private data class Alias(val exercise: Exercise, val words: List<String>)

    private val aliases: List<Alias> = kb.exercises.flatMap { ex ->
        (listOf(ex.name) + ex.aliases).map { a ->
            Alias(ex, PtText.tokens(a).filter { it !in PtText.STOPWORDS && it !in NOISE && !it.startsWith("(") })
        }
    }.filter { it.words.isNotEmpty() }

    /**
     * Melhor exercício citado. Prioriza o apelido mais longo inteiramente presente na frase (tolerando
     * erros de digitação); em empate, prefere exercícios do programa do usuário ([preferred]) e os mais comuns.
     */
    fun exercise(tokens: List<String>, preferred: Set<ExerciseId> = emptySet()): Exercise? {
        val words = tokens.filter { it !in PtText.STOPWORDS }
        if (words.isEmpty()) return null
        data class Hit(val ex: Exercise, val matched: Int, val total: Int)
        val hits = aliases.mapNotNull { a ->
            val matched = a.words.count { w -> words.any { PtText.similarWord(it, w) } }
            if (matched == a.words.size) Hit(a.exercise, matched, a.words.size) else null
        }
        return hits.sortedWith(
            compareByDescending<Hit> { it.matched }
                .thenByDescending { it.ex.id in preferred }
                .thenByDescending { it.ex.staple }
                .thenBy { it.ex.id.value }
        ).firstOrNull()?.ex
    }

    private companion object {
        /** Palavras dos nomes que sozinhas não identificam exercício. */
        val NOISE = setOf("maquina", "barra", "halter", "halteres", "polia", "peso", "corporal", "sentado", "pe", "com", "no", "na")
    }
}
