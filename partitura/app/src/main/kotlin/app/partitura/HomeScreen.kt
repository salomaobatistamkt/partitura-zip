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
                    Text("Partitura", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    Text("Leia, escreva e toque suas partituras.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ToolBtn(Ic.GEAR, { showSettings = true })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionCard(Ic.OPEN, "Abrir arquivo", "MusicXML, PDF ou foto", { state.openPicker() }, Modifier.weight(1f), strong = true)
                ActionCard(Ic.STUDIO, "Nova partitura", "Começar do zero", { showNew = true }, Modifier.weight(1f))
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
    var bars by remember { mutableIntStateOf(8) }
    var piano by remember { mutableStateOf(false) }
    val sigs = listOf(4 to 4, 3 to 4, 2 to 4, 6 to 8)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nova partitura") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Título") }, singleLine = true)
                Stepper("Tonalidade", Names.key(fifths) + " maior", { if (fifths > -6) fifths-- }, { if (fifths < 6) fifths++ })
                Wrap { sigs.forEachIndexed { i, (a, b) -> FilterChip(selected = ts == i, onClick = { ts = i }, label = { Text("$a/$b") }) } }
                Stepper("Compassos", "$bars", { if (bars > 1) bars-- }, { if (bars < 64) bars++ })
                Wrap {
                    FilterChip(selected = !piano, onClick = { piano = false }, label = { Text("Uma pauta") })
                    FilterChip(selected = piano, onClick = { piano = true }, label = { Text("Piano (Sol + Fá)") })
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val s = Edit.blank(title.ifBlank { "Sem título" }, fifths, sigs[ts].first, sigs[ts].second, bars)
                if (piano) {
                    val top = s.parts[0]
                    val low = Part("P1-2", "Piano (pauta 2)", top.measures.map { Measure(it.number, it.notes.toMutableList()) }.toMutableList(), 'F', top.group)
                    s.parts[0] = Part(top.id, "Piano (pauta 1)", top.measures, 'G', top.group)
                    s.parts += low
                }
                onCreate(s)
            }) { Text("Criar") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun SettingsDialog(state: AppState, onClose: () -> Unit) {
    var url by remember { mutableStateOf(state.omrUrl) }
    AlertDialog(
        onDismissRequest = { state.applyOmrUrl(url); onClose() },
        title = { Text("Ajustes") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Tema escuro", Modifier.weight(1f))
                    Switch(checked = state.dark, onCheckedChange = { state.applyDark(it) })
                }
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("Servidor de leitura de PDF/foto (OMR)") },
                    placeholder = { Text("https://seu-servidor/omr") }, singleLine = true,
                )
                Text(
                    "PDF e foto precisam de um servidor com Audiveris (reconhecimento óptico de música). O app envia o arquivo e recebe um MusicXML. " +
                        "A pasta omr-server do projeto traz o servidor pronto para Docker.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Ainda não existe", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Importar MIDI, exportar MP3, escrita à mão com reconhecimento, assistente de IA e instrumentos reais (o som é um piano sintetizado).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("Fonte de notação: Bravura (SIL Open Font License).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { state.applyOmrUrl(url); onClose() }) { Text("Pronto") } },
    )
}
