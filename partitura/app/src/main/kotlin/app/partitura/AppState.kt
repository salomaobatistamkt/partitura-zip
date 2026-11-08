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
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

private fun newTrack(frames: Int): AudioTrack =
    AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Synth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(maxOf(frames * 2, 4096)).setTransferMode(AudioTrack.MODE_STATIC).build()

/** Toca partituras: sintetiza o trecho em segundo plano e acompanha a posição para a tela seguir a música. */
class Player {
    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var token = 0
    private var frames = 0
    private var loop = false
    private var fromSec = 0.0
    var playing by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var busy by mutableStateOf(false)
    /** Posição atual na música, em segundos (no andamento usado para sintetizar). */
    var posSec by mutableDoubleStateOf(0.0)
    var usedTempo by mutableIntStateOf(100)
    val active: Boolean get() = playing || paused

    private val keyCache = HashMap<Int, ShortArray>()
    private val keyTracks = ArrayList<AudioTrack>()

    fun stop() {
        token++
        val t = track; track = null
        if (t != null) { try { t.stop() } catch (_: Exception) {}; try { t.release() } catch (_: Exception) {} }
        playing = false; paused = false; busy = false
    }

    fun start(score: Score, tempo: Int, volumes: List<Double>, from: Double, to: Double, loop: Boolean) {
        stop()
        val my = token
        busy = true
        val snapshot = score.copyDeep()
        thread {
            val pcm = Synth.render(snapshot, tempo, volumes, from, to)
            val limit = if (to < 1e8) ((to - from) * Synth.RATE).toInt() + (if (loop) 0 else Synth.RATE / 3) else pcm.size
            val n = minOf(pcm.size, limit)
            main.post {
                if (my != token || n <= 0) { busy = false; return@post }
                try {
                    val t = newTrack(n)
                    t.write(pcm, 0, n)
                    if (loop) t.setLoopPoints(0, n, -1)
                    track = t; frames = n; this.loop = loop; fromSec = from; usedTempo = tempo
                    posSec = from; playing = true; paused = false; busy = false
                    t.play()
                    poll(my, t)
                } catch (e: Exception) { busy = false; playing = false }
            }
        }
    }

    fun pause() { val t = track ?: return; try { t.pause() } catch (_: Exception) {}; playing = false; paused = true }
    fun resume() { val t = track ?: return; try { t.play() } catch (_: Exception) {}; playing = true; paused = false; poll(token, t) }

    private fun poll(my: Int, t: AudioTrack) {
        main.postDelayed({
            if (my != token || !playing) return@postDelayed
            val head = try { t.playbackHeadPosition } catch (_: Exception) { frames }
            val pos = if (loop) head % frames else head
            posSec = fromSec + pos.toDouble() / Synth.RATE
            if (!loop && head >= frames) stop() else poll(my, t)
        }, 16)
    }

    /** Toca uma nota isolada (teclado). */
    fun key(midi: Int) {
        val pcm = keyCache.getOrPut(midi) {
            val p = Spell.forMidi(midi, 0)
            Synth.render(Theory.toScore("k", listOf(p), 1.0, 0), 120, listOf(1.0)).let { it.copyOf(minOf(it.size, Synth.RATE * 2)) }
        }
        try {
            val t = newTrack(pcm.size)
            t.write(pcm, 0, pcm.size)
            keyTracks += t
            t.play()
            main.postDelayed({ try { t.stop(); t.release() } catch (_: Exception) {}; keyTracks.remove(t) }, 2100)
            if (keyTracks.size > 12) { val o = keyTracks.removeAt(0); try { o.stop(); o.release() } catch (_: Exception) {} }
        } catch (_: Exception) {}
    }

    fun release() { stop(); keyTracks.forEach { try { it.release() } catch (_: Exception) {} }; keyTracks.clear() }
}

class AppState(private val app: Context) {
    private val prefs = app.getSharedPreferences("partitura", Context.MODE_PRIVATE)
    var score by mutableStateOf<Score?>(null)
    var rev by mutableIntStateOf(0)
    var selP by mutableIntStateOf(0)
    var selM by mutableIntStateOf(0)
    var selN by mutableIntStateOf(0)
    var hasSel by mutableStateOf(false)
    var dark by mutableStateOf(prefs.getBoolean("dark", true))
        private set
    var omrUrl by mutableStateOf(prefs.getString("omr_url", "") ?: "")
        private set
    var message by mutableStateOf<String?>(null)
    var projectName by mutableStateOf<String?>(null)
    var tempo by mutableIntStateOf(100)
    val volumes = mutableStateListOf<Float>()
    val player = Player()

    var editMode by mutableStateOf(false)
    var drawTool by mutableStateOf(false)
    var loop by mutableStateOf(false)
    var rangeFrom by mutableIntStateOf(0)   // 0 = música toda
    var rangeTo by mutableIntStateOf(0)
    var importing by mutableStateOf(false)
    var needOmr by mutableStateOf(false)
    var goStudio: () -> Unit = {}

    var openPicker: () -> Unit = {}
    var saveAs: (String, ByteArray) -> Unit = { _, _ -> }

    fun applyDark(v: Boolean) { dark = v; prefs.edit().putBoolean("dark", v).apply() }
    fun applyOmrUrl(v: String) { omrUrl = v.trim(); prefs.edit().putString("omr_url", omrUrl).apply() }

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
        selP = 0; selM = 0; selN = 0; hasSel = false
        rangeFrom = 0; rangeTo = 0
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

    fun currentNote(): Note? { rev; return if (hasSel) score?.parts?.getOrNull(selP)?.measures?.getOrNull(selM)?.notes?.getOrNull(selN) else null }

    fun select(p: Int, m: Int, n: Int) { selP = p; selM = m; selN = n; hasSel = true }

    fun moveSel(dir: Int) {
        val ms = score?.parts?.getOrNull(selP)?.measures ?: return
        var m = selM; var n = selN + dir
        if (n < 0) { if (m > 0) { m--; n = maxOf(0, ms[m].notes.size - 1) } else n = 0 }
        else if (n >= ms[m].notes.size) { if (m < ms.size - 1) { m++; n = 0 } else n = ms[m].notes.size - 1 }
        selM = m; selN = n.coerceAtLeast(0)
    }

    /** Teclado: grava a altura na nota selecionada, toca e avança. */
    fun writeNote(midi: Int) {
        if (!hasSel || currentNote() == null) { message = "Toque numa nota ou pausa da pauta para escolher onde escrever."; player.key(midi); return }
        edit { Edit.setPitch(it, selP, selM, selN, midi) }
        player.key(midi)
        moveSel(1)
    }

    /** Toca a partir de [fromQ] (semínimas) ou do começo/trecho; respeita trecho e repetição. */
    fun play(fromQ: Double? = null) {
        val s = score ?: return
        val times = Synth.measureTimes(s, tempo)
        var from = 0.0; var to = Double.MAX_VALUE
        if (rangeFrom > 0) {
            from = times[(rangeFrom - 1).coerceIn(0, times.size - 1)]
            to = times[rangeTo.coerceIn(1, times.size - 1)]
        }
        if (fromQ != null) {
            val t = fromQ * 60.0 / tempo
            val endT = if (to > 1e8) times.last() else to
            if (t >= from && t < endT) from = t
        }
        player.start(s, tempo, volumes.map { it.toDouble() }, from, to, loop)
    }

    /** Se estiver tocando, recomeça do ponto atual (usado ao mudar andamento ou volume). */
    fun restartIfPlaying() {
        val p = player
        if (p.playing) play(p.posSec * p.usedTempo / 60.0)
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
        try { open(MusicXml.parseAny(f.readBytes()), f.nameWithoutExtension); goStudio() } catch (e: Exception) { message = "Não foi possível abrir: ${e.message}" }
    }

    fun deleteProject(f: File) { f.delete(); rev++ }

    /** Abre um arquivo escolhido: MusicXML/MXL direto; PDF e imagem passam pelo servidor de leitura (OMR). */
    fun importUri(cr: ContentResolver, uri: Uri) {
        val bytes = try { cr.openInputStream(uri)!!.use { it.readBytes() } } catch (e: Exception) { message = "Não consegui abrir o arquivo (${e.message})."; return }
        val kind = sniff(bytes)
        when (kind) {
            "pdf", "image" -> readWithServer(bytes, kind)
            "midi" -> message = "Importar MIDI ainda não está disponível. Use MusicXML (.musicxml, .xml, .mxl)."
            else -> try {
                val s = MusicXml.parseAny(bytes)
                open(s, null)
                val issues = Validate.measureDurations(s)
                message = "Aberto: ${s.title}" + if (issues.isNotEmpty()) " (${issues.size} compasso(s) com duração diferente do compasso)" else ""
                goStudio()
            } catch (e: Exception) {
                message = "Não foi possível ler o arquivo: ${e.message ?: "formato desconhecido"}"
            }
        }
    }

    private fun sniff(b: ByteArray): String {
        fun at(i: Int) = if (i < b.size) b[i].toInt() and 255 else -1
        return when {
            at(0) == '%'.code && at(1) == 'P'.code && at(2) == 'D'.code && at(3) == 'F'.code -> "pdf"
            at(0) == 0x89 && at(1) == 'P'.code && at(2) == 'N'.code -> "image"
            at(0) == 0xFF && at(1) == 0xD8 -> "image"
            at(0) == 'M'.code && at(1) == 'T'.code && at(2) == 'h'.code && at(3) == 'd'.code -> "midi"
            else -> "xml"
        }
    }

    private fun readWithServer(bytes: ByteArray, kind: String) {
        if (omrUrl.isBlank()) { needOmr = true; return }
        importing = true
        thread {
            var out: String? = null
            var err: String? = null
            try {
                val c = URL(omrUrl).openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true
                c.connectTimeout = 15000; c.readTimeout = 300000
                c.setRequestProperty("Content-Type", if (kind == "pdf") "application/pdf" else "application/octet-stream")
                c.outputStream.use { it.write(bytes) }
                val code = c.responseCode
                val body = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
                if (code in 200..299) {
                    val s = MusicXml.parseAny(body)
                    Handler(Looper.getMainLooper()).post { importing = false; open(s, null); message = "Partitura lida: ${s.title}. Confira as notas — leitura óptica pode errar."; goStudio() }
                    return@thread
                }
                err = "O servidor respondeu $code: " + String(body).take(200)
            } catch (e: Exception) { err = "Não consegui falar com o servidor de leitura (${e.message})" }
            out = err
            Handler(Looper.getMainLooper()).post { importing = false; message = out }
        }
    }
}
