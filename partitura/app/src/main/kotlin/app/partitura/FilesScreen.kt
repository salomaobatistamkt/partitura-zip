package app.partitura

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import app.partitura.core.*
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

@Composable
fun FilesScreen(state: AppState) {
    val s = state.score
    var busy by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 760.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Arquivos", style = MaterialTheme.typography.headlineMedium)
            Panel {
                Text("Abrir", style = MaterialTheme.typography.titleSmall)
                Button(onClick = { state.openPicker() }) { Text("Escolher arquivo") }
                Text("MusicXML (.musicxml, .xml, .mxl). PDF e foto passam pelo servidor de leitura, que se configura nos Ajustes do Início.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val saved = state.projects()
            Panel {
                Text("Salvos neste aparelho", style = MaterialTheme.typography.titleSmall)
                if (saved.isEmpty()) Text("Nenhum projeto salvo. Use o botão de salvar no Estúdio.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                saved.forEach { f ->
                    Row(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).clickable { state.openProject(f) }.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(f.nameWithoutExtension, Modifier.weight(1f))
                        ToolBtn(Ic.TRASH, { state.deleteProject(f) })
                    }
                }
            }
            if (s == null) {
                Panel { Text("Abra uma partitura para exportar.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else {
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
                        Button(onClick = { state.saveAs("$base.png", renderPng(s)) }) { Text("PNG") }
                        Button(onClick = { state.saveAs("$base.pdf", renderPdf(s)) }) { Text("PDF") }
                    }
                    Text("O WAV usa o andamento e os volumes do Estúdio (${state.tempo} bpm). MP3 ainda não está disponível.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

fun renderPng(s: Score): ByteArray {
    val widthSp = 100f; val sp = 10f
    val sc = Engrave.scene(s, widthSp)
    val h = (sc.height * sp).toInt() + 20
    val bmp = Bitmap.createBitmap((widthSp * sp).toInt(), h.coerceAtMost(14000), Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp); c.drawColor(AColor.WHITE)
    ScenePainter.draw(c, sc, sp, AColor.BLACK)
    val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.PNG, 100, out); bmp.recycle()
    return out.toByteArray()
}

fun renderPdf(s: Score): ByteArray {
    val pw = 595; val ph = 842; val margin = 40f
    val widthSp = 78f
    val sp = (pw - 2 * margin) / widthSp
    val sc = Engrave.scene(s, widthSp)
    val availSp = (ph - 2 * margin) / sp
    val doc = PdfDocument()
    // agrupa sistemas em páginas
    val pages = ArrayList<Pair<Float, Float>>() // (topo, fim) em sp
    var start = (sc.systems.firstOrNull()?.top ?: 0f) - 2f
    var end = start
    for (sys in sc.systems) {
        if (sys.bottom + 4f - start > availSp && end > start) { pages += start to end; start = sys.top - 2f }
        end = sys.bottom + 4f
    }
    pages += start to maxOf(end, start + 1f)
    val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 15f; color = AColor.BLACK }
    pages.forEachIndexed { i, (a, b) ->
        val pg = doc.startPage(PdfDocument.PageInfo.Builder(pw, ph, i + 1).create())
        val c = pg.canvas
        c.drawColor(AColor.WHITE)
        if (i == 0) c.drawText(s.title, margin, 26f, titlePaint)
        c.save()
        c.translate(margin, margin - a * sp)
        c.clipRect(0f, a * sp, widthSp * sp, b * sp)
        ScenePainter.draw(c, sc, sp, AColor.BLACK, y0 = a * sp, y1 = b * sp)
        c.restore()
        doc.finishPage(pg)
    }
    val out = ByteArrayOutputStream(); doc.writeTo(out); doc.close()
    return out.toByteArray()
}
