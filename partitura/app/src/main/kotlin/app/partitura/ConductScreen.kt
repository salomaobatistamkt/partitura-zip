package app.partitura

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.partitura.core.*
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Regência: desenho animado do gesto da mão direita (visto de quem rege) para a fórmula de compasso da música.
 * A batuta pode andar sozinha no andamento escolhido, com metrônomo, ou seguir a música que está tocando.
 */
@Composable
fun ConductScreen(state: AppState) {
    val s = state.score
    val p = state.player
    var beats by remember(s) { mutableIntStateOf(s?.beats ?: 4) }
    var beatType by remember(s) { mutableIntStateOf(s?.beatType ?: 4) }
    val pattern = remember(beats, beatType) { Conducting.forMeter(beats, beatType) }
    var anim by remember { mutableStateOf(false) }
    var freePhase by remember { mutableDoubleStateOf(0.0) }

    // anacruse: se o primeiro compasso é incompleto, os compassos seguintes ainda começam no tempo 1
    val offsetQ = remember(s, state.rev, pattern) {
        if (s == null) 0.0 else {
            val t = Synth.measureTimes(s, 60)
            val first = if (t.size > 1) t[1] else 0.0
            val full = 4.0 * s.beats / s.beatType
            if (first > 1e-6 && first < full - 1e-6) full - first else 0.0
        }
    }
    val measQ = 4.0 * (s?.beats ?: beats) / (s?.beatType ?: beatType)

    val phaseNow: () -> Double = {
        when {
            p.active -> (p.posSec * p.usedTempo / 60.0 + (if (p.kind == "music") offsetQ else 0.0)) / pattern.beatQuarters
            anim -> freePhase
            else -> -1.0
        }
    }
    val curBeat by remember(pattern) { derivedStateOf { val ph = phaseNow(); if (ph < 0) -1 else Math.floorMod(floor(ph).toInt(), pattern.beats) } }
    val curMeasure by remember(pattern) { derivedStateOf { if (p.active && p.kind == "music") floor((p.posSec * p.usedTempo / 60.0 + offsetQ) / measQ).toInt() + 1 else -1 } }

    LaunchedEffect(anim, pattern) {
        if (anim) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val dt = (now - last) / 1e9; last = now
                freePhase += dt * (state.tempo / 60.0) / pattern.beatQuarters
            }
        }
    }
    // metrônomo acompanha mudança de andamento ou de compasso
    LaunchedEffect(state.tempo, pattern) { if (p.active && p.kind == "click") p.startClick(pattern.beats, 60.0 / state.tempo * pattern.beatQuarters, state.tempo) }

    val clickOn = p.active && p.kind == "click"
    val musicOn = p.active && p.kind == "music"
    val totalBars = s?.parts?.firstOrNull()?.measures?.size ?: 0

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 760.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text("Regência", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "${pattern.title} · ${state.tempo} bpm" + (if (pattern.sub > 1) " · ${"%.0f".format(state.tempo / pattern.beatQuarters)} gestos por minuto" else ""),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val stage: @Composable (Modifier) -> Unit = { m -> Stage(pattern, phaseNow, curBeat, m) }
            val side: @Composable () -> Unit = {
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (curBeat >= 0) "${curBeat + 1}" else "–", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                        Column {
                            Text(if (curMeasure >= 1) "Compasso $curMeasure" + (if (totalBars > 0) " de $totalBars" else "") else if (curBeat >= 0) "Gesto atual" else "Parado", style = MaterialTheme.typography.titleSmall)
                            Text(if (curBeat >= 0) pattern.steps[curBeat].substringAfter("· ") else "Toque em um dos botões abaixo para ver o gesto andando.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Panel {
                    Text("Como fazer", style = MaterialTheme.typography.titleSmall)
                    pattern.steps.forEachIndexed { i, t ->
                        val on = i == curBeat
                        Text(
                            t, style = MaterialTheme.typography.bodyMedium,
                            color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent).padding(horizontal = 8.dp, vertical = 5.dp),
                        )
                    }
                    pattern.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    stage(Modifier.weight(1.15f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) { side() }
                }
            } else {
                stage(Modifier.fillMaxWidth())
                side()
            }

            Panel {
                Wrap {
                    Button(onClick = {
                        if (anim) anim = false else { p.stop(); freePhase = 0.0; anim = true }
                    }) { Text(if (anim) "Parar animação" else "Ver o gesto andando") }
                    FilledTonalButton(onClick = {
                        if (clickOn) p.stop() else { anim = false; p.startClick(pattern.beats, 60.0 / state.tempo * pattern.beatQuarters, state.tempo) }
                    }) { Text(if (clickOn) "Parar metrônomo" else "Com metrônomo") }
                    if (s != null) FilledTonalButton(onClick = {
                        if (musicOn) p.stop() else { anim = false; p.stop(); state.play(null) }
                    }) { Text(if (musicOn) "Parar a música" else "Reger a música") }
                }
                Stepper("Andamento (♩ por minuto)", "${state.tempo}", { state.tempo = (state.tempo - 5).coerceAtLeast(30) }, { state.tempo = (state.tempo + 5).coerceAtMost(220) })
                if (musicOn) Text("Mudar o andamento aqui vale na próxima vez que tocar a música.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                SectionTitle("Fórmula de compasso")
                Wrap {
                    Conducting.METERS.forEach { (b, bt) ->
                        FilterChip(selected = beats == b && beatType == bt, onClick = { beats = b; beatType = bt }, label = { Text("$b/$bt") })
                    }
                }
                if (s != null && (s.beats != beats || s.beatType != beatType)) Text("A partitura aberta está em ${s.beats}/${s.beatType}. Ao reger a música, o gesto muda sozinho só se você escolher essa fórmula.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (s != null) FilledTonalButton(onClick = { beats = s.beats; beatType = s.beatType }, enabled = s.beats != beats || s.beatType != beatType) { Text("Usar o compasso da partitura (${s.beats}/${s.beatType})") }
            }

            Panel {
                Text("Dicas", style = MaterialTheme.typography.titleSmall)
                listOf(
                    "A mão direita marca o tempo; a esquerda faz entradas e dinâmica.",
                    "O círculo numerado é o ictus: o ponto exato do tempo, onde a batuta chega e rebate.",
                    "Antes de começar, faça um gesto de preparação no andamento (o último gesto do compasso, subindo) e então o 1.",
                    "Gestos pequenos pedem som suave; gestos amplos, som forte.",
                    "O desenho é visto de quem rege: a esquerda do desenho é a sua esquerda.",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun Stage(pattern: ConductPattern, phaseNow: () -> Double, curBeat: Int, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val primary = cs.primary; val onPrimary = cs.onPrimary; val onSurface = cs.onSurface; val muted = cs.onSurfaceVariant; val surface = cs.surface; val outline = cs.outline
    val path = remember(pattern) { pattern.path(48) }
    Box(modifier.clip(MaterialTheme.shapes.large).background(surface).aspectRatio(1.05f)) {
        Canvas(Modifier.matchParentSize()) {
            val w = size.width; val h = size.height
            fun px(pt: Pt) = Offset(w * (0.09f + 0.82f * pt.x), h * (0.07f + 0.86f * pt.y))
            val dp = density
            // linha do chão (nível do ictus 1) bem suave
            drawLine(outline.copy(alpha = 0.5f), Offset(w * 0.05f, px(pattern.ictusPoint(0)).y), Offset(w * 0.95f, px(pattern.ictusPoint(0)).y), strokeWidth = 1f * dp)
            val per = 48
            // caminho: cada gesto numa faixa; o atual em destaque
            for (b in 0 until pattern.beats) {
                val on = b == curBeat
                val seg = Path()
                for (k in 0..per) {
                    val pt = pattern.position(b + minOf(k, per - 1).toDouble() / per + (if (k == per) 0.9999 / per else 0.0))
                    val o = px(pt)
                    if (k == 0) seg.moveTo(o.x, o.y) else seg.lineTo(o.x, o.y)
                }
                drawPath(seg, if (on) primary else muted.copy(alpha = 0.35f), style = Stroke(width = (if (on) 5f else 3f) * dp, cap = StrokeCap.Round, join = StrokeJoin.Round))
                // seta de direção no meio do gesto
                val a = px(pattern.position(b + 0.52)); val c = px(pattern.position(b + 0.60))
                val dx = c.x - a.x; val dy = c.y - a.y; val len = hypot(dx, dy).coerceAtLeast(0.001f)
                val ux = dx / len; val uy = dy / len
                val head = Path().apply {
                    moveTo(c.x + ux * 7f * dp, c.y + uy * 7f * dp)
                    lineTo(c.x - ux * 5f * dp - uy * 6f * dp, c.y - uy * 5f * dp + ux * 6f * dp)
                    lineTo(c.x - ux * 5f * dp + uy * 6f * dp, c.y - uy * 5f * dp - ux * 6f * dp)
                    close()
                }
                drawPath(head, if (on) primary else muted.copy(alpha = 0.5f), style = Fill)
            }
            val ph = phaseNow()
            // marcas dos ictus
            val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; textSize = 15f * dp; isFakeBoldText = true }
            for (b in 0 until pattern.beats) {
                val o = px(pattern.ictusPoint(b)); val on = b == curBeat
                drawCircle(if (on) primary else surface, 16f * dp, o)
                drawCircle(if (on) primary else muted, 16f * dp, o, style = Stroke(width = 2f * dp))
                txt.color = (if (on) onPrimary else onSurface).toArgb()
                drawIntoCanvas { it.nativeCanvas.drawText("${b + 1}", o.x, o.y + 5.2f * dp, txt) }
            }
            // pulso no ictus que acabou de passar
            if (ph >= 0) {
                val frac = ph - floor(ph)
                if (frac < 0.22) {
                    val o = px(pattern.ictusPoint(floor(ph).toInt()))
                    val k = (frac / 0.22).toFloat()
                    drawCircle(primary.copy(alpha = 1f - k), (16f + 34f * k) * dp, o, style = Stroke(width = 3f * dp))
                }
            }
            // batuta com rastro
            if (ph >= 0) {
                for (k in 10 downTo 1) {
                    val o = px(pattern.position(ph - k * 0.022))
                    drawCircle(primary.copy(alpha = 0.55f * (1f - k / 11f)), (9f - k * 0.55f).coerceAtLeast(2f) * dp, o)
                }
                val o = px(pattern.position(ph))
                drawCircle(primary.copy(alpha = 0.30f), 20f * dp, o)
                drawCircle(onSurface, 10f * dp, o)
                drawCircle(primary, 10f * dp, o, style = Stroke(width = 3f * dp))
            } else {
                val o = px(pattern.waypoints.last())
                drawCircle(onSurface.copy(alpha = 0.9f), 10f * dp, o)
                drawCircle(primary, 10f * dp, o, style = Stroke(width = 3f * dp))
                txt.color = muted.toArgb(); txt.textSize = 12f * dp; txt.isFakeBoldText = false
                drawIntoCanvas { it.nativeCanvas.drawText("posição de preparação", o.x, o.y - 18f * dp, txt) }
            }
        }
    }
}
