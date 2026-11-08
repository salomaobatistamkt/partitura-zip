package app.partitura

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.partitura.core.*

private val TOCAR_QUARTERS = listOf("Semibreve" to 4.0, "Mínima" to 2.0, "Semínima" to 1.0, "Colcheia" to 0.5, "Semicolcheia" to 0.25)
private val NOTE_KEYS = listOf("Dó" to 0, "Dó♯" to 1, "Ré" to 2, "Ré♯" to 3, "Mi" to 4, "Fá" to 5, "Fá♯" to 6, "Sol" to 7, "Sol♯" to 8, "Lá" to 9, "Lá♯" to 10, "Si" to 11)

@Composable
fun HomeScreen(state: AppState, goEditor: () -> Unit) {
    var showNew by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScrollCompat().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Partitura", style = MaterialTheme.typography.headlineLarge)
        Text("Leia, escreva, toque e transponha partituras. Tudo funciona offline.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Wrap {
            Button(onClick = { state.openPicker() }) { Text("Abrir arquivo MusicXML") }
            FilledTonalButton(onClick = { showNew = true }) { Text("Novo projeto") }
        }
        SectionTitle("Exemplos para testar")
        Wrap {
            Samples.all().forEach { s -> OutlinedButton(onClick = { state.open(s); goEditor() }) { Text(s.title) } }
        }
        SectionTitle("Projetos salvos")
        val list = state.projects()
        if (list.isEmpty()) Text("Nenhum projeto salvo ainda. Use Salvar no editor.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        list.forEach { f ->
            Panel {
                Text(f.nameWithoutExtension, style = MaterialTheme.typography.titleMedium)
                Wrap {
                    Button(onClick = { state.openProject(f); goEditor() }) { Text("Abrir") }
                    OutlinedButton(onClick = { state.deleteProject(f) }) { Text("Excluir") }
                }
            }
        }
        SectionTitle("Aparência")
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tema escuro")
            Switch(checked = state.dark, onCheckedChange = { state.dark = it })
        }
        SectionTitle("O que ainda não existe nesta versão")
        Text(
            "Leitura de partitura por foto, PDF ou câmera (OMR), escrita com caneta, assistente de IA, exportar MP3 e importar MIDI. " +
                "O som é um sintetizador simples, sem biblioteca de instrumentos reais.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (showNew) NewProjectDialog(onDismiss = { showNew = false }) { s -> state.open(s); showNew = false; goEditor() }
}

@Composable
fun NewProjectDialog(onDismiss: () -> Unit, onCreate: (Score) -> Unit) {
    var title by remember { mutableStateOf("Novo projeto") }
    var fifths by remember { mutableIntStateOf(0) }
    var ts by remember { mutableIntStateOf(0) }
    var bars by remember { mutableIntStateOf(8) }
    val sigs = listOf(4 to 4, 3 to 4, 2 to 4, 6 to 8)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Novo projeto") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Título") }, singleLine = true)
                Stepper("Tonalidade", Names.key(fifths) + " maior", { if (fifths > -6) fifths-- }, { if (fifths < 6) fifths++ })
                Wrap { sigs.forEachIndexed { i, (a, b) -> FilterChip(selected = ts == i, onClick = { ts = i }, label = { Text("$a/$b") }) } }
                Stepper("Compassos", "$bars", { if (bars > 1) bars-- }, { if (bars < 64) bars++ })
            }
        },
        confirmButton = { Button(onClick = { onCreate(Edit.blank(title.ifBlank { "Sem título" }, fifths, sigs[ts].first, sigs[ts].second, bars)) }) { Text("Criar") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
fun EditorScreen(state: AppState, wide: Boolean, goHome: () -> Unit) {
    val s = state.score
    if (s == null) { Column(Modifier.padding(20.dp)) { Empty("Nenhuma partitura aberta", "Abra um arquivo, um exemplo ou crie um projeto novo na tela inicial.", state, goHome) }; return }
    val rev = state.rev
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScrollCompat().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(s.title, style = MaterialTheme.typography.titleLarge)
                StaffView(state, s, editable = true)
            }
            Column(Modifier.width(380.dp).fillMaxHeight().verticalScrollCompat().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Tools(state, s) }
        }
    } else {
        Column(Modifier.fillMaxSize().verticalScrollCompat().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleLarge)
            StaffView(state, s, editable = true)
            Tools(state, s)
        }
    }
}

@Composable
private fun Tools(state: AppState, s: Score) {
    val rev = state.rev
    var octave by remember { mutableIntStateOf(4) }
    var saveName by remember { mutableStateOf(false) }
    val cur = state.currentNote()
    Panel {
        val desc = if (cur == null) "Toque numa nota da pauta para selecionar." else {
            val part = s.parts[state.selP]
            val what = cur.pitch?.let { Names.pitch(it) } ?: "pausa"
            "${part.name} · compasso ${state.selM + 1} · $what · ${durName(cur.duration.toDouble() / s.divisions)}"
        }
        Text("Selecionada", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(desc)
        Wrap {
            OutlinedButton(onClick = { state.moveSel(-1) }) { Text("◀ Anterior") }
            OutlinedButton(onClick = { state.moveSel(1) }) { Text("Próxima ▶") }
            OutlinedButton(onClick = { cur?.pitch?.let { state.player.preview(listOf(it)) } }) { Text("Ouvir nota") }
        }
        Wrap {
            OutlinedButton(onClick = { state.undo() }, enabled = state.canUndo) { Text("Desfazer") }
            OutlinedButton(onClick = { state.redo() }, enabled = state.canRedo) { Text("Refazer") }
            Button(onClick = { if (state.projectName != null) state.save(state.projectName!!) else saveName = true }) { Text("Salvar") }
        }
    }
    Panel {
        Text("Teclado", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Stepper("Oitava", "$octave", { if (octave > 1) octave-- }, { if (octave < 7) octave++ })
        Wrap { NOTE_KEYS.forEach { (n, off) -> FilledTonalButton(onClick = { state.writeNote((octave + 1) * 12 + off) }, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(n) } } }
        Text("Cada tecla grava a altura na nota selecionada e avança para a próxima.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Panel {
        Text("Nota", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Wrap {
            OutlinedButton(onClick = { state.edit { Edit.shiftSemitones(it, state.selP, state.selM, state.selN, 1) } }) { Text("+ semitom") }
            OutlinedButton(onClick = { state.edit { Edit.shiftSemitones(it, state.selP, state.selM, state.selN, -1) } }) { Text("− semitom") }
            OutlinedButton(onClick = { state.edit { Edit.shiftDiatonic(it, state.selP, state.selM, state.selN, 1) } }) { Text("▲ grau") }
            OutlinedButton(onClick = { state.edit { Edit.shiftDiatonic(it, state.selP, state.selM, state.selN, -1) } }) { Text("▼ grau") }
        }
        Wrap {
            OutlinedButton(onClick = { state.edit { Edit.setAlter(it, state.selP, state.selM, state.selN, 1) } }) { Text("♯") }
            OutlinedButton(onClick = { state.edit { Edit.setAlter(it, state.selP, state.selM, state.selN, -1) } }) { Text("♭") }
            OutlinedButton(onClick = { state.edit { Edit.setAlter(it, state.selP, state.selM, state.selN, 0) } }) { Text("♮") }
            OutlinedButton(onClick = { state.edit { Edit.toggleRest(it, state.selP, state.selM, state.selN) } }) { Text("Nota / pausa") }
            OutlinedButton(onClick = { state.edit { Edit.toggleTie(it, state.selP, state.selM, state.selN) } }) { Text("Ligadura") }
        }
        Text("Duração", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Wrap {
            TOCAR_QUARTERS.forEach { (n, q) ->
                FilterChip(selected = cur != null && cur.duration == (q * s.divisions).toInt(), onClick = { state.edit { Edit.setDuration(it, state.selP, state.selM, state.selN, q) } }, label = { Text(n) })
            }
            FilterChip(selected = false, onClick = { state.edit { Edit.toggleDot(it, state.selP, state.selM, state.selN) } }, label = { Text("Ponto") })
        }
        Wrap {
            Button(onClick = { state.edit { st -> val i = Edit.insertAfter(st, state.selP, state.selM, state.selN); state.selN = i } }) { Text("Inserir nota") }
            OutlinedButton(onClick = { state.edit { Edit.delete(it, state.selP, state.selM, state.selN) } }) { Text("Apagar nota") }
        }
    }
    Panel {
        Text("Compassos", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Wrap {
            OutlinedButton(onClick = { state.edit { Edit.addMeasure(it) } }) { Text("Adicionar no fim") }
            OutlinedButton(onClick = { state.edit { Edit.deleteMeasure(it, state.selM) } }) { Text("Remover compasso ${state.selM + 1}") }
            Button(onClick = { state.player.play(s, state.tempo, state.volumes.map { it.toDouble() }, false, state.selM, s.parts[0].measures.size - 1) }) { Text("Ouvir daqui") }
            OutlinedButton(onClick = { state.player.stop() }) { Text("Parar") }
        }
        val issues = Validate.measureDurations(s)
        if (issues.isNotEmpty()) {
            Text("Atenção: ${issues.size} compasso(s) com duração diferente de ${s.beats}/${s.beatType}.", color = MaterialTheme.colorScheme.error)
            issues.take(3).forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
        } else Text("Todos os compassos estão completos.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (saveName) {
        var name by remember { mutableStateOf(s.title) }
        AlertDialog(
            onDismissRequest = { saveName = false },
            title = { Text("Salvar projeto") },
            text = { OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") }, singleLine = true) },
            confirmButton = { Button(onClick = { state.save(name); saveName = false }) { Text("Salvar") } },
            dismissButton = { TextButton(onClick = { saveName = false }) { Text("Cancelar") } },
        )
    }
}

fun durName(q: Double): String = when (q) {
    4.0 -> "semibreve"; 2.0 -> "mínima"; 1.0 -> "semínima"; 0.5 -> "colcheia"; 0.25 -> "semicolcheia"
    6.0 -> "semibreve pontuada"; 3.0 -> "mínima pontuada"; 1.5 -> "semínima pontuada"; 0.75 -> "colcheia pontuada"
    else -> "$q tempos"
}

@Composable
fun PlaybackScreen(state: AppState, goHome: () -> Unit) {
    val s = state.score
    if (s == null) { Column(Modifier.padding(20.dp)) { Empty("Nada para tocar", "Abra um arquivo ou um exemplo primeiro.", state, goHome) }; return }
    val p = state.player
    val measures = s.parts[0].measures.size
    var loop by remember { mutableStateOf(false) }
    var mFrom by remember { mutableIntStateOf(1) }
    var mTo by remember { mutableIntStateOf(measures) }
    if (mTo > measures) mTo = measures
    if (mFrom > mTo) mFrom = mTo
    Column(Modifier.fillMaxSize().verticalScrollCompat().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(s.title, style = MaterialTheme.typography.titleLarge)
        Panel {
            Wrap {
                Button(onClick = { p.play(s, state.tempo, state.volumes.map { it.toDouble() }, loop, mFrom - 1, mTo - 1) }, enabled = !p.busy) { Text(if (p.busy) "Preparando…" else "Tocar") }
                OutlinedButton(onClick = { p.stop() }) { Text("Parar") }
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = loop, onCheckedChange = { loop = it }); Spacer(Modifier.width(8.dp)); Text("Repetir (loop)") }
            }
            LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth())
            Text(if (p.playing) "Tocando" else "Parado", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel {
            Text("Andamento: ${state.tempo} bpm (original ${s.tempo}) — o tom não muda", style = MaterialTheme.typography.titleSmall)
            Slider(value = state.tempo.toFloat(), onValueChange = { state.tempo = it.toInt() }, valueRange = 10f..240f)
            Wrap {
                listOf(0.2 to "0,2x", 0.4 to "0,4x", 0.7 to "0,7x", 1.0 to "1x", 1.25 to "1,25x", 1.5 to "1,5x").forEach { (f, l) ->
                    FilterChip(selected = false, onClick = { state.tempo = (s.tempo * f).toInt().coerceIn(10, 240) }, label = { Text(l) })
                }
            }
            Text("A mudança vale na próxima vez que tocar.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Panel {
            Text("Trecho", style = MaterialTheme.typography.titleSmall)
            Stepper("Do compasso", "$mFrom", { if (mFrom > 1) mFrom-- }, { if (mFrom < mTo) mFrom++ })
            Stepper("Ao compasso", "$mTo", { if (mTo > mFrom) mTo-- }, { if (mTo < measures) mTo++ })
        }
        Panel {
            Text("Volume por instrumento", style = MaterialTheme.typography.titleSmall)
            s.parts.forEachIndexed { i, part ->
                val v = state.volumes.getOrElse(i) { 1f }
                Text("${part.name}: ${(v * 100).toInt()}%")
                Slider(value = v, onValueChange = { if (i < state.volumes.size) state.volumes[i] = it }, valueRange = 0f..1.5f)
            }
        }
        StaffView(state, s, editable = false)
    }
}
