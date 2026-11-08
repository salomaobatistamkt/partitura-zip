package app.partitura

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.partitura.core.*
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

@Composable
fun TransposeScreen(state: AppState, goHome: () -> Unit) {
    val s = state.score
    if (s == null) { Column(Modifier.padding(20.dp)) { Empty("Nada para transpor", "Abra um arquivo ou um exemplo primeiro.", state, goHome) }; return }
    var semis by remember(state.rev) { mutableIntStateOf(0) }
    val preview = remember(state.rev, semis) { Transpose.bySemitones(s, semis) }
    val curClass = Math.floorMod(7 * s.fifths, 12)
    Column(Modifier.fillMaxSize().verticalScrollCompat().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Transposição", style = MaterialTheme.typography.headlineSmall)
        Text("Tonalidade atual: ${Names.key(s.fifths)} maior (ou ${Names.minorKey(s.fifths)} menor)", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Panel {
            Text("Por semitons", style = MaterialTheme.typography.titleSmall)
            Stepper("Semitons", (if (semis > 0) "+" else "") + semis, { if (semis > -12) semis-- }, { if (semis < 12) semis++ })
            Text("Resultado: ${Names.key(preview.fifths)} maior · ${Names.minorKey(preview.fifths)} menor", color = MaterialTheme.colorScheme.primary)
        }
        Panel {
            Text("Para uma tonalidade", style = MaterialTheme.typography.titleSmall)
            Wrap {
                (-6..6).sortedBy { Math.floorMod(7 * it, 12) }.forEach { f ->
                    val diff = Math.floorMod(Math.floorMod(7 * f, 12) - curClass + 6, 12) - 6
                    FilterChip(selected = false, onClick = { semis = diff }, label = { Text(Names.key(f)) })
                }
            }
        }
        Panel {
            Text("Instrumentos transpositores (altura escrita)", style = MaterialTheme.typography.titleSmall)
            Wrap {
                listOf("Clarinete, trompete ou sax tenor em Si♭" to 2, "Sax alto ou clarone em Mi♭" to 9, "Trompa em Fá" to 7).forEach { (n, k) ->
                    FilterChip(selected = false, onClick = { semis = k }, label = { Text(n) })
                }
            }
            Text("Só a altura escrita é transposta; a oitava dos instrumentos não é ajustada.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Wrap {
            Button(onClick = { state.replace(Transpose.bySemitones(s, semis)); state.toast("Partitura transposta (${if (semis > 0) "+" else ""}$semis). Dá para desfazer no editor.") }, enabled = semis != 0) { Text("Aplicar à partitura") }
            OutlinedButton(onClick = { semis = 0 }) { Text("Zerar") }
            OutlinedButton(onClick = { state.player.play(preview, state.tempo, state.volumes.map { it.toDouble() }, false, 0, preview.parts[0].measures.size - 1) }) { Text("Ouvir resultado") }
            OutlinedButton(onClick = { state.player.stop() }) { Text("Parar") }
        }
        Text("Prévia", style = MaterialTheme.typography.titleSmall)
        StaffView(state, preview, editable = false)
    }
}

private val TONICS = listOf(
    "Dó" to Pitch('C', 0, 4), "Dó♯" to Pitch('C', 1, 4), "Ré♭" to Pitch('D', -1, 4), "Ré" to Pitch('D', 0, 4), "Mi♭" to Pitch('E', -1, 4),
    "Mi" to Pitch('E', 0, 4), "Fá" to Pitch('F', 0, 4), "Fá♯" to Pitch('F', 1, 4), "Sol♭" to Pitch('G', -1, 4), "Sol" to Pitch('G', 0, 4),
    "Lá♭" to Pitch('A', -1, 4), "Lá" to Pitch('A', 0, 4), "Si♭" to Pitch('B', -1, 4), "Si" to Pitch('B', 0, 4),
)

@Composable
fun TheoryScreen(state: AppState, goEditor: () -> Unit) {
    var section by remember { mutableIntStateOf(0) }
    var tonicIdx by remember { mutableIntStateOf(0) }
    var scaleType by remember { mutableStateOf(ScaleType.MAIOR) }
    var quality by remember { mutableStateOf(ChordQuality.MAIOR) }
    var fieldMajor by remember { mutableStateOf(true) }
    var sevenths by remember { mutableStateOf(false) }
    val tonic = TONICS[tonicIdx].second
    Column(Modifier.fillMaxSize().verticalScrollCompat().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Teoria musical", style = MaterialTheme.typography.headlineSmall)
        Wrap { listOf("Escalas", "Acordes", "Campo harmônico", "Ciclo das quintas").forEachIndexed { i, n -> FilterChip(selected = section == i, onClick = { section = i }, label = { Text(n) }) } }
        if (section != 3) {
            Text("Nota de partida", style = MaterialTheme.typography.titleSmall)
            Wrap { TONICS.forEachIndexed { i, (n, _) -> FilterChip(selected = tonicIdx == i, onClick = { tonicIdx = i }, label = { Text(n) }) } }
        }
        when (section) {
            0 -> {
                Wrap { ScaleType.values().forEach { t -> FilterChip(selected = scaleType == t, onClick = { scaleType = t }, label = { Text(t.label) }) } }
                val notes = Theory.scale(tonic, scaleType)
                val sc = Theory.toScore("${Names.step(tonic.step, tonic.alter)} ${scaleType.label}", notes, 1.0, 0)
                Panel {
                    Text(notes.joinToString(" – ") { Names.step(it.step, it.alter) }, style = MaterialTheme.typography.titleMedium)
                    Wrap {
                        Button(onClick = { state.player.play(sc, 100, listOf(1.0), false, 0, sc.parts[0].measures.size - 1) }) { Text("Ouvir escala") }
                        OutlinedButton(onClick = { state.player.stop() }) { Text("Parar") }
                        OutlinedButton(onClick = { sc.tempo = 100; state.open(sc); goEditor() }) { Text("Abrir no editor") }
                    }
                }
                StaffView(state, sc, editable = false)
            }
            1 -> {
                Wrap { ChordQuality.values().forEach { q -> FilterChip(selected = quality == q, onClick = { quality = q }, label = { Text(q.label) }) } }
                val root = tonic.copy(octave = 3)
                val notes = Theory.chord(root, quality)
                val block = Theory.toScore("Acorde", notes, 4.0, 0, chord = true)
                val arp = Theory.toScore("Arpejo", notes, 1.0, 0)
                Panel {
                    Text(Names.step(tonic.step, tonic.alter) + " " + quality.label.lowercase(), style = MaterialTheme.typography.titleMedium)
                    Text(notes.joinToString(" – ") { Names.step(it.step, it.alter) })
                    Wrap {
                        Button(onClick = { state.player.play(block, 80, listOf(1.0), false, 0, 0) }) { Text("Ouvir acorde") }
                        OutlinedButton(onClick = { state.player.play(arp, 100, listOf(1.0), false, 0, 0) }) { Text("Ouvir arpejo") }
                        OutlinedButton(onClick = { state.open(block); goEditor() }) { Text("Abrir no editor") }
                    }
                }
                StaffView(state, block, editable = false)
            }
            2 -> {
                Wrap {
                    FilterChip(selected = fieldMajor, onClick = { fieldMajor = true }, label = { Text("Maior") })
                    FilterChip(selected = !fieldMajor, onClick = { fieldMajor = false }, label = { Text("Menor natural") })
                    FilterChip(selected = sevenths, onClick = { sevenths = !sevenths }, label = { Text("Com sétima") })
                }
                val field = Theory.harmonicField(tonic.copy(octave = 3), fieldMajor, sevenths)
                field.forEach { ch ->
                    Panel {
                        Text("${ch.degree}  ·  ${ch.name}", style = MaterialTheme.typography.titleMedium)
                        Text(ch.notes.joinToString(" – ") { Names.step(it.step, it.alter) } + "  (${ch.quality.label.lowercase()})", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedButton(onClick = { state.player.play(Theory.toScore("Acorde", ch.notes, 4.0, 0, chord = true), 80, listOf(1.0), false, 0, 0) }) { Text("Ouvir") }
                    }
                }
                Button(onClick = {
                    val sc = Score("Campo harmônico de ${Names.step(tonic.step, tonic.alter)} ${if (fieldMajor) "maior" else "menor"}", 4, 0, 4, 4, 70)
                    val part = Part("P1", "Piano", mutableListOf())
                    field.forEachIndexed { i, ch -> val m = Measure(i + 1); ch.notes.forEachIndexed { k, p -> m.notes += Note(p, 16, chord = k > 0) }; part.measures += m }
                    sc.parts += part; state.open(sc); goEditor()
                }) { Text("Abrir sequência no editor") }
                Text("Cada acorde fica num compasso, como acorde em bloco.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                Panel {
                    Text("Ciclo das quintas", style = MaterialTheme.typography.titleMedium)
                    Text("Cada passo para a direita sobe uma quinta e soma um sustenido; para a esquerda soma um bemol.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    (-6..6).forEach { f ->
                        val acc = when { f == 0 -> "sem acidentes"; f > 0 -> "$f sustenido(s)"; else -> "${-f} bemol(is)" }
                        Text("${Names.key(f)} maior · ${Names.minorKey(f)} menor — $acc")
                    }
                }
            }
        }
    }
}

@Composable
fun FilesScreen(state: AppState, goHome: () -> Unit) {
    val s = state.score
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScrollCompat().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Arquivos", style = MaterialTheme.typography.headlineSmall)
        Panel {
            Text("Importar", style = MaterialTheme.typography.titleSmall)
            Button(onClick = { state.openPicker() }) { Text("Abrir MusicXML (.musicxml, .xml, .mxl)") }
        }
        if (s == null) { Empty("Nada para exportar", "Abra um arquivo ou um exemplo primeiro.", state, goHome); return@Column }
        val base = s.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "partitura" }
        Panel {
            Text("Exportar \"${s.title}\"", style = MaterialTheme.typography.titleSmall)
            Wrap {
                Button(onClick = { state.saveAs("$base.musicxml", MusicXml.write(s).toByteArray()) }) { Text("MusicXML") }
                Button(onClick = { state.saveAs("$base.mid", Midi.export(s)) }) { Text("MIDI") }
                Button(enabled = !busy, onClick = {
                    busy = true; val snap = s.copyDeep(); val t = state.tempo; val v = state.volumes.map { it.toDouble() }
                    thread { val w = Synth.wavBytes(Synth.render(snap, t, v)); android.os.Handler(android.os.Looper.getMainLooper()).post { busy = false; state.saveAs("$base.wav", w) } }
                }) { Text(if (busy) "Gerando áudio…" else "WAV (áudio)") }
                Button(onClick = { state.saveAs("$base.png", renderPng(s)) }) { Text("PNG (imagem)") }
                Button(onClick = { state.saveAs("$base.pdf", renderPdf(s)) }) { Text("PDF") }
            }
            Text("O WAV usa o andamento e os volumes da tela Reprodução. MP3 ainda não está disponível.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

fun renderPng(s: Score): ByteArray {
    val sc = 1.6f; val wUnits = 880f
    val lay = Layout.layout(s, wUnits)
    val bmp = Bitmap.createBitmap((wUnits * sc).toInt(), StaffRenderer.height(lay.rows.size, sc).toInt() + 20, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); c.drawColor(AColor.WHITE)
    StaffRenderer.draw(c, lay, sc, AColor.BLACK, AColor.BLACK)
    val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out); bmp.recycle()
    return out.toByteArray()
}

fun renderPdf(s: Score): ByteArray {
    val pw = 595; val ph = 842; val margin = 24f
    val lay = Layout.layout(s, pw - 2 * margin)
    val perPage = ((ph - 2 * margin) / StaffRenderer.ROW_H).toInt().coerceAtLeast(1)
    val doc = PdfDocument()
    var page = 0; var r = 0
    while (r < lay.rows.size || page == 0) {
        val end = minOf(lay.rows.size, r + perPage) - 1
        val pg = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, page + 1).create())
        pg.canvas.translate(margin, margin)
        pg.canvas.drawColor(AColor.WHITE)
        pg.canvas.drawText(s.title, 0f, -6f, android.graphics.Paint().apply { textSize = 14f })
        if (lay.rows.isNotEmpty()) StaffRenderer.draw(pg.canvas, lay, 1f, AColor.BLACK, AColor.BLACK, null, r..end)
        doc.finishPage(pg)
        r += perPage; page++
        if (lay.rows.isEmpty()) break
    }
    val out = ByteArrayOutputStream(); doc.writeTo(out); doc.close()
    return out.toByteArray()
}
