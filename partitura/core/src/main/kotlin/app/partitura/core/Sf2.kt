package app.partitura.core

import java.io.DataInputStream
import java.io.InputStream
import kotlin.math.exp
import kotlin.math.pow

/**
 * Leitor e sintetizador mínimo de SoundFont 2 (.sf2): amostras reais com laço, envelope de volume
 * (atraso/ataque/sustentação/decaimento/soltura), afinação, filtro passa-baixa e intensidade (velocity).
 * Não implementa modulação por controladores, envelope de filtro nem LFO — só o que importa para tocar partitura.
 */
class Sf2 private constructor(
    private val smpl: ShortArray,
    private val headers: List<Hdr>,
    private val presets: Map<Int, List<Region>>,   // chave = banco*128 + programa
    val names: Map<Int, String>,
) {
    class Hdr(val name: String, val start: Int, val end: Int, val loopS: Int, val loopE: Int, val rate: Int, val pitch: Int, val corr: Int)
    class Region(val kLo: Int, val kHi: Int, val vLo: Int, val vHi: Int, val hdr: Hdr, val g: IntArray)

    fun has(program: Int, bank: Int = 0) = presets.containsKey(bank * 128 + program)

    fun regions(program: Int, key: Int, vel: Int, bank: Int = 0): List<Region> =
        (presets[bank * 128 + program] ?: presets[program] ?: emptyList()).filter { key in it.kLo..it.kHi && vel in it.vLo..it.vHi }

    /**
     * Soma a nota em [buf] (mono, [rate] Hz) a partir da amostra [s0] (≥ 0 ou negativa: a parte antes de 0 é cortada).
     * [holdSec] = quanto tempo a tecla fica apertada; depois entra a soltura. Devolve quantas amostras a nota ocupa.
     */
    fun render(program: Int, key: Int, vel: Int, holdSec: Double, gain: Double, buf: DoubleArray, s0: Int, rate: Int, maxReleaseSec: Double = 1.4): Int {
        var longest = 0
        val rs = regions(program, key, vel)
        val pick = if (rs.size > 2) rs.take(2) else rs      // no máximo 2 camadas (estéreo/duplo): economiza CPU
        for (r in pick) longest = maxOf(longest, one(r, key, vel, holdSec, gain / maxOf(1, pick.size).toDouble().pow(0.5), buf, s0, rate, maxReleaseSec))
        return longest
    }

    private fun one(r: Region, key: Int, vel: Int, holdSec: Double, gain: Double, buf: DoubleArray, s0: Int, rate: Int, maxRel: Double): Int {
        val g = r.g; val h = r.hdr
        val start = h.start + g[G_START] + g[G_START_C] * 32768
        val end = h.end + g[G_END] + g[G_END_C] * 32768
        val loopS = h.loopS + g[G_LOOPS] + g[G_LOOPS_C] * 32768
        val loopE = h.loopE + g[G_LOOPE] + g[G_LOOPE_C] * 32768
        val mode = g[G_MODE] and 3
        val loop = (mode == 1 || mode == 3) && loopE - loopS > 8
        val root = if (g[G_ROOT] >= 0) g[G_ROOT] else h.pitch
        val keyUse = if (g[G_KEYNUM] >= 0) g[G_KEYNUM] else key
        val cents = (keyUse - root) * g[G_SCALE] + g[G_COARSE] * 100 + g[G_FINE] + h.corr
        val step = 2.0.pow(cents / 1200.0) * h.rate / rate
        // envelope de volume
        fun tc(v: Int) = 2.0.pow(v / 1200.0)
        val delay = if (g[G_DELAY] <= -12000) 0.0 else tc(g[G_DELAY])
        val attack = tc(g[G_ATTACK]).coerceAtLeast(0.002)
        val hold = tc(g[G_HOLD] + (60 - keyUse) * g[G_KEY_HOLD])
        val decay = tc(g[G_DECAY] + (60 - keyUse) * g[G_KEY_DECAY]).coerceAtLeast(0.01)
        val sustDb = (g[G_SUSTAIN] / 10.0).coerceIn(0.0, 100.0)
        val release = tc(g[G_RELEASE]).coerceIn(0.01, maxRel)
        val att = g[G_ATTEN] / 10.0 * 0.7                       // 0,7: as amostras do banco já vêm baixas
        val vf = (vel / 127.0).pow(1.7)
        val amp = gain * 10.0.pow(-att / 20.0) * vf
        // filtro passa-baixa de 1 polo (mais "fechado" em notas fracas: pp soa mais macio)
        val fcCents = minOf(13500, g[G_FC]) - ((127 - vel) * 11)
        val fcHz = 8.176 * 2.0.pow(fcCents / 1200.0)
        val useLp = fcHz < rate * 0.42
        val lpA = if (useLp) 1.0 - exp(-2.0 * Math.PI * fcHz / rate) else 1.0
        var lp = 0.0
        val tHold = holdSec.coerceAtLeast(0.03)
        var pos = start.toDouble()
        var i = 0
        var dbAtRel = -1.0
        val dt = 1.0 / rate
        val maxLen = ((tHold + release * 1.6 + 0.2) * rate).toInt()
        val atkEnd = delay + attack; val holdEnd = atkEnd + hold
        while (i < maxLen) {
            val t = i * dt
            // nível em dB (0 = cheio)
            var db: Double
            if (t < delay) db = 100.0
            else if (t < atkEnd) db = -20.0 * Math.log10(((t - delay) / attack).coerceAtLeast(1e-5))
            else if (t < holdEnd) db = 0.0
            else db = minOf(sustDb, (t - holdEnd) / decay * 100.0)
            if (t >= tHold) {
                if (dbAtRel < 0) dbAtRel = db
                db = dbAtRel + (t - tHold) / release * 100.0
                if (db >= 96.0) break
            }
            val idx = s0 + i
            if (idx >= buf.size) break
            // amostra com interpolação linear
            val ip = pos.toInt()
            if (ip + 1 >= smpl.size) break
            val fr = pos - ip
            val s = (smpl[ip] * (1.0 - fr) + smpl[ip + 1] * fr) / 32768.0
            if (idx >= 0) {
                lp += (s - lp) * lpA
                val o = if (useLp) lp else s
                buf[idx] += o * amp * 10.0.pow(-db / 20.0)
            }
            pos += step
            if (loop) { if (pos >= loopE) pos -= (loopE - loopS) } else if (pos >= end) break
            i++
        }
        return i
    }

    companion object {
        private const val G_START = 0; private const val G_END = 1; private const val G_LOOPS = 2; private const val G_LOOPE = 3
        private const val G_START_C = 4; private const val G_FC = 8; private const val G_END_C = 12
        private const val G_PAN = 17; private const val G_DELAY = 33; private const val G_ATTACK = 34; private const val G_HOLD = 35
        private const val G_DECAY = 36; private const val G_SUSTAIN = 37; private const val G_RELEASE = 38
        private const val G_KEY_HOLD = 39; private const val G_KEY_DECAY = 40; private const val G_INSTR = 41
        private const val G_KEYRANGE = 43; private const val G_VELRANGE = 44; private const val G_LOOPS_C = 45; private const val G_KEYNUM = 46
        private const val G_ATTEN = 48; private const val G_LOOPE_C = 50; private const val G_COARSE = 51; private const val G_FINE = 52
        private const val G_SAMPLE = 53; private const val G_MODE = 54; private const val G_SCALE = 56; private const val G_ROOT = 58
        private const val NG = 64

        private fun defaults(): IntArray {
            val a = IntArray(NG)
            for (k in intArrayOf(G_DELAY, G_ATTACK, G_HOLD, G_DECAY, G_RELEASE)) a[k] = -12000
            a[G_FC] = 13500; a[G_SCALE] = 100; a[G_KEYNUM] = -1; a[G_ROOT] = -1
            a[G_KEYRANGE] = 0 or (127 shl 8); a[G_VELRANGE] = 0 or (127 shl 8)
            return a
        }

        private class Gen(val op: Int, val amt: Int)
        private class Zone(val gens: List<Gen>)

        private fun skipN(d: DataInputStream, n: Long) { var left = n; val t = ByteArray(8192); while (left > 0) { val r = d.read(t, 0, minOf(left, t.size.toLong()).toInt()); if (r < 0) throw java.io.EOFException(); left -= r } }
        private fun le16(b: ByteArray, o: Int) = (b[o].toInt() and 255) or ((b[o + 1].toInt() and 255) shl 8)
        private fun le32(b: ByteArray, o: Int) = le16(b, o) or (le16(b, o + 2) shl 16)
        private fun s16(b: ByteArray, o: Int) = le16(b, o).toShort().toInt()
        private fun str(b: ByteArray, o: Int, n: Int): String { var e = o; while (e < o + n && b[e].toInt() != 0) e++; return String(b, o, e - o, Charsets.ISO_8859_1) }

        /** Lê o arquivo em fluxo (não precisa ter tudo na memória de uma vez). */
        fun load(input: InputStream): Sf2 {
            val din = DataInputStream(input.buffered(1 shl 16))
            fun id(): String { val b = ByteArray(4); din.readFully(b); return String(b, Charsets.ISO_8859_1) }
            fun u32(): Long { val b = ByteArray(4); din.readFully(b); return le32(b, 0).toLong() and 0xffffffffL }
            require(id() == "RIFF"); u32(); require(id() == "sfbk")
            var smpl = ShortArray(0)
            val pd = HashMap<String, ByteArray>()
            while (true) {
                val cid = try { id() } catch (e: java.io.EOFException) { break }
                val size = u32()
                if (cid != "LIST") { skipN(din, size + (size and 1)); continue }
                val type = id(); var left = size - 4
                while (left > 8) {
                    val sid = id(); val ssz = u32(); left -= 8
                    val pad = ssz and 1
                    if (type == "sdta" && sid == "smpl") {
                        val n = (ssz / 2).toInt(); smpl = ShortArray(n)
                        val tmp = ByteArray(1 shl 16); var done = 0
                        while (done < n) {
                            val c = minOf(tmp.size / 2, n - done); din.readFully(tmp, 0, c * 2)
                            for (k in 0 until c) smpl[done + k] = ((tmp[2 * k].toInt() and 255) or (tmp[2 * k + 1].toInt() shl 8)).toShort()
                            done += c
                        }
                    } else if (type == "pdta") {
                        val b = ByteArray(ssz.toInt()); din.readFully(b); pd[sid] = b
                    } else skipN(din, ssz)
                    if (pad > 0) skipN(din, pad)
                    left -= ssz + pad
                }
                if (left > 0) skipN(din, left)
            }
            return build(smpl, pd)
        }

        private fun zones(bag: ByteArray, gen: ByteArray, from: Int, to: Int): List<Zone> {
            val out = ArrayList<Zone>()
            for (z in from until to) {
                val g0 = le16(bag, z * 4); val g1 = le16(bag, (z + 1) * 4)
                out += Zone((g0 until g1).map { Gen(le16(gen, it * 4), s16(gen, it * 4 + 2)) })
            }
            return out
        }

        private fun build(smpl: ShortArray, pd: Map<String, ByteArray>): Sf2 {
            val shdr = pd["shdr"]!!; val nS = shdr.size / 46 - 1
            val headers = (0 until nS).map { val o = it * 46; Hdr(str(shdr, o, 20), le32(shdr, o + 20), le32(shdr, o + 24), le32(shdr, o + 28), le32(shdr, o + 32), le32(shdr, o + 36), shdr[o + 40].toInt() and 255, shdr[o + 41].toInt()) }
            val inst = pd["inst"]!!; val ibag = pd["ibag"]!!; val igen = pd["igen"]!!
            val nI = inst.size / 22 - 1
            // regiões por instrumento (já com o global aplicado)
            val instRegions = ArrayList<List<Pair<IntArray, Hdr>>>()
            for (ii in 0 until nI) {
                val zs = zones(ibag, igen, le16(inst, ii * 22 + 20), le16(inst, (ii + 1) * 22 + 20))
                var glob: Zone? = null; val rl = ArrayList<Pair<IntArray, Hdr>>()
                for ((zi, z) in zs.withIndex()) {
                    val hasSample = z.gens.any { it.op == G_SAMPLE }
                    if (!hasSample) { if (zi == 0) glob = z; continue }
                    val a = defaults()
                    glob?.gens?.forEach { a[it.op.coerceAtMost(NG - 1)] = it.amt }
                    z.gens.forEach { a[it.op.coerceAtMost(NG - 1)] = it.amt }
                    val sid = a[G_SAMPLE]
                    if (sid in headers.indices) rl += a to headers[sid]
                }
                instRegions += rl
            }
            val phdr = pd["phdr"]!!; val pbag = pd["pbag"]!!; val pgen = pd["pgen"]!!
            val nP = phdr.size / 38 - 1
            val presets = HashMap<Int, List<Region>>(); val names = HashMap<Int, String>()
            val ADD_SKIP = setOf(G_KEYRANGE, G_VELRANGE, G_INSTR, G_SAMPLE, G_MODE, G_ROOT, G_KEYNUM, 0, 1, 2, 3, 4, 12, 45, 50)
            for (pi in 0 until nP) {
                val o = pi * 38
                val prog = le16(phdr, o + 20); val bank = le16(phdr, o + 22)
                val zs = zones(pbag, pgen, le16(phdr, o + 24), le16(phdr, (pi + 1) * 38 + 24))
                var glob: Zone? = null; val regs = ArrayList<Region>()
                for ((zi, z) in zs.withIndex()) {
                    val ins = z.gens.firstOrNull { it.op == G_INSTR }
                    if (ins == null) { if (zi == 0) glob = z; continue }
                    if (ins.amt !in instRegions.indices) continue
                    var pkL = 0; var pkH = 127; var pvL = 0; var pvH = 127
                    fun applyRanges(zz: Zone?) = zz?.gens?.forEach {
                        if (it.op == G_KEYRANGE) { pkL = maxOf(pkL, it.amt and 255); pkH = minOf(pkH, (it.amt shr 8) and 255) }
                        if (it.op == G_VELRANGE) { pvL = maxOf(pvL, it.amt and 255); pvH = minOf(pvH, (it.amt shr 8) and 255) }
                    }
                    applyRanges(glob); applyRanges(z)
                    for ((a0, hd) in instRegions[ins.amt]) {
                        val a = a0.copyOf()
                        fun add(zz: Zone?) = zz?.gens?.forEach { if (it.op !in ADD_SKIP && it.op < NG) a[it.op] += it.amt }
                        add(glob); add(z)
                        val kl = maxOf(a0[G_KEYRANGE] and 255, pkL); val kh = minOf((a0[G_KEYRANGE] shr 8) and 255, pkH)
                        val vl = maxOf(a0[G_VELRANGE] and 255, pvL); val vh = minOf((a0[G_VELRANGE] shr 8) and 255, pvH)
                        if (kl <= kh && vl <= vh) regs += Region(kl, kh, vl, vh, hd, a)
                    }
                }
                presets[bank * 128 + prog] = regs
                names[bank * 128 + prog] = str(phdr, o, 20)
            }
            return Sf2(smpl, headers, presets, names)
        }

        /** Programa General MIDI de cada timbre do app. */
        fun programOf(timbreId: String): Int = when (timbreId) {
            "piano", "piano_soft" -> 0
            "epiano" -> 4
            "guitar" -> 24
            "harp" -> 46
            "bass" -> 32
            "organ" -> 19
            "strings" -> 48
            "pad" -> 89
            "flute" -> 73
            "reed" -> 71
            "brass" -> 61
            "voice" -> 52
            "voice_oo" -> 53
            else -> 0
        }
    }
}

/** Banco de sons carregado (ou null: aí o app usa o sintetizador próprio). */
object SoundBank {
    @Volatile var sf2: Sf2? = null
}
