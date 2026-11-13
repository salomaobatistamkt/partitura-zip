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
class KeyboardProfile(val lowMidi: Int, val highMidi: Int, val templates: Array<FloatArray>, val label: String = "", val noise: FloatArray? = null) {
    val keys get() = highMidi - lowMidi + 1

    fun toBytes(): ByteArray {
        val bo = ByteArrayOutputStream(); val d = DataOutputStream(bo)
        d.writeInt(0x4B425032); d.writeInt(lowMidi); d.writeInt(highMidi); d.writeInt(Spec.NB); d.writeUTF(label)
        for (t in templates) for (v in t) d.writeFloat(v)
        d.writeBoolean(noise != null); if (noise != null) for (v in noise) d.writeFloat(v)
        d.flush(); return bo.toByteArray()
    }

    companion object {
        fun fromBytes(b: ByteArray): KeyboardProfile? = try {
            val d = DataInputStream(ByteArrayInputStream(b))
            val magic = d.readInt()
            if (magic != 0x4B425031 && magic != 0x4B425032) null else {
                val lo = d.readInt(); val hi = d.readInt(); val nb = d.readInt(); val label = d.readUTF()
                if (nb != Spec.NB || hi < lo || hi - lo > 120) null
                else {
                    val tpl = Array(hi - lo + 1) { FloatArray(nb) { d.readFloat() } }
                    val noise = if (magic == 0x4B425032 && d.readBoolean()) FloatArray(nb) { d.readFloat() } else null
                    KeyboardProfile(lo, hi, tpl, label, noise)
                }
            }
        } catch (_: Throwable) { null }

        /** Teclados comuns: nome → (primeira, última) tecla em MIDI (Dó central = 60 = C4, padrão científico). */
        val PRESETS = listOf(
            Triple("49 teclas (C2 a C6)", 36, 84),
            Triple("61 teclas (C1 a C6)", 24, 84),
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

    class Frames(val x: Array<FloatArray>, val rms: DoubleArray, val audio: FloatArray, val room: Room? = null)

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
        val room = subtractRoomNoise(x, rms)
        return Frames(x, rms, a, room)
    }

    /** Variância do ambiente: espectro médio (e desvio) dos quadros mais silenciosos vira um piso por frequência, descontado de todos os quadros. */
    @JvmField var NOISE_K = 1.5f
    class Room(val snrDb: Double, val floorRms: Double, val usable: Boolean, val mean: FloatArray? = null)
    fun subtractRoomNoise(x: Array<FloatArray>, rms: DoubleArray): Room {
        if (x.size < 40) return Room(99.0, 0.0, false)
        val order = rms.indices.sortedBy { rms[it] }
        val q = order.take(max(10, x.size / 8))
        val mx = rms.maxOrNull() ?: 0.0
        val floorRms = q.map { rms[it] }.average()
        val med = rms.sortedArray()[rms.size * 3 / 4]
        val snr = if (floorRms > 0) 20 * kotlin.math.log10(max(1e-9, med) / floorRms) else 99.0
        if (mx <= 0 || floorRms > 0.15 * mx) return Room(snr, floorRms, false)   // sem pausas de verdade: não dá para medir a sala
        val mean = FloatArray(NB); val sd = FloatArray(NB)
        for (t in q) for (j in 0 until NB) mean[j] += x[t][j] / q.size
        for (t in q) for (j in 0 until NB) { val d = x[t][j] - mean[j]; sd[j] += d * d / q.size }
        for (j in 0 until NB) sd[j] = sqrt(sd[j])
        val keep = mean.copyOf()
        for (row in x) for (j in 0 until NB) row[j] = max(0f, row[j] - NOISE_K * (mean[j] + sd[j]))
        return Room(snr, floorRms, true, keep)
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
        if (on.size < n / 2) return Result(null, on.size, n, emptyList(), "Ouvi só ${on.size} notas, esperava $n (${KeyboardProfile.noteName(low)} a ${KeyboardProfile.noteName(high)}). Toque uma tecla por vez, sem pedal, com uma pausa curta entre elas, sem pular nenhuma.")
        // molde de cada ataque candidato (inclui ecos e batidas de tecla: o alinhamento abaixo os descarta)
        val C = on.size
        val cand = arrayOfNulls<FloatArray>(C); val guess = IntArray(C) { -1 }
        for (i in 0 until C) {
            val s0 = on[i]; val e0 = if (i + 1 < C) on[i + 1] - 0.05 else s0 + 3.0
            val f0 = ((s0 + 0.10) / Spec.HOP_SEC).toInt(); val f1 = min(fr.x.size - 1, (min(e0, s0 + 0.9) / Spec.HOP_SEC).toInt())
            val t = FloatArray(Spec.NB); var cnt = 0
            for (u in f0..max(f0, f1)) { if (u >= fr.x.size) break; for (j in 0 until Spec.NB) t[j] += fr.x[u][j]; cnt++ }
            if (cnt == 0) continue
            var mx = 0f; for (j in 0 until Spec.NB) { t[j] /= cnt; if (t[j] > mx) mx = t[j] }
            if (mx <= 0f) continue
            for (j in 0 until Spec.NB) { t[j] /= mx; if (t[j] < 0.02f) t[j] = 0f }
            cand[i] = t; guess[i] = guessMidi(t)
        }
        // programação dinâmica: casa a sequência de ataques com as teclas esperadas, em ordem.
        // Ataque a mais (eco, batida) é descartado; tecla sem ataque é registrada como faltando.
        val INF = 1e9
        val dp = Array(C + 1) { DoubleArray(n + 1) { INF } }; val bt = Array(C + 1) { IntArray(n + 1) }
        dp[0][0] = 0.0
        for (i in 0..C) for (j in 0..n) {
            val cur = dp[i][j]; if (cur >= INF) continue
            if (i < C && cur + 0.8 < dp[i + 1][j]) { dp[i + 1][j] = cur + 0.8; bt[i + 1][j] = 1 }       // descarta ataque
            if (j < n && cur + 2.0 < dp[i][j + 1]) { dp[i][j + 1] = cur + 2.0; bt[i][j + 1] = 2 }       // tecla não ouvida
            if (i < C && j < n) {
                val g = guess[i]; val k = low + j
                val c = if (cand[i] == null) 3.0 else if (g == k) 0.0 else if (abs(g - k) == 1) 0.7 else if (g % 12 == k % 12) 1.0 else 2.2
                if (cur + c < dp[i + 1][j + 1]) { dp[i + 1][j + 1] = cur + c; bt[i + 1][j + 1] = 3 }
            }
        }
        val assign = IntArray(n) { -1 }
        run { var i = C; var j = n; while (i > 0 || j > 0) { when (bt[i][j]) { 1 -> i--; 2 -> j--; else -> { assign[j - 1] = i - 1; i--; j-- } } } }
        val missing = (0 until n).filter { assign[it] < 0 }
        val dropped = C - (n - missing.size)
        val tpl = Array(n) { FloatArray(Spec.NB) }
        val problems = ArrayList<String>()
        for (i in 0 until n) { val c = assign[i]; if (c >= 0 && cand[c] != null) tpl[i] = cand[c]!!.copyOf() }
        val tooMany = missing.size > max(2, n / 12)
        if (tooMany) return Result(null, C, n, missing.map { KeyboardProfile.noteName(low + it) + " (não ouvida)" },
            "Ouvi só ${n - missing.size} das $n teclas; não ouvi: ${missing.take(8).joinToString(", ") { KeyboardProfile.noteName(low + it) }}. Toque uma tecla por vez, em ordem, sem pular nenhuma, e deixe a nota soar cerca de 1 s.")
        // teclas não ouvidas (poucas): molde do vizinho mais próximo, deslocado na escala logarítmica (transposição = deslocamento de bins)
        for (m in missing) {
            val near = (0 until n).filter { it !in missing }.minByOrNull { abs(it - m) } ?: continue
            val sh = (m - near) * Spec.PER
            for (j in 0 until Spec.NB) { val src = j - sh; tpl[m][j] = if (src in 0 until Spec.NB) tpl[near][src] else 0f }
            problems += KeyboardProfile.noteName(low + m) + " (molde estimado)"
        }
        for (i in 0 until n) {
            if (i in missing) continue
            val g = guessMidi(tpl[i])
            if (abs(g - (low + i)) > 1 && g % 12 != (low + i) % 12) problems += "${KeyboardProfile.noteName(low + i)} (soou como ${KeyboardProfile.noteName(g)})"
        }
        val noteRms = on.indices.map { fr.rms[min(fr.rms.size - 1, ((on[it] + 0.2) / Spec.HOP_SEC).toInt())] }.sorted()
        val floorRms = fr.rms.sorted().take(max(5, fr.rms.size / 8)).average()
        val snr = if (floorRms > 0) 20 * kotlin.math.log10(max(1e-9, noteRms[noteRms.size / 2]) / floorRms) else 99.0
        val envMsg = (if (snr < 12) " Sala barulhenta (${snr.roundToInt()} dB acima do ruído): o resultado pode ser pior; tente um lugar mais silencioso." else "") +
            (if (dropped > 0) " Descartei $dropped ataque(s) falso(s) (eco ou barulho da tecla)." else "")
        progress(1f)
        val ok = problems.size <= max(3, n / 10)
        if (!ok) return Result(null, on.size, n, problems, "Ouvi as notas, mas ${problems.size} não bateram com a ordem esperada (${problems.take(4).joinToString(", ")}). Provavelmente a faixa escolhida não é a do teclado; confira o intervalo e grave de novo.")
        return Result(KeyboardProfile(low, high, tpl, label, fr.room?.mean), on.size, n, problems, (if (problems.isEmpty()) "Perfil aprendido: $n teclas." else "Perfil aprendido: $n teclas (conferir: ${problems.joinToString(", ")}).") + envMsg)
    }
}

/** Escuta com moldes fixos: em cada quadro acha quanto de cada tecla explica o espectro (atualizações multiplicativas, com esparsidade). */
class NmfTranscriber(val profile: KeyboardProfile, val iters: Int = 20, val sparsity: Float = 0.05f, val rel: Float = 0.30f, val minDur: Double = 0.15) {
    private val P = profile.keys
    /** Componentes extras que absorvem o barulho da sala (medido na calibração), ruído plano e ruído rosado — assim o ruído não vira notas graves. */
    private val Q = 3
    private var lastSnr = 99.0
    private val idx: Array<IntArray>; private val vals: Array<FloatArray>; private val colSum: FloatArray
    init {
        val all = ArrayList<FloatArray>(); for (t in profile.templates) all += t
        val room = profile.noise
        all += if (room != null) FloatArray(Spec.NB) { room[it] } else FloatArray(Spec.NB) { 1f }
        all += FloatArray(Spec.NB) { 1f }
        all += FloatArray(Spec.NB) { 1f / (1f + it / 24f) }      // 1/f: energia nos graves, como ventilador e ronco
        idx = Array(P + Q) { p -> all[p].indices.filter { all[p][it] > 0f }.toIntArray() }
        vals = Array(P + Q) { p -> FloatArray(idx[p].size) { all[p][idx[p][it]] } }
        colSum = FloatArray(P + Q) { p -> max(1e-6f, vals[p].sum()) }
        for (p in 0 until P + Q) for (i in vals[p].indices) vals[p][i] /= colSum[p]
    }

    fun frames(audio: FloatArray, sr: Int, progress: (Float) -> Unit = {}): SpectralTranscriber.Frames {
        val fr = Spec.analyze(audio, sr) { progress(it) }
        lastSnr = fr.room?.snrDb ?: 99.0
        val nF = fr.x.size
        val act = Array(nF) { FloatArray(P) }; val raw = Array(nF) { FloatArray(P) }
        val rmsMax = (fr.rms.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
        val PT = P + Q
        val h = FloatArray(PT) { 0.02f }
        val est = FloatArray(Spec.NB); val ratio = FloatArray(Spec.NB)
        for (t in 0 until nF) {
            if (t % 64 == 0) progress(0.5f + 0.45f * t / nF)
            if (fr.rms[t] < 0.03 * rmsMax) { for (p in 0 until PT) h[p] = 0.02f; continue }
            val x = fr.x[t]
            var xs = 0f; for (v in x) xs += v
            if (xs <= 0f) continue
            val floor0 = 0.05f * xs / PT
            for (p in 0 until PT) h[p] = max(h[p] * 0.7f, floor0)
            for (it in 0 until iters) {
                java.util.Arrays.fill(est, 1e-4f)
                var hm = 0f; for (p in 0 until PT) if (h[p] > hm) hm = h[p]
                val thr = hm * 2e-3f
                for (p in 0 until PT) { val hp = h[p]; if (hp < thr) continue; val ix = idx[p]; val vv = vals[p]; for (i in ix.indices) est[ix[i]] += hp * vv[i] }
                for (b in 0 until Spec.NB) ratio[b] = x[b] / est[b]
                for (p in 0 until PT) {
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

    fun transcribe(audio: FloatArray, sr: Int, progress: (Float) -> Unit = {}): List<NoteEvent> = SpectralTranscriber(lowMidi = profile.lowMidi, highMidi = profile.highMidi).notes(frames(audio, sr, progress)).let { NoteCleanup.apply(it, minDur, lastSnr) }
}
