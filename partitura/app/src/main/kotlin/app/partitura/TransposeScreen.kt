package app.partitura

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.partitura.core.*

@Composable
fun TransposeScreen(state: AppState) {
    val s = state.score
    if (s == null) { Empty("Nada para transpor", "Abra ou crie uma partitura primeiro.", "Abrir arquivo") { state.openPicker() }; return }
    val rev = state.rev
    var semis by remember(rev) { mutableIntStateOf(0) }
    val preview = remember(rev, semis) { Transpose.bySemitones(s, semis) }
    val curClass = Math.floorMod(7 * s.fifths, 12)
    val p = state.player
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Transpor", style = MaterialTheme.typography.headlineMedium)

        Panel {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("de", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Names.key(s.fifths)} maior", style = MaterialTheme.typography.titleLarge)
                    Text("${Names.minorKey(s.fifths)} menor", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon24(Ic.NEXT, MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f)) {
                    Text("para", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${Names.key(preview.fifths)} maior", style = MaterialTheme.typography.titleLarge, color = if (semis == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary)
                    Text("${Names.minorKey(preview.fifths)} menor", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Stepper("Semitons", (if (semis > 0) "+" else "") + semis, { if (semis > -12) semis-- }, { if (semis < 12) semis++ })
        }

        SectionTitle("Ir direto para a tonalidade")
        Wrap {
            (-6..6).sortedBy { Math.floorMod(7 * it, 12) }.forEach { f ->
                val diff = Math.floorMod(Math.floorMod(7 * f, 12) - curClass + 6, 12) - 6
                FilterChip(selected = semis == diff && diff != 0, onClick = { semis = diff }, label = { Text(Names.key(f)) })
            }
        }
        SectionTitle("Instrumentos transpositores (altura escrita)")
        Wrap {
            listOf("Si♭ (clarinete, trompete)" to 2, "Mi♭ (sax alto)" to 9, "Fá (trompa)" to 7).forEach { (n, k) ->
                FilterChip(selected = semis == k, onClick = { semis = k }, label = { Text(n) })
            }
        }

        SectionTitle("Prévia")
        StaticScore(preview, rev * 100 + semis + 50, maxHeight = 320.dp, q = { if (p.active) p.posSec * p.usedTempo / 60.0 else null })

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(
                onClick = { state.replace(Transpose.bySemitones(s, semis)); state.toast("Transposta ${if (semis > 0) "+" else ""}$semis semitons. Dá para desfazer no Estúdio."); semis = 0 },
                enabled = semis != 0,
            ) { Text("Aplicar à partitura") }
            OutlinedButton(onClick = {
                if (p.active) p.stop() else p.start(preview, state.tempo, state.volumes.map { it.toDouble() }, 0.0, Double.MAX_VALUE, false)
            }) { Text(if (p.active) "Parar" else "Ouvir") }
            if (semis != 0) TextButton(onClick = { semis = 0 }) { Text("Zerar") }
        }
        Text("A grafia segue a nova armadura (bemóis ou sustenidos). Instrumentos transpositores: só a altura escrita muda, a oitava não é ajustada.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
