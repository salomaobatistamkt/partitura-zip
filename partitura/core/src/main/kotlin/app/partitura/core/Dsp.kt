package app.partitura.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

object Dsp {
    /** FFT complexa radix-2 no lugar (n potência de 2). */
    class Fft(val n: Int) {
        private val cosT = DoubleArray(n / 2) { cos(2 * PI * it / n) }
        private val sinT = DoubleArray(n / 2) { -sin(2 * PI * it / n) }
        private val rev = IntArray(n).also { r -> val bits = Integer.numberOfTrailingZeros(n); for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits) }
        fun transform(re: DoubleArray, im: DoubleArray) {
            for (i in 0 until n) { val j = rev[i]; if (j > i) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t } }
            var size = 2
            while (size <= n) {
                val half = size / 2; val step = n / size
                var i = 0
                while (i < n) {
                    var k = 0
                    for (j in i until i + half) {
                        val tr = re[j + half] * cosT[k] - im[j + half] * sinT[k]
                        val ti = re[j + half] * sinT[k] + im[j + half] * cosT[k]
                        re[j + half] = re[j] - tr; im[j + half] = im[j] - ti
                        re[j] += tr; im[j] += ti
                        k += step
                    }
                    i += size
                }
                size *= 2
            }
        }
    }

    /** Reamostra (média simples antes de decimar, interpolação linear depois). */
    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to) return x
        var src = x
        if (from > to) {
            val k = (from.toDouble() / to).roundToInt().coerceAtLeast(1)
            if (k > 1) {
                val f = FloatArray(src.size / k)
                for (i in f.indices) { var s = 0f; for (j in 0 until k) s += src[i * k + j]; f[i] = s / k }
                src = f
            }
            val fromEff = from / k
            if (fromEff == to) return src
            return interp(src, fromEff, to)
        }
        return interp(src, from, to)
    }

    private fun interp(x: FloatArray, from: Int, to: Int): FloatArray {
        val n = (x.size.toLong() * to / from).toInt()
        val out = FloatArray(n)
        val r = from.toDouble() / to
        for (i in 0 until n) {
            val p = i * r; val i0 = p.toInt(); val fr = (p - i0).toFloat()
            val a = x[min(i0, x.size - 1)]; val b = x[min(i0 + 1, x.size - 1)]
            out[i] = a + (b - a) * fr
        }
        return out
    }

    /** WAV PCM 8/16/24/32 bits ou float32, qualquer número de canais → mono. Devolve (amostras, taxa). */
    fun readWav(b: ByteArray): Pair<FloatArray, Int> {
        require(b.size > 44 && String(b, 0, 4) == "RIFF" && String(b, 8, 4) == "WAVE") { "Isto não é um arquivo WAV." }
        fun u16(i: Int) = (b[i].toInt() and 255) or ((b[i + 1].toInt() and 255) shl 8)
        fun u32(i: Int) = u16(i) or (u16(i + 2) shl 16)
        var p = 12; var fmt = 1; var ch = 1; var sr = 44100; var bits = 16; var dataOff = -1; var dataLen = 0
        while (p + 8 <= b.size) {
            val id = String(b, p, 4); val len = u32(p + 4)
            if (id == "fmt ") { fmt = u16(p + 8); ch = u16(p + 10); sr = u32(p + 12); bits = u16(p + 22); if (fmt == 0xFFFE && len >= 26) fmt = u16(p + 32) }
            if (id == "data") { dataOff = p + 8; dataLen = if (len < 0 || dataOff + len > b.size) b.size - dataOff else len; break }
            p += 8 + len + (len and 1)
        }
        require(dataOff >= 0) { "WAV sem dados." }
        val bytes = bits / 8; val frames = dataLen / (bytes * ch)
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            var s = 0.0
            for (c in 0 until ch) {
                val o = dataOff + (i * ch + c) * bytes
                s += when {
                    fmt == 3 && bits == 32 -> java.lang.Float.intBitsToFloat(u32(o)).toDouble()
                    bits == 8 -> ((b[o].toInt() and 255) - 128) / 128.0
                    bits == 16 -> (u16(o).toShort().toInt()) / 32768.0
                    bits == 24 -> (((b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8) or (b[o + 2].toInt() shl 16)) / 8388608.0)
                    else -> u32(o) / 2147483648.0
                }
            }
            out[i] = (s / ch).toFloat()
        }
        return out to sr
    }
}

/**
 * Motor próprio (sem modelo de IA) de reserva: soma de harmônicos com cancelamento iterativo, vários sons ao mesmo tempo.
 * Funciona bem em áudio limpo de um instrumento; em gravações ruidosas ou muito densas o Basic Pitch é melhor.
 */
class SpectralTranscriber(val maxPoly: Int = 4, val lowMidi: Int = 24, val highMidi: Int = 108) {
    companion object { const val SR = 22050; const val N = 4096; const val HOP = 512; const val NFFT = 8192; const val ONSET_LEAD = 0.012 }

    class Frames(val act: Array<FloatArray>, val raw: Array<FloatArray>, val lowMidi: Int, val onsets: DoubleArray = DoubleArray(0))

    fun analyze(audio0: FloatArray, sr: Int, progress: (Float) -> Unit = {}): Frames {
        val x = Dsp.resample(audio0, sr, SR)
        var peak = 0f; for (v in x) peak = max(peak, abs(v))
        if (peak > 0) for (i in x.indices) x[i] = x[i] / peak
        val nFrames = max(0, (x.size - N) / HOP + 1)
        val nP = highMidi - lowMidi + 1
        val act = Array(nFrames) { FloatArray(nP) }
        val raw = Array(nFrames) { FloatArray(nP) }
        val fft = Dsp.Fft(NFFT)
        val win = DoubleArray(N) { 0.5 - 0.5 * cos(2 * PI * it / (N - 1)) }
        val re = DoubleArray(NFFT); val im = DoubleArray(NFFT)
        val binHz = SR.toDouble() / NFFT
        val maxBin = (7000.0 / binHz).toInt()
        val mag = DoubleArray(maxBin + 2)
        val f0 = DoubleArray(nP) { 440.0 * 2.0.pow((lowMidi + it - 69) / 12.0) }
        val rms = DoubleArray(nFrames)
        for (t in 0 until nFrames) {
            var e = 0.0; for (i in 0 until N) e += x[t * HOP + i].toDouble().let { it * it }
            rms[t] = sqrt(e / N)
        }
        val rmsMax = (rms.maxOrNull() ?: 0.0).coerceAtLeast(1e-9)
        for (t in 0 until nFrames) {
            if (t % 64 == 0) progress(t.toFloat() / nFrames * 0.9f)
            if (rms[t] < 0.03 * rmsMax) continue
            for (i in 0 until N) { re[i] = x[t * HOP + i] * win[i]; im[i] = 0.0 }
            for (i in N until NFFT) { re[i] = 0.0; im[i] = 0.0 }
            fft.transform(re, im)
            for (k in 0..maxBin) mag[k] = sqrt(re[k] * re[k] + im[k] * im[k])
            // compressão e "branqueamento": tira a média local para que o timbre não decida o resultado
            val c = DoubleArray(maxBin + 1) { sqrt(mag[it]) }
            val wht = DoubleArray(maxBin + 1)
            run {
                val w = 48; var s = 0.0; var cnt = 0
                for (k in 0..min(w, maxBin)) { s += c[k]; cnt++ }
                for (k in 0..maxBin) {
                    wht[k] = max(0.0, c[k] - 0.9 * s / cnt)
                    val add = k + w + 1; if (add <= maxBin) { s += c[add]; cnt++ }
                    val rem = k - w; if (rem >= 0) { s -= c[rem]; cnt-- }
                }
            }
            val frameMax = wht.maxOrNull() ?: 0.0
            if (frameMax <= 0) continue
            var first = 0.0
            for (iter in 0 until maxPoly) {
                var bestP = -1; var bestS = 0.0
                for (pi in 0 until nP) {
                    var s = 0.0; var present = 0; var h1 = 0.0
                    for (h in 1..9) {
                        val f = f0[pi] * h; if (f > 6500) break
                        val kc = (f / binHz).roundToInt(); val hw = max(1, (f * 0.020 / binHz).roundToInt())
                        var m = 0.0; for (k in max(0, kc - hw)..min(maxBin, kc + hw)) m = max(m, wht[k])
                        if (h == 1) h1 = m
                        s += m * 0.86.pow(h - 1)
                        if (m > 0.2 * frameMax) present++
                    }
                    if (present >= 3 && h1 > 0.12 * frameMax || present >= 5) { if (s > bestS) { bestS = s; bestP = pi } }
                }
                if (bestP < 0) break
                if (iter == 0) first = bestS else if (bestS < 0.30 * first) break
                act[t][bestP] = (bestS / (frameMax * 3.0)).toFloat().coerceAtMost(1f)
                raw[t][bestP] = bestS.toFloat()
                for (h in 1..12) {
                    val f = f0[bestP] * h; if (f > 6500) break
                    val kc = (f / binHz).roundToInt(); val hw = max(2, (f * 0.026 / binHz).roundToInt())
                    for (k in max(0, kc - hw)..min(maxBin, kc + hw)) wht[k] *= 0.12
                }
            }
        }
        progress(0.9f)
        return Frames(act, raw, lowMidi, onsetTimes(x))
    }

    /** Começos de ataque (s) pelo fluxo espectral de janelas curtas: dá a precisão de tempo que a janela longa não tem. */
    fun onsetTimes(x: FloatArray): DoubleArray {
        val n = 1024; val hop = 256
        val nF = (x.size - n) / hop + 1
        if (nF < 8) return DoubleArray(0)
        val fft = Dsp.Fft(n)
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val re = DoubleArray(n); val im = DoubleArray(n)
        val kMax = (5000.0 / (SR.toDouble() / n)).toInt()
        var prev = DoubleArray(kMax + 1)
        val flux = DoubleArray(nF)
        for (t in 0 until nF) {
            for (i in 0 until n) { re[i] = x[t * hop + i] * win[i]; im[i] = 0.0 }
            fft.transform(re, im)
            val cur = DoubleArray(kMax + 1) { ln(1.0 + 30.0 * sqrt(re[it] * re[it] + im[it] * im[it])) }
            if (t > 0) { var f = 0.0; for (k in 3..kMax) f += max(0.0, cur[k] - prev[k]); flux[t] = f }
            prev = cur
        }
        // limiar adaptativo: média local + fração do desvio
        val out = ArrayList<Double>()
        val w = 40
        for (t in 3 until nF - 3) {
            var m = 0.0; var c = 0; for (k in max(0, t - w)..min(nF - 1, t + w)) { m += flux[k]; c++ }
            m /= c
            var v = 0.0; for (k in max(0, t - w)..min(nF - 1, t + w)) v += (flux[k] - m) * (flux[k] - m)
            val sd = sqrt(v / c)
            var isMax = true; for (k in t - 3..t + 3) if (flux[k] > flux[t]) { isMax = false; break }
            if (isMax && flux[t] > m + 0.9 * sd && flux[t] > 1.0) out += (t * hop + n * 0.5) / SR - ONSET_LEAD
        }
        return out.toDoubleArray()
    }

    /** Quadros → notas: histerese, remoção de fantasmas de oitava e separação de notas repetidas. */
    fun notes(fr: Frames): List<NoteEvent> {
        val nF = fr.act.size; if (nF == 0) return emptyList()
        val nP = fr.act[0].size
        var gmax = 0f; for (a in fr.act) for (v in a) gmax = max(gmax, v)
        if (gmax <= 0f) return emptyList()
        // suaviza no tempo (máximo de 3 quadros menos o ruído) e normaliza
        val sm = Array(nF) { t -> FloatArray(nP) { p -> max(fr.act[t][p], max(fr.act[max(0, t - 1)][p], fr.act[min(nF - 1, t + 1)][p]) * 0.9f) / gmax } }
        val on = 0.22f; val off = 0.12f
        val rs = Array(nF) { t -> FloatArray(nP) { p -> 0.25f * fr.raw[max(0, t - 1)][p] + 0.5f * fr.raw[t][p] + 0.25f * fr.raw[min(nF - 1, t + 1)][p] } }
        val sec = HOP.toDouble() / SR
        val out = ArrayList<NoteEvent>()
        for (p in 0 until nP) {
            var t = 0
            while (t < nF) {
                if (sm[t][p] < on) { t++; continue }
                var s = t; var e = t; var gap = 0; var u = t
                while (u < nF) {
                    if (sm[u][p] >= off) { e = u; gap = 0 } else { gap++; if (gap > 3) break }
                    u++
                }
                // separa notas repetidas: vale na energia absoluta com menos de 70% dos dois picos vizinhos
                var segStart = s
                var i = s + 3
                while (i < e - 2) {
                    val mid = rs[i][p]
                    var l = 0f; for (k in max(segStart, i - 10)..i) l = max(l, rs[k][p])
                    var r = 0f; for (k in i..min(e, i + 10)) r = max(r, rs[k][p])
                    if (mid < 0.70f * min(l, r) && mid <= rs[i - 1][p] && mid <= rs[i + 1][p] && i - segStart >= 3) {
                        out += mk(segStart, i, p, sm, fr.lowMidi, sec); segStart = i; i += 3
                    } else i++
                }
                out += mk(segStart, e + 1, p, sm, fr.lowMidi, sec)
                t = max(u, e + 1)
            }
        }
        // ajusta o começo de cada nota ao ataque mais próximo (janela curta = tempo preciso)
        val snapped = out.map { n ->
            val on = fr.onsets.filter { it >= n.start - 0.06 && it <= n.start + 0.16 && it < n.end - 0.03 }.minByOrNull { abs(it - n.start) }
            if (on != null) NoteEvent(on, n.end, n.midi, n.amp) else n
        }
        // fantasmas: nota uma oitava (ou 12+7) acima de outra, quase ao mesmo tempo e bem mais fraca
        val res = snapped.filter { it.end - it.start >= 0.06 }.sortedBy { it.start }
        return res.filter { n ->
            res.none { m ->
                m !== n && (n.midi - m.midi == 12 || n.midi - m.midi == 19 || n.midi - m.midi == 24) &&
                    abs(n.start - m.start) < 0.08 && n.end <= m.end + 0.12 && n.amp < 0.55 * m.amp
            }
        }
    }

    private fun mk(s: Int, e: Int, p: Int, sm: Array<FloatArray>, low: Int, sec: Double): NoteEvent {
        var mx = 0f; for (t in s until min(e, sm.size)) mx = max(mx, sm[t][p])
        // a janela é centrada: o começo real é anterior ao centro do primeiro quadro
        val lag = (SpectralTranscriber.N / 2.0 / SpectralTranscriber.SR) * 0.55
        return NoteEvent(max(0.0, s * sec + SpectralTranscriber.N / 2.0 / SpectralTranscriber.SR - lag), e * sec + SpectralTranscriber.N / 2.0 / SpectralTranscriber.SR - lag * 0.4, low + p, mx.toDouble())
    }

    fun transcribe(audio: FloatArray, sr: Int, progress: (Float) -> Unit = {}): List<NoteEvent> = notes(analyze(audio, sr, progress))
}
