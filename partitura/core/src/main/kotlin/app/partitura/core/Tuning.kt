package app.partitura.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Afinação do instrumento e ruído da sala.
 * Teclado ou piano fora de 440 Hz (ou celular gravando com desvio) faz o reconhecimento errar notas inteiras;
 * aqui se mede o desvio em centésimos de semitom e o áudio é reamostrado para a afinação padrão antes da escuta.
 */
object Tuning {
    class Estimate(val cents: Double, val confidence: Double)

    /** Desvio global (−50..+50 centésimos) pelos picos espectrais: histograma circular do resto de cada pico em relação ao semitom mais próximo. */
    fun estimate(audio: FloatArray, sr: Int): Estimate {
        val a = if (sr == 22050) audio else Dsp.resample(audio, sr, 22050)
        val n = 8192; val hop = 4096
        if (a.size < n * 2) return Estimate(0.0, 0.0)
        val fft = Dsp.Fft(n)
        val win = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val re = DoubleArray(n); val im = DoubleArray(n)
        val hist = DoubleArray(100)
        val binHz = 22050.0 / n
        val mag = DoubleArray(n / 2 + 2)
        var total = 0.0
        var t = 0
        while (t + n <= a.size) {
            for (i in 0 until n) { re[i] = a[t + i] * win[i]; im[i] = 0.0 }
            fft.transform(re, im)
            var mx = 0.0
            for (k in 0..n / 2) { mag[k] = sqrt(re[k] * re[k] + im[k] * im[k]); if (k * binHz in 70.0..1500.0 && mag[k] > mx) mx = mag[k] }
            if (mx > 1e-6) {
                val kLo = (70.0 / binHz).toInt() + 1; val kHi = (1500.0 / binHz).toInt()
                for (k in kLo..kHi) {
                    val m = mag[k]
                    if (m < 0.15 * mx || m < mag[k - 1] || m <= mag[k + 1]) continue
                    val la = ln(mag[k - 1] + 1e-12); val lb = ln(m + 1e-12); val lc = ln(mag[k + 1] + 1e-12)
                    val den = la - 2 * lb + lc
                    val d = if (den == 0.0) 0.0 else 0.5 * (la - lc) / den
                    val f = (k + d) * binHz
                    val semis = 12.0 * ln(f / 440.0) / ln(2.0)
                    var c = (semis - Math.rint(semis)) * 100.0        // −50..50
                    var b = (c + 50.0).roundToInt(); if (b >= 100) b = 99; if (b < 0) b = 0
                    val w = (m / mx).pow(2.0)
                    hist[b] += w; total += w
                }
            }
            t += hop
        }
        if (total <= 0.0) return Estimate(0.0, 0.0)
        // suaviza (circular, ±3) e acha o máximo
        val sm = DoubleArray(100) { i -> var s = 0.0; for (d in -3..3) s += hist[Math.floorMod(i + d, 100)] * (4 - abs(d)); s }
        var bi = 0; for (i in 0 until 100) if (sm[i] > sm[bi]) bi = i
        var num = 0.0; var den = 0.0
        for (d in -8..8) { val w = hist[Math.floorMod(bi + d, 100)]; num += w * (bi + d - 50 + 0.5); den += w }
        val cents = if (den > 0) num / den else 0.0
        var near = 0.0; for (d in -8..8) near += hist[Math.floorMod(bi + d, 100)]
        return Estimate(cents, near / total)
    }

    /** Desvio a corrigir, ou 0 quando é pequeno (< [min] centésimos) ou a medida é pouco confiável. */
    fun correction(audio: FloatArray, sr: Int, min: Double = 8.0): Double {
        val e = estimate(audio, sr)
        return if (e.confidence >= 0.18 && abs(e.cents) >= min) e.cents else 0.0
    }

    /** Fator de tempo: o áudio corrigido é [k] vezes mais longo (k = 2^(cents/1200)); tempos detectados nele voltam ao original dividindo por k. */
    fun factor(cents: Double) = 2.0.pow(cents / 1200.0)

    /** Reamostra para a afinação padrão: nota [cents] acima de 440 passa a soar no lugar certo. */
    fun retune(audio: FloatArray, sr: Int, cents: Double): FloatArray {
        if (cents == 0.0) return audio
        val k = factor(cents)
        val to = (sr * k).roundToInt()
        return Dsp.resample(audio, sr, to)
    }
}

/**
 * Limpeza das notas detectadas, com margem de erro física:
 * • nota grave precisa durar mais (poucos ciclos de onda não são nota: batida da tecla, ronco, ressonância da caixa);
 * • a mesma nota de novo logo depois de terminar (eco da sala) é a mesma nota, não duas;
 * • quanto mais barulhenta a sala, mais exigente o corte.
 */
object NoteCleanup {
    fun minDuration(midi: Int, base: Double, snrDb: Double = 99.0): Double {
        val low = when { midi < 36 -> 0.30; midi < 48 -> 0.20; midi < 60 -> 0.12; else -> 0.0 }
        val noisy = if (snrDb < 12.0) 1.35 else if (snrDb < 20.0) 1.15 else 1.0
        return max(base, low * noisy)
    }

    fun apply(notes: List<NoteEvent>, base: Double = 0.07, snrDb: Double = 99.0, mergeGap: Double = 0.08): List<NoteEvent> {
        val by = notes.sortedBy { it.start }.groupBy { it.midi }
        val out = ArrayList<NoteEvent>()
        for ((_, list) in by) {
            val merged = ArrayList<NoteEvent>()
            for (n in list) {
                val last = merged.lastOrNull()
                if (last != null && n.start - last.end <= mergeGap && n.amp < last.amp * 0.8 + 0.05) {
                    merged[merged.size - 1] = NoteEvent(last.start, max(last.end, n.end), last.midi, last.amp)   // eco mais fraco: prolonga a nota
                } else merged += n
            }
            for (n in merged) if (n.end - n.start >= minDuration(n.midi, base, snrDb)) out += n
        }
        return out.sortedBy { it.start }
    }
}
