package app.partitura.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Gravação de partitura: transforma a Score numa lista de desenhos em "espaços de pauta" (sp).
 * 1 sp = distância entre duas linhas da pauta. Os glifos são da fonte Bravura (SMuFL, licença OFL).
 * O mesmo resultado é desenhado no Android (Canvas) e, nos testes, em java.awt.
 */

sealed class Item { abstract val note: Int }
/** Glifo SMuFL com origem (x, y = linha de base) em sp. Tamanho da fonte = 4 sp. */
class Glyph(val code: Int, val x: Float, val y: Float, override val note: Int = -1) : Item()
class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val w: Float, override val note: Int = -1) : Item()
/** Viga: paralelogramo entre (x1,y1)-(x2,y2) com espessura vertical [h]. */
class Beam(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val h: Float, override val note: Int = -1) : Item()
class Curve(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val up: Boolean, override val note: Int = -1) : Item()
class Brace(val x: Float, val y1: Float, val y2: Float) : Item() { override val note = -1 }
class Txt(val s: String, val x: Float, val y: Float, val size: Float, val bold: Boolean = false) : Item() { override val note = -1 }

/** Nota (ou pausa, midi = -1) com tempos em semínimas desde o início. */
class SNote(val part: Int, val m: Int, val idx: Int, val startQ: Double, val endQ: Double, val midi: Int)

class Anchor(val q: Double, val x: Float)
class SMeasure(val index: Int, val startQ: Double, val endQ: Double, val x0: Float, val x1: Float, val anchors: List<Anchor>)
/** [staffTops] indexado pela parte (y do topo da pauta de cada parte neste sistema). */
class SSystem(val top: Float, val bottom: Float, val x0: Float, val x1: Float, val measures: List<SMeasure>, val staffTops: List<Float>)

class Scene(val items: List<Item>, val width: Float, val height: Float, val notes: List<SNote>, val systems: List<SSystem>) {
    /** Posição do cursor para o tempo [q] (semínimas): sistema e x (sp), ou null após o fim. */
    fun cursor(q: Double): Pair<Int, Float>? {
        for ((si, s) in systems.withIndex()) for (m in s.measures) {
            if (q >= m.startQ && q < m.endQ) {
                val a = m.anchors
                if (a.isEmpty()) return si to m.x0
                var prev = a[0]
                for (cur in a) {
                    if (q < cur.q) {
                        val span = cur.q - prev.q
                        return si to (if (span <= 0) prev.x else prev.x + ((q - prev.q) / span).toFloat() * (cur.x - prev.x))
                    }
                    prev = cur
                }
                val span = m.endQ - prev.q
                return si to (if (span <= 0) prev.x else prev.x + ((q - prev.q) / span).toFloat() * (m.x1 - 0.8f - prev.x))
            }
        }
        return null
    }
    fun soundingAt(q: Double): List<Int> = notes.indices.filter { notes[it].midi >= 0 && notes[it].startQ <= q + 1e-9 && q < notes[it].endQ - 1e-9 }
    val totalQ: Double get() = notes.maxOfOrNull { it.endQ } ?: 0.0
}

object Engrave {
    const val NOTE_BLACK = 0xE0A4; const val NOTE_HALF = 0xE0A3; const val NOTE_WHOLE = 0xE0A2
    const val CLEF_G = 0xE050; const val CLEF_F = 0xE062
    const val SHARP = 0xE262; const val FLAT = 0xE260; const val NATURAL = 0xE261
    const val DOT = 0xE1E7
    private val FLAG_UP = intArrayOf(0, 0, 0, 0xE240, 0xE242, 0xE244)
    private val FLAG_DOWN = intArrayOf(0, 0, 0, 0xE241, 0xE243, 0xE245)
    private val REST = mapOf(0 to 0xE4E3, 1 to 0xE4E4, 2 to 0xE4E5, 3 to 0xE4E6, 4 to 0xE4E7, 5 to 0xE4E8)
    const val TIME0 = 0xE080

    private const val HEAD_W = 1.18f
    private const val STEM = 0.12f

    /** Valor rítmico: nível (0 semibreve, 1 mínima, 2 semínima, 3 colcheia, 4 semicolcheia...) e pontos. */
    class Val(val level: Int, val dots: Int)
    fun valueOf(q: Double): Val {
        for (lvl in 0..5) {
            val base = 4.0 / (1 shl lvl)
            if (abs(q - base) < 1e-6) return Val(lvl, 0)
            if (abs(q - base * 1.5) < 1e-6) return Val(lvl, 1)
            if (abs(q - base * 1.75) < 1e-6) return Val(lvl, 2)
        }
        // duração irregular (quiáltera, ligadura): menor figura que comporta a duração
        for (lvl in 5 downTo 0) if (4.0 / (1 shl lvl) >= q - 1e-6) return Val(lvl, 0)
        return Val(0, 0)
    }

    private class Ev(val onset: Int, val dur: Int, val voice: Int, val rest: Boolean, val heads: List<Head>)
    private class Head(val pitch: Pitch?, val idx: Int, val tieStart: Boolean)

    private fun events(m: Measure): List<Ev> {
        val out = ArrayList<Ev>()
        val vt = HashMap<Int, Int>()
        data class B(val onset: Int, val dur: Int, val voice: Int, val rest: Boolean, val heads: MutableList<Head>)
        val bs = ArrayList<B>()
        val lastB = HashMap<Int, B>()
        m.notes.forEachIndexed { i, n ->
            if (n.chord && lastB[n.voice] != null) {
                lastB[n.voice]!!.heads += Head(n.pitch, i, n.tieStart)
            } else {
                val on = vt[n.voice] ?: 0
                val b = B(on, n.duration, n.voice, n.pitch == null, mutableListOf(Head(n.pitch, i, n.tieStart)))
                bs += b; lastB[n.voice] = b
                vt[n.voice] = on + n.duration
            }
        }
        bs.sortedWith(compareBy({ it.onset }, { it.voice })).forEach { out += Ev(it.onset, it.dur, it.voice, it.rest, it.heads) }
        return out
    }

    /** Largura estimada (em sp) de um texto de tamanho [size]; a fonte do sistema varia, então é uma estimativa generosa. */
    fun textWidth(t: String, size: Float): Float = t.sumOf { c ->
        (when { c in "iIl.,;:'!|-" -> 0.32; c in "mwMW" -> 0.85; c.isUpperCase() || c.isDigit() -> 0.66; else -> 0.52 }) }.toFloat() * size

    const val LYRIC_SIZE = 1.55f
    const val CHORD_SIZE = 1.75f
    private fun lyricShown(n: Note): String? = n.lyric?.takeIf { it.isNotEmpty() }?.let { it + if (n.syl == 'b' || n.syl == 'm') "-" else "" }

    private fun space(q: Double) = (1.9 + 2.1 * sqrt(max(q, 0.125))).toFloat()

    private class MeasureCol(val len: Int, val onsets: List<Int>, val rel: List<Float>, val width: Float)

    fun build(s: Score, widthSp: Float): Scene {
        val items = ArrayList<Item>()
        val snotes = ArrayList<SNote>()
        val div = s.divisions.toDouble()
        val nMeasures = s.parts.maxOfOrNull { it.measures.size } ?: 0
        // eventos por parte/compasso
        val evs = s.parts.map { p -> List(nMeasures) { mi -> p.measures.getOrNull(mi)?.let { events(it) } ?: emptyList() } }
        // início de cada compasso em semínimas (por parte, como no sintetizador)
        val measStartQ = Array(s.parts.size) { DoubleArray(nMeasures + 1) }
        s.parts.forEachIndexed { pi, p ->
            var t = 0
            for (mi in 0 until nMeasures) {
                measStartQ[pi][mi] = t / div
                val mm = p.measures.getOrNull(mi)
                t += mm?.notes?.filter { !it.chord }?.groupBy { it.voice }?.values?.maxOfOrNull { l -> l.sumOf { it.duration } } ?: 0
            }
            measStartQ[pi][nMeasures] = t / div
        }
        val colQ = DoubleArray(nMeasures + 1) { i -> measStartQ[0][i] }
        // colunas de compasso: grade de tempos compartilhada por todas as pautas
        val cols = ArrayList<MeasureCol>()
        for (mi in 0 until nMeasures) {
            val len = ((colQ[mi + 1] - colQ[mi]) * div).toInt().coerceAtLeast(1)
            val on = java.util.TreeSet<Int>(); on += 0
            val accAt = HashMap<Int, Boolean>()
            val need = HashMap<Int, Float>()   // largura mínima de coluna por causa de letra ou cifra
            for (pi in s.parts.indices) {
                val mm = s.parts[pi].measures.getOrNull(mi)
                mm?.harmony?.forEach { h -> val at = h.at.coerceIn(0, len - 1); on += at; need[at] = max(need[at] ?: 0f, textWidth(h.text(), CHORD_SIZE) + 0.7f) }
                for (e in evs[pi][mi]) {
                    if (e.rest) continue
                    val ly = mm?.notes?.getOrNull(e.heads[0].idx)?.let { lyricShown(it) } ?: continue
                    need[e.onset] = max(need[e.onset] ?: 0f, textWidth(ly, LYRIC_SIZE) + 0.5f)
                }
                val keyState = HashMap<Char, Int>().also { st -> Pitch.STEPS.forEach { st[it] = Spell.keyAlter(s.fifths, it) } }
                for (e in evs[pi][mi]) {
                    on += e.onset
                    for (h in e.heads) { val pt = h.pitch ?: continue
                        if (keyState[pt.step] != pt.alter) { accAt[e.onset] = true; keyState[pt.step] = pt.alter } }
                }
            }
            val list = on.toList()
            val rel = ArrayList<Float>()
            var x = 1.4f
            for ((k, o) in list.withIndex()) {
                val next = list.getOrNull(k + 1) ?: len
                rel += x + (if (accAt[o] == true) 1.2f else 0f)
                x += max(space((next - o) / div), need[o] ?: 0f) + (if (accAt[o] == true) 1.2f else 0f)
            }
            cols += MeasureCol(len, list, rel, max(x + 0.2f, 7f))
        }
        val clefW = 3.6f
        val keyW = abs(s.fifths) * 1.15f + (if (s.fifths != 0) 0.8f else 0.2f)
        val timeW = 2.8f
        val leftMargin = 2.4f // espaço da chave/colchete
        val groups = ArrayList<MutableList<Int>>()
        s.parts.forEachIndexed { pi, p ->
            val g = groups.firstOrNull { it.isNotEmpty() && s.parts[it[0]].group == p.group }
            if (g != null) g += pi else groups += mutableListOf(pi)
        }
        val staffGap = 9.5f // entre pautas da mesma chave
        val groupGap = 11f
        val sysGap = 5f
        var y = 4f
        var maxX = widthSp
        val systems = ArrayList<SSystem>()
        var mi = 0
        var sysNo = 0
        if (nMeasures == 0) return Scene(emptyList(), widthSp, 10f, emptyList(), emptyList())
        while (mi < nMeasures) {
            val first = sysNo == 0
            val headW = clefW + keyW + (if (first) timeW else 0f)
            val x0 = leftMargin
            val availAll = widthSp - 1.5f - x0 - headW
            var end = mi; var w = 0f
            while (end < nMeasures && (end == mi || w + cols[end].width <= availAll)) { w += cols[end].width; end++ }
            val last = end >= nMeasures
            val factor = if (last) min(availAll / w, 1.35f).coerceAtLeast(0.6f).let { if (w < availAll * 0.6f) 1f.coerceAtLeast(it.coerceAtMost(1.25f)) else it } else availAll / w
            val sysTopY = y
            // posições y das pautas
            val staffTops = ArrayList<Float>()
            var yy = sysTopY + 3f
            val groupSpans = ArrayList<Pair<Float, Float>>()
            for ((gi, g) in groups.withIndex()) {
                val gTop = yy
                for ((k, _) in g.withIndex()) { staffTops += yy; if (k < g.size - 1) yy += staffGap }
                groupSpans += gTop to (yy + 4f)
                yy += 4f + groupGap
            }
            val sysBottom = yy - groupGap
            // mapa parte -> y da pauta
            val partY = HashMap<Int, Float>()
            var si = 0
            for (g in groups) for (pi in g) { partY[pi] = staffTops[si++] }
            val mx0 = x0 + headW
            val measures = ArrayList<SMeasure>()
            var cx = mx0
            // desenhos de cabeçalho: pautas, claves, armadura, compasso
            val xEnd = mx0 + w * factor
            for ((pi, p) in s.parts.withIndex()) {
                val top = partY[pi]!!
                for (l in 0..4) items += Line(x0, top + l, xEnd, top + l, 0.13f)
                val clefCode = if (p.clef == 'F') CLEF_F else CLEF_G
                val clefPos = if (p.clef == 'F') 6 else 2
                items += Glyph(clefCode, x0 + 0.6f, top + (8 - clefPos) / 2f)
                var kx = x0 + clefW
                for (pos in Layout.keySigPositions(p.clef, s.fifths)) {
                    items += Glyph(if (s.fifths > 0) SHARP else FLAT, kx, top + (8 - pos) / 2f); kx += 1.15f
                }
                if (first && s.parts.size > 1 && groups.any { it[0] == pi }) {
                    val nm = p.name.substringBefore(" (pauta").trim().let { if (it.length > 11) it.take(10) + "…" else it }
                    items += Txt(nm, x0 + 0.5f, top - 0.9f, 1.3f)
                }
                if (first) {
                    val tx = x0 + clefW + keyW
                    fun digits(n: Int, cy: Float) {
                        val str = n.toString(); var dx = tx
                        for (ch in str) { items += Glyph(TIME0 + (ch - '0'), dx, cy); dx += 1.7f }
                    }
                    digits(s.beats, top + 1f); digits(s.beatType, top + 3f)
                }
            }
            // linha inicial e chaves
            for ((gi, g) in groups.withIndex()) {
                val (a, b) = groupSpans[gi]
                items += Line(x0, a, x0, b, 0.16f)
                if (g.size > 1) items += Brace(x0 - 0.3f, a, b)
            }
            if (groups.size > 1) items += Line(x0, groupSpans.first().first, x0, groupSpans.last().second, 0.16f)
            // compassos
            for (m in mi until end) {
                val col = cols[m]
                val mw = col.width * factor
                val absX = col.rel.map { cx + it * factor }
                val anchors = col.onsets.mapIndexed { k, o -> Anchor(colQ[m] + o / div, absX[k]) }
                measures += SMeasure(m, colQ[m], colQ[m + 1], cx, cx + mw, anchors)
                for ((pi, p) in s.parts.withIndex()) {
                    engraveMeasure(s, pi, p, m, evs[pi][m], col, absX, cx, cx + mw, partY[pi]!!, measStartQ[pi][m], items, snotes)
                }
                // barras de compasso por grupo
                val lastM = m == nMeasures - 1
                for ((gi, _) in groups.withIndex()) {
                    val (a, b) = groupSpans[gi]
                    if (lastM) { items += Line(cx + mw - 0.9f, a, cx + mw - 0.9f, b, 0.16f); items += Line(cx + mw - 0.2f, a, cx + mw - 0.2f, b, 0.5f) }
                    else items += Line(cx + mw, a, cx + mw, b, 0.16f)
                }
                // número do compasso no início do sistema
                if (m == mi) {
                    val hasHarm = (mi until end).any { mm -> s.parts.any { it.measures.getOrNull(mm)?.harmony?.isNotEmpty() == true } }
                    items += Txt("${s.parts[0].measures.getOrNull(m)?.number ?: (m + 1)}", cx + 0.2f, sysTopY + (if (hasHarm) -0.6f else 1.2f), 1.6f)
                }
                cx += mw
            }
            systems += SSystem(sysTopY, sysBottom, x0, xEnd, measures, s.parts.indices.map { partY[it]!! })
            y = sysBottom + sysGap + 6f
            mi = end; sysNo++
        }
        return Scene(items, widthSp, y, snotes, systems)
    }

    private fun yOf(top: Float, pos: Int) = top + (8 - pos) / 2f

    private class Placed(val ev: Ev, val x: Float, val up: Boolean, val level: Int, val dots: Int, val minPos: Int, val maxPos: Int,
                         val beamId: Int = -1)

    private fun engraveMeasure(s: Score, pi: Int, part: Part, mIdx: Int, evs: List<Ev>, col: MeasureCol, absX: List<Float>,
                                mx0: Float, mx1: Float, top: Float, startQ: Double, items: MutableList<Item>, snotes: MutableList<SNote>) {
        val div = s.divisions.toDouble()
        val measure = part.measures.getOrNull(mIdx) ?: return
        val voices = evs.map { it.voice }.distinct().sorted()
        val multiVoice = voices.size > 1
        val keyState = HashMap<Char, Int>().also { st -> Pitch.STEPS.forEach { st[it] = Spell.keyAlter(s.fifths, it) } }
        val bar = col.len
        // ordem cronológica para o estado de acidentes
        val placedByVoice = HashMap<Int, MutableList<Placed>>()
        val noteIds = HashMap<Int, Int>() // índice da nota no compasso -> id global
        for (e in evs.sortedBy { it.onset }) {
            val x = absX[col.onsets.indexOf(e.onset)]
            val q = e.dur / div
            val v = valueOf(q)
            val voiceRank = voices.indexOf(e.voice)
            val onsetQ = startQ + e.onset / div
            if (!e.rest) measure.notes.getOrNull(e.heads[0].idx)?.let { lyricShown(it) }?.let { items += Txt(it, x - 0.1f, top + 4f + 3.7f, LYRIC_SIZE) }
            if (e.rest) {
                val whole = e.dur >= s.divisions * 4 * s.beats / s.beatType && !multiVoice
                val lvl = if (whole) 0 else v.level
                val rpos = when {
                    lvl == 0 -> 6; lvl == 1 -> 4; else -> 4
                } + (if (multiVoice) (if (voiceRank == 0) 2 else -2) else 0)
                val rx = if (whole) (mx0 + mx1) / 2f - 0.6f else x
                val rid = snotes.size
                snotes += SNote(pi, mIdx, e.heads[0].idx, onsetQ, onsetQ + q, -1)
                items += Glyph(REST[min(lvl, 5)] ?: 0xE4E5, rx, yOf(top, rpos), rid)
                for (d in 0 until (if (whole) 0 else v.dots)) items += Glyph(DOT, rx + 1.5f + d * 0.5f, yOf(top, rpos + 1))
                continue
            }
            // cabeças ordenadas por altura
            val heads = e.heads.filter { it.pitch != null }.sortedBy { Layout.staffPos(part.clef, it.pitch!!.step, it.pitch.octave) }
            if (heads.isEmpty()) continue
            val poss = heads.map { Layout.staffPos(part.clef, it.pitch!!.step, it.pitch.octave) }
            val minP = poss.first(); val maxP = poss.last()
            val up = if (multiVoice) voiceRank == 0 else (if (abs(minP - 4) > abs(maxP - 4)) minP < 4 else maxP < 4)
            val code = when { v.level == 0 -> NOTE_WHOLE; v.level == 1 -> NOTE_HALF; else -> NOTE_BLACK }
            val headW = if (v.level == 0) 1.688f else HEAD_W
            // deslocamento de segundas
            val shift = BooleanArray(heads.size)
            if (v.level > 0) {
                // regra: em segundas, a nota deslocada fica do lado oposto da haste
                var i = if (up) 0 else heads.size - 1
                val step = if (up) 1 else -1
                while (i in heads.indices) {
                    val nb = i + step
                    if (nb in heads.indices && abs(poss[nb] - poss[i]) == 1 && !shift[i]) { shift[nb] = true; i = nb + step } else i += step
                }
            }
            val xShift = if (up) headW else -headW
            // acidentes (colunas)
            val accX = ArrayList<Pair<Int, Float>>() // idx, colunas
            val accIdx = heads.indices.filter { val pt = heads[it].pitch!!; keyState[pt.step] != pt.alter }
            val colLast = ArrayList<Int>()
            for (i in accIdx.reversed()) {
                var c = 0
                while (c < colLast.size && abs(colLast[c] - poss[i]) < 6) c++
                if (c == colLast.size) colLast += poss[i] else colLast[c] = poss[i]
                accX += i to (c + 1) * 1.15f
            }
            for ((i, off) in accX) {
                val pt = heads[i].pitch!!
                val code2 = when (pt.alter) { 1 -> SHARP; -1 -> FLAT; 0 -> NATURAL; 2 -> 0xE263; else -> 0xE264 }
                items += Glyph(code2, x - off - (if (shift[i] && !up) headW else 0f), yOf(top, poss[i]))
            }
            for (h in heads) { val pt = h.pitch!!; keyState[pt.step] = pt.alter }
            // ids globais, notas tocáveis (nota de ligadura continua ressoando até o fim)
            val ids = heads.map { h ->
                val key = h.idx
                val id = snotes.size
                val endQ = onsetQ + q
                snotes += SNote(pi, mIdx, key, onsetQ, endQ, h.pitch!!.midi)
                id
            }
            // linhas suplementares
            val lo = poss.first(); val hi = poss.last()
            val ledgerX1 = x - 0.4f; val ledgerX2 = x + headW + 0.4f + (if (shift.any { it }) headW else 0f)
            var lp = -2
            while (lp >= lo) { items += Line(ledgerX1, yOf(top, lp), ledgerX2, yOf(top, lp), 0.16f); lp -= 2 }
            lp = 10
            while (lp <= hi) { items += Line(ledgerX1, yOf(top, lp), ledgerX2, yOf(top, lp), 0.16f); lp += 2 }
            // cabeças
            heads.forEachIndexed { i, _ ->
                val hx = x + (if (shift[i]) xShift else 0f)
                items += Glyph(code, hx, yOf(top, poss[i]), ids[i])
            }
            // pontos
            for (d in 0 until v.dots) heads.forEachIndexed { i, _ ->
                val dp = if (poss[i] % 2 == 0) poss[i] + 1 else poss[i]
                val dxs = x + headW + (if (shift.any { it } && !up) 0f else 0f) + (if (shift[i] && up) headW else 0f)
                items += Glyph(DOT, dxs + 0.4f + d * 0.5f, yOf(top, dp))
            }
            // haste
            if (v.level >= 1) {
                val stemX = if (up) x + headW - STEM / 2 else x - 0.0f + STEM / 2
                val yTipBase = if (up) yOf(top, maxP) - 3.5f else yOf(top, minP) + 3.5f
                // haste chega ao menos até a linha central
                val mid = yOf(top, 4)
                val tip = if (up) min(yTipBase, mid) else max(yTipBase, mid)
                val y0 = if (up) yOf(top, minP) else yOf(top, maxP)
                val stemY0 = y0 + (if (up) -0.168f else 0.168f)
                val l = Line(stemX, stemY0, stemX, tip, STEM, ids[0])
                placedByVoice.getOrPut(e.voice) { ArrayList() } += Placed(e, x, up, v.level, v.dots, minP, maxP)
                stems += StemRec(l, e, x, stemX, tip, up, v.level, ids[0], e.voice, e.onset)
            }
            // ligaduras de valor
        }
        for (h in measure.harmony) {
            val k = col.onsets.indexOf(h.at.coerceIn(0, bar - 1))
            if (k >= 0) items += Txt(h.text(), absX[k] - 0.1f, top - 1.7f, CHORD_SIZE, true)
        }
        flushStems(s, col, part, top, items, mIdx, div)
    }

    private class StemRec(val line: Line, val e: Ev, val x: Float, val stemX: Float, val tip: Float, val up: Boolean, val level: Int, val noteId: Int, val voice: Int, val onset: Int)
    private val stems = ArrayList<StemRec>()

    /** Desenha hastes, bandeirolas e vigas do compasso. */
    private fun flushStems(s: Score, col: MeasureCol, part: Part, top: Float, items: MutableList<Item>, mIdx: Int, div: Double) {
        val beatLen = (div * (4.0 / s.beatType) * (if (s.beatType == 8 && s.beats % 3 == 0) 3 else 1)).toInt().coerceAtLeast(1)
        val byVoice = stems.groupBy { it.voice }
        for ((_, list) in byVoice) {
            val sorted = list.sortedBy { it.onset }
            var i = 0
            while (i < sorted.size) {
                val st = sorted[i]
                if (st.level >= 3) {
                    var j = i
                    val group = arrayListOf(st)
                    while (j + 1 < sorted.size) {
                        val nx = sorted[j + 1]; val cur = sorted[j]
                        if (nx.level >= 3 && nx.onset == cur.onset + cur.e.dur && nx.onset / beatLen == st.onset / beatLen) { group += nx; j++ } else break
                    }
                    if (group.size >= 2) { drawBeamed(group, items); i = j + 1; continue }
                    // sem viga: bandeirola
                    val code = if (st.up) FLAG_UP[min(st.level, 5)] else FLAG_DOWN[min(st.level, 5)]
                    items += st.line
                    items += Glyph(code, st.stemX - STEM / 2, st.tip, st.noteId)
                } else items += st.line
                i++
            }
        }
        stems.clear()
    }

    private fun drawBeamed(g: List<StemRec>, items: MutableList<Item>) {
        val up = g.count { it.up } * 2 >= g.size
        val beamY = if (up) g.minOf { it.tip } else g.maxOf { it.tip }
        for (st in g) {
            val sx = if (up) st.x + HEAD_W - STEM / 2 else st.x + STEM / 2
            val y0 = st.line.y1
            items += Line(sx, y0, sx, beamY, STEM, st.noteId)
        }
        fun sx(st: StemRec) = if (up) st.x + HEAD_W else st.x
        val th = 0.5f
        for (lvl in 3..5) {
            var i = 0
            while (i < g.size) {
                if (g[i].level >= lvl) {
                    var j = i
                    while (j + 1 < g.size && g[j + 1].level >= lvl) j++
                    val off = (lvl - 3) * 0.75f * (if (up) 1 else -1)
                    if (j > i) items += Beam(sx(g[i]) - (if (up) STEM else 0f), beamY + off + (if (up) 0f else -th * 0f), sx(g[j]) + (if (up) 0f else STEM), beamY + off, th)
                    else {
                        val stubL = if (i > 0) -1.1f else 1.1f
                        val bx = sx(g[i])
                        items += Beam(min(bx, bx + stubL), beamY + off, max(bx, bx + stubL), beamY + off, th)
                    }
                    i = j + 1
                } else i++
            }
        }
    }

    /** Segunda passada: liga as notas ligadas usando os itens já posicionados. */
    fun tiesPass(scene: Scene, s: Score): List<Item> {
        val out = ArrayList<Item>()
        val heads = scene.items.filterIsInstance<Glyph>().filter { it.note >= 0 && (it.code == NOTE_BLACK || it.code == NOTE_HALF || it.code == NOTE_WHOLE) }
        // sistema de cada y (agrupa por sistema pela posição vertical)
        for ((id, n) in scene.notes.withIndex()) {
            if (n.midi < 0) continue
            val part = s.parts[n.part]
            val note = part.measures[n.m].notes[n.idx]
            if (!note.tieStart) continue
            // procura o destino: próxima nota do mesmo midi na mesma parte iniciando em endQ
            val next = scene.notes.indices.firstOrNull { scene.notes[it].part == n.part && scene.notes[it].midi == n.midi && it != id && abs(scene.notes[it].startQ - n.endQ) < 1e-6 } ?: continue
            val h1 = heads.firstOrNull { it.note == id } ?: continue
            val h2 = heads.firstOrNull { it.note == next } ?: continue
            val stemUp = scene.items.any { it is Line && it.note == id && it.y2 < it.y1 }
            val up = !stemUp
            val sameRow = abs(h1.y - h2.y) < 30f && h2.x > h1.x
            val x1 = h1.x + 1.3f
            if (sameRow) out += Curve(x1, h1.y + (if (up) -0.5f else 0.5f), h2.x - 0.1f, h2.y + (if (up) -0.5f else 0.5f), up)
            else {
                out += Curve(x1, h1.y + (if (up) -0.5f else 0.5f), x1 + 2.5f, h1.y + (if (up) -0.5f else 0.5f), up)
            }
        }
        return out
    }

    /** Cena completa: layout + ligaduras. */
    @Synchronized fun scene(s: Score, widthSp: Float): Scene {
        stems.clear()
        val sc = build(s, widthSp)
        val ties = tiesPass(sc, s)
        return Scene(sc.items + ties, sc.width, sc.height, sc.notes, sc.systems)
    }
}

/** Hit-test e conversão de coordenadas para edição direta na pauta (toque ou caneta). */
object SceneHit {
    /** Nota/pausa mais próxima do ponto (sp), ou null se estiver longe. */
    fun nearest(sc: Scene, x: Float, y: Float): Int? {
        var best: Int? = null; var bd = Float.MAX_VALUE
        for (it in sc.items) {
            if (it !is Glyph || it.note < 0) continue
            val isHead = it.code == Engrave.NOTE_BLACK || it.code == Engrave.NOTE_HALF || it.code == Engrave.NOTE_WHOLE
            val cx = it.x + (if (isHead) 0.6f else 0.6f)
            val cy = if (isHead) it.y else it.y
            val dx = x - cx; val dy = (y - cy) * 0.8f
            val d = dx * dx + dy * dy
            if (d < bd) { bd = d; best = it.note }
        }
        return if (bd <= 9f) best else null
    }

    /** Nota/pausa da parte [part], no sistema onde está [y], mais próxima na horizontal. */
    fun nearestInPart(sc: Scene, x: Float, y: Float, part: Int): Int? {
        val sys = sc.systems.firstOrNull { y >= it.top - 3f && y <= it.bottom + 4f } ?: return null
        var best: Int? = null; var bd = Float.MAX_VALUE
        for (it in sc.items) {
            if (it !is Glyph || it.note < 0 || sc.notes[it.note].part != part) continue
            if (it.y < sys.top - 6f || it.y > sys.bottom + 6f) continue
            val d = abs(x - (it.x + 0.6f))
            if (d < bd) { bd = d; best = it.note }
        }
        return if (bd <= 3.5f) best else null
    }

    /** Parte e posição (meios-espaços) na pauta mais próxima do y; null se estiver fora de qualquer pauta. */
    fun staffAt(sc: Scene, x: Float, y: Float): Pair<Int, Int>? {
        for (s in sc.systems) {
            if (y < s.top - 2f || y > s.bottom + 3f) continue
            var bestPart = -1; var bd = Float.MAX_VALUE
            s.staffTops.forEachIndexed { i, t -> val d = abs(y - (t + 2f)); if (d < bd) { bd = d; bestPart = i } }
            if (bestPart < 0 || bd > 6f) continue
            val pos = 8 - Math.round((y - s.staffTops[bestPart]) * 2f)
            return bestPart to pos
        }
        return null
    }
}

