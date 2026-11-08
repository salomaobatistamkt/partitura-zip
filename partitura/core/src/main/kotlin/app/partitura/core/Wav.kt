package app.partitura.core

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Sintetizador aditivo de piano. O andamento é aplicado na síntese, então o tom nunca muda. */
object Synth {
    const val RATE = 44100

    class Event(val startSec: Double, val durSec: Double, val midi: Int, val part: Int)

    fun events(s: Score, tempo: Int = s.tempo): List<Event> {
        val sec = 60.0 / tempo / s.divisions
        val ev = mutableListOf<Event>()
        s.parts.forEachIndexed { pi, p ->
            var t = 0
            val voiceTime = HashMap<Int, Int>()
            var lastStart = 0
            val open = HashMap<Int, Int>() // midi -> índice do evento ligado
            for (m in p.measures) {
                val mStart = t
                voiceTime.clear()
                for (n in m.notes) {
                    val start = if (n.chord) lastStart else mStart + (voiceTime[n.voice] ?: 0)
                    if (!n.chord) { voiceTime[n.voice] = (voiceTime[n.voice] ?: 0) + n.duration; lastStart = start }
                    val pt = n.pitch ?: continue
                    if (n.tieStop && open.containsKey(pt.midi)) {
                        val idx = open[pt.midi]!!; val old = ev[idx]
                        ev[idx] = Event(old.startSec, old.durSec + n.duration * sec, old.midi, pi)
                        if (!n.tieStart) open.remove(pt.midi)
                    } else {
                        ev += Event(start * sec, n.duration * sec, pt.midi, pi)
                        if (n.tieStart) open[pt.midi] = ev.size - 1
                    }
                }
                t = mStart + (voiceTime.values.maxOrNull() ?: 0)
            }
        }
        return ev
    }

    /** Início de cada compasso (segundos); tamanho = compassos + 1. Usa a primeira parte. */
    fun measureTimes(s: Score, tempo: Int = s.tempo): DoubleArray {
        val sec = 60.0 / tempo / s.divisions
        val part = s.parts.firstOrNull() ?: return doubleArrayOf(0.0)
        val out = DoubleArray(part.measures.size + 1)
        var t = 0
        part.measures.forEachIndexed { i, m ->
            out[i] = t * sec
            val per = m.notes.filter { !it.chord }.groupBy { it.voice }.values.maxOfOrNull { l -> l.sumOf { it.duration } } ?: 0
            t += per
        }
        out[part.measures.size] = t * sec
        return out
    }

    fun render(s: Score, tempo: Int = s.tempo, volumes: List<Double> = emptyList(), fromSec: Double = 0.0, toSec: Double = Double.MAX_VALUE): ShortArray {
        val all = events(s, tempo).filter { it.startSec >= fromSec - 1e-6 && it.startSec < toSec }
        val end = (all.maxOfOrNull { minOf(it.startSec + it.durSec, toSec) } ?: fromSec) + 1.0
        val buf = DoubleArray(((end - fromSec) * RATE).toInt())
        for (e in all) {
            val f0 = 440.0 * 2.0.pow((e.midi - 69) / 12.0)
            val vol = volumes.getOrElse(e.part) { 1.0 } * 0.22
            val s0 = ((e.startSec - fromSec) * RATE).toInt().coerceAtLeast(0)
            val len = ((e.durSec + 0.5) * RATE).toInt()
            val relStart = ((e.durSec) * RATE).toInt()
            // Osciladores por recorrência (rotação de fasor): muito mais rápido que sin/exp por amostra.
            val nh = minOf(6, (9000.0 / f0).toInt().coerceAtLeast(1))
            val cr = DoubleArray(nh); val ci = DoubleArray(nh); val re = DoubleArray(nh); val im = DoubleArray(nh)
            val am = DoubleArray(nh); val dc = DoubleArray(nh)
            val base = 1.2 + f0 / 900.0
            for (h in 0 until nh) {
                val w = 2 * PI * f0 * (h + 1) / RATE
                cr[h] = kotlin.math.cos(w); ci[h] = kotlin.math.sin(w); re[h] = 1.0; im[h] = 0.0
                am[h] = 1.0 / (h + 1).toDouble().pow(1.3)
                dc[h] = exp(-(h * 1.5) / RATE)
            }
            val slow = exp(-base * 0.45 / RATE); val fast = exp(-base * 2.2 / RATE)
            var eS = 0.65; var eF = 0.35
            val relMul = exp(-14.0 / RATE)
            var rel = 1.0
            val atkLen = (0.004 * RATE).toInt()
            for (i in 0 until len) {
                val idx = s0 + i; if (idx >= buf.size) break
                var v = 0.0
                for (h in 0 until nh) {
                    v += im[h] * am[h]
                    val nr = re[h] * cr[h] - im[h] * ci[h]; val ni = re[h] * ci[h] + im[h] * cr[h]
                    re[h] = nr; im[h] = ni
                    am[h] *= dc[h]
                }
                eS *= slow; eF *= fast
                if (i >= relStart) rel *= relMul
                val atk = if (i < atkLen) i.toDouble() / atkLen else 1.0
                buf[idx] += v * (eS + eF) * rel * atk * vol
                if (i % 4096 == 4095) for (h in 0 until nh) { val m = kotlin.math.sqrt(re[h] * re[h] + im[h] * im[h]); re[h] /= m; im[h] /= m }
            }
        }
        val peak = buf.maxOfOrNull { kotlin.math.abs(it) } ?: 1.0
        val g = if (peak > 0.95) 0.95 / peak else 1.0
        return ShortArray(buf.size) { (buf[it] * g * 32767).toInt().coerceIn(-32768, 32767).toShort() }
    }

    fun wavBytes(pcm: ShortArray): ByteArray {
        val o = ByteArrayOutputStream()
        fun le32(v: Int) { o.write(v and 255); o.write(v shr 8 and 255); o.write(v shr 16 and 255); o.write(v shr 24 and 255) }
        fun le16(v: Int) { o.write(v and 255); o.write(v shr 8 and 255) }
        o.write("RIFF".toByteArray()); le32(36 + pcm.size * 2); o.write("WAVEfmt ".toByteArray())
        le32(16); le16(1); le16(1); le32(RATE); le32(RATE * 2); le16(2); le16(16)
        o.write("data".toByteArray()); le32(pcm.size * 2)
        for (x in pcm) le16(x.toInt())
        return o.toByteArray()
    }
}
