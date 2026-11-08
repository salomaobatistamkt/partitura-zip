package app.partitura

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.pointerInput
import app.partitura.core.ConductPattern
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Ponto 3D em metros: x = direita de quem rege, y = para cima, z = para frente (em direção ao coro). */
private class V(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V) = V(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V) = V(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V(x * k, y * k, z * k)
    fun dot(o: V) = x * o.x + y * o.y + z * o.z
    fun len() = sqrt(dot(this))
    fun norm(): V { val l = len().coerceAtLeast(1e-5f); return V(x / l, y / l, z / l) }
    fun cross(o: V) = V(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
}

private class Seg3(val a: V, val b: V, val w: Float, val c: Color, val depth: Float)

/** Braço de dois ossos: devolve o cotovelo para o ombro [s] alcançar o pulso [t]; [out] = lado do cotovelo (+1 direita, -1 esquerda). */
private fun elbow(s: V, t: V, upper: Float, fore: Float, out: Float): V {
    val dv = t - s
    val d = min(dv.len(), upper + fore - 0.002f).coerceAtLeast(0.05f)
    val dir = dv.norm()
    val a = (upper * upper - fore * fore + d * d) / (2f * d)
    val h = sqrt(max(upper * upper - a * a, 0f))
    val pole = V(0.55f * out, -1f, -0.25f)          // cotovelo para baixo, um pouco para fora e para trás
    val pOrth = (pole - dir * pole.dot(dir)).norm()
    return s + dir * a + pOrth * h
}

/**
 * Boneco de regência em 3D (esqueleto projetado). A mão direita segue o desenho do gesto; a esquerda fica em repouso
 * ou espelha a direita. A câmera gira em torno do corpo: [yaw] 0 = de costas (visão de quem rege), 90 = de lado, 180 = de frente.
 */
@Composable
fun ConductFigure(pattern: ConductPattern, phaseNow: () -> Double, twoHands: Boolean, yaw: Float, onYaw: (Float) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val bg = cs.surface; val floorC = cs.outline; val onS = cs.onSurface
    val yawNow = androidx.compose.runtime.rememberUpdatedState(yaw)
    val onYawNow = androidx.compose.runtime.rememberUpdatedState(onYaw)
    Canvas(
        modifier.clip(RoundedCornerShape(20.dp)).background(bg).aspectRatio(1.25f)
            .pointerInput(Unit) { detectDragGestures { _, d -> onYawNow.value(((yawNow.value + d.x * 0.5f) % 360f + 360f) % 360f) } },
    ) {
        val dens = density
        val w = size.width; val h = size.height
        val th = yaw * PI.toFloat() / 180f
        val ct = cos(th); val st = sin(th)
        val scale = h * 0.50f
        val cx = w / 2f; val base = h * 0.94f
        // câmera: gira em torno do eixo vertical; profundidade menor = mais perto da câmera
        fun proj(p: V): Offset { val xr = p.x * ct + p.z * st; val zr = -p.x * st + p.z * ct; val k = 1f / (1f + zr * 0.10f); return Offset(cx + xr * scale * k, base - p.y * scale * k) }
        fun depth(p: V) = -p.x * st + p.z * ct

        val ph = phaseNow()
        val skin = Color(0xFFE2AC85); val shirt = Color(0xFF34476B); val shirtDark = Color(0xFF273654); val trousers = Color(0xFF1F2430); val hair = Color(0xFF3A2A20)
        // alvo da mão direita (pulso) a partir do desenho: x lateral, y altura, z um pouco mais perto do corpo quando baixo
        fun handTarget(px: Float, py: Float): V = V(0.12f + (px - 0.5f) * 0.80f, 1.78f - py * 0.62f, 0.34f + (py - 0.5f) * 0.10f)
        val rp = if (ph >= 0) pattern.position(ph) else null
        val shR = V(0.20f, 1.46f, 0f); val shL = V(-0.20f, 1.46f, 0f)
        val upper = 0.30f; val fore = 0.28f
        val wristR = if (rp != null) handTarget(rp.x, rp.y) else V(0.30f, 1.20f, 0.20f)
        val wristL = if (twoHands && rp != null) handTarget(1f - rp.x, rp.y).let { V(-it.x, it.y, it.z) }
            else V(-0.26f, 1.12f, 0.16f)
        val elR = elbow(shR, wristR, upper, fore, +1f)
        val elL = elbow(shL, wristL, upper, fore, -1f)

        val segs = ArrayList<Seg3>()
        fun seg(a: V, b: V, wd: Float, c: Color) { segs += Seg3(a, b, wd, c, (depth(a) + depth(b)) / 2f) }
        // pernas e tronco
        val hipR = V(0.11f, 0.92f, 0f); val hipL = V(-0.11f, 0.92f, 0f)
        seg(hipR, V(0.13f, 0.48f, 0.02f), 0.115f, trousers); seg(V(0.13f, 0.48f, 0.02f), V(0.14f, 0.04f, 0f), 0.095f, trousers)
        seg(hipL, V(-0.13f, 0.48f, 0.02f), 0.115f, trousers); seg(V(-0.13f, 0.48f, 0.02f), V(-0.14f, 0.04f, 0f), 0.095f, trousers)
        seg(V(0.14f, 0.03f, 0f), V(0.15f, 0.03f, 0.13f), 0.07f, Color(0xFF111111)); seg(V(-0.14f, 0.03f, 0f), V(-0.15f, 0.03f, 0.13f), 0.07f, Color(0xFF111111))
        seg(V(0f, 0.95f, 0f), V(0f, 1.42f, 0f), 0.36f, shirt)                  // tronco largo
        seg(shL, shR, 0.13f, shirtDark)                                         // ombros
        seg(V(0f, 1.46f, 0f), V(0f, 1.56f, 0f), 0.07f, skin)                    // pescoço
        // braços
        seg(shR, elR, 0.10f, shirt); seg(elR, wristR, 0.085f, shirt)
        seg(shL, elL, 0.10f, shirt); seg(elL, wristL, 0.085f, shirt)
        // mãos: palma + dedos na direção do antebraço, polegar de lado
        fun hand(el: V, wr: V, side: Float) {
            val dir = (wr - el).norm()
            val tip = wr + dir * 0.10f
            seg(wr, tip, 0.075f, skin)
            val perp = dir.cross(V(0f, 1f, 0f)).norm()
            seg(wr + dir * 0.02f, wr + dir * 0.06f + perp * (0.05f * side), 0.035f, skin)
        }
        hand(elR, wristR, +1f); hand(elL, wristL, -1f)
        // cabeça
        val head = V(0f, 1.69f, 0.01f)
        segs += Seg3(head, head, 0.22f, skin, depth(head))
        // desenha do mais longe para o mais perto
        segs.sortByDescending { it.depth }
        // chão e sombra
        drawOval(floorC.copy(alpha = 0.18f), Offset(cx - w * 0.22f, base - h * 0.02f), androidx.compose.ui.geometry.Size(w * 0.44f, h * 0.05f))
        for (sg in segs) {
            val a = proj(sg.a); val b = proj(sg.b)
            val kk = 1f / (1f + sg.depth * 0.10f)
            val strokeW = sg.w * scale * kk
            if (sg.a === sg.b) {
                drawCircle(sg.c, strokeW / 2f, a)
                // de costas o cabelo cobre a cabeça; de frente aparecem olhos e boca; de lado, só uma orelha
                val front = depth(V(0f, 0f, 1f))
                if (front > 0.25f) drawCircle(hair, strokeW / 2f * 0.98f, a)
                else if (front < -0.25f) {
                    drawArc(hair, 180f, 180f, true, Offset(a.x - strokeW / 2f, a.y - strokeW / 2f), androidx.compose.ui.geometry.Size(strokeW, strokeW * 0.55f))
                    drawCircle(Color(0xFF222222), strokeW * 0.035f, a + Offset(-strokeW * 0.16f, -strokeW * 0.02f))
                    drawCircle(Color(0xFF222222), strokeW * 0.035f, a + Offset(strokeW * 0.16f, -strokeW * 0.02f))
                    drawLine(Color(0xFF8A4B3C), a + Offset(-strokeW * 0.10f, strokeW * 0.18f), a + Offset(strokeW * 0.10f, strokeW * 0.18f), strokeWidth = strokeW * 0.03f, cap = StrokeCap.Round)
                } else {
                    drawArc(hair, 90f, 180f, true, Offset(a.x - strokeW / 2f, a.y - strokeW / 2f), androidx.compose.ui.geometry.Size(strokeW, strokeW))
                    drawCircle(Color(0xFF222222), strokeW * 0.035f, a + Offset(strokeW * 0.22f * (if (st > 0f) -1f else 1f), -strokeW * 0.02f))
                }
            } else {
                drawLine(sg.c, a, b, strokeWidth = strokeW, cap = StrokeCap.Round)
            }
        }
        // legenda do ponto de vista
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = onS.copy(alpha = 0.7f).toArgb(); textSize = 13f * dens; textAlign = android.graphics.Paint.Align.LEFT }
        val label = when { abs(((yaw + 180f) % 360f) - 180f) < 30f -> "Vista de costas (de quem rege)"; abs(yaw - 90f) < 30f -> "Vista de lado (esquerda do coro)"; abs(yaw - 180f) < 30f -> "Vista de frente (do coro)"; abs(yaw - 270f) < 30f -> "Vista de lado (direita do coro)"; else -> "Girando…" }
        drawContext.canvas.nativeCanvas.drawText(label, 12f * dens, 20f * dens, paint)
    }
}

