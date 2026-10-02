package com.fitkingia.core.model

import java.util.Locale

/** Formatação numérica pt-BR para textos exibidos ao usuário. */
object Fmt {
    private val ptBR: Locale = Locale.forLanguageTag("pt-BR")

    /** 12 → "12"; 12.5 → "12,5"; 1.55 (2 casas) → "1,55". Zeros à direita são removidos. */
    fun num(v: Double, decimals: Int = 1): String {
        val s = String.format(ptBR, "%.${decimals}f", v)
        return if (',' in s) s.trimEnd('0').trimEnd(',') else s
    }

    /** Sempre com casas decimais fixas: 0.46 → "0,46". */
    fun fixed(v: Double, decimals: Int): String = String.format(ptBR, "%.${decimals}f", v)

    /** 3450 → "3.450". */
    fun int(v: Int): String = String.format(ptBR, "%,d", v)

    fun kg(v: Double): String = num(v, 2) + " kg"

    fun signed(v: Double, decimals: Int = 2): String = (if (v > 0) "+" else "") + fixed(v, decimals)
}
