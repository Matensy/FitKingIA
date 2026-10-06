package com.fitkingia.app.figure

import com.fitkingia.core.knowledge.Exercise
import java.text.Normalizer
import java.util.Locale

/**
 * Qual ilustração mostrar para cada exercício do banco.
 *
 * 1. Mapa explícito por id (os 125 exercícios de exercises.json na data deste arquivo).
 * 2. Exercícios novos: variações reconhecidas pelo nome que o padrão não distingue
 *    (ex.: "mesa flexora", "coice", "búlgaro").
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
        "machine_hip_thrust" to "hip_thrust_barra",
        "smith_hip_thrust" to "hip_thrust_barra",
        "machine_glute_kickback" to "coice_polia",
        "cable_glute_kickback" to "coice_polia",
        "ankle_weight_kickback" to "coice_quatro_apoios",
        "cable_hip_abduction" to "abducao_polia",
        "side_lying_hip_abduction" to "abducao_deitado",
        // Concha: não há ilustração própria; a abdução deitado de lado é a mais próxima.
        "mini_band_clamshell" to "abducao_deitado",
        "mini_band_glute_bridge" to "ponte",
        "dumbbell_sumo_squat" to "agachamento_goblet",
        "seated_calf_raise" to "panturrilha_sentado",
        "t_bar_row" to "remada_curvada",
        "machine_assisted_pull_up" to "barra_fixa",
        "preacher_curl" to "rosca_scott",
        "swiss_ball_leg_curl" to "flexora_deslizante",
        "decline_crunch" to "abdominal_declinado",
        "swiss_ball_crunch" to "abdominal",
    )

    /**
     * Variações que o padrão de movimento sozinho não distingue (ex.: mesa × cadeira flexora).
     * Palavras sem acento, minúsculas, casadas como palavra inteira. Ordem importa.
     */
    private val refinements: List<Pair<List<String>, String>> = listOf(
        listOf("mesa flexora", "flexora deitado", "flexora deitada", "lying leg curl") to "flexora_mesa",
        listOf("quatro apoios", "quadruped", "donkey kick") to "coice_quatro_apoios",
        listOf("coice", "kickback", "glute kickback") to "coice_polia",
        // Concha (clamshell) usa a abdução deitado de lado: a mais próxima entre as ilustrações.
        listOf("deitado de lado", "side lying", "concha", "clamshell") to "abducao_deitado",
        listOf("abducao em pe", "abducao de quadril em pe", "standing abduction") to "abducao_em_pe",
        listOf("panturrilha sentado", "seated calf") to "panturrilha_sentado",
        listOf("scott", "preacher") to "rosca_scott",
        listOf("declinado", "decline") to "abdominal_declinado",
        listOf("cavalinho", "t-bar", "t bar") to "remada_curvada",
        listOf("barra fixa", "pull up", "pull-up", "chin up") to "barra_fixa",
        listOf("nordica", "nordic") to "flexao_nordica",
        listOf("bulgaro", "bulgarian") to "bulgaro",
        listOf("agachamento frontal", "front squat") to "agachamento_frontal",
        listOf("hack") to "agachamento_hack",
        listOf("pull through", "pull-through") to "pull_through",
        listOf("swing") to "swing",
        listOf("face pull") to "face_pull",
        listOf("serrote") to "remada_unilateral",
        listOf("pike") to "flexao_pike",
        listOf("diamante", "diamond") to "flexao",
        listOf("testa", "skull crusher") to "triceps_testa",
        listOf("dead bug") to "dead_bug",
        listOf("roda abdominal", "ab wheel") to "roda_abdominal",
        listOf("lenhador", "woodchop") to "lenhador",
        listOf("mala", "suitcase") to "mala",
        listOf("ponte unilateral", "single leg glute bridge") to "ponte_unilateral",
    )

    /** Para padrões desconhecidos (o banco ganhou um padrão novo): adivinha pelo nome. */
    private val keywords: List<Pair<List<String>, String>> = listOf(
        listOf("hip thrust", "elevacao pelvica") to "hip_thrust_barra",
        listOf("ponte") to "ponte",
        listOf("extensao de quadril") to "coice_polia",
        listOf("abdutora", "abducao", "abduction") to "abdutora",
        listOf("adutora", "aducao", "adduction") to "adutora",
        listOf("sumo") to "agachamento_goblet",
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
    internal val ruleTargets: Set<String> get() = (refinements + keywords).map { it.second }.toSet()

    /** Versão só com textos (testável sem o banco). */
    fun motionId(id: String, name: String, aliases: List<String>, pattern: String, loadType: String, equipment: Set<String>): String {
        explicit[id]?.let { return it }
        val text = (listOf(id.replace('_', ' '), name) + aliases).joinToString(" | ") { normalize(it) }
        fun match(rules: List<Pair<List<String>, String>>) = rules.firstOrNull { (words, _) -> words.any { w -> containsWord(text, w) } }?.second
        match(refinements)?.let { return it }
        val byPattern = byPattern(pattern, loadType, equipment)
        if (byPattern != GENERIC) return byPattern
        return match(keywords) ?: GENERIC
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
                cable -> "coice_polia"
                body || band -> "ponte"
                dumbbell -> "hip_thrust_halter"
                else -> "hip_thrust_barra"
            }
            "knee_flexion" -> when {
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
