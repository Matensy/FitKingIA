package com.fitkingia.appcore

/**
 * Textos gravados pela versão 0.1.0 (nomes de sessão como "Push (empurrar)" e "Legs A", e as explicações
 * do programa e da semana que os citam) → português. Só traduz o texto: o conteúdo salvo continua o mesmo
 * (não troca pelo nome do modelo atual, que pode descrever outro treino).
 */
object LegacyNames {
    private val phrases = listOf(
        "Push (empurrar)" to "Empurrar", "Pull (puxar)" to "Puxar", "Legs (pernas)" to "Pernas", "Full Body" to "Corpo inteiro",
        "Recovery Score" to "Índice de recuperação", "QUICK SESSION" to "TREINO RÁPIDO", "NOVO PR" to "NOVO RECORDE",
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
