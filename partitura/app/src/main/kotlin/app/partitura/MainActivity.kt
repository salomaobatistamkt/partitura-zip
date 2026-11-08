package app.partitura

import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import app.partitura.core.*
import kotlin.concurrent.thread

class MainActivity : ComponentActivity() {
    private var track: AudioTrack? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { Surface { App() } } }
    }

    private fun play(score: Score, tempo: Int, loop: Boolean) {
        stop()
        thread {
            val pcm = Synth.render(score, tempo)
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Synth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            t.write(pcm, 0, pcm.size)
            if (loop) t.setLoopPoints(0, pcm.size, -1)
            track = t; t.play()
        }
    }
    private fun stop() { track?.run { try { stop() } catch (_: Exception) {}; release() }; track = null }
    override fun onDestroy() { stop(); super.onDestroy() }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun App() {
        var score by remember { mutableStateOf<Score?>(null) }
        var tempo by remember { mutableFloatStateOf(100f) }
        var loop by remember { mutableStateOf(false) }
        var msg by remember { mutableStateOf("Abra um arquivo MusicXML (.musicxml ou .xml).") }
        var pendingExport by remember { mutableStateOf<ByteArray?>(null) }

        val opener = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri ?: return@rememberLauncherForActivityResult
            try {
                val bytes = contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                val s = MusicXml.parse(bytes); score = s; tempo = s.tempo.toFloat()
                val issues = Validate.measureDurations(s)
                msg = "Aberto: ${s.title}. " + if (issues.isEmpty()) "Compassos consistentes." else "Avisos: " + issues.take(3).joinToString("; ")
            } catch (e: Exception) { msg = "Não foi possível ler o arquivo: ${e.message}" }
        }
        val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
            val data = pendingExport
            if (uri != null && data != null) { contentResolver.openOutputStream(uri)?.use { it.write(data) }; msg = "Arquivo exportado." }
        }
        fun export(name: String, data: ByteArray) { pendingExport = data; saver.launch(name) }

        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Partitura", style = MaterialTheme.typography.headlineMedium)
            Button(onClick = { opener.launch(arrayOf("*/*")) }) { Text("Abrir MusicXML") }
            Text(msg)
            val s = score
            if (s != null) {
                Staff(s)
                Text("Andamento: ${tempo.toInt()} bpm (o tom não muda)")
                Slider(value = tempo, onValueChange = { tempo = it }, valueRange = 30f..220f)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { play(s, tempo.toInt(), loop) }) { Text("Tocar") }
                    Button(onClick = { stop() }) { Text("Parar") }
                    Row { Checkbox(checked = loop, onCheckedChange = { loop = it }); Text("Loop", Modifier.align(androidx.compose.ui.Alignment.CenterVertically)) }
                }
                Text("Transpor")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { score = Transpose.bySemitones(s, -1) }) { Text("-1") }
                    Button(onClick = { score = Transpose.bySemitones(s, 1) }) { Text("+1") }
                    Button(onClick = { score = Transpose.bySemitones(s, -2) }) { Text("-2") }
                    Button(onClick = { score = Transpose.bySemitones(s, 2) }) { Text("+2") }
                }
                Text("Exportar")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { export("partitura.musicxml", MusicXml.write(s).toByteArray()) }) { Text("MusicXML") }
                    Button(onClick = { export("partitura.mid", Midi.export(s)) }) { Text("MIDI") }
                    Button(onClick = { thread { val w = Synth.wavBytes(Synth.render(s, tempo.toInt())); runOnUiThread { export("partitura.wav", w) } } }) { Text("WAV") }
                }
            }
        }
    }

    /** Pauta simples: desenha a primeira parte (linhas, cabeças de nota e barras de compasso). Sem hastes nem ligaduras ainda. */
    @Composable
    fun Staff(s: Score) {
        val part = s.parts.firstOrNull() ?: return
        val ink = if (isSystemInDarkTheme()) Color.White else Color.Black
        val notes = part.measures.sumOf { it.notes.size }
        val width = (part.measures.size * 40 + notes * 28 + 40).dp
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Canvas(Modifier.width(width).height(160.dp)) {
                val gap = 10.dp.toPx(); val top = 50.dp.toPx()
                for (i in 0..4) drawLine(ink, Offset(0f, top + i * gap), Offset(size.width, top + i * gap), 1.5f)
                var x = 20.dp.toPx()
                for (m in part.measures) {
                    for (n in m.notes) {
                        val p = n.pitch
                        if (p != null && !n.chord || p != null) {
                            // posição diatônica relativa a E4 (linha de baixo da clave de sol)
                            val steps = (p!!.octave * 7 + Pitch.STEPS.indexOf(p.step)) - (4 * 7 + 2)
                            val y = top + 4 * gap - steps * gap / 2
                            drawCircle(ink, gap * 0.55f, Offset(x, y))
                        }
                        if (!n.chord) x += 28.dp.toPx()
                    }
                    drawLine(ink, Offset(x, top), Offset(x, top + 4 * gap), 1.5f); x += 12.dp.toPx()
                }
            }
        }
    }
}
