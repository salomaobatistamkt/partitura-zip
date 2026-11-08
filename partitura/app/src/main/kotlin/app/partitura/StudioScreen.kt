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
            ToolBtn(Ic.MIXER, { showMixer = true })
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).onSizeChanged { viewport = it.height }) {
            val spPx = spDp * density
            val widthPx = constraints.maxWidth.toFloat()
            val scene = remember(s, rev, widthPx, spPx) { Engrave.scene(s, widthPx / spPx) }
            val selId = if (!state.hasSel) -1 else scene.notes.indexOfFirst { it.part == state.selP && it.m == state.selM && it.idx == state.selN }
            val activeIds by remember(scene) { derivedStateOf { if (p.active) scene.soundingAt(p.posSec * p.usedTempo / 60.0) else emptyList() } }
            val activeSet = remember(activeIds) { activeIds.toSet() }
            val activeMidi = remember(activeIds, scene) { activeIds.map { scene.notes[it].midi }.toSet() }
            val curSystem by remember(scene) { derivedStateOf { if (p.active) (scene.cursor(p.posSec * p.usedTempo / 60.0)?.first ?: -1) else -1 } }

            LaunchedEffect(curSystem, scene) {
                if (curSystem >= 0 && curSystem < scene.systems.size) {
                    val sys = scene.systems[curSystem]
                    val top = sys.top * spPx; val bottom = sys.bottom * spPx
                    if (top < scroll.value || bottom > scroll.value + viewport) scroll.animateScrollTo((top - 12 * density).toInt().coerceAtLeast(0))
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
                    cursor = { if (p.active) scene.cursor(p.posSec * p.usedTempo / 60.0) else null },
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
                    Text("Dó${kbFirst / 12 - 1} – Dó${kbLast / 12 - 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    ToolBtn(Ic.NEXT, { kbFirst = (kbFirst + 12).coerceAtMost(96 - 12 * octs) })
                    Spacer(Modifier.weight(1f))
                    Text(if (state.editMode) "Teclas escrevem na nota selecionada" else "Toque para ouvir", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PianoKeyboard(kbFirst, kbLast, pianoActive(state, p), marks, { m -> if (state.editMode) state.writeNote(m) else p.key(m) }, height = if (wide) 110.dp else 84.dp)
            }
        }
    }
    if (showMixer) MixerSheet(state, s) { showMixer = false }
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

@Composable
private fun EditPanel(state: AppState, s: Score) {
    val cur = state.currentNote()
    val part = s.parts.getOrNull(state.selP)
    val desc = if (cur == null) "Toque numa nota ou pausa da pauta para editar." else {
        val what = cur.pitch?.let { Names.pitch(it) } ?: "pausa"
        "${part?.name ?: ""} · compasso ${state.selM + 1} · $what · ${durName(cur.duration.toDouble() / s.divisions)}"
    }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 10.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(desc, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            DURS.forEach { (g, q) ->
                GlyphBtn(g, { state.edit { Edit.setDurationSmart(it, state.selP, state.selM, state.selN, q) } },
                    selected = cur != null && cur.duration == (q * s.divisions).toInt(), enabled = cur != null)
            }
            GlyphBtn(0xE1E7, { state.edit { Edit.toggleDot(it, state.selP, state.selM, state.selN) } }, enabled = cur != null, size = 16)
            Spacer(Modifier.width(8.dp))
            GlyphBtn(Engrave.SHARP, { state.edit { Edit.setAlter(it, state.selP, state.selM, state.selN, 1) } }, enabled = cur?.pitch != null)
            GlyphBtn(Engrave.FLAT, { state.edit { Edit.setAlter(it, state.selP, state.selM, state.selN, -1) } }, enabled = cur?.pitch != null)
            GlyphBtn(Engrave.NATURAL, { state.edit { Edit.setAlter(it, state.selP, state.selM, state.selN, 0) } }, enabled = cur?.pitch != null)
            Spacer(Modifier.width(8.dp))
            GlyphBtn(0xE4E5, { state.edit { Edit.toggleRest(it, state.selP, state.selM, state.selN) } }, enabled = cur != null, size = 16)
            TextBtn("Ligadura", { state.edit { Edit.toggleTie(it, state.selP, state.selM, state.selN) } }, selected = cur?.tieStart == true, enabled = cur?.pitch != null)
            TextBtn("↑ ½ tom", { state.edit { Edit.shiftSemitones(it, state.selP, state.selM, state.selN, 1) } }, enabled = cur?.pitch != null)
            TextBtn("↓ ½ tom", { state.edit { Edit.shiftSemitones(it, state.selP, state.selM, state.selN, -1) } }, enabled = cur?.pitch != null)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextBtn("Escrever tocando na pauta", { state.drawTool = !state.drawTool }, selected = state.drawTool)
            TextBtn("Inserir nota", { state.edit { st -> state.selN = Edit.insertAfter(st, state.selP, state.selM, state.selN) } }, enabled = cur != null)
            TextBtn("Apagar nota", { state.edit { Edit.delete(it, state.selP, state.selM, state.selN) } }, enabled = cur != null)
            TextBtn("+ compasso", { state.edit { Edit.addMeasure(it) } })
            TextBtn("− compasso ${state.selM + 1}", { state.edit { Edit.deleteMeasure(it, state.selM) } }, enabled = s.parts.firstOrNull()?.measures?.let { it.size > 1 } == true)
        }
        val issues = Validate.measureDurations(s)
        if (issues.isNotEmpty()) Text("Atenção: ${issues.size} compasso(s) com duração diferente de ${s.beats}/${s.beatType}. ${issues.first()}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, maxLines = 2)
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
            Column {
                Text("Volume por instrumento", style = MaterialTheme.typography.titleSmall)
                s.parts.forEachIndexed { i, part ->
                    val v = state.volumes.getOrElse(i) { 1f }
                    Text("${part.name}: ${(v * 100).toInt()}%", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Slider(value = v, onValueChange = { if (i < state.volumes.size) state.volumes[i] = it }, valueRange = 0f..1.5f, onValueChangeFinished = { state.restartIfPlaying() })
                }
            }
        }
    }
}
