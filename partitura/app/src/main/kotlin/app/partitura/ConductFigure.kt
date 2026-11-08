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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.partitura.core.ConductPattern
import app.partitura.core.ConductRig

/** Regente de corpo inteiro: o desenho vem de [ConductRig] (core); aqui só se pinta. Arraste para girar a câmera. */
@Composable
fun ConductFigure(pattern: ConductPattern, phaseNow: () -> Double, twoHands: Boolean, yaw: Float, onYaw: (Float) -> Unit, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val bg = cs.surface; val onS = cs.onSurface
    val yawNow = androidx.compose.runtime.rememberUpdatedState(yaw)
    val onYawNow = androidx.compose.runtime.rememberUpdatedState(onYaw)
    Canvas(
        modifier.clip(RoundedCornerShape(20.dp)).background(bg).aspectRatio(1.0f)
            .pointerInput(Unit) { detectDragGestures { _, d -> onYawNow.value(((yawNow.value + d.x * 0.5f) % 360f + 360f) % 360f) } },
    ) {
        val dens = density
        val painter = object : ConductRig.Painter {
            val ds: DrawScope = this@Canvas
            override fun capsule(x0: Float, y0: Float, r0: Float, x1: Float, y1: Float, r1: Float, argb: Int) {
                val c = Color(argb)
                // cápsula cônica: traço com o raio médio + círculos nas pontas com o raio de cada uma
                ds.drawCircle(c, r0, Offset(x0, y0)); ds.drawCircle(c, r1, Offset(x1, y1))
                ds.drawLine(c, Offset(x0, y0), Offset(x1, y1), strokeWidth = (r0 + r1), cap = StrokeCap.Butt)
            }
            override fun ellipse(cx: Float, cy: Float, rx: Float, ry: Float, argb: Int) {
                ds.drawOval(Color(argb), Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry))
            }
            override fun line(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, argb: Int) {
                ds.drawLine(Color(argb), Offset(x0, y0), Offset(x1, y1), strokeWidth = w.coerceAtLeast(1f), cap = StrokeCap.Round)
            }
            override fun shadow(cx: Float, cy: Float, rx: Float, ry: Float) {
                ds.drawOval(cs.outline.copy(alpha = 0.22f), Offset(cx - rx, cy - ry), Size(2 * rx, 2 * ry))
            }
        }
        val tSec = System.nanoTime() / 1e9
        val prims = ConductRig.pose(pattern, phaseNow(), twoHands, tSec)
        ConductRig.draw(prims, yaw, size.width, size.height, painter)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = onS.copy(alpha = 0.7f).toArgb(); textSize = 13f * dens; textAlign = android.graphics.Paint.Align.LEFT }
        drawContext.canvas.nativeCanvas.drawText(ConductRig.viewName(yaw), 12f * dens, 20f * dens, paint)
    }
}
