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

/** Aba "Instrumentos": estudo de piano (escalas, dicionário de acordes, exercícios da sua partitura), violão (braço e acordes) e flauta doce (dedilhado). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun InstrumentsScreen(state: AppState, wide: Boolean) {
    var inst by rememberSaveable { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Instrumentos", style = MaterialTheme.typography.headlineMedium)
        Text("Estude no teclado, no braço do violão e na flauta doce. Toque em qualquer tecla ou casa para ver e ouvir a nota.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Wrap { listOf("Piano", "Violão", "Flauta doce").forEachIndexed { i, n -> FilterChip(selected = inst == i, onClick = { inst = i }, label = { Text(n) }) } }
        when (inst) {
            0 -> PianoStudy(state, wide)
            1 -> GuitarStudy(state)
            else -> RecorderStudy(state)
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

    Wrap { listOf("Escalas", "Dicionário de acordes", "Exercícios da sua música").forEachIndexed { i, n -> FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(n) }) } }

    // teclado de referência: mão direita roxa, mão esquerda vermelha
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
                val steps = scaleType.intervals.let { iv -> (iv + 12).zipWithNext { a, b -> if (b - a == 2) "T" else if (b - a == 1) "S" else "${(b - a)}" } }
                Text("Tons e semitons: " + steps.joinToString(" "), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                val fing = if (scaleType == ScaleType.MAIOR) Study.fingering(tn, false) else if (scaleType == ScaleType.MENOR_NATURAL) Study.fingering(tn, true) else null
                if (fing != null) {
                    Text("Mão direita (subindo): " + fing.first.joinToString(" "), color = Color(Roles.ARGB[1]))
                    Text("Mão esquerda (subindo): " + fing.second.joinToString(" "), color = Color(Roles.ARGB[2]))
                } else Text("Digitação: o app só mostra a que tem certeza (maiores C G D A E F e menores naturais Lá, Mi, Ré). Nesta escala, procure a indicação do seu professor ou método.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                val fingers = Study.chordFingers(inv.size)
                Text("Direita (roxo): " + inv.joinToString(" ") { Names.step(it.step, it.alter) } + (fingers?.let { "  ·  dedos " + it.first.joinToString("-") } ?: ""), color = Color(Roles.ARGB[1]))
                Text("Esquerda (vermelho): ${Names.step(bassRoot.step, bassRoot.alter)} (fundamental, uma oitava abaixo)" + (fingers?.let { "  ·  dedo 5" } ?: ""), color = Color(Roles.ARGB[2]))
                val sc = remember(rootIdx, chordIdx, invIdx) { Theory.toScore(Study.chordName(rn, type), inv, 4.0, 0, chord = true) }
                StaticScore(sc, 0, 160.dp, q = if (playing === sc) qNow else null)
                Wrap {
                    Button(onClick = { playScore(sc, 80) }) { Text("Ouvir acorde") }
                    OutlinedButton(onClick = { playScore(Theory.toScore("Arpejo", inv, 1.0, 0), 100) }) { Text("Ouvir arpejo") }
                }
            }
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
        PianoKeyboard(first, last, rh + lh, emptySet(), { m -> tapped = m; p.key(m) }, height = if (wide) 120.dp else 90.dp, roles = roles, attack = rh + lh)
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

    Wrap { listOf("Braço: todas as notas", "Dicionário de acordes").forEachIndexed { i, n -> FilterChip(selected = mode == i, onClick = { mode = i }, label = { Text(n) }) } }
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
            Fretboard(showPc, tapped) { s, f -> tapped = s to f; p.key(Study.guitarMidi(s, f), "guitar") }
            if (showPc >= 0) {
                val pos = Study.guitarPositions(showPc)
                Text("${Study.pcName(showPc)} aparece em ${pos.size} casas: " + pos.sortedBy { it.first }.reversed().joinToString("; ") { "corda ${6 - it.first} casa ${it.second}" }, style = MaterialTheme.typography.bodySmall)
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
private fun Fretboard(showPc: Int, tapped: Pair<Int, Int>?, onTap: (Int, Int) -> Unit) {
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
                val isTap = tapped?.first == s && tapped.second == f
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

// ====================================================================== FLAUTA DOCE

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecorderStudy(state: AppState) {
    val p = state.player
    var sel by rememberSaveable { mutableIntStateOf(0) }
    val f = Study.RECORDER[sel.coerceIn(0, Study.RECORDER.lastIndex)]
    Panel {
        Text("Flauta doce soprano (dedilhado barroco/inglês)", style = MaterialTheme.typography.titleMedium)
        Text("A soprano começa no Dó5 (todos os furos tampados) e sobe sem trocar de instrumento. Para o som uma oitava acima (oitavado), o dedilhado muda e o sopro fica um pouco mais forte, mas sem estourar. Bolinha cheia = furo tampado; vazia = aberto. O primeiro furo é o do polegar, atrás.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Wrap { Study.RECORDER.forEachIndexed { i, fg -> FilterChip(selected = sel == i, onClick = { sel = i; p.key(fg.pitch.midi, "flute") }, label = { Text(Names.step(fg.pitch.step, fg.pitch.alter) + (if (fg.pitch.octave >= 6) " (oitavado)" else "")) }) } }
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
            RecorderDiagram(f.holes, Modifier.width(76.dp).height(260.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(Study.ptName(f.pitch) + (if (f.pitch.octave >= 6) " — oitavado (agudo)" else if (f.pitch.step in listOf('C', 'D') && f.pitch.alter == 0) " — normal (grave)" else ""), style = MaterialTheme.typography.titleMedium)
                Text(midiLabel(f.pitch.midi), style = MaterialTheme.typography.bodySmall)
                Text("Furos tampados: " + (if (f.holes[0]) "polegar" else "polegar solto") + ", " + f.holes.indices.drop(1).filter { f.holes[it] }.joinToString(" ").ifEmpty { "nenhum" }, style = MaterialTheme.typography.bodyMedium)
                if (f.note.isNotBlank()) Text(f.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { p.key(f.pitch.midi, "flute") }) { Text("Ouvir") }
            }
        }
        Text("Dica: Dó e Ré aparecem duas vezes na lista — o normal (grave) e o oitavado (agudo). Notas de outras flautas (contralto, tenor) soam em outro registro.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RecorderDiagram(holes: BooleanArray, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRoundRect(cs.surfaceVariant, Offset(w * 0.30f, 0f), Size(w * 0.40f, h), androidx.compose.ui.geometry.CornerRadius(w * 0.2f))
        fun hole(i: Int, x: Float, y: Float, r: Float) {
            if (holes[i]) drawCircle(cs.primary, r, Offset(x, y)) else drawCircle(cs.onSurface, r, Offset(x, y), style = Stroke(3f))
        }
        val r = w * 0.15f
        hole(0, w * 0.10f, h * 0.08f, r * 0.9f)                // polegar (atrás)
        hole(1, w * 0.5f, h * 0.12f, r); hole(2, w * 0.5f, h * 0.22f, r); hole(3, w * 0.5f, h * 0.32f, r)
        hole(4, w * 0.5f, h * 0.48f, r)
        hole(5, w * 0.5f, h * 0.58f, r)
        hole(6, w * 0.40f, h * 0.70f, r * 0.8f); hole(6, w * 0.60f, h * 0.70f, r * 0.8f)
        hole(7, w * 0.40f, h * 0.82f, r * 0.8f); hole(7, w * 0.60f, h * 0.82f, r * 0.8f)
    }
}
