package app.partitura

import android.content.res.AssetManager
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Fontes carregadas uma vez (Bravura = fonte de notação musical SMuFL, licença OFL). */
object Fonts {
    var bravura: Typeface = Typeface.DEFAULT
    fun init(assets: AssetManager) { try { bravura = Typeface.createFromAsset(assets, "bravura.otf") } catch (_: Exception) {} }
}

enum class Ic { HOME, STUDIO, SWAP, THEORY, FOLDER, PLAY, PAUSE, STOP, LOOP, MIXER, EDIT, UNDO, REDO, PLUS, MINUS, GEAR, PIANO, ZOOM_IN, ZOOM_OUT, START, TRASH, SAVE, OPEN, CAMERA, BACK, NEXT, CONDUCT, SERVER, INSTR, MORE, COPY, CHECK }

@Composable
fun Icon24(kind: Ic, tint: Color, dp: Dp = 24.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(dp)) {
        val u = size.minDimension / 24f
        val st = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, Offset(x1 * u, y1 * u), Offset(x2 * u, y2 * u), strokeWidth = 2f * u, cap = StrokeCap.Round)
        fun path(block: Path.() -> Unit, fill: Boolean = false) = drawPath(Path().apply(block), tint, style = if (fill) Fill else st)
        when (kind) {
            Ic.HOME -> path({ moveTo(3f * u, 11f * u); lineTo(12f * u, 3.5f * u); lineTo(21f * u, 11f * u); moveTo(5.5f * u, 9.5f * u); lineTo(5.5f * u, 20f * u); lineTo(18.5f * u, 20f * u); lineTo(18.5f * u, 9.5f * u) })
            Ic.STUDIO -> { drawCircle(tint, 3.2f * u, Offset(8f * u, 18f * u)); line(11f, 18f, 11f, 4.5f); line(11f, 4.5f, 18f, 8f) }
            Ic.SWAP -> { line(7f, 20f, 7f, 5f); line(7f, 5f, 3.5f, 8.5f); line(7f, 5f, 10.5f, 8.5f); line(17f, 4f, 17f, 19f); line(17f, 19f, 13.5f, 15.5f); line(17f, 19f, 20.5f, 15.5f) }
            Ic.THEORY -> { drawCircle(tint, 8.5f * u, Offset(12f * u, 12f * u), style = st); drawCircle(tint, 1.6f * u, Offset(12f * u, 3.5f * u)); drawCircle(tint, 1.6f * u, Offset(20.5f * u, 12f * u)); drawCircle(tint, 1.6f * u, Offset(12f * u, 20.5f * u)); drawCircle(tint, 1.6f * u, Offset(3.5f * u, 12f * u)) }
            Ic.FOLDER -> path({ moveTo(3f * u, 7f * u); lineTo(9f * u, 7f * u); lineTo(11f * u, 9f * u); lineTo(21f * u, 9f * u); lineTo(21f * u, 19f * u); lineTo(3f * u, 19f * u); close() })
            Ic.PLAY -> path({ moveTo(7f * u, 4.5f * u); lineTo(19f * u, 12f * u); lineTo(7f * u, 19.5f * u); close() }, true)
            Ic.PAUSE -> { drawRect(tint, Offset(6f * u, 5f * u), Size(4f * u, 14f * u)); drawRect(tint, Offset(14f * u, 5f * u), Size(4f * u, 14f * u)) }
            Ic.STOP -> drawRect(tint, Offset(6f * u, 6f * u), Size(12f * u, 12f * u))
            Ic.START -> { drawRect(tint, Offset(5f * u, 5f * u), Size(2.6f * u, 14f * u)); path({ moveTo(19f * u, 5f * u); lineTo(19f * u, 19f * u); lineTo(9f * u, 12f * u); close() }, true) }
            Ic.LOOP -> { drawArc(tint, 40f, 270f, false, Offset(4f * u, 4f * u), Size(16f * u, 16f * u), style = st); path({ moveTo(15f * u, 2.5f * u); lineTo(19.5f * u, 6.5f * u); lineTo(14f * u, 8f * u); close() }, true) }
            Ic.MIXER -> { line(6f, 4f, 6f, 20f); line(12f, 4f, 12f, 20f); line(18f, 4f, 18f, 20f); drawCircle(tint, 2.4f * u, Offset(6f * u, 14f * u)); drawCircle(tint, 2.4f * u, Offset(12f * u, 8f * u)); drawCircle(tint, 2.4f * u, Offset(18f * u, 15f * u)) }
            Ic.EDIT -> { line(5f, 19f, 17f, 7f); line(15f, 5f, 19f, 9f); line(4.5f, 19.5f, 8f, 18.5f) }
            Ic.UNDO -> { drawArc(tint, 180f, 190f, false, Offset(6f * u, 8f * u), Size(14f * u, 10f * u), style = st); path({ moveTo(9f * u, 3.5f * u); lineTo(5f * u, 9f * u); lineTo(11.5f * u, 10f * u); close() }, true) }
            Ic.REDO -> { drawArc(tint, 170f, -190f, false, Offset(4f * u, 8f * u), Size(14f * u, 10f * u), style = st); path({ moveTo(15f * u, 3.5f * u); lineTo(19f * u, 9f * u); lineTo(12.5f * u, 10f * u); close() }, true) }
            Ic.PLUS -> { line(12f, 5f, 12f, 19f); line(5f, 12f, 19f, 12f) }
            Ic.MINUS -> line(5f, 12f, 19f, 12f)
            Ic.GEAR -> { drawCircle(tint, 3.2f * u, Offset(12f * u, 12f * u), style = st); for (k in 0 until 8) { val a = Math.toRadians(k * 45.0); val c = Math.cos(a).toFloat(); val s = Math.sin(a).toFloat(); line(12f + 6.8f * c, 12f + 6.8f * s, 12f + 9.5f * c, 12f + 9.5f * s) } }
            Ic.PIANO -> { drawRect(tint, Offset(3f * u, 5f * u), Size(18f * u, 14f * u), style = st); line(9f, 11f, 9f, 19f); line(15f, 11f, 15f, 19f); drawRect(tint, Offset(7f * u, 5f * u), Size(4f * u, 7f * u)); drawRect(tint, Offset(13f * u, 5f * u), Size(4f * u, 7f * u)) }
            Ic.ZOOM_IN -> { drawCircle(tint, 6f * u, Offset(10.5f * u, 10.5f * u), style = st); line(15f, 15f, 20f, 20f); line(8f, 10.5f, 13f, 10.5f); line(10.5f, 8f, 10.5f, 13f) }
            Ic.ZOOM_OUT -> { drawCircle(tint, 6f * u, Offset(10.5f * u, 10.5f * u), style = st); line(15f, 15f, 20f, 20f); line(8f, 10.5f, 13f, 10.5f) }
            Ic.TRASH -> { line(5f, 7f, 19f, 7f); path({ moveTo(7f * u, 7f * u); lineTo(8f * u, 20f * u); lineTo(16f * u, 20f * u); lineTo(17f * u, 7f * u) }); line(10f, 4f, 14f, 4f) }
            Ic.SAVE -> path({ moveTo(5f * u, 4f * u); lineTo(16f * u, 4f * u); lineTo(20f * u, 8f * u); lineTo(20f * u, 20f * u); lineTo(5f * u, 20f * u); close(); moveTo(8f * u, 4f * u); lineTo(8f * u, 9f * u); lineTo(15f * u, 9f * u); lineTo(15f * u, 4f * u) })
            Ic.OPEN -> { path({ moveTo(12f * u, 4f * u); lineTo(12f * u, 15f * u); moveTo(7.5f * u, 10.5f * u); lineTo(12f * u, 15f * u); lineTo(16.5f * u, 10.5f * u) }); line(5f, 20f, 19f, 20f) }
            Ic.CAMERA -> { path({ moveTo(3f * u, 8f * u); lineTo(7.5f * u, 8f * u); lineTo(9f * u, 5.5f * u); lineTo(15f * u, 5.5f * u); lineTo(16.5f * u, 8f * u); lineTo(21f * u, 8f * u); lineTo(21f * u, 19f * u); lineTo(3f * u, 19f * u); close() }); drawCircle(tint, 3.6f * u, Offset(12f * u, 13.2f * u), style = st) }
            Ic.BACK -> { line(15f, 5f, 8f, 12f); line(8f, 12f, 15f, 19f) }
            Ic.CONDUCT -> { path({ moveTo(12f * u, 3.5f * u); lineTo(12f * u, 17f * u); cubicTo(11f * u, 20.5f * u, 11f * u, 20.5f * u, 7.5f * u, 17f * u); moveTo(12f * u, 17f * u); cubicTo(18f * u, 18f * u, 18f * u, 18f * u, 19f * u, 10f * u) }); drawCircle(tint, 1.7f * u, Offset(19f * u, 9f * u)) }
            Ic.SERVER -> { drawRect(tint, Offset(4f * u, 4f * u), Size(16f * u, 6.5f * u), style = st); drawRect(tint, Offset(4f * u, 13.5f * u), Size(16f * u, 6.5f * u), style = st); drawCircle(tint, 1.1f * u, Offset(8f * u, 7.2f * u)); drawCircle(tint, 1.1f * u, Offset(8f * u, 16.8f * u)) }
            Ic.INSTR -> { line(3f, 6f, 21f, 6f); line(3f, 12f, 21f, 12f); line(3f, 18f, 21f, 18f); drawCircle(tint, 2.6f * u, Offset(15f * u, 9f * u)); line(17.4f, 9f, 17.4f, 2.5f) }
            Ic.MORE -> { drawCircle(tint, 1.9f * u, Offset(5f * u, 12f * u)); drawCircle(tint, 1.9f * u, Offset(12f * u, 12f * u)); drawCircle(tint, 1.9f * u, Offset(19f * u, 12f * u)) }
            Ic.COPY -> { drawRect(tint, Offset(8f * u, 8f * u), Size(11f * u, 12f * u), style = st); path({ moveTo(5f * u, 15f * u); lineTo(5f * u, 4f * u); lineTo(14f * u, 4f * u) }) }
            Ic.CHECK -> path({ moveTo(5f * u, 12.5f * u); lineTo(10f * u, 17.5f * u); lineTo(19f * u, 7f * u) })
            Ic.NEXT -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
        }
    }
}

/** Botão de ferramenta: ícone (ou conteúdo) com fundo suave; destaca quando [selected]. */
@Composable
fun ToolBtn(ic: Ic, onClick: () -> Unit, modifier: Modifier = Modifier, selected: Boolean = false, enabled: Boolean = true, primary: Boolean = false, desc: String = "") {
    val cs = MaterialTheme.colorScheme
    val bg = when { primary -> cs.primary; selected -> cs.primaryContainer; else -> Color.Transparent }
    val fg = when { primary -> cs.onPrimary; selected -> cs.onPrimaryContainer; else -> cs.onSurface }
    Box(
        modifier.size(42.dp).clip(MaterialTheme.shapes.medium).background(bg).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon24(ic, if (enabled) fg else fg.copy(alpha = 0.3f), 22.dp) }
}

/** Texto desenhado com a fonte Bravura (símbolos musicais). */
@Composable
fun GlyphText(code: Int, sizeDp: Dp, color: Color, modifier: Modifier = Modifier, base: Float = 0.72f) {
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    Canvas(modifier.size(sizeDp * 0.75f, sizeDp)) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.typeface = Fonts.bravura; p.textSize = sizeDp.value * density; p.color = color.toArgb()
        drawIntoCanvas { it.nativeCanvas.drawText(String(Character.toChars(code)), 0f, size.height * base, p) }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable
fun Panel(modifier: Modifier = Modifier, pad: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            .padding(pad),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Wrap(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
fun Empty(title: String, body: String, action: String, onGo: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onGo) { Text(action) }
    }
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        ToolBtn(Ic.MINUS, onMinus)
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 56.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        ToolBtn(Ic.PLUS, onPlus)
    }
}

fun Modifier.verticalScrollCompat(): Modifier = composed { this.verticalScroll(rememberScrollState()) }
