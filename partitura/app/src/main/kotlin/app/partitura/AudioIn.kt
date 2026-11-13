package app.partitura

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import app.partitura.core.*
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Gravação do microfone (mono, 44,1 kHz). Tudo fica na memória do aparelho. */
class MicRecorder {
    val rate = 44100
    @Volatile var level = 0f
    @Volatile var seconds = 0.0
    @Volatile private var running = false
    private var thread: Thread? = null
    private var buf = FloatArray(rate * 30)
    private var n = 0
    @Volatile var error: String? = null

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true; n = 0; error = null
        thread = Thread {
            var rec: AudioRecord? = null
            try {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) throw IllegalStateException("O aparelho não aceita gravar a ${rate} Hz.")
                rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate))
                if (rec.state != AudioRecord.STATE_INITIALIZED) throw IllegalStateException("Não consegui abrir o microfone.")
                rec.startRecording()
                val chunk = ShortArray(2048)
                while (running) {
                    val r = rec.read(chunk, 0, chunk.size)
                    if (r <= 0) continue
                    var peak = 0f
                    synchronized(this) {
                        if (n + r > buf.size) {
                            if (buf.size >= rate * 60 * 10) { running = false; return@synchronized }
                            buf = buf.copyOf(buf.size * 2)
                        }
                        for (i in 0 until r) { val v = chunk[i] / 32768f; buf[n + i] = v; val a = abs(v); if (a > peak) peak = a }
                        n += r
                    }
                    level = peak; seconds = n.toDouble() / rate
                }
            } catch (e: Throwable) {
                error = e.message ?: e.toString()
            } finally {
                try { rec?.stop() } catch (_: Throwable) {}
                try { rec?.release() } catch (_: Throwable) {}
                running = false; level = 0f
            }
        }.also { it.start() }
    }

    fun stop(): FloatArray {
        running = false
        thread?.join(1500)
        return synchronized(this) { buf.copyOf(n) }
    }

    val isRunning get() = running
}

/** Lê um arquivo de áudio (WAV, MP3, M4A/AAC, OGG, FLAC…) com os decodificadores do próprio Android. Mono, taxa original. */
object AudioFile {
    const val MAX_SECONDS = 600

    fun read(ctx: Context, uri: Uri): Pair<FloatArray, Int> {
        // WAV simples: leitor próprio
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                val head = ByteArray(12)
                val got = ins.read(head)
                if (got == 12 && String(head, 0, 4) == "RIFF" && String(head, 8, 4) == "WAVE") {
                    val rest = ins.readBytes()
                    if (rest.size < 200L * 1024 * 1024) return Dsp.readWav(head + rest)
                }
            }
        } catch (_: OutOfMemoryError) { throw IllegalStateException("Arquivo grande demais para ler de uma vez.") } catch (_: Exception) {}
        return decode(ctx, uri)
    }

    private fun decode(ctx: Context, uri: Uri): Pair<FloatArray, Int> {
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        var fmt: MediaFormat? = null
        for (i in 0 until ex.trackCount) {
            val f = ex.getTrackFormat(i)
            if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) { track = i; fmt = f; break }
        }
        if (track < 0 || fmt == null) { ex.release(); throw IllegalStateException("Esse arquivo não tem áudio que eu consiga ler.") }
        ex.selectTrack(track)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var out = FloatArray(rate * 30)
        var n = 0
        val info = MediaCodec.BufferInfo()
        var inDone = false
        var outDone = false
        try {
            while (!outDone) {
                if (!inDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val b = codec.getInputBuffer(ii)!!
                        val sz = ex.readSampleData(b, 0)
                        if (sz < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                        else { codec.queueInputBuffer(ii, 0, sz, ex.sampleTime, 0); ex.advance() }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    ob.position(info.offset); ob.limit(info.offset + info.size)
                    val sb = ob.order(ByteOrder.nativeOrder()).asShortBuffer()
                    val frames = sb.remaining() / ch
                    if (n + frames > out.size) out = out.copyOf(maxOf(out.size * 2, n + frames))
                    for (i in 0 until frames) {
                        var a = 0f
                        for (c in 0 until ch) a += sb.get() / 32768f
                        out[n++] = a / ch
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    if (n > rate * MAX_SECONDS) outDone = true
                } else if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val nf = codec.outputFormat
                    rate = nf.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    ch = nf.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
        } finally {
            try { codec.stop() } catch (_: Throwable) {}
            codec.release(); ex.release()
        }
        return out.copyOf(n) to rate
    }
}

/** Basic Pitch em TFLite, no aparelho. Se o modelo não carregar, quem chama usa o motor espectral próprio. */
class BasicPitchModel(ctx: Context) : BpRunner {
    private val interp: Interpreter
    private val noteIdx: Int
    private val onsetIdx: Int
    private val outShapes: List<IntArray>

    init {
        val bytes = ctx.assets.open("nmp.tflite").use { it.readBytes() }
        val bb = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        bb.put(bytes); bb.rewind()
        interp = Interpreter(bb, Interpreter.Options().setNumThreads(2))
        val cnt = interp.outputTensorCount
        outShapes = (0 until cnt).map { interp.getOutputTensor(it).shape() }
        var ni = -1; var oi = -1
        for (i in 0 until cnt) {
            val name = interp.getOutputTensor(i).name()
            if (outShapes[i].last() == 88) { if (name.endsWith(":1")) ni = i else if (name.endsWith(":2")) oi = i }
        }
        if (ni < 0 || oi < 0) {   // sem nomes confiáveis: as duas saídas com 88 colunas, na ordem
            val idx = (0 until cnt).filter { outShapes[it].last() == 88 }
            if (idx.size < 2) throw IllegalStateException("Modelo inesperado")
            ni = idx[0]; oi = idx[1]
        }
        noteIdx = ni; onsetIdx = oi
    }

    override fun run(window: FloatArray): BpWindow {
        val inp = ByteBuffer.allocateDirect(window.size * 4).order(ByteOrder.nativeOrder())
        for (v in window) inp.putFloat(v)
        inp.rewind()
        val outs = HashMap<Int, Any>()
        val bufs = HashMap<Int, ByteBuffer>()
        for (i in outShapes.indices) {
            val size = outShapes[i].fold(1) { a, b -> a * b }
            val b = ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder())
            bufs[i] = b; outs[i] = b
        }
        interp.runForMultipleInputsOutputs(arrayOf<Any>(inp), outs)
        fun grab(i: Int): Array<FloatArray> {
            val b = bufs[i]!!; b.rewind()
            val fb = b.asFloatBuffer()
            return Array(BasicPitch.WIN_FRAMES) { FloatArray(BasicPitch.NOTES).also { r -> fb.get(r) } }
        }
        return BpWindow(grab(noteIdx), grab(onsetIdx))
    }

    fun close() { try { interp.close() } catch (_: Throwable) {} }
}

/** Resultado de uma "escuta". [engine] diz qual motor foi usado, para a tela ser honesta. */
class Heard(val notes: List<NoteEvent>, val engine: String, val seconds: Double)

object Listener {
    /** Áudio → notas. Tenta o Basic Pitch; se falhar por qualquer motivo, usa o motor espectral próprio. */
    fun hear(ctx: Context, audio: FloatArray, sr: Int, profile: KeyboardProfile? = null, progress: (Float, String) -> Unit): Heard {
        val x0 = BasicPitch.normalize(audio)
        val secs = x0.size.toDouble() / sr
        if (profile != null) {
            try {
                progress(0f, "Moldes do seu teclado")
                val notes = NmfTranscriber(profile).transcribe(x0, sr) { progress(it, "Moldes do seu teclado") }
                return Heard(notes, "Seu teclado calibrado (${profile.label.ifBlank { "${profile.keys} teclas" }})", secs)
            } catch (_: Throwable) { /* cai para os outros motores */ }
        }
        // afinação: teclado/piano fora de 440 Hz faz os motores genéricos errarem notas inteiras; mede e corrige antes de escutar
        val cents = try { Tuning.correction(x0, sr) } catch (_: Throwable) { 0.0 }
        val x = if (cents != 0.0) Tuning.retune(x0, sr, cents) else x0
        val k = Tuning.factor(cents)
        val tuneMsg = if (cents != 0.0) ", afinação corrigida ${if (cents > 0) "+" else ""}${cents.toInt()} centésimos" else ""
        fun back(n: List<NoteEvent>): List<NoteEvent> {
            val clean = NoteCleanup.apply(n)
            return if (cents == 0.0) clean else clean.map { NoteEvent(it.start / k, it.end / k, it.midi, it.amp) }
        }
        try {
            val m = BasicPitchModel(ctx)
            try {
                progress(0f, "Rede neural no aparelho")
                val notes = BasicPitch.transcribe(x, sr, m) { progress(it, "Rede neural no aparelho") }
                return Heard(back(notes), "Basic Pitch (rede neural no aparelho$tuneMsg)", secs)
            } finally { m.close() }
        } catch (e: Throwable) {
            progress(0f, "Motor próprio")
            val notes = SpectralTranscriber().transcribe(x, sr) { progress(it, "Motor próprio") }
            return Heard(back(notes), "Motor espectral próprio (o modelo não carregou: ${e.javaClass.simpleName}$tuneMsg)", secs)
        }
    }
}
