package app.partitura

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.partitura.core.*

private val DENSITY = listOf("Calma (notas longas)", "Média", "Movida (colcheias)")

/** Aba Criatividade: letra → melodia → arranjo (piano e vozes) → acordes. Tudo vem de regras (core/Compose.kt e Arrange.kt), sem rede. */
@Composable
fun CreativeScreen(state: AppState) {
    var lyrics by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("Minha música") }
    var fifths by rememberSaveable { mutableIntStateOf(0) }
    var minor by rememberSaveable { mutableStateOf(false) }
    var meterIdx by rememberSaveable { mutableIntStateOf(2) }
    var tempo by rememberSaveable { mutableIntStateOf(96) }
    var density by rememberSaveable { mutableIntStateOf(1) }
    var chordsPerBar by rememberSaveable { mutableIntStateOf(1) }
    var sevenths by rememberSaveable { mutableStateOf(false) }
    var pickup by rememberSaveable { mutableStateOf(true) }
    var seed by rememberSaveable { mutableIntStateOf(1) }
    var piano by rememberSaveable { mutableStateOf("") }
    var voices by rememberSaveable { mutableStateOf("") }
    var voiceLyrics by rememberSaveable { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var base by remember { mutableStateOf<Score?>(null) }
    var ver by remember { mutableIntStateOf(0) }
    var chordEdit by remember { mutableStateOf<Pair<Int, Harmony>?>(null) }
    val meter = Conducting.METERS[meterIdx]
    val p = state.player

    fun generate() {
        error = null
        try {
            val r = Compose.compose(lyrics, CreativeSettings(title = title.ifBlank { "Minha música" }, fifths = fifths, minor = minor, beats = meter.first, beatType = meter.second,
                tempo = tempo, seed = seed.toLong(), density = density, chordsPerBar = chordsPerBar, sevenths = sevenths, pickup = pickup))
            base = r.score; ver++
        } catch (e: Exception) { error = e.message ?: "Não consegui montar essa letra."; base = null }
    }

    val arranged: Score? = remember(base, ver, piano, voices, voiceLyrics) {
        val b = base ?: return@remember null
        try {
            var s: Score = b
            if (voices.isNotEmpty()) s = Arrange.addVoices(s, voices, voiceLyrics)
            if (piano.isNotEmpty()) s = Arrange.addPiano(s, piano)
            s
        } catch (e: Exception) { b }
    }

    val tonic = if (minor) Names.minorKey(fifths) + " menor" else Names.key(fifths) + " maior"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Criatividade", style = MaterialTheme.typography.headlineMedium)
        Text("Cole uma letra, escolha tom e compasso: o app separa as sílabas, cria melodia e acordes e escreve tudo na partitura. As regras são fixas (sílaba forte em tempo forte, cadência no fim de cada verso); mude a variação para ouvir outra versão.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Panel {
            Text("1 · Letra e música", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = lyrics, onValueChange = { lyrics = it }, label = { Text("Letra (um verso por linha; linha em branco separa estrofes)") }, minLines = 5, maxLines = 12, modifier = Modifier.fillMaxWidth())
            SectionTitle("Tonalidade: $tonic")
            Stepper("Armadura (bemóis/sustenidos)", if (fifths > 0) "$fifths♯" else if (fifths < 0) "${-fifths}♭" else "0", { if (fifths > -7) fifths-- }, { if (fifths < 7) fifths++ })
            Wrap {
                FilterChip(selected = !minor, onClick = { minor = false }, label = { Text("Maior") })
                FilterChip(selected = minor, onClick = { minor = true }, label = { Text("Menor") })
            }
            SectionTitle("Compasso: ${meter.first}/${meter.second}")
            Wrap { Conducting.METERS.forEachIndexed { i, m -> FilterChip(selected = meterIdx == i, onClick = { meterIdx = i }, label = { Text("${m.first}/${m.second}") }) } }
            Stepper("Andamento (♩ por minuto)", "$tempo", { if (tempo > 40) tempo -= 4 }, { if (tempo < 200) tempo += 4 })
            SectionTitle("Ritmo da melodia")
            Wrap { DENSITY.forEachIndexed { i, n -> FilterChip(selected = density == i, onClick = { density = i }, label = { Text(n) }) } }
            Wrap {
                FilterChip(selected = pickup, onClick = { pickup = !pickup }, label = { Text("Começar em anacruse") })
            }
            Stepper("Variação", "$seed", { if (seed > 1) { seed--; if (base != null) generate() } }, { seed++; if (base != null) generate() })
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = { generate() }, enabled = lyrics.isNotBlank()) { Text(if (base == null) "Gerar melodia e acordes" else "Gerar de novo") }
        }

        val sc = base
        if (sc != null && arranged != null) {
            Panel {
                Text("Prévia", style = MaterialTheme.typography.titleMedium)
                StaticScore(arranged, ver * 1000 + piano.hashCode() + voices.hashCode() + (if (voiceLyrics) 1 else 0) + sc.parts[0].measures.sumOf { it.harmony.sumOf { h -> h.rootPc + h.kind.length } }, maxHeight = 420.dp, q = { if (p.active) p.posSec * p.usedTempo / 60.0 else null })
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { if (p.active) p.stop() else p.start(arranged, arranged.tempo, List(arranged.parts.size) { 1.0 }, 0.0, Double.MAX_VALUE, false) }) { Text(if (p.active) "Parar" else "Ouvir") }
                    Text("${sc.parts[0].measures.size} compassos · ${arranged.parts.size} pautas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Panel {
                Text("2 · Arranjo", style = MaterialTheme.typography.titleMedium)
                SectionTitle("Acompanhamento de piano")
                Wrap {
                    FilterChip(selected = piano.isEmpty(), onClick = { piano = "" }, label = { Text("Sem piano") })
                    Arrange.PIANO_STYLES.forEach { st -> FilterChip(selected = piano == st.id, onClick = { piano = st.id }, label = { Text(st.label) }) }
                }
                Arrange.PIANO_STYLES.firstOrNull { it.id == piano }?.let { Text(it.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                SectionTitle("Outras vozes (sugestões)")
                Wrap {
                    FilterChip(selected = voices.isEmpty(), onClick = { voices = "" }, label = { Text("Só a melodia") })
                    Arrange.VOICE_MODES.forEach { vm -> FilterChip(selected = voices == vm.id, onClick = { voices = vm.id }, label = { Text(vm.label) }) }
                }
                Arrange.VOICE_MODES.firstOrNull { it.id == voices }?.let { Text(it.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (voices.isNotEmpty() && voices != "baixo") Wrap { FilterChip(selected = voiceLyrics, onClick = { voiceLyrics = !voiceLyrics }, label = { Text("Letra também nas vozes novas") }) }
            }

            Panel {
                Text("3 · Acordes", style = MaterialTheme.typography.titleMedium)
                Text("Toque num acorde para trocar a fundamental ou o tipo, ou para apagá-lo. O acompanhamento se refaz sozinho.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Wrap {
                    FilterChip(selected = chordsPerBar == 1, onClick = { chordsPerBar = 1; generate() }, label = { Text("1 acorde por compasso") })
                    FilterChip(selected = chordsPerBar == 2, onClick = { chordsPerBar = 2; generate() }, label = { Text("2 por compasso") })
                    FilterChip(selected = sevenths, onClick = { sevenths = !sevenths; generate() }, label = { Text("Com sétimas") })
                }
                Wrap {
                    sc.parts[0].measures.forEachIndexed { mi, m ->
                        m.harmony.forEach { h ->
                            OutlinedButton(onClick = { chordEdit = (mi * 10000 + h.at) to h }, contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)) { Text("${mi + 1}: ${h.text()}") }
                        }
                    }
                }
            }

            Panel {
                Text("Usar", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { p.stop(); state.open(arranged.copyDeep(), title.ifBlank { "Minha música" }); state.goStudio() }) { Text("Abrir no Estúdio") }
                    OutlinedButton(onClick = { state.saveAs((title.ifBlank { "Minha música" }) + ".musicxml", MusicXml.write(arranged).toByteArray()) }) { Text("Salvar MusicXML") }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    val ce = chordEdit
    if (ce != null && base != null) {
        val bar = ce.first / 10000; val at = ce.first % 10000
        var h by remember(ce) { mutableStateOf(ce.second) }
        val key = TonalKey(fifths, minor)
        AlertDialog(
            onDismissRequest = { chordEdit = null },
            title = { Text("Compasso ${bar + 1}: ${h.text()}") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fundamental", style = MaterialTheme.typography.labelMedium)
                    Wrap {
                        (0 until 12).forEach { pc ->
                            val (st, al) = key.spellPc(pc)
                            FilterChip(selected = h.rootPc == pc, onClick = { h = h.copy(step = st, alter = al, bassStep = null, bassAlter = 0) }, label = { Text(Names.step(st, al)) })
                        }
                    }
                    Text("Tipo", style = MaterialTheme.typography.labelMedium)
                    Wrap {
                        Harmony.KINDS.forEach { (k, v) -> FilterChip(selected = h.kind == k, onClick = { h = h.copy(kind = k) }, label = { Text(if (v.second.isEmpty()) "maior" else v.second) }) }
                    }
                }
            },
            confirmButton = { Button(onClick = { val b = base!!.copyDeep(); Edit.setChord(b, 0, bar, at, h); base = b; ver++; chordEdit = null }) { Text("Aplicar") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { val b = base!!.copyDeep(); Edit.setChord(b, 0, bar, at, null); base = b; ver++; chordEdit = null }) { Text("Apagar") }
                    TextButton(onClick = { chordEdit = null }) { Text("Cancelar") }
                }
            },
        )
    }
}
