package com.fitkingia.knowledge

import com.fitkingia.core.knowledge.*
import com.fitkingia.core.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.LocalDate

/** Carrega o fitness.db para o [KnowledgeBase] do domínio (adaptador JVM; no Android será Room). */
class SqliteKnowledgeRepository(private val dbFile: File) {

    fun load(): KnowledgeBase = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}").use { c ->
        c.createStatement().use { it.execute("PRAGMA query_only = ON") }
        val rules = query(c, "SELECT * FROM rules ORDER BY id") { rs ->
            RuleMeta(RuleId(rs.getString("id")), rs.getString("category"), rs.getString("description"),
                RuleBasis.valueOf(rs.getString("basis")), rs.getString("rationale"), emptyList(), LocalDate.parse(rs.getString("last_reviewed")))
        }
        val ruleClaims = query(c, "SELECT rule_id, claim_id FROM rule_claims ORDER BY rule_id, claim_id") { it.getString(1) to ClaimId(it.getString(2)) }
            .groupBy({ it.first }, { it.second })
        val params = query(c, "SELECT id, params_json FROM rules") { it.getString(1) to Json.parseToJsonElement(it.getString(2)).jsonObject }.toMap()

        val texts = query(c, "SELECT exercise_id, kind, text FROM exercise_texts ORDER BY exercise_id, kind, position") {
            Triple(it.getString(1), it.getString(2), it.getString(3))
        }.groupBy { it.first }
        val aliases = multi(c, "SELECT exercise_id, alias FROM exercise_aliases ORDER BY exercise_id, alias")
        val muscles = query(c, "SELECT exercise_id, muscle_id, role FROM exercise_muscles ORDER BY exercise_id, muscle_id") {
            Triple(it.getString(1), MuscleId(it.getString(2)), it.getString(3))
        }.groupBy { it.first }
        val equipment = multi(c, "SELECT exercise_id, equipment_id FROM exercise_equipment ORDER BY exercise_id, equipment_id")
        val joints = query(c, "SELECT exercise_id, joint, demand FROM exercise_joint_demand") {
            Triple(it.getString(1), Joint.valueOf(it.getString(2)), it.getInt(3))
        }.groupBy { it.first }
        val subs = multi(c, "SELECT exercise_id, substitute_id FROM exercise_substitutions ORDER BY exercise_id, rank")

        val exercises = query(c, "SELECT * FROM exercises ORDER BY id") { rs ->
            val id = rs.getString("id")
            val t = texts[id].orEmpty()
            fun kind(k: String) = t.filter { it.second == k }.map { it.third }
            Exercise(
                id = ExerciseId(id), name = rs.getString("name"), aliases = aliases[id].orEmpty(),
                pattern = PatternId(rs.getString("pattern_id")), mechanic = Mechanic.valueOf(rs.getString("mechanic")),
                laterality = Laterality.valueOf(rs.getString("laterality")), loadType = LoadType.valueOf(rs.getString("load_type")),
                primaryMuscles = muscles[id].orEmpty().filter { it.third == "primary" }.map { it.second }.toSet(),
                secondaryMuscles = muscles[id].orEmpty().filter { it.third == "secondary" }.map { it.second }.toSet(),
                equipment = equipment[id].orEmpty().map(::EquipmentId).toSet(),
                difficulty = rs.getInt("difficulty"), minTier = TrainingTier.valueOf(rs.getString("min_tier")),
                stability = rs.getInt("stability"), mobility = rs.getInt("mobility"),
                jointDemand = joints[id].orEmpty().associate { it.second to it.third },
                instructions = kind("instruction"), commonMistakes = kind("common_mistake"),
                safetyNotes = kind("safety_note"), progressionMethods = kind("progression_method"),
                curatedSubstitutes = subs[id].orEmpty().map(::ExerciseId),
                staple = rs.getInt("staple"),
                maxTier = rs.getString("max_tier")?.let(TrainingTier::valueOf),
                timed = rs.getInt("timed") == 1,
            )
        }

        val related = multi(c, "SELECT pattern_id, related_id FROM pattern_relations")
        val slots = query(c, "SELECT * FROM session_slots ORDER BY split_id, session_position, position") { rs ->
            Triple(rs.getString("split_id"), rs.getInt("session_position"), Slot(
                pattern = PatternId(rs.getString("pattern_id")), role = SlotRole.valueOf(rs.getString("role")),
                baseSets = rs.getInt("base_sets"), targetMuscle = rs.getString("target_muscle_id")?.let(::MuscleId),
                preferMechanic = rs.getString("prefer_mechanic")?.let(Mechanic::valueOf),
                preferLaterality = rs.getString("prefer_laterality")?.let(Laterality::valueOf),
            ))
        }
        val sessions = query(c, "SELECT * FROM session_templates ORDER BY split_id, position") { rs ->
            val split = rs.getString("split_id"); val pos = rs.getInt("position")
            split to SessionTemplate(rs.getString("key"), rs.getString("name"),
                slots.filter { it.first == split && it.second == pos }.map { it.third })
        }.groupBy({ it.first }, { it.second })
        val focuses = multi(c, "SELECT split_id, focus FROM split_focuses")
        val claimSources = query(c, "SELECT * FROM claim_sources ORDER BY claim_id, source_id") { rs ->
            rs.getString("claim_id") to ClaimSource(SourceId(rs.getString("source_id")), Stance.valueOf(rs.getString("stance")), rs.getString("note"))
        }.groupBy({ it.first }, { it.second })

        KnowledgeBase(
            muscles = query(c, "SELECT * FROM muscles ORDER BY rowid") { rs ->
                Muscle(MuscleId(rs.getString("id")), rs.getString("name"), rs.getString("region"), rs.getInt("volume_tracked") == 1,
                    rs.getDouble("volume_factor"), rs.getString("fill_pattern_id")?.let(::PatternId))
            },
            patterns = query(c, "SELECT * FROM movement_patterns ORDER BY rowid") { rs ->
                val id = rs.getString("id")
                MovementPattern(PatternId(id), rs.getString("name"), rs.getString("description"), related[id].orEmpty().map(::PatternId).toSet())
            },
            equipment = query(c, "SELECT * FROM equipment ORDER BY rowid") { Equipment(EquipmentId(it.getString("id")), it.getString("name"), it.getString("category")) },
            environments = run {
                val eq = multi(c, "SELECT environment_id, equipment_id FROM environment_equipment")
                query(c, "SELECT * FROM environments ORDER BY rowid") { rs ->
                    val id = rs.getString("id")
                    TrainingEnvironment(EnvironmentId(id), rs.getString("name"), eq[id].orEmpty().map(::EquipmentId).toSet())
                }
            },
            exercises = exercises,
            splits = query(c, "SELECT * FROM split_templates ORDER BY days_per_week, priority DESC, id") { rs ->
                val id = rs.getString("id")
                SplitTemplate(SplitId(id), rs.getString("name"), rs.getInt("days_per_week"), TrainingTier.valueOf(rs.getString("min_tier")),
                    focuses[id].orEmpty().map(TrainingFocus::valueOf).toSet(), rs.getInt("priority"), rs.getString("rationale"), sessions[id].orEmpty())
            },
            sports = query(c, "SELECT * FROM sports ORDER BY rowid") { rs ->
                Sport(SportId(rs.getString("id")), rs.getString("name"), rs.getInt("lower_body_load"), rs.getInt("upper_body_load"),
                    rs.getInt("cardio_demand"), rs.getInt("recovery_demand"))
            },
            safetyQuestions = query(c, "SELECT * FROM safety_questions ORDER BY position") { rs ->
                SafetyQuestion(rs.getString("id"), rs.getString("question"), rs.getString("category"),
                    SafetyOutcome.valueOf(rs.getString("outcome_if_yes")), rs.getString("message"), rs.getString("applies_to")?.let(Sex::valueOf))
            },
            foods = run {
                val fa = multi(c, "SELECT food_id, alias FROM food_aliases")
                query(c, "SELECT * FROM foods ORDER BY rowid") { rs ->
                    val id = rs.getString("id")
                    Food(FoodId(id), rs.getString("name"), fa[id].orEmpty(), rs.getDouble("kcal"), rs.dblOrNull("protein_g"),
                        rs.dblOrNull("carbs_g"), rs.dblOrNull("fat_g"), rs.dblOrNull("fiber_g"), rs.dblOrNull("sodium_mg"),
                        rs.getDouble("default_serving_g"), rs.getString("serving_label"), SourceId(rs.getString("source_id")), rs.getString("verification"))
                }
            },
            supplements = run {
                val ss = multi(c, "SELECT supplement_id, source_id FROM supplement_sources")
                query(c, "SELECT * FROM supplements ORDER BY rowid") { rs ->
                    val id = rs.getString("id")
                    Supplement(SupplementId(id), rs.getString("name"), rs.getString("what_is"), rs.getString("purpose"),
                        rs.getString("evidence_summary"), rs.getString("how_studied"), rs.getString("known_effects"),
                        rs.getString("limitations"), rs.getString("cautions"), EvidenceLevel.valueOf(rs.getString("evidence_level")),
                        ss[id].orEmpty().map(::SourceId))
                }
            },
            sources = query(c, "SELECT * FROM evidence_sources ORDER BY rowid") { rs ->
                EvidenceSource(SourceId(rs.getString("id")), rs.getString("title"), rs.getString("authors"), rs.getString("organization"),
                    rs.getObject("year")?.let { (it as Number).toInt() }, rs.getString("venue"), rs.getString("url"), rs.getString("doi"),
                    SourceType.valueOf(rs.getString("source_type")), SourceTier.valueOf(rs.getString("tier")), rs.getString("topic"),
                    rs.getString("summary"), rs.getString("last_verified")?.let(LocalDate::parse),
                    VerificationStatus.valueOf(rs.getString("verification")), rs.getString("verification_note"))
            },
            claims = query(c, "SELECT * FROM claims ORDER BY rowid") { rs ->
                val id = rs.getString("id")
                Claim(ClaimId(id), rs.getString("statement"), rs.getString("topic"), EvidenceLevel.valueOf(rs.getString("evidence_level")),
                    claimSources[id].orEmpty(), LocalDate.parse(rs.getString("last_reviewed")), ReviewStatus.valueOf(rs.getString("review_status")))
            },
            rules = rules.map { it.copy(claimIds = ruleClaims[it.id.value].orEmpty()) },
            ruleSet = RuleSetParser.parse(params),
            meta = query(c, "SELECT key, value FROM meta") { it.getString(1) to it.getString(2) }.toMap(),
        )
    }

    private fun <T> query(c: Connection, sql: String, map: (ResultSet) -> T): List<T> =
        c.createStatement().use { st -> st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } } }

    private fun multi(c: Connection, sql: String): Map<String, List<String>> =
        query(c, sql) { it.getString(1) to it.getString(2) }.groupBy({ it.first }, { it.second })

    private fun ResultSet.dblOrNull(col: String): Double? = getDouble(col).let { if (wasNull()) null else it }
}
