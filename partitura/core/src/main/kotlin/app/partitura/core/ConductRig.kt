package app.partitura.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Ponto 3D em metros: x = direita de quem rege, y = para cima, z = para frente (em direção ao coro). */
class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun len() = sqrt(dot(this))
    fun norm(): V3 { val l = len().coerceAtLeast(1e-6f); return V3(x / l, y / l, z / l) }
    fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
}

/** Peça do corpo: cápsula cônica (a→b, raios r0→r1), elipsoide (centro a, raios rx,ry,rz em b) ou esfera. [face] marca a cabeça. */
class Prim(val kind: Int, val a: V3, val b: V3, val r0: Float, val r1: Float, val color: Int, val face: Boolean = false, val bias: Float = 0f) {
    companion object { const val CAPSULE = 0; const val ELLIPSOID = 1 }
}

/**
 * Corpo inteiro do regente. O movimento sai do próprio desenho do gesto (mão direita), passando por uma cadeia cinemática
 * com atraso: punho → mão → cotovelo → ombro → tronco → quadril → joelhos → cabeça. Cada elo repete o gesto um instante depois e
 * menor, o que dá a fluidez (o corpo "segue" a mão em vez de só mexer os braços). Nada é guardado entre quadros: tudo é função da fase.
 */
object ConductRig {
    private const val SKIN = 0xFFE0AC85.toInt()
    private const val SHIRT = 0xFFF1F1F1.toInt()
    private const val JACKET = 0xFF1E2A47.toInt()
    private const val JACKET2 = 0xFF26355A.toInt()
    private const val TROUSERS = 0xFF171B24.toInt()
    private const val SHOES = 0xFF0D0D0D.toInt()
    private const val HAIR = 0xFF2E2118.toInt()
    private const val TIE = 0xFF7A1F2B.toInt()

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }

    /** Dois ossos: devolve a junta do meio (cotovelo/joelho) para ligar [s] a [t]; [pole] = para onde a junta aponta. */
    private fun twoBone(s: V3, t: V3, l1: Float, l2: Float, pole: V3): V3 {
        val dv = t - s
        val d = min(dv.len(), l1 + l2 - 0.002f).coerceAtLeast(0.05f)
        val dir = dv.norm()
        val a = (l1 * l1 - l2 * l2 + d * d) / (2f * d)
        val h = sqrt(max(l1 * l1 - a * a, 0f))
        val po = (pole - dir * pole.dot(dir)).norm()
        return s + dir * a + po * h
    }

    private fun handWorld(px: Float, py: Float) = V3(0.16f + (px - 0.5f) * 0.70f, 1.58f - py * 0.66f, 0.38f + (py - 0.5f) * 0.12f)

    /**
     * [phase] = fase do gesto (ver [ConductPattern.position]); negativo = parado (só respira). [t] = segundos (respiração).
     * [twoHands] = mão esquerda espelha a direita; senão fica relaxada ao lado do corpo, acompanhando o balanço.
     */
    fun pose(pattern: ConductPattern, phase: Double, twoHands: Boolean, t: Double): List<Prim> {
        val moving = phase >= 0
        fun at(lag: Double): Pt = if (moving) pattern.position(phase - lag) else pattern.waypoints.last()
        val breath = sin(t * 1.9).toFloat() * (if (moving) 0.004f else 0.008f)
        val sway = if (moving) 0f else sin(t * 0.7).toFloat() * 0.006f

        // elos da cadeia (quanto maior o atraso, mais "atrás" na onda de movimento)
        val hand = at(0.0); val wristP = at(-0.02); val elbowP = at(0.07); val shP = at(0.12); val chestP = at(0.17); val hipP = at(0.24); val headP = at(0.20)

        // ---- coluna ----
        val low = smooth(0.45f, 0.88f, chestP.y)                         // 0 = mão alta, 1 = ictus baixo
        val hipLow = smooth(0.45f, 0.88f, hipP.y)
        val dip = 0.045f * hipLow                                          // quadril desce no tempo forte (joelhos dobram)
        val hipShift = -(hipP.x - 0.5f) * 0.10f + sway                     // peso muda de um pé para o outro
        val pelvis = V3(hipShift, 0.97f - dip + breath, 0.0f)
        val leanX = (chestP.x - 0.5f) * 0.14f                              // tronco acompanha para o lado do gesto
        val leanZ = 0.020f + low * 0.065f - (1f - low) * 0.012f           // inclina para a frente no ictus, volta ao subir
        val chest = pelvis + V3(leanX, 0.50f, leanZ)
        val neck = chest + V3(leanX * 0.35f, 0.115f, leanZ * 0.45f)
        val nod = (headP.y - 0.5f) * 0.03f
        val head = neck + V3(leanX * 0.3f + (headP.x - 0.5f) * 0.03f, 0.135f - nod * 0.4f, 0.012f + nod)
        // ombros: linha gira e sobe do lado da mão
        val rot = (shP.x - 0.5f) * 0.30f
        val shoulderC = chest + V3(0f, 0.01f, 0f)
        val shR0 = shoulderC + V3(0.195f * cos(rot), (0.5f - shP.y) * 0.035f, 0.195f * sin(rot))
        val shL0 = shoulderC + V3(-0.195f * cos(rot), 0f, -0.195f * sin(rot))
        val shR = shR0; val shL = shL0

        // ---- pernas: pés plantados, joelhos dobram quando o quadril desce ----
        val footR = V3(0.135f, 0.045f, 0.01f); val footL = V3(-0.135f, 0.045f, 0.01f)
        val hipR = pelvis + V3(0.105f, -0.02f, 0f); val hipL = pelvis + V3(-0.105f, -0.02f, 0f)
        val kneeR = twoBone(hipR, footR + V3(0f, 0.03f, 0f), 0.46f, 0.46f, V3(0.15f, 0f, 1f))
        val kneeL = twoBone(hipL, footL + V3(0f, 0.03f, 0f), 0.46f, 0.46f, V3(-0.15f, 0f, 1f))

        // ---- braço direito: segue a mão ----
        val upper = 0.30f; val fore = 0.27f
        val wristR = handWorld(wristP.x, wristP.y)
        val elR = twoBone(shR, wristR, upper, fore, V3(0.55f, -1f, -0.35f))
        // ---- braço esquerdo ----
        val wristL: V3; val elL: V3
        if (twoHands && moving) {
            val m = at(0.02)
            val w = handWorld(1f - m.x, m.y)
            wristL = V3(-w.x + 0.0f, w.y, w.z)
            elL = twoBone(shL, wristL, upper, fore, V3(-0.55f, -1f, -0.35f))
        } else {
            // relaxado: cai ao lado do corpo e balança de leve com o tronco
            wristL = shL + V3(-0.045f + leanX * 0.5f, -0.50f + breath * 2f, 0.075f + low * 0.03f)
            elL = twoBone(shL, wristL, upper, fore, V3(-0.35f, -0.25f, -1f))
        }

        val out = ArrayList<Prim>(60)
        fun cap(a: V3, b: V3, r0: Float, r1: Float, c: Int, bias: Float = 0f) { out += Prim(Prim.CAPSULE, a, b, r0, r1, c, false, bias) }
        fun ell(c: V3, rx: Float, ry: Float, rz: Float, col: Int, face: Boolean = false, bias: Float = 0f) { out += Prim(Prim.ELLIPSOID, c, V3(rx, ry, rz), 0f, 0f, col, face, bias) }

        // pernas e sapatos
        cap(hipR, kneeR, 0.075f, 0.058f, TROUSERS); cap(kneeR, footR + V3(0f, 0.03f, 0f), 0.058f, 0.045f, TROUSERS)
        cap(hipL, kneeL, 0.075f, 0.058f, TROUSERS); cap(kneeL, footL + V3(0f, 0.03f, 0f), 0.058f, 0.045f, TROUSERS)
        cap(footR + V3(0f, 0.02f, -0.03f), footR + V3(0.01f, 0.02f, 0.14f), 0.042f, 0.036f, SHOES)
        cap(footL + V3(0f, 0.02f, -0.03f), footL + V3(-0.01f, 0.02f, 0.14f), 0.042f, 0.036f, SHOES)
        // quadril e tronco (elipsoides), paletó, camisa e gravata
        ell(pelvis + V3(0f, 0.03f, 0f), 0.165f, 0.10f, 0.105f, TROUSERS, bias = 0.02f)
        val waist = pelvis.lerp(chest, 0.45f)
        ell(waist + V3(0f, 0.02f, 0f), 0.150f, 0.145f, 0.098f, JACKET, bias = 0.02f)
        ell(chest + V3(0f, -0.02f, 0f), 0.185f, 0.150f, 0.112f, JACKET2, bias = 0.02f)
        ell(chest + V3(0f, 0.02f, 0.085f), 0.055f, 0.11f, 0.025f, SHIRT, bias = -0.06f)       // camisa no peito (frente)
        ell(chest + V3(0f, -0.01f, 0.108f), 0.014f, 0.095f, 0.012f, TIE, bias = -0.07f)        // gravata
        cap(shL, shR, 0.062f, 0.062f, JACKET2, bias = 0.0f)                                  // ombros
        cap(neck + V3(0f, -0.07f, 0f), neck + V3(0f, 0.035f, 0f), 0.045f, 0.040f, SKIN, bias = 0.01f)
        // cabeça: crânio + cabelo (atrás) + nariz para marcar a frente
        ell(head, 0.082f, 0.105f, 0.095f, SKIN, face = true)
        ell(head + V3(0f, 0.025f, -0.018f), 0.087f, 0.095f, 0.095f, HAIR, bias = 0.03f)
        // braços
        cap(shR, elR, 0.056f, 0.047f, JACKET2, 0.02f); cap(elR, wristR, 0.047f, 0.038f, JACKET, 0.02f)
        cap(shL, elL, 0.056f, 0.047f, JACKET2, 0.02f); cap(elL, wristL, 0.047f, 0.038f, JACKET, 0.02f)
        cap(wristR + (elR - wristR).norm() * -0.0f, wristR + (wristR - elR).norm() * 0.02f, 0.040f, 0.034f, SHIRT)  // punho de camisa
        cap(wristL, wristL + (wristL - elL).norm() * 0.02f, 0.040f, 0.034f, SHIRT)
        // mãos
        val speed = if (moving) { val a = at(-0.03); val b = at(0.03); V3((a.x - b.x) * 0.70f, (b.y - a.y) * 0.62f, 0f) } else V3(0f, 0f, 0f)
        val ictusness = if (moving) smooth(0.55f, 0.88f, hand.y) else 0f
        hand(out, elR, wristR, +1f, speed, ictusness)
        hand(out, elL, wristL, -1f, if (twoHands && moving) V3(-speed.x, speed.y, 0f) else V3(0f, 0f, 0f), if (twoHands && moving) ictusness else 0.55f)
        return out
    }

    /** Mão: palma + quatro dedos de duas falanges + polegar. A palma olha para baixo; os dedos se fecham um pouco no ictus e abrem no ar. */
    private fun hand(out: MutableList<Prim>, elbow: V3, wrist: V3, side: Float, vel: V3, ictus: Float) {
        // direção para a frente da mão: antebraço, puxada um pouco pela velocidade (o punho "conduz")
        var f = (wrist - elbow).norm()
        f = (f + V3(vel.x * 1.5f, vel.y * 0.8f, 0f)).norm()
        val up = V3(0f, 1f, 0f)
        val s = up.cross(f).norm()                      // para o lado (x+ quando f = +z)
        val palmUp = f.cross(s).norm()                  // normal da palma (para cima)
        val knuckle = wrist + f * 0.085f
        // palma
        out += Prim(Prim.CAPSULE, wrist + f * 0.01f, knuckle, 0.040f, 0.043f, SKIN, false, -0.01f)
        out += Prim(Prim.CAPSULE, knuckle + s * (-0.032f * side), knuckle + s * (0.036f * side), 0.017f, 0.015f, SKIN, false, -0.01f)
        val curl = 0.35f + 0.65f * ictus                 // 0 = esticado, 1 = bem curvo
        val lens = floatArrayOf(0.066f, 0.074f, 0.070f, 0.056f)
        for (i in 0 until 4) {
            val across = (-0.030f + 0.020f * i) * side    // do indicador (perto do polegar) ao mínimo
            val spread = (i - 1.5f) * 0.10f * side
            val base = knuckle + s * across
            val d1 = (f + s * spread - palmUp * (0.25f * curl)).norm()
            val k1 = base + d1 * (lens[i] * 0.55f)
            val d2 = (f + s * spread * 0.6f - palmUp * (0.85f * curl)).norm()
            val k2 = k1 + d2 * (lens[i] * 0.45f)
            out += Prim(Prim.CAPSULE, base, k1, 0.0135f, 0.012f, SKIN, false, -0.015f)
            out += Prim(Prim.CAPSULE, k1, k2, 0.012f, 0.0105f, SKIN, false, -0.015f)
        }
        // polegar: sai do lado de dentro da palma
        val tb = wrist + f * 0.035f + s * (-0.038f * side)
        val td = (f * 0.7f + s * (-0.55f * side) - palmUp * (0.2f * curl)).norm()
        val t1 = tb + td * 0.045f; val t2 = t1 + (td + f * 0.4f - palmUp * (0.3f * curl)).norm() * 0.035f
        out += Prim(Prim.CAPSULE, tb, t1, 0.016f, 0.014f, SKIN, false, -0.015f)
        out += Prim(Prim.CAPSULE, t1, t2, 0.014f, 0.012f, SKIN, false, -0.015f)
    }

    // ------------------------------------------------------------------ câmera e desenho

    /** Quem desenha (Compose ou Java2D) implementa isto; coordenadas de tela em pixels. */
    interface Painter {
        fun capsule(x0: Float, y0: Float, r0: Float, x1: Float, y1: Float, r1: Float, argb: Int)
        fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, argb: Int)
        fun line(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, argb: Int)
        fun shadow(cx: Float, cy: Float, rx: Float, ry: Float)
    }

    private fun shade(c: Int, k: Float): Int {
        val r = (((c shr 16) and 255) * k).toInt().coerceIn(0, 255); val g = (((c shr 8) and 255) * k).toInt().coerceIn(0, 255); val b = ((c and 255) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /**
     * Projeta e desenha [prims]. [yawDeg]: 0 = de costas (visão de quem rege), 90 = de lado, 180 = de frente.
     * A câmera é levemente alta; peças mais longe saem um pouco mais escuras e são pintadas primeiro.
     */
    fun draw(prims: List<Prim>, yawDeg: Float, width: Float, height: Float, p: Painter) {
        val th = yawDeg * PI.toFloat() / 180f
        val ct = cos(th); val st = sin(th)
        val pitch = 0.10f; val cp = cos(pitch); val sp = sin(pitch)
        val scale = height * 0.52f
        val cx = width / 2f; val base = height * 0.955f
        fun depth(v: V3) = -v.x * st + v.z * ct
        fun sx(v: V3): Float { val xr = v.x * ct + v.z * st; return cx + xr * scale }
        fun sy(v: V3): Float { val zr = depth(v); return base - (v.y * cp + zr * sp) * scale }
        p.shadow(cx, base - 0.01f * scale, 0.30f * scale, 0.07f * scale)
        class Item(val prim: Prim, val d: Float)
        val items = prims.map { pr ->
            val d = if (pr.kind == Prim.CAPSULE) (depth(pr.a) + depth(pr.b)) / 2f else depth(pr.a)
            Item(pr, d + pr.bias)
        }.sortedByDescending { it.d }
        val front = depth(V3(0f, 0f, 1f))   // >0: a frente do corpo aponta para longe da câmera (vemos as costas)
        for (it in items) {
            val pr = it.prim
            val k = (1.08f - (it.d + 0.4f) * 0.28f).coerceIn(0.62f, 1.12f)
            val col = shade(pr.color, k)
            val edge = shade(pr.color, k * 0.55f)
            if (pr.kind == Prim.CAPSULE) {
                val x0 = sx(pr.a); val y0 = sy(pr.a); val x1 = sx(pr.b); val y1 = sy(pr.b)
                val o = max(1.2f, scale * 0.0035f)
                p.capsule(x0, y0, pr.r0 * scale + o, x1, y1, pr.r1 * scale + o, edge)
                p.capsule(x0, y0, pr.r0 * scale, x1, y1, pr.r1 * scale, col)
            } else {
                val rx = pr.b.x; val ry = pr.b.y; val rz = pr.b.z
                val hw = sqrt((rx * ct) * (rx * ct) + (rz * st) * (rz * st)) * scale
                val cxp = sx(pr.a); val cyp = sy(pr.a)
                val o = max(1.2f, scale * 0.0035f)
                p.ellipse(cxp, cyp, hw + o, ry * scale + o, edge)
                p.ellipse(cxp, cyp, hw, ry * scale, col)
                if (pr.face && front < -0.3f) {
                    // de frente: olhos, nariz e boca
                    val e = 0.038f * scale
                    val ex = rx * scale * 0.42f * abs(ct).coerceAtLeast(0.2f)
                    p.ellipse(cxp - ex, cyp - ry * scale * 0.08f, e * 0.45f, e * 0.30f, 0xFF1B1B1B.toInt())
                    p.ellipse(cxp + ex, cyp - ry * scale * 0.08f, e * 0.45f, e * 0.30f, 0xFF1B1B1B.toInt())
                    p.line(cxp, cyp - ry * scale * 0.02f, cxp, cyp + ry * scale * 0.18f, scale * 0.006f, shade(pr.color, 0.8f))
                    p.line(cxp - ex * 0.6f, cyp + ry * scale * 0.42f, cxp + ex * 0.6f, cyp + ry * scale * 0.42f, scale * 0.007f, 0xFF8A4B3C.toInt())
                } else if (pr.face && abs(front) <= 0.3f) {
                    // de lado: nariz saliente do lado da frente
                    val dir = if (st > 0f) 1f else -1f
                    p.capsule(cxp + dir * hw * 0.92f, cyp, scale * 0.012f, cxp + dir * (hw + scale * 0.016f), cyp + ry * scale * 0.04f, scale * 0.008f, shade(pr.color, k * 0.95f))
                }
            }
        }
    }

    /** Legenda do ponto de vista. */
    fun viewName(yaw: Float): String {
        val y = ((yaw % 360f) + 360f) % 360f
        return when {
            y < 30f || y > 330f -> "Vista de costas (como quem rege vê)"
            abs(y - 90f) <= 30f -> "Vista de lado (esquerda de quem rege)"
            abs(y - 180f) <= 30f -> "Vista de frente (como o coro vê)"
            abs(y - 270f) <= 30f -> "Vista de lado (direita de quem rege)"
            else -> "Girando…"
        }
    }
}
