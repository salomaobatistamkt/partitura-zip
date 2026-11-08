package app.partitura

import android.graphics.Paint
import android.graphics.Path as APath
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.partitura.core.*

/** Desenha uma [Scene] (lista de símbolos em espaços de pauta) num Canvas do Android. Usado na tela, no PNG e no PDF. */
object ScenePainter {
    private val strCache = HashMap<Int, String>()
    private fun str(code: Int) = strCache.getOrPut(code) { String(Character.toChars(code)) }

    fun draw(c: android.graphics.Canvas, sc: Scene, sp: Float, ink: Int, hi: Set<Int> = emptySet(), hiColor: Int = ink,
             sel: Int = -1, selColor: Int = ink, y0: Float = -1e9f, y1: Float = 1e9f) {
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.bravura; textSize = 4f * sp }
        val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT }
        val fp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        val tp = Paint(Paint.ANTI_ALIAS_FLAG)
        val path = APath()
        val margin = 7f * sp
        for (it in sc.items) {
            val color = if (it.note >= 0) (if (it.note == sel) selColor else if (it.note in hi) hiColor else ink) else ink
            when (it) {
                is Glyph -> {
                    val y = it.y * sp
                    if (y < y0 - margin || y > y1 + margin) continue
                    gp.color = color
                    c.drawText(str(it.code), it.x * sp, y, gp)
                }
                is Line -> {
                    if (maxOf(it.y1, it.y2) * sp < y0 - margin || minOf(it.y1, it.y2) * sp > y1 + margin) continue
                    lp.color = color; lp.strokeWidth = it.w * sp
                    c.drawLine(it.x1 * sp, it.y1 * sp, it.x2 * sp, it.y2 * sp, lp)
                }
                is Beam -> {
                    if (it.y1 * sp < y0 - margin || it.y1 * sp > y1 + margin) continue
                    fp.color = color
                    path.reset()
                    path.moveTo(it.x1 * sp, it.y1 * sp); path.lineTo(it.x2 * sp, it.y2 * sp)
                    path.lineTo(it.x2 * sp, (it.y2 + it.h) * sp); path.lineTo(it.x1 * sp, (it.y1 + it.h) * sp); path.close()
                    c.drawPath(path, fp)
                }
                is Curve -> {
                    if (it.y1 * sp < y0 - margin || it.y1 * sp > y1 + margin) continue
                    lp.color = color; lp.strokeWidth = 0.22f * sp
                    path.reset()
                    path.moveTo(it.x1 * sp, it.y1 * sp)
                    path.quadTo((it.x1 + it.x2) / 2f * sp, ((it.y1 + it.y2) / 2f + (if (it.up) -1.1f else 1.1f)) * sp, it.x2 * sp, it.y2 * sp)
                    c.drawPath(path, lp)
                }
                is Brace -> {
                    if (it.y2 * sp < y0 - margin || it.y1 * sp > y1 + margin) continue
                    lp.color = color; lp.strokeWidth = 0.32f * sp
                    val mid = (it.y1 + it.y2) / 2f
                    path.reset()
                    path.moveTo((it.x + 0.8f) * sp, it.y1 * sp)
                    path.cubicTo((it.x - 0.2f) * sp, (it.y1 + 3f) * sp, (it.x + 0.8f) * sp, (mid - 2f) * sp, (it.x - 0.4f) * sp, mid * sp)
                    path.cubicTo((it.x + 0.8f) * sp, (mid + 2f) * sp, (it.x - 0.2f) * sp, (it.y2 - 3f) * sp, (it.x + 0.8f) * sp, it.y2 * sp)
                    c.drawPath(path, lp)
                }
                is Txt -> {
                    if (it.y * sp < y0 - margin || it.y * sp > y1 + margin) continue
                    tp.color = ink; tp.textSize = it.size * sp; tp.isFakeBoldText = it.bold
                    c.drawText(it.s, it.x * sp, it.y * sp, tp)
                }
            }
        }
    }
}

/**
 * Pauta numa folha clara. O cursor e o realce das notas tocando são desenhados por cima;
 * [cursor] é lido só no desenho, então o movimento não recompõe a tela.
 */
@Composable
fun ScoreView(
    scene: Scene, spPx: Float, active: Set<Int>, selected: Int, cursor: () -> Pair<Int, Float>?,
    scroll: ScrollState, viewportPx: Int, onTap: ((Float, Float) -> Unit)?, modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val heightDp = with(density) { (scene.height * spPx).toInt().toDp() }
    val inkArgb = android.graphics.Color.rgb(0x1E, 0x1E, 0x24)
    val amber = android.graphics.Color.rgb(0xE0, 0x7A, 0x00)
    val blue = android.graphics.Color.rgb(0x2F, 0x6F, 0xDE)
    Box(modifier.fillMaxWidth().height(heightDp).background(Paper)) {
        Canvas(
            Modifier.matchParentSize().pointerInput(scene, spPx, onTap) {
                detectTapGestures { off -> onTap?.invoke(off.x / spPx, off.y / spPx) }
            },
        ) {
            val top = scroll.value.toFloat()
            drawIntoCanvas { ScenePainter.draw(it.nativeCanvas, scene, spPx, inkArgb, active, amber, selected, blue, top, top + viewportPx) }
        }
        Canvas(Modifier.matchParentSize()) {
            val cur = cursor() ?: return@Canvas
            val sys = scene.systems.getOrNull(cur.first) ?: return@Canvas
            drawLine(Color(0xAAE07A00), Offset(cur.second * spPx, (sys.top - 0.5f) * spPx), Offset(cur.second * spPx, (sys.bottom + 0.5f) * spPx), strokeWidth = 0.28f * spPx)
        }
    }
}

/** Pauta pequena, só de leitura (prévia, teoria). Se [q] devolver o tempo atual (em semínimas), mostra cursor e notas tocando. */
@Composable
fun StaticScore(score: Score, rev: Int, maxHeight: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, spDp: Float = 6.5f, q: (() -> Double?)? = null) {
    val density = LocalDensity.current.density
    val scroll = androidx.compose.foundation.rememberScrollState()
    var vp by remember { mutableIntStateOf(0) }
    BoxWithConstraints(
        modifier.fillMaxWidth().heightIn(max = maxHeight).clip(androidx.compose.material3.MaterialTheme.shapes.medium)
            .onSizeChanged { vp = it.height },
    ) {
        val spPx = spDp * density
        val scene = remember(score, rev, constraints.maxWidth, spPx) { Engrave.scene(score, constraints.maxWidth / spPx) }
        val active by remember(scene) { derivedStateOf { val t = q?.invoke(); if (t == null) emptyList<Int>() else scene.soundingAt(t) } }
        val activeSet = remember(active) { active.toSet() }
        Column(Modifier.verticalScroll(scroll)) {
            ScoreView(scene, spPx, activeSet, -1, { val t = q?.invoke(); if (t == null) null else scene.cursor(t) }, scroll, vp, null)
        }
    }
}
