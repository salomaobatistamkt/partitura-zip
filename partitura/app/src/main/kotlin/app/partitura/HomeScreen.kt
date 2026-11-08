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
    var ts by remember { mutableIntStateOf(0) }
    var bars by remember { mutableIntStateOf(32) }
    var tempo by remember { mutableIntStateOf(100) }
    var instIdx by remember { mutableIntStateOf(0) }
    val sigs = listOf(4 to 4, 3 to 4, 2 to 4, 6 to 8, 12 to 8, 2 to 2)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova partitura") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Título") }, singleLine = true)
                Text("Instrumento", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Wrap { Instruments.CATALOG.forEachIndexed { i, ins -> FilterChip(selected = instIdx == i, onClick = { instIdx = i }, label = { Text(ins.name + if (ins.staves == 2) " (2 pautas)" else "") }) } }
                Text("Dá para adicionar mais instrumentos depois, no Estúdio.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Stepper("Tonalidade", Names.key(fifths) + " maior", { if (fifths > -6) fifths-- }, { if (fifths < 6) fifths++ })
                Wrap { sigs.forEachIndexed { i, (a, b) -> FilterChip(selected = ts == i, onClick = { ts = i }, label = { Text("$a/$b") }) } }
                Stepper("Andamento (♩ por minuto)", "$tempo", { if (tempo > 40) tempo -= 5 }, { if (tempo < 200) tempo += 5 })
                Stepper("Compassos", "$bars", { if (bars > 4) bars -= 4 }, { if (bars < 400) bars += 4 })
                Text("Compassos a mais podem ser acrescentados enquanto você escreve.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                onCreate(Edit.blankFor(title.ifBlank { "Sem título" }, fifths, sigs[ts].first, sigs[ts].second, bars, Instruments.CATALOG[instIdx], tempo))
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
