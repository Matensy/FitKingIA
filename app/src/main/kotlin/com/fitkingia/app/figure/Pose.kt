package com.fitkingia.app.figure

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// ---------------------------------------------------------------------------------------------
// Modelo de pose 2D das ilustrações de exercício.
//
// Coordenadas normalizadas numa caixa ≈ 0..100 × 0..100 com y para baixo; o chão fica em
// y = GROUND. Ângulos em graus pela convenção dir(θ) = (sen θ, cos θ): 0 = para baixo,
// 90 = para a frente (+x, para onde a figura olha na vista lateral), 180 = para cima, −90 = para trás.
//
// Uma pose não guarda coordenadas soltas: guarda PARÂMETROS (posição da pelve, inclinação do
// tronco, ângulos ou alvos de mãos/pés) que são resolvidos por cinemática direta/inversa com
// comprimentos fixos de segmento. Assim, interpolar dois quadros-chave nunca "estica" um braço.
// ---------------------------------------------------------------------------------------------

const val GROUND = 92f

/** Ponto/vetor 2D. */
data class P(val x: Float, val y: Float) {
    operator fun plus(o: P) = P(x + o.x, y + o.y)
    operator fun minus(o: P) = P(x - o.x, y - o.y)
    operator fun times(k: Float) = P(x * k, y * k)
    fun dot(o: P) = x * o.x + y * o.y
    fun len() = sqrt(x * x + y * y)
    fun unit(): P { val l = len(); return if (l < 1e-6f) P(0f, 1f) else P(x / l, y / l) }
    /** Gira 90°: (0, −1) "para cima" vira (1, 0) "para a frente". */
    fun perp() = P(-y, x)
    fun dist(o: P) = (this - o).len()
    /** Ângulo deste vetor na convenção de [dir]. */
    fun angle(): Float = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()
}

fun dir(deg: Float): P {
    val r = deg * PI / 180.0
    return P(sin(r).toFloat(), cos(r).toFloat())
}

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun lerp(a: P, b: P, t: Float) = P(lerp(a.x, b.x, t), lerp(a.y, b.y, t))

/** Curva suave de aceleração e desaceleração (ease-in-out senoidal). */
fun easeInOut(t: Float): Float = (0.5 - 0.5 * cos(PI * t.coerceIn(0f, 1f))).toFloat()

/**
 * Articulações. Na vista lateral, os sem sufixo são do lado PERTO de quem olha e os _B do lado
 * de trás (desenhados mais escuros). Nas vistas de frente/de cima, _B é o lado esquerdo da tela.
 */
enum class J(val back: Boolean = false) {
    HEAD, NECK, PELVIS,
    SHOULDER, ELBOW, WRIST, HIP, KNEE, ANKLE, TOE,
    SHOULDER_B(true), ELBOW_B(true), WRIST_B(true), HIP_B(true), KNEE_B(true), ANKLE_B(true), TOE_B(true);

    /** A mesma articulação do outro lado do corpo. */
    val mirror: J get() = when (this) {
        SHOULDER -> SHOULDER_B; ELBOW -> ELBOW_B; WRIST -> WRIST_B; HIP -> HIP_B; KNEE -> KNEE_B; ANKLE -> ANKLE_B; TOE -> TOE_B
        SHOULDER_B -> SHOULDER; ELBOW_B -> ELBOW; WRIST_B -> WRIST; HIP_B -> HIP; KNEE_B -> KNEE; ANKLE_B -> ANKLE; TOE_B -> TOE
        else -> this
    }
}

/** De onde a figura é vista. */
enum class ViewKind(val label: String?) {
    SIDE(null),
    FRONT("vista de frente"),
    /** Plano horizontal visto de cima (adução/abdução horizontal, abdutora sentada). */
    TOP("vista de cima"),
    /** Olhando ao longo do corpo (pela cabeça): crucifixo deitado, crucifixo inverso curvado. */
    AXIAL("vista pela cabeça"),
}

/** Proporções do boneco (unidades da caixa). Altura em pé ≈ 80. */
data class Rig(
    val view: ViewKind = ViewKind.SIDE,
    val headR: Float = 5f,
    val neck: Float = 7.5f,
    val torso: Float = 25f,
    val upperArm: Float = 14f,
    val forearm: Float = 14f,
    val thigh: Float = 20f,
    val shin: Float = 19.5f,
    val foot: Float = 8.5f,
    /** Meia largura dos ombros/quadris (0 na vista lateral, onde os dois lados se sobrepõem). */
    val shoulderHalf: Float = 0f,
    val shoulderDrop: Float = 0f,
    val hipHalf: Float = 0f,
    val showLegs: Boolean = true,
    /** Quanto do tronco aparece (vistas em que ele aponta para quem olha). */
    val torsoVisible: Boolean = true,
    /**
     * Vista de frente com os braços passando na frente do tronco (ex.: halter pendurado entre as
     * pernas): contorno na cor do fundo, senão braço e tronco viram uma mancha só.
     */
    val armsOverTorso: Boolean = false,
) {
    val front get() = view != ViewKind.SIDE

    companion object {
        val SIDE = Rig()
        val FRONT = Rig(view = ViewKind.FRONT, shoulderHalf = 8f, shoulderDrop = 2f, hipHalf = 4.5f, foot = 4f)
    }
}

/** Como um membro (braço ou perna) é posicionado num quadro-chave. */
sealed class Limb {
    abstract fun mirrored(cx: Float): Limb

    /**
     * Ângulos do segmento proximal (braço/coxa) e distal (antebraço/canela) na convenção de [dir].
     * [relative] = medidos com o tronco "em pé": giram junto com a inclinação do tronco.
     */
    data class Angles(val upper: Float, val lower: Float, val relative: Boolean = false) : Limb() {
        override fun mirrored(cx: Float) = Angles(-upper, -lower, relative)
    }

    /**
     * Punho/tornozelo vai até [target] (cinemática inversa de dois segmentos). O cotovelo/joelho
     * dobra para o lado de [bend], dado no referencial do tronco: x = ao longo da coluna (para a
     * cabeça), y = perpendicular (para a frente na vista lateral; para a direita nas outras).
     * Com [world] = true, [bend] é uma direção no mundo (usado nos joelhos: "para a frente" não
     * gira junto quando o tronco se inclina numa dobradiça de quadril).
     */
    data class Reach(val target: Anchor, val bend: P = P(0f, 1f), val world: Boolean = false) : Limb() {
        override fun mirrored(cx: Float) = Reach(target.mirrored(cx), if (world) P(-bend.x, bend.y) else P(bend.x, -bend.y), world)
    }

    /**
     * Cotovelo/joelho em [mid] e punho/tornozelo em [end], sem cinemática. Só para vistas com
     * escorço, em que o comprimento APARENTE do segmento muda de verdade (ex.: concha vista de
     * frente e do alto: a coxa que gira em direção a quem olha fica mais curta no desenho).
     */
    data class Free(val mid: Anchor, val end: Anchor) : Limb() {
        override fun mirrored(cx: Float) = Free(mid.mirrored(cx), end.mirrored(cx))
    }
}

/** Perna: membro + pé. [foot] é o ângulo tornozelo→ponta do pé; [footRelative] = relativo à canela. */
data class Leg(val limb: Limb, val foot: Float = 76f, val footRelative: Boolean = false) {
    fun mirrored(cx: Float) = Leg(limb.mirrored(cx), -foot, footRelative)
}

/** Um ponto que pode estar fixo no cenário ou preso ao corpo (acessórios e alvos de mãos/pés). */
sealed class Anchor {
    abstract fun mirrored(cx: Float): Anchor

    data class At(val p: P) : Anchor() {
        override fun mirrored(cx: Float) = At(P(2 * cx - p.x, p.y))
    }

    /** Articulação + deslocamento fixo. */
    data class On(val j: J, val dx: Float = 0f, val dy: Float = 0f) : Anchor() {
        override fun mirrored(cx: Float) = On(j.mirror, -dx, dy)
    }

    /** No referencial do tronco: [along] da pelve para o pescoço; [perp] para a frente (ou direita). */
    data class Body(val along: Float, val perp: Float) : Anchor() {
        override fun mirrored(cx: Float) = Body(along, -perp)
    }

    /** Sobre a reta a→b: [along] unidades a partir de [a]; [perp] para o lado perp() de a→b. */
    data class Seg(val a: J, val b: J, val along: Float, val perp: Float = 0f) : Anchor() {
        override fun mirrored(cx: Float) = Seg(a.mirror, b.mirror, along, -perp)
    }

    /**
     * Na vertical da articulação: x de [j] + [dx], altura [y] fixa. Para peças que acompanham o
     * corpo só na horizontal e continuam em pé (ex.: coluna da plataforma da barra assistida).
     */
    data class Plumb(val j: J, val dx: Float, val y: Float) : Anchor() {
        override fun mirrored(cx: Float) = Plumb(j.mirror, -dx, y)
    }
}

/** Quadro-chave: parâmetros da pose. Na vista lateral, os membros de trás repetem os da frente por padrão. */
data class Key(
    val pelvis: P,
    /** Inclinação do tronco: 0 = em pé, 90 = cabeça para a frente (+x), −90 = cabeça para trás (−x). */
    val lean: Float = 0f,
    /** Cabeça em relação ao tronco: positivo = queixo para a frente/para o peito. */
    val head: Float = 0f,
    val arm: Limb,
    val leg: Leg,
    val armB: Limb = arm,
    val legB: Leg = leg,
)

/** Pose resolvida: coordenadas de todas as articulações. */
class Pose(val rig: Rig, private val pts: Array<P>, val spine: P) {
    operator fun get(j: J): P = pts[j.ordinal]

    /** Perpendicular do tronco: para a frente (vista lateral) ou para a direita (outras vistas). */
    val side: P get() = spine.perp()

    fun resolve(a: Anchor): P = when (a) {
        is Anchor.At -> a.p
        is Anchor.On -> get(a.j) + P(a.dx, a.dy)
        is Anchor.Body -> get(J.PELVIS) + spine * a.along + side * a.perp
        is Anchor.Seg -> {
            val u = (get(a.b) - get(a.a)).unit()
            get(a.a) + u * a.along + u.perp() * a.perp
        }
        is Anchor.Plumb -> P(get(a.j).x + a.dx, a.y)
    }

    companion object {
        /** Segmentos rígidos do boneco (os comprimentos não podem variar entre quadros). */
        val SEGMENTS = listOf(
            J.PELVIS to J.NECK, J.NECK to J.HEAD,
            J.SHOULDER to J.ELBOW, J.ELBOW to J.WRIST, J.SHOULDER_B to J.ELBOW_B, J.ELBOW_B to J.WRIST_B,
            J.HIP to J.KNEE, J.KNEE to J.ANKLE, J.ANKLE to J.TOE,
            J.HIP_B to J.KNEE_B, J.KNEE_B to J.ANKLE_B, J.ANKLE_B to J.TOE_B,
        )
    }
}

/** Resolve os parâmetros de [k] em coordenadas. Ordem: tronco → pernas → braços (braços podem mirar as pernas). */
fun solve(rig: Rig, k: Key): Pose {
    val pts = Array(J.values().size) { P(0f, 0f) }
    val spine = dir(180f - k.lean)
    val side = spine.perp()
    val pelvis = k.pelvis
    val neck = pelvis + spine * rig.torso
    pts[J.PELVIS.ordinal] = pelvis
    pts[J.NECK.ordinal] = neck
    pts[J.HEAD.ordinal] = neck + dir(180f - k.lean - k.head) * rig.neck
    val shoulders = neck - spine * rig.shoulderDrop
    pts[J.SHOULDER.ordinal] = shoulders + side * rig.shoulderHalf
    pts[J.SHOULDER_B.ordinal] = shoulders - side * rig.shoulderHalf
    pts[J.HIP.ordinal] = pelvis + side * rig.hipHalf
    pts[J.HIP_B.ordinal] = pelvis - side * rig.hipHalf
    // Pose parcial para resolver âncoras que dependem do tronco.
    val partial = Pose(rig, pts, spine)

    fun bendWorld(b: P) = spine * b.x + side * b.y

    fun limb(root: P, l1: Float, l2: Float, spec: Limb): Pair<P, P> = when (spec) {
        is Limb.Angles -> {
            val off = if (spec.relative) -k.lean else 0f
            val mid = root + dir(spec.upper + off) * l1
            mid to (mid + dir(spec.lower + off) * l2)
        }
        is Limb.Reach -> ik(root, partial.resolve(spec.target), l1, l2, if (spec.world) spec.bend else bendWorld(spec.bend))
        is Limb.Free -> partial.resolve(spec.mid) to partial.resolve(spec.end)
    }

    fun leg(spec: Leg, hip: J, knee: J, ankle: J, toe: J) {
        val (kn, an) = limb(pts[hip.ordinal], rig.thigh, rig.shin, spec.limb)
        pts[knee.ordinal] = kn
        pts[ankle.ordinal] = an
        val footAngle = if (spec.footRelative) (an - kn).angle() + spec.foot else spec.foot
        pts[toe.ordinal] = an + dir(footAngle) * rig.foot
    }
    leg(k.leg, J.HIP, J.KNEE, J.ANKLE, J.TOE)
    leg(k.legB, J.HIP_B, J.KNEE_B, J.ANKLE_B, J.TOE_B)

    fun arm(spec: Limb, shoulder: J, elbow: J, wrist: J) {
        val (el, wr) = limb(pts[shoulder.ordinal], rig.upperArm, rig.forearm, spec)
        pts[elbow.ordinal] = el
        pts[wrist.ordinal] = wr
    }
    arm(k.arm, J.SHOULDER, J.ELBOW, J.WRIST)
    arm(k.armB, J.SHOULDER_B, J.ELBOW_B, J.WRIST_B)
    return Pose(rig, pts, spine)
}

/**
 * Cinemática inversa de dois segmentos: a extremidade vai o mais perto possível de [target]
 * (se não alcançar, o membro fica esticado apontando para ele) e a articulação do meio dobra
 * para o lado de [bend]. Os comprimentos [l1] e [l2] são sempre respeitados.
 */
fun ik(root: P, target: P, l1: Float, l2: Float, bend: P): Pair<P, P> {
    val d = target - root
    val u = if (d.len() < 1e-4f) bend.unit() else d.unit()
    val dist = d.len().coerceIn(abs(l1 - l2) + 1e-3f, (l1 + l2) * 0.9995f)
    val end = root + u * dist
    val a = (l1 * l1 - l2 * l2 + dist * dist) / (2 * dist)
    val h = sqrt(max(0f, l1 * l1 - a * a))
    var n = u.perp()
    if (n.dot(bend) < 0) n = n * -1f
    return (root + u * a + n * h) to end
}

fun Limb.lerp(o: Limb, t: Float): Limb = when {
    this is Limb.Angles && o is Limb.Angles && relative == o.relative -> Limb.Angles(lerp(upper, o.upper, t), lerp(lower, o.lower, t), relative)
    this is Limb.Reach && o is Limb.Reach && world == o.world -> Limb.Reach(target.lerp(o.target, t), lerp(bend, o.bend, t), world)
    this is Limb.Free && o is Limb.Free -> Limb.Free(mid.lerp(o.mid, t), end.lerp(o.end, t))
    else -> throw IllegalArgumentException("quadros com tipos de membro diferentes: $this × $o")
}

fun Anchor.lerp(o: Anchor, t: Float): Anchor = when {
    this is Anchor.At && o is Anchor.At -> Anchor.At(lerp(p, o.p, t))
    this is Anchor.Body && o is Anchor.Body -> Anchor.Body(lerp(along, o.along, t), lerp(perp, o.perp, t))
    this is Anchor.On && o is Anchor.On && j == o.j -> Anchor.On(j, lerp(dx, o.dx, t), lerp(dy, o.dy, t))
    this is Anchor.Seg && o is Anchor.Seg && a == o.a && b == o.b -> Anchor.Seg(a, b, lerp(along, o.along, t), lerp(perp, o.perp, t))
    this is Anchor.Plumb && o is Anchor.Plumb && j == o.j -> Anchor.Plumb(j, lerp(dx, o.dx, t), lerp(y, o.y, t))
    else -> throw IllegalArgumentException("quadros com âncoras incompatíveis: $this × $o")
}

fun Leg.lerp(o: Leg, t: Float): Leg {
    require(footRelative == o.footRelative) { "pé relativo/absoluto misturado entre quadros" }
    return Leg(limb.lerp(o.limb, t), lerp(foot, o.foot, t), footRelative)
}

fun Key.lerp(o: Key, t: Float) = Key(
    pelvis = lerp(pelvis, o.pelvis, t), lean = lerp(lean, o.lean, t), head = lerp(head, o.head, t),
    arm = arm.lerp(o.arm, t), leg = leg.lerp(o.leg, t), armB = armB.lerp(o.armB, t), legB = legB.lerp(o.legB, t),
)
