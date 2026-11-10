package app.partitura

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.partitura.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val MINOR_TYPES = setOf(ScaleType.MENOR_NATURAL, ScaleType.MENOR_HARMONICA, ScaleType.MENOR_MELODICA, ScaleType.DORICO, ScaleType.FRIGIO)

/** Aba "Instrumentos": estudo de piano (escalas, dicionário e identificador de acordes, exercícios da sua partitura) e violão (braço e acordes). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstrumentsScreen(state: AppState, wide: Boolean) {
    var inst by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Instrumentos", style = MaterialTheme.typography.headlineMedium)
        Text("Toque numa tecla ou casa para ouvir. Monte um acorde e o app diz qual é.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Wrap { listOf("Piano", "Violão").forEachIndexed { i, n -> FilterChip(selected = inst == i, onClick = { inst = i }, label = { Text(n) }) } }
        when (inst) {
            0 -> PianoStudy(state, wide)
            else -> GuitarStudy(state)
        }
    }
}

private fun midiLabel(m: Int): String {
    val p = Spell.forMidi(m, 0)
    return Names.pitch(p) + " (" + Study.ptName(p) + ") · " + "%.1f".format(Study.freq(m)) + " Hz"
}

// ====================================================================== PIANO

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PianoStudy(state: AppState, wide: Boolean) {
    val p = state.player
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var scaleType by remember { mutableStateOf(ScaleType.MAIOR) }
    var tonicIdx by rememberSaveable { mutableIntStateOf(0) }
    var rootIdx by rememberSaveable { mutableIntStateOf(0) }
    var chordIdx by rememberSaveable { mutableIntStateOf(0) }
    var invIdx by rememberSaveable { mutableIntStateOf(0) }
    var tapped by remember { mutableStateOf<Int?>(null) }
    var playing by remember { mutableStateOf<Score?>(null) }
    fun playScore(sc: Score, tempo: Int) { playing = sc; p.start(sc, tempo, listOf(1.0), 0.0, Double.MAX_VALUE, false) }
    val qNow: () -> Double? = { if (p.active) p.posQ else null }

    Wrap { listOf("Escalas", "Dicionário de acordes", "Exercícios da sua música", "Identificar acorde").forEachIndexed { i, n -> FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(n) }) } }

    // teclado de referência: mão direita roxa, mão esquerda vermelha
    val picked = remember { mutableStateListOf<Int>() }
    var rh by remember { mutableStateOf(emptySet<Int>()) }
    var lh by remember { mutableStateOf(emptySet<Int>()) }

    when (mode) {
        0 -> {
            Wrap { ScaleType.values().forEach { t -> FilterChip(selected = scaleType == t, onClick = { scaleType = t }, label = { Text(t.label) }) } }
            val minor = scaleType in MINOR_TYPES
            val tonics = if (minor) Study.MINOR_TONICS else Study.MAJOR_TONICS
            Wrap { tonics.forEachIndexed { i, (n, _) -> FilterChip(selected = tonicIdx.coerceIn(0, 11) == i, onClick = { tonicIdx = i }, label = { Text(n) }) } }
            val (tn, tonic) = tonics[tonicIdx.coerceIn(0, 11)]
            val notes = remember(tonicIdx, scaleType) { Theory.scale(tonic, scaleType) }
            val left = remember(tonicIdx, scaleType) { notes.map { it.copy(octave = it.octave - 1) } }
            LaunchedEffect(tonicIdx, scaleType) { rh = notes.map { it.midi }.toSet(); lh = left.map { it.midi }.toSet() }
            val sc = remember(tonicIdx, scaleType) { Theory.toScore("$tn ${scaleType.label}", notes, 1.0, 0) }
            Panel {
                Text("$tn ${scaleType.label.lowercase()}", style = MaterialTheme.typography.titleMedium)
                Text(notes.joinToString(" – ") { Names.step(it.step, it.alter) } + "   (" + notes.dropLast(1).joinToString(", ") { Study.ptShort(it.step, it.alter) } + ")")
                Collapsible("Tons, semitons e dedilhado") {
                    val steps = scaleType.intervals.let { iv -> (iv + 12).zipWithNext { a, b -> if (b - a == 2) "T" else if (b - a == 1) "S" else "${(b - a)}" } }
                    Text("Tons e semitons: " + steps.joinToString(" "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    val fing = if (scaleType == ScaleType.MAIOR) Study.fingering(tn, false) else if (scaleType == ScaleType.MENOR_NATURAL) Study.fingering(tn, true) else null
                    if (fing != null) {
                        Text("Mão direita (subindo): " + fing.first.joinToString(" "), color = Color(Roles.ARGB[1]))
                        Text("Mão esquerda (subindo): " + fing.second.joinToString(" "), color = Color(Roles.ARGB[2]))
                    } else Text("Dedilhado: o app só mostra o que tem certeza (maiores C G D A E F; menores naturais Lá, Mi, Ré). Para esta escala, siga seu professor ou método.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StaticScore(sc, 0, 190.dp, q = if (playing === sc) qNow else null)
                Wrap {
                    Button(onClick = { playScore(sc, 100) }) { Text("Ouvir escala") }
                    OutlinedButton(onClick = { state.open(sc.copyDeep()); state.goStudio() }) { Text("Abrir no Estúdio") }
                }
            }
        }
        1 -> {
            Wrap { Study.CHORD_ROOTS.forEachIndexed { i, (n, _) -> FilterChip(selected = rootIdx == i, onClick = { rootIdx = i; invIdx = 0 }, label = { Text(n) }) } }
            Wrap { Study.CHORDS.forEachIndexed { i, t -> FilterChip(selected = chordIdx == i, onClick = { chordIdx = i; invIdx = 0 }, label = { Text(if (t.suffix.isEmpty()) "Maior" else t.suffix) }) } }
            val (rn, root) = Study.CHORD_ROOTS[rootIdx]
            val type = Study.CHORDS[chordIdx]
            val chord = remember(rootIdx, chordIdx) { Study.chordNotes(root.copy(octave = 4), type) }
            val invs = remember(rootIdx, chordIdx) { Study.inversions(chord) }
            val inv = invs[invIdx.coerceIn(0, invs.lastIndex)]
            val bassRoot = chord.first().copy(octave = 3)
            LaunchedEffect(rootIdx, chordIdx, invIdx) { rh = inv.map { it.midi }.toSet(); lh = setOf(bassRoot.midi) }
            Panel {
                Text(Study.chordName(rn, type) + "  ·  " + type.name, style = MaterialTheme.typography.titleMedium)
                Text("Notas: " + chord.joinToString(" – ") { Names.step(it.step, it.alter) } + "   (fórmula " + type.tones + ")")
                Text(chord.joinToString(", ") { Study.ptShort(it.step, it.alter) }, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Wrap { invs.forEachIndexed { i, _ -> FilterChip(selected = invIdx == i, onClick = { invIdx = i }, label = { Text(if (i == 0) "Posição fundamental" else "${i}ª inversão") }) } }
                Collapsible("Mãos e dedos") {
                    val fingers = Study.chordFingers(inv.size)
                    Text("Direita (roxo): " + inv.joinToString(" ") { Names.step(it.step, it.alter) } + (fingers?.let { "  ·  dedos " + it.first.joinToString("-") } ?: ""), color = Color(Roles.ARGB[1]))
                    Text("Esquerda (vermelho): ${Names.step(bassRoot.step, bassRoot.alter)} (fundamental, uma oitava abaixo)" + (fingers?.let { "  ·  dedo 5" } ?: ""), color = Color(Roles.ARGB[2]))
                }
                val sc = remember(rootIdx, chordIdx, invIdx) { Theory.toScore(Study.chordName(rn, type), inv, 4.0, 0, chord = true) }
                StaticScore(sc, 0, 160.dp, q = if (playing === sc) qNow else null)
                Wrap {
                    Button(onClick = { playScore(sc, 80) }) { Text("Ouvir acorde") }
                    OutlinedButton(onClick = { playScore(Theory.toScore("Arpejo", inv, 1.0, 0), 100) }) { Text("Ouvir arpejo") }
                }
            }
        }
        3 -> {
            LaunchedEffect(picked.toList()) { rh = picked.toSet(); lh = emptySet() }
            IdentifyPanel(
                picked.toList(), "Toque nas teclas: o app anota e diz o acorde. Toque de novo para tirar.",
                onClear = { picked.clear() }, onUndo = { if (picked.isNotEmpty()) picked.removeAt(picked.lastIndex) },
                onPlay = { picked.forEach { m -> p.key(m) } },
                onArp = { scope.launch { picked.sorted().forEach { m -> p.key(m); delay(260) } } },
            )
        }
        else -> {
            val s = state.score
            if (s == null) {
                Panel { Text("Abra uma partitura (Início ou Estúdio) e volte aqui: o app cria exercícios no tom e nos acordes dela — escala, oitavas, cinco dedos, saltos, arpejos, acompanhamento, baixo de Alberti e cadência.") }
            } else {
                val ex = remember(s, state.rev) { try { Study.exercises(s) } catch (_: Throwable) { emptyList() } }
                val minor = Arrange.inferMinor(s)
                Text("Gerados no tom de ${Names.key(s.fifths)} ${if (minor) "menor (relativa ${Names.minorKey(s.fifths)})" else "maior"}, a partir de «${s.title}». Mão direita no Sol, esquerda no Fá.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                ex.forEach { e ->
                    Panel {
                        Text(e.title, style = MaterialTheme.typography.titleMedium)
                        Text(e.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        StaticScore(e.score, 0, 230.dp, q = if (playing === e.score) qNow else null)
                        Wrap {
                            Button(onClick = { playScore(e.score, e.score.tempo) }) { Text("Ouvir") }
                            OutlinedButton(onClick = { state.open(e.score.copyDeep(), e.title); state.goStudio() }) { Text("Abrir no Estúdio") }
                        }
                    }
                }
            }
        }
    }

    // teclado: roxo = mão direita, vermelho = mão esquerda
    val tapInfo = tapped?.let { midiLabel(it) }
    Panel(pad = 10.dp) {
        Text(tapInfo ?: "Toque numa tecla para ver o nome e ouvir.", style = MaterialTheme.typography.titleSmall)
        val first = 48; val last = if (wide) 84 else 72
        val roles = remember(rh, lh) { buildMap { for (m in lh) put(m, 2); for (m in rh) put(m, 1) } }
        PianoKeyboard(first, last, rh + lh, emptySet(), { m -> tapped = m; p.key(m); if (mode == 3) { if (m in picked) picked.remove(m) else picked.add(m) } }, height = if (wide) 120.dp else 90.dp, roles = roles, attack = rh + lh)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("● mão direita", color = Color(Roles.ARGB[1]), style = MaterialTheme.typography.labelMedium)
            Text("● mão esquerda", color = Color(Roles.ARGB[2]), style = MaterialTheme.typography.labelMedium)
        }
    }
}

// ====================================================================== VIOLÃO

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuitarStudy(state: AppState) {
    val p = state.player
    val scope = rememberCoroutineScope()
    var mode by rememberSaveable { mutableIntStateOf(0) }
    var showPc by rememberSaveable { mutableIntStateOf(-1) }       // -1 = nome de todas as notas
    var tapped by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var rootIdx by rememberSaveable { mutableIntStateOf(0) }
    var chordIdx by rememberSaveable { mutableIntStateOf(0) }
    val frets = remember { mutableStateListOf<Pair<Int, Int>>() }   // uma casa por corda, na ordem em que foram tocadas

    Wrap { listOf("Braço: todas as notas", "Dicionário de acordes", "Identificar acorde").forEachIndexed { i, n -> FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(n) }) } }
    if (mode == 0) {
        Panel {
            Text("Cordas soltas (da mais grossa para a mais fina): Mi, Lá, Ré, Sol, Si, Mi", style = MaterialTheme.typography.titleSmall)
            Text("Cada casa sobe um semitom. Escolha uma nota para ver todas as casas onde ela aparece, ou deixe em «Todas» para ver o nome de cada casa.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Wrap {
                FilterChip(selected = showPc == -1, onClick = { showPc = -1 }, label = { Text("Todas") })
                (0 until 12).forEach { pc -> FilterChip(selected = showPc == pc, onClick = { showPc = pc }, label = { Text(Study.pcName(pc)) }) }
            }
            val t = tapped
            Text(if (t != null) { val m = Study.guitarMidi(t.first, t.second); "Corda ${6 - t.first} (${Study.GUITAR_STRING_NAMES[t.first]}), casa ${t.second}: " + midiLabel(m) } else "Toque numa casa para ouvir.", style = MaterialTheme.typography.titleSmall)
            Fretboard(showPc, setOfNotNull(tapped)) { s, f -> tapped = s to f; p.key(Study.guitarMidi(s, f), "guitar") }
            if (showPc >= 0) {
                val pos = Study.guitarPositions(showPc)
                Text("${Study.pcName(showPc)} aparece em ${pos.size} casas: " + pos.sortedBy { it.first }.reversed().joinToString("; ") { "corda ${6 - it.first} casa ${it.second}" }, style = MaterialTheme.typography.bodySmall)
            }
        }
    } else if (mode == 2) {
        val ms = frets.map { Study.guitarMidi(it.first, it.second) }
        IdentifyPanel(
            ms, "Toque uma casa por corda (solta = 0). Toque de novo para tirar.",
            onClear = { frets.clear() }, onUndo = { if (frets.isNotEmpty()) frets.removeAt(frets.lastIndex) },
            onPlay = { frets.forEach { (s, f) -> p.key(Study.guitarMidi(s, f), "guitar") } },
            onArp = { scope.launch { frets.sortedBy { it.first }.forEach { (s, f) -> p.key(Study.guitarMidi(s, f), "guitar"); delay(120) } } },
        )
        Panel(pad = 10.dp) {
            Fretboard(-1, frets.toSet()) { s, f ->
                p.key(Study.guitarMidi(s, f), "guitar")
                val same = frets.firstOrNull { it.first == s }
                if (same == s to f) frets.remove(same) else { if (same != null) frets.remove(same); frets.add(s to f) }
            }
        }
    } else {
        Wrap { Study.CHORD_ROOTS.forEachIndexed { i, (n, _) -> FilterChip(selected = rootIdx == i, onClick = { rootIdx = i }, label = { Text(n) }) } }
        Wrap { Study.CHORDS.forEachIndexed { i, t -> FilterChip(selected = chordIdx == i, onClick = { chordIdx = i }, label = { Text(if (t.suffix.isEmpty()) "Maior" else t.suffix) }) } }
        val (rn, root) = Study.CHORD_ROOTS[rootIdx]
        val type = Study.CHORDS[chordIdx]
        val shapes = remember(rootIdx, chordIdx) { Study.guitarShapes(root, type) }
        val notes = remember(rootIdx, chordIdx) { Study.chordNotes(root, type) }
        Panel {
            Text(Study.chordName(rn, type) + "  ·  " + type.name, style = MaterialTheme.typography.titleMedium)
            Text("Notas: " + notes.joinToString(" – ") { Names.step(it.step, it.alter) } + "  (fórmula ${type.tones})")
            if (shapes.isEmpty()) Text("Não achei forma tocável nas primeiras 12 casas com todas as notas essenciais.", color = MaterialTheme.colorScheme.error)
            Text("Formas, da mais fácil para a mais aguda. X = não toca a corda, O = corda solta, número = casa. Toque no desenho para ouvir.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                shapes.forEach { sh ->
                    ChordDiagram(sh) {
                        scope.launch { sh.frets.forEachIndexed { s, f -> if (f >= 0) { p.key(Study.guitarMidi(s, f), "guitar"); delay(55) } } }
                    }
                }
            }
        }
    }
}

@Composable
private fun Fretboard(showPc: Int, tapped: Set<Pair<Int, Int>>, onTap: (Int, Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val cell = 52f; val top = 26f; val gap = 30f
    val frets = Study.GUITAR_FRETS
    val wDp = (cell * (frets + 1.4f)).dp; val hDp = (top + gap * 6 + 30f).dp
    Box(Modifier.horizontalScroll(rememberScrollState())) {
        Canvas(
            Modifier.width(wDp).height(hDp).pointerInput(Unit) {
                detectTapGestures { off ->
                    val d = density
                    val s = 5 - ((off.y / d - top + gap / 2) / gap).toInt().coerceIn(0, 5)
                    // x: casa 0 fica à esquerda da pestana; as demais em colunas
                    val f = Math.round(((off.x / d) - cell * 0.4f) / cell)
                    onTap(s, f.coerceIn(0, frets))
                }
            },
        ) {
            val d = density
            val u = d
            val nut = cell * 0.9f * u
            val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = cs.onSurface.toArgb(); textSize = 11f * u }
            val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = android.graphics.Color.WHITE; textSize = 10.5f * u; isFakeBoldText = true }
            // casas
            for (f in 1..frets + 1) {
                val x = nut + (f - 1) * cell * u
                drawLine(cs.outline, Offset(x, top * u - 4 * u), Offset(x, (top + gap * 5) * u + 4 * u), strokeWidth = if (f == 1) 5f else 1.5f)
            }
            for (f in 1..frets) {
                val cx = nut + (f - 0.5f) * cell * u
                if (f in listOf(3, 5, 7, 9, 15, 17, 19)) drawCircle(cs.outlineVariant, 5f * u, Offset(cx, (top + gap * 2.5f) * u))
                if (f == 12) { drawCircle(cs.outlineVariant, 5f * u, Offset(cx, (top + gap * 1.5f) * u)); drawCircle(cs.outlineVariant, 5f * u, Offset(cx, (top + gap * 3.5f) * u)) }
                drawIntoCanvas { it.nativeCanvas.drawText("$f", cx, (top + gap * 5 + 24) * u, label) }
            }
            // cordas (corda 6 embaixo)
            for (s in 0 until 6) {
                val y = (top + gap * (5 - s)) * u
                drawLine(cs.onSurfaceVariant, Offset(nut - cell * 0.35f * u, y), Offset(nut + frets * cell * u, y), strokeWidth = (1.2f + (5 - s) * 0.45f))
            }
            // notas
            for (s in 0 until 6) for (f in 0..frets) {
                val pc = Math.floorMod(Study.guitarMidi(s, f), 12)
                val show = showPc == -1 || pc == showPc
                val isTap = (s to f) in tapped
                if (!show && !isTap) continue
                val cx = if (f == 0) nut - cell * 0.35f * u else nut + (f - 0.5f) * cell * u
                val cy = (top + gap * (5 - s)) * u
                val root = showPc >= 0 && pc == showPc
                drawCircle(if (isTap) Color(Roles.ARGB[0]) else if (root) cs.primary else cs.secondary, 11f * u, Offset(cx, cy))
                drawIntoCanvas { it.nativeCanvas.drawText(Study.pcName(pc), cx, cy + 4f * u, white) }
            }
        }
    }
}

@Composable
private fun ChordDiagram(sh: Study.Shape, onTap: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val w = 104f; val h = 128f
    Canvas(Modifier.width(w.dp).height(h.dp).pointerInput(sh) { detectTapGestures { onTap() } }) {
        val u = density
        val left = 22f * u; val topY = 26f * u
        val sx = (w - 34f) / 5f * u; val fy = 20f * u
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = cs.onSurface.toArgb(); textSize = 12f * u }
        val base = if (sh.baseFret <= 1 && sh.frets.none { it > 4 }) 1 else sh.baseFret
        for (i in 0..4) drawLine(cs.outline, Offset(left, topY + i * fy), Offset(left + 5 * sx, topY + i * fy), strokeWidth = if (i == 0 && base == 1) 5f else 1.5f)
        for (s in 0 until 6) drawLine(cs.onSurfaceVariant, Offset(left + s * sx, topY), Offset(left + s * sx, topY + 4 * fy), strokeWidth = 1.5f)
        if (base > 1) drawIntoCanvas { it.nativeCanvas.drawText("${base}ª", left - 12f * u, topY + fy * 0.7f, label) }
        // pestana
        if (sh.barre) {
            val minF = sh.frets.filter { it > 0 }.minOrNull() ?: 0
            val idx = sh.frets.indices.filter { sh.frets[it] == minF }
            if (idx.size >= 2) drawRoundRect(cs.primary, Offset(left + idx.first() * sx - 7f * u, topY + (minF - base + 0.5f) * fy - 6f * u), Size((idx.last() - idx.first()) * sx + 14f * u, 12f * u), androidx.compose.ui.geometry.CornerRadius(6f * u))
        }
        for (s in 0 until 6) {
            val f = sh.frets[s]
            val x = left + s * sx
            when {
                f < 0 -> drawIntoCanvas { it.nativeCanvas.drawText("X", x, topY - 8f * u, label) }
                f == 0 -> drawCircle(cs.onSurface, 5f * u, Offset(x, topY - 12f * u), style = Stroke(2f))
                else -> drawCircle(cs.primary, 7.5f * u, Offset(x, topY + (f - base + 0.5f) * fy))
            }
        }
        val low = sh.notes.first()
        drawIntoCanvas { it.nativeCanvas.drawText(Study.pcName(low), left + 2.5f * sx, topY + 4 * fy + 20f * u, label) }
    }
}

/** Painel comum de "identificar acorde": mostra as notas anotadas e os acordes que elas formam. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentifyPanel(midis: List<Int>, help: String, onClear: () -> Unit, onUndo: () -> Unit, onPlay: () -> Unit, onArp: () -> Unit) {
    val found = remember(midis) { Identify.chords(midis) }
    Panel {
        Text("Identificar acorde", style = MaterialTheme.typography.titleMedium)
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (midis.isEmpty()) Text("Nenhuma nota ainda.", style = MaterialTheme.typography.titleSmall)
        else {
            Text("Notas: " + midis.joinToString(" – ") { Names.pitch(Spell.forMidi(it, 0)) }, style = MaterialTheme.typography.titleSmall)
            val pcs = midis.map { Math.floorMod(it, 12) }.distinct()
            if (found.isEmpty()) Text(
                if (pcs.size < 3) "Preciso de pelo menos 3 notas diferentes para dizer um acorde (tenho ${pcs.size})."
                else "Não encontrei um acorde do dicionário com exatamente essas notas.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            ) else {
                val best = found.first()
                Text(best.name, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
                Text(best.description + " · notas: " + best.notes.joinToString(" – ") { Names.step(it.step, it.alter) }, style = MaterialTheme.typography.bodyMedium)
                if (found.size > 1) {
                    Text("Também pode ser:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    found.drop(1).take(5).forEach { Text("• ${it.name} — ${it.description}", style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
        Wrap {
            Button(onClick = onPlay, enabled = midis.isNotEmpty()) { Text("Ouvir junto") }
            OutlinedButton(onClick = onArp, enabled = midis.isNotEmpty()) { Text("Ouvir em arpejo") }
            OutlinedButton(onClick = onUndo, enabled = midis.isNotEmpty()) { Text("Desfazer") }
            OutlinedButton(onClick = onClear, enabled = midis.isNotEmpty()) { Text("Limpar") }
        }
    }
}
