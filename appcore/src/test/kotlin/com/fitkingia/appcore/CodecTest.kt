package com.fitkingia.appcore

import com.fitkingia.core.program.ProgramResult
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class CodecTest {
    @Test fun `sessoes sobrevivem ida e volta em JSON`() {
        val env = TestEnv()
        val program = assertIs<ProgramResult.Generated>(env.app.submit(TestEnv.answers(kb = env.kb)).result).program
        val back = Codec.sessionsFrom(Codec.sessions(program.sessions), env.kb)
        assertEquals(program.sessions.map { it.copy(exercises = it.exercises.map { e -> e.copy(slot = null) }) }, back)
        val ex = Codec.explanationsFrom(Codec.explanations(program.explanations))
        assertEquals(program.explanations, ex)
    }
}
