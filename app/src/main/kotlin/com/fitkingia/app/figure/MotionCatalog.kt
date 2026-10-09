package com.fitkingia.app.figure

import com.fitkingia.app.figure.Props.at

/**
 * Catálogo de movimentos ilustrados. Cada um é desenhado com proporções fixas (ver [Rig]) e
 * quadros-chave em coordenadas da caixa 0..100 (chão em y = 92, tornozelo em pé em y = 89).
 *
 * Convenções (ver Pose.kt): ângulos 0 = para baixo, 90 = para a frente, 180 = para cima.
 * Dobras de joelho/cotovelo no referencial do tronco: P(ao longo da coluna, para a frente).
 */
object MotionCatalog {
    // Dobras mais usadas.
    private val KNEE = P(1f, 0f)            // joelho para a frente (direção no mundo)
    private val KNEE_UP = P(0.2f, -1f)      // deitado de costas: joelhos para cima
    private val KNEE_DOWN = P(0f, 1f)       // de bruços/de lado: joelhos para o chão
    private val OUT_R = P(1f, 0f)           // vista de frente: joelho para fora (direita da tela)
    private val OUT_L = P(-1f, 0f)
    private val ELBOW_BACK = P(-0.3f, -1f)  // cotovelo para trás (remadas, supinos)
    private val ELBOW_DOWN = P(-1f, 0.2f)   // cotovelo em direção ao quadril
    private val ELBOW_FWD = P(-1f, 0.6f)    // cotovelo para baixo e à frente

    private fun f(n: Number) = n.toFloat()
    private fun p(x: Number, y: Number) = P(f(x), f(y))
    private fun pt(x: Number, y: Number) = at(f(x), f(y))
    private fun reach(x: Number, y: Number, bend: P) = Limb.Reach(pt(x, y), bend)
    /** Tornozelo até (x, y); [bend] é direção no mundo. */
    private fun legReach(x: Number, y: Number, bend: P = KNEE) = Limb.Reach(pt(x, y), bend, world = true)
    private fun grip(along: Number, perp: Number, bend: P) = Limb.Reach(Anchor.Body(f(along), f(perp)), bend)
    private fun ang(u: Number, l: Number = u, rel: Boolean = false) = Limb.Angles(f(u), f(l), rel)

    /** Pé apoiado: tornozelo em (x, y) e ângulo do pé no mundo. */
    private fun foot(x: Number, y: Number = 89, angle: Number = 76, bend: P = KNEE) = Leg(legReach(x, y, bend), f(angle))

    /** Perna por ângulos, pé relativo à canela (85 ≈ tornozelo neutro). */
    private fun legAng(u: Number, l: Number = u, footRel: Number = 85, rel: Boolean = false) = Leg(ang(u, l, rel), f(footRel), footRelative = true)

    private fun key(
        px: Number, py: Number, lean: Number = 0, head: Number = 0,
        arm: Limb, leg: Leg, armB: Limb = arm, legB: Leg = leg,
    ) = Key(p(px, py), f(lean), f(head), arm, leg, armB, legB)

    /** Vistas de frente/de cima: o lado esquerdo da tela espelha o direito em torno da pelve. */
    private fun sym(k: Key) = k.copy(armB = k.arm.mirrored(k.pelvis.x), legB = k.leg.mirrored(k.pelvis.x))

    private fun dumbbells() = listOf(Props.dumbbellIn(J.WRIST_B), Props.dumbbellIn(J.WRIST))

    /** Vista de frente: mãos na cintura (direita, esquerda). */
    private fun waistFront() = Pair(grip(3, 6.5, P(0f, 1f)), grip(3, -6.5, P(0f, -1f)))

    // Proporções para as vistas que não são de lado.
    private val FRONT = Rig.FRONT
    /** De cima, sentado: tronco e canelas (verticais) quase somem; coxas e braços aparecem inteiros. */
    private val TOP_SEATED = Rig(view = ViewKind.TOP, headR = 5.5f, neck = 1f, torso = 3f, upperArm = 7f, forearm = 8f,
        thigh = 20f, shin = 2f, foot = 3f, shoulderHalf = 9f, hipHalf = 5.5f, torsoVisible = false)
    private val TOP_PECDECK = TOP_SEATED.copy(upperArm = 14f, forearm = 3f)
    private val TOP_STRAIGHT = TOP_SEATED.copy(upperArm = 14f, forearm = 13f)
    private val TOP_STANDING = TOP_STRAIGHT.copy(showLegs = false)
    /** Olhando pela cabeça: o tronco aponta para quem olha. */
    private val AXIAL = Rig(view = ViewKind.AXIAL, headR = 5f, neck = 2.5f, torso = 0.5f, shoulderHalf = 9f, hipHalf = 5f, torsoVisible = false, foot = 3f)

    val all: List<Motion> by lazy { lower() + push() + pull() + arms() + core() + other() }

    private val byId: Map<String, Motion> by lazy { all.associateBy { it.id } }

    fun get(id: String): Motion = byId[id] ?: error("Movimento desconhecido: $id")
    fun find(id: String): Motion? = byId[id]

    // =========================================================================================
    // Membros inferiores
    // =========================================================================================

    private fun lower(): List<Motion> {
        val list = ArrayList<Motion>()
        val ankle = foot(50)

        // --- Agachamentos ------------------------------------------------------------------
        val barBack = Anchor.Body(23.6f, -3.4f)
        val holdBack = Limb.Reach(barBack, P(-1f, -0.5f))
        list += Motion("agachamento_barra", "Agachamento com barra nas costas", listOf(
            key(48, 49.7, lean = 8, head = -6, arm = holdBack, leg = ankle),
            key(40, 73.5, lean = 44, head = -28, arm = holdBack, leg = ankle),
        ), Props.barbell(barBack))

        val barFront = Anchor.Body(25.6f, 3.2f)
        val rack = ang(78, 250, rel = true)
        list += Motion("agachamento_frontal", "Agachamento frontal", listOf(
            key(49, 49.7, lean = 3, arm = rack, leg = ankle),
            key(41, 75, lean = 22, head = -10, arm = rack, leg = ankle),
        ), Props.barbell(barFront))

        val goblet = grip(17.5, 6, P(-1f, 0.3f))
        list += Motion("agachamento_goblet", "Agachamento com halter no peito (goblet)", listOf(
            key(49, 49.7, lean = 4, arm = goblet, leg = ankle),
            key(41, 76, lean = 24, head = -10, arm = goblet, leg = ankle),
        ), listOf(
            Prop.Line(Anchor.Body(21.5f, 6.5f), Anchor.Body(13.5f, 6.5f), 1.8f, Ink.METAL, Layer.FRONT),
            Prop.Disc(Anchor.Body(21.5f, 6.5f), 2.8f, Ink.METAL), Prop.Disc(Anchor.Body(13.5f, 6.5f), 2.8f, Ink.METAL),
        ))

        list += Motion("agachamento_livre", "Agachamento com o peso do corpo", listOf(
            key(49, 49.7, lean = 3, arm = ang(8, 8), leg = ankle),
            key(40, 74, lean = 38, head = -15, arm = ang(88, 88), leg = ankle),
        ))

        val smithFoot = foot(54)
        list += Motion("agachamento_smith", "Agachamento na barra guiada (Smith)", listOf(
            key(51, 49.7, lean = 6, head = -4, arm = holdBack, leg = smithFoot),
            key(41.2, 72, lean = 30, head = -18, arm = holdBack, leg = smithFoot),
        ), listOf(Props.frame(p(50.1, 4), p(50.1, GROUND), 1.8f)) + Props.barbell(barBack, 7f))

        // Hack: costas no encosto inclinado, que desliza junto com o corpo.
        val hackFoot = foot(62, 84, angle = 70)
        val hackHands = grip(26.5, 3, P(-1f, 0.5f))
        list += Motion("agachamento_hack", "Agachamento hack (máquina)", listOf(
            key(37.4, 54.6, lean = -40, head = 10, arm = hackHands, leg = hackFoot),
            key(50.3, 69.9, lean = -40, head = 10, arm = hackHands, leg = hackFoot),
        ), listOf(
            Props.frame(p(9.5, 32.3), p(51.3, 82.1), 2f),
            Props.frame(p(51.3, 82.1), p(51.3, GROUND), 2f),
            Props.pad(Anchor.Body(-1f, -3.6f), Anchor.Body(26f, -3.6f), 3.6f),
            Prop.Disc(Anchor.Body(27.5f, 1.5f), 2.4f, Ink.PAD, layer = Layer.FRONT),
            Props.pad(p(56, 88.4), p(76, 85.4), 3f),
            Props.frame(p(66, 88), p(66, GROUND)),
        ))

        // Prensa de pernas 45°: quadril fixo no assento, a plataforma desliza a 45°.
        val pressHands = reach(32, 74.5, P(-1f, 0f))
        list += Motion("leg_press", "Prensa de pernas 45° (leg press)", listOf(
            key(30, 74, lean = -55, head = 25, arm = pressHands, leg = Leg(legReach(59, 51, P(0f, -1f)), -135f)),
            key(30, 74, lean = -55, head = 25, arm = pressHands, leg = Leg(legReach(50, 60, P(0f, -1f)), -135f)),
        ), legPressProps())

        // --- Afundos -----------------------------------------------------------------------
        val front = foot(62)
        val backToes = Leg(legReach(30, 85, KNEE), 40f)
        list += Motion("afundo", "Afundo com halteres (passada parada)", listOf(
            key(46, 54, lean = 4, arm = ang(2), leg = front, legB = backToes),
            key(45, 68, lean = 6, arm = ang(2), leg = front, legB = backToes),
        ), dumbbells())

        list += Motion("afundo_livre", "Afundo com o peso do corpo", listOf(
            key(46, 54, lean = 4, arm = grip(2, 2.5, P(0f, -1f)), leg = front, legB = backToes),
            key(45, 68, lean = 6, arm = grip(2, 2.5, P(0f, -1f)), leg = front, legB = backToes),
        ))

        val hips = grip(2, 2.5, P(0f, -1f))
        list += Motion("afundo_reverso", "Afundo para trás", listOf(
            key(51.5, 49.7, lean = 2, arm = hips, leg = foot(54), legB = foot(51)),
            key(47, 56, lean = 6, arm = hips, leg = foot(54), legB = Leg(legReach(40, 81, KNEE), 60f)),
            key(43, 68, lean = 8, arm = hips, leg = foot(54), legB = Leg(legReach(30, 85, KNEE), 40f)),
        ))

        val rearOnBench = Leg(legReach(24, 68.5, P(0.5f, 1f)), -70f)
        list += Motion("bulgaro", "Agachamento búlgaro", listOf(
            key(47, 54, lean = 6, arm = ang(2), leg = front, legB = rearOnBench),
            key(44.5, 68, lean = 18, head = -8, arm = ang(2), leg = front, legB = rearOnBench),
        ), Props.bench(4f, 28f, 72f) + dumbbells())

        val onBox = foot(60, 73)
        list += Motion("subida_banco", "Subida no banco", listOf(
            key(46, 57, lean = 18, head = -8, arm = ang(2), leg = onBox, legB = foot(40)),
            key(54, 44, lean = 8, arm = ang(2), leg = onBox, legB = Leg(legReach(47, 70, KNEE), 70f)),
            key(58, 34, lean = 2, arm = ang(2), leg = onBox, legB = foot(57, 73)),
        ), listOf(Props.box(52f, 74f, 76f)) + dumbbells())

        // --- Cadeiras extensora e flexora ------------------------------------------------
        val seatHands = reach(43, 68.5, P(-1f, 0f))
        list += Motion("extensora", "Cadeira extensora", listOf(
            key(40, 66, lean = -12, head = 5, arm = seatHands, leg = legAng(90, -8, 88)),
            key(40, 66, lean = -12, head = 5, arm = seatHands, leg = legAng(90, 84, 88)),
        ), seatedMachine() + Props.lever(p(60.5, 66), Anchor.Seg(J.KNEE, J.ANKLE, 17f, -2.6f)))

        list += Motion("flexora_cadeira", "Cadeira flexora", listOf(
            key(40, 66, lean = -12, head = 5, arm = seatHands, leg = legAng(90, 84, 88)),
            key(40, 66, lean = -12, head = 5, arm = seatHands, leg = legAng(90, -25, 88)),
        ), seatedMachine() + listOf(Props.pad(p(49, 61.2), p(60, 61.2), 3f)) +
            Props.lever(p(60.5, 66), Anchor.Seg(J.KNEE, J.ANKLE, 17f, 2.6f)))

        // Mesa flexora (deitado de bruços); a cabeça fica para +x.
        val proneHands = reach(84, 66.5, P(-1f, -0.4f))
        list += Motion("flexora_mesa", "Mesa flexora", listOf(
            key(42, 65.5, lean = 90, head = -15, arm = proneHands, leg = legAng(-90, -90, 15)),
            key(42, 65.5, lean = 90, head = -15, arm = proneHands, leg = legAng(-90, -200, 15)),
        ), Props.bench(24f, 84f, 68.6f) + Props.lever(p(22, 65.5), Anchor.Seg(J.KNEE, J.ANKLE, 17f, 2.6f)))

        list += Motion("flexora_elastico", "Flexora com elástico (deitado)", listOf(
            key(42, 89, lean = 90, head = -15, arm = ang(93, 93), leg = legAng(-90, -90, 15)),
            key(42, 89, lean = 90, head = -15, arm = ang(93, 93), leg = legAng(-90, -200, 15)),
        ), listOf(Props.mat(-6f, 84f), Prop.Disc(pt(-9, 88.5), 1.8f, Ink.FRAME, layer = Layer.BACK),
            Props.band(pt(-9, 88.5), Anchor.On(J.ANKLE))))

        // Flexão nórdica: joelhos fixos no chão, coxa e tronco descem juntos como uma prancha.
        val nordicLeg = Leg(legReach(20.5, 89.5, KNEE), -85f)
        val nordicArms = ang(25, 150, rel = true)
        list += Motion("flexao_nordica", "Flexão nórdica", listOf(
            key(40, 70, lean = 0, arm = nordicArms, leg = nordicLeg),
            key(50, 72.7, lean = 30, arm = nordicArms, leg = nordicLeg),
            key(57.3, 80, lean = 60, head = -10, arm = nordicArms, leg = nordicLeg),
        ), listOf(Props.mat(10f, 80f), Prop.Disc(pt(20.5, 86.2), 2.6f, Ink.PAD)))

        // Flexão de joelhos deslizando: ponte com os calcanhares sobre a toalha.
        list += Motion("flexora_deslizante", "Flexão de joelhos deslizando (toalha)", listOf(
            key(40.9, 74.7, lean = -123.5, head = 53.5, arm = ang(86, 90), leg = foot(58, 88.5, angle = 160, bend = KNEE_UP)),
            key(43.6, 80.15, lean = -109.5, head = 39.5, arm = ang(86, 90), leg = foot(76, 88.5, angle = 160, bend = KNEE_UP)),
        ), listOf(Props.mat(4f, 88f), Prop.Line(Anchor.On(J.ANKLE, -3f, 2.4f), Anchor.On(J.ANKLE, 5f, 2.4f), 1.8f, Ink.BAND, Layer.FRONT)))

        list += Motion("sissy", "Agachamento com joelhos à frente (sissy)", listOf(
            key(50, 49.7, lean = 0, arm = ang(70, 70, rel = true), leg = foot(50, 89)),
            key(51, 57.9, lean = -40, head = 25, arm = ang(70, 70, rel = true), leg = foot(50, 87, angle = 50)),
        ))

        // --- Dobradiça de quadril --------------------------------------------------------
        list += Motion("terra", "Levantamento terra", listOf(
            key(32.8, 65, lean = 68, head = -35, arm = reach(54.5, 83.5, P(0f, -1f)), leg = ankle),
            key(36, 58, lean = 44, head = -18, arm = reach(54.5, 66, P(0f, -1f)), leg = ankle),
            key(48.5, 49.6, lean = 0, arm = reach(49, 52.6, P(0f, -1f)), leg = ankle),
        ), Props.barbell(Anchor.On(J.WRIST)))

        list += Motion("terra_romeno", "Terra romeno com barra (stiff)", listOf(
            key(49, 49.7, lean = 3, arm = reach(50.5, 52.6, P(0f, -1f)), leg = ankle),
            key(32, 55, lean = 70, head = -12, arm = reach(55, 74.4, P(0f, -1f)), leg = ankle),
        ), Props.barbell(Anchor.On(J.WRIST)))

        list += Motion("stiff_halteres", "Terra romeno com halteres (stiff)", listOf(
            key(49, 49.7, lean = 3, arm = reach(50.5, 52.6, P(0f, -1f)), leg = ankle),
            key(32, 55, lean = 70, head = -12, arm = reach(55, 74.4, P(0f, -1f)), leg = ankle),
        ), dumbbells())

        for ((id, name, weighted) in listOf(
            Triple("stiff_unilateral", "Terra romeno unilateral com halter", true),
            Triple("stiff_unilateral_livre", "Terra romeno unilateral (peso do corpo)", false),
        )) {
            list += Motion(id, name, listOf(
                key(50, 49.6, lean = 0, arm = ang(0), leg = legAng(-6, -10, 80), legB = foot(51)),
                key(45, 51.5, lean = 80, head = -10, arm = ang(0), leg = legAng(-80, -80, 85), legB = foot(51)),
            ), if (weighted) listOf(Props.dumbbellIn(J.WRIST)) else emptyList())
        }

        list += Motion("swing", "Balanço com kettlebell (swing)", listOf(
            key(36, 57, lean = 62, head = -25, arm = ang(-15, -15), leg = ankle),
            key(49, 49.6, lean = -4, arm = ang(88, 88), leg = ankle),
        ), Props.kettlebell(J.ELBOW, J.WRIST), hold = 0.04f, periodMs = 1800)

        // Extensão lombar no banco 45°: pernas fixas, tronco desce e volta à linha das pernas.
        val bxLeg = Leg(legReach(22, 82, KNEE), 45f)
        val crossed = ang(25, 150, rel = true)
        list += Motion("extensao_lombar", "Extensão lombar no banco 45°", listOf(
            key(49.6, 54.4, lean = 45, arm = crossed, leg = bxLeg),
            key(49.6, 54.4, lean = 125, head = 15, arm = crossed, leg = bxLeg),
        ), listOf(
            Props.frame(p(50, 63), p(44, GROUND), 2f), Props.frame(p(19, 87), p(16, GROUND), 2f),
            Props.frame(p(12, GROUND - 0.8f), p(50, GROUND - 0.8f), 2f),
            Props.pad(p(48.2, 61.5), p(52.4, 57.2), 4f),
            Props.pad(p(18.5, 82.9), p(27, 91.2), 2.4f),
            Prop.Disc(pt(19.5, 79.5), 2.4f, Ink.PAD, layer = Layer.FRONT),
        ))

        list += Motion("pull_through", "Extensão de quadril na polia entre as pernas", listOf(
            key(36, 57, lean = 66, head = -25, arm = ang(-12, -12), leg = ankle),
            key(49, 49.6, lean = -2, arm = ang(12, 12), leg = ankle),
        ), Props.column(6f, 30f) + Props.cable(p(8, 86), Anchor.On(J.WRIST)))

        // --- Elevação pélvica e ponte ----------------------------------------------------
        val thrustFeet = foot(64)
        val bar = Anchor.Body(1f, 4.6f)
        list += Motion("hip_thrust_barra", "Elevação pélvica com barra", listOf(
            key(39.3, 83.5, lean = -54.5, head = 20, arm = grip(1, 5.5, P(0f, -1f)), leg = thrustFeet),
            key(44, 69, lean = -90, head = 20, arm = grip(1, 5.5, P(0f, -1f)), leg = thrustFeet),
        ), Props.bench(0f, 21f, 72f) + Props.barbell(bar))

        list += Motion("hip_thrust_halter", "Elevação pélvica com halter", listOf(
            key(39.3, 83.5, lean = -54.5, head = 20, arm = grip(1, 4.8, P(0f, -1f)), leg = thrustFeet),
            key(44, 69, lean = -90, head = 20, arm = grip(1, 4.8, P(0f, -1f)), leg = thrustFeet),
        ), Props.bench(0f, 21f, 72f) + Props.dumbbell(Anchor.Body(1f, 4.4f)))

        val bridgeArms = ang(84, 90)
        list += Motion("ponte", "Ponte de glúteo", listOf(
            key(45, 88.5, lean = -90, head = 20, arm = bridgeArms, leg = foot(62, bend = KNEE_UP)),
            key(40.9, 74.7, lean = -123.5, head = 53.5, arm = bridgeArms, leg = foot(62, bend = KNEE_UP)),
        ), listOf(Props.mat(4f, 76f)))

        list += Motion("ponte_unilateral", "Ponte de glúteo unilateral", listOf(
            key(45, 88.5, lean = -90, head = 20, arm = bridgeArms, leg = legAng(128, 128, 80), legB = foot(60, bend = KNEE_UP)),
            key(40.9, 74.7, lean = -123.5, head = 53.5, arm = bridgeArms, leg = legAng(112, 112, 80), legB = foot(60, bend = KNEE_UP)),
        ), listOf(Props.mat(4f, 84f)))

        // --- Panturrilhas ------------------------------------------------------------------
        val calfHands = grip(27, 4, P(-1f, 0.3f))
        list += Motion("panturrilha_maquina", "Panturrilha em pé (máquina)", listOf(
            key(45.3, 46.1, arm = calfHands, leg = Leg(legReach(45.5, 85.4, KNEE), 110f)),
            key(47.8, 36.8, arm = calfHands, leg = Leg(legReach(48, 76, KNEE), 40f)),
        ), listOf(
            Props.box(48f, 70f, 84.5f), Props.frame(p(36, 14), p(36, GROUND), 2.2f),
            Prop.Line(pt(36, 20), Anchor.Body(27.2f, -3f), 2f, Ink.FRAME),
            Props.pad(Anchor.Body(27.2f, -3.5f), Anchor.Body(27.2f, 3.5f), 3.4f, Layer.FRONT),
        ))

        list += Motion("panturrilha_livre", "Panturrilha em pé (peso do corpo)", listOf(
            key(49.7, 49.6, arm = hips, leg = Leg(legReach(49.7, 89, KNEE), 76f)),
            key(53, 44.6, arm = hips, leg = Leg(legReach(53.1, 84, KNEE), 35f)),
        ))

        list += Motion("panturrilha_halter", "Panturrilha unilateral no step com halter", listOf(
            key(45.3, 46.1, arm = ang(0), armB = reach(70, 34, P(-1f, 0f)), leg = Leg(legReach(45.5, 85.4, KNEE), 110f), legB = legAng(5, -70, 70)),
            key(47.8, 36.8, arm = ang(0), armB = reach(70, 25, P(-1f, 0f)), leg = Leg(legReach(48, 76, KNEE), 40f), legB = legAng(5, -70, 70)),
        ), listOf(Props.box(48f, 66f, 84.5f), Props.frame(p(73, 6), p(73, GROUND), 3f), Props.dumbbellIn(J.WRIST)))

        list += Motion("panturrilha_leg_press", "Panturrilha no leg press", listOf(
            key(30, 74, lean = -55, head = 25, arm = pressHands, leg = Leg(legReach(60.6, 49.5, P(0f, -1f)), -120f)),
            key(30, 74, lean = -55, head = 25, arm = pressHands, leg = Leg(legReach(60.6, 49.5, P(0f, -1f)), -175f)),
        ), legPressProps(onToes = true))

        // --- Abdução e adução --------------------------------------------------------------
        val topHands = reach(59.5, 53, P(0f, 1f))
        list += Motion("abdutora", "Cadeira abdutora", listOf(
            sym(key(50, 50, arm = topHands, leg = legAng(6, 6, 0))),
            sym(key(50, 50, arm = topHands, leg = legAng(32, 32, 0))),
        ), topSeat() + listOf(
            Props.pad(Anchor.Seg(J.HIP, J.KNEE, 12f, -3.6f), Anchor.Seg(J.HIP, J.KNEE, 19f, -3.6f), 3f, Layer.FRONT),
            Props.pad(Anchor.Seg(J.HIP_B, J.KNEE_B, 12f, 3.6f), Anchor.Seg(J.HIP_B, J.KNEE_B, 19f, 3.6f), 3f, Layer.FRONT),
        ), rig = TOP_SEATED)

        list += Motion("adutora", "Cadeira adutora", listOf(
            sym(key(50, 50, arm = topHands, leg = legAng(34, 34, 0))),
            sym(key(50, 50, arm = topHands, leg = legAng(6, 6, 0))),
        ), topSeat() + listOf(
            Props.pad(Anchor.Seg(J.HIP, J.KNEE, 12f, 3.4f), Anchor.Seg(J.HIP, J.KNEE, 19f, 3.4f), 3f, Layer.FRONT),
            Props.pad(Anchor.Seg(J.HIP_B, J.KNEE_B, 12f, -3.4f), Anchor.Seg(J.HIP_B, J.KNEE_B, 19f, -3.4f), 3f, Layer.FRONT),
        ), rig = TOP_SEATED)

        val waist = grip(3, 6.5, P(0f, 1f))
        list += Motion("caminhada_lateral", "Caminhada lateral com elástico", listOf(
            key(50, 50.5, arm = waist, armB = waist.mirrored(50f), leg = foot(57, angle = 60, bend = OUT_R), legB = foot(43, angle = -60, bend = OUT_L)),
            key(54, 51.5, arm = grip(3, 6.5, P(0f, 1f)), armB = grip(3, -6.5, P(0f, -1f)),
                leg = foot(69, angle = 60, bend = OUT_R), legB = foot(43, angle = -60, bend = OUT_L)),
        ), listOf(Props.band(Anchor.Seg(J.HIP, J.KNEE, 16f), Anchor.Seg(J.HIP_B, J.KNEE_B, 16f))), rig = FRONT)

        list += Motion("abducao_em_pe", "Abdução de quadril em pé", listOf(
            key(50, 49.6, arm = waist, armB = waist.mirrored(50f), leg = legAng(1, 1, 60), legB = foot(45.5, angle = -60, bend = OUT_L)),
            key(49, 49.6, lean = -4, arm = waist, armB = waist.mirrored(50f), leg = legAng(32, 32, 60), legB = foot(45.5, angle = -60, bend = OUT_L)),
        ), listOf(Props.band(Anchor.On(J.ANKLE, 0f, -2f), Anchor.On(J.ANKLE_B, 0f, -2f))), rig = FRONT)

        list += Motion("coice_polia", "Coice na polia (extensão de quadril)", listOf(
            key(48, 50.5, lean = 25, head = -10, arm = reach(74, 48, P(-1f, 0f)), leg = legAng(15, -30, 80), legB = foot(50)),
            key(48, 50.5, lean = 25, head = -10, arm = reach(74, 48, P(-1f, 0f)), leg = legAng(-38, -45, 80), legB = foot(50)),
        ), Props.column(79f, 20f) + Props.cable(p(77, 87), Anchor.On(J.ANKLE)))

        return list
    }

    private fun legPressProps(onToes: Boolean = false): List<Prop> {
        val plate = if (onToes) J.TOE else J.ANKLE
        val back = if (onToes) 1.8f else 2.8f
        // Plataforma perpendicular ao trilho (direção (0,707; −0,707)).
        return listOf(
            Props.frame(p(40, 69), p(76, 33), 2.2f),
            Props.frame(p(40, 69), p(40, GROUND), 2.2f),
            Props.frame(p(6, GROUND - 0.8f), p(60, GROUND - 0.8f), 2.2f),
            Props.frame(p(24, 80), p(24, GROUND), 2.2f),
            Props.pad(p(22, 78.4), p(38, 78.4), 3.4f),
            Props.pad(p(27.9, 76.95), p(6.6, 62.0), 3.6f),
            Props.pad(
                Anchor.On(plate, back * 0.707f + 4f * 0.707f, -back * 0.707f + 4f * 0.707f),
                Anchor.On(plate, back * 0.707f - 12f * 0.707f, -back * 0.707f - 12f * 0.707f), 3f, Layer.FRONT,
            ),
        )
    }

    /** Assento e encosto das cadeiras extensora/flexora (quadril em (40, 66), tronco −12°). */
    private fun seatedMachine(): List<Prop> = listOf(
        Props.frame(p(40, 72), p(40, GROUND), 2.2f),
        Props.frame(p(33, 50), p(30, GROUND), 2f),
        Props.pad(p(30, 70), p(58, 70), 3.4f),
        Props.pad(p(36.1, 65.8), p(31.3, 43.3), 3.6f),
    )

    /** Assento visto de cima (abdutora/adutora). */
    private fun topSeat(): List<Prop> = listOf(
        Prop.Block(40f, 40f, 60f, 58f, Ink.PAD),
        Props.pad(p(37, 40), p(63, 40), 3.6f),
    )

    // =========================================================================================
    // Empurrar (peito, ombros)
    // =========================================================================================

    private fun push(): List<Motion> {
        val list = ArrayList<Motion>()
        // Deitado no banco reto: cabeça para −x, tronco em y = 69,3 (topo do banco em 72).
        val benchFeet = foot(73)
        val pressBend = P(-0.4f, -1f)
        fun supine(id: String, name: String, top: P, bottom: P, props: List<Prop>) = Motion(id, name, listOf(
            key(55, 69.3, lean = -90, head = 20, arm = Limb.Reach(Anchor.At(top), pressBend), leg = benchFeet),
            key(55, 69.3, lean = -90, head = 20, arm = Limb.Reach(Anchor.At(bottom), pressBend), leg = benchFeet),
        ), Props.bench(15f, 62f, 72f) + props)

        list += supine("supino_barra", "Supino reto com barra", p(31, 41.6), p(37, 64.4), Props.barbell(Anchor.On(J.WRIST)))
        list += supine("supino_halteres", "Supino reto com halteres", p(31, 42), p(35, 63.5), dumbbells())
        list += supine("supino_smith", "Supino na barra guiada (Smith)", p(34, 41.6), p(34, 64.4),
            listOf(Props.frame(p(34, 26), p(34, GROUND), 1.8f)) + Props.barbell(Anchor.On(J.WRIST), 7f))

        // Banco inclinado: tronco a −55°.
        val inclineFeet = foot(70)
        fun incline(id: String, name: String, props: List<Prop>) = Motion(id, name, listOf(
            key(52, 71, lean = -55, head = 15, arm = reach(32, 29.2, P(-1f, -0.6f)), leg = inclineFeet),
            key(52, 71, lean = -55, head = 15, arm = reach(37, 54.5, P(-1f, -0.6f)), leg = inclineFeet),
        ), listOf(
            Props.frame(p(38, 69), p(40, GROUND), 2f), Props.frame(p(54, 77), p(54, GROUND), 2f),
            Props.frame(p(34, GROUND - 0.8f), p(60, GROUND - 0.8f), 2f),
            Props.pad(p(46, 75.6), p(62, 75.6), 3.4f),
            Props.pad(p(48.2, 72.9), p(28.6, 59.1), 3.6f),
        ) + props)
        list += incline("supino_inclinado_barra", "Supino inclinado com barra", Props.barbell(Anchor.On(J.WRIST)))
        list += incline("supino_inclinado_halteres", "Supino inclinado com halteres", dumbbells())

        val chestPressFeet = foot(52)
        list += Motion("supino_maquina", "Supino máquina", listOf(
            key(35, 66, lean = -8, arm = reach(40, 44, P(-0.6f, -1f)), leg = chestPressFeet),
            key(35, 66, lean = -8, arm = reach(58, 44, P(-0.6f, -1f)), leg = chestPressFeet),
        ), uprightSeat(35f, 66f, -8f) + Props.lever(p(62, 16), Anchor.On(J.WRIST), roller = false))

        // Flexão: corpo reto girando em torno dos dedos dos pés; mãos fixas no chão.
        val pushLeg = Leg(legReach(14.6, 83.7, KNEE), 94f, footRelative = true)
        val pushBend = P(-0.7f, -1f)
        list += Motion("flexao", "Flexão de braços", listOf(
            key(51.8, 70.9, lean = 71, arm = reach(76, 90.6, pushBend), leg = pushLeg),
            key(53.9, 83.9, lean = 90.3, arm = reach(76, 90.6, pushBend), leg = pushLeg),
        ))

        val inclLeg = Leg(legReach(21.5, 84.5, KNEE), 94f, footRelative = true)
        list += Motion("flexao_inclinada", "Flexão inclinada (mãos elevadas)", listOf(
            key(51.4, 60.4, lean = 49.5, arm = reach(74, 71, pushBend), leg = inclLeg),
            key(58, 71.3, lean = 68.1, arm = reach(74, 71, pushBend), leg = inclLeg),
        ), listOf(Props.box(66f, 88f, 72.5f)))

        val dipLegs = legAng(8, -75, 70)
        list += Motion("paralelas", "Mergulho nas paralelas", listOf(
            key(46.8, 44.45, lean = 12, arm = reach(51, 47, P(-0.3f, -1f)), leg = dipLegs),
            key(43.3, 55.1, lean = 28, head = -8, arm = reach(51, 47, P(-0.3f, -1f)), leg = dipLegs),
        ), listOf(
            Props.frame(p(36, 49.5), p(66, 49.5), 2.2f), Props.frame(p(38, 49.5), p(38, GROUND), 2.2f), Props.frame(p(64, 49.5), p(64, GROUND), 2.2f),
        ))

        list += Motion("supino_elastico", "Supino com elástico (em pé)", listOf(
            key(51, 51.5, lean = 8, arm = reach(60.5, 30, P(-0.6f, -1f)), leg = foot(60), legB = foot(42)),
            key(51, 51.5, lean = 8, arm = reach(80, 30, P(-0.6f, -1f)), leg = foot(60), legB = foot(42)),
        ), Props.column(14f, 24f) + listOf(Props.band(pt(16, 30), Anchor.On(J.WRIST), Layer.BACK)))

        // Voador visto de cima: braços abrem e fecham no plano horizontal.
        val deckLever = Props.lever(p(59, 40), Anchor.On(J.ELBOW), roller = false) + Props.lever(p(41, 40), Anchor.On(J.ELBOW_B), roller = false)
        list += Motion("voador", "Voador", listOf(
            sym(key(50, 50, arm = ang(100, 100), leg = legAng(4, 4, 0))),
            sym(key(50, 50, arm = ang(22, 22), leg = legAng(4, 4, 0))),
        ), topSeat() + deckLever + listOf(
            Prop.Disc(Anchor.On(J.ELBOW), 3f, Ink.PAD), Prop.Disc(Anchor.On(J.ELBOW_B), 3f, Ink.PAD),
        ), rig = TOP_PECDECK)

        // Crossover visto de frente: mãos saem do alto e se encontram à frente do corpo.
        list += Motion("crucifixo_polia", "Crucifixo na polia", listOf(
            sym(key(50, 49.6, lean = 0, arm = reach(82, 18, P(-1f, 0.5f)), leg = foot(55.5, angle = 60, bend = OUT_R))),
            sym(key(50, 49.6, lean = 0, arm = reach(52.5, 37.6, P(-1f, 0.5f)), leg = foot(55.5, angle = 60, bend = OUT_R))),
        ), Props.column(91f, 10f) + Props.column(9f, 10f) +
            Props.cable(p(89, 13), Anchor.On(J.WRIST)) + Props.cable(p(11, 13), Anchor.On(J.WRIST_B)), rig = FRONT)

        // Crucifixo deitado visto pela cabeceira do banco.
        list += Motion("crucifixo_halteres", "Crucifixo com halteres", listOf(
            sym(key(50, 69.5, arm = reach(84, 76, P(-1f, 0.5f)), leg = legAng(0, 0, 0))),
            sym(key(50, 69.5, arm = reach(52.5, 42, P(-1f, 0.5f)), leg = legAng(0, 0, 0))),
        ), listOf(
            Prop.Block(42f, 72f, 58f, 75.5f, Ink.PAD),
            Props.frame(p(45, 75), p(45, GROUND), 2f), Props.frame(p(55, 75), p(55, GROUND), 2f),
            Props.frame(p(40, GROUND - 0.8f), p(60, GROUND - 0.8f), 2f),
        ) + dumbbells(), rig = AXIAL.copy(showLegs = false), viewLabel = "vista pela cabeceira do banco")

        // Desenvolvimento.
        list += Motion("desenvolvimento_barra", "Desenvolvimento em pé com barra", listOf(
            key(49, 49.7, lean = -5, head = -12, arm = reach(54, 24, ELBOW_FWD), leg = foot(50)),
            key(49, 49.7, lean = -3, head = -14, arm = reach(54.5, 10, ELBOW_FWD), leg = foot(50)),
            key(49, 49.7, lean = 0, arm = reach(49, -2.5, ELBOW_FWD), leg = foot(50)),
        ), Props.barbell(Anchor.On(J.WRIST), 7.5f))

        val seatedFeet = foot(58)
        list += Motion("desenvolvimento_halteres", "Desenvolvimento sentado com halteres", listOf(
            key(42, 68, lean = -6, arm = reach(43, 40, ELBOW_DOWN), leg = seatedFeet),
            key(42, 68, lean = -6, arm = reach(40.5, 15.6, ELBOW_DOWN), leg = seatedFeet),
        ), uprightSeat(42f, 68f, -6f) + dumbbells())

        list += Motion("desenvolvimento_maquina", "Desenvolvimento na máquina", listOf(
            key(42, 68, lean = -6, arm = reach(43, 40, ELBOW_DOWN), leg = seatedFeet),
            key(42, 68, lean = -6, arm = reach(40.5, 15.6, ELBOW_DOWN), leg = seatedFeet),
        ), uprightSeat(42f, 68f, -6f) + Props.lever(p(24, 34), Anchor.On(J.WRIST), roller = false))

        list += Motion("flexao_pike", "Flexão com quadril elevado (pike)", listOf(
            key(38.35, 51, lean = 120, arm = reach(60, 90.6, P(-1f, -0.6f)), leg = Leg(legReach(24, 87.6, KNEE), 94f, footRelative = true)),
            key(38.35, 51, lean = 140, arm = reach(60, 90.6, P(-1f, -0.6f)), leg = Leg(legReach(24, 87.6, KNEE), 94f, footRelative = true)),
        ))

        list += Motion("desenvolvimento_elastico", "Desenvolvimento com elástico", listOf(
            key(49, 49.7, lean = -2, arm = reach(54, 24, ELBOW_FWD), leg = foot(50)),
            key(49, 49.7, lean = 0, arm = reach(49.5, -2.5, ELBOW_FWD), leg = foot(50)),
        ), listOf(Props.band(Anchor.On(J.TOE, -3f, 1f), Anchor.On(J.WRIST))))

        // Elevações laterais vistas de frente.
        val lateralDown = ang(8, 8)
        val lateralUp = ang(84, 88)
        val stance = foot(55.5, angle = 60, bend = OUT_R)
        list += Motion("elevacao_lateral", "Elevação lateral com halteres", listOf(
            sym(key(50, 49.6, arm = lateralDown, leg = stance)),
            sym(key(50, 49.6, arm = lateralUp, leg = stance)),
        ), dumbbells(), rig = FRONT)

        list += Motion("elevacao_lateral_polia", "Elevação lateral na polia", listOf(
            sym(key(50, 49.6, arm = ang(-4, -4), leg = stance)).copy(armB = reach(21, 44, P(-1f, 0f))),
            sym(key(50, 49.6, arm = lateralUp, leg = stance)).copy(armB = reach(21, 44, P(-1f, 0f))),
        ), Props.column(18f, 30f) + Props.cable(p(20, 88), Anchor.On(J.WRIST)), rig = FRONT)

        list += Motion("elevacao_lateral_elastico", "Elevação lateral com elástico", listOf(
            sym(key(50, 49.6, arm = lateralDown, leg = stance)),
            sym(key(50, 49.6, arm = lateralUp, leg = stance)),
        ), listOf(Props.band(Anchor.On(J.ANKLE), Anchor.On(J.WRIST)), Props.band(Anchor.On(J.ANKLE_B), Anchor.On(J.WRIST_B))), rig = FRONT)

        return list
    }

    /** Banco/assento vertical com encosto (quadril em [px],[py] e tronco a [lean] graus). */
    private fun uprightSeat(px: Float, py: Float, lean: Float): List<Prop> {
        val spine = dir(180f - lean)
        val back = spine.perp() * -3.7f
        val a = P(px, py) + back + spine * 1.5f
        val b = P(px, py) + back + spine * 25f
        return listOf(
            Props.frame(P(px + 1, py + 6), P(px + 1, GROUND), 2.2f),
            Props.frame(P(a.x - 2, py), P(a.x - 6, GROUND), 2f),
            Props.pad(P(px - 8, py + 4.6f), P(px + 10, py + 4.6f), 3.4f),
            Props.pad(a, b, 3.6f),
        )
    }

    // =========================================================================================
    // Puxar (costas)
    // =========================================================================================

    private fun pull(): List<Motion> {
        val list = ArrayList<Motion>()
        val rowBend = P(0.2f, -1f)
        val hingeFeet = foot(50)

        list += Motion("remada_curvada", "Remada curvada com barra", listOf(
            key(36, 54, lean = 65, head = -25, arm = reach(58.5, 71.2, rowBend), leg = hingeFeet),
            key(36, 54, lean = 65, head = -25, arm = reach(49, 54, rowBend), leg = hingeFeet),
        ), Props.barbell(Anchor.On(J.WRIST)))

        // Serrote: joelho e mão de trás no banco, braço da frente rema.
        val kneeOnBench = Leg(legReach(11.5, 70, KNEE), -80f)
        list += Motion("remada_unilateral", "Remada unilateral com halter (serrote)", listOf(
            key(30, 51, lean = 82, head = -10, arm = reach(56, 75, rowBend), armB = reach(58, 71.5, P(-1f, 0f)), leg = foot(36), legB = kneeOnBench),
            key(30, 51, lean = 82, head = -10, arm = reach(46, 58, rowBend), armB = reach(58, 71.5, P(-1f, 0f)), leg = foot(36), legB = kneeOnBench),
        ), Props.bench(2f, 66f, 72f) + Props.dumbbellIn(J.WRIST))

        val rowFeet = Leg(legReach(68, 80, KNEE_UP), 170f)
        list += Motion("remada_sentada_polia", "Remada sentada na polia", listOf(
            key(30, 73.5, lean = 10, head = -5, arm = reach(61, 54, ELBOW_BACK), leg = rowFeet),
            key(30, 73.5, lean = -5, arm = reach(36, 62.5, ELBOW_BACK), leg = rowFeet),
        ), listOf(
            Props.pad(p(18, 78), p(44, 78), 3.4f), Props.frame(p(31, 80), p(31, GROUND), 2f),
            Props.pad(p(71, 70), p(71, 88), 3f),
        ) + Props.column(78f, 20f) + Props.cable(p(73, 74), Anchor.On(J.WRIST)))

        list += Motion("remada_maquina", "Remada na máquina (apoio no peito)", listOf(
            key(36, 66, lean = 8, arm = reach(66, 47, ELBOW_BACK), leg = foot(52)),
            key(36, 66, lean = 8, arm = reach(46, 48, ELBOW_BACK), leg = foot(52)),
        ), listOf(
            Props.pad(p(28, 70), p(44, 70), 3.4f), Props.frame(p(36, 72), p(36, GROUND), 2.2f),
            Props.pad(p(48, 38), p(47, 54), 4f), Props.frame(p(49, 48), p(62, GROUND), 2f),
        ) + Props.lever(p(68, 16), Anchor.On(J.WRIST), roller = false))

        list += Motion("remada_apoiada", "Remada com halteres apoiada no banco inclinado", listOf(
            key(40, 62, lean = 55, head = -10, arm = reach(60.5, 75.5, rowBend), leg = Leg(legReach(13, 87.5, KNEE), 50f)),
            key(40, 62, lean = 55, head = -10, arm = reach(53, 60, rowBend), leg = Leg(legReach(13, 87.5, KNEE), 50f)),
        ), listOf(
            Props.pad(p(39.7, 66.8), p(63.5, 50.2), 3.6f),
            Props.frame(p(50, 60), p(46, GROUND), 2f), Props.frame(p(36, GROUND - 0.8f), p(60, GROUND - 0.8f), 2f),
        ) + dumbbells())

        // Remada invertida: barriga para cima, cabeça para −x; o corpo reto gira em torno dos
        // calcanhares e as mãos ficam fixas na barra.
        val heels = foot(92, 89, angle = 160, bend = KNEE)
        val invBend = P(-0.5f, -1f)
        list += Motion("remada_invertida_barra", "Remada invertida na barra baixa", listOf(
            key(55.6, 74.3, lean = -68, arm = reach(43.1, 39.4, invBend), leg = heels),
            key(64, 61.5, lean = -45.5, arm = reach(43.1, 39.4, invBend), leg = heels),
        ), Props.fixedBar(43.1f, 39.4f, postX = 36f))

        list += Motion("remada_invertida_trx", "Remada invertida na fita de suspensão", listOf(
            key(55.6, 74.3, lean = -68, arm = reach(43.1, 39.4, invBend), leg = heels),
            key(64, 61.5, lean = -45.5, arm = reach(43.1, 39.4, invBend), leg = heels),
        ), listOf(
            Prop.Line(pt(34, -2), Anchor.On(J.WRIST), 1.4f, Ink.BAND, Layer.BACK),
            Prop.Disc(pt(34, -2), 1.6f, Ink.FRAME, layer = Layer.BACK),
        ))

        list += Motion("remada_elastico", "Remada com elástico (sentado)", listOf(
            key(30, 88.5, lean = -3, arm = reach(55, 70, ELBOW_BACK), leg = Leg(legReach(68.5, 88.5, KNEE_UP), 175f)),
            key(30, 88.5, lean = -3, arm = reach(36, 74, ELBOW_BACK), leg = Leg(legReach(68.5, 88.5, KNEE_UP), 175f)),
        ), listOf(Props.mat(10f, 80f), Props.band(Anchor.On(J.TOE), Anchor.On(J.WRIST))))

        // Barra fixa: a barra fica acima da caixa (a figura é reenquadrada automaticamente).
        val hangLegs = legAng(8, -20, 75)
        val pullBend = P(-1f, 0.4f)
        // Pendurado de verdade: a barra fica alta o bastante para os pés não tocarem o chão.
        list += Motion("barra_fixa", "Barra fixa", listOf(
            key(49.3, 38.5, lean = 4, arm = reach(52, -14, pullBend), leg = hangLegs),
            key(52.84, 19.6, lean = -10, head = -15, arm = reach(52, -14, pullBend), leg = hangLegs),
        ), Props.fixedBar(52f, -14f, postX = 28f))

        list += Motion("barra_fixa_assistida", "Barra fixa assistida com elástico", listOf(
            key(49.3, 38.5, lean = 4, arm = reach(52, -14, pullBend), leg = hangLegs),
            key(52.84, 19.6, lean = -10, head = -15, arm = reach(52, -14, pullBend), leg = hangLegs),
        ), Props.fixedBar(52f, -14f, postX = 28f) + listOf(Props.band(pt(52, -14), Anchor.On(J.KNEE))))

        val pulldownLegs = foot(58)
        list += Motion("puxada", "Puxada alta na polia", listOf(
            key(40, 66, lean = -12, head = -10, arm = reach(40, 14.5, P(-1f, -0.6f)), leg = pulldownLegs),
            key(40, 66, lean = -12, head = -5, arm = reach(41, 36, P(-1f, -0.6f)), leg = pulldownLegs),
        ), listOf(
            Props.pad(p(30, 70), p(52, 70), 3.4f), Props.frame(p(40, 72), p(40, GROUND), 2.2f),
            Props.pad(p(51, 61.2), p(61, 61.2), 3f), Props.frame(p(56, 61), p(30, 61), 1.6f),
            Props.frame(p(22, 0), p(22, GROUND), 2.4f), Props.frame(p(22, 1), p(42, 1), 2.4f),
        ) + Props.cable(p(40, 2), Anchor.On(J.WRIST)) + Props.lightBar(Anchor.On(J.WRIST)))

        list += Motion("pulldown_bracos_estendidos", "Puxada com braços estendidos na polia", listOf(
            key(44, 51, lean = 30, head = -10, arm = ang(145, 145), leg = foot(48)),
            key(44, 51, lean = 30, head = -10, arm = ang(10, 10), leg = foot(48)),
        ), Props.column(82f, 4f) + Props.cable(p(79, 8), Anchor.On(J.WRIST)))

        val kneel = Leg(legReach(30.5, 89, KNEE), -85f)
        list += Motion("puxada_elastico", "Puxada com elástico (ajoelhado)", listOf(
            key(50, 69.5, lean = -5, arm = reach(56, 18, P(-1f, -0.6f)), leg = kneel),
            key(50, 69.5, lean = -5, arm = reach(53, 40, P(-1f, -0.6f)), leg = kneel),
        ), listOf(Props.mat(20f, 70f), Props.frame(p(66, 2), p(66, GROUND), 3f), Props.band(pt(64, 8), Anchor.On(J.WRIST))))

        val doorFeet = foot(72)
        list += Motion("remada_toalha", "Remada com toalha na porta", listOf(
            key(61.8, 51, lean = -15, head = 5, arm = reach(73.5, 40, ELBOW_BACK), leg = doorFeet),
            key(68.6, 49.85, lean = -5, arm = reach(72, 36, ELBOW_BACK), leg = doorFeet),
        ), listOf(
            Prop.Block(80f, 2f, 84f, GROUND, Ink.FRAME),
            Prop.Disc(pt(79, 47), 1.6f, Ink.FRAME, layer = Layer.BACK),
            Prop.Line(pt(79, 47), Anchor.On(J.WRIST), 2f, Ink.PAD, Layer.FRONT),
        ))

        return list
    }

    // =========================================================================================
    // Braços
    // =========================================================================================

    private fun arms(): List<Motion> {
        val list = ArrayList<Motion>()
        val standFeet = foot(50)
        val curlDown = ang(4, 8, rel = true)
        val curlUp = ang(14, 150, rel = true)

        list += Motion("rosca_barra", "Rosca direta com barra", listOf(
            key(49, 49.7, lean = -2, arm = curlDown, leg = standFeet),
            key(49, 49.7, lean = -2, arm = curlUp, leg = standFeet),
        ), Props.barbell(Anchor.On(J.WRIST), 6.5f))

        // Halteres: um sobe enquanto o outro desce (rosca alternada).
        list += Motion("rosca_halteres", "Rosca com halteres", listOf(
            key(49, 49.7, lean = -2, arm = curlDown, armB = curlUp, leg = standFeet),
            key(49, 49.7, lean = -2, arm = curlUp, armB = curlDown, leg = standFeet),
        ), dumbbells())

        list += Motion("rosca_inclinada", "Rosca inclinada com halteres", listOf(
            key(45, 70, lean = -35, head = 20, arm = ang(0, 5), leg = foot(64)),
            key(45, 70, lean = -35, head = 20, arm = ang(0, 135), leg = foot(64)),
        ), listOf(
            Props.pad(p(40, 74.5), p(56, 74.5), 3.4f), Props.pad(p(40.8, 70.5), p(27.1, 50.8), 3.6f),
            Props.frame(p(48, 76), p(48, GROUND), 2f), Props.frame(p(33, 62), p(36, GROUND), 2f),
        ) + dumbbells())

        list += Motion("rosca_polia", "Rosca na polia", listOf(
            key(49, 49.7, lean = -2, arm = curlDown, leg = standFeet),
            key(49, 49.7, lean = -2, arm = curlUp, leg = standFeet),
        ), Props.column(80f, 40f) + Props.cable(p(78, 88), Anchor.On(J.WRIST)))

        list += Motion("rosca_elastico", "Rosca com elástico", listOf(
            key(49, 49.7, lean = -2, arm = curlDown, leg = standFeet),
            key(49, 49.7, lean = -2, arm = curlUp, leg = standFeet),
        ), listOf(Props.band(Anchor.On(J.ANKLE, 3f, 2.5f), Anchor.On(J.WRIST))))

        val pushdownFeet = foot(52)
        list += Motion("triceps_polia", "Tríceps na polia", listOf(
            key(48, 50, lean = 10, head = -5, arm = ang(3, 95), leg = pushdownFeet),
            key(48, 50, lean = 10, head = -5, arm = ang(3, 3), leg = pushdownFeet),
        ), Props.column(74f, 2f) + Props.cable(p(70, 5), Anchor.On(J.WRIST)))

        list += Motion("triceps_elastico", "Tríceps com elástico", listOf(
            key(48, 50, lean = 10, head = -5, arm = ang(3, 95), leg = pushdownFeet),
            key(48, 50, lean = 10, head = -5, arm = ang(3, 3), leg = pushdownFeet),
        ), listOf(Props.frame(p(74, 2), p(74, GROUND), 3f), Props.band(pt(72, 8), Anchor.On(J.WRIST))))

        list += Motion("triceps_frances_polia", "Tríceps francês na polia", listOf(
            key(46, 51, lean = 30, head = -10, arm = ang(165, -20, rel = true), leg = foot(58), legB = foot(36)),
            key(46, 51, lean = 30, head = -10, arm = ang(165, -195, rel = true), leg = foot(58), legB = foot(36)),
        ), Props.column(6f, 30f) + Props.cable(p(8, 40), Anchor.On(J.WRIST)))

        list += Motion("triceps_frances_halter", "Tríceps francês com halter", listOf(
            key(49, 49.7, arm = ang(172, -10, rel = true), leg = standFeet),
            key(49, 49.7, arm = ang(172, -188, rel = true), leg = standFeet),
        ), listOf(Props.dumbbell(Anchor.On(J.WRIST))))

        list += Motion("triceps_testa", "Tríceps testa", listOf(
            key(55, 69.3, lean = -90, head = 20, arm = ang(190, 185), leg = foot(73)),
            key(55, 69.3, lean = -90, head = 20, arm = ang(190, 319), leg = foot(73)),
        ), Props.bench(15f, 62f, 72f) + Props.barbell(Anchor.On(J.WRIST), 5.5f))

        list += Motion("mergulho_banco", "Mergulho no banco", listOf(
            key(36.2, 68.4, lean = -5, arm = reach(32, 71, P(-0.2f, -1f)), leg = foot(68, 87.5, angle = 150, bend = KNEE_UP)),
            key(37.2, 80.9, lean = -5, arm = reach(32, 71, P(-0.2f, -1f)), leg = foot(68, 87.5, angle = 150, bend = KNEE_UP)),
        ), Props.bench(4f, 33f, 72.5f))

        return list
    }

    // =========================================================================================
    // Core
    // =========================================================================================

    private fun core(): List<Motion> {
        val list = ArrayList<Motion>()
        val forearm = reach(86, 89.5, P(-0.5f, 1f))
        list += Motion("prancha", "Prancha", listOf(
            key(48.2, 83.3, lean = 72, arm = forearm, leg = Leg(legReach(14, 76, KNEE_DOWN), 100f, footRelative = true)),
            key(47.25, 78.8, lean = 82.4, arm = forearm, leg = Leg(legReach(8.3, 84, KNEE_DOWN), 100f, footRelative = true)),
        ), listOf(Props.mat(0f, 92f)), hold = 0.45f, periodMs = 3600)

        // Prancha lateral vista de frente: lado de baixo = articulações sem sufixo.
        val sideBottomArm = reach(84, 89.5, P(-1.19f, 0.77f))
        val hand = ang(178, 178)
        list += Motion("prancha_lateral", "Prancha lateral", listOf(
            key(47.3, 84.15, lean = 50, arm = sideBottomArm, armB = hand, leg = foot(11, 88.5, angle = 60, bend = KNEE_DOWN), legB = foot(10, 84, angle = 60, bend = KNEE_DOWN)),
            key(45.8, 72.5, lean = 77.8, arm = sideBottomArm, armB = hand, leg = foot(11, 88.5, angle = 60, bend = KNEE_DOWN), legB = foot(10, 84, angle = 60, bend = KNEE_DOWN)),
        ), listOf(Props.mat(0f, 92f)), rig = FRONT, hold = 0.45f, periodMs = 3600)

        list += Motion("dead_bug", "Inseto morto (dead bug)", listOf(
            key(50, 88.5, lean = -90, head = 20, arm = ang(180, 180), leg = legAng(180, 90, 90)),
            key(50, 88.5, lean = -90, head = 20, arm = ang(265, 265), armB = ang(180, 180), leg = legAng(180, 90, 90), legB = legAng(100, 95, 90)),
        ), listOf(Props.mat(4f, 96f)))

        val kneelWheel = Leg(legReach(0.5, 89, KNEE), -85f)
        list += Motion("roda_abdominal", "Roda abdominal", listOf(
            key(20, 69.5, lean = 70, head = -10, arm = reach(44, 87.5, P(-1f, 0f)), leg = kneelWheel),
            key(32.9, 74.2, lean = 78, head = -10, arm = reach(70, 87.5, P(-1f, 0f)), leg = kneelWheel),
            key(39.3, 84.3, lean = 84, head = -10, arm = reach(91, 87.5, P(-1f, 0f)), leg = kneelWheel),
        ), listOf(Props.mat(-6f, 30f), Prop.Disc(Anchor.On(J.WRIST), 4.5f, Ink.METAL, ring = true), Prop.Disc(Anchor.On(J.WRIST), 1.4f, Ink.METAL)))

        // Pallof visto de cima: o cabo puxa de lado e as mãos empurram para a frente sem girar.
        list += Motion("pallof_polia", "Antirrotação na polia (Pallof)", listOf(
            key(50, 48, arm = reach(51, 54, P(0f, 1f)), armB = reach(49, 54, P(0f, -1f)), leg = legAng(0)),
            key(50, 48, arm = reach(50.8, 72, P(0f, 1f)), armB = reach(49.2, 72, P(0f, -1f)), leg = legAng(0)),
        ), listOf(Prop.Disc(pt(92, 54), 2.6f, Ink.FRAME, layer = Layer.BACK)) + Props.cable(p(92, 54), Anchor.On(J.WRIST)),
            rig = TOP_STANDING, hold = 0.3f, periodMs = 3000)

        list += Motion("pallof_elastico", "Antirrotação com elástico (Pallof)", listOf(
            key(50, 48, arm = reach(51, 54, P(0f, 1f)), armB = reach(49, 54, P(0f, -1f)), leg = legAng(0)),
            key(50, 48, arm = reach(50.8, 72, P(0f, 1f)), armB = reach(49.2, 72, P(0f, -1f)), leg = legAng(0)),
        ), listOf(Prop.Disc(pt(92, 54), 2.6f, Ink.FRAME, layer = Layer.BACK), Props.band(pt(92, 54), Anchor.On(J.WRIST))),
            rig = TOP_STANDING, hold = 0.3f, periodMs = 3000)

        // Braços estendidos em direção aos joelhos; só as escápulas saem do chão.
        list += Motion("abdominal", "Abdominal curto", listOf(
            key(50, 88.5, lean = -90, head = 22, arm = ang(100, 100), leg = foot(64, bend = KNEE_UP)),
            key(50, 88.5, lean = -64, head = 30, arm = ang(97, 97), leg = foot(64, bend = KNEE_UP)),
        ), listOf(Props.mat(8f, 80f)))

        val ropeHands = Limb.Reach(Anchor.Seg(J.NECK, J.HEAD, 7f, 3.2f), P(-1f, 0.6f))
        list += Motion("abdominal_polia", "Abdominal na polia (ajoelhado)", listOf(
            key(45, 69.5, lean = 15, arm = ropeHands, leg = Leg(legReach(25.5, 89, KNEE), -85f)),
            key(45, 69.5, lean = 80, head = 15, arm = ropeHands, leg = Leg(legReach(25.5, 89, KNEE), -85f)),
        ), listOf(Props.mat(15f, 60f)) + Props.column(80f, 2f) + Props.cable(p(76, 6), Anchor.On(J.WRIST)))

        list += Motion("elevacao_joelhos", "Elevação de joelhos na barra", listOf(
            key(49.3, 37.5, lean = 4, arm = reach(52, -15, P(-1f, 0.4f)), leg = legAng(4, 4, 80)),
            key(49, 36, lean = -5, arm = reach(52, -15, P(-1f, 0.4f)), leg = legAng(110, 10, 80)),
        ), Props.fixedBar(52f, -15f, postX = 28f))

        // Lenhador visto de frente: as duas mãos juntas no pegador, do alto à direita para baixo à esquerda.
        list += Motion("lenhador", "Lenhador na polia", listOf(
            key(52, 51, lean = 8, arm = reach(69, 11, P(-1f, 0.5f)), armB = reach(69, 11, P(-1f, -0.5f)),
                leg = foot(62, angle = 60, bend = OUT_R), legB = foot(38, angle = -60, bend = OUT_L)),
            key(47, 54, lean = -12, arm = reach(41, 56, P(-1f, 0.5f)), armB = reach(41, 56, P(-1f, -0.5f)),
                leg = foot(62, angle = 60, bend = OUT_R), legB = foot(38, angle = -60, bend = OUT_L)),
        ), Props.column(91f, 2f) + Props.cable(p(89, 4), Anchor.On(J.WRIST)), rig = FRONT)

        return list
    }

    // =========================================================================================
    // Carregamentos, extras e genérico
    // =========================================================================================

    private fun other(): List<Motion> {
        val list = ArrayList<Motion>()
        // Caminhada no lugar (como numa esteira): o pé de apoio desliza para trás, o outro passa no ar.
        fun walk(id: String, name: String, props: List<Prop>) = Motion(id, name, listOf(
            key(50, 50.4, arm = ang(2), leg = foot(58, angle = 96), legB = foot(41, 86.5, angle = 55)),
            key(50, 49.8, arm = ang(2), leg = foot(50), legB = foot(49, 82, angle = 62)),
            key(50, 50.4, arm = ang(2), leg = foot(41, 86.5, angle = 55), legB = foot(58, angle = 96)),
            key(50, 49.8, arm = ang(2), leg = foot(49, 82, angle = 62), legB = foot(50)),
        ), props, loop = true, periodMs = 1700)
        list += walk("fazendeiro", "Caminhada do fazendeiro", dumbbells())
        list += walk("mala", "Carregamento unilateral (mala)", listOf(Props.dumbbellIn(J.WRIST)))

        // Crucifixo inverso curvado, visto de frente (a pessoa inclinada olha para o chão).
        list += Motion("crucifixo_inverso_halteres", "Crucifixo inverso com halteres", listOf(
            sym(key(50, 51, arm = ang(-8, -8), leg = foot(55.5, angle = 60, bend = OUT_R))),
            sym(key(50, 51, arm = ang(84, 90), leg = foot(55.5, angle = 60, bend = OUT_R))),
        ), dumbbells(), rig = AXIAL.copy(neck = 4f), viewLabel = "vista de frente, tronco inclinado")

        list += Motion("crucifixo_inverso_maquina", "Crucifixo inverso na máquina", listOf(
            sym(key(50, 50, arm = ang(14, 14), leg = legAng(4, 4, 0))),
            sym(key(50, 50, arm = ang(96, 96), leg = legAng(4, 4, 0))),
        ), topSeat().take(1) + listOf(Props.pad(p(41, 54), p(59, 54), 3.4f)) +
            Props.lever(p(53, 72), Anchor.On(J.WRIST), roller = false) + Props.lever(p(47, 72), Anchor.On(J.WRIST_B), roller = false),
            rig = TOP_STRAIGHT)

        list += Motion("pull_apart", "Abertura de braços com elástico", listOf(
            sym(key(50, 48, arm = ang(10, 10), leg = legAng(0))),
            sym(key(50, 48, arm = ang(90, 90), leg = legAng(0))),
        ), listOf(Props.band(Anchor.On(J.WRIST), Anchor.On(J.WRIST_B))), rig = TOP_STANDING)

        list += Motion("face_pull", "Puxada alta para o rosto na polia", listOf(
            key(46, 50.5, lean = -8, arm = reach(69, 26, P(0.6f, -1f)), leg = foot(54), legB = foot(40)),
            key(46, 50.5, lean = -8, head = -5, arm = reach(49, 18, P(0.6f, -1f)), leg = foot(54), legB = foot(40)),
        ), Props.column(86f, 10f) + Props.cable(p(83, 26), Anchor.On(J.WRIST)))

        list += Motion("elevacao_t", "Elevação em T deitado de bruços", listOf(
            sym(key(50, 89.5, arm = ang(91, 91), leg = legAng(0))),
            sym(key(50, 89.5, arm = ang(104, 104), leg = legAng(0))),
        ), listOf(Props.mat(8f, 92f)), rig = AXIAL.copy(showLegs = false), viewLabel = "vista pela cabeça (deitado de bruços)")

        // --- Variações de glúteo/panturrilha/braço para aparelhos novos -------------------
        // Coice em quatro apoios: joelhos sob o quadril, mãos sob os ombros; a perna da frente sobe.
        val quadKnee = Leg(legReach(15.5, 89.5, KNEE), -85f)
        list += Motion("coice_quatro_apoios", "Coice em quatro apoios", listOf(
            key(35, 69.5, lean = 75, head = -10, arm = reach(60, 90.5, P(-1f, 0f)), leg = legAng(-2, -102, 20), legB = quadKnee),
            key(35, 69.5, lean = 75, head = -10, arm = reach(60, 90.5, P(-1f, 0f)), leg = legAng(-95, -180, 60), legB = quadKnee),
        ), listOf(Props.mat(5f, 75f), Prop.Disc(Anchor.Seg(J.KNEE, J.ANKLE, 18f), 2.2f, Ink.METAL)))

        // Deitado de lado, visto de frente: lado de baixo = articulações sem sufixo.
        val lyingArms = Pair(ang(95, 95), ang(-95, -95))
        list += Motion("abducao_deitado", "Abdução de quadril deitado de lado", listOf(
            key(40, 84.5, lean = 82, arm = lyingArms.first, armB = lyingArms.second, leg = legAng(-90, -90, 0), legB = legAng(-92, -92, 0)),
            key(40, 84.5, lean = 82, arm = lyingArms.first, armB = lyingArms.second, leg = legAng(-90, -90, 0), legB = legAng(-128, -128, 0)),
        ), listOf(Props.mat(-4f, 96f), Prop.Disc(Anchor.Seg(J.KNEE_B, J.ANKLE_B, 17f), 2.2f, Ink.METAL)), rig = FRONT, viewLabel = "vista de frente (deitado de lado)")

        val cableSide = waistFront()
        list += Motion("abducao_polia", "Abdução de quadril na polia (em pé)", listOf(
            key(50, 49.6, arm = cableSide.first, armB = reach(16, 42, P(-1f, 0f)), leg = legAng(1, 1, 60), legB = foot(45.5, angle = -60, bend = OUT_L)),
            key(49, 49.6, lean = -4, arm = cableSide.first, armB = reach(16, 42, P(-1f, 0f)), leg = legAng(32, 32, 60), legB = foot(45.5, angle = -60, bend = OUT_L)),
        ), Props.column(12f, 30f) + Props.cable(p(14, 88), Anchor.On(J.ANKLE)), rig = FRONT)

        // Panturrilha sentado: ponta do pé fixa no degrau, o joelho sobe com o apoio.
        val seatedCalfHands = Limb.Reach(Anchor.On(J.KNEE, -4f, -5f), P(-1f, 0.4f))
        list += Motion("panturrilha_sentado", "Panturrilha sentado (máquina)", listOf(
            key(40, 66, lean = -5, arm = seatedCalfHands, leg = Leg(legReach(60.2, 87.7), 105f)),
            key(40, 66, lean = -5, arm = seatedCalfHands, leg = Leg(legReach(62.4, 79.5), 45f)),
        ), listOf(
            Props.box(64f, 82f, 87.2f), Props.pad(p(30, 70), p(48, 70), 3.4f), Props.frame(p(40, 72), p(40, GROUND), 2.2f),
            Props.pad(Anchor.On(J.KNEE, -7f, -3.8f), Anchor.On(J.KNEE, 2f, -3.8f), 3.2f, Layer.FRONT),
            Prop.Line(pt(80, 40), Anchor.On(J.KNEE, 2f, -3.8f), 2f, Ink.FRAME), Props.frame(p(80, 40), p(80, GROUND), 2.2f),
        ))

        // Rosca Scott: braço apoiado no banco inclinado, só o antebraço se move.
        list += Motion("rosca_scott", "Rosca Scott", listOf(
            key(38, 66, lean = 15, head = -5, arm = ang(45, 40), leg = foot(55)),
            key(38, 66, lean = 15, head = -5, arm = ang(45, 172), leg = foot(55)),
        ), listOf(
            Props.pad(p(46.8, 47.8), p(58.2, 57.4), 3.2f), Props.frame(p(57, 58), p(57, GROUND), 2.2f),
            Props.pad(p(30, 70), p(46, 70), 3.4f), Props.frame(p(38, 72), p(38, GROUND), 2.2f),
        ) + Props.barbell(Anchor.On(J.WRIST), 5.5f))

        // Abdominal no banco declinado: pés presos no alto, cabeça mais baixa que o quadril.
        val declineLegs = foot(66, 74, angle = 150, bend = KNEE_UP)
        list += Motion("abdominal_declinado", "Abdominal no banco declinado", listOf(
            key(48, 74.5, lean = -108, head = 25, arm = ang(25, 150, rel = true), leg = declineLegs),
            key(48, 74.5, lean = -42, head = 15, arm = ang(25, 150, rel = true), leg = declineLegs),
        ), listOf(
            Props.pad(p(16, 87), p(62, 73.5), 3.4f), Props.frame(p(22, 87), p(22, GROUND), 2f), Props.frame(p(58, 76), p(58, GROUND), 2f),
            Prop.Disc(pt(68.5, 70.5), 2.4f, Ink.PAD), Prop.Disc(pt(64.5, 78.5), 2.4f, Ink.PAD, layer = Layer.BACK),
        ))

        // --- Variações com acessório ou aparelho próprio ---------------------------------
        // Concha vista de frente e do alto (deitado de lado): o que vem para quem olha desce no
        // desenho. Quadris e joelhos flexionados, pés juntos; só o joelho de cima abre. As pernas
        // usam Limb.Free porque, girando em direção a quem olha, a coxa muda de tamanho aparente.
        val shellRig = FRONT.copy(hipHalf = 3.7f, shoulderHalf = 6.6f)
        val shellFoot = -80f
        // Braço de cima apoiado no quadril (um pouco inclinado: o tronco aqui é mais estreito).
        fun shell(topKnee: P, topAnkle: P) = key(46, 79, lean = 86, arm = lyingArms.first, armB = ang(-80, -80),
            leg = Leg(Limb.Free(pt(32.2, 90), pt(18.4, 82.3)), shellFoot),
            legB = Leg(Limb.Free(Anchor.At(topKnee), Anchor.At(topAnkle)), shellFoot))
        list += Motion("concha", "Concha (abertura de joelhos deitado de lado)", listOf(
            shell(p(32.6, 85.6), p(18.9, 78.6)),
            shell(p(31, 68.5), p(18.9, 78.6)),
        ), listOf(
            Prop.Block(2f, 80f, 98f, GROUND, Ink.PAD),
            Props.band(Anchor.Seg(J.HIP, J.KNEE, 12f), Anchor.Seg(J.HIP_B, J.KNEE_B, 12f)),
        ), rig = shellRig, viewLabel = "vista de frente e do alto (deitado de lado)")

        // Sumô com um halter seguro pela cabeça, pendurado entre as pernas (vista de frente).
        val sumoHands = reach(51, 55.5, P(0f, 1f))
        list += Motion("agachamento_sumo", "Agachamento sumô com halter entre as pernas", listOf(
            sym(key(50, 51.5, arm = sumoHands, leg = foot(66, angle = 60, bend = OUT_R))),
            sym(key(50, 68, arm = reach(51, 72, P(0f, 1f)), leg = foot(66, angle = 60, bend = OUT_R))),
        ), listOf(
            Prop.Line(Anchor.On(J.WRIST, -1f, 1f), Anchor.On(J.WRIST, -1f, 10f), 1.8f, Ink.METAL, Layer.FRONT),
            Prop.Disc(Anchor.On(J.WRIST, -1f, 1.5f), 2.8f, Ink.METAL),
            Prop.Disc(Anchor.On(J.WRIST, -1f, 10.5f), 3.2f, Ink.METAL),
        ), rig = FRONT.copy(armsOverTorso = true))

        // Bola suíça: bola apoiada no chão (centro em y = GROUND − raio).
        val ballR = 8f
        fun ball(at: Anchor) = Prop.Disc(at, ballR, Ink.PAD, layer = Layer.BACK)
        // Flexora na bola: ponte com os calcanhares sobre a bola, que rola em direção ao glúteo.
        val ballArms = ang(86, 90)
        list += Motion("flexora_bola", "Flexão de joelhos na bola suíça", listOf(
            key(40, 76, lean = -118.7, head = 48.7, arm = ballArms, leg = Leg(legReach(77, 74.5, KNEE_UP), 160f)),
            key(37.3, 72, lean = -129.8, head = 59.8, arm = ballArms, leg = Leg(legReach(58, 74.5, KNEE_UP), 160f)),
        ), listOf(Props.mat(2f, 52f), ball(Anchor.On(J.ANKLE, 1f, GROUND - ballR - 74.5f))))

        // Abdominal na bola: lombar apoiada na bola, pés no chão; o tronco enrola para cima.
        val ballFeet = Leg(legReach(68, 89, KNEE_UP), 76f)
        list += Motion("abdominal_bola", "Abdominal na bola suíça", listOf(
            key(52, 75, lean = -100, head = 20, arm = ang(25, 150, rel = true), leg = ballFeet),
            key(52, 75, lean = -60, head = 20, arm = ang(25, 150, rel = true), leg = ballFeet),
        ), listOf(ball(pt(46, GROUND - ballR))))

        // Coice na máquina de glúteo: tronco e antebraços apoiados, o pé empurra a alavanca para trás.
        list += Motion("coice_maquina", "Coice na máquina de glúteo", listOf(
            key(48, 50.5, lean = 55, head = -15, arm = ang(0, 90), leg = legAng(15, -30, 80), legB = foot(50)),
            key(48, 50.5, lean = 55, head = -15, arm = ang(0, 90), leg = legAng(-38, -45, 80), legB = foot(50)),
        ), listOf(
            Props.frame(p(36, GROUND - 0.8f), p(84, GROUND - 0.8f), 2f),
            Props.frame(p(60, 49), p(60, GROUND), 2.2f),
            Props.pad(Anchor.Body(4f, 4.6f), Anchor.Body(16f, 4.6f), 3.6f, Layer.MID),
            Props.frame(p(80, 55), p(80, GROUND), 2.2f),
            Props.pad(p(64, 53.8), p(86, 53.8), 3.2f),
        ) + Props.lever(p(44, 56), Anchor.On(J.ANKLE)))

        // Elevação pélvica na máquina: encosto, cinto/almofada sobre o quadril preso por cabo à base
        // (a resistência puxa o quadril para baixo) e plataforma para os pés, tudo numa estrutura só.
        val thrustFeet = foot(64)
        val thrustHands = grip(1, 5.5, P(0f, -1f))
        val thrustKeys = listOf(
            key(39.3, 83.5, lean = -54.5, head = 20, arm = thrustHands, leg = thrustFeet),
            key(44, 69, lean = -90, head = 20, arm = thrustHands, leg = thrustFeet),
        )
        val beltPad = Anchor.Body(1f, 5.4f)
        list += Motion("hip_thrust_maquina", "Elevação pélvica na máquina", thrustKeys, listOf(
            Props.frame(p(4, 74), p(4, GROUND), 1.6f), Props.frame(p(17, 74), p(17, GROUND), 1.6f),
            Props.frame(p(2, GROUND - 0.8f), p(78, GROUND - 0.8f), 2f),
            Props.pad(p(1.7, 73.7), p(19.3, 73.7), 3.4f),
            Prop.Block(54f, 89.4f, 78f, GROUND, Ink.PAD),
        ) + Props.cable(p(42, 90), beltPad) + listOf(Props.pad(Anchor.Body(-2.5f, 5.6f), Anchor.Body(4.5f, 5.6f), 3.2f, Layer.FRONT)))

        // Elevação pélvica no Smith: a barra corre num trilho vertical (atrás do corpo).
        list += Motion("hip_thrust_smith", "Elevação pélvica no Smith", thrustKeys,
            listOf(Props.frame(p(42, 30), p(42, GROUND), 1.8f)) + Props.bench(0f, 21f, 72f) + Props.barbell(Anchor.Body(1f, 4.6f), 7f))

        // Barra assistida na máquina: ajoelhado na plataforma, que sobe junto (coluna telescópica).
        val pullHands = reach(52, -14, P(-1f, 0.4f))
        val kneelOnPad = legAng(2, -88, 80)
        list += Motion("barra_fixa_maquina", "Barra fixa assistida na máquina", listOf(
            key(49.3, 38.5, lean = 4, arm = pullHands, leg = kneelOnPad),
            key(52.84, 19.6, lean = -10, head = -15, arm = pullHands, leg = kneelOnPad),
        ), Props.fixedBar(52f, -14f, postX = 28f) + listOf(
            // A coluna sobe e desce na vertical com a plataforma (não inclina quando o corpo avança).
            Prop.Line(Anchor.On(J.KNEE, -6f, 4.5f), Anchor.Plumb(J.KNEE, -6f, GROUND), 2.2f, Ink.FRAME),
            Props.pad(Anchor.On(J.KNEE, 1.5f, 3.4f), Anchor.On(J.KNEE, -15f, 3.4f), 3f),
        ))

        // Ponte com mini band: a faixa laranja abraça as coxas logo acima dos joelhos.
        val bridgeArms = ang(84, 90)
        list += Motion("ponte_mini_band", "Ponte de glúteo com mini elástico", listOf(
            key(45, 88.5, lean = -90, head = 20, arm = bridgeArms, leg = foot(62, bend = KNEE_UP)),
            key(40.9, 74.7, lean = -123.5, head = 53.5, arm = bridgeArms, leg = foot(62, bend = KNEE_UP)),
        ), listOf(Props.mat(4f, 76f), Prop.Line(Anchor.Seg(J.HIP, J.KNEE, 15f, -2.7f), Anchor.Seg(J.HIP, J.KNEE, 15f, 2.7f), 2.2f, Ink.BAND, Layer.FRONT)))

        list += Motion("em_pe", "Posição em pé", listOf(
            key(50, 49.6, arm = ang(4), leg = foot(50)),
            key(50, 49.9, arm = ang(6), leg = foot(50)),
        ))
        return list
    }
}
