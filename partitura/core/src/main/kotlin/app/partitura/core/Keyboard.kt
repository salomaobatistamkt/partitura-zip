package app.partitura.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Perfil do teclado: o "som" de cada tecla, aprendido de uma gravação do próprio instrumento tocando uma nota de cada vez, do grave ao agudo.
 * Cada tecla vira um molde espectral (energia por quarto de semitom). A escuta depois decompõe o áudio em somas desses moldes (NMF com moldes fixos),
 * em vez de adivinhar notas por um modelo genérico.
 */
class KeyboardProfile(val lowMidi: Int, val highMidi: Int, val templates: Array<FloatArray>, val label: String = "") {
    val keys get() = highMidi - lowMidi + 1

    fun toBytes(): ByteArray {
        val bo = ByteArrayOutputStream(); val d = DataOutputStream(bo)
        d.writeInt(0x4B425031); d.writeInt(lowMidi); d.writeInt(highMidi); d.writeInt(Spec.NB); d.writeUTF(label)
        for (t in templates) for (v in t) d.writeFloat(v)
        d.flush(); return bo.toByteArray()
    }

    companion object {
        fun fromBytes(b: ByteArray): KeyboardProfile? = try {
            val d = DataInputStream(ByteArrayInputStream(b))
            if (d.readInt() != 0x4B425031) null else {
                val lo = d.readInt(); val hi = d.readInt(); val nb = d.readInt(); val label = d.readUTF()
                if (nb != Spec.NB || hi < lo || hi - lo > 120) null
                else KeyboardProfile(lo, hi, Array(hi - lo + 1) { FloatArray(nb) { d.readFloat() } }, label)
            }
        } catch (_: Throwable) { null }

        /** Teclados comuns: nome → (primeira, última) tecla em MIDI (Dó central = 60 = C4, padrão científico). */
        val PRESETS = listOf(
            Triple("49 teclas (C2 a C6)", 36, 84),
            Triple("61 teclas (C2 a C7)", 36, 96),
            Triple("76 teclas (E1 a G7)", 28, 103),
            Triple("88 teclas (A0 a C8)", 21, 108),
        )
        private val NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        fun noteName(m: Int) = NAMES[Math.floorMod(m, 12)] + (m / 12 - 1)
    }
}

/** Front-end comum: áudio → espectrograma em escala logarítmica (4 divisões por semitom), com compressão por raiz. */
object Spec {
    @JvmField var POW = 0.6
    @JvmField var CUT = 0.06f
    const val SR = 22050; const val N = 4096; const val HOP = 512; const val NFFT = 8192
    const val MIDI0 = 21; const val MIDI1 = 108; const val PER = 4
    const val NB = (MIDI1 - MIDI0) * PER + 1
    const val HOP_SEC = HOP.toDouble() / SR

    private val binHz = SR.toDouble() / NFFT
    private val kLo = IntArray(NB); private val kHi = IntArray(NB); private val pos = DoubleArray(NB)
    init {
        val half = 2.0.pow(1.0 / 96.0)
        for (j in 0 until NB) {
            val f = 440.0 * 2.0.pow((MIDI0 + j.toDouble() / PER - 69) / 12.0)
            pos[j] = f / binHz
            kLo[j] = ((f / half) / binHz).roundToInt(); kHi[j] = ((f * half) / binHz).roundToInt()
        }
    }

    class Frames(val x: Array<FloatArray>, val rms: DoubleArray, val audio: FloatArray)

    /** Áudio qualquer → quadros (HOP 512 a 22,05 kHz). */
    fun analyze(audio0: FloatArray, sr: Int, progress: (Float) -> Unit = {}): Frames {
        val a = Dsp.resample(audio0, sr, SR)
        var peak = 0f; for (v in a) peak = max(peak, abs(v))
        if (peak > 0) for (i in a.indices) a[i] = a[i] / peak
        val nF = max(0, (a.size - N) / HOP + 1)
        val fft = Dsp.Fft(NFFT)
        val win = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        val maxK = NFFT / 2
        val mag = DoubleArray(maxK + 2)
        val rms = DoubleArray(nF)
        val x = Array(nF) { FloatArray(NB) }
        for (t in 0 until nF) {
            if (t % 128 == 0) progress(t.toFloat() / nF * 0.5f)
            var e = 0.0
            for (i in 0 until N) { val v = a[t * HOP + i].toDouble(); e += v * v; re[i] = v * win[i]; im[i] = 0.0 }
            rms[t] = sqrt(e / N)
            for (i in N until NFFT) { re[i] = 0.0; im[i] = 0.0 }
            fft.transform(re, im)
            for (k in 0..maxK) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]).pow(POW)
            var mx = 0.0
            val row = x[t]
            for (j in 0 until NB) {
                val p = pos[j]; val k = p.toInt(); val fr = p - k
                var v = mag[k] * (1 - fr) + mag[min(maxK, k + 1)] * fr
                if (kHi[j] > kLo[j]) for (kk in kLo[j]..min(maxK, kHi[j])) if (mag[kk] > v) v = mag[kk]
                row[j] = v.toFloat(); if (v > mx) mx = v
            }
            val cut = (CUT * mx).toFloat()
            for (j in 0 until NB) row[j] = max(0f, row[j] - cut)
        }
        return Frames(x, rms, a)
    }
}

object KeyboardCalib {
    class Result(val profile: KeyboardProfile?, val found: Int, val expected: Int, val problems: List<String>, val message: String)

    /** Estimativa de altura de um molde (soma harmônica) — só para conferir se a tecla gravada é a esperada. */
    private fun guessMidi(t: FloatArray): Int {
        var best = -1; var bs = 0.0
        for (m in 24..100) {
            var s = 0.0
            for (h in 1..8) {
                val j = (((m + 12.0 * kotlin.math.ln(h.toDouble()) / kotlin.math.ln(2.0)) - Spec.MIDI0) * Spec.PER).roundToInt()
                if (j >= Spec.NB) break
                var mm = 0f; for (jj in max(0, j - 1)..min(Spec.NB - 1, j + 1)) mm = max(mm, t[jj]); s += mm * 0.88.pow(h - 1)
            }
            if (s > bs) { bs = s; best = m }
        }
        return best
    }

    /** Ataques por salto de energia (cada nota é tocada isolada, com pausa): sobe bem acima do piso dos 0,3 s anteriores. */
    fun attacks(fr: Spec.Frames): ArrayList<Double> {
        val r = fr.rms; val out = ArrayList<Double>()
        val mx = (r.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
        val back = (0.30 / Spec.HOP_SEC).toInt(); var last = -1e9
        if (r.size > 3 && max(r[0], r[1]) > 0.02 * mx) { out += 0.0; last = 0.0 }   // gravação que já começa com a primeira tecla
        for (t in 2 until r.size) {
            if (r[t] < 0.02 * mx) continue
            var lo = Double.MAX_VALUE; for (k in max(0, t - back)..t - 1) lo = min(lo, r[k])
            if (r[t] > 2.2 * lo + 0.004 * mx && r[t] >= r[t - 1] && (t + 1 >= r.size || r[t] >= r[t + 1] * 0.8) && r[t] > 0.9 * (r[min(r.size - 1, t + 3)])) {
                val tt = t * Spec.HOP_SEC + Spec.N / 2.0 / Spec.SR - 0.04
                if (tt - last > 0.30) { out += max(0.0, tt); last = tt }
            }
        }
        return out
    }

    /**
     * [audio]: gravação de uma nota por vez, de [low] a [high], em ordem crescente (de semitom em semitom, teclas pretas incluídas).
     * Procura os ataques, confere a quantidade e aprende o molde de cada tecla.
     */
    fun learn(audio: FloatArray, sr: Int, low: Int, high: Int, label: String, progress: (Float) -> Unit = {}): Result {
        val n = high - low + 1
        val fr = Spec.analyze(audio, sr) { progress(it) }
        if (fr.x.size < 20) return Result(null, 0, n, emptyList(), "A gravação ficou curta demais.")
        val on = attacks(fr)
        if (on.size != n) {
            val msg = if (on.size < n) "Ouvi ${on.size} notas, esperava $n (${KeyboardProfile.noteName(low)} a ${KeyboardProfile.noteName(high)}). Toque uma tecla por vez, sem pedal, com uma pausa curta entre elas, sem pular nenhuma."
            else "Ouvi ${on.size} notas, esperava $n (${KeyboardProfile.noteName(low)} a ${KeyboardProfile.noteName(high)}). Tem ruído ou toque repetido: grave de novo em lugar silencioso, uma tecla por vez."
            return Result(null, on.size, n, emptyList(), msg)
        }
        val tpl = Array(n) { FloatArray(Spec.NB) }
        val problems = ArrayList<String>()
        for (i in 0 until n) {
            val s0 = on[i]; val e0 = if (i + 1 < n) on[i + 1] - 0.05 else s0 + 3.0
            val f0 = ((s0 + 0.10) / Spec.HOP_SEC).toInt(); val f1 = min(fr.x.size - 1, (min(e0, s0 + 0.9) / Spec.HOP_SEC).toInt())
            var cnt = 0
            for (t in f0..max(f0, f1)) { if (t >= fr.x.size) break; for (j in 0 until Spec.NB) tpl[i][j] += fr.x[t][j]; cnt++ }
            if (cnt == 0) { problems += KeyboardProfile.noteName(low + i) + " (sem som)"; continue }
            var mx = 0f; for (j in 0 until Spec.NB) { tpl[i][j] /= cnt; if (tpl[i][j] > mx) mx = tpl[i][j] }
            if (mx <= 0f) { problems += KeyboardProfile.noteName(low + i) + " (sem som)"; continue }
            for (j in 0 until Spec.NB) { tpl[i][j] /= mx; if (tpl[i][j] < 0.02f) tpl[i][j] = 0f }
            val g = guessMidi(tpl[i])
            if (abs(g - (low + i)) > 1 && g % 12 != (low + i) % 12) problems += "${KeyboardProfile.noteName(low + i)} (soou como ${KeyboardProfile.noteName(g)})"
        }
        progress(1f)
        val ok = problems.size <= max(2, n / 10)
        if (!ok) return Result(null, on.size, n, problems, "Ouvi $n notas, mas ${problems.size} não bateram com a ordem esperada (${problems.take(4).joinToString(", ")}). Provavelmente alguma tecla foi pulada ou a faixa está errada; grave de novo.")
        return Result(KeyboardProfile(low, high, tpl, label), on.size, n, problems, if (problems.isEmpty()) "Perfil aprendido: $n teclas." else "Perfil aprendido: $n teclas (conferir: ${problems.joinToString(", ")}).")
    }
}

/** Escuta com moldes fixos: em cada quadro acha quanto de cada tecla explica o espectro (atualizações multiplicativas, com esparsidade). */
class NmfTranscriber(val profile: KeyboardProfile, val iters: Int = 20, val sparsity: Float = 0.05f, val rel: Float = 0.30f, val minDur: Double = 0.15) {
    private val P = profile.keys
    private val idx: Array<IntArray>; private val vals: Array<FloatArray>; private val colSum: FloatArray
    init {
        idx = Array(P) { p -> profile.templates[p].indices.filter { profile.templates[p][it] > 0f }.toIntArray() }
        vals = Array(P) { p -> FloatArray(idx[p].size) { profile.templates[p][idx[p][it]] } }
        colSum = FloatArray(P) { p -> max(1e-6f, vals[p].sum()) }
        for (p in 0 until P) for (i in vals[p].indices) vals[p][i] /= colSum[p]
    }

    fun frames(audio: FloatArray, sr: Int, progress: (Float) -> Unit = {}): SpectralTranscriber.Frames {
        val fr = Spec.analyze(audio, sr) { progress(it) }
        val nF = fr.x.size
        val act = Array(nF) { FloatArray(P) }; val raw = Array(nF) { FloatArray(P) }
        val rmsMax = (fr.rms.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
        val h = FloatArray(P) { 0.02f }
        val est = FloatArray(Spec.NB); val ratio = FloatArray(Spec.NB)
        for (t in 0 until nF) {
            if (t % 64 == 0) progress(0.5f + 0.45f * t / nF)
            if (fr.rms[t] < 0.03 * rmsMax) { for (p in 0 until P) h[p] = 0.02f; continue }
            val x = fr.x[t]
            var xs = 0f; for (v in x) xs += v
            if (xs <= 0f) continue
            val floor0 = 0.05f * xs / P
            for (p in 0 until P) h[p] = max(h[p] * 0.7f, floor0)
            for (it in 0 until iters) {
                java.util.Arrays.fill(est, 1e-4f)
                var hm = 0f; for (p in 0 until P) if (h[p] > hm) hm = h[p]
                val thr = hm * 2e-3f
                for (p in 0 until P) { val hp = h[p]; if (hp < thr) continue; val ix = idx[p]; val vv = vals[p]; for (i in ix.indices) est[ix[i]] += hp * vv[i] }
                for (b in 0 until Spec.NB) ratio[b] = x[b] / est[b]
                for (p in 0 until P) {
                    val ix = idx[p]; val vv = vals[p]; var num = 0f; var den = 0f
                    for (i in ix.indices) { num += vv[i] * ratio[ix[i]]; den += vv[i] }
                    h[p] = h[p] * num / (den + sparsity)
                }
            }
            var m = 0f; for (p in 0 until P) if (h[p] > m) m = h[p]
            if (m <= 0f) continue
            for (p in 0 until P) { val v = h[p]; raw[t][p] = v; act[t][p] = min(1f, v / m * (if (v >= rel * m) 1f else 0f)) * min(1f, (fr.rms[t] / rmsMax).toFloat() * 4f) }
        }
        progress(0.95f)
        return SpectralTranscriber.Frames(act, raw, profile.lowMidi, SpectralTranscriber().onsetTimes(fr.audio))
    }

    fun transcribe(audio: FloatArray, sr: Int, progress: (Float) -> Unit = {}): List<NoteEvent> = SpectralTranscriber(lowMidi = profile.lowMidi, highMidi = profile.highMidi).notes(frames(audio, sr, progress)).filter { it.end - it.start >= minDur }
}
