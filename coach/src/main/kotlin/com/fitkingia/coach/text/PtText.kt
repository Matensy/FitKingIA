package com.fitkingia.coach.text

import java.text.Normalizer

/**
 * Processamento de texto em português, sem dependências: normalização, tokens,
 * stopwords, stemmer leve e n-gramas de caracteres (tolerância a erros de digitação).
 */
object PtText {
    private val accents = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val nonWord = Regex("[^a-z0-9,.\\s/x]")

    /** Minúsculas, sem acento, sem pontuação estranha, espaços simples. Mantém vírgula/ponto de números. */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(accents, "")
            .replace(nonWord, " ")
            .replace(Regex("(?<!\\d)[,.]|[,.](?!\\d)"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    fun tokens(text: String): List<String> = normalize(text).split(' ').filter { it.isNotEmpty() }

    val STOPWORDS = setOf(
        "a", "o", "as", "os", "um", "uma", "uns", "umas", "de", "do", "da", "dos", "das", "em", "no", "na", "nos", "nas",
        "ao", "aos", "para", "pra", "pro", "pros", "pras", "por", "pelo", "pela", "com", "e", "ou", "que", "se", "me", "te",
        "eu", "voce", "vc", "ele", "ela", "isso", "isto", "esse", "essa", "este", "esta", "aquele", "aquela", "ja", "tb",
        "tambem", "mais", "muito", "muita", "bem", "la", "aqui", "entao", "ai", "ne", "ta", "to", "tou", "estou", "esta",
        "ser", "sou", "e", "é", "foi", "era", "tem", "ter", "tenho", "meu", "minha", "meus", "minhas", "seu", "sua",
        "qual", "quais", "como", "por favor", "favor", "algum", "alguma", "so", "pouco", "coisa",
    )

    /** Stemmer leve (inspirado no RSLP): corta sufixos comuns preservando radical ≥ 3 letras. */
    private val SUFFIXES = listOf(
        "amentos", "imentos", "amento", "imento", "mente", "acoes", "icoes", "acao", "icao", "ancia", "encia",
        "aveis", "iveis", "avel", "ivel", "issimo", "issima", "ismos", "ismo", "istas", "ista",
        "aram", "eram", "iram", "ariam", "eriam", "iriam", "assem", "essem", "issem",
        "ando", "endo", "indo", "ados", "adas", "idos", "idas", "ado", "ada", "ido", "ida",
        "aria", "eria", "iria", "avam", "ava", "ia", "ei", "ou", "amos", "emos", "imos",
        "ar", "er", "ir", "oes", "aes", "eis", "ais", "ns", "es", "as", "os", "is", "s", "a", "o", "e",
    )

    fun stem(token: String): String {
        if (token.length <= 3 || token.any { it.isDigit() }) return token
        for (suf in SUFFIXES) {
            if (token.endsWith(suf) && token.length - suf.length >= 3) return token.dropLast(suf.length)
        }
        return token
    }

    /** Tokens de conteúdo (sem stopwords), já reduzidos ao radical. */
    fun contentStems(text: String): List<String> = tokens(text).filter { it !in STOPWORDS }.map(::stem)

    /** Trigramas de caracteres com marcadores de borda: "joelho" → "<jo", "joe", ..., "ho>". */
    fun charGrams(token: String, n: Int = 3): List<String> {
        val t = "<$token>"
        if (t.length <= n) return listOf(t)
        return (0..t.length - n).map { t.substring(it, it + n) }
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }

    /**
     * Palavra parecida o bastante: exata até 5 letras (evita "terça" ≈ "terra"), 1 erro até 8, 2 nas longas
     * ("agaxamento" ≈ "agachamento").
     */
    fun similarWord(a: String, b: String): Boolean {
        if (a == b) return true
        val maxLen = maxOf(a.length, b.length)
        val allowed = when { maxLen <= 5 -> 0; maxLen <= 8 -> 1; else -> 2 }
        return kotlin.math.abs(a.length - b.length) <= allowed && levenshtein(a, b) <= allowed
    }
}
