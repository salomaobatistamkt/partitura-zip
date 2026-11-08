package app.partitura.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Nota posicionada. [pos] = posição na pauta contando meios-espaços a partir da linha de baixo (null = pausa). */
data class LNote(val m: Int, val n: Int, val x: Float, val pos: Int?, val q: Float, val acc: Int?, val chord: Boolean,
                 val stemUp: Boolean, val dots: Int, val tie: Boolean)

class LRow(val part: Int, val firstMeasure: Int, val first: Boolean, val notes: List<LNote>, val bars: List<Float>, val left: Float)

class LayoutResult(val rows: List<LRow>, val s: Score, val width: Float) {
    fun rowsOf(part: Int) = rows.filter { it.part == part }
}

object Layout {
    private val SHARP_T = listOf('F' to 5, 'C' to 5, 'G' to 5, 'D' to 5, 'A' to 4, 'E' to 5, 'B' to 4)
    private val SHARP_B = listOf('F' to 3, 'C' to 3, 'G' to 3, 'D' to 3, 'A' to 2, 'E' to 3, 'B' to 2)
    private val FLAT_T = listOf('B' to 4, 'E' to 5, 'A' to 4, 'D' to 5, 'G' to 4, 'C' to 5, 'F' to 4)
    private val FLAT_B = listOf('B' to 2, 'E' to 3, 'A' to 2, 'D' to 3, 'G' to 2, 'C' to 3, 'F' to 2)

    fun staffPos(clef: Char, step: Char, octave: Int): Int {
        val idx = octave * 7 + Pitch.STEPS.indexOf(step)
        return idx - if (clef == 'F') 2 * 7 + 4 else 4 * 7 + 2
    }

    /** Posições (meios-espaços) dos acidentes da armadura. */
    fun keySigPositions(clef: Char, fifths: Int): List<Int> {
        val list = if (fifths >= 0) (if (clef == 'F') SHARP_B else SHARP_T) else (if (clef == 'F') FLAT_B else FLAT_T)
        return list.take(kotlin.math.abs(fifths)).map { staffPos(clef, it.first, it.second) }
    }

    private fun advance(q: Float) = 22f + 18f * sqrt(min(max(q, 0.25f), 4f))

    fun layout(s: Score, width: Float): LayoutResult {
        val rows = mutableListOf<LRow>()
        val sigW = kotlin.math.abs(s.fifths) * 10f + 6f
        s.parts.forEachIndexed { pi, part ->
            var mi = 0
            var first = true
            while (mi < part.measures.size) {
                val left = 36f + sigW + (if (first) 24f else 0f)
                val avail = width - left - 8f
                val notes = mutableListOf<LNote>()
                val bars = mutableListOf<Float>()
                var x = 0f
                val start = mi
                while (mi < part.measures.size) {
                    val m = part.measures[mi]
                    val w = 16f + max(m.notes.filter { !it.chord }.sumOf { advance(it.duration.toFloat() / s.divisions).toDouble() }.toFloat(), 26f)
                    if (x + w > avail && mi > start) break
                    val state = HashMap<Char, Int>()
                    for (st in Pitch.STEPS) state[st] = Spell.keyAlter(s.fifths, st)
                    var nx = x + 12f
                    var lastX = nx
                    m.notes.forEachIndexed { ni, n ->
                        val q = n.duration.toFloat() / s.divisions
                        val px = if (n.chord) lastX else nx
                        var acc: Int? = null
                        val pt = n.pitch
                        var pos: Int? = null
                        if (pt != null) {
                            pos = staffPos(part.clef, pt.step, pt.octave)
                            if (state[pt.step] != pt.alter) { acc = pt.alter; state[pt.step] = pt.alter }
                        }
                        val dots = if (q == 1.5f || q == 3f || q == 0.75f || q == 0.375f || q == 6f) 1 else 0
                        notes += LNote(mi, ni, left + px, pos, q, acc, n.chord, (pos ?: 4) < 4, dots, n.tieStart)
                        if (!n.chord) { lastX = nx; nx += advance(q) }
                    }
                    x += w
                    bars += left + x
                    mi++
                }
                rows += LRow(pi, part.measures.getOrNull(start)?.number ?: (start + 1), first, notes, bars, left)
                first = false
            }
        }
        return LayoutResult(rows, s, width)
    }
}
