package app.partitura

import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.partitura.core.*
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

private val TONICS = listOf(
    "Dó" to Pitch('C', 0, 4), "Dó♯" to Pitch('C', 1, 4), "Ré♭" to Pitch('D', -1, 4), "Ré" to Pitch('D', 0, 4), "Mi♭" to Pitch('E', -1, 4),
    "Mi" to Pitch('E', 0, 4), "Fá" to Pitch('F', 0, 4), "Fá♯" to Pitch('F', 1, 4), "Sol♭" to Pitch('G', -1, 4), "Sol" to Pitch('G', 0, 4),
    "Lá♭" to Pitch('A', -1, 4), "Lá" to Pitch('A', 0, 4), "Si♭" to Pitch('B', -1, 4), "Si" to Pitch('B', 0, 4),
)

/** Posição i (0 = Dó, 1 = Sol, 2 = Ré...) -> armadura em quintas (−5..6). */
private fun fifthsAt(i: Int) = if (i <= 6) i else i - 12
private fun posOf(fifths: Int) = Math.floorMod(fifths, 12)

private fun accidentalsText(f: Int): String {
    val sharps = listOf("Fá♯", "Dó♯", "Sol♯", "Ré♯", "Lá♯", "Mi♯", "Si♯")
    val flats = listOf("Si♭", "Mi♭", "Lá♭", "Ré♭", "Sol♭", "Dó♭", "Fá♭")
    return when {
        f == 0 -> "sem sustenidos nem bemóis"
        f > 0 -> "$f sustenido${if (f > 1) "s" else ""}: " + sharps.take(f).joinToString(", ")
        else -> (if (-f > 1) "${-f} bemóis: " else "1 bemol: ") + flats.take(-f).joinToString(", ")
    }
}

/**
 * Círculo das quintas de verdade: anel de fora = tonalidades maiores, anel de dentro = relativas menores.
 * Ao escolher uma tonalidade, o círculo gira para ela ficar no topo e os graus (I, IV, V, ii, iii, vi, vii°) aparecem nos setores.
 */
@Composable
fun CircleOfFifths(sel: Int, minor: Boolean, onSelect: (Int, Boolean) -> Unit, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var target by remember { mutableFloatStateOf(sel * 30f) }
    RotateOnChange(sel) { newSel ->
        val cur = Math.floorMod(target.roundToInt(), 360).toFloat()
        var diff = (newSel * 30f - cur)
        while (diff > 180f) diff -= 360f
        while (diff < -180f) diff += 360f
        target += diff
    }
    val rot by animateFloatAsState(target, tween(450), label = "rot")
    val majorNames = (0 until 12).map { Names.key(fifthsAt(it)).let { n -> if (it == 6) "Fá♯" else n } }
    val minorNames = (0 until 12).map { Names.minorKey(fifthsAt(it)) }
    val surfaceVar = cs.surfaceVariant; val surface = cs.surface; val primary = cs.primary; val secondary = cs.secondary
    val onSurface = cs.onSurface; val outline = cs.outline; val primaryC = cs.primaryContainer; val secC = cs.secondaryContainer
    Canvas(
        modifier.pointerInput(rot) {
            detectTapGestures { off ->
                val cx = size.width / 2f; val cy = size.height / 2f
                val r = hypot(off.x - cx, off.y - cy)
                val R = minOf(cx, cy)
                if (r < R * 0.42f || r > R) return@detectTapGestures
                val a = Math.toDegrees(atan2((off.y - cy).toDouble(), (off.x - cx).toDouble())).toFloat()
                val i = Math.floorMod(((a + 90f + rot) / 30f).roundToInt(), 12)
                onSelect(i, r < R * 0.70f)
            }
        },
    ) {
        val cx = size.width / 2f; val cy = size.height / 2f
        val R = minOf(cx, cy) - 2f
        val rMid = R * 0.70f; val rIn = R * 0.42f
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = onSurface.toArgb(); isFakeBoldText = true }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = onSurface.toArgb() }
        fun sector(i: Int, r0: Float, r1: Float, color: Color, edge: Boolean) {
            val start = -90f + 30f * i - rot - 15f
            val path = Path()
            path.arcTo(Rect(Offset(cx - r1, cy - r1), Size(2 * r1, 2 * r1)), start, 30f, true)
            path.arcTo(Rect(Offset(cx - r0, cy - r0), Size(2 * r0, 2 * r0)), start + 30f, -30f, false)
            path.close()
            drawPath(path, color)
            drawPath(path, outline, style = Stroke(width = 1.5f))
            if (edge) drawPath(path, primary, style = Stroke(width = 4f))
        }
        for (i in 0 until 12) {
            val d = Math.floorMod(i - sel, 12)
            val majorRole = when (d) { 0 -> "I"; 1 -> "V"; 11 -> "IV"; else -> "" }
            val minorRole = when (d) { 0 -> "vi"; 1 -> "iii"; 11 -> "ii"; 2 -> "vii°"; else -> "" }
            val majorColor = when (d) { 0 -> primaryC.copy(alpha = 1f); 1, 11 -> primaryC.copy(alpha = 0.55f); else -> surfaceVar }
            val minorColor = when (d) { 0, 1, 11 -> secC; 2 -> secC.copy(alpha = 0.45f); else -> surface }
            sector(i, rMid, R, majorColor, !minor && d == 0)
            sector(i, rIn, rMid, minorColor, minor && d == 0)
            val ang = Math.toRadians((-90f + 30f * i - rot).toDouble())
            val mx = cx + (R + rMid) / 2f * cos(ang).toFloat(); val my = cy + (R + rMid) / 2f * sin(ang).toFloat()
            val nx = cx + (rMid + rIn) / 2f * cos(ang).toFloat(); val ny = cy + (rMid + rIn) / 2f * sin(ang).toFloat()
            label.textSize = R * 0.105f; small.textSize = R * 0.062f
            drawIntoCanvas {
                val c = it.nativeCanvas
                c.drawText(majorNames[i], mx, my + (if (majorRole.isEmpty()) label.textSize * 0.35f else -small.textSize * 0.1f), label)
                if (majorRole.isNotEmpty()) c.drawText(majorRole, mx, my + label.textSize * 0.75f, small)
                label.textSize = R * 0.082f
                c.drawText(minorNames[i] + "m", nx, ny + (if (minorRole.isEmpty()) label.textSize * 0.35f else -small.textSize * 0.1f), label)
                if (minorRole.isNotEmpty()) c.drawText(minorRole, nx, ny + label.textSize * 0.85f, small)
            }
        }
        drawCircle(surface, rIn - 2f, Offset(cx, cy))
        drawCircle(outline, rIn - 2f, Offset(cx, cy), style = Stroke(1.5f))
    }
}

@Composable
private fun RotateOnChange(sel: Int, onChange: (Int) -> Unit) {
    var last by remember { mutableIntStateOf(sel) }
    LaunchedEffect(sel) { if (sel != last) { onChange(sel); last = sel } }
}

@Composable
fun TheoryScreen(state: AppState, wide: Boolean) {
    var tab by remember { mutableIntStateOf(0) }
    var circleSel by remember { mutableIntStateOf(0) }
    var circleMinor by remember { mutableStateOf(false) }
    var tonicIdx by remember { mutableIntStateOf(0) }
    var scaleType by remember { mutableStateOf(ScaleType.MAIOR) }
    var quality by remember { mutableStateOf(ChordQuality.MAIOR) }
    var fieldMajor by remember { mutableStateOf(true) }
    var sevenths by remember { mutableStateOf(false) }
    var playing by remember { mutableStateOf<Score?>(null) }
    val p = state.player
    val octs = if (wide) 4 else 2
    var kbFirst by remember { mutableIntStateOf(if (wide) 48 else 60) }
    val kbLast = kbFirst + 12 * octs

    fun playScore(sc: Score, tempo: Int) { playing = sc; p.start(sc, tempo, listOf(1.0), 0.0, Double.MAX_VALUE, false) }
    fun playChord(notes: List<Pitch>) = playScore(Theory.toScore("Acorde", notes, 4.0, 0, chord = true), 80)

    val ev = remember(playing, p.usedTempo) { playing?.let { Synth.events(it, p.usedTempo) } ?: emptyList() }
    val activeMidi by remember(ev) {
        derivedStateOf { if (!p.active) emptySet<Int>() else { val t = p.posSec; ev.filter { it.startSec <= t + 1e-6 && t < it.startSec + it.durSec }.map { it.midi }.toSet() } }
    }
    val qNow: () -> Double? = { if (p.active) p.posSec * p.usedTempo / 60.0 else null }
    var marks by remember { mutableStateOf(emptySet<Int>()) }   // classes de altura (0..11) realçadas no piano

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Teoria", style = MaterialTheme.typography.headlineMedium)
            Wrap { listOf("Círculo das quintas", "Escalas", "Acordes", "Campo harmônico").forEachIndexed { i, n -> FilterChip(selected = tab == i, onClick = { tab = i; marks = emptySet() }, label = { Text(n) }) } }
            if (tab != 0) {
                Wrap { TONICS.forEachIndexed { i, (n, _) -> FilterChip(selected = tonicIdx == i, onClick = { tonicIdx = i }, label = { Text(n) }) } }
            }
            val tonic = TONICS[tonicIdx].second
            when (tab) {
                0 -> {
                    val f = fifthsAt(circleSel)
                    // tonalidade escolhida: maior (anel de fora) ou relativa menor (anel de dentro)
                    val tonicPitch = if (!circleMinor) Spell.fromFifths(f, 60 + Math.floorMod(7 * f, 12)) else Spell.fromFifths(f + 3, 60 + Math.floorMod(7 * (f + 3), 12))
                    val type = if (circleMinor) ScaleType.MENOR_NATURAL else ScaleType.MAIOR
                    val scale = remember(circleSel, circleMinor) { Theory.scale(tonicPitch, type) }
                    val sc = remember(circleSel, circleMinor) { Theory.toScore("Escala", scale, 1.0, f) }
                    val field = remember(circleSel, circleMinor) { Theory.harmonicField(tonicPitch.copy(octave = 3), !circleMinor, false) }
                    val keyName = if (circleMinor) "${Names.minorKey(f)} menor" else "${Names.key(f)} maior"
                    LaunchedEffect(circleSel, circleMinor) { marks = scale.map { Math.floorMod(it.midi, 12) }.toSet() }
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                            CircleOfFifths(circleSel, circleMinor, { i, m -> circleSel = i; circleMinor = m }, Modifier.fillMaxSize())
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(keyName, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                                Text(if (f == 0) "sem acidentes" else if (f > 0) "$f ♯" else "${-f} ♭", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                            }
                        }
                    }
                    Panel {
                        Text(keyName, style = MaterialTheme.typography.titleMedium)
                        Text("Armadura: " + accidentalsText(f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            if (circleMinor) "Relativa maior: ${Names.key(f)} · vizinhas: ${Names.minorKey(f - 1)} menor (iv) e ${Names.minorKey(f + 1)} menor (v)"
                            else "Relativa menor: ${Names.minorKey(f)} · vizinhas: ${Names.key(f - 1)} (IV) e ${Names.key(f + 1)} (V)",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text("Escala: " + scale.joinToString(" – ") { Names.step(it.step, it.alter) }, style = MaterialTheme.typography.bodyLarge)
                        StaticScore(sc, 0, 190.dp, q = if (playing === sc) qNow else null)
                        Wrap {
                            Button(onClick = { playScore(sc, 100) }) { Text("Ouvir escala") }
                            OutlinedButton(onClick = { val s2 = Theory.toScore("Escala de $keyName", scale, 1.0, f); s2.tempo = 100; state.open(s2); state.goStudio() }) { Text("Abrir no Estúdio") }
                        }
                        Text("Acordes da tonalidade (toque para ouvir)", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Wrap { field.forEach { ch -> AssistChip(onClick = { playChord(ch.notes) }, label = { Text("${ch.degree}  ${ch.name}") }) } }
                    }
                }
                1 -> {
                    Wrap { ScaleType.values().forEach { t -> FilterChip(selected = scaleType == t, onClick = { scaleType = t }, label = { Text(t.label) }) } }
                    val notes = Theory.scale(tonic, scaleType)
                    val sc = remember(tonicIdx, scaleType) { Theory.toScore("${Names.step(tonic.step, tonic.alter)} ${scaleType.label}", notes, 1.0, 0) }
                    LaunchedEffect(tonicIdx, scaleType) { marks = notes.map { Math.floorMod(it.midi, 12) }.toSet() }
                    Panel {
                        Text(Names.step(tonic.step, tonic.alter) + " " + scaleType.label.lowercase(), style = MaterialTheme.typography.titleMedium)
                        Text(notes.joinToString(" – ") { Names.step(it.step, it.alter) })
                        StaticScore(sc, 0, 190.dp, q = if (playing === sc) qNow else null)
                        Wrap {
                            Button(onClick = { playScore(sc, 100) }) { Text("Ouvir escala") }
                            OutlinedButton(onClick = { val s2 = Theory.toScore(sc.title, notes, 1.0, 0); s2.tempo = 100; state.open(s2); state.goStudio() }) { Text("Abrir no Estúdio") }
                        }
                    }
                }
                2 -> {
                    Wrap { ChordQuality.values().forEach { q -> FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q.label) }) } }
                    val notes = Theory.chord(tonic.copy(octave = 3), quality)
                    val block = remember(tonicIdx, quality) { Theory.toScore("Acorde", notes, 4.0, 0, chord = true) }
                    val arp = remember(tonicIdx, quality) { Theory.toScore("Arpejo", notes, 1.0, 0) }
                    LaunchedEffect(tonicIdx, quality) { marks = notes.map { Math.floorMod(it.midi, 12) }.toSet() }
                    Panel {
                        Text(Names.step(tonic.step, tonic.alter) + " " + quality.label.lowercase(), style = MaterialTheme.typography.titleMedium)
                        Text(notes.joinToString(" – ") { Names.step(it.step, it.alter) })
                        StaticScore(block, 0, 190.dp, q = if (playing === block) qNow else null)
                        Wrap {
                            Button(onClick = { playScore(block, 80) }) { Text("Ouvir acorde") }
                            OutlinedButton(onClick = { playScore(arp, 100) }) { Text("Ouvir arpejo") }
                            OutlinedButton(onClick = { state.open(block); state.goStudio() }) { Text("Abrir no Estúdio") }
                        }
                    }
                }
                else -> {
                    Wrap {
                        FilterChip(selected = fieldMajor, onClick = { fieldMajor = true }, label = { Text("Maior") })
                        FilterChip(selected = !fieldMajor, onClick = { fieldMajor = false }, label = { Text("Menor natural") })
                        FilterChip(selected = sevenths, onClick = { sevenths = !sevenths }, label = { Text("Com sétima") })
                    }
                    val field = Theory.harmonicField(tonic.copy(octave = 3), fieldMajor, sevenths)
                    field.forEach { ch ->
                        Panel(pad = 12.dp) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text("${ch.degree}  ·  ${ch.name}", style = MaterialTheme.typography.titleMedium)
                                    Text(ch.notes.joinToString(" – ") { Names.step(it.step, it.alter) } + "  (${ch.quality.label.lowercase()})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                ToolBtn(Ic.PLAY, { marks = ch.notes.map { Math.floorMod(it.midi, 12) }.toSet(); playChord(ch.notes) }, primary = true)
                            }
                        }
                    }
                    Button(onClick = {
                        val sc = Score("Campo harmônico de ${Names.step(tonic.step, tonic.alter)} ${if (fieldMajor) "maior" else "menor"}", 4, 0, 4, 4, 70)
                        val part = Part("P1", "Piano", mutableListOf())
                        field.forEachIndexed { i, ch -> val m = Measure(i + 1); ch.notes.forEachIndexed { k, pt -> m.notes += Note(pt, 16, chord = k > 0) }; part.measures += m }
                        sc.parts += part; state.open(sc); state.goStudio()
                    }) { Text("Abrir sequência no Estúdio") }
                }
            }
        }
        // piano fixo embaixo: mostra a escala/acorde e acompanha o que está tocando
        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolBtn(Ic.BACK, { kbFirst = (kbFirst - 12).coerceAtLeast(24) })
                Text("Dó${kbFirst / 12 - 1} – Dó${kbLast / 12 - 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ToolBtn(Ic.NEXT, { kbFirst = (kbFirst + 12).coerceAtMost(96 - 12 * octs) })
                Spacer(Modifier.weight(1f))
                Text("Teclas claras = notas do exemplo", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val markMidi = remember(marks, kbFirst) { (kbFirst..kbLast).filter { Math.floorMod(it, 12) in marks }.toSet() }
            PianoKeyboard(kbFirst, kbLast, activeMidi, markMidi, { m -> p.key(m) }, height = if (wide) 110.dp else 84.dp)
        }
    }
}
