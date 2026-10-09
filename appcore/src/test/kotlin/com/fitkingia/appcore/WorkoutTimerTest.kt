package com.fitkingia.appcore

import com.fitkingia.core.model.ExperienceLevel
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkoutTimerTest {

    @Test fun restCountsFromTheClockNotFromTicks() {
        val t = RestTimer()
        assertFalse(t.active)
        assertEquals(0, t.left(0))
        t.start(120, now = 10_000)
        assertEquals(120, t.left(10_000))
        assertEquals(120, t.left(10_001)) // arredonda para cima: 119,999 s ainda mostra 2:00
        assertEquals(119, t.left(11_000))
        // Nenhum tique durante 15 s (tela coberta): o tempo continua certo ao voltar.
        assertEquals(105, t.left(25_000))
        assertFalse(t.finished(129_999))
        assertEquals(1, t.left(129_999))
        assertTrue(t.finished(130_000))
        assertEquals(0, t.left(131_000))
    }

    @Test fun nextTickLandsOnTheNextSecondBoundary() {
        val t = RestTimer()
        t.start(90, now = 0)
        assertEquals(1000, t.nextTickIn(0))
        assertEquals(500, t.nextTickIn(500))
        assertEquals(89, t.left(1000))
        assertEquals(0, t.nextTickIn(90_000))
        assertEquals(0, t.nextTickIn(95_000))
    }

    @Test fun adjustMovesTheEndAndNeverFinishesTheRest() {
        val t = RestTimer()
        t.start(60, now = 0)
        t.adjust(15, now = 10_000)
        assertEquals(65, t.left(10_000))
        assertEquals(65, t.total) // o anel passa a usar a duração maior
        t.adjust(-15, now = 10_000)
        assertEquals(50, t.left(10_000))
        t.adjust(-15, now = 59_000) // falta 1 s: −15 s deixa 1 s, não encerra
        assertEquals(1, t.left(59_000))
        assertFalse(t.finished(59_000))
        t.stop()
        assertFalse(t.active)
        t.adjust(15, now = 0) // sem descanso, nada muda
        assertFalse(t.active)
    }

    @Test fun beginnersSeeTheFigureOpen() {
        assertTrue(WorkoutDisplay.figureOpenByDefault(ExperienceLevel.NONE))
        assertTrue(WorkoutDisplay.figureOpenByDefault(ExperienceLevel.UNDER_3_MONTHS))
        assertFalse(WorkoutDisplay.figureOpenByDefault(ExperienceLevel.MONTHS_3_TO_6))
        assertFalse(WorkoutDisplay.figureOpenByDefault(ExperienceLevel.YEARS_2_PLUS))
        assertFalse(WorkoutDisplay.figureOpenByDefault(null))
    }
}
