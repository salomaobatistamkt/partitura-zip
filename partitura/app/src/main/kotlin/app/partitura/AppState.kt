package app.partitura

import android.content.ContentResolver
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import app.partitura.core.*
import java.io.File
import kotlin.concurrent.thread

class Player {
    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var token = 0
    var playing by mutableStateOf(false)
    var progress by mutableFloatStateOf(0f)
    var busy by mutableStateOf(false)

    fun stop() {
        token++
        val t = track; track = null
        if (t != null) { try { t.stop() } catch (_: Exception) {}; try { t.release() } catch (_: Exception) {} }
        playing = false; progress = 0f; busy = false
    }

    fun play(score: Score, tempo: Int, volumes: List<Double>, loop: Boolean, fromMeasure: Int, toMeasure: Int) {
        stop()
        val my = token
        busy = true
        val snapshot = score.copyDeep()
        thread {
            val times = Synth.measureTimes(snapshot, tempo)
            val a = times[fromMeasure.coerceIn(0, times.size - 1)]
            val b = times[(toMeasure + 1).coerceIn(0, times.size - 1)]
            val pcm = Synth.render(snapshot, tempo, volumes, a, if (b > a) b else Double.MAX_VALUE)
            // limita ao trecho pedido (render acrescenta 1 s de cauda)
            val frames = if (b > a) minOf(pcm.size, ((b - a) * Synth.RATE).toInt() + Synth.RATE / 4) else pcm.size
            main.post {
                if (my != token || frames <= 0) { busy = false; return@post }
                try {
                    val t = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Synth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(frames * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
                    t.write(pcm, 0, frames)
                    if (loop) t.setLoopPoints(0, frames, -1)
                    track = t; playing = true; busy = false
                    t.play()
                    poll(my, t, frames, loop)
                } catch (e: Exception) { busy = false; playing = false }
            }
        }
    }

    private fun poll(my: Int, t: AudioTrack, frames: Int, loop: Boolean) {
        main.postDelayed({
            if (my != token) return@postDelayed
            val pos = try { t.playbackHeadPosition } catch (_: Exception) { frames }
            progress = ((pos % frames).toFloat() / frames).coerceIn(0f, 1f)
            if (!loop && pos >= frames) { stop() } else poll(my, t, frames, loop)
        }, 60)
    }

    fun preview(pitches: List<Pitch>) {
        if (pitches.isEmpty()) return
        val s = Theory.toScore("p", pitches, 1.0, 0, chord = pitches.size > 1)
        play(s, 160, listOf(1.0), false, 0, 0)
    }
}

class AppState(private val app: Context) {
    var score by mutableStateOf<Score?>(null)
    var rev by mutableIntStateOf(0)
    var selP by mutableIntStateOf(0)
    var selM by mutableIntStateOf(0)
    var selN by mutableIntStateOf(0)
    var dark by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
    var projectName by mutableStateOf<String?>(null)
    var tempo by mutableIntStateOf(100)
    val volumes = mutableStateListOf<Float>()
    val player = Player()

    var openPicker: () -> Unit = {}
    var saveAs: (String, ByteArray) -> Unit = { _, _ -> }

    private val undo = ArrayDeque<Score>()
    private val redo = ArrayDeque<Score>()
    val canUndo: Boolean get() { rev; return undo.isNotEmpty() }
    val canRedo: Boolean get() { rev; return redo.isNotEmpty() }

    fun open(s: Score, name: String? = null) {
        player.stop()
        score = s; undo.clear(); redo.clear()
        projectName = name
        tempo = s.tempo
        volumes.clear(); repeat(s.parts.size) { volumes.add(1f) }
        selP = 0; selM = 0; selN = 0
        rev++
    }

    private fun fixSel() {
        val s = score ?: return
        selP = selP.coerceIn(0, s.parts.size - 1)
        val ms = s.parts[selP].measures
        selM = selM.coerceIn(0, ms.size - 1)
        selN = selN.coerceIn(0, maxOf(0, ms[selM].notes.size - 1))
    }

    fun edit(block: (Score) -> Unit) {
        val s = score ?: return
        undo.addLast(s.copyDeep()); if (undo.size > 60) undo.removeFirst()
        redo.clear()
        block(s); fixSel(); rev++
    }

    fun replace(n: Score) {
        val s = score ?: return
        undo.addLast(s.copyDeep()); redo.clear()
        n.tempo = s.tempo
        score = n; fixSel(); rev++
    }

    fun undo() { val s = score ?: return; val p = undo.removeLastOrNull() ?: return; redo.addLast(s.copyDeep()); score = p; fixSel(); rev++ }
    fun redo() { val s = score ?: return; val p = redo.removeLastOrNull() ?: return; undo.addLast(s.copyDeep()); score = p; fixSel(); rev++ }

    fun currentNote(): Note? = score?.parts?.getOrNull(selP)?.measures?.getOrNull(selM)?.notes?.getOrNull(selN)

    fun moveSel(dir: Int) {
        val ms = score?.parts?.getOrNull(selP)?.measures ?: return
        var m = selM; var n = selN + dir
        if (n < 0) { if (m > 0) { m--; n = maxOf(0, ms[m].notes.size - 1) } else n = 0 }
        else if (n >= ms[m].notes.size) { if (m < ms.size - 1) { m++; n = 0 } else n = ms[m].notes.size - 1 }
        selM = m; selN = n.coerceAtLeast(0)
    }

    /** Teclado virtual: grava a altura na nota selecionada e avança para a próxima. */
    fun writeNote(midi: Int) {
        if (currentNote() == null) { message = "Toque numa nota da pauta para selecioná-la."; return }
        val s = score ?: return
        val p = Spell.forMidi(midi, s.fifths)
        edit { Edit.setPitch(it, selP, selM, selN, midi) }
        player.preview(listOf(p))
        moveSel(1)
    }

    fun toast(m: String) { message = m }

    // ---- arquivos ----
    private fun dir() = File(app.filesDir, "projects").also { it.mkdirs() }
    fun projects(): List<File> { rev; return dir().listFiles { f -> f.extension == "musicxml" }?.sortedByDescending { it.lastModified() } ?: emptyList() }

    fun save(name: String) {
        val s = score ?: return
        val clean = name.trim().replace(Regex("[^\\p{L}\\p{N} _.-]"), "").ifBlank { "Sem título" }
        File(dir(), "$clean.musicxml").writeText(MusicXml.write(s))
        projectName = clean; rev++
        message = "Projeto salvo: $clean"
    }

    fun openProject(f: File) {
        try { open(MusicXml.parseAny(f.readBytes()), f.nameWithoutExtension) } catch (e: Exception) { message = "Não foi possível abrir: ${e.message}" }
    }

    fun deleteProject(f: File) { f.delete(); rev++ }

    fun importUri(cr: ContentResolver, uri: Uri) {
        try {
            val bytes = cr.openInputStream(uri)!!.use { it.readBytes() }
            val s = MusicXml.parseAny(bytes)
            open(s, null)
            val issues = Validate.measureDurations(s)
            message = "Aberto: ${s.title}" + if (issues.isNotEmpty()) " (${issues.size} aviso(s) de compasso)" else ""
        } catch (e: Exception) {
            message = "Não foi possível ler o arquivo. Use MusicXML (.musicxml, .xml ou .mxl). ${e.message ?: ""}"
        }
    }
}
