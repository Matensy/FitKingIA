package com.fitkingia.app.figure

import android.widget.LinearLayout
import com.fitkingia.app.ui.Btn
import com.fitkingia.app.ui.add
import com.fitkingia.app.ui.button
import com.fitkingia.app.ui.card
import com.fitkingia.app.ui.label
import com.fitkingia.app.ui.muted
import com.fitkingia.core.knowledge.Exercise

// Peças prontas para as telas: cada tela só chama uma linha (as telas são editadas por várias frentes).

/** Card "Como fazer" com a ilustração animada do exercício (tela do exercício). */
fun LinearLayout.exerciseFigureCard(ex: Exercise): ExerciseFigureView {
    lateinit var view: ExerciseFigureView
    card {
        label("Como fazer")
        view = add(ExerciseFigureView(context, FigureMapping.motionFor(ex), description = ex.name), bottom = 6)
        muted("Ilustração animada — veja também as instruções abaixo", 12f)
    }
    return view
}

/**
 * Botão "Ver o movimento" / "Esconder o movimento" e, quando [shown], a ilustração logo abaixo
 * (tela do treino). O estado fica na tela; [onToggle] inverte e redesenha.
 */
fun LinearLayout.exerciseFigureToggle(ex: Exercise, shown: Boolean, onToggle: () -> Unit) {
    button(if (shown) "Esconder o movimento" else "👁 Ver o movimento", Btn.GHOST, onClick = onToggle)
    if (shown) card(bottom = 10) {
        add(ExerciseFigureView(context, FigureMapping.motionFor(ex), heightDp = 200, description = ex.name), bottom = 2)
        muted("Toque na figura para pausar", 12f)
    }
}
