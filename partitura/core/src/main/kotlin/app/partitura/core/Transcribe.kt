package app.partitura.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Nota detectada no áudio (tempos em segundos, altura em MIDI, força 0..1). */
class NoteEvent(val start: Double, val end: Double, val midi: Int, val amp: Double = 1.0)

class TranscribeOptions(
    val title: String = "Gravação",
    /** andamento (♩ por minuto); null = estimar pelas notas */
    val tempo: Double? = null,
    val beats: Int = 4, val beatType: Int = 4,
    /** segundo em que o compasso 1 começa; null = primeira nota */
    val downbeat: Double? = null,
    /** 0 = fiel (piano, 2 pautas, todas as notas); 1 = melodia + acordes; 2 = melodia + acordes + acompanhamento de piano */
    val mode: Int = 0,
    val pianoStyle: String = "balada",
    val instrument: String = "piano",
    /** notas mais curtas que isto (s) são descartadas */
    val minNote: Double = 0.07,
    /** tom forçado (armadura -7..7); null = detectar */
    val fifths: Int? = null,
    val minor: Boolean = false,
)

class Transcribed(val score: Score, val tempo: Double, val fifths: Int, val minor: Boolean, val bars: Int, val notes: Int)

/** Notas detectadas → partitura: andamento, quantização, tom, melodia (nota mais aguda), acordes e pautas. */
object ScoreFromNotes {
    private class Q(val s: Int, val e: Int, val midi: Int, val amp: Double)
    private class Seg(val s: Int, val e: Int, val p: List<Int>)

    // ------------------------------------------------------------------ andamento
    /** Melhor andamento (♩/min) e fase da grade de colcheias (s), pela concentração dos começos de nota. */
    fun estimateTempo(ev: List<NoteEvent>): Pair<Double, Double> {
        if (ev.size < 3) return 100.0 to (ev.firstOrNull()?.start ?: 0.0)
        val on = ev.map { it.start }.toDoubleArray(); val w = ev.map { 0.4 + it.amp }.toDoubleArray()
        var bestT = 100.0; var bestS = -1.0; var bestPh = on[0]
        var T = 50.0
        while (T <= 190.0) {
            val p = 60.0 / T / 2.0   // colcheia
            var cx = 0.0; var cy = 0.0; var sw = 0.0
            for (i in on.indices) { val a = 2 * PI * on[i] / p; cx += w[i] * cos(a); cy += w[i] * sin(a); sw += w[i] }
            val r = sqrt(cx * cx + cy * cy) / sw
            // grade de semínima: só começos "no tempo" contam em dobro (distingue T de 2T)
            val p4 = 60.0 / T
            var qx = 0.0; var qy = 0.0
            for (i in on.indices) { val a = 2 * PI * on[i] / p4; qx += w[i] * cos(a); qy += w[i] * sin(a) }
            val rq = sqrt(qx * qx + qy * qy) / sw
            val prior = exp(-((T - 100.0) / 48.0) * ((T - 100.0) / 48.0))
            val s = (0.75 * r + 0.25 * rq) * (0.6 + 0.4 * prior)
            if (s > bestS) { bestS = s; bestT = T; bestPh = atan2(cy, cx) / (2 * PI) * p }
            T += 0.5
        }
        return bestT.roundToInt().toDouble() to bestPh
    }

    // ------------------------------------------------------------------ tom e acordes
    private val MAJ = doubleArrayOf(6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88)
    private val MIN = doubleArrayOf(6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17)

    private fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average(); var n = 0.0; var da = 0.0; var db = 0.0
        for (i in a.indices) { n += (a[i] - ma) * (b[i] - mb); da += (a[i] - ma) * (a[i] - ma); db += (b[i] - mb) * (b[i] - mb) }
        return if (da <= 0 || db <= 0) 0.0 else n / sqrt(da * db)
    }

    private val FIFTHS_OF_MAJOR_PC = intArrayOf(0, -5, 2, -3, 4, -1, 6, 1, -4, 3, -2, 5)   // dó=0, ré♭=-5 ...

    /** Tom pelo histograma de classes de altura ponderado pela duração (Krumhansl). */
    fun detectKey(notes: List<Pair<Int, Double>>): Pair<Int, Boolean> {
        val h = DoubleArray(12)
        for ((m, d) in notes) h[Math.floorMod(m, 12)] += d
        if (h.sum() <= 0) return 0 to false
        var best = 0 to false; var bs = -9.0
        for (t in 0 until 12) for (minor in listOf(false, true)) {
            val prof = if (minor) MIN else MAJ
            val rot = DoubleArray(12) { prof[Math.floorMod(it - t, 12)] }
            var s = pearson(h, rot)
            if (minor) s -= 0.015
            if (s > bs) { bs = s; best = t to minor }
        }
        val relMajor = if (best.second) (best.first + 3) % 12 else best.first
        return FIFTHS_OF_MAJOR_PC[relMajor] to best.second
    }

    private val CH_KINDS = listOf("major" to doubleArrayOf(1.2, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.8, 0.0, 0.0, 0.0, 0.0),
        "minor" to doubleArrayOf(1.2, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.8, 0.0, 0.0, 0.0, 0.0),
        "dominant" to doubleArrayOf(1.2, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.8, 0.0, 0.0, 0.6, 0.0),
        "minor-seventh" to doubleArrayOf(1.2, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.8, 0.0, 0.0, 0.6, 0.0),
        "major-seventh" to doubleArrayOf(1.2, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.8, 0.0, 0.0, 0.0, 0.6),
        "diminished" to doubleArrayOf(1.2, 0.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0))

    /** Melhor acorde para um vetor de croma (12 posições) e a classe do baixo (ou -1). */
    private fun bestChords(chroma: DoubleArray, bassPc: Int, key: TonalKey): List<Pair<Int, Pair<String, Double>>> {
        val tot = chroma.sum().coerceAtLeast(1e-9)
        val out = ArrayList<Pair<Int, Pair<String, Double>>>()
        val scale = (0 until 7).map { key.pcOf(it) }.toSet()
        for (root in 0 until 12) for ((kind, tpl) in CH_KINDS) {
            var s = 0.0
            for (pc in 0 until 12) {
                val c = chroma[pc] / tot
                val tw = tpl[Math.floorMod(pc - root, 12)]
                s += if (tw > 0) c * tw else -c * 0.9
            }
            if (bassPc == root) s += 0.18
            val simple = kind == "major" || kind == "minor"
            if (!simple) s -= 0.12
            if (root in scale) s += 0.06
            out += root to (kind to s)
        }
        return out
    }

    // ------------------------------------------------------------------ pautas
    private fun bounds(beats: Int, beatType: Int): Set<Int> {
        val unit = 16 / beatType
        val gs = Grid.accentGroups(beats, beatType)
        val b = HashSet<Int>(); var p = 0
        for (g in gs) { b += p; p += g * unit }
        if (beatType <= 4) { var q = 0; while (q < beats * unit) { b += q; q += unit } }
        return b
    }

    private val SIZES = intArrayOf(16, 12, 8, 6, 4, 3, 2, 1)

    private fun chunk(p: Int, rem: Int, barTicks: Int, bounds: Set<Int>, mid: Int): Int {
        for (d in SIZES) {
            if (d > rem) continue
            val e = p + d
            if (d > 1) {
                val crossesBound = bounds.any { it > p && it < e }
                if (crossesBound && !(p in bounds && (e in bounds || e == barTicks))) continue
                if (mid in (p + 1) until e && !(p == 0 && d == barTicks)) continue
            }
            return d
        }
        return 1
    }

    private fun measuresFrom(segs: List<Seg>, barStarts: List<Int>, barEnd: Int, beats: Int, beatType: Int, key: TonalKey): MutableList<Measure> {
        val barTicks = 16 * beats / beatType
        val bnd = bounds(beats, beatType)
        val mid = if (beats >= 4 && beats % 2 == 0) barTicks / 2 else if (beatType >= 8 && beats % 3 == 0 && beats >= 6) barTicks / 2 else -1
        val out = ArrayList<Measure>()
        val sorted = segs.sortedBy { it.s }
        for ((bi, bs) in barStarts.withIndex()) {
            val be = if (bi + 1 < barStarts.size) barStarts[bi + 1] else barEnd
            val len = be - bs
            val off = barTicks - len   // compasso inicial incompleto: a posição métrica começa no fim do compasso
            val notes = ArrayList<Note>()
            var pos = bs
            for (sg in sorted) {
                if (sg.e <= bs || sg.s >= be) continue
                val s = max(sg.s, bs); val e = min(sg.e, be)
                if (s < pos) continue
                // pausa até a nota
                var gap = s - pos
                while (gap > 0) { val d = chunk(pos - bs + off, gap, barTicks, bnd, mid); notes += Note(null, d); pos += d; gap -= d }
                var rem = e - s; var first = true
                while (rem > 0) {
                    val d = chunk(pos - bs + off, rem, barTicks, bnd, mid)
                    val last = rem - d == 0
                    val tieStart = !last || sg.e > be
                    val tieStop = !first || sg.s < bs
                    sg.p.sorted().forEachIndexed { k, m -> notes += Note(key.spell(m), d, 1, k > 0, tieStart, tieStop) }
                    pos += d; rem -= d; first = false
                }
            }
            var gap = be - pos
            while (gap > 0) { val d = chunk(pos - bs + off, gap, barTicks, bnd, mid); notes += Note(null, d); pos += d; gap -= d }
            out += Measure(bi + 1, notes)
        }
        return out
    }

    // ------------------------------------------------------------------ montagem
    fun build(events0: List<NoteEvent>, o: TranscribeOptions): Transcribed {
        val ev = events0.filter { it.end - it.start >= o.minNote && it.midi in 21..108 }.sortedBy { it.start }
        require(ev.isNotEmpty()) { "Não encontrei notas no áudio." }
        val (estT, phase) = if (o.tempo == null) estimateTempo(ev) else (o.tempo to 0.0)
        val tempo = o.tempo ?: estT
        val tickSec = 60.0 / tempo / 4.0           // semicolcheia
        val first = ev.first().start
        val t0 = o.downbeat ?: run {
            // ponto da grade de colcheias mais próximo da primeira nota
            val p = tickSec * 2
            if (o.tempo == null) phase + Math.round((first - phase) / p) * p else first
        }
        val barTicks = 16 * o.beats / o.beatType
        // quantiza
        val qs = ev.map { e ->
            val s = ((e.start - t0) / tickSec).roundToInt()
            val en = ((e.end - t0) / tickSec).roundToInt()
            Q(s, max(en, s + 1), e.midi, e.amp)
        }
        val minTick = qs.minOf { it.s }
        val lead = if (minTick < 0) ((-minTick - 1) % barTicks + 1) else 0     // anacruse (ticks antes do compasso 1)
        val shift = if (minTick < 0) lead else 0
        val q = qs.map { Q(it.s + shift, it.e + shift, it.midi, it.amp) }
        val total = q.maxOf { it.e }
        val barStarts = ArrayList<Int>()
        if (lead > 0) barStarts += 0
        var b0 = lead
        while (b0 < total || barStarts.isEmpty()) { barStarts += b0; b0 += barTicks }
        val barEnd = barStarts.last() + barTicks
        val nBars = barStarts.size

        // tom
        val (fifths, minor) = if (o.fifths != null) (o.fifths to o.minor) else detectKey(q.map { it.midi to (it.e - it.s).toDouble() })
        val key = TonalKey(fifths, minor)
        val s = Score(o.title, 4, fifths, o.beats, o.beatType, tempo.roundToInt())

        // melodia = nota mais aguda em cada instante
        fun skyline(): List<Seg> {
            val ticks = total
            val top = IntArray(ticks) { -1 }; val topId = IntArray(ticks) { -1 }
            for ((i, n) in q.withIndex()) for (t in n.s until min(n.e, ticks)) if (n.midi > top[t]) { top[t] = n.midi; topId[t] = i }
            val res = ArrayList<Seg>(); var t = 0
            while (t < ticks) {
                if (top[t] < 0) { t++; continue }
                var u = t; while (u + 1 < ticks && topId[u + 1] == topId[t]) u++
                res += Seg(t, u + 1, listOf(top[t])); t = u + 1
            }
            return res
        }

        fun partFrom(segs: List<Seg>, id: String, name: String, clef: Char, inst: String): Part =
            Part(id, name, measuresFrom(segs, barStarts, barEnd, o.beats, o.beatType, key), clef, id, inst)

        if (o.mode == 0) {
            // piano fiel: mão direita (>= dó central) e esquerda; notas com o mesmo começo viram acorde
            fun cluster(sel: List<Q>): List<Seg> {
                val by = sel.groupBy { it.s }.toSortedMap()
                val keys = by.keys.toList(); val segs = ArrayList<Seg>()
                for ((i, k) in keys.withIndex()) {
                    val g = by.getValue(k)
                    val nxt = keys.getOrNull(i + 1) ?: Int.MAX_VALUE
                    val e = min(g.maxOf { it.e }, nxt)
                    segs += Seg(k, max(e, k + 1), g.map { it.midi }.distinct())
                }
                return segs
            }
            // ponto de divisão entre as mãos: o "vale" de menos notas perto do dó central (evita cortar uma melodia no meio)
            val mass = DoubleArray(128)
            for (n in q) mass[n.midi] += (n.e - n.s).toDouble()
            var split = 60; var bestCost = Double.MAX_VALUE
            for (sp in 55..64) {
                val cost = mass[sp - 1] + mass[sp] + 0.6 * (mass[sp - 2] + mass[sp + 1]) + 0.35 * Math.abs(sp - 60) * (mass.sum() / 400.0)
                if (cost < bestCost - 1e-9) { bestCost = cost; split = sp }
            }
            val rh = q.filter { it.midi >= split }; val lh = q.filter { it.midi < split }
            s.parts += partFrom(cluster(rh), "P1", "Piano (pauta 1)", 'G', "piano")
            s.parts += Part("P1-2", "Piano (pauta 2)", measuresFrom(cluster(lh), barStarts, barEnd, o.beats, o.beatType, key), 'F', "P1", "piano")
        } else {
            val sky = skyline()
            val avg = sky.map { it.p[0] }.average()
            val inst = Instruments.byId(o.instrument)
            s.parts += partFrom(sky, "P1", if (inst != null) inst.name else "Melodia", if (avg < 55) 'F' else 'G', inst?.timbre?.id ?: "piano")
        }

        // acordes (janelas de meio compasso ou compasso) sobre todas as notas
        val half = if (o.beats >= 4 || (o.beatType >= 8 && o.beats >= 6)) barTicks / 2 else barTicks
        val wins = ArrayList<IntArray>()    // [barIndex, startInBar, endInBar]
        for (bi in 0 until nBars) {
            val bs = barStarts[bi]; val be = if (bi + 1 < nBars) barStarts[bi + 1] else barEnd
            if (be - bs < barTicks) continue       // anacruse não recebe acorde
            var p = 0; while (p < barTicks) { wins += intArrayOf(bi, p, min(p + half, barTicks)); p += half }
        }
        if (wins.isNotEmpty()) {
            val chroma = Array(wins.size) { DoubleArray(12) }; val bass = IntArray(wins.size) { -1 }
            for ((wi, w) in wins.withIndex()) {
                val ws = barStarts[w[0]] + w[1]; val we = barStarts[w[0]] + w[2]
                var lowest = 999
                for (n in q) {
                    val a = max(n.s, ws); val z = min(n.e, we); if (z <= a) continue
                    val wgt = (z - a) * (0.5 + n.amp) * (if (n.midi < 52) 1.4 else 1.0)
                    chroma[wi][Math.floorMod(n.midi, 12)] += wgt
                    if (n.midi < lowest && (z - a) >= 2) lowest = n.midi
                }
                bass[wi] = if (lowest < 999) Math.floorMod(lowest, 12) else -1
            }
            val cand = wins.indices.map { bestChords(chroma[it], bass[it], key) }
            val nC = cand[0].size
            val best = Array(wins.size) { DoubleArray(nC) { -1e9 } }; val from = Array(wins.size) { IntArray(nC) }
            for (c in 0 until nC) best[0][c] = cand[0][c].second.second
            for (w in 1 until wins.size) {
                val prevBest = best[w - 1].indices.maxByOrNull { best[w - 1][it] }!!
                for (c in 0 until nC) {
                    var bv = -1e9; var bj = 0
                    // transição: ficar no mesmo acorde é grátis; trocar custa 0.14 (limita a pesquisa ao melhor anterior e ao mesmo acorde)
                    val stay = best[w - 1][c]
                    val sw = best[w - 1][prevBest] - 0.14
                    if (stay >= sw) { bv = stay; bj = c } else { bv = sw; bj = prevBest }
                    best[w][c] = bv + cand[w][c].second.second; from[w][c] = bj
                }
            }
            var cur = best.last().indices.maxByOrNull { best.last()[it] }!!
            val pick = IntArray(wins.size)
            for (w in wins.size - 1 downTo 0) { pick[w] = cur; cur = from[w][cur] }
            val part0 = s.parts[0]
            var prev: Pair<Int, String>? = null
            for ((wi, w) in wins.withIndex()) {
                val (root, kk) = cand[wi][pick[wi]].let { it.first to it.second.first }
                // notas muito escassas na janela: repete o acorde anterior
                val empty = chroma[wi].sum() < 1e-6
                val now = if (empty && prev != null) prev else root to kk
                val newBar = wi == 0 || wins[wi - 1][0] != w[0]
                if (newBar || now != prev) {
                    val (st, al) = key.spellPc(now.first)
                    part0.measures[w[0]].harmony += Harmony(w[1], st, al, now.second)
                }
                prev = now
            }
        }
        if (o.mode == 2) {
            val withPiano = Arrange.addPiano(s, o.pianoStyle)
            return Transcribed(withPiano, tempo, fifths, minor, nBars, ev.size)
        }
        return Transcribed(s, tempo, fifths, minor, nBars, ev.size)
    }
}
