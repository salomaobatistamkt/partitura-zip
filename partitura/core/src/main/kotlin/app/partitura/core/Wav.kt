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

    /** Síntese em pedaços: o primeiro pedaço (1 s) fica pronto quase na hora, então o som começa sem espera mesmo em músicas longas. */
    class Prepared internal constructor(val total: Int, private val nextChunk: () -> ShortArray?) {
        /** Próximo pedaço de áudio, em ordem; null quando acabou. Não é seguro chamar de duas threads ao mesmo tempo. */
        fun next(): ShortArray? = nextChunk()
    }

    private fun softClip(x: Double): Short {
        val a = kotlin.math.abs(x)
        val y = if (a < 0.7) a else 0.7 + 0.3 * kotlin.math.tanh((a - 0.7) / 0.3)
        return ((if (x < 0) -y else y) * 32767).toInt().coerceIn(-32768, 32767).toShort()
    }

    fun prepare(s: Score, tempo: Int = s.tempo, volumes: List<Double> = emptyList(), fromSec: Double = 0.0, toSec: Double = Double.MAX_VALUE): Prepared {
        val all = events(s, tempo).filter { it.startSec >= fromSec - 1e-6 && it.startSec < toSec }.sortedBy { it.startSec }
        val end = (all.maxOfOrNull { minOf(it.startSec + it.durSec, toSec) } ?: fromSec) + 1.0
        val total = ((end - fromSec) * RATE).toInt().coerceAtLeast(1)
        val timbres = s.parts.map { Timbre.of(it.inst) }
        var pos = 0; var evIdx = 0
        var carry = DoubleArray(0)
        return Prepared(total) {
            if (pos >= total) null else {
                val len = minOf(if (pos == 0) RATE else 3 * RATE, total - pos)
                val w1 = pos + len
                val sel = ArrayList<Event>(); var maxEnd = w1
                while (evIdx < all.size) {
                    val e = all[evIdx]
                    val st = ((e.startSec - fromSec) * RATE).toInt().coerceAtLeast(0)
                    if (st >= w1 && w1 < total) break
                    sel += e; maxEnd = maxOf(maxEnd, st + ((e.durSec + 0.5) * RATE).toInt() + 1); evIdx++
                }
                val buf = DoubleArray(maxOf(maxEnd, pos + carry.size) - pos)
                System.arraycopy(carry, 0, buf, 0, carry.size)
                for (e in sel) {
                    val tb = timbres.getOrElse(e.part) { Timbre.PIANO }
                    val st = ((e.startSec - fromSec) * RATE).toInt().coerceAtLeast(0)
                    renderNote(e, tb, volumes.getOrElse(e.part) { 1.0 } * 0.22 * tb.gain, buf, st - pos)
                }
                val out = ShortArray(len) { softClip(buf[it]) }
                carry = if (buf.size > len) buf.copyOfRange(len, buf.size) else DoubleArray(0)
                pos = w1
                out
            }
        }
    }

    fun render(s: Score, tempo: Int = s.tempo, volumes: List<Double> = emptyList(), fromSec: Double = 0.0, toSec: Double = Double.MAX_VALUE): ShortArray {
        val p = prepare(s, tempo, volumes, fromSec, toSec)
        val out = ShortArray(p.total); var o = 0
        while (true) { val c = p.next() ?: break; System.arraycopy(c, 0, out, o, c.size); o += c.size }
        return out
    }

    /** Soma uma nota em [buf] a partir da amostra [s0] (pode ser negativa: nota que começou no pedaço anterior não ocorre, mas fica seguro). */
    private fun renderNote(e: Event, tb: Timbre, vol: Double, buf: DoubleArray, s0: Int) {
        val f0 = 440.0 * 2.0.pow((e.midi - 69) / 12.0)
        val len = ((e.durSec + 0.5) * RATE).toInt()
        val relStart = ((e.durSec) * RATE).toInt()
        // Osciladores por recorrência (rotação de fasor): muito mais rápido que sin/exp por amostra.
        val nh = minOf(tb.nh, (9000.0 / f0).toInt().coerceAtLeast(1))
        val cr = DoubleArray(nh); val ci = DoubleArray(nh); val re = DoubleArray(nh); val im = DoubleArray(nh)
        val am = DoubleArray(nh); val dc = DoubleArray(nh)
        val base = (1.2 + f0 / 900.0) * tb.decayScale
        var norm = 0.0
        for (h in 0 until nh) norm += tb.amp(h + 1, f0)
        val nrm = if (tb.sustained) 1.0 / maxOf(norm, 1e-6) * 2.2 else 1.0
        for (h in 0 until nh) {
            val w = 2 * PI * f0 * (h + 1) / RATE
            cr[h] = kotlin.math.cos(w); ci[h] = kotlin.math.sin(w); re[h] = 1.0; im[h] = 0.0
            am[h] = tb.amp(h + 1, f0) * nrm
            dc[h] = if (tb.sustained) 1.0 else exp(-(h * tb.harmDecay) / RATE)
        }
        val slow = exp(-base * 0.45 / RATE); val fast = exp(-base * 2.2 / RATE)
        var eS = 0.65; var eF = 0.35
        val relMul = exp(-tb.releaseRate / RATE)
        var rel = 1.0
        val atkLen = (tb.attack * RATE).toInt().coerceAtLeast(1)
        for (i in 0 until len) {
            val idx = s0 + i
            if (idx >= buf.size) break
            var v = 0.0
            for (h in 0 until nh) {
                v += im[h] * am[h]
                val nr = re[h] * cr[h] - im[h] * ci[h]; val ni = re[h] * ci[h] + im[h] * cr[h]
                re[h] = nr; im[h] = ni
                am[h] *= dc[h]
            }
            eS *= slow; eF *= fast
            if (i >= relStart) rel *= relMul
            if (idx >= 0) {
                val atk = if (i < atkLen) i.toDouble() / atkLen else 1.0
                val env = if (tb.sustained) 1.0 else (eS + eF)
                buf[idx] += v * env * rel * atk * vol
            }
            if (i % 4096 == 4095) for (h in 0 until nh) { val m = kotlin.math.sqrt(re[h] * re[h] + im[h] * im[h]); re[h] /= m; im[h] /= m }
        }
    }

    /** Um compasso de metrônomo: [beats] cliques, o primeiro mais agudo e forte. Dá para repetir em laço sem emenda. */
    fun clickLoop(beats: Int, beatSec: Double): ShortArray = clickLoopLens(List(beats.coerceAtLeast(1)) { 1.0 }, beatSec)

    /** Metrônomo com gestos de durações diferentes ([lens] em semínimas); o primeiro clique é o acento. */
    fun clickLoopLens(lens: List<Double>, quarterSec: Double): ShortArray {
        val starts = ArrayList<Int>(); var acc = 0.0
        for (l in lens) { starts += Math.round(acc * quarterSec * RATE).toInt(); acc += l }
        val total = Math.round(acc * quarterSec * RATE).toInt().coerceAtLeast(1)
        val out = ShortArray(total)
        for ((b, st) in starts.withIndex()) {
            val f = if (b == 0) 1850.0 else 1250.0
            val a = if (b == 0) 0.9 else 0.6
            val end = if (b + 1 < starts.size) starts[b + 1] else total
            val n = minOf(end - st, (0.045 * RATE).toInt())
            for (i in 0 until n) {
                val t = i.toDouble() / RATE
                out[st + i] = (sin(2 * PI * f * t) * exp(-t * 140.0) * a * 28000).toInt().toShort()
            }
        }
        return out
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
