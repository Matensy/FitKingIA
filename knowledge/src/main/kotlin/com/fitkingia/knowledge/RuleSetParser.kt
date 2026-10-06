package com.fitkingia.knowledge

import com.fitkingia.core.knowledge.*
import com.fitkingia.core.model.*
import kotlinx.serialization.json.*

/** Converte `rules.params_json` nos parâmetros tipados de [RuleSet]. Falha cedo se faltar algo. */
object RuleSetParser {

    fun parse(params: Map<String, JsonObject>): RuleSet {
        fun p(id: String) = params[id] ?: error("Regra obrigatória ausente no banco: $id")
        fun <T> rule(id: String, f: (JsonObject) -> T) = Rule(RuleId(id), runCatching { f(p(id)) }.getOrElse {
            throw IllegalStateException("Parâmetros inválidos na regra $id: ${it.message}", it)
        })
        return RuleSet(
            volume = rule("volume.weekly_sets") { o ->
                VolumeRules(
                    targets = o.obj("targets").entries.associate { (focus, byTier) ->
                        TrainingFocus.valueOf(focus) to byTier.jsonObject.entries.associate { (tier, arr) ->
                            val a = arr.jsonArray.map { it.jsonPrimitive.double }
                            require(a.size == 3 && a[0] <= a[1] && a[1] <= a[2]) { "meta $focus/$tier deve ser [min, alvo, max]" }
                            TrainingTier.valueOf(tier) to VolumeTarget(a[0], a[1], a[2])
                        }
                    },
                    primaryCredit = o.dbl("primary_credit"),
                    secondaryCredit = o.dbl("secondary_credit"),
                    minSetsPerExercise = o.int("min_sets_per_exercise"),
                    maxSetsPerExercise = o.obj("max_sets_per_exercise").entries.associate { Mechanic.valueOf(it.key) to it.value.jsonPrimitive.int },
                )
            },
            prescription = rule("prescription.reps_rir_rest") { o ->
                PrescriptionRules(
                    byFocus = o.obj("by_focus").entries.associate { (focus, roles) ->
                        TrainingFocus.valueOf(focus) to roles.jsonObject.entries.associate { (role, v) ->
                            val r = v.jsonObject
                            SlotRole.valueOf(role) to RepPrescription(r.range("reps"), r.int("rir"), r.range("rest"), r.str("tempo"))
                        }
                    },
                    noviceExtraRir = o.int("novice_extra_rir"),
                    timedHoldSeconds = o.range("timed_hold_seconds"),
                )
            },
            frequency = rule("frequency.days") { o ->
                FrequencyRules(o.obj("max_days_by_tier").entries.associate { TrainingTier.valueOf(it.key) to it.value.jsonPrimitive.int }, o.int("min_session_minutes"))
            },
            timing = rule("timing.session_clock") { o ->
                TimingRules(o.int("general_warmup_minutes"), o.int("ramp_up_seconds"), o.int("work_seconds_per_set"),
                    o.obj("setup_seconds").entries.associate { LoadType.valueOf(it.key) to it.value.jsonPrimitive.int })
            },
            progression = rule("progression.double") { o ->
                ProgressionRules(
                    o.obj("increments").entries.associate { LoadType.valueOf(it.key) to it.value.jsonPrimitive.double },
                    o.int("failed_sessions_before_reduction"), o.dbl("reduction_fraction"), o.int("easy_rir_margin"), o.int("max_reps_for_e1rm"),
                )
            },
            recovery = rule("recovery.readiness_score") { o ->
                val w = o.obj("weights"); val t = o.obj("thresholds")
                RecoveryRules(w.dbl("sleep"), w.dbl("energy"), w.dbl("soreness"), w.dbl("stress"), w.dbl("motivation"),
                    o.obj("sleep_scores").entries.associate { SleepQuality.valueOf(it.key) to it.value.jsonPrimitive.double },
                    t.int("normal"), t.int("reduced"), t.int("low"))
            },
            fatigue = rule("fatigue.model") { o ->
                FatigueRules(o.dbl("half_life_hours"), o.dbl("points_per_hard_set"), o.int("hard_set_max_rir"), o.dbl("easy_set_factor"), o.dbl("sport_points_per_level"))
            },
            scheduling = rule("scheduling.week") { o ->
                SchedulingRules(o.dbl("consecutive_overlap_weight"), o.dbl("sport_same_day_weight"), o.dbl("sport_adjacent_weight"),
                    o.dbl("over_time_weight"), o.dbl("order_deviation_weight"))
            },
            hydration = rule("hydration.target") { o ->
                HydrationRules(o.dbl("ml_per_kg"), o.obj("sweat_ml_per_hour").entries.associate { SweatLevel.valueOf(it.key) to it.value.jsonPrimitive.int },
                    o.dbl("hot_multiplier"), o.int("round_to_ml"))
            },
            nutrition = rule("nutrition.targets") { o ->
                val pr = o.obj("protein_g_per_kg"); val loss = o.obj("weekly_loss_pct")
                NutritionRules(
                    o.obj("activity_factors").entries.associate { ActivityLevel.valueOf(it.key) to it.value.jsonPrimitive.double },
                    o.obj("energy_adjustment").entries.associate { EnergyGoal.valueOf(it.key) to it.value.jsonPrimitive.double },
                    pr.dbl("min"), pr.dbl("target"), pr.dbl("max"), loss.dbl("min"), loss.dbl("max"),
                )
            },
            deload = rule("deload.advisor") { o ->
                DeloadRules(o.int("declining_sessions"), o.dbl("min_share_of_main_lifts"), o.int("low_readiness_threshold"), o.dbl("volume_reduction"))
            },
            weightTrend = rule("body.weight_trend") { o -> WeightTrendRules(o.int("moving_average_days"), o.dbl("notable_daily_change_kg")) },
            bodyMetrics = rule("body.metrics") { o ->
                BodyMetricRules(
                    o.getValue("bmi_bands").jsonArray.map { val a = it.jsonArray; BmiBand(a[0].jsonPrimitive.double, a[1].jsonPrimitive.content) },
                    o.dbl("whtr_increased"), o.dbl("whtr_high"),
                )
            },
            gamification = rule("gamification.xp") { o ->
                GamificationRules(o.obj("points").entries.associate { it.key to it.value.jsonPrimitive.int }, o.int("xp_per_level"))
            },
            priority = rule("priority.region") { o ->
                val pr = o.obj("priority"); val ot = o.obj("other"); val f = o.obj("min_frequency"); val sel = o.obj("selection")
                fun days(x: JsonObject) = x.entries.associate { it.key.toInt() to it.value.jsonPrimitive.int }
                PriorityRules(
                    pr.dbl("min_of_target"), pr.dbl("target_factor"), pr.dbl("max_factor"),
                    ot.dbl("min_factor"), ot.dbl("target_factor"), ot.dbl("max_factor"),
                    o.obj("synergists").entries.associate { (r, arr) -> BodyRegion.valueOf(r) to arr.jsonArray.map { MuscleId(it.jsonPrimitive.content) }.toSet() },
                    o.getValue("no_reduction_for").jsonArray.map { BodyRegion.valueOf(it.jsonPrimitive.content) }.toSet(),
                    days(f.obj("lower")), days(f.obj("other")),
                    o.int("leading_positions"), sel.dbl("focus_bonus"), sel.dbl("primary_bonus"),
                    o.dbl("min_focused_of_target"),
                    o.obj("min_share_of_half").entries.associate { BodyRegion.valueOf(it.key) to it.value.jsonPrimitive.double },
                )
            },
        )
    }

    private fun JsonObject.obj(k: String) = getValue(k).jsonObject
    private fun JsonObject.dbl(k: String) = getValue(k).jsonPrimitive.double
    private fun JsonObject.int(k: String) = getValue(k).jsonPrimitive.int
    private fun JsonObject.str(k: String) = getValue(k).jsonPrimitive.content
    private fun JsonObject.range(k: String): IntRange {
        val a = getValue(k).jsonArray.map { it.jsonPrimitive.int }
        require(a.size == 2 && a[0] <= a[1]) { "$k deve ser [min, max]" }
        return a[0]..a[1]
    }
}
