package app.partitura.core

import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Sintetizador aditivo de piano. O andamento é aplicado na síntese, então o tom nunca muda. */
object Synth {
    const val RATE = 44100

    class Event(val startSec: Double, val durSec: Double, val midi: Int, val part: Int, val vel: Int = 80, val voice: Int = 1, val mi: Int = 0)

    /**
     * Notas com tempo em segundos. Respeita ritornelos/casas, dinâmicas (pp…ff, crescendo), rit./accel. e mudanças de andamento.
     * [plain] ignora tudo isso (ordem natural, andamento fixo, intensidade padrão): usado para exportar MIDI e nos testes.
     */
    fun events(s: Score, tempo: Int = s.tempo, expand: Boolean = true, plain: Boolean = false): List<Event> =
        events(s, Playlist.timeline(s, tempo, expand, plain))

    fun events(s: Score, tl: Playlist.Timeline): List<Event> {
        val ev = ArrayList<Event>()
        s.parts.forEachIndexed { pi, p ->
            val open = HashMap<Int, Int>() // midi -> índice do evento ligado
            var prevMi = -2
            for ((k, pl) in tl.played.withIndex()) {
                if (pl.mi != prevMi + 1) open.clear()
                prevMi = pl.mi
                val m = p.measures.getOrNull(pl.mi) ?: continue
                val voiceTime = HashMap<Int, Int>()
                var lastStart = 0
                for (n in m.notes) {
                    val off = if (n.chord) lastStart else (voiceTime[n.voice] ?: 0)
                    if (!n.chord) { voiceTime[n.voice] = off + n.duration; lastStart = off }
                    val pt = n.pitch ?: continue
                    val st = tl.secIn(k, off.toDouble()); val en = tl.secIn(k, (off + n.duration).toDouble())
                    if (n.tieStop && open.containsKey(pt.midi)) {
                        val idx = open[pt.midi]!!; val old = ev[idx]
                        ev[idx] = Event(old.startSec, en - old.startSec, old.midi, pi, old.vel, old.voice, old.mi)
                        if (!n.tieStart) open.remove(pt.midi)
                    } else {
                        ev += Event(st, en - st, pt.midi, pi, tl.velocity(pi, (pl.startDiv + off).toDouble()), n.voice, pl.mi)
                        if (n.tieStart) open[pt.midi] = ev.size - 1
                    }
                }
            }
        }
        return ev
    }

    /** Início de cada compasso (segundos, na primeira vez que soa); tamanho = compassos + 1 (o último é a duração total, com repetições). */
    fun measureTimes(s: Score, tempo: Int = s.tempo, expand: Boolean = true): DoubleArray = Playlist.timeline(s, tempo, expand).measureStarts()

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

    /** Reverberação de sala (Schroeder): dá corpo e tira o som seco/"amador". Mantém estado entre os pedaços. */
    private class Room(wet: Double) {
        private val w = wet
        private val cd = intArrayOf(1557, 1617, 1491, 1422, 1277, 1356)
        private val cb = Array(6) { DoubleArray(cd[it]) }; private val ci = IntArray(6); private val lp = DoubleArray(6)
        private val ad = intArrayOf(556, 441, 341)
        private val ab = Array(3) { DoubleArray(ad[it]) }; private val ai = IntArray(3)
        fun process(x: DoubleArray, n: Int) {
            for (i in 0 until n) {
                val inp = x[i] * 0.3
                var acc = 0.0
                for (c in 0 until 6) {
                    val d = cb[c][ci[c]]
                    lp[c] = d * 0.72 + lp[c] * 0.28
                    cb[c][ci[c]] = inp + lp[c] * 0.84
                    if (++ci[c] >= cd[c]) ci[c] = 0
                    acc += d
                }
                var y = acc * 0.28
                for (a in 0 until 3) {
                    val bv = ab[a][ai[a]]
                    val out = -y + bv
                    ab[a][ai[a]] = y + bv * 0.5
                    if (++ai[a] >= ad[a]) ai[a] = 0
                    y = out
                }
                x[i] += y * w
            }
        }
    }

    fun prepare(s: Score, tempo: Int = s.tempo, volumes: List<Double> = emptyList(), fromSec: Double = 0.0, toSec: Double = Double.MAX_VALUE, expand: Boolean = true, mix: Mix? = null): Prepared {
        val all = events(s, tempo, expand).filter { it.startSec >= fromSec - 1e-6 && it.startSec < toSec }.sortedBy { it.startSec }
        val end = (all.maxOfOrNull { minOf(it.startSec + it.durSec, toSec) } ?: fromSec) + 1.0
        val total = ((end - fromSec) * RATE).toInt().coerceAtLeast(1)
        val timbres = s.parts.map { Timbre.of(it.inst) }
        val bank = if (useSoundBank) SoundBank.sf2 else null
        val room = Room(if (bank != null) 0.28 else 0.5)
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
                    sel += e; maxEnd = maxOf(maxEnd, st + ((e.durSec + (if (bank != null) 2.6 else 0.5)) * RATE).toInt() + 1); evIdx++
                }
                val buf = DoubleArray(maxOf(maxEnd, pos + carry.size) - pos)
                System.arraycopy(carry, 0, buf, 0, carry.size)
                for (e in sel) {
                    val st = ((e.startSec - fromSec) * RATE).toInt().coerceAtLeast(0)
                    if (mix == null) {
                        val tb = timbres.getOrElse(e.part) { Timbre.PIANO }
                        val v = volumes.getOrElse(e.part) { 1.0 }
                        if (bank != null) renderSf(bank, e, tb, v, buf, st - pos) else renderNote(e, tb, v * 0.22 * tb.gain, buf, st - pos)
                    } else for (ti in mix.tracksFor(e.part, e.voice, e.mi)) {
                        if (!mix.audible(ti)) continue
                        val tb = Timbre.of(mix.timbreId(ti, s))
                        val v = mix.volume[ti] * volumes.getOrElse(e.part) { 1.0 }
                        if (bank != null) renderSf(bank, e, tb, v, buf, st - pos) else renderNote(e, tb, v * 0.22 * tb.gain, buf, st - pos)
                    }
                }
                room.process(buf, len)
                val out = ShortArray(len) { softClip(buf[it]) }
                carry = if (buf.size > len) buf.copyOfRange(len, buf.size) else DoubleArray(0)
                pos = w1
                out
            }
        }
    }

    fun render(s: Score, tempo: Int = s.tempo, volumes: List<Double> = emptyList(), fromSec: Double = 0.0, toSec: Double = Double.MAX_VALUE, expand: Boolean = true, mix: Mix? = null): ShortArray {
        val p = prepare(s, tempo, volumes, fromSec, toSec, expand, mix)
        val out = ShortArray(p.total); var o = 0
        while (true) { val c = p.next() ?: break; System.arraycopy(c, 0, out, o, c.size); o += c.size }
        return out
    }

    /** Liga/desliga o banco de sons realista (SoundFont) quando ele estiver carregado. */
    @Volatile var useSoundBank = true

    /** Ganho por timbre quando se usa o SoundFont (coro e pads vêm mais fortes nas amostras, piano menos). */
    private fun sfGain(id: String) = when (id) { "voice", "voice_oo" -> 1.3; "strings", "pad" -> 1.2; "organ" -> 1.1; "brass", "reed" -> 1.2; "bass" -> 2.0; "piano_soft" -> 1.35; else -> 1.55 }

    private fun renderSf(sf: Sf2, e: Event, tb: Timbre, vol: Double, buf: DoubleArray, s0: Int) {
        val prog = Sf2.programOf(tb.id)
        val hold = if (tb.id == "piano" || tb.id == "piano_soft" || tb.id == "harp" || tb.id == "guitar" || tb.id == "epiano") e.durSec + 0.12 else e.durSec
        sf.render(prog, e.midi.coerceIn(0, 127), (if (tb.id == "piano_soft") e.vel * 0.85 else e.vel.toDouble()).toInt().coerceIn(1, 127), hold, vol * sfGain(tb.id), buf, s0, RATE)
    }

    /** Soma uma nota em [buf] a partir da amostra [s0] (pode ser negativa: nota que começou no pedaço anterior não ocorre, mas fica seguro). */
    private fun renderNote(e: Event, tb: Timbre, vol0: Double, buf: DoubleArray, s0: Int) {
        val f0 = 440.0 * 2.0.pow((e.midi - 69) / 12.0)
        val len = ((e.durSec + 0.5) * RATE).toInt()
        val relStart = ((e.durSec) * RATE).toInt()
        val vol = vol0 * (e.vel / 80.0).pow(1.4)
        val ns = tb.strings.coerceIn(1, 3)
        val bri = ((e.vel / 127.0) / (80.0 / 127.0)).pow(tb.bright)        // >1 quando toca forte
        val nh = minOf(tb.nh, (11000.0 / f0).toInt().coerceAtLeast(1))
        val n = nh * ns
        val cr = DoubleArray(n); val ci = DoubleArray(n); val re = DoubleArray(n); val im = DoubleArray(n)
        val am = DoubleArray(n); val dc = DoubleArray(n); val fr = DoubleArray(n)
        val base = (1.2 + f0 / 900.0) * tb.decayScale
        var norm = 0.0
        val hamp = DoubleArray(nh)
        for (h in 0 until nh) {
            val hf = f0 * (h + 1)
            var a = tb.amp(h + 1, f0)
            if (tb.strike > 0) a *= 0.35 + 0.65 * kotlin.math.abs(sin(PI * (h + 1) * tb.strike)).pow(0.7)
            a /= kotlin.math.sqrt(1.0 + (hf / tb.fc).pow(4))
            a *= bri.pow(h * 0.8).coerceAtMost(1.6)
            hamp[h] = a; norm += a
        }
        val nrm = if (tb.sustained) 1.0 / maxOf(norm, 1e-6) * 2.2 else 1.0
        for (si in 0 until ns) {
            val dt = if (ns == 1) 0.0 else (si - (ns - 1) / 2.0) * tb.detune
            val ratio = 2.0.pow(dt / 1200.0)
            for (h in 0 until nh) {
                val k = si * nh + h
                val stretch = kotlin.math.sqrt(1.0 + tb.inharm * (h + 1.0) * (h + 1.0))
                fr[k] = f0 * (h + 1) * stretch * ratio
                val w = 2 * PI * fr[k] / RATE
                cr[k] = kotlin.math.cos(w); ci[k] = kotlin.math.sin(w)
                // fase inicial levemente diferente por corda evita somar tudo em fase (menos "buzina")
                val ph = si * 1.7 + h * 0.31
                re[k] = kotlin.math.cos(ph); im[k] = kotlin.math.sin(ph)
                am[k] = hamp[h] * nrm / ns
                dc[k] = if (tb.sustained) 1.0 else exp(-(h * tb.harmDecay * (1.0 + 0.25 * si)) / RATE)
            }
        }
        val slow = exp(-base * 0.45 / RATE); val fast = exp(-base * 2.2 / RATE)
        var eS = 0.65; var eF = 0.35
        val relMul = exp(-tb.releaseRate / RATE)
        var rel = 1.0
        val atkLen = (tb.attack * RATE).toInt().coerceAtLeast(1)
        var seed = (e.midi * 7919L + (e.startSec * 1000).toLong()) and 0x7fffffffL
        var nz = 0.0
        val vibOn = tb.vibrato > 0
        val vibDepth = tb.vibrato / 1200.0 * kotlin.math.ln(2.0)
        for (i in 0 until len) {
            val idx = s0 + i
            if (idx >= buf.size) break
            if (vibOn && i % 32 == 0) {
                val t = i.toDouble() / RATE
                val v = if (t < tb.vibDelay) 0.0 else sin(2 * PI * tb.vibRate * (t - tb.vibDelay)) * kotlin.math.min(1.0, (t - tb.vibDelay) / 0.4) * vibDepth
                val mul = exp(v)
                for (k in 0 until n) { val w = 2 * PI * fr[k] * mul / RATE; cr[k] = kotlin.math.cos(w); ci[k] = kotlin.math.sin(w) }
            }
            var v = 0.0
            for (k in 0 until n) {
                v += im[k] * am[k]
                val nr = re[k] * cr[k] - im[k] * ci[k]; val ni = re[k] * ci[k] + im[k] * cr[k]
                re[k] = nr; im[k] = ni
                am[k] *= dc[k]
            }
            eS *= slow; eF *= fast
            if (i >= relStart) rel *= relMul
            if (idx >= 0) {
                val atk = if (i < atkLen) i.toDouble() / atkLen else 1.0
                val env = if (tb.sustained) 1.0 else (eS + eF)
                var out = v * env * rel * atk
                if (tb.hammer > 0 && i < 1800) {
                    seed = (seed * 1103515245L + 12345L) and 0x7fffffffL
                    val r = (seed / 1073741824.0) - 1.0
                    nz = nz * 0.82 + r * 0.18               // ruído filtrado (grave): "toc" do martelo
                    out += nz * tb.hammer * exp(-i / 260.0) * 0.9 * (0.4 + e.vel / 127.0) * (if (f0 < 500) 1.0 else 500.0 / f0)
                }
                buf[idx] += out * vol
            }
            if (i % 4096 == 4095) for (k in 0 until n) { val m = kotlin.math.sqrt(re[k] * re[k] + im[k] * im[k]); re[k] /= m; im[k] /= m }
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
