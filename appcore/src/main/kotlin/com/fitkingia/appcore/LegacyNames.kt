package com.fitkingia.appcore

/**
 * Nomes de sessão gravados pela versão 0.1.0 (em inglês, ex.: "Push (empurrar)", "Legs A") → português.
 * Só traduz o rótulo: o conteúdo salvo continua o mesmo (não troca pelo nome do modelo atual, que pode
 * descrever outro treino).
 */
object LegacyNames {
    private val phrases = listOf(
        "Push (empurrar)" to "Empurrar", "Pull (puxar)" to "Puxar", "Legs (pernas)" to "Pernas", "Full Body" to "Corpo inteiro",
    )
    private val words = listOf("Push" to "Empurrar", "Pull" to "Puxar", "Legs" to "Pernas", "Upper" to "Superiores", "Lower" to "Inferiores")
        .map { (en, pt) -> Regex("\\b$en\\b") to pt }

    fun pt(name: String): String {
        var out = name
        for ((en, pt) in phrases) out = out.replace(en, pt)
        for ((re, pt) in words) out = re.replace(out, pt)
        return out
    }
}
