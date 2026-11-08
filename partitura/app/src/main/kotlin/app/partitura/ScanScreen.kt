package app.partitura

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color as AColor
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.partitura.core.*

private val SCAN_METERS = listOf<Pair<Int?, Int>>(null to 4, 4 to 4, 3 to 4, 2 to 4, 6 to 8)

/** Bitmap → imagem binária para o leitor. [adaptive] = foto com luz irregular. */
private fun toOmr(bm: Bitmap, adaptive: Boolean): OmrImage {
    val w = bm.width; val h = bm.height
    val px = IntArray(w * h)
    bm.getPixels(px, 0, w, 0, 0, w, h)
    val g = IntArray(w * h) { val c = px[it]; (AColor.red(c) * 30 + AColor.green(c) * 59 + AColor.blue(c) * 11) / 100 }
    return if (adaptive) OmrImage.fromGrayAdaptive(g, w, h) else OmrImage.fromGray(g, w, h)
}

private fun renderPdfPage(pr: PdfRenderer, index: Int, width: Int): Bitmap {
    val page = pr.openPage(index)
    try {
        val h = (width.toLong() * page.height / page.width).toInt()
        val bm = Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888)
        bm.eraseColor(AColor.WHITE)
        page.render(bm, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bm
    } finally { page.close() }
}

private fun medianSpace(im: OmrImage): Double {
    val st = Omr.detectStaves(im)
    if (st.isEmpty()) return 0.0
    return st.map { it.space }.sorted()[st.size / 2]
}

/** Aba Ler: PDF ou imagem de partitura → notas, 100% no aparelho (core/Omr.kt), sem servidor. */
@Composable
fun ScanScreen(state: AppState) {
    val ctx = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var busy by remember { mutableStateOf(false) }
    var progText by remember { mutableStateOf("") }
    var prog by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<OmrResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var title by rememberSaveable { mutableStateOf("Partitura lida") }
    var meterIdx by rememberSaveable { mutableIntStateOf(0) }
    var lastUri by remember { mutableStateOf<Uri?>(null) }
    var lastIsPdf by remember { mutableStateOf(true) }
    var photo by rememberSaveable { mutableStateOf(false) }

    fun run(uri: Uri, isPdf: Boolean) {
        busy = true; error = null; result = null; prog = 0f; progText = "Abrindo…"
        val (beats, beatType) = SCAN_METERS[meterIdx].let { (it.first) to it.second }
        val adaptive = !isPdf || photo
        Thread {
            try {
                val results = ArrayList<OmrResult>()
                val opts = OmrOptions(title = title.ifBlank { "Partitura lida" }, beats = beats, beatType = beatType)
                if (isPdf) {
                    val fd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: throw IllegalStateException("Não consegui abrir o PDF.")
                    val pr = PdfRenderer(fd)
                    try {
                        val n = minOf(pr.pageCount, 40)
                        for (i in 0 until n) {
                            main.post { prog = i / n.toFloat(); progText = "Lendo página ${i + 1} de $n…" }
                            var width = 1700
                            var bm = renderPdfPage(pr, i, width)
                            var im = toOmr(bm, adaptive); bm.recycle()
                            val sp = medianSpace(im)
                            if (sp in 1.0..8.5 && width < 3200) {          // pauta pequena: renderiza maior
                                width = minOf(3200, (width * 10.0 / sp).toInt())
                                bm = renderPdfPage(pr, i, width); im = toOmr(bm, adaptive); bm.recycle()
                            }
                            try { results += Omr.read(im, opts) }
                            catch (e: IllegalStateException) { if (n == 1) throw e }     // página sem pauta (capa, texto)
                        }
                        if (pr.pageCount > n) throw IllegalStateException("O PDF tem ${pr.pageCount} páginas; li as $n primeiras.")
                    } finally { pr.close() }
                } else {
                    main.post { progText = "Lendo a imagem…" }
                    val o = BitmapFactory.Options(); o.inJustDecodeBounds = true
                    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
                    var sample = 1
                    while (maxOf(o.outWidth, o.outHeight) / sample > 3200) sample *= 2
                    val o2 = BitmapFactory.Options(); o2.inSampleSize = sample
                    val bm = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: throw IllegalStateException("Não consegui abrir a imagem.")
                    val im = toOmr(bm, true); bm.recycle()
                    results += Omr.read(im, opts)
                }
                if (results.isEmpty()) throw IllegalStateException("Não encontrei pautas em nenhuma página.")
                val merged = if (results.size == 1) results[0] else Omr.merge(results, opts.title)
                main.post { result = merged; busy = false }
            } catch (e: Throwable) {
                main.post { error = e.message ?: e.toString(); busy = false }
            }
        }.start()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val type = ctx.contentResolver.getType(uri) ?: ""
            val pdf = type.contains("pdf") || uri.toString().lowercase().endsWith(".pdf")
            lastUri = uri; lastIsPdf = pdf
            run(uri, pdf)
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Ler partitura", style = MaterialTheme.typography.headlineSmall)
        Text("Abra um PDF ou uma imagem de partitura e o app transforma em notas editáveis. Tudo roda no aparelho, sem servidor e sem internet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Panel {
            if (busy) {
                Text("Lendo…", style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(progress = { prog.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                Text(progText, style = MaterialTheme.typography.bodySmall)
            } else {
                Button(onClick = { picker.launch(arrayOf("application/pdf", "image/*")) }) { Text("Escolher PDF ou imagem") }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = photo, onClick = { photo = !photo }, label = { Text("É uma foto (luz irregular)") })
                }
                Text("Funciona melhor com PDF digital ou imagem nítida, página reta, uma ou duas pautas por sistema (melodia, piano).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Panel {
            Text("Opções", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(title, { title = it }, label = { Text("Título") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("Compasso", style = MaterialTheme.typography.titleSmall)
            FlowRowChips(SCAN_METERS.map { if (it.first == null) "Automático" else "${it.first}/${it.second}" }, meterIdx) { meterIdx = it }
            val u = lastUri
            if (u != null && !busy) Button(onClick = { run(u, lastIsPdf) }) { Text("Ler de novo com estas opções") }
        }
        val r = result
        if (r != null) {
            Panel {
                Text("Resultado", style = MaterialTheme.typography.titleMedium)
                Text("${r.notes} notas · ${r.bars} compassos · ${r.score.parts.size} pauta(s) por sistema · tom ${if (r.score.fifths == 0) "sem armadura" else if (r.score.fifths > 0) "${r.score.fifths}♯" else "${-r.score.fifths}♭"} · ${r.score.beats}/${r.score.beatType}")
                r.warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                StaticScore(r.score, 0, 360.dp)
                Text("Confira no Estúdio: leitura óptica pode errar notas, ritmos e acidentes. Ainda não lê tercinas, ligaduras, letras e dinâmicas.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { state.open(r.score.copyDeep(), title.ifBlank { "Partitura lida" }); state.goStudio() }) { Text("Abrir no Estúdio") }
                    OutlinedButton(onClick = { state.saveAs(title.ifBlank { "Partitura lida" } + ".musicxml", MusicXml.write(r.score).toByteArray()) }) { Text("Salvar MusicXML") }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowChips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l -> FilterChip(selected = selected == i, onClick = { onSelect(i) }, label = { Text(l) }) }
    }
}
