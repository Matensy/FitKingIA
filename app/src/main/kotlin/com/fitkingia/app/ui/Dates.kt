package com.fitkingia.app.ui

import com.fitkingia.core.program.ptCapitalized
import java.time.LocalDate
import java.time.LocalDateTime

/** Datas em português sem depender dos dados de localidade do aparelho. */
object Dates {
    private val months = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")

    fun long(d: LocalDate): String = "${d.dayOfWeek.ptCapitalized()}, ${d.dayOfMonth} de ${months[d.monthValue - 1]}"
    fun short(d: LocalDate): String = "%02d/%02d".format(d.dayOfMonth, d.monthValue)
    fun dayShort(d: LocalDate): String = d.dayOfWeek.ptCapitalized().take(3)
    fun time(t: LocalDateTime): String = "%02d:%02d".format(t.hour, t.minute)
    fun relative(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "hoje"
        today.minusDays(1) -> "ontem"
        else -> short(d)
    }

    fun mmss(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
}
