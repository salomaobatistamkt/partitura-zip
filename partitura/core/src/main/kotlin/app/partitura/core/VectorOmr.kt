package app.partitura.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Leitor de partitura em PDF digital (Sibelius, MuseScore, Finale, Dorico…): lê direto os símbolos vetoriais do arquivo
 * (cabeças de nota, hastes, barras, linhas da pauta, ligaduras, texto) em vez de adivinhar pixels. Preciso e rápido.
 * Fontes musicais reconhecidas: SMuFL (Bravura, Leland, Petaluma…), Opus/Petrucci (Sibelius). Outras → devolve null (usa o leitor de imagem).
 */
object VectorOmr {
    private class G(val c: String, val x: Double, val y: Double, val size: Double, val ch: Int, val font: String, val adv: Double)
    private class Staff(val ys: DoubleArray, val x0: Double, val x1: Double) {
        val bot get() = ys[0]; val top get() = ys[4]; val sp get() = (ys[4] - ys[0]) / 4
        var kind = "single"; var grp = 0; var slot = 0; var gnum = 0
        val key get() = "$kind/$gnum/$slot"
    }
    private class Seg(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val w: Double)
    private class Head(val g: G, val kind: String, val pos: Int, val cx: Double, val w: Double) {
        var stem = -1; var acc: String? = null; var tie = false; var tieStop = false
        val x get() = g.x; val y get() = g.y
    }
    private class Ev(var x: Double, var dur: Double, val rest: Boolean) {
        var heads = ArrayList<Head>(); var up: Boolean? = null; var tip = 0.0; var stemX = 0.0; var y = 0.0; var measureRest = false
        var lyric: String? = null; var syl = 's'; var voice = 1; var full = false; var hasStem = false; var onset = 0.0
    }

    // ----------------------------------------------------------------- classificação das fontes
    private val MUSIC_FONT_PREFIX = listOf("Opus", "Petrucci", "Maestro", "Bravura", "Leland", "Petaluma", "Gonville", "Emmentaler", "Sebastian", "November", "Finale Maestro")

    private fun classify(font: String, ch: Int): String? {
        if (ch in 0xE000..0xF8FF) {
            return when (ch) {
                0xE050 -> "clefG"; 0xE062 -> "clefF"; 0xE05C -> "clefC"
                0xE0A2 -> "headWhole"; 0xE0A3 -> "headHalf"; 0xE0A4 -> "headFilled"
                0xE260 -> "flat"; 0xE261 -> "natural"; 0xE262 -> "sharp"
                0xE4E3 -> "restWhole"; 0xE4E4 -> "restHalf"; 0xE4E5 -> "restQuarter"; 0xE4E6 -> "rest8"; 0xE4E7 -> "rest16"
                0xE1E7 -> "dot"; 0xE240, 0xE241, 0xE242, 0xE243, 0xE244, 0xE245 -> "flag"
                in 0xE080..0xE089 -> "timeDigit"; in 0xE880..0xE889 -> "tupDigit"
                else -> null
            }
        }
        val f = font
        if (f.startsWith("Petrucci")) return when (ch) {
            0x26 -> "clefG"; 0x3f -> "clefF"; 0x62 -> "flat"; 0x23 -> "sharp"; 0x6e -> "natural"
            0xcf -> "headFilled"; 0xfa -> "headHalf"; 0x77 -> "headWhole"
            0xb7 -> "restWhole"; 0xee -> "restHalf"; 0xce -> "restQuarter"; 0xe4 -> "rest8"; else -> null
        }
        if (f.startsWith("Opus")) {
            if (f.contains("Special")) return when (ch) { 0x2122 -> "dot"; 0x7b -> "brace"; else -> null }
            if (f.contains("Text")) return if (ch in 0x30..0x39) "tupDigit" else null
            if (f.contains("Chords")) return "chord"
            if (ch in 0x30..0x39) return "timeDigit"
            if (ch == 0x4a || ch == 0x6a) return "flag"
        }
        return null
    }
    private fun isMusicFont(f: String) = MUSIC_FONT_PREFIX.any { f.startsWith(it) }

    private val STEPS = "CDEFGAB"
    private val SHARP_ORDER = "FCGDAEB"
    private val FLAT_ORDER = "BEADGCF"

    // ----------------------------------------------------------------- leitura
    class Opts(val title: String?, val beats: Int?, val beatType: Int, val tempo: Int)

    /** Devolve null se o PDF não tem notação vetorial reconhecível (ex.: partitura escaneada). */
    fun read(bytes: ByteArray, o: OmrOptions, progress: ((Int, Int) -> Unit)? = null): OmrResult? {
        val pdf = try { PdfFile(bytes) } catch (e: Exception) { return null }
        val pages = try { pdf.pages() } catch (e: Exception) { return null }
        if (pages.isEmpty()) return null
        val warnings = ArrayList<String>()
        val systemsAll = ArrayList<SystemData>()
        var title: String? = null; var tempo: Int? = null
        for ((pi, pg) in pages.withIndex()) {
            progress?.invoke(pi, pages.size)
            val d = try { PdfContent.read(pdf, pg) } catch (e: Exception) { continue }
            val gl = ArrayList<G>()
            for (g in d.glyphs) { val f = g.font; gl += G(classify(f, g.ch) ?: "", g.x, g.y, g.size, g.ch, f, g.adv) }
            val hasMusic = gl.any { it.c.startsWith("head") }
            if (!hasMusic) continue
            val r = analyzePage(d, gl, pi == 0)
            systemsAll += r.systems
            if (pi == 0 || title == null) { r.title?.let { if (title == null) title = it }; r.tempo?.let { if (tempo == null) tempo = it } }
        }
        progress?.invoke(pages.size, pages.size)
        if (systemsAll.isEmpty()) return null
        return assemble(systemsAll, o, title, tempo, warnings)
    }

    private class StaffData(val staff: Staff, val clef: Char, val fifths: Int, val ts: Pair<Int, Int>?, val events: List<Ev>, val heads: List<Head>, val lyricsOn: Boolean)
    private class SystemData(val staves: List<StaffData>, val bars: List<Double>, val harmony: List<Triple<Double, Double, Harmony0>>)
    private class Harmony0(val step: Char, val alter: Int, val kind: String, val bassStep: Char?, val bassAlter: Int)
    private class PageResult(val systems: List<SystemData>, val title: String?, val tempo: Int?)

    // ----------------------------------------------------------------- análise de uma página
    private fun analyzePage(d: PdfPageData, gl: List<G>, first: Boolean): PageResult {
        val segs = d.segs.map { Seg(it.x1, it.y1, it.x2, it.y2, it.w) }
        val staves = detectStaves(segs).sortedByDescending { it.ys[0] }
        // sistemas
        val vert = segs.filter { abs(it.x1 - it.x2) < 0.3 }
        val systems = ArrayList<ArrayList<Staff>>()
        for (s in staves) {
            val prev = systems.lastOrNull()?.last()
            if (prev != null) {
                val br = vert.any { abs(it.x1 - s.x0) < 2.0 && min(it.y1, it.y2) <= s.top + 1 && max(it.y1, it.y2) >= prev.bot - 1 }
                if (br) { systems.last().add(s); continue }
            }
            systems.add(arrayListOf(s))
        }
        val braceExt = d.glyphs.filter { classify(it.font, it.ch) == "brace" }.map { it.y to it.y + it.ysize }
        val thick = segs.filter { it.w > 2.0 && abs(it.x1 - it.x2) < 0.3 }.map { min(it.y1, it.y2) to max(it.y1, it.y2) }
        val beams = d.fills.filter { !it.curved && it.pts.size in 4..6 }
        val curves = d.fills.filter { it.curved }
        // texto
        val lyricToks = textTokens(gl, false)
        val out = ArrayList<SystemData>()
        for (sy in systems) {
            // grupos
            for (s in sy) {
                val c = (s.top + s.bot) / 2
                val b = braceExt.firstOrNull { c >= it.first - 2 && c <= it.second + 2 }
                val t = thick.firstOrNull { c >= it.first - 2 && c <= it.second + 2 }
                when {
                    b != null -> { s.kind = "brace"; s.grp = (b.first * 10).roundToInt() }
                    t != null -> { s.kind = "bracket"; s.grp = (t.first * 10).roundToInt() }
                    else -> { s.kind = "single"; s.grp = (s.bot * 10).roundToInt() }
                }
            }
            val order = ArrayList<Int>(); for (s in sy) if (s.grp !in order) order.add(s.grp)
            val kc = HashMap<String, Int>(); val gnum = HashMap<Int, Int>()
            for (g in order) { val k = sy.first { it.grp == g }.kind; gnum[g] = kc[k] ?: 0; kc[k] = (kc[k] ?: 0) + 1 }
            val slotCnt = HashMap<Int, Int>()
            for (s in sy) { s.gnum = gnum[s.grp]!!; s.slot = slotCnt[s.grp] ?: 0; slotCnt[s.grp] = s.slot + 1 }
            out += readSystem(sy, gl, segs, vert, beams, curves, lyricToks)
        }
        // título e andamento (primeira página)
        var title: String? = null; var tempo: Int? = null
        if (first) {
            val tg = gl.filter { !isMusicFont(it.font) && it.c == "" && it.ch in 32..0x2FF }
            val big = tg.maxOfOrNull { it.size }
            if (big != null && big > 11) {
                val line = tg.filter { it.size >= big - 0.5 }.sortedBy { it.x }
                val y0 = line.maxOf { it.y }
                title = line.filter { abs(it.y - y0) < big }.sortedBy { it.x }.joinToString("") { String(Character.toChars(it.ch)) }.trim().ifBlank { null }
            }
            val rows = tg.groupBy { (it.y / 3).roundToInt() }
            for ((_, r) in rows) {
                val txt = r.sortedBy { it.x }.joinToString("") { String(Character.toChars(it.ch)) }
                val m = Regex("=\\s*(\\d{2,3})").find(txt)
                if (m != null && tempo == null) { val v = m.groupValues[1].toInt(); if (v in 30..300) tempo = v }
            }
        }
        return PageResult(out, title, tempo)
    }

    private fun detectStaves(segs: List<Seg>): List<Staff> {
        val h = segs.filter { abs(it.y1 - it.y2) < 0.3 && abs(it.x1 - it.x2) > 60 }
            .map { Seg(min(it.x1, it.x2), (it.y1 + it.y2) / 2, max(it.x1, it.x2), (it.y1 + it.y2) / 2, it.w) }.sortedBy { it.y1 }
        // remove linhas duplicadas
        val l = ArrayList<Seg>()
        for (s in h) { val p = l.lastOrNull(); if (p != null && abs(p.y1 - s.y1) < 0.15 && abs(p.x1 - s.x1) < 2 && abs(p.x2 - s.x2) < 2) continue; l += s }
        val res = ArrayList<Staff>()
        var i = 0
        while (i + 4 < l.size) {
            val c = l.subList(i, i + 5)
            val dd = DoubleArray(4) { c[it + 1].y1 - c[it].y1 }
            val m = dd.average()
            if (m > 1.5 && dd.all { abs(it - m) < 0.15 * m } && abs(c[0].x1 - c[4].x1) < 2 && abs(c[0].x2 - c[4].x2) < 2) {
                res += Staff(DoubleArray(5) { c[it].y1 }, c.minOf { it.x1 }, c.maxOf { it.x2 }); i += 5
            } else i++
        }
        return res
    }

    // ----------------------------------------------------------------- texto
    private class Tok(val text: String, val x: Double, val x1: Double, val y: Double, val size: Double, val chord: Boolean)

    private fun textTokens(gl: List<G>, chords: Boolean): List<Tok> {
        val src = gl.filter { if (chords) it.c == "chord" else (it.c == "" && !isMusicFont(it.font) && it.ch in 32..0x2FF || it.ch in 0x2010..0x2015 || it.ch == 0x203F) }
        val lines = src.groupBy { (it.y / 1.2).roundToInt() }.values
        val toks = ArrayList<Tok>()
        for (ln in lines) {
            val s = ln.sortedBy { it.x }
            var cur = StringBuilder(); var sx = 0.0; var ex = 0.0; var sy = 0.0; var sz = 0.0; var started = false
            fun flush() { if (started && cur.isNotBlank()) toks += Tok(cur.toString().trim(), sx, ex, sy, sz, chords); cur = StringBuilder(); started = false }
            for (g in s) {
                if (!chords && (g.ch == 0x2D || g.ch == 0x2013)) { flush(); toks += Tok("-", g.x, g.x + g.adv, g.y, g.size, false); continue }
                if (started && g.x - ex > 0.28 * g.size) flush()
                if (!started) { started = true; sx = g.x; sy = g.y; sz = g.size }
                val chs = if (g.ch == 0xA8 && chords) "b" else if (g.ch == 0x203F) "_" else if (g.ch == 32) " " else String(Character.toChars(g.ch))
                if (g.ch == 32) { flush(); continue }
                cur.append(chs); ex = g.x + g.adv
            }
            flush()
        }
        return toks
    }

    private fun parseChord(t0: String): Harmony0? {
        val t = t0.replace("♭", "b").replace("♯", "#").replace("♮", "")
        val m = Regex("^([A-G])([#b]?)([^/]*)(?:/([A-G])([#b]?))?$").find(t) ?: return null
        val q = m.groupValues[3]
        if (q.any { it.isLetter() && it !in "msuajdiMgn" }) return null
        val kind = when {
            q.contains("maj7") || q.contains("M7") || q.contains("7M") || q.contains("△") -> "major-seventh"
            q.startsWith("dim7") -> "diminished-seventh"
            q.startsWith("dim") || q.startsWith("°") -> "diminished"
            q.startsWith("aug") || q.startsWith("+") -> "augmented"
            q.startsWith("m7b5") -> "half-diminished"
            q.startsWith("m7") || q.startsWith("m9") -> "minor-seventh"
            q.startsWith("m6") -> "minor-sixth"
            q.startsWith("m") && !q.startsWith("maj") -> "minor"
            q.startsWith("sus2") -> "suspended-second"
            q.startsWith("sus") -> "suspended-fourth"
            q.startsWith("9") -> "dominant-ninth"
            q.startsWith("7") -> "dominant"
            q.startsWith("6") -> "major-sixth"
            else -> "major"
        }
        fun alt(s: String) = when (s) { "#" -> 1; "b" -> -1; else -> 0 }
        return Harmony0(m.groupValues[1][0], alt(m.groupValues[2]), kind, m.groupValues[4].firstOrNull(), alt(m.groupValues[5]))
    }

    // ----------------------------------------------------------------- um sistema
    private fun readSystem(sy: List<Staff>, gl: List<G>, segs: List<Seg>, vert: List<Seg>, beams: List<PdfFill>, curves: List<PdfFill>, lyricToks: List<Tok>): SystemData {
        val sp0 = sy[0].sp
        val allHeads = gl.filter { it.c.startsWith("head") }
        // barras de compasso: linhas verticais que terminam exatamente em bordas de pauta e não tocam cabeças
        val edgesB = sy.map { it.bot }; val edgesT = sy.map { it.top }
        val barLines = HashSet<Seg>(); val bx = ArrayList<Double>()
        for (l in vert) {
            val y0 = min(l.y1, l.y2); val y1 = max(l.y1, l.y2)
            if (edgesB.none { abs(y0 - it) < 0.9 } || edgesT.none { abs(y1 - it) < 0.9 }) continue
            if (l.x1 <= sy[0].x0 + 2) continue
            if (allHeads.any { it.y >= y0 - 0.3 * sp0 && it.y <= y1 + 0.3 * sp0 && (abs(l.x1 - it.x) < 0.4 * sp0 || abs(l.x1 - (it.x + 1.17 * sp0)) < 0.4 * sp0) }) continue
            barLines += l; bx += l.x1
        }
        bx.sort()
        val bars = ArrayList<Double>()
        for (x in bx) { if (bars.isNotEmpty() && x - bars.last() < 2.0 * sp0) bars[bars.size - 1] = x else bars += x }
        val x1 = sy.maxOf { it.x1 }
        val sysHeadsX = allHeads.filter { h -> sy.any { it.bot - 9 * sp0 <= h.y && h.y <= it.top + 9 * sp0 } }.map { it.x }
        val rest = gl.filter { it.c.startsWith("rest") }.filter { r -> sy.any { it.bot - 9 * sp0 <= r.y && r.y <= it.top + 9 * sp0 } }.map { it.x }
        val lastX = (sysHeadsX + rest).maxOrNull() ?: 0.0
        if (bars.isEmpty() || (bars.last() < x1 - 3 && lastX > bars.last())) bars += x1

        fun nearestStaff(y: Double): Pair<Int, Double> {
            var best = 0; var bd = 1e9
            for ((i, s) in sy.withIndex()) { val d = if (y >= s.bot && y <= s.top) 0.0 else min(abs(y - s.bot), abs(y - s.top)); if (d < bd) { bd = d; best = i } }
            return best to bd
        }
        val staffData = ArrayList<StaffData>()
        for ((si, s) in sy.withIndex()) {
            val sp = s.sp; val mid = (s.bot + s.top) / 2
            val gs = gl.filter { g ->
                if (g.c.isEmpty() || g.c == "chord" || g.c == "brace") return@filter false
                val (n, d) = nearestStaff(g.y)
                if (n != si || d > 6.2 * sp) return@filter false
                if ((g.c.startsWith("head") || g.c == "flag" || g.c == "dot" || g.c == "flat" || g.c == "sharp" || g.c == "natural" || g.c.startsWith("rest")) && g.size < 3.3 * sp) return@filter false
                true
            }.distinctBy { listOf(it.c, (it.x * 5).roundToInt(), (it.y * 5).roundToInt()) }
            val heads0 = gs.filter { it.c.startsWith("head") }
            // duplicatas exatas (duas vozes com a mesma nota): mantém ambas só se houver duas hastes
            val dupHeads = gl.filter { g -> g.c.startsWith("head") && nearestStaff(g.y).let { it.first == si && it.second <= 6.2 * sp } && g.size >= 3.3 * sp }
            val rests = gs.filter { it.c.startsWith("rest") }
            val clefs = gs.filter { it.c.startsWith("clef") && it.x < s.x0 + 8 * sp }.sortedBy { it.x }
            val clef = if (clefs.firstOrNull()?.c == "clefF") 'F' else 'G'
            val firstX = (heads0 + rests).minOfOrNull { it.x } ?: x1
            val accs = gs.filter { it.c == "flat" || it.c == "sharp" || it.c == "natural" }
            val ks = accs.filter { it.x < firstX - 3.0 * sp && it.x < s.x0 + 30 * sp && (bars.isEmpty() || it.x < bars[0]) }
            val fifths = ks.count { it.c == "sharp" } - ks.count { it.c == "flat" }
            val digs = gs.filter { it.c == "timeDigit" && it.x < firstX && it.x < s.x0 + 30 * sp }
            var ts: Pair<Int, Int>? = null
            if (digs.isNotEmpty()) {
                fun num(l: List<G>): Int? = l.sortedBy { it.x }.joinToString("") { if (it.ch < 0x100) it.ch.toChar().toString() else (it.ch - 0xE080).toString() }.toIntOrNull()
                val a = num(digs.filter { it.y > mid }); val b = num(digs.filter { it.y <= mid })
                if (a != null && b != null && a in 1..32 && b in listOf(1, 2, 4, 8, 16)) ts = a to b
            }
            fun posY(y: Double) = ((y - s.bot) / (sp / 2)).roundToInt()
            // hastes
            val stems = vert.filter { l ->
                val len = abs(l.y1 - l.y2)
                l !in barLines && len >= 1.5 * sp && len <= 14 * sp && s.bot - 14 * sp <= min(l.y1, l.y2) && max(l.y1, l.y2) <= s.top + 14 * sp && l.x1 > s.x0 && l.x1 < s.x1
            }.map { doubleArrayOf(it.x1, min(it.y1, it.y2), max(it.y1, it.y2)) }
            val heads = ArrayList<Head>()
            val usedStem = HashMap<String, MutableSet<Int>>()
            for (g in dupHeads.sortedWith(compareBy({ it.x }, { it.y }))) {
                val w = if (g.c == "headWhole") 1.62 * sp else 1.17 * sp
                val h = Head(g, g.c, posY(g.y), g.x + w / 2, w)
                val dupKey = "${(g.x * 5).roundToInt()}/${(g.y * 5).roundToInt()}"
                val taken = usedStem.getOrPut(dupKey) { HashSet() }
                var best = -1; var bd = 1e9
                for ((k, st) in stems.withIndex()) {
                    if (k in taken) continue
                    if (g.y < st[1] - 0.35 * sp || g.y > st[2] + 0.35 * sp) continue
                    val d = min(abs(st[0] - (g.x + w)), abs(st[0] - g.x))
                    if (d < 0.4 * sp && d < bd) { bd = d; best = k }
                }
                if (best >= 0 || taken.isEmpty()) { h.stem = best; if (best >= 0) taken += best; heads += h }
            }
            // acidentes (fora da armadura)
            for (a in accs) {
                if (a in ks) continue
                var best: Head? = null; var bd = 1e9
                for (h in heads) { val dx = h.x - a.x; if (dx >= 0.2 * sp && dx <= 3.2 * sp && abs(a.y - h.y) <= 1.6 * sp && dx < bd) { bd = dx; best = h } }
                if (best != null && best.acc == null) best.acc = a.c
            }
            // acordes (cabeças com a mesma haste)
            val events = ArrayList<Ev>()
            val byStem = HashMap<Int, ArrayList<Head>>()
            for (h in heads) if (h.stem >= 0) byStem.getOrPut(h.stem) { ArrayList() }.add(h)
            val dotsG = gs.filter { it.c == "dot" }
            fun dotCount(hs: List<Head>): Int {
                val rx = hs.maxOf { it.x + it.w }
                val rows = HashMap<Int, Int>()
                for (d in dotsG) {
                    val ok = hs.any { h -> val dx = d.x - (h.x + h.w); dx >= -0.3 * sp && dx <= 3.2 * sp + (rx - (h.x + h.w)) && abs(d.y - h.y) <= 0.62 * sp }
                    if (ok) rows.merge((d.y / (0.3 * sp)).roundToInt(), 1, Int::plus)
                }
                return rows.values.maxOrNull() ?: 0
            }
            fun duration(kind: String, nDots: Int, beamsN: Int): Double {
                val base = when (kind) { "headWhole" -> 96.0; "headHalf" -> 48.0; else -> if (beamsN <= 0) 24.0 else 24.0 / (1 shl min(beamsN, 4)) }
                return if (nDots > 0) base * (2 - Math.pow(0.5, nDots.toDouble())) else base
            }
            for ((k, hs) in byStem) {
                val st = stems[k]
                val ymax = hs.maxOf { it.y }; val ymin = hs.minOf { it.y }
                val up = (st[2] - ymax) > (ymin - st[1])
                val tip = if (up) st[2] else st[1]
                var nb = 0
                for (p in beams) {
                    val xa = p.pts.minOf { it[0] }; val xb = p.pts.maxOf { it[0] }
                    if (st[0] >= xa - 0.4 && st[0] <= xb + 0.4 && xb - xa > 1.0 * sp) {
                        val yy = yRangeAt(p.pts, st[0].coerceIn(xa, xb))
                        if (yy != null && abs((yy.first + yy.second) / 2 - tip) <= 1.9 * sp) nb++
                    }
                }
                val nf = gs.count { it.c == "flag" && abs(it.x - st[0]) < 1.6 * sp && abs(it.y - tip) < 4.2 * sp }
                val kind = hs[0].kind
                val e = Ev(hs.map { it.cx }.average(), duration(kind, dotCount(hs), if (kind == "headFilled") max(nb, nf) else 0), false)
                e.heads = ArrayList(hs); e.up = up; e.tip = tip; e.stemX = st[0]; e.hasStem = true
                events += e
            }
            val stemless = heads.filter { it.stem < 0 }.sortedBy { it.cx }
            val sl = ArrayList<Ev>()
            for (h in stemless) {
                val e = sl.firstOrNull { abs(it.heads[0].cx - h.cx) < 0.5 * sp }
                if (e != null) e.heads.add(h) else { val n = Ev(h.cx, 0.0, false); n.heads = arrayListOf(h); sl += n }
            }
            for (e in sl) { e.dur = duration(e.heads[0].kind, dotCount(e.heads), 0); events += e }
            for (r in rests) {
                var nd = 0
                for (g in dotsG) { val dx = g.x - (r.x + 1.2 * sp); if (dx >= -0.4 * sp && dx <= 2.6 * sp && abs(g.y - r.y) <= 1.4 * sp) nd++ }
                val base = when (r.c) { "restWhole" -> 96.0; "restHalf" -> 48.0; "restQuarter" -> 24.0; "rest8" -> 12.0; "rest16" -> 6.0; else -> 24.0 }
                val e = Ev(r.x + 0.55 * sp, if (nd > 0) base * (2 - Math.pow(0.5, nd.toDouble())) else base, true)
                e.y = r.y; e.measureRest = r.c == "restWhole"
                events += e
            }
            // quiálteras
            val NORM = mapOf(2 to 3, 3 to 2, 4 to 3, 6 to 4)
            val tds = gs.filter { it.c == "tupDigit" && (it.y > s.top + 0.5 * sp || it.y < s.bot - 0.5 * sp) }
            for (dg in tds) {
                val n = if (dg.ch < 0x100) dg.ch - 0x30 else dg.ch - 0xE880
                val norm = NORM[n] ?: continue
                val cand = segs.filter { abs(it.x1 - it.x2) > 3 && it.w < 1.0 && abs(it.y1 - it.y2) < 0.25 * abs(it.x1 - it.x2) && abs((it.y1 + it.y2) / 2 - dg.y) < 1.9 * sp }
                val left = cand.filter { max(it.x1, it.x2) >= dg.x - 3.5 * sp && max(it.x1, it.x2) <= dg.x + 0.8 * sp }
                val right = cand.filter { min(it.x1, it.x2) >= dg.x - 0.8 * sp && min(it.x1, it.x2) <= dg.x + 3.5 * sp }
                var xa: Double; var xb: Double
                if (left.isNotEmpty() && right.isNotEmpty()) { xa = left.minOf { min(it.x1, it.x2) }; xb = right.maxOf { max(it.x1, it.x2) } }
                else {
                    var fa: Double? = null; var fb: Double? = null
                    for (p in beams) {
                        val pa = p.pts.minOf { it[0] }; val pb = p.pts.maxOf { it[0] }
                        if (pa - 1 <= dg.x && dg.x <= pb + 1 && pb - pa > 2 * sp) { fa = pa - 0.8 * sp; fb = pb + 0.8 * sp; break }
                    }
                    if (fa == null || fb == null) continue
                    xa = fa; xb = fb
                }
                val above = dg.y > s.top
                var mem = events.filter { it.x >= xa - 0.3 * sp && it.x <= xb + 0.3 * sp }
                val ups = mem.filter { !it.rest && it.up == true }; val dns = mem.filter { !it.rest && it.up == false }
                if (ups.isNotEmpty() && dns.isNotEmpty() && ups.any { u -> dns.any { abs(u.x - it.x) < 0.9 * sp } }) mem = if (above) ups else dns
                if (System.getProperty("vdbg") != null) System.err.println("TUP n=$n x=${dg.x} y=${dg.y} font=${dg.font} range=$xa..$xb mem=${mem.size} staff=$si")
                if (mem.size >= 2) for (e in mem) e.dur = e.dur * norm / n
            }
            // ligaduras de valor
            for (c in curves) {
                val xa = c.pts.minOf { it[0] }; val xb = c.pts.maxOf { it[0] }; val ya = c.pts.minOf { it[1] }; val yb = c.pts.maxOf { it[1] }
                if (xb - xa < 1.5 * sp || xb - xa > 24 * sp || yb - ya > 3.2 * sp) continue
                if (yb < s.bot - 7 * sp || ya > s.top + 7 * sp) continue
                val ym = (ya + yb) / 2
                val lh = heads.filter { it.cx >= xa - 1.7 * sp && it.cx <= xa + 1.5 * sp && abs(it.y - ym) <= 2.4 * sp }
                val rh = heads.filter { it.cx >= xb - 1.5 * sp && it.cx <= xb + 1.7 * sp && abs(it.y - ym) <= 2.4 * sp }
                var done = false
                for (l in lh) { val r = rh.firstOrNull { it.pos == l.pos && it.cx > l.cx + sp && it !== l }; if (r != null && !l.tie) { l.tie = true; r.tieStop = true; done = true; break } }
                if (!done && lh.isNotEmpty() && rh.isEmpty() && xb > s.x1 - 3 * sp) { lh.minByOrNull { abs(it.y - ym) }?.let { it.tie = true } }
                if (!done && rh.isNotEmpty() && lh.isEmpty() && xa < (heads.minOfOrNull { it.cx } ?: 1e9) - sp) { rh.minByOrNull { abs(it.y - ym) }?.let { it.tieStop = true } }
            }
            // letra (linha mais próxima abaixo da pauta)
            val lowerTop = sy.getOrNull(si + 1)?.top
            val lt = lyricToks.filter { t ->
                t.y < s.bot - 0.8 * sp && t.y > s.bot - 9 * sp && t.size > 5 && t.size < 16 &&
                    (lowerTop == null || (s.bot - t.y) < (t.y - lowerTop)) && t.x >= s.x0 && t.x1 <= s.x1 + 5
            }
            var hasLyrics = false
            if (lt.isNotEmpty() && events.any { !it.rest }) {
                val ly = lt.maxOf { it.y }   // linha mais próxima (verso 1)
                val line = lt.filter { abs(it.y - ly) < 2.5 }.sortedBy { it.x }
                val words = line.filter { it.text != "-" && it.text != "–" && it.text != "—" && it.text != "_" }
                var lastX = -1e9
                val ordered = line
                for ((i, t) in ordered.withIndex()) {
                    if (t.text == "-" || t.text == "–" || t.text == "—" || t.text == "_") continue
                    val cx = t.x + min((t.x1 - t.x) / 2, 1.5 * sp)
                    val ev = events.filter { !it.rest && it.x >= lastX + 0.5 * sp && it.lyric == null }.minByOrNull { abs(it.x - cx) }
                    if (ev != null && abs(ev.x - cx) <= 5 * sp) {
                        ev.lyric = t.text.trim('_')
                        val hb = i > 0 && ordered[i - 1].text.let { it == "-" || it == "–" }
                        val ha = i + 1 < ordered.size && ordered[i + 1].text.let { it == "-" || it == "–" }
                        ev.syl = if (hb && ha) 'm' else if (hb) 'e' else if (ha) 'b' else 's'
                        lastX = ev.x; hasLyrics = true
                    }
                }
                if (words.isEmpty()) hasLyrics = false
            }
            if (System.getProperty("vdbg2") != null && s.x0 > 0) System.err.println("STAFF si=$si y=${s.bot.toInt()} " + events.sortedBy { it.x }.joinToString(" ") { "${it.x.toInt()}:${it.dur}" + (if (it.rest) "r" else "") })
            staffData += StaffData(s, clef, fifths, ts, events, heads, hasLyrics)
        }
        // cifras
        val chordToks = textTokens(gl, true)
        val harm = ArrayList<Triple<Double, Double, Harmony0>>()
        for (t in chordToks) {
            val h = parseChord(t.text) ?: continue
            if (t.y < sy.last().bot - 3 * sp0 || t.y > sy.first().top + 12 * sp0) continue
            harm += Triple(t.x, t.y, h)
        }
        return SystemData(staffData, bars, harm)
    }

    private fun yRangeAt(P: List<DoubleArray>, x: Double): Pair<Double, Double>? {
        val ys = ArrayList<Double>()
        for (i in P.indices) {
            val a = P[i]; val b = P[(i + 1) % P.size]
            if (a[0] == b[0]) continue
            if (x >= min(a[0], b[0]) && x <= max(a[0], b[0])) { val t = (x - a[0]) / (b[0] - a[0]); ys += a[1] + t * (b[1] - a[1]) }
        }
        return if (ys.isEmpty()) null else ys.min() to ys.max()
    }

    // ----------------------------------------------------------------- montagem da partitura
    private class RawNote(val pitch: Pitch?, val dur: Int, val voice: Int, val chord: Boolean, val tieStart: Boolean, val tieStop: Boolean, val lyric: String?, val syl: Char)

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun assemble(systems: List<SystemData>, o: OmrOptions, title: String?, tempo: Int?, warnings: MutableList<String>): OmrResult {
        // esqueleto de partes
        val skeleton = systems.maxByOrNull { it.staves.size }!!.staves.map { it.staff.key }.toMutableList()
        for (sd in systems) for (st in sd.staves) if (st.staff.key !in skeleton) {
            // insere depois da última chave do mesmo tipo/grupo, senão no fim
            val idx = skeleton.indexOfLast { it.substringBeforeLast('/') == st.staff.key.substringBeforeLast('/') }
            if (idx >= 0) skeleton.add(idx + 1, st.staff.key) else skeleton.add(st.staff.key)
            warnings += "Alguns sistemas têm pautas extras; incluí como pauta adicional."
        }
        var ts: Pair<Int, Int>? = null
        if (o.beats != null) ts = o.beats to o.beatType
        for (sd in systems) { if (ts != null) break; ts = sd.staves.firstNotNullOfOrNull { it.ts } }
        val tsv = ts ?: (4 to 4)
        var barLen = 96 * tsv.first / tsv.second
        val firstClef = HashMap<String, Char>()
        val rawParts = HashMap<String, ArrayList<ArrayList<RawNote>>>()   // chave -> medidas -> notas
        val harmByPart = HashMap<String, ArrayList<ArrayList<Pair<Int, Harmony0>>>>()
        for (k in skeleton) { rawParts[k] = ArrayList(); harmByPart[k] = ArrayList() }
        var firstMeasure = true; var notes = 0; var adjusted = 0; var bars = 0
        var fifths: Int? = null
        var tsChange = false
        for (sd in systems) {
            val nBars = sd.bars.size
            sd.staves.firstNotNullOfOrNull { it.ts }?.let { t -> if (o.beats == null && t != tsv) { tsChange = true } ; if (o.beats == null && t == tsv) barLen = 96 * t.first / t.second }
            val sysFifths = sd.staves.firstOrNull()?.fifths ?: 0
            if (fifths == null) fifths = sysFifths else if (fifths != sysFifths && sd === systems.firstOrNull { it === sd }) { }
            val present = HashMap<String, StaffData>()
            for (st in sd.staves) present[st.staff.key] = st
            for (k in skeleton) {
                val st = present[k]
                if (st != null) firstClef.putIfAbsent(k, st.clef)
                val mlist = rawParts[k]!!; val hl = harmByPart[k]!!
                for (mi in 0 until nBars) {
                    if (st == null) { mlist.add(arrayListOf(RawNote(null, barLen, 1, false, false, false, null, 's'))); hl.add(ArrayList()); continue }
                    val xs = listOf(st.staff.x0) + sd.bars
                    val a = xs[mi]; val b = xs[mi + 1]
                    val inm = st.events.filter { if (mi == 0) it.x < b else it.x >= a && it.x < b }.sortedBy { it.x }
                    val sp = st.staff.sp
                    val res = buildMeasure(st, inm, barLen, sp, firstMeasure && mi == 0 && sd === systems[0])
                    if (res.second) adjusted++
                    mlist.add(res.first)
                    hl.add(ArrayList())
                }
                // pitch já foi resolvido em buildMeasure
            }
            // cifras → pauta logo abaixo do texto
            for ((hx, hy, h) in sd.harmony) {
                val under = sd.staves.filter { hy >= it.staff.top - 2 * it.staff.sp }.minByOrNull { hy - it.staff.top } ?: continue
                val k = under.staff.key
                val xs = listOf(under.staff.x0) + sd.bars
                var mi = (0 until nBars).firstOrNull { hx >= xs[it] - 0.01 && hx < xs[it + 1] } ?: continue
                val idxBase = (harmByPart[k]!!.size - nBars)
                val inm = under.events.filter { !it.rest && (if (mi == 0) it.x < xs[1] else it.x >= xs[mi] && it.x < xs[mi + 1]) && it.voice == 1 }.sortedBy { it.x }
                var at = 0.0
                for (e in inm) { if (e.x <= hx + 0.6 * under.staff.sp) { at = e.onset } else break }
                harmByPart[k]!![idxBase + mi].add(at.toInt() to h)
            }
            bars += nBars
            firstMeasure = false
        }
        // divisões
        var g = 24
        for (k in skeleton) for (m in rawParts[k]!!) for (n in m) g = gcd(g, n.dur)
        g = gcd(g, barLen)
        if (g <= 0) g = 1
        var div = 24 / g
        val kf = 4 / gcd(div, 4); div *= kf
        val scale = kf.toDouble() / g
        val score = Score(o.title.takeIf { o.title != "Partitura lida" } ?: title ?: o.title, div, fifths ?: 0, tsv.first, tsv.second, tempo ?: o.tempo)
        var gname = HashMap<String, Int>()
        for ((pi, k) in skeleton.withIndex()) {
            val kind = k.substringBefore('/'); val gnum = k.substringAfter('/').substringBefore('/'); val slot = k.substringAfterLast('/').toInt()
            val groupId = "G$kind$gnum"
            val nstaves = skeleton.count { it.substringBeforeLast('/') == k.substringBeforeLast('/') }
            val baseName = when (kind) { "brace" -> "Piano"; "bracket" -> "Coro"; else -> "Pauta ${pi + 1}" }
            val name = if (nstaves > 1) "$baseName (pauta ${slot + 1})" else baseName
            val part = Part("P${pi + 1}", name, mutableListOf(), firstClef[k] ?: 'G', if (kind == "single") "P${pi + 1}" else groupId, if (kind == "bracket") "voice" else "piano")
            for ((mi, m) in rawParts[k]!!.withIndex()) {
                val me = Measure(mi + 1)
                for (n in m) {
                    me.notes += Note(n.pitch, (n.dur * scale).roundToInt().coerceAtLeast(1), n.voice, n.chord, n.tieStart, n.tieStop, n.lyric, n.syl)
                    if (n.pitch != null && !n.chord) notes++ else if (n.pitch != null) notes++
                }
                for ((at, h) in harmByPart[k]!!.getOrNull(mi) ?: emptyList()) me.harmony += Harmony((at * scale).roundToInt(), h.step, h.alter, h.kind, h.bassStep, h.bassAlter)
                part.measures += me
            }
            score.parts += part
        }
        if (tsChange) warnings += "O PDF muda de fórmula de compasso no meio da peça; o app guarda uma fórmula só, então alguns compassos podem aparecer com duração diferente."
        if (adjusted > 0) warnings += "$adjusted compasso(s) tiveram a duração ajustada (ritmo mais complexo que o leitor entende)."
        return OmrResult(score, score.parts.size, systems.size, notes, bars, adjusted, warnings)
    }

    /** Converte os eventos de um compasso em notas (vozes, altura, duração). Devolve (notas, ajustou?). */
    private fun buildMeasure(st: StaffData, inm: List<Ev>, barLen: Int, sp: Double, pickupOk: Boolean): Pair<ArrayList<RawNote>, Boolean> {
        val s = st.staff; val mid = (s.bot + s.top) / 2
        val up = inm.filter { !it.rest && it.up == true }; val dn = inm.filter { !it.rest && it.up == false }
        val two = up.any { u -> dn.any { abs(u.x - it.x) < 0.9 * sp } }
        val full = inm.filter { !it.rest && !it.hasStem && it.dur >= barLen * 0.99 }
        val others = inm.filter { it !in full }
        fun ym(e: Ev) = e.heads.map { it.y }.average()
        val voices = arrayOf(ArrayList<Ev>(), ArrayList<Ev>())
        var fullFirst = false
        for (e in inm) {
            var v = 0
            if (e in full && others.any { !it.rest }) {
                val om = others.filter { !it.rest }.map { ym(it) }.average()
                v = if (ym(e) < om) 1 else 0; e.full = true; if (v == 0) fullFirst = true
            } else if (two) {
                v = if (e.rest) (if (e.y > mid) 0 else 1) else if (e.up == false) 1 else 0
            }
            voices[v] += e
        }
        if (fullFirst) { val f = voices[0].filter { it.full }; val r = voices[0].filter { !it.full } + voices[1]; voices[0] = ArrayList(f); voices[1] = ArrayList(r) }
        val out = ArrayList<RawNote>(); var adjusted = false
        // armadura + acidentes do compasso
        val keyAlter = HashMap<Char, Int>()
        if (st.fifths > 0) for (i in 0 until min(7, st.fifths)) keyAlter[SHARP_ORDER[i]] = 1
        if (st.fifths < 0) for (i in 0 until min(7, -st.fifths)) keyAlter[FLAT_ORDER[i]] = -1
        val measAcc = HashMap<Int, Int>()
        val allHeads = inm.flatMap { e -> e.heads.map { it to e } }.sortedBy { it.second.x }
        val pitchOf = HashMap<Head, Pitch>()
        for ((h, _) in allHeads) {
            val base = if (st.clef == 'G') 4 * 7 + 2 else 2 * 7 + 4
            val d = base + h.pos
            val step = STEPS[Math.floorMod(d, 7)]; val oct = Math.floorDiv(d, 7)
            val ak = d
            when (h.acc) { "flat" -> measAcc[ak] = -1; "sharp" -> measAcc[ak] = 1; "natural" -> measAcc[ak] = 0 }
            val alter = measAcc[ak] ?: (keyAlter[step] ?: 0)
            pitchOf[h] = Pitch(step, alter, oct)
        }
        for ((vi, ev) in voices.withIndex()) {
            if (ev.isEmpty()) continue
            var sum = 0
            val list = ArrayList<RawNote>()
            var onset = 0.0
            for (e in ev.sortedBy { it.x }) {
                e.onset = onset
                val d = if (e.rest && e.measureRest && ev.size == 1) barLen else e.dur.roundToInt()
                if (e.rest) list += RawNote(null, d, vi + 1, false, false, false, null, 's')
                else {
                    val hs = e.heads.sortedBy { it.pos }
                    for ((i, h) in hs.withIndex()) list += RawNote(pitchOf[h], d, vi + 1, i > 0, h.tie, h.tieStop, if (i == 0) e.lyric else null, e.syl)
                }
                e.voice = vi + 1
                sum += d; onset += d
            }
            if (sum < barLen && !(pickupOk && vi == 0 && sum > 0)) list += RawNote(null, barLen - sum, vi + 1, false, false, false, null, 's')
            else if (sum > barLen) adjusted = true
            out += list
        }
        if (out.isEmpty()) out += RawNote(null, barLen, 1, false, false, false, null, 's')
        return out to adjusted
    }
}
