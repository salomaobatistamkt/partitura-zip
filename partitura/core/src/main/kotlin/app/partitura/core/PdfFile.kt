package app.partitura.core

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/** Leitor de PDF mínimo, em Kotlin puro (sem bibliotecas): objetos, xref, fluxos Flate, páginas, fontes e o conteúdo vetorial da página. */
class PdfName(val v: String) { override fun toString() = "/$v" }
class PdfRef(val n: Int, val g: Int)
class PdfStream(val dict: Map<String, Any?>, val raw: ByteArray)
class PdfOp(val v: String)

class PdfLex(val b: ByteArray, var p: Int = 0, private val refs: Boolean = true) {
    fun isWs(c: Int) = c == 32 || c == 10 || c == 13 || c == 9 || c == 12 || c == 0
    private fun isDelim(c: Int) = c == '('.code || c == ')'.code || c == '<'.code || c == '>'.code || c == '['.code || c == ']'.code || c == '{'.code || c == '}'.code || c == '/'.code || c == '%'.code
    fun at(i: Int = p) = if (i in b.indices) b[i].toInt() and 255 else -1
    fun skipWs() {
        while (p < b.size) {
            val c = at()
            if (isWs(c)) p++
            else if (c == '%'.code) { while (p < b.size && at() != 10 && at() != 13) p++ }
            else break
        }
    }
    /** Próximo objeto; [PdfOp] para operadores/palavras-chave; null no fim. */
    fun next(): Any? {
        skipWs()
        if (p >= b.size) return EOF
        val c = at()
        when {
            c == '/'.code -> { p++; return PdfName(readName()) }
            c == '('.code -> return readLiteral()
            c == '<'.code -> {
                if (at(p + 1) == '<'.code) { p += 2; return readDict() }
                return readHex()
            }
            c == '['.code -> { p++; return readArray() }
            c == ']'.code || c == '>'.code || c == ')'.code || c == '{'.code || c == '}'.code -> { p++; return PdfOp(c.toChar().toString()) }
            else -> {
                val s = p
                while (p < b.size && !isWs(at()) && !isDelim(at())) p++
                val t = String(b, s, p - s, Charsets.ISO_8859_1)
                val d = t.toDoubleOrNull()
                if (d != null && (t[0].isDigit() || t[0] == '-' || t[0] == '+' || t[0] == '.')) {
                    if (refs && !t.contains('.') && !t.startsWith("-") && !t.startsWith("+")) {
                        val save = p
                        skipWs(); val s2 = p
                        while (p < b.size && at() in '0'.code..'9'.code) p++
                        if (p > s2) {
                            val g = String(b, s2, p - s2, Charsets.ISO_8859_1).toInt()
                            skipWs()
                            if (at() == 'R'.code && !(at(p + 1) in 'A'.code..'Z'.code || at(p + 1) in 'a'.code..'z'.code)) { p++; return PdfRef(d.toInt(), g) }
                        }
                        p = save
                    }
                    return d
                }
                if (t == "true") return true
                if (t == "false") return false
                if (t == "null") return null
                return PdfOp(t)
            }
        }
    }
    private fun readName(): String {
        val sb = StringBuilder()
        while (p < b.size && !isWs(at()) && !isDelim(at())) {
            var c = at()
            if (c == '#'.code && p + 2 < b.size) { val h = String(b, p + 1, 2, Charsets.ISO_8859_1).toIntOrNull(16); if (h != null) { c = h; p += 2 } }
            sb.append(c.toChar()); p++
        }
        return sb.toString()
    }
    private fun readLiteral(): ByteArray {
        p++; val o = ByteArrayOutputStream(); var depth = 1
        while (p < b.size) {
            var c = at(); p++
            if (c == '\\'.code) {
                val d = at(); p++
                when (d) {
                    'n'.code -> o.write(10); 'r'.code -> o.write(13); 't'.code -> o.write(9); 'b'.code -> o.write(8); 'f'.code -> o.write(12)
                    '('.code, ')'.code, '\\'.code -> o.write(d)
                    13 -> { if (at() == 10) p++ }
                    10 -> {}
                    in '0'.code..'7'.code -> { var v = d - '0'.code; var n = 1; while (n < 3 && at() in '0'.code..'7'.code) { v = v * 8 + (at() - '0'.code); p++; n++ }; o.write(v and 255) }
                    else -> o.write(d)
                }
            } else if (c == '('.code) { depth++; o.write(c) }
            else if (c == ')'.code) { depth--; if (depth == 0) break; o.write(c) }
            else o.write(c)
        }
        return o.toByteArray()
    }
    private fun readHex(): ByteArray {
        p++; val o = ByteArrayOutputStream(); var hi = -1
        while (p < b.size) {
            val c = at(); p++
            if (c == '>'.code) break
            val v = Character.digit(c, 16); if (v < 0) continue
            if (hi < 0) hi = v else { o.write(hi * 16 + v); hi = -1 }
        }
        if (hi >= 0) o.write(hi * 16)
        return o.toByteArray()
    }
    private fun readArray(): List<Any?> {
        val l = ArrayList<Any?>()
        while (true) {
            val o = next()
            if (o === EOF) break
            if (o is PdfOp && o.v == "]") break
            l += o
        }
        return l
    }
    private fun readDict(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        while (true) {
            val k = next()
            if (k === EOF) break
            if (k is PdfOp && k.v == ">") { if (at() == '>'.code) p++; break }
            if (k !is PdfName) continue
            val v = next()
            if (v is PdfOp && v.v == ">") { if (at() == '>'.code) p++; break }
            m[k.v] = v
        }
        return m
    }
    companion object { val EOF = Any() }
}

class PdfFile(private val b: ByteArray) {
    private val xref = HashMap<Int, Long>()
    private val inStm = HashMap<Int, Pair<Int, Int>>()
    private val cache = HashMap<Int, Any?>()
    private val objStmCache = HashMap<Int, Map<Int, Any?>>()
    var trailer: Map<String, Any?> = emptyMap()

    init {
        try { readXrefChain() } catch (e: Exception) { xref.clear(); inStm.clear() }
        if (root() == null) rebuild()
        if (root() == null) throw IllegalStateException("PDF inválido ou protegido.")
    }

    private fun root(): Map<String, Any?>? = try { dict(trailer["Root"]) } catch (e: Exception) { null }

    // ---------- resolução ----------
    fun resolve(o: Any?): Any? {
        var x = o; var n = 0
        while (x is PdfRef && n++ < 20) x = getObj(x.n)
        return x
    }
    @Suppress("UNCHECKED_CAST")
    fun dict(o: Any?): Map<String, Any?>? = when (val r = resolve(o)) { is Map<*, *> -> r as Map<String, Any?>; is PdfStream -> r.dict; else -> null }
    @Suppress("UNCHECKED_CAST")
    fun list(o: Any?): List<Any?>? = resolve(o) as? List<Any?>
    fun num(o: Any?): Double? = resolve(o) as? Double
    fun name(o: Any?): String? = (resolve(o) as? PdfName)?.v

    fun getObj(n: Int): Any? {
        if (cache.containsKey(n)) return cache[n]
        var res: Any? = null
        try {
            val off = xref[n]
            if (off != null) {
                val lx = PdfLex(b, off.toInt())
                lx.next(); lx.next(); lx.next() // n g obj
                res = readBody(lx)
            } else {
                val s = inStm[n]
                if (s != null) res = objStm(s.first)[n]
            }
        } catch (e: Exception) { res = null }
        cache[n] = res
        return res
    }

    private fun readBody(lx: PdfLex): Any? {
        val o = lx.next()
        if (o is Map<*, *>) {
            val save = lx.p
            lx.skipWs()
            if (lx.b.size > lx.p + 5 && String(lx.b, lx.p, 6, Charsets.ISO_8859_1) == "stream") {
                lx.p += 6
                if (lx.at() == 13) lx.p++
                if (lx.at() == 10) lx.p++
                val start = lx.p
                @Suppress("UNCHECKED_CAST") val d = o as Map<String, Any?>
                var len = (d["Length"] as? Double)?.toInt() ?: (d["Length"] as? PdfRef)?.let { (getObj(it.n) as? Double)?.toInt() } ?: -1
                if (len < 0 || start + len > b.size || !endsWithEnd(start + len)) len = findEnd(start) - start
                return PdfStream(d, b.copyOfRange(start, start + len.coerceAtLeast(0)))
            }
            lx.p = save
        }
        return o
    }
    private fun endsWithEnd(i: Int): Boolean {
        var j = i
        while (j < b.size && (b[j].toInt() == 10 || b[j].toInt() == 13 || b[j].toInt() == 32)) j++
        return j + 9 <= b.size && String(b, j, 9, Charsets.ISO_8859_1) == "endstream"
    }
    private fun findEnd(start: Int): Int {
        val key = "endstream".toByteArray()
        var i = start
        outer@ while (i + key.size <= b.size) {
            for (k in key.indices) if (b[i + k] != key[k]) { i++; continue@outer }
            var e = i
            if (e > start && b[e - 1].toInt() == 10) e--
            if (e > start && b[e - 1].toInt() == 13) e--
            return e
        }
        return b.size
    }

    private fun objStm(n: Int): Map<Int, Any?> = objStmCache.getOrPut(n) {
        val st = getObj(n) as? PdfStream ?: return@getOrPut emptyMap()
        val data = decode(st) ?: return@getOrPut emptyMap()
        val cnt = (st.dict["N"] as Double).toInt(); val first = (st.dict["First"] as Double).toInt()
        val lx = PdfLex(data, 0, false)
        val nums = IntArray(cnt); val offs = IntArray(cnt)
        for (i in 0 until cnt) { nums[i] = (lx.next() as Double).toInt(); offs[i] = (lx.next() as Double).toInt() }
        val out = HashMap<Int, Any?>()
        for (i in 0 until cnt) { val l2 = PdfLex(data, first + offs[i]); out[nums[i]] = l2.next() }
        out
    }

    // ---------- xref ----------
    private fun readXrefChain() {
        var tail = String(b, maxOf(0, b.size - 1100), minOf(1100, b.size), Charsets.ISO_8859_1)
        val i = tail.lastIndexOf("startxref")
        if (i < 0) throw IllegalStateException("sem startxref")
        val lx0 = PdfLex(b, maxOf(0, b.size - 1100) + i + 9)
        var off = (lx0.next() as Double).toLong()
        val seen = HashSet<Long>()
        var first = true
        while (off >= 0 && seen.add(off)) {
            val lx = PdfLex(b, off.toInt())
            val t = lx.next()
            var prev = -1L
            if (t is PdfOp && t.v == "xref") {
                while (true) {
                    val a = lx.next()
                    if (a is PdfOp && a.v == "trailer") break
                    val start = (a as Double).toInt(); val cnt = (lx.next() as Double).toInt()
                    for (k in 0 until cnt) {
                        val o = (lx.next() as Double).toLong(); lx.next(); val f = lx.next() as PdfOp
                        if (f.v == "n" && !xref.containsKey(start + k) && !inStm.containsKey(start + k)) xref[start + k] = o
                    }
                }
                val tr = lx.next() as Map<*, *>
                @Suppress("UNCHECKED_CAST") val td = tr as Map<String, Any?>
                if (first) trailer = td
                (td["XRefStm"] as? Double)?.let { try { readXrefStream(it.toInt()) } catch (e: Exception) {} }
                prev = (td["Prev"] as? Double)?.toLong() ?: -1L
            } else {
                // objeto xref stream: "n g obj"
                lx.next(); lx.next()
                val o = readXrefStream(off.toInt())
                if (first) trailer = o
                prev = (o["Prev"] as? Double)?.toLong() ?: -1L
            }
            first = false
            off = prev
        }
    }

    private fun readXrefStream(off: Int): Map<String, Any?> {
        val lx = PdfLex(b, off); lx.next(); lx.next(); lx.next()
        val st = readBody(lx) as PdfStream
        val data = decode(st) ?: throw IllegalStateException("xref")
        @Suppress("UNCHECKED_CAST") val w = (st.dict["W"] as List<Any?>).map { (it as Double).toInt() }
        val size = (st.dict["Size"] as Double).toInt()
        @Suppress("UNCHECKED_CAST") val index = (st.dict["Index"] as? List<Any?>)?.map { (it as Double).toInt() } ?: listOf(0, size)
        var p = 0
        fun rd(n: Int): Long { var v = 0L; for (i in 0 until n) { v = (v shl 8) or (data[p++].toLong() and 255) }; return v }
        var k = 0
        while (k + 1 < index.size) {
            for (i in 0 until index[k + 1]) {
                if (p >= data.size) break
                val t = if (w[0] == 0) 1L else rd(w[0]); val f2 = rd(w[1]); val f3 = rd(w[2])
                val num = index[k] + i
                if (t == 1L && !xref.containsKey(num) && !inStm.containsKey(num)) xref[num] = f2
                else if (t == 2L && !xref.containsKey(num) && !inStm.containsKey(num)) inStm[num] = f2.toInt() to f3.toInt()
            }
            k += 2
        }
        return st.dict
    }

    /** Reconstrói a tabela varrendo o arquivo (PDF com xref quebrada). */
    private fun rebuild() {
        xref.clear(); inStm.clear(); cache.clear(); objStmCache.clear()
        val s = String(b, Charsets.ISO_8859_1)
        val re = Regex("(?m)(?:^|[\\s>])(\\d+)\\s+(\\d+)\\s+obj\\b")
        for (m in re.findAll(s)) xref[m.groupValues[1].toInt()] = (m.range.last + 1 - m.value.trimStart().length).toLong().coerceAtLeast(0)
        var tr: Map<String, Any?>? = null
        var i = s.lastIndexOf("trailer")
        while (i >= 0 && tr == null) {
            try { val lx = PdfLex(b, i + 7); val d = lx.next(); if (d is Map<*, *> && d.containsKey("Root")) { @Suppress("UNCHECKED_CAST") val dm = d as Map<String, Any?>; tr = dm } } catch (e: Exception) {}
            i = s.lastIndexOf("trailer", i - 1)
        }
        if (tr == null) {
            for ((n, _) in xref) {
                val o = getObj(n)
                if (o is Map<*, *> && (o["Type"] as? PdfName)?.v == "Catalog") { tr = mapOf("Root" to PdfRef(n, 0)); break }
                if (o is PdfStream && (o.dict["Type"] as? PdfName)?.v == "XRef" && o.dict.containsKey("Root")) { tr = o.dict; break }
            }
        }
        // objetos dentro de ObjStm
        for ((n, _) in xref.entries.toList()) {
            val o = getObj(n)
            if (o is PdfStream && (o.dict["Type"] as? PdfName)?.v == "ObjStm") {
                try {
                    val data = decode(o) ?: continue
                    val cnt = (o.dict["N"] as Double).toInt(); val lx = PdfLex(data, 0, false)
                    for (k in 0 until cnt) { val on = (lx.next() as Double).toInt(); lx.next(); if (!xref.containsKey(on)) inStm[on] = n to k }
                } catch (e: Exception) {}
            }
        }
        trailer = tr ?: emptyMap()
    }

    // ---------- filtros ----------
    fun decode(st: PdfStream): ByteArray? {
        var data = st.raw
        val f = resolve(st.dict["Filter"])
        val filters = when (f) { is PdfName -> listOf(f.v); is List<*> -> f.mapNotNull { name(it) }; else -> emptyList() }
        val parms = resolve(st.dict["DecodeParms"] ?: st.dict["DP"])
        for ((i, fl) in filters.withIndex()) {
            val dp = when (parms) { is List<*> -> dict(parms.getOrNull(i)); is Map<*, *> -> dict(parms); else -> null }
            data = when (fl) {
                "FlateDecode", "Fl" -> predictor(inflate(data) ?: return null, dp)
                "ASCIIHexDecode", "AHx" -> PdfLex(("<" + String(data, Charsets.ISO_8859_1).trim().trimEnd('>') + ">").toByteArray()).let { (it.next() as ByteArray) }
                "ASCII85Decode", "A85" -> ascii85(data)
                else -> return null
            }
        }
        return data
    }
    private fun inflate(d: ByteArray): ByteArray? {
        val inf = Inflater(); inf.setInput(d)
        val o = ByteArrayOutputStream(d.size * 4); val buf = ByteArray(16384)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                o.write(buf, 0, n)
            }
        } catch (e: Exception) { if (o.size() == 0) return null } finally { inf.end() }
        return o.toByteArray()
    }
    private fun predictor(d: ByteArray, dp: Map<String, Any?>?): ByteArray {
        val pr = (dp?.get("Predictor") as? Double)?.toInt() ?: 1
        if (pr < 10) return d
        val cols = (dp?.get("Columns") as? Double)?.toInt() ?: 1
        val colors = (dp?.get("Colors") as? Double)?.toInt() ?: 1
        val bpc = (dp?.get("BitsPerComponent") as? Double)?.toInt() ?: 8
        val bpp = maxOf(1, colors * bpc / 8); val rowLen = (cols * colors * bpc + 7) / 8
        val rows = d.size / (rowLen + 1)
        val out = ByteArray(rows * rowLen)
        for (r in 0 until rows) {
            val ft = d[r * (rowLen + 1)].toInt()
            for (i in 0 until rowLen) {
                val x = d[r * (rowLen + 1) + 1 + i].toInt() and 255
                val a = if (i >= bpp) out[r * rowLen + i - bpp].toInt() and 255 else 0
                val up = if (r > 0) out[(r - 1) * rowLen + i].toInt() and 255 else 0
                val c = if (r > 0 && i >= bpp) out[(r - 1) * rowLen + i - bpp].toInt() and 255 else 0
                val v = when (ft) {
                    0 -> x; 1 -> x + a; 2 -> x + up; 3 -> x + (a + up) / 2
                    4 -> { val pp = a + up - c; val pa = Math.abs(pp - a); val pb = Math.abs(pp - up); val pc = Math.abs(pp - c); x + (if (pa <= pb && pa <= pc) a else if (pb <= pc) up else c) }
                    else -> x
                }
                out[r * rowLen + i] = v.toByte()
            }
        }
        return out
    }
    private fun ascii85(d: ByteArray): ByteArray {
        val o = ByteArrayOutputStream(); var n = 0; var v = 0L
        for (x in d) {
            val c = x.toInt() and 255
            if (c == '~'.code) break
            if (c <= 32) continue
            if (c == 'z'.code && n == 0) { o.write(0); o.write(0); o.write(0); o.write(0); continue }
            v = v * 85 + (c - 33); n++
            if (n == 5) { for (k in 3 downTo 0) o.write(((v shr (8 * k)) and 255).toInt()); n = 0; v = 0 }
        }
        if (n > 1) { for (k in n until 5) v = v * 85 + 84; for (k in 3 downTo (5 - n)) o.write(((v shr (8 * k)) and 255).toInt()) }
        return o.toByteArray()
    }

    // ---------- páginas ----------
    class Page(val dict: Map<String, Any?>, val resources: Map<String, Any?>?)

    fun pages(): List<Page> {
        val out = ArrayList<Page>()
        fun walk(node: Any?, res: Map<String, Any?>?, depth: Int) {
            if (depth > 30) return
            val d = dict(node) ?: return
            val r = dict(d["Resources"]) ?: res
            val kids = list(d["Kids"])
            if (kids != null && name(d["Type"]) != "Page") for (k in kids) walk(k, r, depth + 1)
            else out += Page(d, r)
        }
        val pagesRoot = dict(root()?.get("Pages"))
        walk(pagesRoot, null, 0)
        return out
    }

    fun contentBytes(p: Page): ByteArray {
        val c = resolve(p.dict["Contents"])
        val o = ByteArrayOutputStream()
        fun add(x: Any?) { val s = resolve(x) as? PdfStream ?: return; decode(s)?.let { o.write(it); o.write(10) } }
        if (c is List<*>) c.forEach { add(it) } else add(c)
        return o.toByteArray()
    }
}

// ============================ conteúdo vetorial da página ============================

class PdfGlyph(val font: String, val ch: Int, val x: Double, val y: Double, val size: Double, val ysize: Double, val adv: Double)
class PdfSeg(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val w: Double)
class PdfFill(val pts: List<DoubleArray>, val curved: Boolean)
class PdfPageData(val glyphs: List<PdfGlyph>, val segs: List<PdfSeg>, val fills: List<PdfFill>, val width: Double, val height: Double)

private class FontInfo(val name: String, val twoByte: Boolean, val toUni: Map<Int, Int>, val widths: (Int) -> Double)

object PdfContent {
    private fun parseCMap(data: ByteArray): Map<Int, Int> {
        val s = String(data, Charsets.ISO_8859_1)
        val m = HashMap<Int, Int>()
        fun hex(h: String): Int { val t = h.trim(); return if (t.length >= 4) t.substring(0, 4).toInt(16) else t.toInt(16) }
        for (blk in Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(s))
            for (mm in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(blk.groupValues[1])) m[mm.groupValues[1].toInt(16)] = hex(mm.groupValues[2])
        for (blk in Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(s))
            for (mm in Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(\\[[^\\]]*\\]|<[0-9A-Fa-f]+>)").findAll(blk.groupValues[1])) {
                val a = mm.groupValues[1].toInt(16); val e = mm.groupValues[2].toInt(16); val t = mm.groupValues[3]
                if (t.startsWith("[")) {
                    var i = 0
                    for (h in Regex("<([0-9A-Fa-f]+)>").findAll(t)) { m[a + i] = hex(h.groupValues[1]); i++ }
                } else { val v = hex(t.substring(1, t.length - 1)); for (i in 0..(e - a).coerceAtMost(70000)) m[a + i] = v + i }
            }
        return m
    }

    private fun fontInfo(pdf: PdfFile, fd: Map<String, Any?>): FontInfo {
        var base = pdf.name(fd["BaseFont"]) ?: "?"
        if (base.length > 7 && base[6] == '+') base = base.substring(7)
        val sub = pdf.name(fd["Subtype"])
        val toUni = (pdf.resolve(fd["ToUnicode"]) as? PdfStream)?.let { pdf.decode(it)?.let { d -> parseCMap(d) } } ?: emptyMap()
        if (sub == "Type0") {
            val df = pdf.dict(pdf.list(fd["DescendantFonts"])?.firstOrNull())
            val dw = pdf.num(df?.get("DW")) ?: 1000.0
            val w = HashMap<Int, Double>()
            val wa = pdf.list(df?.get("W"))
            if (wa != null) {
                var i = 0
                while (i < wa.size) {
                    val c = pdf.num(wa[i])?.toInt() ?: break
                    val nx = pdf.resolve(wa.getOrNull(i + 1))
                    if (nx is List<*>) { nx.forEachIndexed { k, v -> pdf.num(v)?.let { w[c + k] = it } }; i += 2 }
                    else { val c2 = (nx as? Double)?.toInt() ?: break; val ww = pdf.num(wa.getOrNull(i + 2)) ?: 0.0; for (k in c..minOf(c2, c + 70000)) w[k] = ww; i += 3 }
                }
            }
            return FontInfo(base, true, toUni) { w[it] ?: dw }
        }
        val first = pdf.num(fd["FirstChar"])?.toInt() ?: 0
        val ws = pdf.list(fd["Widths"])?.map { pdf.num(it) ?: 0.0 }
        val mw = pdf.num(pdf.dict(fd["FontDescriptor"])?.get("MissingWidth")) ?: 0.0
        return FontInfo(base, false, toUni) { c -> ws?.getOrNull(c - first) ?: mw }
    }

    fun read(pdf: PdfFile, page: PdfFile.Page): PdfPageData {
        val glyphs = ArrayList<PdfGlyph>(); val segs = ArrayList<PdfSeg>(); val fills = ArrayList<PdfFill>()
        val fontCache = HashMap<Map<String, Any?>, FontInfo>()
        val mb = pdf.list(page.dict["MediaBox"])?.map { pdf.num(it) ?: 0.0 }
        val pw = if (mb != null && mb.size == 4) mb[2] - mb[0] else 595.0
        val ph = if (mb != null && mb.size == 4) mb[3] - mb[1] else 842.0

        class GS(var ctm: DoubleArray, var lw: Double, var font: FontInfo?, var fs: Double, var tc: Double, var tw: Double, var hs: Double, var rise: Double) {
            fun copy() = GS(ctm.copyOf(), lw, font, fs, tc, tw, hs, rise)
        }
        fun mul(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
            a[0] * b[0] + a[1] * b[2], a[0] * b[1] + a[1] * b[3],
            a[2] * b[0] + a[3] * b[2], a[2] * b[1] + a[3] * b[3],
            a[4] * b[0] + a[5] * b[2] + b[4], a[4] * b[1] + a[5] * b[3] + b[5])

        fun run(content: ByteArray, resources: Map<String, Any?>?, startCtm: DoubleArray, depth: Int) {
            val fonts = pdf.dict(resources?.get("Font"))
            val xobjs = pdf.dict(resources?.get("XObject"))
            var gs = GS(startCtm, 1.0, null, 0.0, 0.0, 0.0, 1.0, 0.0)
            val stack = ArrayList<GS>()
            var tm = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0); var tlm = tm.copyOf(); var leading = 0.0
            // caminho: lista de subcaminhos; cada um é lista de pontos finais com marca de curva
            var sub = ArrayList<DoubleArray>(); val subs = ArrayList<ArrayList<DoubleArray>>(); var hasCurve = false
            var cx = 0.0; var cy = 0.0
            val ops = ArrayList<Any?>()
            val lx = PdfLex(content, 0, false)

            fun tx(x: Double, y: Double) = doubleArrayOf(x * gs.ctm[0] + y * gs.ctm[2] + gs.ctm[4], x * gs.ctm[1] + y * gs.ctm[3] + gs.ctm[5])
            fun lwScale() = Math.sqrt(Math.abs(gs.ctm[0] * gs.ctm[3] - gs.ctm[1] * gs.ctm[2]))
            fun endSub() { if (sub.isNotEmpty()) { subs += sub; sub = ArrayList() } }
            fun n(i: Int) = (ops.getOrNull(i) as? Double) ?: 0.0
            fun show(bytes: ByteArray) {
                val f = gs.font ?: return
                var i = 0
                while (i < bytes.size) {
                    val code: Int
                    if (f.twoByte) { if (i + 1 >= bytes.size) break; code = ((bytes[i].toInt() and 255) shl 8) or (bytes[i + 1].toInt() and 255); i += 2 }
                    else { code = bytes[i].toInt() and 255; i++ }
                    val w = f.widths(code) / 1000.0
                    val trm = mul(doubleArrayOf(gs.fs * gs.hs, 0.0, 0.0, gs.fs, 0.0, gs.rise), mul(tm, gs.ctm))
                    val size = Math.sqrt(trm[0] * trm[0] + trm[1] * trm[1]); val ys = Math.sqrt(trm[2] * trm[2] + trm[3] * trm[3])
                    glyphs += PdfGlyph(f.name, f.toUni[code] ?: code, trm[4], trm[5], size, ys, w * size)
                    var t = (w * gs.fs + gs.tc + (if (!f.twoByte && code == 32) gs.tw else 0.0)) * gs.hs
                    tm = mul(doubleArrayOf(1.0, 0.0, 0.0, 1.0, t, 0.0), tm)
                }
            }
            fun paint(stroke: Boolean, fill: Boolean) {
                endSub()
                if (stroke) {
                    val w = gs.lw * lwScale()
                    for (s in subs) {
                        for (k in 0 until s.size - 1) if (s[k + 1].size == 2 && s[k].size >= 2) segs += PdfSeg(s[k][0], s[k][1], s[k + 1][0], s[k + 1][1], w)
                        if (s.any { it.size == 3 }) fills += PdfFill(s.map { doubleArrayOf(it[0], it[1]) }, true)
                    }
                }
                if (fill) {
                    if (subs.size == 1 && subs[0].size in 4..5 && !subs[0].any { it.size == 3 }) {
                        val p = subs[0]; val xs = p.map { it[0] }; val ys = p.map { it[1] }
                        val w = xs.max() - xs.min(); val h = ys.max() - ys.min()
                        val axis = p.size >= 4 && (Math.abs(p[0][0] - p[1][0]) < 0.01 || Math.abs(p[0][1] - p[1][1]) < 0.01)
                        if (axis && Math.min(w, h) <= 1.3 && Math.max(w, h) > 2.0) {
                            if (w >= h) { val y = (ys.max() + ys.min()) / 2; segs += PdfSeg(xs.min(), y, xs.max(), y, h) }
                            else { val x = (xs.max() + xs.min()) / 2; segs += PdfSeg(x, ys.min(), x, ys.max(), w) }
                            subs.clear(); return
                        }
                    }
                    val pts = ArrayList<DoubleArray>(); var curved = false
                    for (s in subs) for (q in s) { pts += doubleArrayOf(q[0], q[1]); if (q.size == 3) curved = true }
                    if (pts.size >= 3) fills += PdfFill(pts, curved)
                }
                subs.clear()
            }

            while (true) {
                val o = lx.next()
                if (o === PdfLex.EOF) break
                if (o !is PdfOp) { ops += o; continue }
                when (o.v) {
                    "q" -> stack += gs.copy()
                    "Q" -> if (stack.isNotEmpty()) gs = stack.removeAt(stack.size - 1)
                    "cm" -> gs.ctm = mul(doubleArrayOf(n(0), n(1), n(2), n(3), n(4), n(5)), gs.ctm)
                    "w" -> gs.lw = n(0)
                    "BT" -> { tm = doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0); tlm = tm.copyOf() }
                    "Tf" -> { val fn = (ops.getOrNull(0) as? PdfName)?.v; gs.fs = n(1)
                        val fdict = fn?.let { pdf.dict(fonts?.get(it)) }
                        gs.font = fdict?.let { fontCache.getOrPut(it) { fontInfo(pdf, it) } } }
                    "Tc" -> gs.tc = n(0)
                    "Tw" -> gs.tw = n(0)
                    "Tz" -> gs.hs = n(0) / 100.0
                    "TL" -> leading = n(0)
                    "Ts" -> gs.rise = n(0)
                    "Tm" -> { tm = doubleArrayOf(n(0), n(1), n(2), n(3), n(4), n(5)); tlm = tm.copyOf() }
                    "Td" -> { tlm = mul(doubleArrayOf(1.0, 0.0, 0.0, 1.0, n(0), n(1)), tlm); tm = tlm.copyOf() }
                    "TD" -> { leading = -n(1); tlm = mul(doubleArrayOf(1.0, 0.0, 0.0, 1.0, n(0), n(1)), tlm); tm = tlm.copyOf() }
                    "T*" -> { tlm = mul(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, -leading), tlm); tm = tlm.copyOf() }
                    "Tj" -> (ops.getOrNull(0) as? ByteArray)?.let { show(it) }
                    "'" -> { tlm = mul(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, -leading), tlm); tm = tlm.copyOf(); (ops.getOrNull(0) as? ByteArray)?.let { show(it) } }
                    "\"" -> { gs.tw = n(0); gs.tc = n(1); tlm = mul(doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, -leading), tlm); tm = tlm.copyOf(); (ops.getOrNull(2) as? ByteArray)?.let { show(it) } }
                    "TJ" -> (ops.getOrNull(0) as? List<*>)?.forEach { e ->
                        if (e is ByteArray) show(e)
                        else if (e is Double) tm = mul(doubleArrayOf(1.0, 0.0, 0.0, 1.0, -e / 1000.0 * gs.fs * gs.hs, 0.0), tm)
                    }
                    "m" -> { endSub(); val p = tx(n(0), n(1)); sub.add(p); cx = n(0); cy = n(1) }
                    "l" -> { val p = tx(n(0), n(1)); if (sub.isEmpty()) sub.add(p) else sub.add(p); cx = n(0); cy = n(1) }
                    "c" -> { val p = tx(n(4), n(5)); sub.add(doubleArrayOf(p[0], p[1], 1.0)); cx = n(4); cy = n(5) }
                    "v" -> { val p = tx(n(2), n(3)); sub.add(doubleArrayOf(p[0], p[1], 1.0)); cx = n(2); cy = n(3) }
                    "y" -> { val p = tx(n(2), n(3)); sub.add(doubleArrayOf(p[0], p[1], 1.0)); cx = n(2); cy = n(3) }
                    "re" -> { endSub(); val x = n(0); val y = n(1); val w = n(2); val h = n(3)
                        sub.add(tx(x, y)); sub.add(tx(x + w, y)); sub.add(tx(x + w, y + h)); sub.add(tx(x, y + h)); sub.add(tx(x, y)); endSub() }
                    "h" -> { if (sub.isNotEmpty()) sub.add(sub[0].copyOf(2)) }
                    "S" -> paint(true, false)
                    "s" -> { if (sub.isNotEmpty()) sub.add(sub[0].copyOf(2)); paint(true, false) }
                    "f", "F", "f*" -> paint(false, true)
                    "B", "B*" -> paint(true, true)
                    "b", "b*" -> { if (sub.isNotEmpty()) sub.add(sub[0].copyOf(2)); paint(true, true) }
                    "n" -> { endSub(); subs.clear() }
                    "Do" -> {
                        val nm = (ops.getOrNull(0) as? PdfName)?.v
                        val xo = nm?.let { pdf.resolve(xobjs?.get(it)) } as? PdfStream
                        if (xo != null && depth < 8 && pdf.name(xo.dict["Subtype"]) == "Form") {
                            val mtx = pdf.list(xo.dict["Matrix"])?.map { pdf.num(it) ?: 0.0 }
                            val m = if (mtx != null && mtx.size == 6) doubleArrayOf(mtx[0], mtx[1], mtx[2], mtx[3], mtx[4], mtx[5]) else doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)
                            val data = pdf.decode(xo)
                            if (data != null) run(data, pdf.dict(xo.dict["Resources"]) ?: resources, mul(m, gs.ctm), depth + 1)
                        }
                    }
                    "BI" -> { // imagem inline: pula até EI
                        var i = lx.p
                        while (i + 2 < content.size && !(content[i].toInt() == 'E'.code && content[i + 1].toInt() == 'I'.code && lx.isWs(content[i - 1].toInt() and 255) && (i + 2 >= content.size || lx.isWs(content[i + 2].toInt() and 255)))) i++
                        lx.p = minOf(content.size, i + 2)
                    }
                }
                ops.clear()
            }
        }
        val data = pdf.contentBytes(page)
        run(data, page.resources, doubleArrayOf(1.0, 0.0, 0.0, 1.0, 0.0, 0.0), 0)
        return PdfPageData(glyphs, segs, fills, pw, ph)
    }
}
