package com.fitkingia.app.figure

import com.fitkingia.core.knowledge.Exercise
import java.text.Normalizer
import java.util.Locale

/**
 * Qual ilustração mostrar para cada exercício do banco.
 *
 * 1. Mapa explícito por id (os 125 exercícios de exercises.json na data deste arquivo).
 * 2. Exercícios novos: variações reconhecidas pelo nome que o padrão não distingue
 *    (ex.: "mesa flexora", "coice", "búlgaro"). Cada regra só vale para os padrões de movimento
 *    compatíveis: "declinado" num supino não pode virar abdominal declinado.
 * 3. Padrão de movimento + tipo de carga + equipamento.
 * 4. Padrão desconhecido: palavras-chave do nome; por fim "em_pe" (posição neutra).
 * Nunca devolve um movimento inexistente.
 */
object FigureMapping {

    /** id do exercício → id do movimento em [MotionCatalog]. */
    val explicit: Map<String, String> = mapOf(
        // Agachamentos e leg press
        "barbell_back_squat" to "agachamento_barra",
        "front_squat" to "agachamento_frontal",
        "goblet_squat" to "agachamento_goblet",
        "hack_squat" to "agachamento_hack",
        "smith_squat" to "agachamento_smith",
        "leg_press" to "leg_press",
        "bodyweight_squat" to "agachamento_livre",
        // Afundos
        "bulgarian_split_squat" to "bulgaro",
        "dumbbell_split_squat" to "afundo",
        "walking_lunge" to "afundo",
        "reverse_lunge_bodyweight" to "afundo_reverso",
        "split_squat_bodyweight" to "afundo_livre",
        "step_up" to "subida_banco",
        // Joelho
        "leg_extension" to "extensora",
        "sissy_squat" to "sissy",
        "lying_or_seated_leg_curl" to "flexora_cadeira",
        "nordic_curl" to "flexao_nordica",
        "slider_leg_curl" to "flexora_deslizante",
        "band_leg_curl" to "flexora_elastico",
        // Dobradiça de quadril
        "conventional_deadlift" to "terra",
        "romanian_deadlift" to "terra_romeno",
        "dumbbell_romanian_deadlift" to "stiff_halteres",
        "single_leg_rdl" to "stiff_unilateral",
        "single_leg_rdl_bodyweight" to "stiff_unilateral_livre",
        "kettlebell_swing" to "swing",
        "back_extension" to "extensao_lombar",
        "cable_pull_through" to "pull_through",
        // Extensão de quadril
        "barbell_hip_thrust" to "hip_thrust_barra",
        "dumbbell_hip_thrust" to "hip_thrust_halter",
        "glute_bridge" to "ponte",
        "single_leg_glute_bridge" to "ponte_unilateral",
        // Panturrilha
        "standing_calf_raise_machine" to "panturrilha_maquina",
        "leg_press_calf_raise" to "panturrilha_leg_press",
        "dumbbell_calf_raise" to "panturrilha_halter",
        "bodyweight_calf_raise" to "panturrilha_livre",
        // Abdução / adução
        "hip_abduction_machine" to "abdutora",
        "band_lateral_walk" to "caminhada_lateral",
        "hip_adduction_machine" to "adutora",
        // Peito
        "barbell_bench_press" to "supino_barra",
        "dumbbell_bench_press" to "supino_halteres",
        "incline_barbell_bench_press" to "supino_inclinado_barra",
        "incline_dumbbell_press" to "supino_inclinado_halteres",
        "machine_chest_press" to "supino_maquina",
        "smith_bench_press" to "supino_smith",
        "push_up" to "flexao",
        "incline_push_up" to "flexao_inclinada",
        "chest_dip" to "paralelas",
        "band_chest_press" to "supino_elastico",
        "close_grip_bench_press" to "supino_barra",
        "pec_deck" to "voador",
        "cable_fly" to "crucifixo_polia",
        "dumbbell_fly" to "crucifixo_halteres",
        // Ombros
        "barbell_overhead_press" to "desenvolvimento_barra",
        "dumbbell_shoulder_press" to "desenvolvimento_halteres",
        "machine_shoulder_press" to "desenvolvimento_maquina",
        "pike_push_up" to "flexao_pike",
        "band_overhead_press" to "desenvolvimento_elastico",
        "dumbbell_lateral_raise" to "elevacao_lateral",
        "cable_lateral_raise" to "elevacao_lateral_polia",
        "band_lateral_raise" to "elevacao_lateral_elastico",
        "reverse_pec_deck" to "crucifixo_inverso_maquina",
        "face_pull" to "face_pull",
        "dumbbell_reverse_fly" to "crucifixo_inverso_halteres",
        "band_pull_apart" to "pull_apart",
        "prone_t_raise" to "elevacao_t",
        // Costas
        "barbell_row" to "remada_curvada",
        "one_arm_dumbbell_row" to "remada_unilateral",
        "seated_cable_row" to "remada_sentada_polia",
        "machine_row" to "remada_maquina",
        "chest_supported_dumbbell_row" to "remada_apoiada",
        "inverted_row" to "remada_invertida_trx",
        "inverted_row_bar" to "remada_invertida_barra",
        "band_row" to "remada_elastico",
        "towel_door_row" to "remada_toalha",
        "towel_door_row_single" to "remada_toalha",
        "pull_up" to "barra_fixa",
        "chin_up" to "barra_fixa",
        "assisted_pull_up_band" to "barra_fixa_assistida",
        "lat_pulldown" to "puxada",
        "close_grip_pulldown" to "puxada",
        "straight_arm_pulldown" to "pulldown_bracos_estendidos",
        "band_lat_pulldown" to "puxada_elastico",
        // Braços
        "barbell_curl" to "rosca_barra",
        "ez_bar_curl" to "rosca_barra",
        "dumbbell_curl" to "rosca_halteres",
        "hammer_curl" to "rosca_halteres",
        "incline_dumbbell_curl" to "rosca_inclinada",
        "cable_curl" to "rosca_polia",
        "band_curl" to "rosca_elastico",
        "cable_triceps_pushdown" to "triceps_polia",
        "overhead_cable_triceps_extension" to "triceps_frances_polia",
        "dumbbell_overhead_triceps_extension" to "triceps_frances_halter",
        "skull_crusher" to "triceps_testa",
        "bench_dip" to "mergulho_banco",
        "diamond_push_up" to "flexao",
        "band_triceps_extension" to "triceps_elastico",
        // Core
        "plank" to "prancha",
        "dead_bug" to "dead_bug",
        "ab_wheel_rollout" to "roda_abdominal",
        "pallof_press_cable" to "pallof_polia",
        "pallof_press_band" to "pallof_elastico",
        "side_plank" to "prancha_lateral",
        "cable_crunch" to "abdominal_polia",
        "crunch" to "abdominal",
        "hanging_knee_raise" to "elevacao_joelhos",
        "cable_woodchop" to "lenhador",
        // Carregamentos
        "farmers_walk" to "fazendeiro",
        "suitcase_carry" to "mala",
        // Aparelhos e variações acrescentados depois (foco em glúteos e iniciantes)
        "machine_hip_thrust" to "hip_thrust_maquina",
        "smith_hip_thrust" to "hip_thrust_smith",
        "machine_glute_kickback" to "coice_maquina",
        "cable_glute_kickback" to "coice_polia",
        "ankle_weight_kickback" to "coice_quatro_apoios",
        "cable_hip_abduction" to "abducao_polia",
        "side_lying_hip_abduction" to "abducao_deitado",
        "mini_band_clamshell" to "concha",
        "mini_band_glute_bridge" to "ponte_mini_band",
        "dumbbell_sumo_squat" to "agachamento_sumo",
        "seated_calf_raise" to "panturrilha_sentado",
        "t_bar_row" to "remada_curvada",
        "machine_assisted_pull_up" to "barra_fixa_maquina",
        "preacher_curl" to "rosca_scott",
        "swiss_ball_leg_curl" to "flexora_bola",
        "decline_crunch" to "abdominal_declinado",
        "swiss_ball_crunch" to "abdominal_bola",
    )

    /** Regra por nome: palavras → movimento, só nos padrões de movimento em que faz sentido. */
    /** [loads] null = qualquer tipo de carga; senão a regra só vale para esses (o acessório desenhado muda). */
    private class NameRule(val words: List<String>, val motion: String, val patterns: Set<String>, val loads: Set<String>? = null)

    private fun rule(vararg words: String, to: String, patterns: Set<String>) = NameRule(words.toList(), to, patterns)
    private fun rule(vararg words: String, to: String, pattern: String, loads: Set<String>? = null) = NameRule(words.toList(), to, setOf(pattern), loads)

    /**
     * Variações que o padrão de movimento sozinho não distingue (ex.: mesa × cadeira flexora).
     * Palavras sem acento, minúsculas, casadas como palavra inteira. Ordem importa. Cada regra
     * vale só nos padrões listados; com padrão desconhecido (o banco ganhou um padrão novo), vale
     * qualquer uma, porque aí só o nome orienta.
     */
    private val refinements: List<NameRule> = listOf(
        rule("mesa flexora", "flexora deitado", "flexora deitada", "lying leg curl", to = "flexora_mesa", pattern = "knee_flexion"),
        // Bola suíça, não qualquer bola: "abdominal com bola medicinal" não deita numa bola.
        rule("bola suica", "bola de pilates", "na bola", "fitball", "swiss ball", "stability ball", to = "flexora_bola", pattern = "knee_flexion"),
        rule("bola suica", "bola de pilates", "na bola", "fitball", "swiss ball", "stability ball", to = "abdominal_bola", pattern = "trunk_flexion"),
        rule("quatro apoios", "quadruped", "donkey kick", to = "coice_quatro_apoios", pattern = "hip_extension"),
        rule("coice", "kickback", "glute kickback", to = "coice_polia", pattern = "hip_extension"),
        // Depois do coice (coice com mini band continua coice) e só sem carga externa: elevação
        // pélvica com barra e mini band é a da barra, não a ponte no chão.
        rule("mini band", "miniband", "mini elastico", to = "ponte_mini_band", pattern = "hip_extension", loads = setOf("BODYWEIGHT", "BAND")),
        rule("concha", "clamshell", "abertura de joelhos", to = "concha", pattern = "hip_abduction"),
        rule("deitado de lado", "deitada de lado", "side lying", to = "abducao_deitado", pattern = "hip_abduction"),
        rule("abducao em pe", "abducao de quadril em pe", "standing abduction", to = "abducao_em_pe", pattern = "hip_abduction"),
        rule("panturrilha sentado", "seated calf", to = "panturrilha_sentado", pattern = "calf_raise"),
        rule("scott", "preacher", to = "rosca_scott", pattern = "elbow_flexion"),
        rule("declinado", "decline", to = "abdominal_declinado", pattern = "trunk_flexion"),
        // Só pendurado: "elevação de pernas deitado" é no chão, sem barra.
        rule("na barra", "pendurado", "pendurada", "hanging", to = "elevacao_joelhos", pattern = "trunk_flexion"),
        rule("cavalinho", "t-bar", "t bar", to = "remada_curvada", pattern = "horizontal_pull"),
        rule("assistida na maquina", "gravitron", "graviton", to = "barra_fixa_maquina", pattern = "vertical_pull"),
        rule("barra fixa", "pull up", "pull-up", "chin up", to = "barra_fixa", pattern = "vertical_pull"),
        rule("nordica", "nordic", to = "flexao_nordica", pattern = "knee_flexion"),
        rule("bulgaro", "bulgarian", to = "bulgaro", patterns = setOf("lunge", "squat")),
        // A figura do sumô segura um halter (ou kettlebell) entre as pernas; sumô com barra fica no padrão.
        rule("sumo", to = "agachamento_sumo", pattern = "squat", loads = setOf("DUMBBELL", "KETTLEBELL")),
        rule("agachamento frontal", "front squat", to = "agachamento_frontal", pattern = "squat"),
        rule("hack", to = "agachamento_hack", pattern = "squat"),
        rule("pull through", "pull-through", to = "pull_through", patterns = setOf("hinge", "hip_extension")),
        rule("swing", to = "swing", pattern = "hinge"),
        rule("face pull", to = "face_pull", patterns = setOf("horizontal_abduction", "horizontal_pull")),
        rule("serrote", to = "remada_unilateral", pattern = "horizontal_pull"),
        rule("pike", to = "flexao_pike", pattern = "vertical_push"),
        rule("diamante", "diamond", to = "flexao", patterns = setOf("horizontal_push", "elbow_extension")),
        rule("testa", "skull crusher", to = "triceps_testa", pattern = "elbow_extension"),
        rule("dead bug", "inseto morto", to = "dead_bug", patterns = setOf("anti_extension", "trunk_flexion")),
        rule("roda abdominal", "ab wheel", to = "roda_abdominal", pattern = "anti_extension"),
        rule("lenhador", "woodchop", to = "lenhador", patterns = setOf("rotation", "anti_rotation")),
        rule("mala", "suitcase", to = "mala", pattern = "carry"),
        rule("ponte unilateral", "single leg glute bridge", to = "ponte_unilateral", pattern = "hip_extension"),
    )

    /** Para padrões desconhecidos (o banco ganhou um padrão novo): adivinha pelo nome. */
    private val keywords: List<Pair<List<String>, String>> = listOf(
        listOf("hip thrust", "elevacao pelvica") to "hip_thrust_barra",
        listOf("ponte") to "ponte",
        listOf("extensao de quadril") to "coice_polia",
        listOf("abdutora", "abducao", "abduction") to "abdutora",
        listOf("adutora", "aducao", "adduction") to "adutora",
        listOf("concha", "clamshell") to "concha",
        listOf("sumo") to "agachamento_sumo",
        listOf("leg press") to "leg_press",
        listOf("stiff", "romeno", "rdl", "good morning", "bom dia") to "terra_romeno",
        listOf("terra", "deadlift") to "terra",
        listOf("agachamento", "squat") to "agachamento_livre",
        listOf("extensora") to "extensora",
        listOf("flexora", "leg curl") to "flexora_cadeira",
        listOf("panturrilha", "calf") to "panturrilha_maquina",
        listOf("afundo", "passada", "lunge") to "afundo",
        listOf("step", "subida") to "subida_banco",
        listOf("voador", "peck deck", "pec deck") to "voador",
        listOf("crucifixo inverso", "reverse fly") to "crucifixo_inverso_halteres",
        listOf("crossover", "crucifixo na polia") to "crucifixo_polia",
        listOf("crucifixo", "fly") to "crucifixo_halteres",
        listOf("elevacao lateral", "lateral raise", "elevacao frontal", "front raise") to "elevacao_lateral",
        listOf("encolhimento", "shrug") to "fazendeiro",
        listOf("supino", "bench press") to "supino_barra",
        listOf("flexao de bracos", "push up", "push-up") to "flexao",
        listOf("desenvolvimento", "overhead press") to "desenvolvimento_halteres",
        listOf("barra fixa", "pull-up", "pull up", "chin up") to "barra_fixa",
        listOf("puxada", "pulldown") to "puxada",
        listOf("remada", "row") to "remada_curvada",
        listOf("rosca", "curl") to "rosca_halteres",
        listOf("frances", "overhead triceps") to "triceps_frances_halter",
        listOf("triceps") to "triceps_polia",
        listOf("mergulho no banco", "bench dip") to "mergulho_banco",
        listOf("paralela", "dip") to "paralelas",
        listOf("prancha lateral", "side plank") to "prancha_lateral",
        listOf("prancha", "plank") to "prancha",
        listOf("abdominal", "crunch") to "abdominal",
        listOf("elevacao de pernas", "elevacao de joelhos", "leg raise", "knee raise") to "elevacao_joelhos",
        listOf("fazendeiro", "farmer", "carregamento", "carry") to "fazendeiro",
    )

    fun motionFor(e: Exercise): Motion {
        val id = motionId(e.id.value, e.name, e.aliases, e.pattern.value, e.loadType.name, e.equipment.map { it.value }.toSet())
        return MotionCatalog.find(id) ?: MotionCatalog.get(GENERIC)
    }

    /** Todos os movimentos que as regras por nome podem devolver (o teste confere que existem). */
    internal val ruleTargets: Set<String> get() = (refinements.map { it.motion } + keywords.map { it.second }).toSet()

    /** Versão só com textos (testável sem o banco). */
    fun motionId(id: String, name: String, aliases: List<String>, pattern: String, loadType: String, equipment: Set<String>): String {
        explicit[id]?.let { return it }
        val text = (listOf(id.replace('_', ' '), name) + aliases).joinToString(" | ") { normalize(it) }
        fun has(words: List<String>) = words.any { w -> containsWord(text, w) }
        val byPattern = byPattern(pattern, loadType, equipment)
        // Padrão conhecido: só as regras compatíveis com ele; desconhecido: todas.
        val known = byPattern != GENERIC
        refinements.firstOrNull { (!known || pattern in it.patterns) && (it.loads == null || loadType in it.loads) && has(it.words) }?.let { return it.motion }
        if (known) return byPattern
        return keywords.firstOrNull { has(it.first) }?.second ?: GENERIC
    }

    /** Padrão de movimento + carga + equipamento (exercícios futuros sem palavra-chave conhecida). */
    fun byPattern(pattern: String, loadType: String, equipment: Set<String>): String {
        val band = loadType == "BAND"
        val cable = loadType == "CABLE"
        val machine = loadType == "MACHINE"
        val dumbbell = loadType == "DUMBBELL" || loadType == "KETTLEBELL"
        val body = loadType == "BODYWEIGHT"
        return when (pattern) {
            "squat" -> when {
                "leg_press" in equipment -> "leg_press"
                loadType == "SMITH" -> "agachamento_smith"
                machine -> "agachamento_hack"
                dumbbell -> "agachamento_goblet"
                body || band -> "agachamento_livre"
                else -> "agachamento_barra"
            }
            "lunge" -> when {
                "step_box" in equipment -> "subida_banco"
                "flat_bench" in equipment -> "bulgaro"
                body || band -> "afundo_livre"
                else -> "afundo"
            }
            "hinge" -> when {
                loadType == "KETTLEBELL" -> "swing"
                cable -> "pull_through"
                machine -> "extensao_lombar"
                dumbbell -> "stiff_halteres"
                body || band -> "stiff_unilateral_livre"
                else -> "terra_romeno"
            }
            "hip_extension" -> when {
                "hip_thrust_machine" in equipment -> "hip_thrust_maquina"
                "glute_machine" in equipment -> "coice_maquina"
                loadType == "SMITH" || "smith_machine" in equipment -> "hip_thrust_smith"
                machine -> "hip_thrust_maquina"
                cable -> "coice_polia"
                body || band -> if ("mini_band" in equipment) "ponte_mini_band" else "ponte"
                dumbbell -> "hip_thrust_halter"
                else -> "hip_thrust_barra"
            }
            "knee_flexion" -> when {
                "swiss_ball" in equipment -> "flexora_bola"
                band -> "flexora_elastico"
                body -> "flexora_deslizante"
                else -> "flexora_cadeira"
            }
            "knee_extension" -> if (body || band) "sissy" else "extensora"
            "calf_raise" -> when {
                "leg_press" in equipment -> "panturrilha_leg_press"
                machine || loadType == "SMITH" -> "panturrilha_maquina"
                dumbbell -> "panturrilha_halter"
                else -> "panturrilha_livre"
            }
            "hip_abduction" -> when {
                machine -> "abdutora"
                cable -> "abducao_polia"
                band -> "caminhada_lateral"
                else -> "abducao_em_pe"
            }
            "hip_adduction" -> "adutora"
            "horizontal_push" -> when {
                band -> "supino_elastico"
                body -> if ("dip_station" in equipment) "paralelas" else "flexao"
                machine -> "supino_maquina"
                loadType == "SMITH" -> "supino_smith"
                dumbbell -> if ("adjustable_bench" in equipment) "supino_inclinado_halteres" else "supino_halteres"
                else -> if ("adjustable_bench" in equipment) "supino_inclinado_barra" else "supino_barra"
            }
            "horizontal_adduction" -> when {
                machine -> "voador"
                cable || band -> "crucifixo_polia"
                else -> "crucifixo_halteres"
            }
            "vertical_push" -> when {
                band -> "desenvolvimento_elastico"
                body -> "flexao_pike"
                machine -> "desenvolvimento_maquina"
                dumbbell -> "desenvolvimento_halteres"
                else -> "desenvolvimento_barra"
            }
            "shoulder_abduction" -> when {
                cable -> "elevacao_lateral_polia"
                band -> "elevacao_lateral_elastico"
                else -> "elevacao_lateral"
            }
            "horizontal_abduction" -> when {
                machine -> "crucifixo_inverso_maquina"
                cable -> "face_pull"
                band -> "pull_apart"
                body -> "elevacao_t"
                else -> "crucifixo_inverso_halteres"
            }
            "horizontal_pull" -> when {
                cable -> "remada_sentada_polia"
                machine -> "remada_maquina"
                band -> "remada_elastico"
                body -> if ("trx" in equipment) "remada_invertida_trx" else if ("low_bar" in equipment) "remada_invertida_barra" else "remada_toalha"
                dumbbell -> if ("adjustable_bench" in equipment) "remada_apoiada" else "remada_unilateral"
                else -> "remada_curvada"
            }
            "vertical_pull" -> when {
                "assisted_pull_up_machine" in equipment -> "barra_fixa_maquina"
                cable -> "pulldown_bracos_estendidos"
                band -> if ("pull_up_bar" in equipment) "barra_fixa_assistida" else "puxada_elastico"
                machine -> "puxada"
                else -> "barra_fixa"
            }
            "elbow_flexion" -> when {
                cable -> "rosca_polia"
                band -> "rosca_elastico"
                loadType == "BARBELL" -> "rosca_barra"
                else -> "rosca_halteres"
            }
            "elbow_extension" -> when {
                cable -> "triceps_polia"
                band -> "triceps_elastico"
                body -> "mergulho_banco"
                loadType == "BARBELL" -> "triceps_testa"
                else -> "triceps_frances_halter"
            }
            "anti_extension" -> if ("ab_wheel" in equipment) "roda_abdominal" else "prancha"
            "anti_rotation" -> when {
                cable -> "pallof_polia"
                band -> "pallof_elastico"
                else -> "prancha_lateral"
            }
            "trunk_flexion" -> when {
                "swiss_ball" in equipment -> "abdominal_bola"
                cable -> "abdominal_polia"
                "pull_up_bar" in equipment -> "elevacao_joelhos"
                else -> "abdominal"
            }
            "rotation" -> "lenhador"
            "carry" -> "fazendeiro"
            else -> GENERIC
        }
    }

    /** Pose neutra para quando nada mais se aplica. */
    const val GENERIC = "em_pe"

    /** [w] aparece em [text] como palavra inteira ("chin" não casa com "machine"). */
    private fun containsWord(text: String, w: String): Boolean {
        var i = text.indexOf(w)
        while (i >= 0) {
            val before = i == 0 || !text[i - 1].isLetterOrDigit()
            val end = i + w.length
            val after = end >= text.length || !text[end].isLetterOrDigit()
            if (before && after) return true
            i = text.indexOf(w, i + 1)
        }
        return false
    }

    private fun normalize(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
}
