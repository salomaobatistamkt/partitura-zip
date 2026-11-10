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
        var stem = -1; var acc: String? = null; var tie = false; var tieStop = false; var clef = 'G'
        val x get() = g.x; val y get() = g.y
    }
    private class Ev(var x: Double, var dur: Double, val rest: Boolean) {
        var heads = ArrayList<Head>(); var up: Boolean? = null; var tip = 0.0; var stemX = 0.0; var y = 0.0; var measureRest = false
        var lyric: String? = null; var syl = 's'; var voice = 1; var full = false; var hasStem = false; var onset = 0.0
    }

    // ----------------------------------------------------------------- classificação das fontes
    private val MUSIC_FONT_PREFIX = listOf("Opus", "Petrucci", "Maestro", "Bravura", "Leland", "Petaluma", "Gonville", "Emmentaler", "Sebastian", "November", "Finale Maestro")
    private val DYN_VALID = setOf("pppp", "ppp", "pp", "p", "mp", "mf", "f", "ff", "fff", "ffff", "sf", "sfz", "sffz", "fp", "rfz", "fz", "sfp")

    private fun classify(font: String, ch: Int): String? {
        if (ch in 0xE000..0xF8FF) {
            return when (ch) {
                0xE050 -> "clefG"; 0xE062 -> "clefF"; 0xE05C -> "clefC"
                0xE0A2 -> "headWhole"; 0xE0A3 -> "headHalf"; 0xE0A4 -> "headFilled"
                0xE260 -> "flat"; 0xE261 -> "natural"; 0xE262 -> "sharp"
                0xE4E3 -> "restWhole"; 0xE4E4 -> "restHalf"; 0xE4E5 -> "restQuarter"; 0xE4E6 -> "rest8"; 0xE4E7 -> "rest16"
                0xE1E7 -> "dot"; 0xE240, 0xE241 -> "flag"; 0xE242, 0xE243 -> "flag2"; 0xE244, 0xE245 -> "flag3"
                in 0xE080..0xE089 -> "timeDigit"; in 0xE880..0xE889 -> "tupDigit"
                in 0xE520..0xE525 -> "dyn"; 0xE4C0, 0xE4C1 -> "fermata"
                else -> null
            }
        }
        val f = font
        if (f.startsWith("Petrucci")) return when (ch) {
            0x26 -> "clefG"; 0x3f -> "clefF"; 0x62 -> "flat"; 0x23 -> "sharp"; 0x6e -> "natural"
            0xcf -> "headFilled"; 0xfa -> "headHalf"; 0x77 -> "headWhole"
            0xb7 -> "restWhole"; 0xee -> "restHalf"; 0xce -> "restQuarter"; 0xe4 -> "rest8"
            0x4a, 0x6a -> "flag"; 0x4b, 0x6b -> "flag2"; 0x4c, 0x6c -> "flag3"; 0x55, 0x75 -> "fermata"; else -> null
        }
        if (f.startsWith("Opus")) {
            if (f.contains("Special")) return when (ch) { 0x2122 -> "dot"; 0x7b -> "brace"; else -> null }
            if (f.contains("Text")) return if (ch in 0x30..0x39) "tupDigit" else if (ch.toChar() in "fmpszr") "dyn" else null
            if (f.contains("Chords")) return "chord"
            if (ch in 0x30..0x39) return "timeDigit"
            if (ch == 0x4a || ch == 0x6a) return "flag"
            if (ch == 0x4b || ch == 0x6b) return "flag2"
            if (ch == 0x4c || ch == 0x6c) return "flag3"
        }
        return null
    }
    private fun isMusicFont(f: String) = !f.startsWith("MaestroTimes") && MUSIC_FONT_PREFIX.any { f.startsWith(it) }
    private fun dynLetter(g: G): Char = if (g.ch in 0xE520..0xE525) "pmfrsz"[g.ch - 0xE520] else g.ch.toChar()

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
        var title: String? = null; var tempo: Int? = null; var credits: List<Credit> = emptyList()
        for ((pi, pg) in pages.withIndex()) {
            progress?.invoke(pi, pages.size)
            val d = try { PdfContent.read(pdf, pg) } catch (e: Exception) { continue }
            val gl = ArrayList<G>()
            for (g in d.glyphs) { val f = g.font; gl += G(classify(f, g.ch) ?: "", g.x, g.y, g.size, g.ch, f, g.adv) }
            val hasMusic = gl.any { it.c.startsWith("head") }
            if (!hasMusic) continue
            val r = analyzePage(d, gl, pi == 0)
            systemsAll += r.systems
            if (pi == 0) credits = r.credits
            if (pi == 0 || title == null) { r.title?.let { if (title == null) title = it }; r.tempo?.let { if (tempo == null) tempo = it } }
        }
        progress?.invoke(pages.size, pages.size)
        if (systemsAll.isEmpty()) return null
        val res = assemble(systemsAll, o, title, tempo, warnings)
        res.score.credits = credits
        return res
    }

    private class StaffData(val staff: Staff, val clef: Char, val fifths: Int, val ts: Pair<Int, Int>?, val events: List<Ev>, val heads: List<Head>, val lyricsOn: Boolean,
                            val keyMarks: List<Pair<Double, Int>>, val tsMarks: List<Pair<Double, Pair<Int, Int>>>, val clefMarks: List<Pair<Double, Char>>, val meanD: Double?) { var label: String? = null }
    private class DynMark(val x: Double, val y: Double, val mark: String)
    private class WordMark(val x: Double, val y: Double, val kind: String, val bpm: Int = 0)
    private class HairMark(val x0: Double, val x1: Double, val y: Double, val cresc: Boolean)
    private class VoltaMark(val x0: Double, val x1: Double, val y: Double, val num: Int)
    private class SystemData(val staves: List<StaffData>, val bars: List<Double>, val harmony: List<Triple<Double, Double, Harmony0>>,
                             val dbl: List<Boolean>, val repStart: BooleanArray, val repEnd: BooleanArray) {
        val dyns = ArrayList<DynMark>(); val words = ArrayList<WordMark>(); val hairs = ArrayList<HairMark>(); val voltas = ArrayList<VoltaMark>(); val ferm = ArrayList<Pair<Double, Double>>()
    }
    private class Harmony0(val step: Char, val alter: Int, val kind: String, val bassStep: Char?, val bassAlter: Int)
    private class PageResult(val systems: List<SystemData>, val title: String?, val tempo: Int?, val credits: List<Credit> = emptyList())

    // ----------------------------------------------------------------- análise de uma página
    private class Run(val text: String, val x0: Double, val x1: Double, val y: Double, val size: Double)

    /** Trechos de texto (palavras na mesma linha) de fontes comuns: títulos, "rit.", "a tempo", "= 80", "1."… */
    private fun textRuns(gl: List<G>): List<Run> {
        val src = gl.filter { it.c == "" && !isMusicFont(it.font) && (it.ch in 32..0x2FF || it.ch in 0x2010..0x2015) }.sortedBy { it.y }
        val rows = ArrayList<ArrayList<G>>()
        for (g in src) { val l = rows.lastOrNull(); if (l != null && abs(l[0].y - g.y) < 1.2) l += g else rows += arrayListOf(g) }
        val out = ArrayList<Run>()
        for (r in rows) {
            val row = r.sortedBy { it.x }
            var sb = StringBuilder(); var x0 = 0.0; var xe = 0.0; var sz = 0.0; var y = 0.0
            fun flush() { val t = sb.toString().trim(); if (t.isNotEmpty()) out += Run(t, x0, xe, y, sz); sb = StringBuilder() }
            for (g in row) {
                if (sb.isNotEmpty() && g.x - xe > 1.5 * g.size) flush()
                if (sb.isEmpty()) { x0 = g.x; sz = g.size; y = g.y }
                else if (g.ch != 32 && g.x - xe > 0.2 * g.size && !sb.endsWith(" ")) sb.append(' ')
                sb.append(if (g.ch == 32) " " else String(Character.toChars(g.ch)))
                xe = g.x + g.adv
            }
            flush()
        }
        return out
    }

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
        val lyricToks = textTokens(gl, false).let { all ->
            // rodapé (©, direitos reservados, número de página) não é letra: descarta a linha toda e o que está na margem de baixo
            val footRows = all.filter { it.text.contains("©") || it.text.lowercase().startsWith("copyright") || it.text.lowercase().startsWith("direitos") || it.text.lowercase() == "reservados." }.map { it.y }
            all.filter { t -> t.y > d.height * 0.06 && footRows.none { abs(it - t.y) < 3.0 } }
        }
        val runs = textRuns(gl)
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
        // nomes das pautas (texto à esquerda do começo da pauta)
        for ((si, sy) in systems.withIndex()) for ((k, s) in sy.withIndex()) {
            val mid = (s.top + s.bot) / 2
            val lab = runs.filter { it.x1 < s.x0 - 1.5 && it.x0 > s.x0 - 70 && abs(it.y - mid) < 2.6 * s.sp && it.size in 6.0..14.0 && it.text.any { c -> c.isLetter() } }.maxByOrNull { it.x1 }
            if (lab != null && k < out[si].staves.size) out[si].staves[k].label = lab.text
        }
        // marcas de expressão: dinâmicas, andamento, hairpins, casas, fermatas
        fun sysOf(y: Double, reach: Double): Int {
            var best = -1; var bd = 1e9
            for ((i, sy) in systems.withIndex()) {
                val sp = sy[0].sp
                val top = sy.first().top; val bot = sy.last().bot
                val dd = if (y > top) y - top else if (y < bot) bot - y else 0.0
                if (dd / sp < reach && dd < bd) { bd = dd; best = i }
            }
            return best
        }
        val sp0 = systems.firstOrNull()?.get(0)?.sp ?: 4.75
        // dinâmicas: letras p m f s z r agrupadas em palavras
        val dg = gl.filter { it.c == "dyn" && it.size >= 6.5 }.sortedWith(compareBy({ -it.y }, { it.x }))
        val dtoks = ArrayList<Triple<Double, Double, String>>()
        run {
            var cur = StringBuilder(); var cx = 0.0; var cy = 0.0; var lastEnd = 0.0
            fun flush() { val t = cur.toString(); if (t in DYN_VALID) dtoks += Triple(cx, cy, t); cur = StringBuilder() }
            for (g in dg) {
                if (cur.isNotEmpty() && (abs(g.y - cy) > 2.5 || g.x - lastEnd > 0.55 * g.size)) flush()
                if (cur.isEmpty()) { cx = g.x; cy = g.y }
                cur.append(dynLetter(g)); lastEnd = g.x + (if (g.adv > 0) g.adv else 0.6 * g.size)
            }
            flush()
        }
        for (r in runs) if (r.text in DYN_VALID && r.size >= 7) dtoks += Triple(r.x0, r.y, r.text)
        for ((x, y, t) in dtoks) { val si = sysOf(y, 8.0); if (si >= 0) out[si].dyns += DynMark(x, y, t) }
        // palavras e andamento
        val qGlyphs = gl.filter { it.c == "" && it.ch == 0x71 && it.font.contains("OpusText") }
        for (r in runs) {
            val t = r.text.lowercase().trim()
            val si = sysOf(r.y, 9.0); if (si < 0) continue
            val kind = when {
                t.startsWith("poco rit") || t.startsWith("poco rall") -> "pocorit"
                t.startsWith("rit") || t.startsWith("rall") -> "rit"
                t.startsWith("accel") -> "accel"
                t.startsWith("a tempo") || t == "tempo i" || t.startsWith("tempo primo") -> "atempo"
                t.startsWith("poco a poco cresc") || t.startsWith("cresc") -> "cresc"
                t.startsWith("decresc") || t.startsWith("dim") -> "dim"
                else -> null
            }
            if (kind != null) out[si].words += WordMark(r.x0, r.y, kind)
            val m = Regex("=\\s*(\\d{2,3})\\b").find(t)
            if (m != null && r.y > systems[si].first().top) {
                var bpm = m.groupValues[1].toInt().toDouble()
                val q = qGlyphs.minByOrNull { abs(it.y - r.y) + abs(it.x - r.x0) }
                if (q != null && abs(q.y - r.y) < 6 && r.x0 - q.x in 0.0..4 * q.size) {
                    val dotted = gl.any { it.ch == 0x2E && it.font.contains("OpusText") && abs(it.y - q.y) < 3 && it.x > q.x && it.x < q.x + 1.2 * q.size }
                    if (dotted) bpm *= 1.5
                }
                if (bpm in 30.0..300.0) out[si].words += WordMark(r.x0, r.y, "tempo", bpm.roundToInt())
            }
        }
        // hairpins
        run {
            val sl = segs.filter { val dx = abs(it.x2 - it.x1); val dy = abs(it.y2 - it.y1); dx > 2.5 * sp0 && dy > 0.1 * sp0 && dy < 0.45 * dx && it.w < 1.7 }
                .map { if (it.x1 <= it.x2) it else Seg(it.x2, it.y2, it.x1, it.y1, it.w) }
            val used = HashSet<Seg>()
            for (a in sl) {
                if (a in used) continue
                for (b in sl) {
                    if (b === a || b in used) continue
                    val tol = 0.55 * sp0
                    val openRight = abs(a.x1 - b.x1) < tol && abs(a.y1 - b.y1) < tol && abs(a.x2 - b.x2) < 1.0 * sp0 && abs(a.y2 - b.y2) > 0.5 * sp0 && (a.y2 - a.y1) * (b.y2 - b.y1) < 0
                    val openLeft = abs(a.x2 - b.x2) < tol && abs(a.y2 - b.y2) < tol && abs(a.x1 - b.x1) < 1.0 * sp0 && abs(a.y1 - b.y1) > 0.5 * sp0 && (a.y2 - a.y1) * (b.y2 - b.y1) < 0
                    if (!openRight && !openLeft) continue
                    val y = (a.y1 + a.y2 + b.y1 + b.y2) / 4
                    val si = sysOf(y, 8.0)
                    if (si >= 0) out[si].hairs += HairMark(min(a.x1, b.x1), max(a.x2, b.x2), y, openRight)
                    used += a; used += b; break
                }
            }
        }
        // casas de repetição: linha horizontal com gancho à esquerda e número ("1.", "2.") logo abaixo
        run {
            val hl = segs.filter { abs(it.y1 - it.y2) < 0.25 && abs(it.x1 - it.x2) > 4 * sp0 && it.w < 1.8 }
            for (h in hl) {
                val hx0 = min(h.x1, h.x2); val hx1 = max(h.x1, h.x2); val hy = h.y1
                val si = sysOf(hy, 12.0); if (si < 0) continue
                val sy = systems[si]; if (hy < sy.first().top + 1.5 * sp0) continue
                val hook = vert.any { abs(it.x1 - hx0) < 0.7 && max(it.y1, it.y2) >= hy - 0.3 && hy - min(it.y1, it.y2) in 0.8 * sp0..4.6 * sp0 }
                if (!hook) continue
                val txt = runs.filter { it.x0 >= hx0 - 0.5 && it.x0 <= hx0 + 4.5 * sp0 && it.y < hy + 0.5 && it.y > hy - 4.8 * sp0 && Regex("^\\d\\.?.*").matches(it.text.trim()) }.minByOrNull { it.x0 } ?: continue
                val num = txt.text.trim().first().digitToInt()
                if (num in 1..4) out[si].voltas += VoltaMark(hx0, hx1, hy, num)
            }
        }
        // fermatas
        for (g in gl) if (g.c == "fermata") { val si = sysOf(g.y, 7.0); if (si >= 0) out[si].ferm += g.x to g.y }
        // título e andamento (primeira página)
        var title: String? = null; var tempo: Int? = null
        var credits: List<Credit> = emptyList()
        var titleY = Double.NaN
        if (first) {
            val tg = gl.filter { !isMusicFont(it.font) && it.c == "" && it.ch in 32..0x2FF }
            val big = tg.maxOfOrNull { it.size }
            if (big != null && big > 11) {
                val line = tg.filter { it.size >= big - 0.5 }.sortedBy { it.x }
                val y0 = line.maxOf { it.y }; titleY = y0
                title = line.filter { abs(it.y - y0) < big }.sortedBy { it.x }.joinToString("") { String(Character.toChars(it.ch)) }.trim().ifBlank { null }
            }
            // textos do cabeçalho: acima do primeiro sistema, longe o bastante para não serem cifras nem andamento
            run {
                val st0 = staves.firstOrNull() ?: return@run
                val hh = st0.ys[0] - st0.ys[4]
                val above = tg.filter { it.y > st0.ys[0] + hh * 2.2 }.sortedByDescending { it.y }
                val rows = ArrayList<ArrayList<G>>()
                for (g in above) { val r = rows.firstOrNull { abs(it[0].y - g.y) < max(it[0].size, g.size) * 0.6 }; if (r != null) r += g else rows += arrayListOf(g) }
                val list = ArrayList<Credit>(); var rowNo = 0
                var prevY = titleY
                val STOP = setOf("piano", "unis.", "unís.", "div.", "solo", "uh", "coro", "s", "c", "t", "b", "ah", "oh", "tutti")
                for (r in rows.sortedByDescending { it[0].y }.filter { titleY.isNaN() || it[0].y < titleY - 0.5 }) {
                    if (!prevY.isNaN() && prevY - r[0].y > 3.6 * r[0].size) break   // o cabeçalho é um bloco junto ao título
                    prevY = r[0].y
                    val sorted = r.sortedBy { it.x }
                    val runs = ArrayList<ArrayList<G>>()
                    for (g in sorted) { val last = runs.lastOrNull(); if (last != null && g.x - (last.last().x + last.last().adv) < g.size * 1.3) last += g else runs += arrayListOf(g) }
                    var any = false
                    for (run in runs) {
                        val t = run.joinToString("") { String(Character.toChars(it.ch)) }.replace(Regex("\\s+"), " ").trim()
                        if (t.length < 2 || t.lowercase() in STOP || Regex("[a-zà-ú]-[a-zà-ú]").containsMatchIn(t) || Regex("=\\s*\\d").containsMatchIn(t) || t == title) continue
                        val x0 = run.first().x; val x1 = run.last().x + run.last().adv
                        val frac = ((x0 + x1) / 2) / d.width
                        list += Credit(t, if (frac < 0.33) 'L' else if (frac > 0.67) 'R' else 'C', rowNo); any = true
                    }
                    if (any) rowNo++
                }
                credits = list.take(8)
            }
            for (si in out.indices) { val w = out[si].words.firstOrNull { it.kind == "tempo" }; if (w != null) { tempo = w.bpm; break } }
            if (tempo == null) {
                val rows = tg.groupBy { (it.y / 3).roundToInt() }
                for ((_, r) in rows) {
                    val txt = r.sortedBy { it.x }.joinToString("") { String(Character.toChars(it.ch)) }
                    val m = Regex("=\\s*(\\d{2,3})").find(txt)
                    if (m != null && tempo == null) { val v = m.groupValues[1].toInt(); if (v in 30..300) tempo = v }
                }
            }
        }
        return PageResult(out, title, tempo, credits)
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
        val bars = ArrayList<Double>(); val dbl = ArrayList<Boolean>()
        for (x in bx) { if (bars.isNotEmpty() && x - bars.last() < 2.0 * sp0) { bars[bars.size - 1] = x; dbl[dbl.size - 1] = true } else { bars += x; dbl += false } }
        val x1 = sy.maxOf { it.x1 }
        val sysHeadsX = allHeads.filter { h -> sy.any { it.bot - 9 * sp0 <= h.y && h.y <= it.top + 9 * sp0 } }.map { it.x }
        val rest = gl.filter { it.c.startsWith("rest") }.filter { r -> sy.any { it.bot - 9 * sp0 <= r.y && r.y <= it.top + 9 * sp0 } }.map { it.x }
        val lastX = (sysHeadsX + rest).maxOrNull() ?: 0.0
        if (bars.isEmpty() || (bars.last() < x1 - 3 && lastX > bars.last())) { bars += x1; dbl += false }
        // ritornelos: dois pontos nos espaços 2 e 3 da pauta, ao lado de uma barra
        var repStart = BooleanArray(bars.size); var repEnd = BooleanArray(bars.size)
        val repeatDots = HashSet<G>()
        run {
            val dots = gl.filter { it.c == "dot" }
            val s0 = sy[0]; val mid0 = (s0.bot + s0.top) / 2
            // dois pontos alinhados nos espaços 2 e 3 da pauta, a até ~2,6 espaços da barra
            fun side(xa: Double, xb: Double): List<G> {
                val c = dots.filter { it.x >= xa && it.x <= xb && abs(it.y - mid0) <= 0.8 * sp0 }
                for (a in c) { val b = c.firstOrNull { abs(it.x - a.x) < 0.3 * sp0 && abs(abs(it.y - a.y) - sp0) < 0.3 * sp0 }; if (b != null) return listOf(a, b) }
                return emptyList()
            }
            for ((bi, bx0) in bars.withIndex()) {
                if (!dbl[bi]) continue
                val l = side(bx0 - 3.0 * sp0, bx0 - 0.15 * sp0)
                if (l.isNotEmpty()) { repEnd[bi] = true; repeatDots += l }
                if (bi + 1 < bars.size) { val r = side(bx0 + 0.15 * sp0, bx0 + 3.0 * sp0); if (r.isNotEmpty()) { repStart[bi + 1] = true; repeatDots += r } }
            }
            // início de sistema com ritornelo: barra grossa logo depois da armadura e antes de qualquer nota
            val firstEvX = (sysHeadsX + rest).minOrNull() ?: 1e9
            if (bars.size > 1 && firstEvX > bars[0] && dbl[0]) {
                val r0 = side(bars[0] + 0.15 * sp0, bars[0] + 3.0 * sp0)
                if (r0.isNotEmpty()) repeatDots += r0
                val rs = repStart[1] || r0.isNotEmpty()
                bars.removeAt(0); dbl.removeAt(0)
                repStart = BooleanArray(bars.size).also { n -> for (i in n.indices) n[i] = repStart[i + 1] }; repStart[0] = rs
                repEnd = BooleanArray(bars.size).also { n -> for (i in n.indices) n[i] = repEnd[i + 1] }
            }
        }
        if (System.getProperty("vdbg2") != null) System.err.println("BARS x0=${sy[0].x0.toInt()} x1=${x1.toInt()}: " + bars.joinToString(" ") { it.toInt().toString() })

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
            }.filter { it !in repeatDots }.distinctBy { listOf(it.c, (it.x * 5).roundToInt(), (it.y * 5).roundToInt()) }
            val heads0 = gs.filter { it.c.startsWith("head") }
            // duplicatas exatas (duas vozes com a mesma nota): mantém ambas só se houver duas hastes
            val dupHeads = gl.filter { g -> g.c.startsWith("head") && nearestStaff(g.y).let { it.first == si && it.second <= 6.2 * sp } && g.size >= 3.3 * sp }
            val rests = gs.filter { it.c.startsWith("rest") }
            val clefsAll = gs.filter { it.c.startsWith("clef") }.sortedBy { it.x }
            val clefs = clefsAll.filter { it.x < s.x0 + 8 * sp }
            val clef = if (clefs.firstOrNull()?.c == "clefF") 'F' else 'G'
            val clefMarks = clefsAll.filter { it.x >= s.x0 + 8 * sp && (it.c == "clefF" || it.c == "clefG") }.map { it.x to (if (it.c == "clefF") 'F' else 'G') }
            fun clefAt(x: Double): Char { var c = clef; for ((cx, cc) in clefMarks) if (cx < x - 0.2 * sp) c = cc; return c }
            val firstX = (heads0 + rests).minOfOrNull { it.x } ?: x1
            val accs = gs.filter { it.c == "flat" || it.c == "sharp" || it.c == "natural" }
            val ks = accs.filter { it.x < firstX - 3.0 * sp && it.x < s.x0 + 30 * sp && (bars.isEmpty() || it.x < bars[0]) }
            val fifths = ks.count { it.c == "sharp" } - ks.count { it.c == "flat" }
            // mudanças de armadura/fórmula no meio do sistema (logo depois de uma barra)
            val keyMarks = ArrayList<Pair<Double, Int>>(); val tsMarks = ArrayList<Pair<Double, Pair<Int, Int>>>()
            val keyAccs = HashSet<G>(ks)
            for (bi in 0 until bars.size - 1) {
                val bx0 = bars[bi]; val bx1 = bars[bi + 1]
                val firstEv = (heads0 + rests).filter { it.x > bx0 && it.x < bx1 }.minOfOrNull { it.x } ?: bx1
                val region = accs.filter { it.x > bx0 + 0.2 * sp && it.x < firstEv - 0.4 * sp && it.x < bx0 + 22 * sp }.sortedBy { it.x }
                if (region.isNotEmpty() && region.first().x - bx0 <= 4.8 * sp) {
                    var grp = ArrayList<G>(); grp += region.first()
                    for (a in region.drop(1)) { if (a.x - grp.last().x <= 1.9 * sp) grp += a else break }
                    val fl = grp.count { it.c == "flat" }; val sh = grp.count { it.c == "sharp" }; val nat = grp.count { it.c == "natural" }
                    val enough = dbl[bi]
                    val attachedN = grp.count { a -> heads0.any { h -> h.x - a.x in 0.2 * sp..2.6 * sp && abs(h.y - a.y) <= 0.4 * sp } }
                    val unattached = attachedN * 2 < grp.size || (grp.size == 1 && attachedN == 0)
                    if (enough && unattached) {
                        val f = if (fl + sh > 0) sh - fl else 0
                        keyMarks += bx0 to f; keyAccs += grp
                    }
                }
                val dg = gs.filter { it.c == "timeDigit" && it.x > bx0 && it.x < firstEv && it.x < bx0 + 22 * sp }
                if (dg.isNotEmpty() && bi >= 0) {
                    fun nm(l: List<G>): Int? = l.sortedBy { it.x }.joinToString("") { if (it.ch < 0x100) it.ch.toChar().toString() else (it.ch - 0xE080).toString() }.toIntOrNull()
                    val a = nm(dg.filter { it.y > mid }); val b = nm(dg.filter { it.y <= mid })
                    if (a != null && b != null && a in 1..32 && b in listOf(1, 2, 4, 8, 16)) tsMarks += bx0 to (a to b)
                }
            }
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
            for (h in heads) h.clef = clefAt(h.x)
            // acidentes (fora da armadura)
            for (a in accs) {
                if (a in keyAccs) continue
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
                val fl = gs.filter { it.c.startsWith("flag") && abs(it.x - st[0]) < 1.6 * sp && abs(it.y - tip) < 4.2 * sp }
                val nf = max(fl.size, fl.maxOfOrNull { it.c.last().digitToIntOrNull() ?: 1 } ?: 0)
                val kind = hs[0].kind
                val e = Ev(hs.map { it.cx }.average(), duration(kind, dotCount(hs), if (kind == "headFilled") max(nb, nf) else 0), false)
                e.heads = ArrayList(hs); e.up = up; e.tip = tip; e.stemX = st[0]; e.hasStem = true
                events += e
            }
            val stemless = heads.filter { it.stem < 0 }.sortedBy { it.cx }
            val sl = ArrayList<Ev>()
            for (h in stemless) {
                val e = sl.firstOrNull { c -> abs(c.heads[0].cx - h.cx) < 0.5 * sp || (abs(c.heads[0].cx - h.cx) < 1.8 * sp && c.heads.any { abs(it.pos - h.pos) <= 1 } && c.heads[0].kind == h.kind) }
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
                    if (fa == null || fb == null) {
                        // sem colchete nem barra: as n notas consecutivas do lado do número, mais próximas dele
                        val sideUp = dg.y > s.top
                        val cands = events.filter { it.rest || it.up == sideUp }.sortedBy { it.x }
                        var bestW: List<Ev>? = null; var bc = 1e9
                        if (cands.size >= n) for (i in 0..cands.size - n) {
                            val w = cands.subList(i, i + n)
                            val c = abs((w.first().x + w.last().x) / 2 - dg.x)
                            if (c < bc && w.last().x - w.first().x < 16 * sp) { bc = c; bestW = w }
                        }
                        if (bestW == null || bc > 1.8 * sp) continue
                        fa = bestW.first().x - 0.1; fb = bestW.last().x + 0.1
                    }
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
            val meanD = if (heads.isEmpty()) null else heads.map { (if (it.clef == 'G') 30 else 18) + it.pos }.average()
            staffData += StaffData(s, clef, fifths, ts, events, heads, hasLyrics, keyMarks, tsMarks, clefMarks, meanD)
        }
        // cifras
        val chordToks = textTokens(gl, true)
        val harm = ArrayList<Triple<Double, Double, Harmony0>>()
        for (t in chordToks) {
            val h = parseChord(t.text) ?: continue
            if (t.y < sy.last().bot - 3 * sp0 || t.y > sy.first().top + 12 * sp0) continue
            harm += Triple(t.x, t.y, h)
        }
        return SystemData(staffData, bars, harm, dbl, repStart, repEnd)
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
    private class RawNote(val pitch: Pitch?, val dur: Int, val voice: Int, val chord: Boolean, val tieStart: Boolean, val tieStop: Boolean, val lyric: String?, val syl: Char) { var mr = false }
    private class Acc { val notes = ArrayList<RawNote>(); val harm = ArrayList<Pair<Int, Harmony0>>(); val dyn = ArrayList<Pair<Int, String>>(); var clef: Char? = null; var doubt: String? = null }
    private class MeasInfo { var fifths = 0; var beats = 4; var beatType = 4; var barLen = 96; var len = 96; var tempo: Int? = null; var rit = 0
        var repStart = false; var repEnd = false; var volta = 0; var ferm = false }
    private class BM(val voices: List<Pair<Int, ArrayList<RawNote>>>, val sums: List<Int>) {
        val mrOnly get() = voices.size == 1 && voices[0].second.size == 1 && voices[0].second[0].mr
        val maxSum get() = sums.maxOrNull() ?: 0
    }

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private fun pairCost(a: StaffData, b: StaffData): Double {
        var c = 0.0
        if (a.clef != b.clef) c += 1.0
        if (a.staff.kind != b.staff.kind) c += 0.25
        val da = a.meanD; val db = b.meanD
        if (da != null && db != null) c += min(1.0, abs(da - db) / 12.0)
        val la = a.label?.lowercase(); val lb = b.label?.lowercase()
        if (la != null && lb != null) c += if (la == lb) -0.3 else 0.3
        return c
    }

    /** Alinha as pautas de um sistema (algumas podem estar escondidas) com o esqueleto de partes. Devolve, para cada pauta, o índice da parte. */
    private fun align(skel: List<StaffData>, sys: List<StaffData>): IntArray {
        val n = skel.size; val m = sys.size
        if (m > n) return IntArray(m) { min(it, n - 1) }
        val INF = 1e18
        val dp = Array(m + 1) { DoubleArray(n + 1) { INF } }; val from = Array(m + 1) { IntArray(n + 1) }
        dp[0][0] = 0.0
        for (j in 1..n) dp[0][j] = dp[0][j - 1] + 0.04
        for (i in 1..m) for (j in 1..n) {
            var best = INF; var f = 0
            if (dp[i][j - 1] < INF) { best = dp[i][j - 1] + 0.04; f = 0 }
            if (dp[i - 1][j - 1] < INF) { val c = dp[i - 1][j - 1] + pairCost(sys[i - 1], skel[j - 1]); if (c < best - 1e-9) { best = c; f = 1 } }
            dp[i][j] = best; from[i][j] = f
        }
        val res = IntArray(m) { -1 }
        var i = m; var j = n
        while (i > 0 && j > 0) { if (from[i][j] == 1) { res[i - 1] = j - 1; i--; j-- } else j-- }
        return res
    }

    private fun onsetAt(evs: List<Ev>, x: Double, sp: Double, len: Int): Int {
        val e = evs.filter { it.voice == 1 }.sortedBy { it.x }.firstOrNull { it.x >= x - 0.6 * sp }
        return if (e == null) len else e.onset.toInt().coerceIn(0, len)
    }

    private fun assemble(systems: List<SystemData>, o: OmrOptions, title: String?, tempo: Int?, warnings: MutableList<String>): OmrResult {
        val skel = systems.maxByOrNull { it.staves.size }!!.staves
        val n = skel.size
        val tsFixed = o.beats != null
        var curTs: Pair<Int, Int> = if (o.beats != null) o.beats to o.beatType else (systems.firstNotNullOfOrNull { sd -> sd.staves.firstNotNullOfOrNull { it.ts } } ?: (4 to 4))
        val firstTs = curTs
        var curF = 0; var firstF: Int? = null
        val accs = List(n) { ArrayList<Acc>() }
        val infos = ArrayList<MeasInfo>()
        val firstClef = CharArray(n) { ' ' }; val curClef = CharArray(n) { ' ' }
        val labels = arrayOfNulls<String>(n)
        var notes = 0; var adjusted = 0; var bars = 0
        val scoreTempo = tempo ?: o.tempo
        if (tempo == null) warnings += "O PDF não traz o andamento escrito (♩ = número). Usei $scoreTempo bpm; ajuste em Reprodução → Andamento."
        var curTempo = scoreTempo
        val dbg = System.getProperty("vdbg3") != null
        for ((si, sd) in systems.withIndex()) {
            val slotOf = align(skel, sd.staves)
            val atSlot = arrayOfNulls<StaffData>(n)
            for ((i, st) in sd.staves.withIndex()) if (slotOf[i] >= 0) atSlot[slotOf[i]] = st
            for ((i, st) in sd.staves.withIndex()) if (slotOf[i] >= 0 && st.label != null && labels[slotOf[i]] == null) labels[slotOf[i]] = st.label
            val nB = sd.bars.size
            // armadura e fórmula no início do sistema e mudanças no meio
            val sysF = sd.staves.groupingBy { it.fifths }.eachCount().entries.maxByOrNull { it.value }?.key ?: curF
            curF = sysF; if (firstF == null) firstF = sysF
            if (!tsFixed) sd.staves.firstNotNullOfOrNull { it.ts }?.let { curTs = it }
            val keyVotes = HashMap<Int, HashMap<Int, Int>>(); val tsVotes = HashMap<Int, HashMap<Pair<Int, Int>, Int>>()
            for (st in sd.staves) {
                for ((x, f) in st.keyMarks) { val bi = sd.bars.indexOfFirst { abs(it - x) < 1.0 }; if (bi >= 0) keyVotes.getOrPut(bi) { HashMap() }.merge(f, 1, Int::plus) }
                if (!tsFixed) for ((x, t) in st.tsMarks) { val bi = sd.bars.indexOfFirst { abs(it - x) < 1.0 }; if (bi >= 0) tsVotes.getOrPut(bi) { HashMap() }.merge(t, 1, Int::plus) }
            }
            val x0 = sd.staves.first().staff.x0
            val xs = listOf(x0) + sd.bars
            val sysInfos = ArrayList<MeasInfo>()
            for (mi in 0 until nB) {
                if (mi > 0) {
                    keyVotes[mi - 1]?.let { v -> curF = v.entries.maxByOrNull { it.value }!!.key }
                    tsVotes[mi - 1]?.let { v -> curTs = v.entries.maxByOrNull { it.value }!!.key }
                }
                val inf = MeasInfo(); inf.fifths = curF; inf.beats = curTs.first; inf.beatType = curTs.second
                inf.barLen = 96 * curTs.first / curTs.second; inf.len = inf.barLen
                inf.repStart = sd.repStart.getOrElse(mi) { false }; inf.repEnd = sd.repEnd.getOrElse(mi) { false }
                val a = xs[mi]; val b = xs[mi + 1]; val mx = (a + b) / 2
                inf.volta = sd.voltas.firstOrNull { mx >= it.x0 - 0.5 && mx <= it.x1 + 0.5 }?.num ?: 0
                inf.ferm = sd.ferm.any { it.first >= a - 0.5 && it.first < b }
                for (w in sd.words) {
                    if (!(w.x >= a - 0.5 && w.x < b)) continue
                    when (w.kind) {
                        "rit" -> inf.rit = -25; "pocorit" -> inf.rit = -12; "accel" -> inf.rit = 20
                        "tempo" -> if (w.bpm != curTempo) { inf.tempo = w.bpm; curTempo = w.bpm }
                    }
                }
                sysInfos += inf
            }
            // compassos de cada pauta
            val built = Array(n) { arrayOfNulls<BM>(nB) }
            val barEvents = Array(n) { arrayOfNulls<List<Ev>>(nB) }
            for (mi in 0 until nB) {
                val inf = sysInfos[mi]
                for (k in 0 until n) {
                    val st = atSlot[k] ?: continue
                    val a = xs[mi]; val b = xs[mi + 1]
                    val inm = st.events.filter { if (mi == 0) it.x < b else it.x >= a && it.x < b }.sortedBy { it.x }
                    barEvents[k][mi] = inm
                    built[k][mi] = buildMeasure(st, inm, inf.barLen, st.staff.sp, inf.fifths)
                }
                // duração real do compasso: curto só se todas as pautas com música concordam (anacruse, final de seção)
                val real = (0 until n).mapNotNull { k -> built[k][mi]?.takeIf { !it.mrOnly } }
                if (real.isNotEmpty()) {
                    val m = real.maxOf { it.maxSum }
                    if (m in 6 until inf.barLen && real.all { abs(it.maxSum - m) <= 1 }) inf.len = m
                }
            }
            // grava
            for (k in 0 until n) {
                val st = atSlot[k]
                if (st != null && firstClef[k] == ' ') firstClef[k] = st.clef
                if (st != null && curClef[k] == ' ') curClef[k] = st.clef
                for (mi in 0 until nB) {
                    val inf = sysInfos[mi]
                    val acc = Acc()
                    val bm = built[k][mi]
                    if (bm == null) acc.notes += RawNote(null, inf.len, 1, false, false, false, null, 's').also { it.mr = true }
                    else {
                        for ((vi, pair) in bm.voices.withIndex()) {
                            val (vid, list) = pair
                            var sum = bm.sums[vi]
                            for (nn in list) {
                                if (nn.mr) acc.notes += RawNote(null, inf.len, vid, false, false, false, null, 's') else acc.notes += nn
                            }
                            if (list.size == 1 && list[0].mr) sum = inf.len
                            if (sum < inf.len) { acc.notes += RawNote(null, inf.len - sum, vid, false, false, false, null, 's'); if (sum > 0 && !(list.size == 1 && list[0].mr)) acc.doubt = "faltam ${inf.len - sum} divisão(ões) para fechar o compasso; foi completado com pausa" }
                            else if (sum > inf.len + 1) { adjusted++; acc.doubt = "soma de tempos maior que o compasso ($sum/${inf.len})"; if (dbg) System.err.println("BADSUM sys$si bar$mi slot$k v$vid sum=$sum len=${inf.len}") }
                        }
                    }
                    accs[k] += acc
                }
                // mudança de clave
                if (st != null) {
                    if (st.clef != curClef[k] && nB > 0) { accs[k][accs[k].size - nB].clef = st.clef; curClef[k] = st.clef }
                    for ((cx, cc) in st.clefMarks) {
                        var mi = (0 until nB).firstOrNull { cx >= xs[it] - 0.5 && cx < xs[it + 1] } ?: continue
                        if (xs[mi + 1] - cx < 3.5 * st.staff.sp && mi + 1 < nB) mi++
                        if (cc != curClef[k]) { accs[k][accs[k].size - nB + mi].clef = cc; curClef[k] = cc }
                    }
                }
            }
            // cifras → pauta logo abaixo do texto
            for ((hx, hy, h) in sd.harmony) {
                val under = sd.staves.filter { hy >= it.staff.top - 2 * it.staff.sp }.minByOrNull { hy - it.staff.top } ?: continue
                val ui = sd.staves.indexOf(under); val k = slotOf[ui]; if (k < 0) continue
                val mi = (0 until nB).firstOrNull { hx >= xs[it] - 0.01 && hx < xs[it + 1] } ?: continue
                val inm = barEvents[k][mi] ?: continue
                var at = 0.0
                for (e in inm.filter { !it.rest && it.voice == 1 }.sortedBy { it.x }) { if (e.x <= hx + 0.6 * under.staff.sp) at = e.onset else break }
                accs[k][accs[k].size - nB + mi].harm += at.toInt() to h
            }
            // dinâmicas e crescendos
            fun slotNear(y: Double): Int {
                var best = -1; var bd = 1e9
                for ((i, st) in sd.staves.withIndex()) { if (slotOf[i] < 0) continue; val d = abs(y - (st.staff.top + st.staff.bot) / 2); if (d < bd) { bd = d; best = i } }
                return if (best < 0) -1 else slotOf[best]
            }
            fun barOf(x: Double): Int = (0 until nB).firstOrNull { x >= xs[it] - 0.5 && x < xs[it + 1] } ?: (if (x < xs[0]) 0 else nB - 1)
            fun addDyn(x: Double, y: Double, mark: String, end: Boolean = false) {
                val k = slotNear(y); if (k < 0) return
                val mi = barOf(x)
                val evs = barEvents[k][mi] ?: return
                val sp = atSlot[k]!!.staff.sp
                val at = if (end && x >= xs[mi + 1] - 0.5 * sp) sysInfos[mi].len else onsetAt(evs, x, sp, sysInfos[mi].len)
                accs[k][accs[k].size - nB + mi].dyn += at to mark
            }
            for (d in sd.dyns) addDyn(d.x, d.y, d.mark)
            for (hm in sd.hairs) { addDyn(hm.x0, hm.y, if (hm.cresc) "cresc" else "dim"); addDyn(hm.x1, hm.y, "end", true) }
            for (w in sd.words) if ((w.kind == "cresc" || w.kind == "dim") && sd.hairs.none { w.x >= it.x0 - 8 && w.x <= it.x1 + 8 && abs(w.y - it.y) < 40 }) addDyn(w.x, w.y, w.kind)
            infos += sysInfos
            bars += nB
        }
        // divisões
        var g = 24
        for (k in 0 until n) for (ac in accs[k]) for (nn in ac.notes) g = gcd(g, nn.dur)
        for (inf in infos) { g = gcd(g, inf.barLen); g = gcd(g, inf.len) }
        if (g <= 0) g = 1
        var div = 24 / g
        val kf = 4 / gcd(div, 4); div *= kf
        val scale = kf.toDouble() / g
        val score = Score(o.title.takeIf { o.title != "Partitura lida" } ?: title ?: o.title, div, firstF ?: 0, firstTs.first, firstTs.second, scoreTempo)
        val VOICE = Regex("sopr|alto|contralto|tenor|bar[ií]tono|bass|baixo|coro|voz|vocal|choir|voice", RegexOption.IGNORE_CASE)
        for (k in 0 until n) {
            val kind = skel[k].staff.kind
            val grpKey = skel[k].staff.key.substringBeforeLast('/')
            val nst = skel.count { it.staff.key.substringBeforeLast('/') == grpKey }
            val slot = skel[k].staff.slot
            val lab = labels[k]
            val baseName = lab ?: when (kind) { "brace" -> "Piano"; "bracket" -> "Coro"; else -> "Pauta ${k + 1}" }
            val name = if (nst > 1 && lab == null) "$baseName (pauta ${slot + 1})" else baseName
            val inst = if (kind == "bracket" || (lab != null && VOICE.containsMatchIn(lab))) "voice" else "piano"
            val part = Part("P${k + 1}", name, mutableListOf(), if (firstClef[k] == ' ') 'G' else firstClef[k], if (kind == "single") "P${k + 1}" else "G$grpKey", inst)
            var prevF = firstF ?: 0; var prevTs = firstTs
            for ((mi, ac) in accs[k].withIndex()) {
                val inf = infos[mi]
                val me = Measure(mi + 1)
                for (nn in ac.notes) {
                    me.notes += Note(nn.pitch, (nn.dur * scale).roundToInt().coerceAtLeast(1), nn.voice, nn.chord, nn.tieStart, nn.tieStop, nn.lyric, nn.syl)
                    if (nn.pitch != null) notes++
                }
                for ((at, h) in ac.harm) me.harmony += Harmony((at * scale).roundToInt(), h.step, h.alter, h.kind, h.bassStep, h.bassAlter)
                for ((at, mk) in ac.dyn.distinct().sortedBy { it.first }) me.dyn += Dyn((at * scale).roundToInt(), mk)
                if (inf.fifths != prevF) { me.fifths = inf.fifths; prevF = inf.fifths }
                if ((inf.beats to inf.beatType) != prevTs) { me.beats = inf.beats; me.beatType = inf.beatType; prevTs = inf.beats to inf.beatType }
                me.repeatStart = inf.repStart; me.repeatEnd = inf.repEnd; me.volta = inf.volta
                if (inf.ferm) me.mark = "fermata"
                if (k == 0) { me.tempo = inf.tempo; me.rit = inf.rit }
                me.clef = ac.clef
                me.doubt = ac.doubt
                part.measures += me
            }
            score.parts += part
        }
        if (adjusted > 0) warnings += "$adjusted compasso(s) tiveram a duração ajustada (ritmo mais complexo que o leitor entende)."
        val doubtBars = score.parts.flatMap { p -> p.measures.filter { it.doubt != null }.map { it.number } }.distinct().sorted()
        if (doubtBars.isNotEmpty()) warnings += "Leitura incerta nos compassos ${doubtBars.joinToString(", ")}: o ritmo não fechou com a fórmula de compasso. Confira com a partitura original; não invento notas nem altero compassos."
        return OmrResult(score, score.parts.size, systems.size, notes, bars, adjusted, warnings)
    }

    /** Converte os eventos de um compasso em notas (vozes, altura, duração), sem completar com pausas. */
    private fun buildMeasure(st: StaffData, inm: List<Ev>, barLen: Int, sp: Double, fifths: Int): BM {
        val s = st.staff; val mid = (s.bot + s.top) / 2
        val up = inm.filter { !it.rest && it.up == true }; val dn = inm.filter { !it.rest && it.up == false }
        val full = inm.filter { !it.rest && !it.hasStem && it.dur >= barLen * 0.99 }
        val restOver = inm.filter { it.rest && !it.measureRest }.any { r -> inm.any { !it.rest && it !in full && abs(it.x - r.x) < 0.9 * sp } }
        val two = up.any { u -> dn.any { abs(u.x - it.x) < 0.9 * sp } } || restOver
        val others = inm.filter { it !in full }
        fun ym(e: Ev) = e.heads.map { it.y }.average()
        fun fullV(e: Ev): Int { val om = others.filter { !it.rest }.map { ym(it) }.average(); return if (ym(e) < om) 1 else 0 }
        val voices = arrayOf(ArrayList<Ev>(), ArrayList<Ev>())
        var fullFirst = false
        for (e in inm) {
            var v = 0
            if (e in full && others.any { !it.rest }) {
                val om = others.filter { !it.rest }.map { ym(it) }.average()
                v = if (ym(e) < om) 1 else 0; e.full = true; if (v == 0) fullFirst = true
            } else if (two) {
                v = if (e.rest) {
                    val mate = inm.firstOrNull { !it.rest && abs(it.x - e.x) < 0.9 * sp }
                    if (mate != null && mate in full && others.any { !it.rest }) 1 - fullV(mate)
                    else if (mate != null) (if (mate.up == false) 0 else 1) else if (e.y > mid) 0 else 1
                } else if (e.up == false) 1 else 0
            }
            voices[v] += e
        }
        if (fullFirst) { val f = voices[0].filter { it.full }; val r = voices[0].filter { !it.full } + voices[1]; voices[0] = ArrayList(f); voices[1] = ArrayList(r) }
        // armadura + acidentes do compasso
        val keyAlter = HashMap<Char, Int>()
        if (fifths > 0) for (i in 0 until min(7, fifths)) keyAlter[SHARP_ORDER[i]] = 1
        if (fifths < 0) for (i in 0 until min(7, -fifths)) keyAlter[FLAT_ORDER[i]] = -1
        val measAcc = HashMap<Int, Int>()
        val allHeads = inm.flatMap { e -> e.heads.map { it to e } }.sortedBy { it.second.x }
        val pitchOf = HashMap<Head, Pitch>()
        for ((h, _) in allHeads) {
            val base = if (h.clef == 'F') 2 * 7 + 4 else 4 * 7 + 2
            val d = base + h.pos
            val step = STEPS[Math.floorMod(d, 7)]; val oct = Math.floorDiv(d, 7)
            when (h.acc) { "flat" -> measAcc[d] = -1; "sharp" -> measAcc[d] = 1; "natural" -> measAcc[d] = 0 }
            val alter = measAcc[d] ?: (keyAlter[step] ?: 0)
            pitchOf[h] = Pitch(step, alter, oct)
        }
        val outV = ArrayList<Pair<Int, ArrayList<RawNote>>>(); val sums = ArrayList<Int>()
        for ((vi, ev) in voices.withIndex()) {
            if (ev.isEmpty()) continue
            var sum = 0
            val list = ArrayList<RawNote>()
            var acc = 0.0
            for (e in ev.sortedBy { it.x }) {
                e.onset = sum.toDouble()
                val mr = e.rest && e.measureRest && ev.size == 1
                val end = acc + e.dur
                val d = if (mr) barLen else (end.roundToInt() - acc.roundToInt()).coerceAtLeast(if (e.dur > 0.4) 1 else 0)
                acc = end
                if (e.rest) list += RawNote(null, d, vi + 1, false, false, false, null, 's').also { it.mr = mr }
                else {
                    val hs = e.heads.sortedBy { it.pos }
                    for ((i, h) in hs.withIndex()) list += RawNote(pitchOf[h], d, vi + 1, i > 0, h.tie, h.tieStop, if (i == 0) e.lyric else null, e.syl)
                }
                e.voice = vi + 1
                sum += d
            }
            if (System.getProperty("vdbg3") != null && sum != barLen) System.err.println("SUM v$vi sum=$sum bar=$barLen: " + ev.sortedBy { it.x }.joinToString(" ") { "${it.x.toInt()}:${it.dur.toInt()}" + (if (it.rest) "r" else "") + (if (it.up == true) "^" else if (it.up == false) "v" else "") + "h" + it.heads.size })
            outV += (vi + 1) to list; sums += sum
        }
        if (outV.isEmpty()) outV += 1 to arrayListOf(RawNote(null, barLen, 1, false, false, false, null, 's').also { it.mr = true }).also { sums += barLen }
        return BM(outV, sums)
    }
}
