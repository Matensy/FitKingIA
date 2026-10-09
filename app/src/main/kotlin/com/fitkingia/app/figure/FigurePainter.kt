package com.fitkingia.app.figure

import kotlin.math.max
import kotlin.math.min

/** Superfície mínima de desenho (Canvas do Android no app; Graphics2D em ferramentas/testes na JVM). */
interface FigureCanvas {
    /** Traço com pontas arredondadas. */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int)
    /** Círculo cheio ([stroke] = 0) ou anel com espessura [stroke]. */
    fun circle(cx: Float, cy: Float, r: Float, color: Int, stroke: Float = 0f)
    fun rect(l: Float, t: Float, r: Float, b: Float, radius: Float, color: Int)
    fun poly(xs: FloatArray, ys: FloatArray, color: Int)
    fun text(s: String, x: Float, y: Float, size: Float, color: Int)
    fun textWidth(s: String, size: Float): Float
}

/** Cores da ilustração (o app passa as de com.fitkingia.app.ui.C). */
data class FigurePalette(
    val figure: Int,
    val figureBack: Int,
    val equipment: Int,
    val frame: Int,
    val pad: Int,
    val floor: Int,
    val background: Int,
    val label: Int,
)

/** Retângulo do mundo (unidades da caixa) que precisa caber na tela. */
class WorldBounds(val minX: Float, val minY: Float, val maxX: Float, val maxY: Float) {
    val width get() = maxX - minX
    val height get() = maxY - minY
}

/** Transformação mundo → pixels. */
class Viewport(val scale: Float, val ox: Float, val oy: Float) {
    fun x(v: Float) = ox + v * scale
    fun y(v: Float) = oy + v * scale
    fun s(v: Float) = v * scale
}

/** Desenha um movimento numa pose qualquer. Sem estado: pode ser usado por várias views. */
object FigurePainter {
    const val LIMB = 3.8f
    const val TORSO_SIDE = 6.2f
    const val NECK = 3.2f
    const val FOOT = 3.2f

    /** Limites de todas as poses do movimento (amostradas) e dos acessórios, incluindo o chão. */
    fun bounds(m: Motion): WorldBounds {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        fun add(x: Float, y: Float, pad: Float) {
            minX = min(minX, x - pad); maxX = max(maxX, x + pad)
            minY = min(minY, y - pad); maxY = max(maxY, y + pad)
        }
        val n = 24
        for (i in 0..n) {
            val pose = m.pose(i.toFloat() / n)
            for (j in J.values()) {
                if (!m.rig.showLegs && isLeg(j)) continue
                add(pose[j].x, pose[j].y, if (j == J.HEAD) m.rig.headR + 0.6f else LIMB)
            }
            for (p in m.props) when (p) {
                is Prop.Line -> { val a = pose.resolve(p.a); val b = pose.resolve(p.b); add(a.x, a.y, p.width); add(b.x, b.y, p.width) }
                is Prop.Disc -> { val c = pose.resolve(p.at); add(c.x, c.y, p.r + 0.8f) }
                is Prop.Block -> { add(p.x1, p.y1, 0.5f); add(p.x2, p.y2, 0.5f) }
            }
        }
        if (m.rig.view != ViewKind.TOP) add(minX + 1f, GROUND, 1.5f)
        return WorldBounds(minX, minY, maxX, maxY)
    }

    /** Encaixa [b] numa área de [w]×[h] pixels com margem [pad] ([top] em cima), centralizado. */
    fun fit(b: WorldBounds, w: Float, h: Float, pad: Float, top: Float = pad): Viewport {
        val scale = min((w - 2 * pad) / b.width, (h - top - pad) / b.height).coerceAtLeast(0.01f)
        val ox = (w - b.width * scale) / 2 - b.minX * scale
        val oy = top + (h - top - pad - b.height * scale) / 2 - b.minY * scale
        return Viewport(scale, ox, oy)
    }

    private fun isLeg(j: J) = j == J.HIP || j == J.KNEE || j == J.ANKLE || j == J.TOE ||
        j == J.HIP_B || j == J.KNEE_B || j == J.ANKLE_B || j == J.TOE_B

    fun paint(c: FigureCanvas, m: Motion, pose: Pose, v: Viewport, width: Float, pal: FigurePalette, labelSize: Float = 0f) {
        // Chão de ponta a ponta (não existe na vista de cima).
        if (m.rig.view != ViewKind.TOP) c.line(0f, v.y(GROUND) + v.s(0.6f), width, v.y(GROUND) + v.s(0.6f), max(1f, v.s(1.2f)), pal.floor)
        props(c, m, pose, v, pal, Layer.BACK)
        val rig = m.rig
        if (rig.view == ViewKind.SIDE) {
            limbs(c, pose, v, pal.figureBack, back = true, legs = rig.showLegs)
            props(c, m, pose, v, pal, Layer.MID)
            torsoSide(c, pose, v, pal)
            // Contorno na cor do fundo separa braço/perna da frente do tronco quando se sobrepõem.
            limbs(c, pose, v, pal.background, back = false, legs = rig.showLegs, extra = 1.8f)
            limbs(c, pose, v, pal.figure, back = false, legs = rig.showLegs)
        } else {
            if (rig.showLegs) { leg(c, pose, v, pal.figure, true); leg(c, pose, v, pal.figure, false) }
            props(c, m, pose, v, pal, Layer.MID)
            torsoFront(c, pose, v, pal, rig)
            if (rig.armsOverTorso) { arm(c, pose, v, pal.background, true, extra = 1.8f); arm(c, pose, v, pal.background, false, extra = 1.8f) }
            arm(c, pose, v, pal.figure, true); arm(c, pose, v, pal.figure, false)
            head(c, pose, v, pal, rig)
        }
        props(c, m, pose, v, pal, Layer.FRONT)
        if (labelSize > 0f) m.viewLabel?.let {
            // Encolhe o rótulo se ele não couber na largura (telas estreitas, folhas de contato).
            val room = width - labelSize * 1.2f
            val size = min(labelSize, labelSize * room / max(1f, c.textWidth(it, labelSize)))
            c.text(it, labelSize * 0.6f, labelSize * 1.1f, size, pal.label)
        }
    }

    private fun seg(c: FigureCanvas, v: Viewport, a: P, b: P, w: Float, color: Int) =
        c.line(v.x(a.x), v.y(a.y), v.x(b.x), v.y(b.y), v.s(w), color)

    private fun limbs(c: FigureCanvas, pose: Pose, v: Viewport, color: Int, back: Boolean, legs: Boolean, extra: Float = 0f) {
        if (legs) leg(c, pose, v, color, back, extra)
        arm(c, pose, v, color, back, extra)
    }

    /** Com [extra] > 0 desenha o contorno (traço mais largo), começando um pouco depois da raiz do membro. */
    private fun rootOf(root: P, next: P, extra: Float) = if (extra > 0f) root + (next - root).unit() * 4f else root

    private fun leg(c: FigureCanvas, pose: Pose, v: Viewport, color: Int, back: Boolean, extra: Float = 0f) {
        val knee = pose[if (back) J.KNEE_B else J.KNEE]
        val hip = rootOf(pose[if (back) J.HIP_B else J.HIP], knee, extra)
        val ankle = pose[if (back) J.ANKLE_B else J.ANKLE]
        val toe = pose[if (back) J.TOE_B else J.TOE]
        seg(c, v, hip, knee, LIMB + 0.6f + extra, color)
        seg(c, v, knee, ankle, LIMB + extra, color)
        seg(c, v, ankle, toe, FOOT + extra, color)
    }

    private fun arm(c: FigureCanvas, pose: Pose, v: Viewport, color: Int, back: Boolean, extra: Float = 0f) {
        val el = pose[if (back) J.ELBOW_B else J.ELBOW]
        val sh = rootOf(pose[if (back) J.SHOULDER_B else J.SHOULDER], el, extra)
        val wr = pose[if (back) J.WRIST_B else J.WRIST]
        seg(c, v, sh, el, LIMB + extra, color)
        seg(c, v, el, wr, LIMB - 0.4f + extra, color)
        c.circle(v.x(wr.x), v.y(wr.y), v.s(2.1f + extra / 2), color)
    }

    private fun torsoSide(c: FigureCanvas, pose: Pose, v: Viewport, pal: FigurePalette) {
        val pelvis = pose[J.PELVIS]
        val neck = pose[J.NECK]
        // Tronco um pouco mais largo no peito que na cintura: dois traços sobrepostos.
        seg(c, v, pelvis, neck, TORSO_SIDE, pal.figure)
        seg(c, v, pelvis + (neck - pelvis) * 0.45f, neck - pose.spine * 1.2f, TORSO_SIDE + 1.6f, pal.figure)
        seg(c, v, neck, pose[J.HEAD], NECK, pal.figure)
        head(c, pose, v, pal, pose.rig)
    }

    private fun torsoFront(c: FigureCanvas, pose: Pose, v: Viewport, pal: FigurePalette, rig: Rig) {
        val sh = pose[J.SHOULDER]; val shB = pose[J.SHOULDER_B]
        val hip = pose[J.HIP]; val hipB = pose[J.HIP_B]
        seg(c, v, pose[J.NECK], pose[J.HEAD], NECK, pal.figure)
        if (rig.torsoVisible) {
            val waistIn = pose.side * 0.6f
            c.poly(
                floatArrayOf(v.x(shB.x), v.x(sh.x), v.x(hip.x - waistIn.x), v.x(hipB.x + waistIn.x)),
                floatArrayOf(v.y(shB.y), v.y(sh.y), v.y(hip.y - waistIn.y), v.y(hipB.y + waistIn.y)),
                pal.figure,
            )
            seg(c, v, sh, hip, LIMB, pal.figure)
            seg(c, v, shB, hipB, LIMB, pal.figure)
            seg(c, v, hip, hipB, LIMB, pal.figure)
        }
        seg(c, v, shB, sh, LIMB + 0.8f, pal.figure)
    }

    private fun head(c: FigureCanvas, pose: Pose, v: Viewport, pal: FigurePalette, rig: Rig) {
        val h = pose[J.HEAD]
        c.circle(v.x(h.x), v.y(h.y), v.s(rig.headR), pal.figure)
        val up = (h - pose[J.NECK]).unit()
        when (rig.view) {
            ViewKind.SIDE -> {
                // Olho do lado do rosto: mostra para onde a pessoa está virada.
                val eye = h + up.perp() * 2.4f + up * 0.7f
                c.circle(v.x(eye.x), v.y(eye.y), v.s(0.85f), pal.background)
            }
            ViewKind.FRONT -> {
                val s = up.perp()
                for (k in listOf(-1.8f, 1.8f)) {
                    val eye = h + s * k + up * 0.6f
                    c.circle(v.x(eye.x), v.y(eye.y), v.s(0.75f), pal.background)
                }
            }
            else -> {}
        }
    }

    private fun inkColor(ink: Ink, pal: FigurePalette): Int = when (ink) {
        Ink.METAL, Ink.CABLE -> pal.equipment
        Ink.BAND -> (pal.equipment and 0x00FFFFFF) or (0xC0 shl 24)
        Ink.FRAME -> pal.frame
        Ink.PAD -> pal.pad
    }

    private fun props(c: FigureCanvas, m: Motion, pose: Pose, v: Viewport, pal: FigurePalette, layer: Layer) {
        for (p in m.props) {
            if (p.layer != layer) continue
            when (p) {
                is Prop.Line -> seg(c, v, pose.resolve(p.a), pose.resolve(p.b), p.width, inkColor(p.ink, pal))
                is Prop.Disc -> {
                    val at = pose.resolve(p.at)
                    if (p.ring) c.circle(v.x(at.x), v.y(at.y), v.s(p.r - 1.1f), inkColor(p.ink, pal), v.s(2.2f))
                    else c.circle(v.x(at.x), v.y(at.y), v.s(p.r), inkColor(p.ink, pal))
                }
                is Prop.Block -> c.rect(v.x(p.x1), v.y(p.y1), v.x(p.x2), v.y(p.y2), v.s(1.2f), inkColor(p.ink, pal))
            }
        }
    }
}
