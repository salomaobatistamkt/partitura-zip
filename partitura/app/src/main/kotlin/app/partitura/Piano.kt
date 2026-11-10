package app.partitura

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun roleColor(role: Int?, fresh: Boolean): Color {
    val c = Color(Roles.ARGB[role ?: 0])
    return if (fresh) c else c.copy(alpha = 0.78f)
}

private val WHITE_PC = setOf(0, 2, 4, 5, 7, 9, 11)

/** Teclado de piano. [active] = notas soando agora (âmbar); [marks] = notas da escala/acorde (suave). */
@Composable
fun PianoKeyboard(first: Int, last: Int, active: Set<Int>, marks: Set<Int>, onKey: (Int) -> Unit, height: Dp = 96.dp, modifier: Modifier = Modifier, roles: Map<Int, Int> = emptyMap(), attack: Set<Int> = emptySet()) {
    val whites = (first..last).filter { it % 12 in WHITE_PC }
    // a tecla tocada acende e some aos poucos (150 ms para acender, ~400 ms para apagar)
    val scope = rememberCoroutineScope()
    val flash = remember { Animatable(0f) }
    var flashKey by remember { mutableStateOf(-1) }
    var pressed by remember { mutableStateOf(-1) }
    // brilho que apaga aos poucos quando a nota para de soar (em vez de sumir de uma vez)
    val glow = remember { mutableStateMapOf<Int, Float>() }
    LaunchedEffect(active) {
        active.forEach { glow[it] = 1f }
        while (true) {
            var any = false
            for (k in glow.keys.toList()) if (k !in active) { val v = (glow[k] ?: 0f) - 0.07f; if (v <= 0f) glow.remove(k) else { glow[k] = v; any = true } }
            if (!any) break
            withFrameNanos { }
        }
    }
    Canvas(
        modifier.fillMaxWidth().height(height).pointerInput(first, last) {
            detectTapGestures(onPress = { off ->
                val w = size.width.toFloat(); val h = size.height.toFloat()
                val kw = w / whites.size
                var black: Int? = null
                val bw = kw * 0.62f
                for (m in first..last) {
                    if (m % 12 in WHITE_PC) continue
                    val wi = whites.indexOfFirst { it > m }.let { if (it < 0) whites.size else it }
                    val cx = wi * kw
                    if (off.x >= cx - bw / 2 && off.x <= cx + bw / 2 && off.y <= h * 0.62f) { black = m; break }
                }
                val key = black ?: whites[(off.x / kw).toInt().coerceIn(0, whites.size - 1)]
                onKey(key)
                flashKey = key; pressed = key
                scope.launch { flash.snapTo(1f); flash.animateTo(0f, tween(420)) }
                tryAwaitRelease()
                pressed = -1
            })
        },
    ) {
        val kw = size.width / whites.size
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = kw * 0.34f; color = android.graphics.Color.argb(150, 30, 30, 36); textAlign = Paint.Align.CENTER }
        val round = CornerRadius(kw * 0.12f, kw * 0.12f)
        val fl = flash.value
        whites.forEachIndexed { i, m ->
            val lit = m in active
            val g = if (lit) 1f else (glow[m] ?: 0f)
            val idle = if (m in marks) Color(0xFFFBE3B0) else Color(0xFFF7F4EC)
            val base = if (g > 0f) lerp(idle, roleColor(roles[m], m in attack), g) else idle
            val dep = if (pressed == m) 3f else 0f   // tecla apertada afunda um pouco
            val x = i * kw + 0.8f; val w = kw - 1.6f
            // corpo da tecla com degradê (mais escuro embaixo), brilho no topo, borda fina e sombra na base
            drawRoundRect(Brush.verticalGradient(listOf(base, if (g > 0f || m in marks) base else Color(0xFFE0DACB)), 0f, size.height), Offset(x, -round.y + dep), Size(w, size.height + round.y - dep), round)
            drawRoundRect(Color(0x26000000), Offset(x, -round.y + dep), Size(w, size.height + round.y - dep), round, style = androidx.compose.ui.graphics.drawscope.Stroke(1.2f))
            drawRect(Color(0x33000000), Offset(x + 1f, size.height - 5f), Size(w - 2f, 4f))
            if (flashKey == m && fl > 0f) drawRoundRect(Color(Roles.ARGB[0]).copy(alpha = 0.55f * fl), Offset(x, -round.y), Size(w, size.height + round.y), round)
            if (m % 12 == 0) drawIntoCanvas { it.nativeCanvas.drawText("C${m / 12 - 1}", i * kw + kw / 2, size.height - 6f, labelPaint) }
        }
        val bw = kw * 0.62f
        for (m in first..last) {
            if (m % 12 in WHITE_PC) continue
            val wi = whites.indexOfFirst { it > m }
            if (wi < 0) continue
            val cx = wi * kw
            val lit = m in active
            val g = if (lit) 1f else (glow[m] ?: 0f)
            val idle = if (m in marks) Color(0xFF8A6A2A) else Color(0xFF1C1C22)
            val color = if (g > 0f) lerp(idle, roleColor(roles[m], m in attack), g) else idle
            val dep = if (pressed == m) 3f else 0f
            val bh = size.height * 0.62f - dep
            drawRoundRect(Brush.verticalGradient(listOf(if (g > 0f || m in marks) color else Color(0xFF3A3A44), color), 0f, bh), Offset(cx - bw / 2, -round.y + dep), Size(bw, bh + round.y), CornerRadius(bw * 0.18f, bw * 0.18f))
            if (g == 0f && m !in marks) drawRoundRect(Color(0x22FFFFFF), Offset(cx - bw / 2 + bw * 0.14f, dep), Size(bw * 0.22f, bh * 0.82f), CornerRadius(bw * 0.08f, bw * 0.08f))
            if (flashKey == m && fl > 0f) drawRoundRect(Color(Roles.ARGB[0]).copy(alpha = 0.6f * fl), Offset(cx - bw / 2, -round.y), Size(bw, bh + round.y), CornerRadius(bw * 0.18f, bw * 0.18f))
        }
    }
}
