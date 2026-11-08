package app.partitura

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.partitura.core.*

@Composable
private fun ActionCard(ic: Ic, title: String, sub: String, onClick: () -> Unit, modifier: Modifier = Modifier, strong: Boolean = false) {
    val cs = MaterialTheme.colorScheme
    val bg = if (strong) cs.primary else cs.surface
    val fg = if (strong) cs.onPrimary else cs.onSurface
    Column(
        modifier.clip(MaterialTheme.shapes.large).background(bg)
            .border(0.5.dp, cs.outline, MaterialTheme.shapes.large).clickable(onClick = onClick).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon24(ic, fg, 26.dp)
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium, color = fg, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = 0.75f))
        }
    }
}

@Composable
fun HomeScreen(state: AppState) {
    var showNew by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Compasso", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Leia, escreva e toque suas partituras.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ToolBtn(Ic.GEAR, { showSettings = true })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionCard(Ic.OPEN, "Abrir arquivo", "MusicXML, PDF ou foto", { state.openPicker() }, Modifier.weight(1f), strong = true)
                ActionCard(Ic.STUDIO, "Nova partitura", "Começar do zero", { showNew = true }, Modifier.weight(1f))
            }
            if (state.score == null && state.autosaveInfo() != null) {
                ActionCard(Ic.EDIT, "Continuar de onde parei", "Retoma a última partitura que você estava escrevendo", { state.resumeAutosave() }, Modifier.fillMaxWidth())
            }
            val list = state.projects()
            if (list.isNotEmpty()) {
                SectionTitle("Recentes")
                list.take(4).forEach { f ->
                    Row(
                        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.surface)
                            .clickable { state.openProject(f) }.padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(f.nameWithoutExtension, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        ToolBtn(Ic.TRASH, { state.deleteProject(f) })
                    }
                }
            }
            SectionTitle("Exemplos")
            Wrap {
                Samples.all().forEach { s -> AssistChip(onClick = { state.open(s); state.goStudio() }, label = { Text(s.title.substringBefore(" (")) }) }
            }
        }
    }
    if (showNew) NewDialog(onDismiss = { showNew = false }) { s -> state.open(s); showNew = false; state.goStudio() }
    if (showSettings) SettingsDialog(state) { showSettings = false }
}

@Composable
private fun NewDialog(onDismiss: () -> Unit, onCreate: (Score) -> Unit) {
    var title by remember { mutableStateOf("Nova partitura") }
    var fifths by remember { mutableIntStateOf(0) }
    var beats by remember { mutableIntStateOf(4) }
    var beatType by remember { mutableIntStateOf(4) }
    var barsText by remember { mutableStateOf("32") }
    var tempo by remember { mutableIntStateOf(100) }
    val chosen = remember { mutableStateListOf("piano") }
    val common = listOf(4 to 4, 3 to 4, 2 to 4, 2 to 2, 3 to 2, 6 to 8, 9 to 8, 12 to 8, 5 to 4, 6 to 4, 7 to 4, 3 to 8, 5 to 8, 7 to 8, 1 to 4)
    val bars = barsText.toIntOrNull()
    val barsOk = bars != null && bars in 1..999
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova partitura") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Título") }, singleLine = true)

                SectionTitle("Conjuntos prontos")
                Wrap { Instruments.PRESETS.forEach { pr -> AssistChip(onClick = { chosen.clear(); chosen.addAll(pr.ids) }, label = { Text(pr.name) }) } }

                SectionTitle("Instrumentos (toque para acrescentar; pode repetir)")
                Wrap { Instruments.CATALOG.forEach { ins -> FilterChip(selected = chosen.contains(ins.id), onClick = { chosen.add(ins.id) }, label = { Text(ins.name + if (ins.staves == 2) " (2 pautas)" else "") }) } }
                if (chosen.isEmpty()) Text("Escolha pelo menos um instrumento.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                else {
                    Text("Na partitura (de cima para baixo) — toque para tirar:", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Wrap { chosen.toList().forEachIndexed { i, id -> InputChip(selected = true, onClick = { chosen.removeAt(i) }, label = { Text("${i + 1}. " + (Instruments.byId(id)?.name ?: id)) }, trailingIcon = { Text("✕") }) } }
                }

                Stepper("Tonalidade", Names.key(fifths) + " maior", { if (fifths > -7) fifths-- }, { if (fifths < 7) fifths++ })

                SectionTitle("Fórmula de compasso: $beats/$beatType")
                Wrap { common.forEach { (a, b) -> FilterChip(selected = beats == a && beatType == b, onClick = { beats = a; beatType = b }, label = { Text("$a/$b") }) } }
                Stepper("Tempos por compasso", "$beats", { if (beats > 1) beats-- }, { if (beats < 32) beats++ })
                Wrap {
                    Text("Valor do tempo:", modifier = Modifier.padding(end = 4.dp))
                    listOf(2, 4, 8, 16).forEach { bt -> FilterChip(selected = beatType == bt, onClick = { beatType = bt }, label = { Text("1/$bt") }) }
                }

                Stepper("Andamento (♩ por minuto)", "$tempo", { if (tempo > 30) tempo -= 5 }, { if (tempo < 240) tempo += 5 })
                OutlinedTextField(
                    value = barsText, onValueChange = { v -> barsText = v.filter { it.isDigit() }.take(3) },
                    label = { Text("Compassos") }, singleLine = true, isError = !barsOk,
                    supportingText = { Text(if (barsOk) "Mais compassos podem ser acrescentados enquanto você escreve." else "Digite um número de 1 a 999.") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            Button(enabled = barsOk && chosen.isNotEmpty(), onClick = {
                val insts = chosen.mapNotNull { Instruments.byId(it) }
                onCreate(Edit.blankMulti(title.ifBlank { "Sem título" }, fifths, beats, beatType, bars!!, insts, tempo))
            }) { Text("Criar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun SettingsDialog(state: AppState, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Ajustes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tema escuro", Modifier.weight(1f))
                    Switch(checked = state.dark, onCheckedChange = { state.applyDark(it) })
                }
                Column {
                    Text("Leitura de PDF e foto", style = MaterialTheme.typography.titleSmall)
                    Text(
                        if (state.serverUrl.isBlank()) "Servidor ainda não configurado." else "Servidor: ${state.serverUrl}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { onClose(); state.goServer() }, contentPadding = PaddingValues(0.dp)) { Text("Abrir tela Servidor") }
                }
                Text("Ainda não existe", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Importar MIDI, exportar MP3, escrita à mão com reconhecimento, assistente de IA e gravações de instrumentos reais (o som é sintetizado).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Fonte de notação: Bravura (SIL Open Font License).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = onClose) { Text("Pronto") } },
    )
}
