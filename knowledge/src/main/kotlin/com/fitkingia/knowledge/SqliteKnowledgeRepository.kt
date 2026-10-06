package com.fitkingia.knowledge

import com.fitkingia.core.knowledge.*
import com.fitkingia.core.model.*
import com.fitkingia.knowledge.sql.JdbcSqlDatabase
import com.fitkingia.knowledge.sql.SqlDatabase
import com.fitkingia.knowledge.sql.SqlRow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.time.LocalDate

/** Carrega um fitness.db (arquivo) na JVM via JDBC. */
class SqliteKnowledgeRepository(private val dbFile: File) {
    fun load(): KnowledgeBase = JdbcSqlDatabase.open(dbFile).use { db ->
        db.execute("PRAGMA query_only = ON")
        KnowledgeReader(db).load()
    }
}

/**
 * fitness.db → [KnowledgeBase]. Depende só de [SqlDatabase]: o mesmo leitor roda na JVM (JDBC)
 * e no Android (SQLite do sistema).
 */
class KnowledgeReader(private val c: SqlDatabase) {

    fun load(): KnowledgeBase {
        val rules = query("SELECT * FROM rules ORDER BY id") { rs ->
            RuleMeta(RuleId(rs.str("id")), rs.str("category"), rs.str("description"),
                RuleBasis.valueOf(rs.str("basis")), rs.str("rationale"), emptyList(), LocalDate.parse(rs.str("last_reviewed")))
        }
        val ruleClaims = query("SELECT rule_id, claim_id FROM rule_claims ORDER BY rule_id, claim_id") { it.str("rule_id") to ClaimId(it.str("claim_id")) }
            .groupBy({ it.first }, { it.second })
        val params = query("SELECT id, params_json FROM rules") { it.str("id") to Json.parseToJsonElement(it.str("params_json")).jsonObject }.toMap()

        val texts = query("SELECT exercise_id, kind, text FROM exercise_texts ORDER BY exercise_id, kind, position") {
            Triple(it.str("exercise_id"), it.str("kind"), it.str("text"))
        }.groupBy { it.first }
        val aliases = multi("SELECT exercise_id AS k, alias AS v FROM exercise_aliases ORDER BY exercise_id, alias")
        val muscles = query("SELECT exercise_id, muscle_id, role FROM exercise_muscles ORDER BY exercise_id, muscle_id") {
            Triple(it.str("exercise_id"), MuscleId(it.str("muscle_id")), it.str("role"))
        }.groupBy { it.first }
        val equipment = multi("SELECT exercise_id AS k, equipment_id AS v FROM exercise_equipment ORDER BY exercise_id, equipment_id")
        val joints = query("SELECT exercise_id, joint, demand FROM exercise_joint_demand") {
            Triple(it.str("exercise_id"), Joint.valueOf(it.str("joint")), it.int("demand"))
        }.groupBy { it.first }
        val subs = multi("SELECT exercise_id AS k, substitute_id AS v FROM exercise_substitutions ORDER BY exercise_id, rank")
        val focus = multi("SELECT exercise_id AS k, muscle_id AS v FROM exercise_focus ORDER BY exercise_id, muscle_id")

        val exercises = query("SELECT * FROM exercises ORDER BY id") { rs ->
            val id = rs.str("id")
            val t = texts[id].orEmpty()
            fun kind(k: String) = t.filter { it.second == k }.map { it.third }
            Exercise(
                id = ExerciseId(id), name = rs.str("name"), aliases = aliases[id].orEmpty(),
                pattern = PatternId(rs.str("pattern_id")), mechanic = Mechanic.valueOf(rs.str("mechanic")),
                laterality = Laterality.valueOf(rs.str("laterality")), loadType = LoadType.valueOf(rs.str("load_type")),
                primaryMuscles = muscles[id].orEmpty().filter { it.third == "primary" }.map { it.second }.toSet(),
                secondaryMuscles = muscles[id].orEmpty().filter { it.third == "secondary" }.map { it.second }.toSet(),
                equipment = equipment[id].orEmpty().map(::EquipmentId).toSet(),
                difficulty = rs.int("difficulty"), minTier = TrainingTier.valueOf(rs.str("min_tier")),
                stability = rs.int("stability"), mobility = rs.int("mobility"),
                jointDemand = joints[id].orEmpty().associate { it.second to it.third },
                instructions = kind("instruction"), commonMistakes = kind("common_mistake"),
                safetyNotes = kind("safety_note"), progressionMethods = kind("progression_method"),
                curatedSubstitutes = subs[id].orEmpty().map(::ExerciseId),
                staple = rs.int("staple"),
                maxTier = rs.strOrNull("max_tier")?.let(TrainingTier::valueOf),
                timed = rs.int("timed") == 1,
                focusMuscles = focus[id].orEmpty().map(::MuscleId).toSet(),
            )
        }

        val related = multi("SELECT pattern_id AS k, related_id AS v FROM pattern_relations")
        val slots = query("SELECT * FROM session_slots ORDER BY split_id, session_position, position") { rs ->
            Triple(rs.str("split_id"), rs.int("session_position"), Slot(
                pattern = PatternId(rs.str("pattern_id")), role = SlotRole.valueOf(rs.str("role")),
                baseSets = rs.int("base_sets"), targetMuscle = rs.strOrNull("target_muscle_id")?.let(::MuscleId),
                preferMechanic = rs.strOrNull("prefer_mechanic")?.let(Mechanic::valueOf),
                preferLaterality = rs.strOrNull("prefer_laterality")?.let(Laterality::valueOf),
            ))
        }
        val sessions = query("SELECT * FROM session_templates ORDER BY split_id, position") { rs ->
            val split = rs.str("split_id"); val pos = rs.int("position")
            split to SessionTemplate(rs.str("key"), rs.str("name"),
                slots.filter { it.first == split && it.second == pos }.map { it.third })
        }.groupBy({ it.first }, { it.second })
        val focuses = multi("SELECT split_id AS k, focus AS v FROM split_focuses")
        val emphasis = multi("SELECT split_id AS k, region AS v FROM split_emphasis")
        val claimSources = query("SELECT * FROM claim_sources ORDER BY claim_id, source_id") { rs ->
            rs.str("claim_id") to ClaimSource(SourceId(rs.str("source_id")), Stance.valueOf(rs.str("stance")), rs.strOrNull("note"))
        }.groupBy({ it.first }, { it.second })

        return KnowledgeBase(
            muscles = query("SELECT * FROM muscles ORDER BY rowid") { rs ->
                Muscle(MuscleId(rs.str("id")), rs.str("name"), rs.str("region"), rs.int("volume_tracked") == 1,
                    rs.double("volume_factor"), rs.strOrNull("fill_pattern_id")?.let(::PatternId), rs.strOrNull("focus_region")?.let(BodyRegion::valueOf))
            },
            patterns = query("SELECT * FROM movement_patterns ORDER BY rowid") { rs ->
                val id = rs.str("id")
                MovementPattern(PatternId(id), rs.str("name"), rs.str("description"), related[id].orEmpty().map(::PatternId).toSet())
            },
            equipment = query("SELECT * FROM equipment ORDER BY rowid") { Equipment(EquipmentId(it.str("id")), it.str("name"), it.str("category")) },
            environments = run {
                val eq = multi("SELECT environment_id AS k, equipment_id AS v FROM environment_equipment")
                query("SELECT * FROM environments ORDER BY rowid") { rs ->
                    val id = rs.str("id")
                    TrainingEnvironment(EnvironmentId(id), rs.str("name"), eq[id].orEmpty().map(::EquipmentId).toSet())
                }
            },
            exercises = exercises,
            splits = query("SELECT * FROM split_templates ORDER BY days_per_week, priority DESC, id") { rs ->
                val id = rs.str("id")
                SplitTemplate(SplitId(id), rs.str("name"), rs.int("days_per_week"), TrainingTier.valueOf(rs.str("min_tier")),
                    focuses[id].orEmpty().map(TrainingFocus::valueOf).toSet(), rs.int("priority"), rs.str("rationale"), sessions[id].orEmpty(),
                    emphasis[id].orEmpty().map(BodyRegion::valueOf).toSet())
            },
            sports = query("SELECT * FROM sports ORDER BY rowid") { rs ->
                Sport(SportId(rs.str("id")), rs.str("name"), rs.int("lower_body_load"), rs.int("upper_body_load"),
                    rs.int("cardio_demand"), rs.int("recovery_demand"))
            },
            safetyQuestions = query("SELECT * FROM safety_questions ORDER BY position") { rs ->
                SafetyQuestion(rs.str("id"), rs.str("question"), rs.str("category"),
                    SafetyOutcome.valueOf(rs.str("outcome_if_yes")), rs.str("message"), rs.strOrNull("applies_to")?.let(Sex::valueOf))
            },
            foods = run {
                val fa = multi("SELECT food_id AS k, alias AS v FROM food_aliases")
                query("SELECT * FROM foods ORDER BY rowid") { rs ->
                    val id = rs.str("id")
                    Food(FoodId(id), rs.str("name"), fa[id].orEmpty(), rs.double("kcal"), rs.doubleOrNull("protein_g"),
                        rs.doubleOrNull("carbs_g"), rs.doubleOrNull("fat_g"), rs.doubleOrNull("fiber_g"), rs.doubleOrNull("sodium_mg"),
                        rs.double("default_serving_g"), rs.str("serving_label"), SourceId(rs.str("source_id")), rs.str("verification"))
                }
            },
            supplements = run {
                val ss = multi("SELECT supplement_id AS k, source_id AS v FROM supplement_sources")
                query("SELECT * FROM supplements ORDER BY rowid") { rs ->
                    val id = rs.str("id")
                    Supplement(SupplementId(id), rs.str("name"), rs.str("what_is"), rs.str("purpose"),
                        rs.str("evidence_summary"), rs.str("how_studied"), rs.str("known_effects"),
                        rs.str("limitations"), rs.str("cautions"), EvidenceLevel.valueOf(rs.str("evidence_level")),
                        ss[id].orEmpty().map(::SourceId))
                }
            },
            sources = query("SELECT * FROM evidence_sources ORDER BY rowid") { rs ->
                EvidenceSource(SourceId(rs.str("id")), rs.str("title"), rs.str("authors"), rs.strOrNull("organization"),
                    rs.intOrNull("year"), rs.strOrNull("venue"), rs.strOrNull("url"), rs.strOrNull("doi"),
                    SourceType.valueOf(rs.str("source_type")), SourceTier.valueOf(rs.str("tier")), rs.str("topic"),
                    rs.str("summary"), rs.strOrNull("last_verified")?.let(LocalDate::parse),
                    VerificationStatus.valueOf(rs.str("verification")), rs.str("verification_note"))
            },
            claims = query("SELECT * FROM claims ORDER BY rowid") { rs ->
                val id = rs.str("id")
                Claim(ClaimId(id), rs.str("statement"), rs.str("topic"), EvidenceLevel.valueOf(rs.str("evidence_level")),
                    claimSources[id].orEmpty(), LocalDate.parse(rs.str("last_reviewed")), ReviewStatus.valueOf(rs.str("review_status")))
            },
            rules = rules.map { it.copy(claimIds = ruleClaims[it.id.value].orEmpty()) },
            ruleSet = RuleSetParser.parse(params),
            meta = query("SELECT key, value FROM meta") { it.str("key") to it.str("value") }.toMap(),
        )
    }

    private fun <T> query(sql: String, map: (SqlRow) -> T): List<T> = c.query(sql, emptyList(), map)

    private fun multi(sql: String): Map<String, List<String>> =
        query(sql) { it.str("k") to it.str("v") }.groupBy({ it.first }, { it.second })
}
