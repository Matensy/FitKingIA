package com.fitkingia.app.figure

/** Tinta de um acessório: equipamento solto em laranja, estrutura/estofado de máquina em cinza. */
enum class Ink { METAL, FRAME, PAD, CABLE, BAND }

/** Camada de desenho: atrás de tudo, entre os membros de trás e o tronco, ou por cima do corpo. */
enum class Layer { BACK, MID, FRONT }

/** Acessório desenhado junto da figura. */
sealed class Prop(val layer: Layer) {
    class Line(val a: Anchor, val b: Anchor, val width: Float, val ink: Ink, layer: Layer = Layer.BACK) : Prop(layer)
    class Disc(val at: Anchor, val r: Float, val ink: Ink, val ring: Boolean = false, layer: Layer = Layer.FRONT) : Prop(layer)
    class Block(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val ink: Ink, layer: Layer = Layer.BACK) : Prop(layer)
}

/**
 * Um movimento ilustrado: quadros-chave (início → [intermediários] → fim) e acessórios.
 * A animação vai do início ao fim e volta (pausando nos extremos); com [loop] = true, percorre
 * os quadros em ciclo a velocidade constante (caminhadas).
 */
class Motion(
    val id: String,
    val name: String,
    val keys: List<Key>,
    val props: List<Prop> = emptyList(),
    val rig: Rig = Rig.SIDE,
    val loop: Boolean = false,
    /** Fração do ciclo parada no fim do movimento (isometrias seguram mais). */
    val hold: Float = 0.12f,
    val periodMs: Int = 2400,
    viewLabel: String? = null,
) {
    val viewLabel: String? = viewLabel ?: rig.view.label

    init {
        require(keys.size >= 2) { "$id: precisa de pelo menos 2 quadros-chave" }
    }

    /** Parâmetros na posição [f] do percurso (0 = primeiro quadro, 1 = último; em ciclo, 1 volta ao primeiro). */
    fun key(f: Float): Key {
        val segments = if (loop) keys.size else keys.size - 1
        val x = f.coerceIn(0f, 1f) * segments
        val i = x.toInt().coerceAtMost(segments - 1)
        val t = x - i
        return keys[i].lerp(keys[(i + 1) % keys.size], t)
    }

    fun pose(f: Float): Pose = solve(rig, key(f))

    /** Posição no percurso para um instante [ms] da animação. */
    fun phase(ms: Long): Float {
        val u = ((ms % periodMs + periodMs) % periodMs).toFloat() / periodMs
        if (loop) return u
        val rest = 0.04f
        val go = (1f - 2 * rest - hold) / 2
        return when {
            u < rest -> 0f
            u < rest + go -> easeInOut((u - rest) / go)
            u < rest + go + hold -> 1f
            u < rest + 2 * go + hold -> easeInOut(1f - (u - rest - go - hold) / go)
            else -> 0f
        }
    }

    /** Frações usadas nas folhas de contato (início, meio e fim). */
    fun samples(): List<Float> = if (loop) listOf(0f, 1f / 3, 2f / 3) else listOf(0f, 0.5f, 1f)
}

// ---------------------------------------------------------------------------------------------
// Acessórios prontos (montados com as três primitivas acima).
// ---------------------------------------------------------------------------------------------

object Props {
    fun at(x: Float, y: Float) = Anchor.At(P(x, y))

    /** Banco reto: estofado de x1 a x2 com o topo em [top]. */
    fun bench(x1: Float, x2: Float, top: Float): List<Prop> = listOf(
        Prop.Line(at(x1 + 4, top + 2), at(x1 + 4, GROUND), 1.6f, Ink.FRAME),
        Prop.Line(at(x2 - 4, top + 2), at(x2 - 4, GROUND), 1.6f, Ink.FRAME),
        Prop.Line(at(x1 + 1.7f, top + 1.7f), at(x2 - 1.7f, top + 1.7f), 3.4f, Ink.PAD),
    )

    /** Estofado reto entre dois pontos (encosto, assento, apoio). */
    fun pad(a: P, b: P, width: Float = 3.4f): Prop = Prop.Line(Anchor.At(a), Anchor.At(b), width, Ink.PAD)
    fun pad(a: Anchor, b: Anchor, width: Float = 3.4f, layer: Layer = Layer.BACK): Prop = Prop.Line(a, b, width, Ink.PAD, layer)

    /** Haste de estrutura (pés do banco, coluna, trilho). */
    fun frame(a: P, b: P, width: Float = 1.6f): Prop = Prop.Line(Anchor.At(a), Anchor.At(b), width, Ink.FRAME)

    /** Barra com anilhas vista de lado (a anilha aparece como um disco em volta da barra). */
    fun barbell(at: Anchor, r: Float = 8.5f): List<Prop> = listOf(
        Prop.Disc(at, r, Ink.METAL, ring = true),
        Prop.Disc(at, 1.7f, Ink.METAL),
    )

    /** Barra sem anilhas grandes (barra W, barra leve): só um disco pequeno. */
    fun lightBar(at: Anchor): List<Prop> = listOf(Prop.Disc(at, 3.6f, Ink.METAL, ring = true), Prop.Disc(at, 1.4f, Ink.METAL))

    fun dumbbell(at: Anchor, layer: Layer = Layer.FRONT): Prop = Prop.Disc(at, 3.3f, Ink.METAL, layer = layer)

    /** Halter seguro pela mão [hand]; atrás do corpo se a mão for a do lado de trás. */
    fun dumbbellIn(hand: J): Prop = dumbbell(Anchor.On(hand), if (hand.back) Layer.MID else Layer.FRONT)

    /** Kettlebell pendurado na mão, alinhado com o antebraço. */
    fun kettlebell(elbow: J, hand: J, forearm: Float = 14f): List<Prop> = listOf(
        Prop.Line(Anchor.On(hand), Anchor.Seg(elbow, hand, forearm + 2.6f), 1.4f, Ink.METAL),
        Prop.Disc(Anchor.Seg(elbow, hand, forearm + 5.2f), 3.9f, Ink.METAL),
    )

    /** Cabo da polia: roldana em [from] até a pegada em [to]. */
    fun cable(from: P, to: Anchor): List<Prop> = listOf(
        Prop.Line(Anchor.At(from), to, 0.9f, Ink.CABLE),
        Prop.Disc(Anchor.At(from), 1.8f, Ink.FRAME, layer = Layer.BACK),
        Prop.Disc(to, 1.3f, Ink.METAL),
    )

    /** Coluna da polia (estrutura vertical). */
    fun column(x: Float, top: Float): List<Prop> = listOf(
        Prop.Line(at(x, top), at(x, GROUND), 2.4f, Ink.FRAME),
        Prop.Line(at(x - 3, GROUND - 0.6f), at(x + 3, GROUND - 0.6f), 1.6f, Ink.FRAME),
    )

    fun band(a: Anchor, b: Anchor, layer: Layer = Layer.FRONT): Prop = Prop.Line(a, b, 1.3f, Ink.BAND, layer)

    /** Alavanca de máquina: eixo fixo em [pivot] até o rolo/pegador preso ao corpo. */
    fun lever(pivot: P, to: Anchor, roller: Boolean = true): List<Prop> = listOfNotNull(
        Prop.Line(Anchor.At(pivot), to, 2.2f, Ink.FRAME),
        Prop.Disc(Anchor.At(pivot), 2f, Ink.FRAME, layer = Layer.BACK),
        if (roller) Prop.Disc(to, 2.6f, Ink.PAD) else Prop.Disc(to, 1.4f, Ink.METAL),
    )

    /** Barra fixa / barra baixa vista de topo (disco) com o poste. */
    fun fixedBar(x: Float, y: Float, postX: Float = x - 6): List<Prop> = listOf(
        Prop.Line(at(postX, y - 2), at(postX, GROUND), 2f, Ink.FRAME),
        Prop.Line(at(postX, y), at(x, y), 1.6f, Ink.FRAME),
        Prop.Disc(at(x, y), 1.6f, Ink.FRAME, layer = Layer.BACK),
    )

    fun mat(x1: Float, x2: Float): Prop = Prop.Block(x1, GROUND - 1.2f, x2, GROUND, Ink.PAD)

    /** Caixa/step. */
    fun box(x1: Float, x2: Float, top: Float): Prop = Prop.Block(x1, top, x2, GROUND, Ink.PAD)
}
