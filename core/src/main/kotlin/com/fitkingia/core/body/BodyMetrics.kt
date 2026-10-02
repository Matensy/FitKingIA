package com.fitkingia.core.body

import com.fitkingia.core.explain.Explanation
import com.fitkingia.core.knowledge.BodyMetricRules
import com.fitkingia.core.knowledge.Rule
import com.fitkingia.core.knowledge.WeightTrendRules
import com.fitkingia.core.model.Fmt
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

data class BodyMeasurement(
    val date: LocalDate,
    val weightKg: Double? = null,
    val waistCm: Double? = null,
    val abdomenCm: Double? = null,
    val hipCm: Double? = null,
    val chestCm: Double? = null,
    val armLeftCm: Double? = null,
    val armRightCm: Double? = null,
    val thighLeftCm: Double? = null,
    val thighRightCm: Double? = null,
    val calfCm: Double? = null,
)

data class MetricResult(val value: Double, val label: String, val explanation: Explanation)

/** IMC e relação cintura/altura — calculados, explicados, nunca tratados como diagnóstico. */
class BodyMetrics(private val rule: Rule<BodyMetricRules>) {

    fun bmi(weightKg: Double, heightCm: Double): MetricResult {
        require(weightKg > 0 && heightCm > 0)
        val h = heightCm / 100
        val v = round1(weightKg / (h * h))
        val label = rule.params.bmiBands.first { v < it.upperExclusive }.label
        return MetricResult(v, label, Explanation.rule(
            "IMC ${fmt(v)} ($label, classificação da OMS para adultos). O IMC não distingue músculo de gordura " +
                "nem considera distribuição de gordura; é um indicador populacional, não diagnóstico.", rule.id,
        ))
    }

    fun waistToHeight(waistCm: Double, heightCm: Double): MetricResult {
        require(waistCm > 0 && heightCm > 0)
        val v = (waistCm / heightCm * 100).roundToInt() / 100.0
        val p = rule.params
        val label = when {
            v >= p.whtrHigh -> "elevada"
            v >= p.whtrIncreased -> "aumentada"
            else -> "abaixo do ponto de atenção"
        }
        return MetricResult(v, label, Explanation.rule(
            "Relação cintura/altura ${Fmt.fixed(v, 2)} ($label; ponto de atenção ≥ ${Fmt.num(p.whtrIncreased)}). " +
                "Indicador de triagem de risco cardiometabólico — converse com um profissional para interpretação individual.", rule.id,
        ))
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0
    private fun fmt(v: Double) = Fmt.fixed(v, 1)
}

data class WeightEntry(val date: LocalDate, val kg: Double)

data class FluctuationReport(
    val latestChangeKg: Double?,
    val movingAverage: List<Pair<LocalDate, Double>>,
    val weeklyRateKg: Double?,
    val weeklyRatePct: Double?,
    val messages: List<Explanation>,
)

/**
 * Body Fluctuation Tracker ("desinchar"): separa oscilação diária de tendência.
 * Nunca conclui "você ganhou 800 g de gordura" a partir de duas pesagens.
 */
class WeightTrend(private val rule: Rule<WeightTrendRules>) {

    fun analyze(entries: List<WeightEntry>): FluctuationReport {
        val sorted = entries.sortedBy { it.date }
        val msgs = mutableListOf<Explanation>()
        val n = rule.params.movingAverageDays
        val ma = sorted.indices.map { i ->
            val window = sorted.filter { !it.date.isBefore(sorted[i].date.minusDays(n - 1L)) && !it.date.isAfter(sorted[i].date) }
            sorted[i].date to window.map { it.kg }.average()
        }
        val change = if (sorted.size >= 2) sorted.last().kg - sorted[sorted.size - 2].kg else null
        if (change != null && abs(change) >= rule.params.notableDailyChangeKg) {
            val prev = sorted[sorted.size - 2]; val last = sorted.last()
            msgs += Explanation.rule(
                "Peso ${fmt(prev.kg)} kg (${prev.date}) → ${fmt(last.kg)} kg (${last.date}): ${sign(change)} kg. " +
                    "Variações de curto prazo costumam refletir água, glicogênio, sódio e conteúdo gastrointestinal; " +
                    "duas pesagens não permitem concluir ganho ou perda de gordura. Observe a média móvel de $n dias.",
                rule.id,
            )
        }
        var rate: Double? = null
        var ratePct: Double? = null
        val span = if (ma.size >= 2) java.time.temporal.ChronoUnit.DAYS.between(ma.first().first, ma.last().first) else 0
        if (span >= 14) {
            rate = (ma.last().second - ma.first().second) / span * 7
            ratePct = rate / ma.last().second * 100
            msgs += Explanation.rule(
                "Tendência (média móvel): ${sign(rate)} kg/semana (${sign(ratePct)}% do peso corporal por semana).", rule.id,
            )
        }
        return FluctuationReport(change, ma, rate, ratePct, msgs)
    }

    private fun fmt(v: Double) = Fmt.fixed(v, 1)
    private fun sign(v: Double) = Fmt.signed(v)
}
