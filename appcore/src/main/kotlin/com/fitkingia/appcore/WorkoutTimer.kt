package com.fitkingia.appcore

import com.fitkingia.core.model.ExperienceLevel

/**
 * Cronômetro do descanso entre séries, guardado como HORÁRIO DE TÉRMINO num relógio monotônico
 * em milissegundos (no app, SystemClock.elapsedRealtime). O tempo que falta é sempre calculado
 * do relógio: se a tela ficar coberta (ex.: "Como fazer"), um tique atrasar ou o celular dormir,
 * o descanso continua certo e termina na hora.
 */
class RestTimer {
    /** Fim do descanso (ms no relógio de quem chama); 0 = sem descanso. */
    var endsAt: Long = 0L
        private set
    /** Duração usada para desenhar o anel (cresce com o "+15 s"). */
    var total: Int = 0
        private set

    val active: Boolean get() = endsAt != 0L

    fun start(seconds: Int, now: Long) {
        endsAt = now + seconds.coerceAtLeast(1) * 1000L
        total = seconds.coerceAtLeast(1)
    }

    fun stop() {
        endsAt = 0L
        total = 0
    }

    /** Segundos que faltam, arredondados para cima (0 = sem descanso ou já acabou). */
    fun left(now: Long): Int {
        if (!active) return 0
        val ms = endsAt - now
        return if (ms <= 0) 0 else ((ms + 999) / 1000).toInt()
    }

    fun finished(now: Long): Boolean = active && endsAt <= now

    /** "+15 s" / "−15 s". Encurtar nunca encerra o descanso pelo ajuste: fica pelo menos 1 s. */
    fun adjust(seconds: Int, now: Long) {
        if (!active) return
        endsAt = maxOf(endsAt + seconds * 1000L, now + 1000L)
        total = maxOf(total, left(now))
    }

    /** Quanto esperar até o número mostrado mudar (a próxima virada de segundo, ou o fim). */
    fun nextTickIn(now: Long): Long {
        if (!active) return 1000L
        val ms = endsAt - now
        if (ms <= 0) return 0L
        val rest = ms % 1000
        return if (rest == 0L) 1000L else rest
    }
}

/** Como a tela de treino se apresenta para cada perfil (só exibição; nada de prescrição). */
object WorkoutDisplay {
    /**
     * Quem nunca treinou ou treina há menos de 3 meses vê a ilustração de cada exercício já aberta
     * (dá para esconder); os demais abrem quando quiserem em "Ver o movimento".
     */
    fun figureOpenByDefault(level: ExperienceLevel?): Boolean =
        level == ExperienceLevel.NONE || level == ExperienceLevel.UNDER_3_MONTHS
}
