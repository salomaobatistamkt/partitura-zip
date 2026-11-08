package app.partitura.core

import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Escolhas da aba Criatividade. */
class CreativeSettings(
    val title: String = "Nova música",
    val fifths: Int = 0, val minor: Boolean = false,
    val beats: Int = 4, val beatType: Int = 4, val tempo: Int = 100,
    val seed: Long = 1,
    /** 0 calmo (notas longas), 1 médio, 2 movido (colcheias). */
    val density: Int = 1,
    /** alcance da melodia (MIDI): padrão G3–E5 */
    val low: Int = 55, val high: Int = 76,
    /** acordes por compasso (1 ou 2) e se usa sétimas em todos os acordes */
    val chordsPerBar: Int = 1, val sevenths: Boolean = false,
    /** permite começar com anacruse (sílaba fraca antes do tempo forte) */
    val pickup: Boolean = true,
    /** sequência de acordes escolhida pelo usuário (um item por compasso, repetida); null = o app escolhe os acordes */
    val chords: List<List<Harmony>>? = null,
)

/** Grade métrica do compasso: unidade de tempo, grupos acentuados e peso de cada pulso (colcheia). */
class Grid(val beats: Int, val beatType: Int) {
    val tickDiv = if (beatType >= 16) 1 else 2           // divisões (de semínima/4) por pulso
    val divs = 16 * beats / beatType                      // divisões por compasso
    val T = divs / tickDiv                                // pulsos por compasso
    val unit = max(1, (16 / beatType) / tickDiv)          // pulsos por tempo da fórmula
    val groups: List<Int> = accentGroups(beats, beatType) // em tempos da fórmula
    val groupStarts: List<Int>                            // pulso onde começa cada grupo
    val weights = FloatArray(T)
    val bounds: Set<Int>

    init {
        val gs = ArrayList<Int>(); var p = 0
        for (g in groups) { gs += p; p += g * unit }
        groupStarts = gs
        val b = HashSet<Int>(gs)
        if (beatType <= 4 || groups.all { it == 1 }) { var q = 0; while (q < T) { b += q; q += unit } }
        bounds = b
        for (t in 0 until T) {
            val gi = gs.indexOfLast { it <= t }
            weights[t] = when {
                t == 0 -> 4f
                t == gs[gi] -> if (gi == groups.size / 2 && groups.size >= 4 && groups.size % 2 == 0) 3f else 2.2f
                t % unit == 0 && beatType <= 4 -> 2f
                t % unit == 0 -> 1.4f
                else -> 1f
            }
        }
    }

    companion object {
        fun accentGroups(b: Int, t: Int): List<Int> = when {
            b == 1 -> listOf(1)
            b == 2 -> listOf(1, 1)
            b == 3 -> if (t >= 8) listOf(3) else listOf(1, 1, 1)
            b == 4 -> listOf(1, 1, 1, 1)
            b == 5 -> listOf(3, 2)
            b == 6 -> listOf(3, 3)
            b == 7 -> if (t >= 8) listOf(2, 2, 3) else listOf(4, 3)
            b == 8 -> if (t >= 8) listOf(3, 3, 2) else listOf(2, 2, 2, 2)
            b == 9 -> listOf(3, 3, 3)
            b == 10 -> listOf(3, 3, 2, 2)
            b == 11 -> listOf(3, 3, 3, 2)
            b == 12 -> listOf(3, 3, 3, 3)
            else -> { val g = ArrayList<Int>(); var rem = b; while (rem > 4) { g += 3; rem -= 3 }; if (rem == 4) { g += 2; g += 2 } else if (rem > 0) g += rem; g }
        }
    }
}

/** Tonalidade: escala, grafia das notas e conversão grau ↔ MIDI. */
class TonalKey(val fifths: Int, val minor: Boolean) {
    val tonicPc: Int = Math.floorMod(7 * fifths, 12).let { if (minor) (it + 9) % 12 else it }
    val scale: IntArray = if (minor) intArrayOf(0, 2, 3, 5, 7, 8, 10) else intArrayOf(0, 2, 4, 5, 7, 9, 11)
    private val tonicStep: Char = Spell.fromFifths(if (minor) fifths + 3 else fifths, 60 + tonicPc).step
    fun midi(deg: Int): Int = 60 + tonicPc + 12 * Math.floorDiv(deg, 7) + scale[Math.floorMod(deg, 7)]
    fun pcOf(deg: Int) = Math.floorMod(tonicPc + scale[Math.floorMod(deg, 7)], 12)

    /** Grafia de uma altura: diatônica pela armadura; sensível do modo menor com sustenido. */
    fun spell(midi: Int): Pitch {
        val pc = Math.floorMod(midi, 12)
        if (minor && pc == (tonicPc + 11) % 12) {
            val letter = Pitch.STEPS[(Pitch.STEPS.indexOf(tonicStep) + 6) % 7]
            val nat = Pitch.STEP_SEMITONES.getValue(letter)
            val a = Math.floorMod(midi - nat + 6, 12) - 6
            return Pitch(letter, a, (midi - nat - a) / 12 - 1)
        }
        return Spell.forMidi(midi, fifths)
    }
    fun spellPc(pc: Int): Pair<Char, Int> = spell(60 + pc).let { it.step to it.alter }
}

object Compose {
    private val DUR = intArrayOf(1, 2, 3, 4, 6, 8)
    private const val INF = 1e18

    class Slot(val t: Int, val d: Int)
    class Result(val score: Score, val grid: Grid, val key: TonalKey, val pickupTicks: Int, val lineBars: List<IntRange>)

    // ---------------------------------------------------------------- ritmo
    private fun onsetCost(w: Float, stress: Int): Double = when (stress) {
        2 -> (4 - w) * 1.1
        1 -> abs(2.2 - w) * 0.45
        else -> max(0f, w - 1.2f) * 0.9
    }.toDouble()

    private fun target(density: Int, stress: Int): Int = when (density) {
        0 -> if (stress == 2) 4 else if (stress == 1) 3 else 2
        2 -> if (stress == 2) 2 else 1
        else -> if (stress == 2) 2 else 1
    }

    /** Distribui as sílabas no tempo: programação dinâmica sobre toda a letra. Devolve início e duração (em pulsos) de cada sílaba. */
    fun rhythm(lines: List<Lyrics.Line>, st: CreativeSettings, g: Grid): Pair<List<Slot>, Int> {
        val syls = lines.flatMap { l -> l.syls.mapIndexed { i, s -> Triple(s, i == l.syls.lastIndex, l.cadence) } }
        val n = syls.size
        require(n > 0) { "Sem sílabas" }
        val T = g.T
        val maxT = n * 6 + (lines.size + 3) * T * 2 + T
        val cost = Array(n + 1) { DoubleArray(maxT + 1) { INF } }
        val backT = Array(n + 1) { IntArray(maxT + 1) { -1 } }
        val backD = Array(n + 1) { IntArray(maxT + 1) }
        val pickMax = if (st.pickup) g.groupStarts.getOrElse(1) { T } .coerceAtMost(T - 1).let { if (it <= 0) min(g.unit, T - 1) else min(it, T - 1) } else 0
        cost[0][0] = 0.0
        for (k in 1..pickMax) cost[0][T - k] = 0.35 + 0.05 * k
        val finalCost = DoubleArray(maxT + 1) { INF }; val finalFrom = IntArray(maxT + 1) { -1 }; val finalD = IntArray(maxT + 1)
        for (i in 0 until n) {
            val (s, lineLast, cad) = syls[i]
            val tgt = target(st.density, s.stress)
            for (t in 0..maxT) {
                val c = cost[i][t]
                if (c >= INF) continue
                val pos = t % T
                val w = g.weights[pos]
                for (d in DUR) {
                    if (d > T) continue
                    val t2 = t + d
                    if (t2 > maxT - T) continue
                    if (t / T != (t2 - 1) / T) continue                   // não atravessa a barra
                    if ((d == 3 || d == 6) && w < 2f) continue            // pontuada só em tempo
                    var c2 = c + onsetCost(w, s.stress) + 0.55 * abs(d - tgt) + (if (d == 3 || d == 6) 0.25 else 0.0)
                    // sincopa: nota que começa fraca e atravessa um pulso forte
                    var mx = 0f; for (q in pos + 1 until min(pos + d, T)) mx = max(mx, g.weights[q])
                    if (mx > w + 0.5f) c2 += 1.0 * (mx - w)
                    if (lineLast) {
                        if (cad >= 2) { if (d < 4) c2 += (4 - d) * 1.1 } else if (d < 4) c2 += (4 - d) * 0.45
                        val endPos = t2 % T
                        c2 += if (endPos == 0) -0.8 else if (g.bounds.contains(endPos)) 0.2 else 1.4
                        if (i == n - 1) {
                            val e = c2 + (if (endPos == 0) 0.0 else 2.0)
                            if (e < finalCost[t2]) { finalCost[t2] = e; finalFrom[t2] = t; finalD[t2] = d }
                        } else {
                            for (r in 0..T) {
                                val rc = when {
                                    cad >= 1 -> when (r) { 0 -> 2.5; 1 -> 1.3; 2 -> 0.3; else -> 0.3 + 0.18 * (r - 2) }
                                    else -> when (r) { 0 -> 0.8; 1 -> 0.3; 2 -> 0.2; else -> 0.2 + 0.28 * (r - 2) }
                                }
                                val t3 = t2 + r
                                if (t3 > maxT) break
                                val v = c2 + rc
                                if (v < cost[i + 1][t3]) { cost[i + 1][t3] = v; backT[i + 1][t3] = t; backD[i + 1][t3] = d }
                            }
                        }
                    } else if (c2 < cost[i + 1][t2]) { cost[i + 1][t2] = c2; backT[i + 1][t2] = t; backD[i + 1][t2] = d }
                }
            }
        }
        var bestEnd = -1; var bestC = INF
        for (t in 0..maxT) if (finalCost[t] < bestC) { bestC = finalCost[t]; bestEnd = t }
        check(bestEnd >= 0) { "Não consegui encaixar a letra no compasso" }
        val slots = arrayOfNulls<Slot>(n)
        var tt = finalFrom[bestEnd]; var dd = finalD[bestEnd]
        slots[n - 1] = Slot(tt, dd)
        var i = n - 1
        while (i > 0) {
            val pt = backT[i][tt]; val pd = backD[i][tt]
            slots[i - 1] = Slot(pt, pd)
            tt = pt; i--
        }
        val t0 = slots[0]!!.t
        return slots.map { it!! } to t0
    }

    // ---------------------------------------------------------------- melodia
    private val ROLE_HALF = intArrayOf(4, 1, 2)

    /** Grau-alvo (0..6) em que cada linha da estrofe termina. */
    private fun cadenceTargets(n: Int, rnd: Random): IntArray = IntArray(n) { j ->
        when {
            j == n - 1 -> 0
            n == 2 -> if (rnd.nextBoolean()) 4 else 1
            j == n / 2 - 1 && n >= 4 -> 4
            else -> ROLE_HALF[(j + rnd.nextInt(2)) % 3].let { if (it == 1 && rnd.nextInt(3) == 0) 2 else it }
        }
    }

    fun melodyDegrees(lines: List<Lyrics.Line>, slots: List<Slot>, st: CreativeSettings, g: Grid, key: TonalKey, rnd: Random, chordPcs: ((Int) -> Set<Int>?)? = null): List<Int> {
        // faixa de graus permitida
        fun tone(d: Int, set: Set<Int>) = key.pcOf(d) in set || (key.minor && Math.floorMod(d, 7) == 6 && (key.pcOf(d) + 1) % 12 in set)
        val lo = (-40..40).first { key.midi(it) >= st.low }
        val hi = (-40..40).last { key.midi(it) <= st.high }
        val center = (lo + hi) / 2
        // estrofes: linhas até cadência 2
        val stanzas = ArrayList<IntRange>(); var s0 = 0
        lines.forEachIndexed { i, l -> if (l.cadence >= 2 || i == lines.lastIndex) { stanzas += s0..i; s0 = i + 1 } }
        val degs = ArrayList<Int>()
        var idx = 0
        val firstOfShape = HashMap<String, List<Int>>()
        var prevEnd = center
        for (stz in stanzas) {
            val nl = stz.count()
            val cad = cadenceTargets(nl, rnd)
            val stanzaMem = ArrayList<Pair<Lyrics.Line, List<Int>>>()
            prevEnd = center
            for ((j, li) in stz.withIndex()) {
                val line = lines[li]
                val m = line.syls.size
                val startIdx = idx
                // linha com o mesmo desenho de acentos de uma anterior da estrofe: repete o contorno (motivo), mudando só o fim
                val sig = line.syls.joinToString("") { it.stress.toString() }
                val twin = stanzaMem.firstOrNull { it.first.syls.size == m && it.first.syls.joinToString("") { s -> s.stress.toString() } == sig && j >= 2 && chordPcs == null }
                val tgtCad = cad[j]
                // grau final: o mais próximo do centro com o resto desejado
                fun nearest(modDeg: Int, ref: Int): Int = (lo..hi).filter { Math.floorMod(it, 7) == modDeg }.minByOrNull { abs(it - ref) } ?: ref
                var endDeg = nearest(tgtCad, if (j == nl - 1) center - 1 else prevEnd)
                if (chordPcs != null) {
                    val set = chordPcs(slots[startIdx + m - 1].t)
                    if (set != null && !tone(endDeg, set)) endDeg = (lo..hi).filter { tone(it, set) }.minByOrNull { abs(it - endDeg) } ?: endDeg
                }
                val seq = IntArray(m)
                if (twin != null) {
                    val src = twin.second
                    for (k in 0 until m) seq[k] = src[k]
                    // ajusta o fim ao novo alvo
                    seq[m - 1] = endDeg
                    if (m >= 2) seq[m - 2] = (endDeg + (if (rnd.nextBoolean()) 1 else -1)).coerceIn(lo, hi)
                } else {
                    // ponto de partida: perto do fim da linha anterior, em grau do acorde (1, 3, 5)
                    val startSet = chordPcs?.invoke(slots[startIdx].t)
                    val starts = (lo..hi).filter { (if (startSet != null) tone(it, startSet) else Math.floorMod(it, 7) in intArrayOf(0, 2, 4)) && abs(it - center) <= 4 }.ifEmpty { (lo..hi).filter { abs(it - center) <= 4 } }
                    var pos = if (li == 0) starts.minByOrNull { abs(it - (center - 1)) + rnd.nextInt(3) } ?: center
                              else starts.minByOrNull { abs(it - prevEnd) + rnd.nextInt(2) } ?: prevEnd
                    val amp = 2 + rnd.nextInt(3)
                    val dirSign = if (rnd.nextInt(4) == 0) -1 else 1
                    var lastMove = 0
                    for (k in 0 until m) {
                        if (k == m - 1) { seq[k] = endDeg; break }
                        if (k == 0) { seq[0] = pos; continue }
                        val u = k.toDouble() / (m - 1)
                        val contour = pos0(seq[0], endDeg, u) + dirSign * amp * sin(Math.PI * u) * (1.0 - 0.3 * u)
                        val w = g.weights[slots[startIdx + k].t % g.T]
                        var bestM = 0; var bestS = -INF
                        val cands = ArrayList<Pair<Int, Double>>()
                        for (mv in -4..4) {
                            val np = pos + mv
                            if (np < lo || np > hi) continue
                            var sc = -abs(np - contour) * 1.0
                            sc -= when (abs(mv)) { 0 -> 0.9; 1 -> 0.0; 2 -> 0.35; 3 -> 1.3; else -> 2.0 }
                            val cs = chordPcs?.invoke(slots[startIdx + k].t)
                            if (cs != null) { if (tone(np, cs)) sc += (if (w >= 3f) 1.8 else if (w >= 2f) 1.2 else 0.4) else if (w >= 2f) sc -= 1.2 }
                            else if (w >= 3f && Math.floorMod(np, 7) in intArrayOf(0, 2, 4)) sc += 1.0
                            if (abs(lastMove) >= 3 && mv != 0 && (mv > 0) == (lastMove > 0)) sc -= 1.5
                            if (mv == 0 && k >= 2 && seq[k - 2] == pos) sc -= 1.5
                            // penúltima: aproxima do alvo final por grau conjunto
                            if (k == m - 2) sc -= abs(np - endDeg).let { if (it in 1..2) 0.0 else 2.0 }
                            cands += mv to sc
                        }
                        if (cands.isEmpty()) { seq[k] = pos; continue }
                        // sorteio ponderado (temperatura) com a semente
                        val maxSc = cands.maxOf { it.second }
                        val wts = cands.map { Math.exp((it.second - maxSc) / 0.55) }
                        var r = rnd.nextDouble() * wts.sum()
                        for ((ci, c) in cands.withIndex()) { r -= wts[ci]; if (r <= 0) { bestM = c.first; break } }
                        pos += bestM; lastMove = bestM
                        seq[k] = pos
                    }
                }
                stanzaMem += line to seq.toList()
                degs += seq.toList()
                prevEnd = seq[m - 1]
                idx += m
            }
        }
        return degs
    }

    private fun pos0(a: Int, b: Int, u: Double) = a + (b - a) * u

    // ---------------------------------------------------------------- monta a partitura
    private fun restChunks(g: Grid, startInBar: Int, len: Int): List<Int> {
        val out = ArrayList<Int>(); var p = startInBar; var rem = len
        val sortedBounds = g.bounds.sorted()
        while (rem > 0) {
            val next = sortedBounds.firstOrNull { it > p } ?: g.T
            var best = 1
            for (d in intArrayOf(8, 6, 4, 3, 2, 1)) {
                if (d > rem || p + d > g.T) continue
                val inside = p + d <= next
                val okAcross = !inside && p in g.bounds && ((p + d) in g.bounds || p + d == g.T) && (d != 6 || p == 0)
                if (inside || okAcross) { best = d; break }
            }
            out += best; p += best; rem -= best
        }
        return out
    }

    fun compose(text: String, st: CreativeSettings): Result {
        val lines = Lyrics.parse(text)
        require(lines.isNotEmpty()) { "Escreva ou cole uma letra." }
        return compose(lines, st)
    }

    fun compose(lines: List<Lyrics.Line>, st: CreativeSettings): Result {
        val g = Grid(st.beats, st.beatType)
        val key = TonalKey(st.fifths, st.minor)
        val rnd = Random(st.seed * 7919 + 17)
        val (slots, t0) = rhythm(lines, st, g)
        val prog = st.chords?.takeIf { it.isNotEmpty() }
        val firstFull = if (t0 > 0) 1 else 0
        val chordPcs: ((Int) -> Set<Int>?)? = if (prog == null) null else { t ->
            val b = t / g.T
            val lst = prog[(b - firstFull).coerceAtLeast(0) % prog.size]
            val pos = t % g.T
            val ats = Progression.positions(g, lst.size)
            lst[ats.indexOfLast { it <= pos }.coerceAtLeast(0).coerceAtMost(lst.size - 1)].pcs().toSet()
        }
        val degs = melodyDegrees(lines, slots, st, g, key, rnd, chordPcs)
        val syls = lines.flatMap { it.syls }
        val last = slots.last()
        val endT = last.t + last.d
        val totalTicks = ((endT + g.T - 1) / g.T) * g.T
        val firstBar = t0 / g.T                       // 0 (a anacruse fica no compasso 0)
        val s = Score(st.title, 4, st.fifths, st.beats, st.beatType, st.tempo)
        val part = Part("P1", "Voz", mutableListOf(), 'G', "P1", "voice")
        s.parts += part
        // notas com altura (sensível do modo menor sobe quando resolve na tônica)
        val midis = IntArray(degs.size) { key.midi(degs[it]) }
        if (st.minor) for (k in 0 until degs.size - 1) if (Math.floorMod(degs[k], 7) == 6 && degs[k + 1] == degs[k] + 1) midis[k] += 1
        if (st.minor && chordPcs != null) for (k in degs.indices) if (Math.floorMod(degs[k], 7) == 6) { val cs = chordPcs(slots[k].t); val pc = key.pcOf(degs[k]); if (cs != null && pc !in cs && (pc + 1) % 12 in cs) midis[k] = key.midi(degs[k]) + 1 }
        val nBars = totalTicks / g.T
        var si = 0
        for (b in 0 until nBars) {
            val bs = b * g.T
            val from = if (b == 0) t0 else bs
            val me = Measure(b + 1)
            var p = from
            while (p < bs + g.T) {
                if (si < slots.size && slots[si].t == p) {
                    val sl = slots[si]
                    val sy = syls[si]
                    me.notes += Note(key.spell(midis[si]), sl.d * g.tickDiv, 1, false, false, false, sy.text, sy.syl)
                    p += sl.d; si++
                } else {
                    val until = if (si < slots.size && slots[si].t < bs + g.T) slots[si].t else bs + g.T
                    for (d in restChunks(g, p - bs, until - p)) { me.notes += Note(null, d * g.tickDiv); p += d }
                }
            }
            part.measures += me
        }
        // pickup: o primeiro compasso é parcial (já construído a partir de t0)
        if (prog != null) Progression.apply(s, prog) else harmonize(s, part, slots, midis, lines, st, g, key, t0)
        return Result(s, g, key, if (t0 > 0) g.T - t0 else 0, emptyList())
    }

    // ---------------------------------------------------------------- harmonia
    private class Cand(val deg: Int, val rootOff: Int, val kind: String, val sevKind: String)

    private fun candidates(minor: Boolean): List<Cand> = if (!minor) listOf(
        Cand(0, 0, "major", "major-seventh"), Cand(1, 2, "minor", "minor-seventh"), Cand(2, 4, "minor", "minor-seventh"),
        Cand(3, 5, "major", "major-seventh"), Cand(4, 7, "major", "dominant"), Cand(5, 9, "minor", "minor-seventh"), Cand(6, 11, "diminished", "half-diminished"),
    ) else listOf(
        Cand(0, 0, "minor", "minor-seventh"), Cand(1, 2, "diminished", "half-diminished"), Cand(2, 3, "major", "major-seventh"),
        Cand(3, 5, "minor", "minor-seventh"), Cand(4, 7, "major", "dominant"), Cand(5, 8, "major", "major-seventh"), Cand(6, 10, "major", "dominant"),
    )

    // pontuação funcional de passar de um grau a outro
    private val TRANS = Array(7) { DoubleArray(7) }.also { m ->
        fun set(a: Int, vararg v: Pair<Int, Double>) { for ((b, x) in v) m[a][b] = x }
        set(0, 3 to 1.0, 4 to 1.0, 5 to 0.9, 1 to 0.8, 2 to 0.5, 6 to 0.2, 0 to -0.5)
        set(1, 4 to 1.6, 6 to 0.4, 3 to 0.2, 0 to -0.2, 1 to -0.5)
        set(2, 5 to 1.0, 3 to 0.8, 1 to 0.4, 0 to 0.2, 2 to -0.5)
        set(3, 4 to 1.3, 0 to 1.0, 1 to 0.8, 5 to 0.3, 3 to -0.5, 2 to 0.1)
        set(4, 0 to 1.9, 5 to 1.0, 3 to -0.4, 4 to -0.4, 2 to 0.1)
        set(5, 1 to 1.0, 3 to 1.0, 4 to 0.9, 2 to 0.5, 0 to 0.1, 5 to -0.5)
        set(6, 0 to 1.2, 2 to 0.5, 5 to 0.2, 6 to -0.5)
    }

    /** Janelas de acorde: (compasso, pulso inicial, pulso final). */
    private fun windows(nBars: Int, g: Grid, st: CreativeSettings, t0: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        val split = if (st.chordsPerBar >= 2 && g.groups.size >= 2 && g.T >= 4) {
            // fronteira de grupo mais perto da metade
            g.groupStarts.drop(1).minByOrNull { abs(it - g.T / 2) } ?: g.T / 2
        } else -1
        for (b in 0 until nBars) {
            if (b == 0 && t0 > 0) continue                 // anacruse não tem acorde
            if (split > 0) { out += intArrayOf(b, 0, split); out += intArrayOf(b, split, g.T) } else out += intArrayOf(b, 0, g.T)
        }
        return out
    }

    private fun harmonize(s: Score, part: Part, slots: List<Slot>, midis: IntArray, lines: List<Lyrics.Line>, st: CreativeSettings, g: Grid, key: TonalKey, t0: Int) {
        val nBars = part.measures.size
        val wins = windows(nBars, g, st, t0)
        if (wins.isEmpty()) return
        val cands = candidates(st.minor)
        // notas finais de linha e seus papéis
        val lineEndSyl = HashMap<Int, Int>()      // índice da sílaba -> cadência da linha
        var c = 0
        for (l in lines) { c += l.syls.size; lineEndSyl[c - 1] = l.cadence }
        val n = wins.size
        val emis = Array(n) { DoubleArray(7) }
        for ((wi, w) in wins.withIndex()) {
            val ws = w[0] * g.T + w[1]; val we = w[0] * g.T + w[2]
            for (k in slots.indices) {
                val sl = slots[k]
                val a = max(sl.t, ws); val z = min(sl.t + sl.d, we)
                if (z <= a) continue
                val pc = Math.floorMod(midis[k], 12)
                val wt = g.weights[sl.t % g.T]
                val weight = (z - a) * (0.6 + 0.4 * wt / 4.0)
                for (ci in cands.indices) {
                    val h = key.pcOf(cands[ci].deg - 0).let { Math.floorMod(key.tonicPc + cands[ci].rootOff, 12) }
                    val kindIv = Harmony.KINDS.getValue(cands[ci].kind).first
                    val tone = kindIv.any { (h + it) % 12 == pc }
                    emis[wi][ci] += if (tone) weight else if (wt >= 2f && sl.t >= a) -1.2 * weight else -0.25 * weight
                }
                // cadência: a última nota da linha pede acorde de repouso
                if (sl.t + sl.d == z && lineEndSyl.containsKey(k)) {
                    val cad = lineEndSyl[k]!!
                    val isLastLine = k == slots.size - 1
                    if (isLastLine || cad >= 2 && false) emis[wi][0] += 6.0
                    else if (cad >= 1) { emis[wi][4] += 1.8; if (Math.floorMod(midis[k] - key.tonicPc, 12) in intArrayOf(0, 4, 7, 3)) emis[wi][0] += 1.2 }
                    else emis[wi][4] += 1.2
                    if (wi > 0) { /* a janela anterior prefere dominante ou subdominante antes do repouso final */
                        if (isLastLine) { emis[wi - 1][4] += 1.4; emis[wi - 1][3] += 0.9 }
                    }
                }
            }
        }
        emis[0][0] += 2.0
        emis[n - 1][0] += 5.0
        // Viterbi
        val best = Array(n) { DoubleArray(7) { -INF } }; val from = Array(n) { IntArray(7) }
        for (ci in 0 until 7) best[0][ci] = emis[0][ci]
        for (wi in 1 until n) for (ci in 0 until 7) for (pj in 0 until 7) {
            val v = best[wi - 1][pj] + TRANS[pj][ci] + emis[wi][ci]
            if (v > best[wi][ci]) { best[wi][ci] = v; from[wi][ci] = pj }
        }
        var cur = (0 until 7).maxByOrNull { best[n - 1][it] } ?: 0
        val chosen = IntArray(n)
        for (wi in n - 1 downTo 0) { chosen[wi] = cur; cur = from[wi][cur] }
        for (wi in 0 until n) {
            val cd = cands[chosen[wi]]
            val nextDeg = chosen.getOrNull(wi + 1)?.let { cands[it].deg }
            val sev = st.sevenths || (cd.deg == 4 && (nextDeg == 0 || wi == n - 1 && false))
            val kind = if (sev) cd.sevKind else cd.kind
            val pc = Math.floorMod(key.tonicPc + cd.rootOff, 12)
            val (step, alter) = key.spellPc(pc)
            part.measures[wins[wi][0]].harmony += Harmony(wins[wi][1] * g.tickDiv, step, alter, kind)
        }
    }
}
