package app.partitura

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
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
                tryAwaitRelease()
            })
        },
    ) {
        val kw = size.width / whites.size
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = kw * 0.34f; color = android.graphics.Color.argb(150, 30, 30, 36); textAlign = Paint.Align.CENTER }
        whites.forEachIndexed { i, m ->
            val color = when { m in active -> roleColor(roles[m], m in attack); m in marks -> Color(0xFFFBE3B0); else -> Color(0xFFF7F4EC) }
            drawRect(color, Offset(i * kw + 0.5f, 0f), Size(kw - 1f, size.height))
            if (m % 12 == 0) drawIntoCanvas { it.nativeCanvas.drawText("C${m / 12 - 1}", i * kw + kw / 2, size.height - 6f, labelPaint) }
        }
        val bw = kw * 0.62f
        for (m in first..last) {
            if (m % 12 in WHITE_PC) continue
            val wi = whites.indexOfFirst { it > m }
            if (wi < 0) continue
            val cx = wi * kw
            val color = when { m in active -> roleColor(roles[m], m in attack); m in marks -> Color(0xFF8A6A2A); else -> Color(0xFF1C1C22) }
            drawRect(color, Offset(cx - bw / 2, 0f), Size(bw, size.height * 0.62f))
        }
    }
}
