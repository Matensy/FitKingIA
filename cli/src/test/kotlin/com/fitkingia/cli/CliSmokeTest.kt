package com.fitkingia.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertTrue

class CliSmokeTest {
    private fun run(vararg args: String): String {
        System.setProperty("fitking.today", "2026-10-02") // sexta-feira: dia de treino no perfil de exemplo
        val buf = ByteArrayOutputStream()
        val old = System.out
        System.setOut(PrintStream(buf, true, Charsets.UTF_8))
        try { main(arrayOf(*args)) } finally { System.setOut(old) }
        return buf.toString(Charsets.UTF_8)
    }

    @Test fun demoRunsEndToEnd() {
        val out = run("demo")
        listOf("PROGRAMA", "TREINO RÁPIDO", "Por que", "Substituições", "Índice de recuperação", "SIMULADOR",
            "PROGRESSIVE OVERLOAD", "Refeição registrada", "IA LOCAL").forEach { assertTrue(it in out, "faltou '$it'") }
    }

    @Test fun localCoachAnswersAQuestion() {
        assertTrue("Índice de recuperação" in run("pergunta", "hoje", "estou", "cansado"))
    }
}
