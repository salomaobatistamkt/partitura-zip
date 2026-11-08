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

private val ATTRS = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
private val FMT = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Synth.RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()

private fun newTrack(frames: Int): AudioTrack =
    AudioTrack.Builder().setAudioAttributes(ATTRS).setAudioFormat(FMT)
        .setBufferSizeInBytes(maxOf(frames * 2, 4096)).setTransferMode(AudioTrack.MODE_STATIC).build()

private fun newStreamTrack(): AudioTrack {
    val min = AudioTrack.getMinBufferSize(Synth.RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
    return AudioTrack.Builder().setAudioAttributes(ATTRS).setAudioFormat(FMT)
        .setBufferSizeInBytes(maxOf(min * 2, Synth.RATE * 2)).setTransferMode(AudioTrack.MODE_STREAM).build()
}

/**
 * Toca partituras. O som é gerado em pedaços e enviado enquanto toca, então começa quase na hora
 * mesmo em músicas longas; a posição acompanha o áudio para a tela seguir a música.
 */
class Player {
    private val main = Handler(Looper.getMainLooper())
    private var track: AudioTrack? = null
    private var token = 0
    private var frames = 0
    private var loop = false
    private var fromSec = 0.0
    private var tl: Playlist.Timeline? = null
    var playing by mutableStateOf(false)
    var paused by mutableStateOf(false)
    var busy by mutableStateOf(false)
    /** "music" = a partitura; "click" = só o metrônomo da tela de regência. */
    var kind by mutableStateOf("music")
    /** Posição atual na música, em segundos (no andamento usado para sintetizar). */
    var posSec by mutableDoubleStateOf(0.0)
    var usedTempo by mutableIntStateOf(100)
    val active: Boolean get() = playing || paused
    /** Posição atual na partitura em semínimas (sem repetições): acompanha ritornelos, ritardandos e mudanças de andamento. */
    val posQ: Double get() { val t = tl; return if (t == null || kind != "music") posSec * usedTempo / 60.0 else t.scoreQ(posSec) }

    private val keyCache = HashMap<String, ShortArray>()
    private val keyTracks = ArrayList<AudioTrack>()

    fun stop() {
        token++
        val t = track; track = null
        if (t != null) { try { t.stop() } catch (_: Exception) {}; try { t.release() } catch (_: Exception) {} }
        playing = false; paused = false; busy = false
    }

    fun start(score: Score, tempo: Int, volumes: List<Double>, from: Double, to: Double, loop: Boolean, mix: Mix? = null) {
        stop()
        val my = token
        busy = true; kind = "music"
        val snapshot = score.copyDeep()
        val tline = Playlist.timeline(snapshot, tempo, true)
        thread {
            var t: AudioTrack? = null
            try {
                val prep = Synth.prepare(snapshot, tempo, volumes, from, to, true, mix)
                val limit = if (to < 1e8) ((to - from) * Synth.RATE).toInt() + (if (loop) 0 else Synth.RATE / 3) else prep.total
                val total = minOf(prep.total, limit).coerceAtLeast(1)
                val cache = ShortArray(total)
                t = newStreamTrack()
                val tr = t
                var written = 0
                var started = false
                while (written < total) {
                    if (my != token) return@thread
                    val c = prep.next() ?: break
                    val n = minOf(c.size, total - written)
                    System.arraycopy(c, 0, cache, written, n)
                    var o = 0
                    while (o < n) {
                        if (my != token) return@thread
                        val w = tr.write(c, o, n - o)
                        if (w < 0) return@thread
                        o += w
                    }
                    written += n
                    if (!started) {
                        started = true
                        main.post {
                            if (my != token) { try { tr.release() } catch (_: Exception) {}; return@post }
                            track = tr; tl = tline; frames = total; this.loop = loop; fromSec = from; usedTempo = tempo
                            posSec = from; playing = true; paused = false; busy = false
                            try { tr.play() } catch (_: Exception) {}
                            poll(my, tr)
                        }
                    }
                }
                // laço: repete o que já está pronto, sem sintetizar de novo
                while (loop && my == token) {
                    var o = 0
                    while (o < total) {
                        if (my != token) return@thread
                        val w = tr.write(cache, o, minOf(total - o, 16384))
                        if (w < 0) return@thread
                        o += w
                    }
                }
            } catch (e: Exception) {
                main.post { if (my == token) { busy = false; playing = false } }
            } finally {
                val tt = t
                if (tt != null && my != token) { try { tt.release() } catch (_: Exception) {} }
            }
        }
    }

    /** Metrônomo em laço: [beats] cliques de [beatSec]; a posição segue em "segundos de música" ao andamento [tempo]. */
    fun startClick(beats: Int, beatSec: Double, tempo: Int) = startClickLens(List(beats) { 1.0 }, beatSec, tempo)

    /** Metrônomo com gestos desiguais: [lens] em semínimas, [quarterSec] = duração de uma semínima. */
    fun startClickLens(lens: List<Double>, quarterSec: Double, tempo: Int) {
        stop()
        val my = token
        kind = "click"
        val pcm = Synth.clickLoopLens(lens, quarterSec)
        try {
            val t = newTrack(pcm.size)
            t.write(pcm, 0, pcm.size)
            t.setLoopPoints(0, pcm.size, -1)
            track = t; tl = null; frames = pcm.size; loop = true; fromSec = 0.0; usedTempo = tempo
            posSec = 0.0; playing = true; paused = false; busy = false
            t.play()
            poll(my, t)
        } catch (e: Exception) { playing = false }
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

    /** Toca uma nota isolada (teclado) com o timbre do instrumento escolhido. */
    fun key(midi: Int, timbre: String = "piano") {
        val pcm = keyCache.getOrPut("$timbre/$midi") {
            val p = Spell.forMidi(midi, 0)
            val sc = Theory.toScore("k", listOf(p), 1.0, 0); sc.parts[0].inst = timbre
            Synth.render(sc, 120, listOf(1.0)).let { it.copyOf(minOf(it.size, Synth.RATE * 2)) }
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
    /** Compasso copiado (para colar em outro compasso de mesma duração). */
    var clipNotes by mutableStateOf<List<Note>?>(null)
    var dark by mutableStateOf(prefs.getBoolean("dark", true))
        private set
    /** Endereço do servidor de leitura (sem /omr) e chave de acesso opcional. */
    var serverUrl by mutableStateOf(cleanServer(prefs.getString("omr_url", "") ?: ""))
        private set
    /** Aparelho pareado com o servidor? (a chave em si fica criptografada no Keystore e só é lida na hora de usar) */
    var paired by mutableStateOf(!prefs.getString("omr_tok_enc", null).isNullOrBlank())
        private set
    /** Tela Servidor destrancada até este instante (ms). Trancada de novo ao sair do app. */
    var serverUnlockedUntil by mutableLongStateOf(0L)
        private set
    var requestUnlock: () -> Unit = {}
    val serverUnlocked: Boolean get() = System.currentTimeMillis() < serverUnlockedUntil
    fun unlockServer() { serverUnlockedUntil = System.currentTimeMillis() + 5 * 60_000L }
    fun lockServer() { serverUnlockedUntil = 0L }
    val hasPin: Boolean get() = !prefs.getString("srv_pin", null).isNullOrBlank()
    fun setPin(pin: String) { prefs.edit().putString("srv_pin", Vault.hashPin(pin)).apply() }
    fun tryPin(pin: String): Boolean = Vault.checkPin(pin, prefs.getString("srv_pin", "") ?: "").also { if (it) unlockServer() }
    private fun token(): String = Vault.decrypt(prefs.getString("omr_tok_enc", "") ?: "") ?: ""
    /** Texto do progresso da leitura de PDF/foto (para o aviso na tela). */
    var importStage by mutableStateOf("")
    var message by mutableStateOf<String?>(null)
    var projectName by mutableStateOf<String?>(null)
    var tempo by mutableIntStateOf(100)
    val volumes = mutableStateListOf<Float>()
    /** Mesa de som: vozes/instrumentos com volume, mudo, solo e timbre. [mixRev] muda quando a mesa é alterada (para a tela atualizar). */
    var mix by mutableStateOf<Mix?>(null)
    var mixRev by mutableIntStateOf(0)
    fun rebuildMix() { val sc = score ?: return; val nm = Tracks.derive(sc); nm.inherit(mix); mix = nm; mixRev++ }
    fun mixChanged() { mixRev++ }
    val player = Player()

    var editMode by mutableStateOf(false)
    var drawTool by mutableStateOf(false)
    var loop by mutableStateOf(false)
    var rangeFrom by mutableIntStateOf(0)   // 0 = música toda
    var rangeTo by mutableIntStateOf(0)
    var importing by mutableStateOf(false)
    var needOmr by mutableStateOf(false)
    /** PDF/foto escolhido na tela Início: a aba Ler lê no aparelho (motor próprio, sem servidor). */
    var scanUri by mutableStateOf<Uri?>(null)
    var scanIsPdf by mutableStateOf(true)
    var goScan: () -> Unit = {}
    var goStudio: () -> Unit = {}
    var goServer: () -> Unit = {}
    var goHome: () -> Unit = {}
    /** Duração usada pelas teclas ao escrever (em semínimas) e se é pontuada. */
    var inputDur by mutableDoubleStateOf(1.0)
    var inputDot by mutableStateOf(false)
    val effDur: Double get() = if (inputDot && inputDur > 0.25) inputDur * 1.5 else inputDur

    var openPicker: () -> Unit = {}
    var saveAs: (String, ByteArray) -> Unit = { _, _ -> }

    /** Menu lateral: -1 automático, 0 aberto, 1 compacto (só ícones), 2 escondido. */
    var sideMode by mutableIntStateOf(prefs.getInt("side", -1))
    fun applySide(v: Int) { sideMode = v; prefs.edit().putInt("side", v).apply() }
    fun applyDark(v: Boolean) { dark = v; prefs.edit().putBoolean("dark", v).apply() }
    init {
        // migração: a chave antiga (texto puro) vira texto cifrado e o original é apagado
        val old = prefs.getString("omr_token", null)
        if (old != null) {
            val enc = if (old.isNotBlank()) Vault.encrypt(old) else null
            prefs.edit().remove("omr_token").apply { if (enc != null) putString("omr_tok_enc", enc) }.apply()
            paired = enc != null
        }
    }
    fun saveServerUrl(url: String) { serverUrl = cleanServer(url); prefs.edit().putString("omr_url", serverUrl).apply() }
    /** Esquece a chave e o endereço neste aparelho (não avisa o servidor). */
    fun forgetServer() { prefs.edit().remove("omr_tok_enc").remove("omr_url").apply(); paired = false; serverUrl = "" }

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
        mix = null; rebuildMix()
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
        block(s); fixSel(); syncVolumes(); rev++; autosave()
    }

    fun replace(n: Score) {
        val s = score ?: return
        undo.addLast(s.copyDeep()); redo.clear()
        n.tempo = s.tempo
        score = n; fixSel(); syncVolumes(); rev++; autosave()
    }

    fun undo() { val s = score ?: return; val p = undo.removeLastOrNull() ?: return; redo.addLast(s.copyDeep()); score = p; fixSel(); syncVolumes(); rev++; autosave() }
    fun redo() { val s = score ?: return; val p = redo.removeLastOrNull() ?: return; undo.addLast(s.copyDeep()); score = p; fixSel(); syncVolumes(); rev++; autosave() }

    fun currentNote(): Note? { rev; return if (hasSel) score?.parts?.getOrNull(selP)?.measures?.getOrNull(selM)?.notes?.getOrNull(selN) else null }

    fun select(p: Int, m: Int, n: Int) { selP = p; selM = m; selN = n; hasSel = true }

    fun moveSel(dir: Int) {
        val ms = score?.parts?.getOrNull(selP)?.measures ?: return
        var m = selM; var n = selN + dir
        if (n < 0) { if (m > 0) { m--; n = maxOf(0, ms[m].notes.size - 1) } else n = 0 }
        else if (n >= ms[m].notes.size) { if (m < ms.size - 1) { m++; n = 0 } else n = ms[m].notes.size - 1 }
        selM = m; selN = n.coerceAtLeast(0)
    }

    private fun syncVolumes() {
        val n = score?.parts?.size ?: 0
        while (volumes.size < n) volumes.add(1f)
        while (volumes.size > n) volumes.removeAt(volumes.size - 1)
        rebuildMix()
    }

    fun timbreOf(p: Int): String = score?.parts?.getOrNull(p)?.inst ?: "piano"

    /** Primeira posição livre depois da última nota da parte (para continuar escrevendo de onde parou). */
    fun endPosition(p: Int): Pair<Int, Int> {
        val ms = score?.parts?.getOrNull(p)?.measures ?: return 0 to 0
        for (m in ms.indices.reversed()) {
            val notes = ms[m].notes
            val last = notes.indexOfLast { it.pitch != null }
            if (last >= 0) return if (last + 1 < notes.size) m to last + 1 else if (m + 1 < ms.size) m + 1 to 0 else m to last
        }
        return 0 to 0
    }

    /** Escreve na posição selecionada (ou depois da última nota), com a duração escolhida, e avança. Cria compassos se precisar. */
    private fun enterAtCursor(midi: Int?) {
        val s = score ?: return
        if (!hasSel) { val e = endPosition(selP); select(selP, e.first, e.second) }
        var next = selM to selN
        edit { next = Edit.enter(it, selP, selM, selN, midi, effDur) }
        selM = next.first; selN = next.second; hasSel = true
        fixSel()
    }

    /** Tecla do piano em modo de edição: escreve a nota, toca o som do instrumento e avança. */
    fun writeNote(midi: Int) {
        if (score == null) return
        player.key(midi, timbreOf(selP))
        enterAtCursor(midi)
    }

    fun writeRest() { enterAtCursor(null) }

    fun addInstrument(inst: Instrument) {
        var idx = 0
        edit { idx = Edit.addInstrument(it, inst) }
        selP = idx; selM = 0; selN = 0; hasSel = true
        message = "${inst.name} adicionado. Ligue o modo de escrita e toque nas teclas do piano."
    }

    fun removeInstrument(p: Int) {
        val s = score ?: return
        val name = s.parts.getOrNull(p)?.name?.substringBefore(" (pauta") ?: return
        var ok = false
        edit { ok = Edit.removeInstrument(it, p) }
        message = if (ok) "$name removido." else "A partitura precisa de pelo menos um instrumento."
    }

    fun setSound(p: Int, timbre: String) { edit { Edit.setSound(it, p, timbre) } }

    // ---- salvamento automático (para não perder uma música em andamento) ----
    private val autoHandler = Handler(Looper.getMainLooper())
    private val autoRun = Runnable {
        val s = score ?: return@Runnable
        try {
            File(app.filesDir, "autosave.musicxml").writeText(MusicXml.write(s))
            prefs.edit().putString("autosave_name", projectName ?: "").apply()
        } catch (_: Exception) {}
    }
    private fun autosave() { autoHandler.removeCallbacks(autoRun); autoHandler.postDelayed(autoRun, 1200) }
    fun autosaveInfo(): String? { rev; val f = File(app.filesDir, "autosave.musicxml"); return if (f.exists() && f.length() > 0) f.lastModified().toString() else null }
    fun resumeAutosave() {
        try {
            val s = MusicXml.parseAny(File(app.filesDir, "autosave.musicxml").readBytes())
            open(s, prefs.getString("autosave_name", "")?.ifBlank { null }); goStudio()
        } catch (e: Exception) { message = "Não foi possível retomar: ${e.message}" }
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
            val t = Playlist.timeline(s, tempo, true).secOfScoreQ(fromQ)
            val endT = if (to > 1e8) times.last() else to
            if (t >= from && t < endT) from = t
        }
        rebuildMix()
        player.start(s, tempo, volumes.map { it.toDouble() }, from, to, loop, mix?.snapshot())
    }

    /** Se estiver tocando, recomeça do ponto atual (usado ao mudar andamento ou volume). */
    fun restartIfPlaying() {
        val p = player
        if (p.playing) play(p.posQ)
    }

    /** Gera o áudio da partitura inteira (com repetições, dinâmicas e a mesa de som) e pede onde salvar. [format]: "wav" ou "m4a". */
    var exporting by mutableStateOf(false)
    fun exportAudio(format: String) {
        val s = score ?: return
        if (exporting) return
        exporting = true; message = "Gerando o áudio… pode levar alguns segundos."
        val snap = s.copyDeep(); val t = tempo; val v = volumes.map { it.toDouble() }; rebuildMix(); val mx = mix?.snapshot()
        val base = s.title.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().ifBlank { "partitura" }
        val handler = Handler(Looper.getMainLooper())
        thread {
            try {
                val pcm = Synth.render(snap, t, v, mix = mx)
                val bytes = if (format == "m4a") AudioExport.m4a(pcm, Synth.RATE, app.cacheDir) else Synth.wavBytes(pcm)
                handler.post { exporting = false; saveAs("$base.$format", bytes) }
            } catch (e: Throwable) {
                handler.post { exporting = false; message = "Não foi possível gerar o áudio: ${e.message}" }
            }
        }
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
            "pdf", "image" -> { scanUri = uri; scanIsPdf = kind == "pdf"; goScan() }
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

    private fun post(main: (() -> Unit)) = Handler(Looper.getMainLooper()).post { main() }

    private class Http(val code: Int, val body: ByteArray) { val text get() = String(body) }

    private fun call(method: String, url: String, token: String?, body: ByteArray? = null, ctype: String = "application/octet-stream", timeout: Int = 15000): Http {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 8000; c.readTimeout = timeout
        if (!token.isNullOrBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.doOutput = true; c.setRequestProperty("Content-Type", ctype); c.setFixedLengthStreamingMode(body.size); c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val data = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return Http(code, data)
    }

    private fun explain(e: Exception): String = when (e) {
        is javax.net.ssl.SSLException -> "Problema de certificado HTTPS (${e.message?.take(80)})."
        is java.net.UnknownHostException -> "Não achei esse endereço. Confira se está certo e se o tablet está na mesma rede do computador."
        is java.net.SocketTimeoutException -> "O servidor não respondeu a tempo. Confira se está ligado e se o tablet está na mesma rede."
        is java.net.ConnectException -> "Conexão recusada. O servidor está ligado? A porta está certa?"
        else -> "Não consegui conectar (${e.message?.take(100)})."
    }

    private fun httpError(code: Int, text: String): String = when (code) {
        401 -> "O servidor recusou a chave. Pareie de novo (Ajustes → Servidor privado)."
        429 -> "Muitas tentativas erradas: o servidor bloqueou este aparelho por alguns minutos."
        404 -> "Esse endereço não é do servidor de leitura (erro 404)."
        413 -> "Arquivo grande demais (limite 40 MB)."
        502, 503, 504 -> "O servidor demorou ou está fora do ar (erro $code)."
        else -> "O servidor respondeu $code: " + text.take(200)
    }

    /** Lê PDF/foto pelo servidor privado: envia, acompanha o progresso e abre o MusicXML devolvido. */
    private fun readWithServer(bytes: ByteArray, kind: String) {
        if (serverUrl.isBlank() || !paired) { needOmr = true; return }
        serverProblem(serverUrl)?.let { message = it; return }
        importing = true; importStage = "enviando"
        val base = serverUrl; val token = token()
        thread {
            var err: String? = null
            try {
                val j = call("POST", "$base/jobs", token, bytes, if (kind == "pdf") "application/pdf" else "application/octet-stream", 120000)
                if (j.code != 202) err = httpError(j.code, j.text) else {
                    val id = Regex("\"id\"\\s*:\\s*\"([0-9a-f]+)\"").find(j.text)?.groupValues?.get(1) ?: throw RuntimeException("resposta inesperada")
                    val t0 = System.currentTimeMillis()
                    while (err == null) {
                        Thread.sleep(1500)
                        val st = call("GET", "$base/jobs/$id", token)
                        if (st.code != 200) { err = httpError(st.code, st.text); break }
                        val t = st.text
                        val status = Regex("\"status\"\\s*:\\s*\"(\\w+)\"").find(t)?.groupValues?.get(1)
                        val stage = Regex("\"stage\"\\s*:\\s*\"([^\"]*)\"").find(t)?.groupValues?.get(1) ?: ""
                        val pg = Regex("\"page\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1); val pgs = Regex("\"pages\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1)
                        post { importStage = stage + (if (pg != null && pgs != null && pgs != "1") " · página $pg de $pgs" else "") }
                        if (status == "error") { err = Regex("\"error\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(t)?.groupValues?.get(1)?.replace("\\\"", "\"") ?: "falha na leitura"; break }
                        if (status == "done") {
                            val r = call("GET", "$base/jobs/$id/result", token, timeout = 60000)
                            if (r.code != 200) { err = httpError(r.code, r.text); break }
                            val sc = MusicXml.parseAny(r.body)
                            post { importing = false; open(sc, null); message = "Partitura lida: ${sc.title}. Confira as notas — leitura óptica pode errar."; goStudio() }
                            return@thread
                        }
                        if (System.currentTimeMillis() - t0 > 30 * 60_000L) err = "A leitura passou de 30 minutos; desisti."
                    }
                }
            } catch (e: Exception) { err = explain(e) }
            post { importing = false; message = err }
        }
    }

    /** Pareia com o servidor usando o código mostrado nele; guarda a chave recebida criptografada. */
    fun pairServer(url: String, code: String, done: (ok: Boolean, text: String) -> Unit) {
        val base = cleanServer(url)
        serverProblem(base)?.let { done(false, it); return }
        if (code.filter { it.isLetterOrDigit() }.length < 8) { done(false, "O código tem 8 letras/números (ex.: ABCD-2345)."); return }
        thread {
            var ok = false; var text: String
            try {
                val h = call("GET", "$base/health", null)
                if (h.code != 200 || !h.text.contains("compasso-omr")) { post { done(false, "Esse endereço respondeu, mas não é o servidor do Compasso.") }; return@thread }
                val device = (android.os.Build.MODEL ?: "tablet").replace("\"", "")
                val r = call("POST", "$base/pair", null, "{\"code\":\"${code.trim().uppercase()}\",\"device\":\"$device\"}".toByteArray(), "application/json")
                if (r.code == 200) {
                    val tok = Regex("\"token\"\\s*:\\s*\"([^\"]+)\"").find(r.text)?.groupValues?.get(1)
                    val enc = tok?.let { Vault.encrypt(it) }
                    if (enc == null) text = "Pareou, mas o Android não deixou guardar a chave com segurança. Nada foi salvo."
                    else { prefs.edit().putString("omr_tok_enc", enc).putString("omr_url", base).apply(); ok = true; text = "Pareado! A chave ficou guardada criptografada neste aparelho." }
                } else text = if (r.code == 401) "Código errado ou vencido. Peça um novo no computador: python server.py --new-code" else httpError(r.code, r.text)
            } catch (e: Exception) { text = explain(e) }
            post { if (ok) { serverUrl = base; paired = true }; done(ok, text) }
        }
    }

    /** Testa a conexão com o servidor já pareado. */
    fun testServer(done: (ok: Boolean, text: String) -> Unit) {
        if (serverUrl.isBlank() || !paired) { done(false, "Ainda não pareado."); return }
        serverProblem(serverUrl)?.let { done(false, it); return }
        val base = serverUrl; val tok = token()
        thread {
            var ok = false; var text: String
            try {
                val t0 = System.currentTimeMillis()
                val h = call("GET", "$base/health", null)
                if (h.code != 200 || !h.text.contains("compasso-omr")) text = "O endereço respondeu, mas não é o servidor do Compasso."
                else {
                    val c = call("GET", "$base/check", tok)
                    val ms = System.currentTimeMillis() - t0
                    if (c.code == 200) { ok = true; text = "Conectado (${ms} ms) e a chave confere." } else text = httpError(c.code, c.text)
                }
            } catch (e: Exception) { text = explain(e) }
            post { done(ok, text) }
        }
    }

    /** Desconecta este aparelho: avisa o servidor (se alcançar) e apaga a chave daqui. */
    fun unpairServer(done: (String) -> Unit) {
        val base = serverUrl; val tok = token()
        thread {
            var warn = ""
            try { call("POST", "$base/unpair", tok, ByteArray(0)) } catch (e: Exception) { warn = " (o servidor não foi avisado: ${e.message?.take(60)} — use --revoke-all nele)" }
            post { forgetServer(); done("Aparelho desconectado.$warn") }
        }
    }

    /** Procura servidores do Compasso na rede local (broadcast UDP; só funciona se o servidor foi iniciado com DISCOVERY=1, como nos scripts run-local). */
    fun discoverServers(done: (List<String>) -> Unit) {
        thread {
            val found = LinkedHashSet<String>()
            try {
                java.net.DatagramSocket().use { sock ->
                    sock.broadcast = true; sock.soTimeout = 700
                    val msg = "COMPASSO?".toByteArray()
                    sock.send(java.net.DatagramPacket(msg, msg.size, java.net.InetAddress.getByName("255.255.255.255"), 45678))
                    val end = System.currentTimeMillis() + 2500
                    val buf = ByteArray(512)
                    while (System.currentTimeMillis() < end) {
                        try {
                            val pk = java.net.DatagramPacket(buf, buf.size); sock.receive(pk)
                            val t = String(pk.data, 0, pk.length)
                            if (t.contains("compasso-omr")) {
                                val port = Regex("\"port\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1) ?: "8080"
                                found += "http://${pk.address.hostAddress}:$port"
                            }
                        } catch (_: java.net.SocketTimeoutException) {}
                    }
                }
            } catch (_: Exception) {}
            post { done(found.toList()) }
        }
    }
}
