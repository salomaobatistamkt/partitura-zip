package app.partitura.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Ordem de execução (ritornelos e casas 1/2) e linha do tempo com andamento variável (rit./accel./a tempo). */
object Playlist {
    private fun flag(s: Score, mi: Int, f: (Measure) -> Boolean) = s.parts.any { p -> p.measures.getOrNull(mi)?.let(f) == true }
    private fun voltaOf(s: Score, mi: Int) = s.parts.firstNotNullOfOrNull { p -> p.measures.getOrNull(mi)?.volta?.takeIf { it > 0 } } ?: 0

    fun measureCount(s: Score) = s.parts.maxOfOrNull { it.measures.size } ?: 0

    /** Índices (0-based) dos compassos na ordem em que soam. Sem [expand] devolve 0..n-1. */
    fun order(s: Score, expand: Boolean = true): List<Int> {
        val n = measureCount(s)
        if (!expand) return (0 until n).toList()
        val out = ArrayList<Int>()
        var i = 0; var startIdx = 0; var pass = 1
        var prevVolta = 0
        var guard = 0
        while (i < n && guard++ < n * 12 + 50) {
            if (flag(s, i) { it.repeatStart } && !(out.isNotEmpty() && out.last() == i)) { if (startIdx != i || prevVolta > 0) { pass = 1 }; startIdx = i }
            val v = voltaOf(s, i)
            if (v == 0 && prevVolta > 0) { pass = 1; startIdx = i }
            prevVolta = v
            if (v > 0 && v != pass && !(v == 2 && pass > 2 && !hasVolta(s, i, pass))) { i++; continue }
            out += i
            if (flag(s, i) { it.repeatEnd }) {
                val times = timesFor(s, startIdx, i)
                if (pass < times) { pass++; i = startIdx; prevVolta = 0; continue }
                pass = 1; startIdx = i + 1; prevVolta = 0
            }
            i++
        }
        return out
    }

    private fun hasVolta(s: Score, from: Int, number: Int): Boolean {
        var k = from
        while (k < measureCount(s) && voltaOf(s, k) > 0) { if (voltaOf(s, k) == number) return true; k++ }
        return false
    }

    /** Quantas vezes a seção [start]..[end] toca: 2, ou o maior número de casa que aparece logo depois do fim. */
    private fun timesFor(s: Score, start: Int, end: Int): Int {
        var mx = 2
        var k = start
        while (k <= end + 12 && k < measureCount(s)) { mx = max(mx, voltaOf(s, k)); k++ }
        return mx.coerceIn(2, 4)
    }

    private val LEVEL = mapOf("pppp" to 20, "ppp" to 28, "pp" to 38, "p" to 50, "mp" to 62, "mf" to 76, "f" to 92, "ff" to 108, "fff" to 120, "ffff" to 127,
        "sf" to 100, "sfz" to 104, "sffz" to 112, "fz" to 100, "rfz" to 104, "fp" to 50)
    fun level(mark: String): Int? = LEVEL[mark]

    class Played(val mi: Int, val startDiv: Int, val lenDiv: Int, val t0: Double, val t1: Double, val sec0: Double, val secLen: Double)

    class Timeline(val divisions: Int, val played: List<Played>, val measureCount: Int, private val vel: Map<Int, DynTimeline>, private val starts: IntArray = IntArray(0)) {
        /** Posição na partitura (semínimas, sem repetições) do instante [sec]. */
        fun scoreQ(sec: Double): Double = scoreQ(sec) { mi -> starts.getOrElse(mi) { 0 }.toDouble() }
        /** Instante (s) da primeira vez que soa a posição [q] da partitura (semínimas, sem repetições). */
        fun secOfScoreQ(q: Double): Double {
            if (starts.size < 2 || played.isEmpty()) return 0.0
            val d = q * divisions
            var mi = 0
            while (mi < starts.size - 2 && starts[mi + 1] <= d) mi++
            val k = played.indexOfFirst { it.mi == mi }
            if (k < 0) return 0.0
            return secIn(k, (d - starts[mi]).coerceIn(0.0, played[k].lenDiv.toDouble()))
        }
        val totalSec: Double get() = played.lastOrNull()?.let { it.sec0 + it.secLen } ?: 0.0
        val totalDiv: Int get() = played.lastOrNull()?.let { it.startDiv + it.lenDiv } ?: 0

        fun secIn(k: Int, off: Double): Double {
            val p = played[k]; val L = max(p.lenDiv, 1).toDouble()
            val d = p.t1 - p.t0
            val per = 60.0 / divisions
            return p.sec0 + if (abs(d) < 1e-6) per * off / p.t0 else per * L / d * ln(((p.t0 + d * off / L).coerceAtLeast(1.0)) / p.t0)
        }
        /** Segundos de uma posição absoluta (divisões da linha do tempo expandida). */
        fun secOfDiv(div: Double): Double {
            val k = kOfDiv(div)
            return secIn(k, div - played[k].startDiv)
        }
        fun kOfDiv(div: Double): Int {
            var lo = 0; var hi = played.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (played[mid].startDiv <= div + 1e-9) lo = mid else hi = mid - 1 }
            return lo
        }
        /** Posição na partitura (em semínimas, sem repetições) do instante [sec]; serve ao cursor da tela. */
        fun scoreQ(sec: Double, firstDivOf: (Int) -> Double): Double {
            if (played.isEmpty()) return 0.0
            var lo = 0; var hi = played.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (played[mid].sec0 <= sec + 1e-9) lo = mid else hi = mid - 1 }
            val p = played[lo]
            val frac = if (p.secLen <= 0) 0.0 else ((sec - p.sec0) / p.secLen).coerceIn(0.0, 1.0)
            return firstDivOf(p.mi) / divisions + frac * p.lenDiv / divisions
        }
        fun velocity(part: Int, absDiv: Double): Int = vel[part]?.at(absDiv) ?: 80
        /** Início (s) da primeira vez que cada compasso soa; o último item é a duração total. */
        fun measureStarts(): DoubleArray {
            val out = DoubleArray(measureCount + 1) { -1.0 }
            for (p in played) if (out[p.mi] < 0) out[p.mi] = p.sec0
            var last = 0.0
            for (i in out.indices) { if (out[i] < 0) out[i] = last else last = out[i] }
            out[measureCount] = totalSec
            return out
        }
    }

    class DynTimeline(private val steps: List<Pair<Double, Int>>, private val ramps: List<DoubleArray>) {
        fun at(t: Double): Int {
            for (r in ramps) if (t >= r[0] && t < r[1]) return (r[2] + (r[3] - r[2]) * (t - r[0]) / (r[1] - r[0])).toInt()
            var v = 76
            for ((tt, vv) in steps) { if (tt <= t + 1e-9) v = vv else break }
            return v
        }
    }

    /** Monta a linha do tempo. [tempo] é o andamento escolhido para tocar; os andamentos escritos na partitura são escalados por ele. */
    fun timeline(s: Score, tempo: Int = s.tempo, expand: Boolean = true, plain: Boolean = false): Timeline {
        val n = measureCount(s)
        val div = s.divisions.coerceAtLeast(1)
        val bar = div * 4 * s.beats / s.beatType
        val len = IntArray(n) { mi ->
            var mx = 0
            for (p in s.parts) {
                val m = p.measures.getOrNull(mi) ?: continue
                val v = m.notes.filter { !it.chord }.groupBy { it.voice }.values.maxOfOrNull { l -> l.sumOf { it.duration } } ?: 0
                mx = max(mx, v)
            }
            if (mx <= 0) bar else mx
        }
        val order = order(s, expand && !plain)
        val ratio = if (plain) 1.0 else tempo.toDouble() / s.tempo.coerceAtLeast(1)
        val played = ArrayList<Played>()
        var base = tempo.toDouble()
        var startDiv = 0; var sec = 0.0
        var ritLeft = 0; var ritTotal = 0; var ritFactor = 1.0; var ritBase = base
        for ((k, mi) in order.withIndex()) {
            val tm = if (plain) null else s.parts.firstNotNullOfOrNull { it.measures.getOrNull(mi)?.tempo }
            if (tm != null) { base = tm * ratio; ritLeft = 0 }
            val rit = if (plain) 0 else s.parts.firstNotNullOfOrNull { it.measures.getOrNull(mi)?.rit?.takeIf { r -> r != 0 } } ?: 0
            if (rit != 0 && ritLeft == 0) {
                ritFactor = (1 + rit / 100.0).coerceIn(0.5, 1.3)
                val next = order.getOrNull(k + 1)
                val nextPlain = next != null && s.parts.none { it.measures.getOrNull(next)?.let { m -> m.tempo != null || m.rit != 0 } == true }
                ritTotal = if (nextPlain) 2 else 1; ritLeft = ritTotal; ritBase = base
            }
            val t0: Double; val t1: Double
            if (ritLeft > 0) {
                val j = ritTotal - ritLeft
                t0 = ritBase * (1 + (ritFactor - 1) * j / ritTotal); t1 = ritBase * (1 + (ritFactor - 1) * (j + 1) / ritTotal)
                ritLeft--
            } else { t0 = base; t1 = base }
            val L = len[mi]
            val per = 60.0 / div
            val d = t1 - t0
            val secLen = if (abs(d) < 1e-6) per * L / t0 else per * L / d * ln(t1 / t0)
            played += Played(mi, startDiv, L, t0.coerceAtLeast(1.0), t1.coerceAtLeast(1.0), sec, secLen)
            startDiv += L; sec += secLen
        }
        // dinâmicas por parte
        val vel = HashMap<Int, DynTimeline>()
        if (!plain) {
            val own = s.parts.indices.map { pi ->
                val l = ArrayList<Pair<Double, String>>()
                for (pl in played) s.parts[pi].measures.getOrNull(pl.mi)?.dyn?.forEach { l += (pl.startDiv + it.at).toDouble() to it.mark }
                l.sortedBy { it.first }
            }
            val global = own.maxByOrNull { it.size } ?: emptyList()
            for (pi in s.parts.indices) {
                var marks = own[pi]
                if (marks.isEmpty()) {
                    val g = s.parts[pi].group
                    marks = s.parts.indices.firstOrNull { it != pi && s.parts[it].group == g && own[it].isNotEmpty() }?.let { own[it] } ?: global
                }
                vel[pi] = buildDyn(marks, div * 8)
            }
        }
        val starts = IntArray(n + 1); for (i in 0 until n) starts[i + 1] = starts[i] + len[i]
        return Timeline(div, played, n, vel, starts)
    }

    private fun buildDyn(marks: List<Pair<Double, String>>, defaultSpan: Int): DynTimeline {
        val steps = ArrayList<Pair<Double, Int>>(); val ramps = ArrayList<DoubleArray>()
        var cur = 76
        var i = 0
        while (i < marks.size) {
            val (t, m) = marks[i]
            val lv = LEVEL[m]
            if (lv != null) { steps += t to lv; cur = lv; i++; continue }
            if (m == "cresc" || m == "dim") {
                val dir = if (m == "cresc") 1 else -1
                var j = i + 1; var tEnd: Double? = null; var target: Int? = null
                while (j < marks.size) {
                    val (tj, mj) = marks[j]
                    if (mj == "end") { tEnd = tj; j++; break }
                    if (LEVEL[mj] != null) { tEnd = tj; target = LEVEL[mj]; break }
                    if (mj == "cresc" || mj == "dim") { tEnd = tj; break }
                    j++
                }
                val end = tEnd ?: (t + defaultSpan)
                if (target == null) {
                    val nx = marks.getOrNull(j)
                    if (nx != null && LEVEL[nx.second] != null && nx.first - end <= defaultSpan / 8.0) target = LEVEL[nx.second]
                }
                val tv = (target ?: (cur + dir * 22)).coerceIn(16, 127)
                if (end > t) ramps += doubleArrayOf(t, end, cur.toDouble(), tv.toDouble())
                steps += end to tv; cur = tv
                i = if (marks.getOrNull(j)?.first == end && LEVEL[marks.getOrNull(j)?.second ?: ""] != null) j else maxOf(j, i + 1)
                continue
            }
            i++
        }
        return DynTimeline(steps.sortedBy { it.first }, ramps)
    }
}
