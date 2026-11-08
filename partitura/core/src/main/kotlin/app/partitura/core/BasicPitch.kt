package app.partitura.core

import kotlin.math.ceil

/**
 * Saída de uma janela do modelo Basic Pitch (Spotify, Apache-2.0):
 * 172 quadros × 88 notas (MIDI 21..108) para o mapa de notas e de inícios.
 */
class BpWindow(val note: Array<FloatArray>, val onset: Array<FloatArray>)

/** Executa o modelo numa janela de 43844 amostras a 22050 Hz. No Android: TFLite. */
fun interface BpRunner {
    fun run(window: FloatArray): BpWindow
}

/**
 * Basic Pitch: janelamento, montagem dos quadros e extração de notas.
 * Porta do pipeline oficial; o modelo em si roda fora do core.
 */
object BasicPitch {
    const val SR = 22050
    const val NS = 43844
    const val HOP = 256
    const val OVL = 30 * HOP
    const val STEP = NS - OVL
    const val WIN_FRAMES = 172
    const val TRIM = 15
    const val NOTES = 88

    /** Janelas de entrada para um áudio já em 22050 Hz. */
    fun windows(x: FloatArray): List<FloatArray> {
        val lead = OVL / 2
        val len = lead + x.size
        val nwin = maxOf(1, ceil(len.toDouble() / STEP).toInt())
        val out = ArrayList<FloatArray>(nwin)
        for (w in 0 until nwin) {
            val seg = FloatArray(NS)
            val a = w * STEP
            for (i in 0 until NS) {
                val j = a + i - lead
                if (j >= 0 && j < x.size) seg[i] = x[j]
            }
            out.add(seg)
        }
        return out
    }

    /** Junta as janelas (descarta 15 quadros de cada ponta) e corta ao tamanho do áudio. */
    fun assemble(res: List<BpWindow>, nSamples: Int): Pair<Array<FloatArray>, Array<FloatArray>> {
        val nf = nSamples / HOP
        val fr = ArrayList<FloatArray>()
        val on = ArrayList<FloatArray>()
        for (r in res) {
            for (k in TRIM until WIN_FRAMES - TRIM) {
                fr.add(r.note[k]); on.add(r.onset[k])
            }
        }
        val n = minOf(nf, fr.size)
        return Pair(Array(n) { fr[it] }, Array(n) { on[it] })
    }

    fun frameTime(frame: Int): Double =
        frame.toDouble() * HOP / SR - (frame / WIN_FRAMES) * 0.0103

    /** Extração polifônica (output_to_notes_polyphonic) + truque melodia. */
    fun notes(
        frames: Array<FloatArray>, onsets: Array<FloatArray>,
        onsetThresh: Float = 0.5f, frameThresh: Float = 0.4f, minLen: Int = 13, tol: Int = 11,
    ): List<NoteEvent> {
        val nF = frames.size
        if (nF < 3) return emptyList()
        val rem = Array(nF) { frames[it].copyOf() }
        val out = ArrayList<NoteEvent>()
        fun mean(s: Int, e: Int, f: Int): Double {
            var a = 0.0
            for (i in s until e) a += frames[i][f]
            return a / (e - s)
        }
        fun clear(i: Int, f: Int) {
            rem[i][f] = 0f
            if (f < NOTES - 1) rem[i][f + 1] = 0f
            if (f > 0) rem[i][f - 1] = 0f
        }
        // picos de início (máximos locais estritos no tempo), do fim para o começo
        for (s in nF - 2 downTo 1) {
            for (f in NOTES - 1 downTo 0) {
                val v = onsets[s][f]
                if (v < onsetThresh) continue
                if (!(v > onsets[s - 1][f] && v > onsets[s + 1][f])) continue
                if (s >= nF - 1) continue
                var i = s + 1
                var k = 0
                while (i < nF - 1 && k < tol) {
                    k = if (rem[i][f] < frameThresh) k + 1 else 0
                    i++
                }
                i -= k
                if (i - s <= minLen) continue
                for (q in s until i) clear(q, f)
                out.add(NoteEvent(frameTime(s), frameTime(i), f + 21, mean(s, i, f)))
            }
        }
        // notas sem início marcado: maiores energias restantes primeiro
        var cnt = 0
        for (i in 0 until nF) for (f in 0 until NOTES) if (rem[i][f] > frameThresh) cnt++
        val cand = LongArray(cnt)
        var c = 0
        for (i in 0 until nF) for (f in 0 until NOTES) if (rem[i][f] > frameThresh) {
            cand[c++] = i.toLong() * NOTES + f
        }
        val order = cand.sortedWith(compareBy<Long>({ -rem[(it / NOTES).toInt()][(it % NOTES).toInt()] }, { it }))
        for (code in order) {
            val im = (code / NOTES).toInt()
            val f = (code % NOTES).toInt()
            if (rem[im][f] <= frameThresh) continue
            rem[im][f] = 0f
            var i = im + 1
            var k = 0
            while (i < nF - 1 && k < tol) {
                k = if (rem[i][f] < frameThresh) k + 1 else 0
                clear(i, f)
                i++
            }
            val ie = i - 1 - k
            i = im - 1
            k = 0
            while (i > 0 && k < tol) {
                k = if (rem[i][f] < frameThresh) k + 1 else 0
                clear(i, f)
                i--
            }
            val st = i + 1 + k
            if (ie - st <= minLen || st < 0 || ie > nF) continue
            out.add(NoteEvent(frameTime(st), frameTime(ie), f + 21, mean(st, ie, f)))
        }
        out.sortBy { it.start }
        return out
    }

    /** Áudio qualquer → notas. [progress] vai de 0 a 1. */
    fun transcribe(audio: FloatArray, sr: Int, runner: BpRunner, progress: (Float) -> Unit = {}): List<NoteEvent> {
        val x = if (sr == SR) audio else Dsp.resample(audio, sr, SR)
        val ws = windows(x)
        val res = ArrayList<BpWindow>(ws.size)
        for ((i, w) in ws.withIndex()) {
            res.add(runner.run(w))
            progress((i + 1f) / ws.size)
        }
        val (fr, on) = assemble(res, x.size)
        return notes(fr, on)
    }

    /** Normaliza o volume (pico em ~0,9) para dar ao modelo um nível estável. */
    fun normalize(x: FloatArray): FloatArray {
        var p = 0f
        for (v in x) { val a = if (v < 0) -v else v; if (a > p) p = a }
        if (p < 1e-4f) return x
        val g = 0.9f / p
        return FloatArray(x.size) { x[it] * g }
    }
}
