package app.partitura

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
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
                flashKey = key
                scope.launch { flash.snapTo(1f); flash.animateTo(0f, tween(420)) }
                tryAwaitRelease()
            })
        },
    ) {
        val kw = size.width / whites.size
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = kw * 0.34f; color = android.graphics.Color.argb(150, 30, 30, 36); textAlign = Paint.Align.CENTER }
        val round = CornerRadius(kw * 0.12f, kw * 0.12f)
        val fl = flash.value
        whites.forEachIndexed { i, m ->
            val lit = m in active
            val base = when { lit -> roleColor(roles[m], m in attack); m in marks -> Color(0xFFFBE3B0); else -> Color(0xFFF7F4EC) }
            val x = i * kw + 0.8f; val w = kw - 1.6f
            // corpo da tecla com leve degradê (mais escuro embaixo) e cantos de baixo arredondados
            drawRoundRect(Brush.verticalGradient(listOf(base, if (lit || m in marks) base else Color(0xFFE4DFD0)), 0f, size.height), Offset(x, -round.y), Size(w, size.height + round.y), round)
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
            val color = when { lit -> roleColor(roles[m], m in attack); m in marks -> Color(0xFF8A6A2A); else -> Color(0xFF1C1C22) }
            val bh = size.height * 0.62f
            drawRoundRect(Brush.verticalGradient(listOf(if (lit || m in marks) color else Color(0xFF34343C), color), 0f, bh), Offset(cx - bw / 2, -round.y), Size(bw, bh + round.y), CornerRadius(bw * 0.18f, bw * 0.18f))
            if (flashKey == m && fl > 0f) drawRoundRect(Color(Roles.ARGB[0]).copy(alpha = 0.6f * fl), Offset(cx - bw / 2, -round.y), Size(bw, bh + round.y), CornerRadius(bw * 0.18f, bw * 0.18f))
        }
    }
}
