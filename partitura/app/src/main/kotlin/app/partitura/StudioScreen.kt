package app.partitura

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.partitura.core.*

private val DURS = listOf(0xE1D2 to 4.0, 0xE1D3 to 2.0, 0xE1D5 to 1.0, 0xE1D7 to 0.5, 0xE1D9 to 0.25)

@Composable
private fun GlyphBtn(code: Int, onClick: () -> Unit, selected: Boolean = false, enabled: Boolean = true, size: Int = 17) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.primaryContainer else Color0
    val fg = if (selected) cs.onPrimaryContainer else cs.onSurface
    Box(Modifier.size(42.dp).clip(MaterialTheme.shapes.medium).background(bg).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        GlyphText(code, size.dp, if (enabled) fg else fg.copy(alpha = 0.3f), base = 0.86f)
    }
}
private val Color0 = androidx.compose.ui.graphics.Color.Transparent

@Composable
private fun TextBtn(label: String, onClick: () -> Unit, selected: Boolean = false, enabled: Boolean = true) {
    val cs = MaterialTheme.colorScheme
    val bg = if (selected) cs.primaryContainer else cs.surfaceVariant
    val fg = if (selected) cs.onPrimaryContainer else cs.onSurface
    Box(Modifier.height(38.dp).clip(MaterialTheme.shapes.medium).background(bg).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) fg else fg.copy(alpha = 0.35f), style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun StudioScreen(state: AppState, wide: Boolean, goHome: () -> Unit) {
    val s = state.score
    if (s == null) { Empty("Nenhuma partitura aberta", "Abra um arquivo ou crie uma partitura nova na tela Início.", "Ir para o Início", goHome); return }
    val rev = state.rev
    val p = state.player
    var spDp by remember { mutableFloatStateOf(if (wide) 8.5f else 6.5f) }
    var showMixer by remember { mutableStateOf(false) }
    var showInstr by remember { mutableStateOf(false) }
    var showPiano by remember { mutableStateOf(true) }
    var saveDlg by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current.density
    var viewport by remember { mutableIntStateOf(0) }
    val octs = if (wide) 4 else 2
    var kbFirst by remember { mutableIntStateOf(if (wide) 48 else 60) }
    val kbLast = kbFirst + 12 * octs

    Column(Modifier.fillMaxSize()) {
        // ---- barra superior ----
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(s.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (state.editMode) {
                ToolBtn(Ic.UNDO, { state.undo() }, enabled = state.canUndo)
                ToolBtn(Ic.REDO, { state.redo() }, enabled = state.canRedo)
            }
            ToolBtn(Ic.EDIT, { state.editMode = !state.editMode }, selected = state.editMode)
            ToolBtn(Ic.SAVE, { if (state.projectName != null) state.save(state.projectName!!) else saveDlg = true })
            ToolBtn(Ic.INSTR, { showInstr = true }, desc = "Instrumentos")
            ToolBtn(Ic.MIXER, { showMixer = true })
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).onSizeChanged { viewport = it.height }) {
            val spPx = spDp * density
            val widthPx = constraints.maxWidth.toFloat()
            val scene = remember(s, rev, widthPx, spPx) { Engrave.scene(s, widthPx / spPx) }
            val selId = if (!state.hasSel) -1 else scene.notes.indexOfFirst { it.part == state.selP && it.m == state.selM && it.idx == state.selN }
            val activeIds by remember(scene) { derivedStateOf { if (p.active) scene.soundingAt(p.posQ) else emptyList() } }
            val activeSet = remember(activeIds) { activeIds.toSet() }
            val activeMidi = remember(activeIds, scene) { activeIds.map { scene.notes[it].midi }.toSet() }
            val curSystem by remember(scene) { derivedStateOf { if (p.active) (scene.cursor(p.posQ)?.first ?: -1) else -1 } }

            LaunchedEffect(curSystem, scene) {
                if (curSystem >= 0 && curSystem < scene.systems.size) {
                    val sys = scene.systems[curSystem]
                    val top = sys.top * spPx; val bottom = sys.bottom * spPx
                    if (top < scroll.value || bottom > scroll.value + viewport) scroll.animateScrollTo((top - 12 * density).toInt().coerceAtLeast(0))
                }
            }
            LaunchedEffect(selId, scene) {
                if (state.editMode && selId >= 0 && !p.active) {
                    val n = scene.notes[selId]
                    val si = scene.systems.indexOfFirst { sys -> sys.measures.any { it.index == n.m } }
                    if (si >= 0) {
                        val sys = scene.systems[si]
                        val top = sys.top * spPx; val bottom = sys.bottom * spPx
                        if (top < scroll.value || bottom > scroll.value + viewport) scroll.animateScrollTo((top - 12 * density).toInt().coerceAtLeast(0))
                    }
                }
            }
            LaunchedEffect(activeMidi) {
                if (activeMidi.isNotEmpty()) {
                    val lo = activeMidi.min(); val hi = activeMidi.max()
                    if (lo < kbFirst || hi > kbLast) kbFirst = (((lo + hi) / 2 - 6 * octs) / 12 * 12).coerceIn(24, 96 - 12 * octs)
                }
            }

            val onTap: (Float, Float) -> Unit = { x, y ->
                if (state.editMode && state.drawTool) {
                    val st = SceneHit.staffAt(scene, x, y)
                    val id = if (st != null) SceneHit.nearestInPart(scene, x, y, st.first) else null
                    if (st != null && id != null) {
                        val n = scene.notes[id]
                        state.select(n.part, n.m, n.idx)
                        state.edit { Edit.setPitchAtStaffPos(it, n.part, n.m, n.idx, st.second) }
                        state.score?.parts?.getOrNull(n.part)?.measures?.getOrNull(n.m)?.notes?.getOrNull(n.idx)?.pitch?.let { pt -> p.key(pt.midi) }
                    }
                } else {
                    val id = SceneHit.nearest(scene, x, y)
                    if (id != null) {
                        val n = scene.notes[id]
                        state.select(n.part, n.m, n.idx)
                        if (state.editMode && n.midi >= 0) p.key(n.midi)
                    } else if (!state.editMode) state.hasSel = false
                }
            }

            Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                ScoreView(
                    scene = scene, spPx = spPx, active = activeSet, selected = selId,
                    cursor = { if (p.active) scene.cursor(p.posQ) else null },
                    scroll = scroll, viewportPx = viewport, onTap = onTap,
                    modifier = Modifier.clip(MaterialTheme.shapes.medium),
                )
            }

            // ---- transporte (flutuante embaixo da pauta) ----
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp).clip(MaterialTheme.shapes.large)
                    .background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                ToolBtn(Ic.START, { p.stop(); state.hasSel = false }, desc = "Início")
                ToolBtn(
                    if (p.playing) Ic.PAUSE else Ic.PLAY,
                    {
                        when {
                            p.busy -> {}
                            p.playing -> p.pause()
                            p.paused -> p.resume()
                            else -> state.play(if (selId >= 0) scene.notes[selId].startQ else null)
                        }
                    },
                    primary = true,
                )
                ToolBtn(Ic.STOP, { p.stop() }, enabled = p.active || p.busy)
                ToolBtn(Ic.LOOP, { state.loop = !state.loop }, selected = state.loop)
                Text(
                    "♩ ${state.tempo}", Modifier.clip(MaterialTheme.shapes.medium).clickable { showMixer = true }.padding(horizontal = 10.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
                ToolBtn(Ic.ZOOM_OUT, { spDp = (spDp - 1f).coerceAtLeast(4f) })
                ToolBtn(Ic.ZOOM_IN, { spDp = (spDp + 1f).coerceAtMost(16f) })
                ToolBtn(Ic.PIANO, { showPiano = !showPiano }, selected = showPiano)
            }
            if (p.busy) LinearProgressIndicator(Modifier.align(Alignment.TopCenter).fillMaxWidth())
        }

        if (state.editMode) EditPanel(state, s)
        if (showPiano) {
            val marks = remember { emptySet<Int>() }
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 8.dp, vertical = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ToolBtn(Ic.BACK, { kbFirst = (kbFirst - 12).coerceAtLeast(24) })
                    Text("C${kbFirst / 12 - 1} – C${kbLast / 12 - 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ToolBtn(Ic.NEXT, { kbFirst = (kbFirst + 12).coerceAtMost(96 - 12 * octs) })
                    Spacer(Modifier.weight(1f))
                    Text(if (state.editMode) "As teclas escrevem a nota na partitura" else "Toque para ouvir", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PianoKeyboard(kbFirst, kbLast, pianoActive(state, p), marks, { m -> if (state.editMode) state.writeNote(m) else p.key(m, state.timbreOf(state.selP)) }, height = if (wide) 110.dp else 84.dp)
            }
        }
    }
    if (showMixer) MixerSheet(state, s) { showMixer = false }
    if (showInstr) InstrumentsSheet(state, s) { showInstr = false }
    if (saveDlg) {
        var name by remember { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { saveDlg = false },
            title = { Text("Salvar projeto") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, singleLine = true) },
            confirmButton = { Button(onClick = { state.save(name); saveDlg = false }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { saveDlg = false }) { Text("Cancelar") } },
        )
    }
}

/** Notas soando agora, calculadas de novo a cada mudança de posição (o piano de baixo acompanha a música). */
@Composable
private fun pianoActive(state: AppState, p: Player): Set<Int> {
    val s = state.score ?: return emptySet()
    val rev = state.rev
    val ev = remember(s, rev, p.usedTempo) { Synth.events(s, p.usedTempo) }
    val set by remember(ev) {
        derivedStateOf {
            if (!p.active) emptySet<Int>() else { val t = p.posSec; ev.filter { it.startSec <= t + 1e-6 && t < it.startSec + it.durSec }.map { it.midi }.toSet() }
        }
    }
    return set
}

private fun partLabel(s: Score, i: Int): String {
    val p = s.parts[i]
    val base = p.name.substringBefore(" (pauta").trim()
    val staves = s.parts.count { it.group == p.group }
    return if (staves > 1) "$base · ${if (p.clef == 'F') "F" else "G"}" else base
}

@Composable
private fun EditPanel(state: AppState, s: Score) {
    val cur = state.currentNote()
    val part = s.parts.getOrNull(state.selP)
    val bars = s.parts.firstOrNull()?.measures?.size ?: 0
    val dur = durName(state.effDur)
    val desc = when {
        cur == null -> "Escrevendo em ${part?.let { partLabel(s, state.selP) } ?: ""} · $dur · toque nas teclas do piano (continua de onde parou)"
        cur.pitch == null -> "Compasso ${state.selM + 1} de $bars · ${part?.let { partLabel(s, state.selP) } ?: ""} · próxima nota: $dur · toque nas teclas"
        else -> "Nota selecionada: ${Names.pitch(cur.pitch!!)} · ${durName(cur.duration.toDouble() / s.divisions)} · compasso ${state.selM + 1} de $bars"
    }
    val onNote = cur?.pitch != null
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(desc, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (s.parts.size > 1) {
            Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Escrever em", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                s.parts.indices.forEach { i ->
                    FilterChip(selected = state.selP == i, onClick = { state.selP = i; val e = state.endPosition(i); state.select(i, e.first, e.second) }, label = { Text(partLabel(s, i), maxLines = 1) })
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DURS.forEach { (g, q) ->
                GlyphBtn(g, {
                    state.inputDur = q
                    if (onNote) state.edit { Edit.setDurationSmart(it, state.selP, state.selM, state.selN, if (state.inputDot && q > 0.25) q * 1.5 else q) }
                }, selected = state.inputDur == q)
            }
            GlyphBtn(0xE1E7, {
                state.inputDot = !state.inputDot
                if (onNote) state.edit { Edit.toggleDot(it, state.selP, state.selM, state.selN) }
            }, selected = state.inputDot, size = 16)
            GlyphBtn(0xE4E5, { state.writeRest() }, size = 16)
            Spacer(Modifier.width(8.dp))
            GlyphBtn(Engrave.SHARP, { state.edit { Edit.setAlter(it, state.selP, state.selM, state.selN, 1) } }, enabled = onNote)
            GlyphBtn(Engrave.FLAT, { state.edit { Edit.setAlter(it, state.selP, state.selM, state.selN, -1) } }, enabled = onNote)
            GlyphBtn(Engrave.NATURAL, { state.edit { Edit.setAlter(it, state.selP, state.selM, state.selN, 0) } }, enabled = onNote)
            Spacer(Modifier.width(8.dp))
            TextBtn("Ligadura", { state.edit { Edit.toggleTie(it, state.selP, state.selM, state.selN) } }, selected = cur?.tieStart == true, enabled = onNote)
            TextBtn("↑ ½ tom", { state.edit { Edit.shiftSemitones(it, state.selP, state.selM, state.selN, 1) } }, enabled = onNote)
            TextBtn("↓ ½ tom", { state.edit { Edit.shiftSemitones(it, state.selP, state.selM, state.selN, -1) } }, enabled = onNote)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextBtn("+ compasso", { state.edit { Edit.addMeasure(it) } })
            TextBtn("+ 4 compassos", { state.edit { Edit.addMeasures(it, 4) } })
            TextBtn("Inserir nota", { state.edit { st -> state.selN = Edit.insertAfter(st, state.selP, state.selM, state.selN) } }, enabled = cur != null)
            TextBtn("Apagar nota", { state.edit { Edit.delete(it, state.selP, state.selM, state.selN) } }, enabled = cur != null)
            TextBtn("− compasso ${state.selM + 1}", { state.edit { Edit.deleteMeasure(it, state.selM) } }, enabled = bars > 1)
            TextBtn("Tocar na pauta", { state.drawTool = !state.drawTool }, selected = state.drawTool)
        }
        var lyricDlg by remember { mutableStateOf(false) }
        var chordDlg by remember { mutableStateOf(false) }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextBtn("Duplicar compasso", { state.edit { Edit.duplicateMeasure(it, state.selM) } })
            TextBtn("+ vazio depois", { state.edit { Edit.insertMeasureAfter(it, state.selM) } })
            TextBtn("Copiar", { state.clipNotes = Edit.copyMeasure(s, state.selP, state.selM); state.toast("Compasso ${state.selM + 1} copiado") }, enabled = part != null)
            TextBtn("Colar", {
                val c = state.clipNotes
                if (c == null) state.toast("Nada copiado")
                else if (!Edit.pasteMeasure(s.copyDeep(), state.selP, state.selM, c)) state.toast("O compasso copiado tem outra duração")
                else state.edit { Edit.pasteMeasure(it, state.selP, state.selM, c) }
            }, enabled = state.clipNotes != null)
            TextBtn("Limpar compasso", { state.edit { Edit.clearMeasure(it, state.selP, state.selM) } })
            TextBtn("Oitava ↑", { state.edit { Edit.octaveMeasure(it, state.selP, state.selM, 1) } })
            TextBtn("Oitava ↓", { state.edit { Edit.octaveMeasure(it, state.selP, state.selM, -1) } })
            TextBtn("Letra…", { lyricDlg = true }, enabled = onNote)
            TextBtn("Acorde…", { chordDlg = true }, enabled = cur != null)
        }
        if (lyricDlg && cur != null) {
            var txt by remember(state.selP, state.selM, state.selN) { mutableStateOf(cur.lyric?.let { it + if (cur.syl == 'b' || cur.syl == 'm') "-" else "" } ?: "") }
            AlertDialog(
                onDismissRequest = { lyricDlg = false },
                title = { Text("Sílaba desta nota") },
                text = { Column { OutlinedTextField(value = txt, onValueChange = { txt = it }, singleLine = true, label = { Text("Sílaba") }); Text("Termine com “-” quando a palavra continua na próxima nota (ex.: a-). Vazio apaga.", style = MaterialTheme.typography.bodySmall) } },
                confirmButton = { Button(onClick = { state.edit { Edit.setLyric(it, state.selP, state.selM, state.selN, txt) }; state.moveSel(1); lyricDlg = false }) { Text("Aplicar e ir para a próxima") } },
                dismissButton = { TextButton(onClick = { lyricDlg = false }) { Text("Cancelar") } },
            )
        }
        if (chordDlg && cur != null) {
            val ms = part?.measures?.getOrNull(state.selM)?.notes ?: emptyList()
            var at = 0; for (i in 0 until state.selN) if (!ms[i].chord) at += ms[i].duration
            val existing = s.parts[0].measures.getOrNull(state.selM)?.harmony?.firstOrNull { it.at == at }
            var txt by remember(state.selP, state.selM, state.selN) { mutableStateOf(existing?.text() ?: "") }
            val parsed = Chords.parse(txt)
            AlertDialog(
                onDismissRequest = { chordDlg = false },
                title = { Text("Acorde no compasso ${state.selM + 1}") },
                text = { Column { OutlinedTextField(value = txt, onValueChange = { txt = it }, singleLine = true, label = { Text("Cifra (Am7, F♯m, B♭/D, C°…)") }, isError = txt.isNotBlank() && parsed == null); Text("Vale a partir desta nota. Vazio apaga o acorde daqui.", style = MaterialTheme.typography.bodySmall) } },
                confirmButton = { Button(enabled = txt.isBlank() || parsed != null, onClick = { state.edit { Edit.setChord(it, 0, state.selM, at, if (txt.isBlank()) null else parsed) }; chordDlg = false }) { Text("Aplicar") } },
                dismissButton = { TextButton(onClick = { chordDlg = false }) { Text("Cancelar") } },
            )
        }
        val issues = Validate.measureDurations(s)
        if (issues.isNotEmpty()) Text("Atenção: ${issues.size} compasso(s) com duração diferente de ${s.beats}/${s.beatType}. ${issues.first()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InstrumentsSheet(state: AppState, s: Score, onClose: () -> Unit) {
    val groups = s.parts.indices.filter { i -> s.parts.indexOfFirst { it.group == s.parts[i].group } == i }
    val bars = s.parts.firstOrNull()?.measures?.size ?: 0
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Instrumentos", style = MaterialTheme.typography.titleLarge)
            Text("${groups.size} na partitura · $bars compassos", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            groups.forEach { i ->
                val part = s.parts[i]
                var menu by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(part.name.substringBefore(" (pauta"), style = MaterialTheme.typography.titleSmall)
                        val staves = s.parts.count { it.group == part.group }
                        Text(if (staves > 1) "$staves pautas (G e F)" else "clave ${if (part.clef == 'F') "F" else "G"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Box {
                        TextBtn("Som: ${Timbre.of(part.inst).label}", { menu = true })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            Timbre.ALL.forEach { t -> DropdownMenuItem(text = { Text(t.label) }, onClick = { state.setSound(i, t.id); menu = false }) }
                        }
                    }
                    ToolBtn(Ic.TRASH, { state.removeInstrument(i) }, enabled = groups.size > 1)
                }
            }
            SectionTitle("Adicionar instrumento")
            Wrap {
                Instruments.CATALOG.forEach { inst ->
                    AssistChip(onClick = { state.addInstrument(inst); state.editMode = true; onClose() }, label = { Text(inst.name + if (inst.staves == 2) " (2 pautas)" else "") })
                }
            }
            Text(
                "O instrumento entra com pausas em todos os compassos. Depois é só ligar o lápis (modo de escrita), escolher o instrumento em \"Escrever em\" e tocar as teclas do piano. " +
                    "O som é sintetizado: aproxima o caráter de cada instrumento (violão e baixo decaem, órgão e cordas sustentam), mas não são gravações dos instrumentos reais. " +
                    "As notas ficam na altura real (sem transposição de instrumento).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

fun durName(q: Double): String = when (q) {
    4.0 -> "semibreve"; 2.0 -> "mínima"; 1.0 -> "semínima"; 0.5 -> "colcheia"; 0.25 -> "semicolcheia"
    6.0 -> "semibreve pontuada"; 3.0 -> "mínima pontuada"; 1.5 -> "semínima pontuada"; 0.75 -> "colcheia pontuada"
    else -> "$q tempos"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MixerSheet(state: AppState, s: Score, onClose: () -> Unit) {
    val measures = s.parts.firstOrNull()?.measures?.size ?: 1
    ModalBottomSheet(onDismissRequest = onClose, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Reprodução", style = MaterialTheme.typography.titleLarge)
            Column {
                Text("Qualidade do som", style = MaterialTheme.typography.titleSmall)
                Wrap {
                    FilterChip(selected = state.realSound, onClick = { state.applyRealSound(true); state.restartIfPlaying() }, label = { Text("Instrumentos reais") })
                    FilterChip(selected = !state.realSound, onClick = { state.applyRealSound(false); state.restartIfPlaying() }, label = { Text("Sintetizado") })
                }
                if (state.realSound && state.bankState != "pronto") Text(if (state.bankState == "carregando") "Carregando os instrumentos…" else "Instrumentos reais indisponíveis; usando o som sintetizado.", style = MaterialTheme.typography.bodySmall)
            }
            Column {
                Text("Andamento: ${state.tempo} bpm (original ${s.tempo}) · o tom não muda", style = MaterialTheme.typography.titleSmall)
                Slider(value = state.tempo.toFloat(), onValueChange = { state.tempo = it.toInt() }, valueRange = 10f..240f, onValueChangeFinished = { state.restartIfPlaying() })
                Wrap {
                    listOf(0.4 to "0,4×", 0.6 to "0,6×", 0.8 to "0,8×", 1.0 to "1×", 1.25 to "1,25×", 1.5 to "1,5×").forEach { (f, l) ->
                        FilterChip(selected = false, onClick = { state.tempo = (s.tempo * f).toInt().coerceIn(10, 240); state.restartIfPlaying() }, label = { Text(l) })
                    }
                }
            }
            Column {
                Text("Trecho", style = MaterialTheme.typography.titleSmall)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(checked = state.rangeFrom > 0, onCheckedChange = { on -> if (on) { state.rangeFrom = 1; state.rangeTo = measures } else { state.rangeFrom = 0; state.rangeTo = 0 } })
                    Text(if (state.rangeFrom > 0) "Só do compasso ${state.rangeFrom} ao ${state.rangeTo}" else "Música toda")
                }
                if (state.rangeFrom > 0) {
                    Stepper("Do compasso", "${state.rangeFrom}", { if (state.rangeFrom > 1) state.rangeFrom-- }, { if (state.rangeFrom < state.rangeTo) state.rangeFrom++ })
                    Stepper("Ao compasso", "${state.rangeTo}", { if (state.rangeTo > state.rangeFrom) state.rangeTo-- }, { if (state.rangeTo < measures) state.rangeTo++ })
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Switch(checked = state.loop, onCheckedChange = { state.loop = it })
                    Text("Repetir (loop)")
                }
            }
            val mixNow = state.mix
            val mixVer = state.mixRev
            if (mixNow != null && mixVer >= 0) {
                Column {
                    Text("Vozes e instrumentos", style = MaterialTheme.typography.titleSmall)
                    Text("Ouça só uma voz (Solo), silencie outra (Mudo) ou escolha o som de cada uma.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Estilo de som de todas", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    Wrap {
                        FilterChip(selected = mixNow.timbre.all { it == null }, onClick = { mixNow.setAllTimbre(null); state.mixChanged(); state.restartIfPlaying() }, label = { Text("Original") })
                        listOf("piano" to "Piano", "piano_soft" to "Piano suave", "voice" to "Coro (aah)", "voice_oo" to "Coro (ooh)", "strings" to "Cordas", "organ" to "Órgão", "pad" to "Pad", "flute" to "Flauta").forEach { (id, label) ->
                            FilterChip(selected = mixNow.timbre.all { it == id }, onClick = { mixNow.setAllTimbre(id); state.mixChanged(); state.restartIfPlaying() }, label = { Text(label) })
                        }
                    }
                    mixNow.tracks.forEachIndexed { i, t ->
                        var menu by remember { mutableStateOf(false) }
                        Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("${t.name}: ${(mixNow.volume[i] * 100).toInt()}%", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                FilterChip(selected = mixNow.mute[i], onClick = { mixNow.mute[i] = !mixNow.mute[i]; state.mixChanged(); state.restartIfPlaying() }, label = { Text("Mudo") })
                                FilterChip(selected = mixNow.solo[i], onClick = { mixNow.solo[i] = !mixNow.solo[i]; state.mixChanged(); state.restartIfPlaying() }, label = { Text("Solo") })
                                Box {
                                    TextBtn("Som: ${Timbre.of(mixNow.timbreId(i, s)).label}", { menu = true })
                                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("Original da partitura") }, onClick = { mixNow.timbre[i] = null; state.mixChanged(); menu = false; state.restartIfPlaying() })
                                        Timbre.ALL.forEach { tb -> DropdownMenuItem(text = { Text(tb.label) }, onClick = { mixNow.timbre[i] = tb.id; state.mixChanged(); menu = false; state.restartIfPlaying() }) }
                                    }
                                }
                            }
                            Slider(value = mixNow.volume[i].toFloat(), onValueChange = { mixNow.volume[i] = it.toDouble(); state.mixChanged() }, valueRange = 0f..1.5f, onValueChangeFinished = { state.restartIfPlaying() })
                        }
                    }
                }
            }
            Column {
                Text("Baixar o áudio", style = MaterialTheme.typography.titleSmall)
                Text("Gera o arquivo com o que você ouve aqui (andamento, repetições, dinâmicas, vozes escolhidas) para usar em apresentações.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Wrap {
                    Button(enabled = !state.exporting, onClick = { state.exportAudio("wav") }) { Text(if (state.exporting) "Gerando…" else "WAV") }
                    Button(enabled = !state.exporting, onClick = { state.exportAudio("m4a") }) { Text("M4A (menor)") }
                }
            }
        }
    }
}
