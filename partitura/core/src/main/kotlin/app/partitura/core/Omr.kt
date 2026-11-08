package app.partitura.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Imagem binária (true = tinta). */
class OmrImage(val w: Int, val h: Int, val ink: BooleanArray) {
    companion object {
        /** Tons de cinza 0..255 → binária por Otsu. */
        fun fromGray(g: IntArray, w: Int, h: Int): OmrImage {
            val hist = IntArray(256)
            for (v in g) hist[v.coerceIn(0, 255)]++
            val total = g.size.toDouble()
            var sum = 0.0
            for (i in 0..255) sum += i * hist[i].toDouble()
            var wB = 0.0; var sB = 0.0; var best = -1.0; var th = 128
            for (t in 0..255) {
                wB += hist[t]; if (wB == 0.0) continue
                val wF = total - wB; if (wF == 0.0) break
                sB += t * hist[t].toDouble()
                val mB = sB / wB; val mF = (sum - sB) / wF
                val v = wB * wF * (mB - mF) * (mB - mF)
                if (v > best) { best = v; th = t }
            }
            return OmrImage(w, h, BooleanArray(w * h) { g[it] <= th })
        }

        /** Para fotos com luz irregular: limiar local (média da vizinhança). */
        fun fromGrayAdaptive(g: IntArray, w: Int, h: Int): OmrImage {
            val win = max(15, (w / 24) or 1)
            val integ = LongArray((w + 1) * (h + 1))
            for (y in 0 until h) {
                var row = 0L
                for (x in 0 until w) { row += g[y * w + x]; integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row }
            }
            val ink = BooleanArray(w * h)
            val r = win / 2
            for (y in 0 until h) {
                val y0 = max(0, y - r); val y1 = min(h, y + r + 1)
                for (x in 0 until w) {
                    val x0 = max(0, x - r); val x1 = min(w, x + r + 1)
                    val sum = integ[y1 * (w + 1) + x1] - integ[y0 * (w + 1) + x1] - integ[y1 * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
                    val mean = sum.toDouble() / ((x1 - x0) * (y1 - y0))
                    ink[y * w + x] = g[y * w + x] < mean * 0.82 && g[y * w + x] < 200
                }
            }
            return OmrImage(w, h, ink)
        }
    }
}

class OmrOptions(
    val title: String = "Partitura lida",
    /** compasso forçado; null = deduzir da soma dos compassos */
    val beats: Int? = null, val beatType: Int = 4,
    val tempo: Int = 80,
)

class OmrResult(val score: Score, val staves: Int, val systems: Int, val notes: Int, val bars: Int, val adjustedBars: Int, val warnings: List<String>)

/**
 * Leitor óptico de partitura, 100% local. Entende partituras impressas limpas (PDF digital ou imagem nítida):
 * pautas, claves de Sol/Fá, armadura, notas (cabeça, haste, bandeirola, barra, ponto), acordes, pausas, acidentes e barras de compasso.
 * Não lê: fotos tortas, tercinas, ligaduras, letras, dinâmicas, claves de Dó.
 */
object Omr {
    var debug = false
    class Staff(val ys: DoubleArray, val x0: Int, val x1: Int, val thick: Int) {
        val space get() = (ys[4] - ys[0]) / 4
        val top get() = ys[0]
        val bottom get() = ys[4]
    }

    private class Comps(val label: IntArray, val n: Int, val minx: IntArray, val miny: IntArray, val maxx: IntArray, val maxy: IntArray, val area: IntArray)

    private class Head(val x: Double, val y: Double, val filled: Boolean) {
        var stemX = Double.NaN; var up = true; var tip = Double.NaN
        var flags = 0; var dots = 0; var acc: Int? = null
        var hasStem = false
        var whole = false
        var score = 0.0
        var comp = -1
    }

    private class Item(val x: Double, val kind: Int, val dur: Int, val notes: List<IntArray> = emptyList())   // kind 0 acorde, 1 pausa, 2 barra; notes: [posição na pauta, acidente (99 = nenhum)]

    // ---------- componentes conexos (8 vizinhos) ----------
    private fun components(ink: BooleanArray, w: Int, h: Int): Comps {
        val label = IntArray(w * h)
        val minx = ArrayList<Int>(); val miny = ArrayList<Int>(); val maxx = ArrayList<Int>(); val maxy = ArrayList<Int>(); val area = ArrayList<Int>()
        minx.add(0); miny.add(0); maxx.add(0); maxy.add(0); area.add(0)
        var n = 0
        val stack = IntArray(w * h / 2 + 16).let { it }
        var stk = stack
        for (s in 0 until w * h) {
            if (!ink[s] || label[s] != 0) continue
            n++
            var sp = 0
            if (sp >= stk.size) stk = stk.copyOf(stk.size * 2)
            stk[sp++] = s; label[s] = n
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0; var a = 0
            while (sp > 0) {
                val p = stk[--sp]
                val x = p % w; val y = p / w
                a++
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (dy in -1..1) {
                    val yy = y + dy; if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx; if (xx < 0 || xx >= w) continue
                        val q = yy * w + xx
                        if (ink[q] && label[q] == 0) {
                            label[q] = n
                            if (sp >= stk.size) stk = stk.copyOf(stk.size * 2)
                            stk[sp++] = q
                        }
                    }
                }
            }
            minx.add(x0); miny.add(y0); maxx.add(x1); maxy.add(y1); area.add(a)
        }
        return Comps(label, n, minx.toIntArray(), miny.toIntArray(), maxx.toIntArray(), maxy.toIntArray(), area.toIntArray())
    }

    // ---------- pautas ----------
    fun detectStaves(im: OmrImage): List<Staff> {
        val w = im.w; val h = im.h
        val longest = IntArray(h); val st = IntArray(h); val en = IntArray(h)
        for (y in 0 until h) {
            var best = 0; var bs = 0; var be = 0
            var rs = -1; var last = -10
            for (x in 0 until w) {
                if (im.ink[y * w + x]) { if (rs < 0) rs = x; last = x }
                else if (rs >= 0 && x - last > 2) {
                    if (last - rs + 1 > best) { best = last - rs + 1; bs = rs; be = last }
                    rs = -1
                }
            }
            if (rs >= 0 && last - rs + 1 > best) { best = last - rs + 1; bs = rs; be = last }
            longest[y] = best; st[y] = bs; en[y] = be
        }
        val mx = longest.max()
        val thr = max(0.08 * w, 0.2 * mx)
        val cy = ArrayList<Double>(); val cs = ArrayList<Int>(); val ce = ArrayList<Int>(); val ct = ArrayList<Int>()
        var y = 0
        while (y < h) {
            if (longest[y] >= thr) {
                var y2 = y
                while (y2 + 1 < h && longest[y2 + 1] >= thr) y2++
                cy.add((y + y2) / 2.0); ct.add(y2 - y + 1)
                var bi = y; for (k in y..y2) if (longest[k] > longest[bi]) bi = k
                cs.add(st[bi]); ce.add(en[bi])
                y = y2 + 1
            } else y++
        }
        val out = ArrayList<Staff>()
        var i = 0
        while (i + 4 < cy.size) {
            val d = DoubleArray(4) { cy[i + it + 1] - cy[i + it] }
            val mn = d.min(); val mxd = d.max()
            if (mn >= 4 && mxd / mn < 1.3 && ct.subList(i, i + 5).all { it <= mn * 0.6 + 1 }) {
                out.add(Staff(DoubleArray(5) { cy[i + it] }, cs.subList(i, i + 5).sorted()[2], ce.subList(i, i + 5).sorted()[2], ct.subList(i, i + 5).sorted()[2]))
                i += 5
            } else i++
        }
        return out
    }

    // ---------- limpeza das linhas ----------
    private fun removeLines(im: OmrImage, staves: List<Staff>): BooleanArray {
        val w = im.w; val h = im.h
        val ink = im.ink
        val clean = ink.copyOf()
        fun thinRun(x: Int, yc: Int, lim: Int): IntArray? {   // trecho vertical de tinta de espessura <= lim que passa por yc
            var y0 = -1
            for (d in intArrayOf(0, -1, 1)) { val yy = yc + d; if (yy in 0 until h && ink[yy * w + x]) { y0 = yy; break } }
            if (y0 < 0) return null
            var a = y0; while (a > 0 && ink[(a - 1) * w + x]) a--
            var b = y0; while (b < h - 1 && ink[(b + 1) * w + x]) b++
            return if (b - a + 1 <= lim) intArrayOf(a, b) else null
        }
        for (s in staves) {
            val lim = s.thick + 2
            for (yl in s.ys) {
                val yc = yl.roundToInt()
                val xe = min(s.x1, w - 1)
                val erasedRun = arrayOfNulls<IntArray>(w)
                for (x in s.x0..xe) thinRun(x, yc, lim)?.let { r -> erasedRun[x] = r; for (y in r[0]..r[1]) clean[y * w + x] = false }
                // repara cortes curtos no meio de traços finos (curvas tangentes à linha, como a barriga do bemol)
                fun inkNear(x: Int) = (-1..1).any { d -> yc + d in 0 until h && ink[(yc + d) * w + x] }
                var x = s.x0 + 3
                while (x < xe - 3) {
                    if (erasedRun[x] != null) {
                        var x2 = x; while (x2 + 1 <= xe && erasedRun[x2 + 1] != null) x2++
                        fun tall(xx: Int): Boolean {
                            var a = yc; while (a > 0 && ink[(a - 1) * w + xx]) a--
                            var b = yc; while (b < h - 1 && ink[(b + 1) * w + xx]) b++
                            return b - a + 1 >= 3.0 * s.space
                        }
                        if (x2 - x + 1 <= 0.5 * s.space && x - s.x0 > 1.5 * s.space && erasedRun[x - 1] == null && erasedRun[x2 + 1] == null && inkNear(x - 1) && inkNear(x2 + 1) && !tall(x - 1) && !tall(x2 + 1)) {
                            for (xx in x..x2) erasedRun[xx]?.let { r -> for (y in r[0]..r[1]) clean[y * w + xx] = true }
                        }
                        x = x2 + 1
                    } else x++
                }
            }
            // linhas suplementares
            val minSeg = max(3, (0.3 * s.space).roundToInt())
            val limL = s.thick
            for (k in 1..6) for (dir in intArrayOf(-1, 1)) {
                val yc = ((if (dir < 0) s.top else s.bottom) + dir * k * s.space).roundToInt()
                if (yc < 0 || yc >= h) continue
                var x = max(0, s.x0 - 2 * s.space.toInt())
                val xe = min(w - 1, s.x1)
                while (x <= xe) {
                    if (thinRun(x, yc, limL) != null) {
                        var x2 = x
                        while (x2 + 1 <= xe && thinRun(x2 + 1, yc, limL) != null) x2++
                        if (x2 - x + 1 >= minSeg) for (xx in x..x2) thinRun(xx, yc, limL)?.let { r -> for (yy in r[0]..r[1]) clean[yy * w + xx] = false }
                        x = x2 + 1
                    } else x++
                }
            }
        }
        return clean
    }

    private fun erode(src: BooleanArray, w: Int, h: Int, ew: Int, eh: Int): BooleanArray {
        val a = (ew - 1) / 2; val b = ew - 1 - a
        val tmp = BooleanArray(w * h)
        val run = IntArray(w)
        for (y in 0 until h) {
            var r = 0
            for (x in 0 until w) { r = if (src[y * w + x]) r + 1 else 0; run[x] = r }
            for (x in 0 until w) { val xe = x + b; if (xe < w && run[xe] >= ew) tmp[y * w + x] = true }
        }
        val out = BooleanArray(w * h)
        val c = eh - 1 - (eh - 1) / 2; val a2 = (eh - 1) / 2
        val col = IntArray(h)
        for (x in 0 until w) {
            var r = 0
            for (y in 0 until h) { r = if (tmp[y * w + x]) r + 1 else 0; col[y] = r }
            for (y in 0 until h) { val ye = y + c; if (ye < h && col[ye] >= eh) out[y * w + x] = true }
        }
        @Suppress("UNUSED_VARIABLE") val unused = a + a2
        return out
    }

    // ---------- leitura principal ----------
    fun read(im: OmrImage, o: OmrOptions = OmrOptions()): OmrResult {
        val w = im.w; val h = im.h
        val warnings = ArrayList<String>()
        val staves = detectStaves(im)
        if (staves.isEmpty()) throw IllegalStateException("Não encontrei pautas na imagem. Use um PDF/imagem nítido, com a página inteira e reta.")
        val sp = staves.map { it.space }.sorted()[staves.size / 2]
        if (sp < 6.5) warnings += "Imagem de baixa resolução (espaço da pauta ≈ ${"%.1f".format(sp)} px): a leitura pode errar muito."
        val clean = removeLines(im, staves)

        // cabeças cheias: erosão
        val ew = max(3, (0.6 * sp).roundToInt()); val eh = max(3, (0.62 * sp).roundToInt())
        val er = erode(clean, w, h, ew, eh)
        val ec = components(er, w, h)
        val cc0 = components(clean, w, h)

        // tinta dilatada (3x3) para a correlação das cabeças vazadas
        val ink3 = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) if (im.ink[y * w + x]) {
            for (dy in -1..1) { val yy = y + dy; if (yy < 0 || yy >= h) continue; for (dx in -1..1) { val xx = x + dx; if (xx in 0 until w) ink3[yy * w + xx] = true } }
        }

        val parts = ArrayList<MutableList<MutableList<Item>>>()   // [sistema][pauta] -> itens
        val clefs = ArrayList<Char>()
        val keySigs = ArrayList<Int>()

        // agrupa pautas em sistemas: unidas por traço vertical à esquerda
        val sysOf = IntArray(staves.size)
        var sysN = 0
        for (i in staves.indices) {
            if (i == 0) { sysOf[i] = 0; sysN = 1; continue }
            val a = staves[i - 1]; val b = staves[i]
            var joined = false
            for (dx in 0..(1.5 * sp).toInt()) {
                val x = max(a.x0, b.x0) + dx
                if (x >= w) break
                var ok = true
                for (y in a.bottom.roundToInt()..b.top.roundToInt()) if (!clean[y * w + x] && !im.ink[y * w + x]) { ok = false; break }
                if (ok) { joined = true; break }
            }
            if (joined) sysOf[i] = sysOf[i - 1] else { sysOf[i] = sysN; sysN++ }
        }

        class StaffRead(val items: List<Item>, val clef: Char, val fifths: Int)
        val reads = ArrayList<StaffRead>()

        for (s in staves) {
            val si = staves.indexOf(s)
            val top = max(s.top - 6 * sp, if (si > 0) (staves[si - 1].bottom + s.top) / 2.0 else -1e9)
            val bot = min(s.bottom + 6 * sp, if (si < staves.size - 1) (s.bottom + staves[si + 1].top) / 2.0 else 1e9)
            val bandTop = (s.top - 0.4 * sp).roundToInt(); val bandBot = (s.bottom + 0.4 * sp).roundToInt()
            // clave: componente mais à esquerda alto
            var clefComp = -1
            if (debug) for (c in 1..cc0.n) if (cc0.minx[c] < s.x0 + 4 * sp && cc0.maxx[c] > s.x0 - 3 * sp && cc0.maxy[c] >= s.top - 3 * sp && cc0.miny[c] <= s.bottom + 3 * sp) println("  clefcand c=$c x=${cc0.minx[c]}..${cc0.maxx[c]} y=${cc0.miny[c]}..${cc0.maxy[c]}")
            for (c in 1..cc0.n) {
                val bw = cc0.maxx[c] - cc0.minx[c] + 1; val bh = cc0.maxy[c] - cc0.miny[c] + 1
                if (cc0.minx[c] < s.x0 - 2 * sp || cc0.minx[c] > s.x0 + 3.0 * sp) continue
                if (bh < 2.3 * sp || bh > 8 * sp || bw < 1.1 * sp || bw > 5 * sp) continue
                if (cc0.maxy[c] < bandTop || cc0.miny[c] > bandBot) continue
                if (clefComp < 0 || cc0.minx[c] < cc0.minx[clefComp]) clefComp = c
            }
            val clefRight = if (clefComp > 0) cc0.maxx[clefComp] else s.x0
            val clef = if (clefComp > 0 && cc0.maxy[clefComp] > s.bottom + 0.6 * sp) 'G' else if (clefComp > 0) 'F' else 'G'

            // sinal de compasso (dois algarismos colados num só componente), logo depois da clave/armadura
            var timeLeft = Double.MAX_VALUE; var timeRight = 0.0
            for (c in 1..cc0.n) {
                val bw = cc0.maxx[c] - cc0.minx[c] + 1; val bh = cc0.maxy[c] - cc0.miny[c] + 1
                val cx = (cc0.minx[c] + cc0.maxx[c]) / 2.0; val cy = (cc0.miny[c] + cc0.maxy[c]) / 2.0
                if (c == clefComp || cx <= clefRight || cx > clefRight + 14 * sp) continue
                if (bh < 3.3 * sp || bh > 4.7 * sp || bw < 0.8 * sp || bw > 2.3 * sp || abs(cy - s.ys[2]) > 0.6 * sp) continue
                if (cc0.area[c] < 0.33 * bw * bh) continue
                if (cx - bw / 2.0 < timeLeft) { timeLeft = cx - bw / 2.0; timeRight = cx + bw / 2.0 }
            }
            val heads = ArrayList<Head>()
            // cheias
            for (c in 1..ec.n) {
                val cy0 = (ec.miny[c] + ec.maxy[c]) / 2.0
                if (cy0 < top || cy0 > bot) continue
                val cx0 = (ec.minx[c] + ec.maxx[c]) / 2.0
                if (cx0 < clefRight + 0.2 * sp || cx0 < s.x0 || cx0 > s.x1) continue
                val bw = ec.maxx[c] - ec.minx[c] + 1 + ew - 1; val bh = ec.maxy[c] - ec.miny[c] + 1 + eh - 1
                if (bw < 0.7 * sp || bh < 0.6 * sp || bw > 3.6 * sp || bh > 7 * sp) continue
                if (bw >= 2.3 * sp) {       // segunda: duas cabeças lado a lado
                    val xm = (ec.minx[c] + ec.maxx[c]) / 2.0
                    var sl = 0.0; var nl = 0; var sr = 0.0; var nr = 0
                    for (y in ec.miny[c]..ec.maxy[c]) for (x in ec.minx[c]..ec.maxx[c]) if (er[y * w + x] && ec.label[y * w + x] == c) { if (x < xm) { sl += y; nl++ } else { sr += y; nr++ } }
                    if (nl > 0) heads.add(Head(ec.minx[c] + (bw - ew + 1) * 0.25 + (ew - 1) / 2.0, sl / nl, true))
                    if (nr > 0) heads.add(Head(ec.minx[c] + (bw - ew + 1) * 0.75 + (ew - 1) / 2.0, sr / nr, true))
                } else {
                    val n = max(1, (bh / (1.05 * sp)).roundToInt())
                    for (k in 0 until n) heads.add(Head(cx0, ec.miny[c] - (eh - 1) / 2.0 + (k + 0.5) * bh / n, true))
                }
            }
            // vazadas: correlação com uma elipse (as linhas da pauta passam pelo anel, por isso não uso buracos)
            run {
                val th = s.thick / 2 + 1
                val lineRows = ArrayList<Double>()
                for (k in -7..7) lineRows.add(if (k < 0) s.top + k * sp else s.bottom + k * sp)
                for (yl in s.ys) lineRows.add(yl)
                fun onLine(y: Double) = lineRows.any { abs(it - y) <= th }
                val ang = Math.toRadians(-22.0); val ca = Math.cos(ang); val sa = Math.sin(ang)
                class Tpl(val a: Double, val b: Double, val ring: List<DoubleArray>, val inner: List<DoubleArray>, val outer: List<DoubleArray>, val whole: Boolean)
                fun mk(a: Double, b: Double, rot: Double, whole: Boolean): Tpl {
                    val c = Math.cos(rot); val sn = Math.sin(rot)
                    val ringP = (0 until 24).map { val t = it * 2 * Math.PI / 24; val ex = a * Math.cos(t); val ey = b * Math.sin(t); doubleArrayOf(ex * c - ey * sn, ex * sn + ey * c) }
                    val inner = (0 until 8).map { val t = it * 2 * Math.PI / 8; val sc = if (whole) 0.3 else 0.42; val ex = sc * a * Math.cos(t); val ey = sc * b * Math.sin(t); doubleArrayOf(ex * c - ey * sn, ex * sn + ey * c) } + listOf(doubleArrayOf(0.0, 0.0))
                    val outer = ringP.map { doubleArrayOf(it[0] * 1.7, it[1] * 1.7) }
                    return Tpl(a, b, ringP, inner, outer, whole)
                }
                val tpls = listOf(mk(0.50 * sp, 0.34 * sp, ang, false), mk(0.85 * sp, 0.47 * sp, 0.0, true))
                @Suppress("UNUSED_VARIABLE") val unusedTrig = ca + sa
                class Cand(val x: Int, val y: Double, val score: Double, val whole: Boolean)
                val cands = ArrayList<Cand>()
                val xa = max(s.x0, (clefRight + 0.6 * sp).roundToInt()); val xb = min(s.x1, w - 1)
                fun ledgerRun(cx: Int, y: Double): Boolean {       // linha suplementar com pelo menos 1,2 espaço em torno de cx
                    for (dy in -1..1) {
                        val yy = y.roundToInt() + dy
                        if (yy !in 0 until h) continue
                        var l = 0; var x = cx; while (x >= 0 && im.ink[yy * w + x]) { l++; x-- }
                        var r = 0; x = cx + 1; while (x < w && im.ink[yy * w + x]) { r++; x++ }
                        if (l + r >= 1.2 * sp) return true
                    }
                    return false
                }
                for (pos in -7..15) {
                    val cy = s.bottom - pos * sp / 2.0
                    if (cy < 1 || cy > h - 2 || cy < top || cy > bot) continue
                    for (cx in xa..xb) {
                        for (t in tpls) {
                            var rs = 0
                            for (p in t.ring) { val x = (cx + p[0]).roundToInt(); val y = (cy + p[1]).roundToInt(); if (x in 0 until w && y in 0 until h && ink3[y * w + x]) rs++ }
                            val rf = rs / t.ring.size.toDouble()
                            if (debug && t.whole && rf >= 0.8) println("   whole? x=$cx y=${cy.toInt()} pos=$pos rf=${"%.2f".format(rf)}")
                            if (rf < 0.9) continue
                            if (pos >= 10) { var okL = true; for (k in 1..(pos - 8) / 2) if (!ledgerRun(cx, s.top - k * sp)) okL = false; if (!okL) continue }
                            if (pos <= -2) { var okL = true; for (k in 1..(-pos) / 2) if (!ledgerRun(cx, s.bottom + k * sp)) okL = false; if (!okL) continue }
                            var wh = 0; var cnt = 0
                            for (p in t.inner) { val yy = cy + p[1]; if (onLine(yy)) continue; cnt++; val x = (cx + p[0]).roundToInt(); val y = yy.roundToInt(); if (x in 0 until w && y in 0 until h && !im.ink[y * w + x]) wh++ }
                            if (cnt < (if (t.whole) 0 else 3) || wh < cnt) continue
                            if (t.whole) {      // semibreve: nada ao redor (sem haste, sem acidente colado)
                                var free = 0; var tot = 0
                                for (p in t.outer) { if (abs(p[0]) < 0.7 * 1.7 * t.a) continue; val yy = cy + p[1]; if (onLine(yy)) continue; tot++; val x = (cx + p[0]).roundToInt(); val y = yy.roundToInt(); if (x in 0 until w && y in 0 until h && !im.ink[y * w + x]) free++ else if (x !in 0 until w || y !in 0 until h) free++ }
                                if (tot < 5 || free < tot) continue
                                var stemNear = false
                                var xx = (cx - 1.3 * t.a).roundToInt()
                                val xe2 = (cx + 1.3 * t.a).roundToInt()
                                while (xx <= xe2 && !stemNear) {
                                    if (xx in 0 until w) {
                                        var up = 0; var y = cy.roundToInt(); while (y > 0 && clean[y * w + xx]) { up++; y-- }
                                        var dn = 0; y = cy.roundToInt() + 1; while (y < h && clean[y * w + xx]) { dn++; y++ }
                                        if (up + dn >= 1.9 * sp) stemNear = true
                                    }
                                    xx++
                                }
                                if (stemNear) continue
                            }
                            cands.add(Cand(cx, cy, rf, t.whole))
                        }
                    }
                }
                val acc = ArrayList<Cand>()
                for (c in cands.sortedByDescending { it.score }) {
                    if (acc.any { abs(it.x - c.x) < 0.9 * sp && abs(it.y - c.y) < 0.4 * sp }) continue
                    if (heads.any { abs(it.x - c.x) < 0.9 * sp && abs(it.y - c.y) < 0.45 * sp }) continue
                    acc.add(c)
                }
                // centra no meio do trecho conexo de bons candidatos vizinhos
                for (c in acc) {
                    val near = cands.filter { abs(it.y - c.y) < 0.1 && abs(it.x - c.x) < 0.6 * sp && it.whole == c.whole }
                    val cx = if (near.isEmpty()) c.x.toDouble() else (near.minOf { it.x } + near.maxOf { it.x }) / 2.0
                    heads.add(Head(cx, c.y, false).also { it.whole = c.whole; it.score = c.score })
                }
            }
            heads.removeAll { it.x < timeRight + 0.2 * sp }
            heads.sortBy { it.x }

            // hastes
            val stemWin = max(1, (0.12 * sp).roundToInt())
            val half = (0.5 * sp).roundToInt()
            for (hd in heads) {
                var bestLen = 0.0; var bestX = Double.NaN; var bestUp = true
                val y0 = hd.y.roundToInt().coerceIn(0, h - 1)
                for (side in intArrayOf(-1, 1)) {
                    val xs = (hd.x + side * 0.5 * sp).roundToInt()
                    for (dx in -stemWin..stemWin) {
                        val x = xs + dx
                        if (x < 0 || x >= w) continue
                        var ra = Int.MAX_VALUE; var rb = Int.MIN_VALUE
                        for (y in max(0, y0 - half)..min(h - 1, y0 + half)) if (clean[y * w + x]) {
                            var a2 = y; while (a2 > 0 && clean[(a2 - 1) * w + x]) a2--
                            var b2 = y; while (b2 < h - 1 && clean[(b2 + 1) * w + x]) b2++
                            if (a2 < ra) ra = a2; if (b2 > rb) rb = b2
                        }
                        if (ra == Int.MAX_VALUE) continue
                        val up = (y0 - ra).toDouble(); val dn = (rb - y0).toDouble()
                        if (up > bestLen) { bestLen = up; bestX = x.toDouble(); bestUp = true }
                        if (dn > bestLen) { bestLen = dn; bestX = x.toDouble(); bestUp = false }
                    }
                }
                if (bestLen >= 2.2 * sp) {
                    hd.hasStem = true; hd.stemX = bestX; hd.up = bestUp
                    hd.tip = if (bestUp) hd.y - bestLen else hd.y + bestLen
                }
            }
            heads.removeAll { !it.hasStem && !it.whole }
            heads.removeAll { it.whole && it.hasStem }
            // bandeirolas e barras: camadas de tinta ao lado da haste, perto da ponta
            for (hd in heads) {
                if (!hd.hasStem || !hd.filled) continue
                val sx = hd.stemX
                var best = 0
                for (side in doubleArrayOf(-1.0, 1.0)) {
                    val x = (sx + side * 0.4 * sp).roundToInt()
                    if (x < 0 || x >= w) continue
                    val inward = if (hd.up) 1 else -1
                    val y0 = hd.tip.roundToInt(); val y1 = (hd.tip + inward * 2.3 * sp).roundToInt()
                    val minRun = max(3, (0.3 * sp).roundToInt())
                    var runs = 0; var inRun = false; var runLen = 0; var maxLen = 0; var count = 0
                    for (y in min(y0, y1)..max(y0, y1)) {
                        val on = y in 0 until h && clean[y * w + x]
                        if (on) { if (!inRun) { inRun = true; runLen = 0 }; runLen++; if (runLen > maxLen) maxLen = runLen; if (runLen == minRun) runs++ }
                        else inRun = false
                    }
                    count = if (runs == 1 && maxLen >= 0.9 * sp) 2 else runs
                    if (count > best) best = count
                    if (debug) println("   flags head x=${hd.x.toInt()} side=$side runs=$runs maxLen=$maxLen")
                }
                hd.flags = best.coerceIn(0, 3)
            }

            // componentes de símbolos (com as cabeças apagadas, para o acidente colado não ser engolido por elas)
            val clean2 = clean.copyOf()
            for (hd in heads) {
                val rx = 0.72 * sp; val ry = 0.56 * sp
                for (y in max(0, (hd.y - ry).toInt())..min(h - 1, (hd.y + ry).toInt() + 1)) for (x in max(0, (hd.x - rx).toInt())..min(w - 1, (hd.x + rx).toInt() + 1)) {
                    val ex = (x - hd.x) / rx; val ey = (y - hd.y) / ry
                    if (ex * ex + ey * ey <= 1.0) clean2[y * w + x] = false
                }
            }
            val cc = components(clean2, w, h)
            val headComps = HashSet<Int>()
            for (hd in heads) {
                var lab = 0
                if (hd.hasStem) {
                    val dir = if (hd.up) -1 else 1
                    for (k in 1..8) {
                        val yy = (hd.y + dir * (0.8 + 0.15 * k) * sp).roundToInt().coerceIn(0, h - 1)
                        for (dx in -1..1) { val l = cc.label[yy * w + (hd.stemX.roundToInt() + dx).coerceIn(0, w - 1)]; if (l != 0 && lab == 0) lab = l }
                    }
                }
                hd.comp = lab
                if (lab != 0) headComps.add(lab)
            }
            val stemXs = heads.filter { it.hasStem }.map { it.stemX }

            // barras de compasso
            val barXs = ArrayList<Double>()
            run {
                var x = (clefRight + 0.2 * sp).roundToInt()
                val y0 = s.top.roundToInt(); val y1 = s.bottom.roundToInt()
                var runStart = -1
                val limit = min(s.x1, w - 1)
                while (x <= limit + 1) {
                    var full = x <= limit
                    if (full) for (y in y0..y1) if (!clean[y * w + x]) { full = false; break }
                    if (full && stemXs.any { abs(it - x) <= 1.5 }) full = false
                    if (full && heads.any { abs(it.x - x) < 0.55 * sp && abs(it.y - s.top - 2 * sp) < 3.2 * sp }) full = false
                    if (full) { if (runStart < 0) runStart = x }
                    else if (runStart >= 0) { barXs.add((runStart + x - 1) / 2.0); runStart = -1 }
                    x++
                }
            }
            // une barras próximas (dupla/final)
            val bars = ArrayList<Double>()
            for (b in barXs) if (bars.isEmpty() || b - bars.last() > 1.2 * sp) bars.add(b)

            // símbolos soltos
            class Sym(val c: Int, val cx: Double, val cy: Double, val bw: Int, val bh: Int)
            val syms = ArrayList<Sym>()
            for (c in 1..cc.n) {
                if (c in headComps || c == clefComp) continue
                val bw = cc.maxx[c] - cc.minx[c] + 1; val bh = cc.maxy[c] - cc.miny[c] + 1
                val cx = (cc.minx[c] + cc.maxx[c]) / 2.0; val cy = (cc.miny[c] + cc.maxy[c]) / 2.0
                if (cx <= clefRight || cx < s.x0 || cx > s.x1) continue
                if (cc.maxy[c] < s.top - 3.2 * sp || cc.miny[c] > s.bottom + 3.2 * sp) continue
                if (cc.area[c] < 0.05 * sp * sp) continue
                syms += Sym(c, cx, cy, bw, bh)
            }
            fun vrun(sy: Sym): Int {
                var best = 0
                for (x in cc.minx[sy.c]..cc.maxx[sy.c]) {
                    var r = 0
                    for (y in cc.miny[sy.c]..cc.maxy[sy.c]) { if (cc.label[y * w + x] == sy.c) { r++; if (r > best) best = r } else r = 0 }
                }
                return best
            }
            val used = HashSet<Int>()
            // pontos
            for (sy in syms) {
                if (sy.bw in (0.12 * sp).toInt()..(0.6 * sp).toInt() && sy.bh in (0.12 * sp).toInt()..(0.6 * sp).toInt() && cc.area[sy.c] >= 0.5 * sy.bw * sy.bh) {
                    val near = heads.filter { sy.cx - it.x in 0.7 * sp..2.1 * sp && abs(sy.cy - it.y) <= 0.6 * sp }
                    val hd = near.minByOrNull { sy.cx - it.x }
                    if (hd != null) { hd.dots++; used += sy.c }
                }
            }
            // sinais de compasso (dois algarismos empilhados) logo depois da armadura
            run {
                val cands = syms.filter { it.c !in used && it.bh in (1.4 * sp).toInt()..(2.6 * sp).toInt() && it.bw in (0.5 * sp).toInt()..(1.8 * sp).toInt() && it.cy in s.top - 0.3 * sp..s.bottom + 0.3 * sp }
                for (a in cands) for (b in cands) {
                    if (a === b || a.cy >= b.cy) continue
                    if (abs(a.cx - b.cx) > 0.5 * sp) continue
                    if (b.cy - a.cy < 1.2 * sp || b.cy - a.cy > 2.6 * sp) continue
                    val firstHead = heads.firstOrNull()?.x ?: Double.MAX_VALUE
                    if (a.cx > firstHead) continue
                    if (a.cx < timeLeft) { timeLeft = a.cx; timeRight = max(a.cx + a.bw / 2.0, b.cx + b.bw / 2.0); used += a.c; used += b.c }
                }
            }
            if (timeLeft != Double.MAX_VALUE) for (sy in syms) if (sy.cx >= timeLeft - 1 && sy.cx <= timeRight + 1 && sy.bh >= 3.0 * sp) used += sy.c
            if (timeLeft == Double.MAX_VALUE) for (sy in syms) {
                if (sy.c in used) continue
                val firstHead = heads.firstOrNull()?.x ?: Double.MAX_VALUE
                if (sy.bh in (3.3 * sp).toInt()..(4.7 * sp).toInt() && sy.bw in (0.8 * sp).toInt()..(2.3 * sp).toInt() && cc.area[sy.c] >= 0.33 * sy.bw * sy.bh && abs(sy.cy - s.ys[2]) < 0.6 * sp && sy.cx < firstHead && sy.cx < s.x0 + 14 * sp) {
                    timeLeft = sy.cx - sy.bw / 2.0; timeRight = sy.cx + sy.bw / 2.0; used += sy.c; break
                }
            }
            // armadura: componentes largos (vários sustenidos/bemóis colados) são contados pelos traços verticais
            var keyN = 0; var keySign = 0
            run {
                val firstHeadX0 = heads.firstOrNull()?.x ?: Double.MAX_VALUE
                val lim = min(firstHeadX0, if (timeLeft == Double.MAX_VALUE) Double.MAX_VALUE else timeLeft)
                for (sy in syms) {
                    if (sy.c in used || sy.bw < 1.25 * sp || sy.bh < 1.8 * sp || sy.bh > 5.2 * sp) continue
                    if (sy.cx <= clefRight || sy.cx + sy.bw / 2.0 > lim + 0.5 * sp) continue
                    val strokes = ArrayList<DoubleArray>()      // x, y médio
                    var runCols = ArrayList<Int>()
                    var lastX = -9
                    fun flush() {
                        if (runCols.isNotEmpty()) {
                            var sy0 = 0.0; var sn = 0
                            for (xx in runCols) { var r = 0; var bestR = 0; var bestEnd = 0
                                for (yy in cc.miny[sy.c]..cc.maxy[sy.c]) { if (cc.label[yy * w + xx] == sy.c) { r++; if (r > bestR) { bestR = r; bestEnd = yy } } else r = 0 }
                                sy0 += bestEnd - bestR / 2.0; sn++ }
                            strokes.add(doubleArrayOf(runCols.average(), sy0 / sn)); runCols = ArrayList()
                        }
                    }
                    for (x in cc.minx[sy.c]..cc.maxx[sy.c]) {
                        var r = 0; var best = 0
                        for (y in cc.miny[sy.c]..cc.maxy[sy.c]) { if (cc.label[y * w + x] == sy.c) { r++; if (r > best) best = r } else r = 0 }
                        if (best >= 1.5 * sp) { if (x - lastX > 1) flush(); runCols.add(x); lastX = x }
                    }
                    flush()
                    if (debug) println("  keysig comp bw=${sy.bw} strokes=${strokes.map { "${it[0].toInt()}:${it[1].toInt()}" }}")
                    if (strokes.isEmpty()) continue
                    val glyphs = ArrayList<MutableList<DoubleArray>>()
                    for (st in strokes) if (glyphs.isEmpty() || st[0] - glyphs.last().last()[0] > 0.62 * sp) glyphs.add(mutableListOf(st)) else glyphs.last().add(st)
                    for (g in glyphs) {
                        val kind = if (g.size == 1) -1 else if (abs(g[0][1] - g[1][1]) > 0.45 * sp) 0 else 1
                        if (kind != 0) { keyN++; keySign += kind }
                    }
                    used += sy.c
                }
            }
            // acidentes
            class Acc(val sym: Sym, val kind: Int) // kind: -1 bemol, 0 bequadro, 1 sustenido
            val accs = ArrayList<Acc>()
            for (sy in syms) {
                if (sy.c in used) continue
                if (sy.bh < 1.6 * sp || sy.bh > 3.6 * sp || sy.bw < 0.45 * sp || sy.bw > 1.5 * sp) continue
                val vr = vrun(sy)
                if (vr < 1.3 * sp) continue
                if (sy.bw < 0.3 * sp) continue
                // colunas com traço vertical longo
                val cols = ArrayList<IntArray>()    // [x, centroY*1000]
                for (x in cc.minx[sy.c]..cc.maxx[sy.c]) {
                    var r = 0; var bestR = 0; var bestEnd = 0
                    for (y in cc.miny[sy.c]..cc.maxy[sy.c]) { if (cc.label[y * w + x] == sy.c) { r++; if (r > bestR) { bestR = r; bestEnd = y } } else r = 0 }
                    if (bestR >= 0.62 * sy.bh) cols.add(intArrayOf(x, ((bestEnd - bestR / 2.0) * 1000).toInt()))
                }
                if (cols.isEmpty()) continue
                val groups = ArrayList<MutableList<IntArray>>()
                for (c in cols) if (groups.isEmpty() || c[0] - groups.last().last()[0] > 1) groups.add(mutableListOf(c)) else groups.last().add(c)
                if (groups.any { it.size > max(2.0, 0.28 * sp) }) continue
                if (groups.size == 1 && sy.bh > 2.75 * sp) continue
                val kind = if (groups.size == 1) -1 else {
                    val d = abs(groups[0].map { it[1] }.average() - groups[1].map { it[1] }.average()) / 1000.0
                    if (d > 0.45 * sp) 0 else 1
                }
                if (debug) println("  acc c=${sy.c} cx=${sy.cx.toInt()} bw=${sy.bw} bh=${sy.bh} vr=${vr} grupos=${groups.map { it.size }} kind=$kind")
                accs += Acc(sy, kind); used += sy.c
            }
            // armadura × acidentes de nota
            val firstHeadX = heads.firstOrNull()?.x ?: Double.MAX_VALUE
            for (a in accs.sortedBy { it.sym.cx }) {
                val hd = heads.filter { it.x - a.sym.cx in 0.3 * sp..1.9 * sp && abs(it.y - (a.sym.cy + (if (a.kind == -1) 0.3 * sp else 0.0))) <= 1.4 * sp }.minByOrNull { abs(it.y - a.sym.cy) }
                if (hd != null && hd.acc == null) hd.acc = a.kind
                else if (a.sym.cx < firstHeadX && a.sym.cx > clefRight && (timeLeft == Double.MAX_VALUE || a.sym.cx < timeLeft)) { keyN++; keySign += a.kind }
            }
            val fifths = if (keySign < 0) -keyN else keyN
            // símbolos que são pedaços de cabeças já achadas (anel solto) não são pausas
            for (sy in syms) if (sy.c !in used && heads.any { it.x in cc.minx[sy.c] - 0.3 * sp..cc.maxx[sy.c] + 0.3 * sp && it.y in cc.miny[sy.c] - 0.3 * sp..cc.maxy[sy.c] + 0.3 * sp }) used += sy.c
            // pausas
            val rests = ArrayList<Item>()
            val lineMid = s.ys[2]
            for (sy in syms) {
                if (sy.c in used) continue
                if (sy.cx < timeRight + 0.1 * sp || sy.cx < clefRight) continue
                if (sy.cy < s.top - 0.3 * sp || sy.cy > s.bottom + 0.3 * sp) continue
                if (sy.bw < 0.6 * sp || sy.bw > 1.9 * sp) continue
                if (sy.bw < 0.3 * sp * 0 + 0 || cc.area[sy.c] < 0.3 * sp * sp) continue
                if (vrun(sy) >= 1.7 * sp && sy.bw < 0.4 * sp) continue
                val dur = when {
                    sy.bh <= 0.75 * sp && sy.bw >= 0.8 * sp -> if (sy.cy < lineMid - 0.5 * sp) 16 else 8
                    sy.bh >= 2.6 * sp -> 4
                    sy.bh >= 1.2 * sp -> 2
                    else -> continue
                }
                rests += Item(sy.cx, 1, dur)
            }
            // agrupa cabeças em acordes
            val items = ArrayList<Item>()
            val groupsH = ArrayList<MutableList<Head>>()
            for (hd in heads) {
                val g = groupsH.lastOrNull()
                val same = g != null && g.any { (it.hasStem && hd.hasStem && abs(it.stemX - hd.stemX) <= 0.3 * sp) || abs(it.x - hd.x) <= 0.4 * sp }
                if (same) g!!.add(hd) else groupsH.add(mutableListOf(hd))
            }
            for (g in groupsH) {
                val ref = g.firstOrNull { it.hasStem } ?: g[0]
                val base = when {
                    !ref.filled && !ref.hasStem -> 16
                    !ref.filled -> 8
                    ref.flags >= 2 -> 1
                    ref.flags == 1 -> 2
                    else -> 4
                }
                val dots = g.maxOf { it.dots }
                val dur = if (dots >= 1) base + base / 2 + (if (dots >= 2) base / 4 else 0) else base
                items += Item(g.map { it.x }.average(), 0, dur, g.map { hd -> intArrayOf(Math.round((s.bottom - hd.y) / (s.space / 2)).toInt(), hd.acc ?: 99) }.sortedBy { it[0] })
            }
            items += rests
            for (b in bars) items += Item(b, 2, 0)
            items.sortBy { it.x }
            if (debug) {
                println("PAUTA sp=${"%.1f".format(sp)} x0=${s.x0} clefRight=$clefRight clef=$clef fifths=$fifths time=[$timeLeft,$timeRight] heads=${heads.size} bars=${bars.map { it.toInt() }}")
                for (sy in syms) println("  sym c=${sy.c} cx=${sy.cx.toInt()} cy=${sy.cy.toInt()} bw=${"%.1f".format(sy.bw / sp)} bh=${"%.1f".format(sy.bh / sp)} usado=${sy.c in used}")
                for (hd in heads) println("  head x=${hd.x.toInt()} y=${hd.y.toInt()} filled=${hd.filled} whole=${hd.whole} stem=${hd.hasStem} up=${hd.up} flags=${hd.flags} dots=${hd.dots} acc=${hd.acc} score=${"%.2f".format(hd.score)}")
                for (it in items) println("  item x=${it.x.toInt()} k=${it.kind} d=${it.dur} n=${it.notes.map { n -> n[0] }}")
            }
            reads += StaffRead(items, clef, fifths)
            clefs += clef; keySigs += fifths
        }

        // ---------- monta a partitura ----------
        val nPart = (0 until sysN).map { sy -> staves.indices.count { sysOf[it] == sy } }.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        val fifths0 = keySigs.firstOrNull() ?: 0
        val perPart = List(nPart) { ArrayList<List<Item>>() }
        val partClef = CharArray(nPart) { 'G' }
        for (sy in 0 until sysN) {
            val idx = staves.indices.filter { sysOf[it] == sy }
            if (idx.size != nPart) { warnings += "Um sistema tem ${idx.size} pauta(s) em vez de $nPart; foi ignorado."; continue }
            for ((pi, si) in idx.withIndex()) {
                if (sy == 0) partClef[pi] = reads[si].clef
                var cur = ArrayList<Item>()
                for (it in reads[si].items) {
                    if (it.kind == 2) { if (cur.isNotEmpty()) { perPart[pi].add(cur); cur = ArrayList() } }
                    else cur.add(it)
                }
                if (cur.isNotEmpty()) perPart[pi].add(cur)
            }
        }
        val sums = perPart.flatMap { p -> p.map { m -> m.sumOf { it.dur } } }.filter { it > 0 }
        val common = sums.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key ?: 16
        val beats: Int; val beatType: Int
        if (o.beats != null) { beats = o.beats; beatType = o.beatType }
        else when (common) {
            16 -> { beats = 4; beatType = 4 }; 12 -> { beats = 3; beatType = 4 }; 8 -> { beats = 2; beatType = 4 }
            20 -> { beats = 5; beatType = 4 }; 24 -> { beats = 6; beatType = 4 }; 4 -> { beats = 1; beatType = 4 }
            else -> { beats = 4; beatType = 4; warnings += "Não consegui deduzir o compasso (soma típica $common); usei 4/4." }
        }
        val barLen = 16 * beats / beatType
        val score = Score(o.title, 4, fifths0, beats, beatType, o.tempo)
        var adjusted = 0; var noteCount = 0
        val nMeas = perPart.maxOf { it.size }
        val sharpOrder = "FCGDAEB"; val flatOrder = "BEADGCF"
        fun keyAlter(step: Char): Int = when {
            fifths0 > 0 -> if (sharpOrder.indexOf(step) in 0 until fifths0) 1 else 0
            fifths0 < 0 -> if (flatOrder.indexOf(step) in 0 until -fifths0) -1 else 0
            else -> 0
        }
        fun padRests(m: Measure, n: Int) {
            var left = n
            for (d in intArrayOf(16, 12, 8, 6, 4, 3, 2, 1)) while (left >= d) { m.notes += Note(null, d); left -= d }
        }
        for (pi in 0 until nPart) {
            val part = Part("P${pi + 1}", if (nPart == 2) (if (pi == 0) "Piano (mão direita)" else "Piano (mão esquerda)") else "Parte ${pi + 1}", mutableListOf(), partClef[pi], if (nPart == 2) "piano" else "P${pi + 1}", "piano")
            val base = if (partClef[pi] == 'F') 2 * 7 + 4 else 4 * 7 + 2
            for (mi in 0 until nMeas) {
                val m = Measure(mi + 1)
                val src = perPart[pi].getOrNull(mi)
                var total = 0
                val acc = HashMap<Int, Int>()
                var clipped = false
                if (src != null) for (it in src) {
                    if (total >= barLen) { clipped = true; break }
                    var d = it.dur
                    if (total + d > barLen) { d = barLen - total; clipped = true }
                    if (it.kind == 1) m.notes += Note(null, d)
                    else {
                        for ((k, n) in it.notes.withIndex()) {
                            val dia = base + n[0]
                            val step = "CDEFGAB"[Math.floorMod(dia, 7)]
                            val alter = if (n[1] != 99) { acc[dia] = n[1]; n[1] } else acc[dia] ?: keyAlter(step)
                            m.notes += Note(Pitch(step, alter, Math.floorDiv(dia, 7)), d, chord = k > 0)
                            noteCount++
                        }
                    }
                    total += d
                }
                if (total < barLen && !(mi == 0 && total > 0)) { padRests(m, barLen - total); if (mi != nMeas - 1 || clipped) adjusted++ }
                else if (clipped) adjusted++
                part.measures += m
            }
            score.parts += part
        }
        if (adjusted > 0) warnings += "$adjusted compasso(s) com ritmo que não fechou; foram completados com pausas ou cortados. Confira no Estúdio."
        return OmrResult(score, staves.size, sysN, noteCount, nMeas, adjusted, warnings)
    }

    /** Junta resultados de várias páginas (mesmo número de partes) numa só partitura. */
    fun merge(rs: List<OmrResult>, title: String): OmrResult {
        require(rs.isNotEmpty())
        val first = rs[0].score
        val out = Score(title, first.divisions, first.fifths, first.beats, first.beatType, first.tempo)
        val warnings = ArrayList<String>()
        val nPart = first.parts.size
        for (pi in 0 until nPart) {
            val p = first.parts[pi]
            out.parts += Part(p.id, p.name, mutableListOf(), p.clef, p.group, p.inst)
        }
        var bars = 0
        for ((i, r) in rs.withIndex()) {
            if (r.score.parts.size != nPart) { warnings += "Página ${i + 1} tem ${r.score.parts.size} pauta(s) por sistema em vez de $nPart; foi ignorada."; continue }
            if (r.score.beats != first.beats || r.score.beatType != first.beatType) warnings += "Página ${i + 1} parece ter outro compasso (${r.score.beats}/${r.score.beatType})."
            for (pi in 0 until nPart) for (m in r.score.parts[pi].measures) out.parts[pi].measures += Measure(out.parts[pi].measures.size + 1, m.notes.toMutableList(), m.harmony.toMutableList())
            bars = out.parts[0].measures.size
            warnings += r.warnings.map { if (rs.size > 1) "Página ${i + 1}: $it" else it }
        }
        return OmrResult(out, rs.sumOf { it.staves }, rs.sumOf { it.systems }, rs.sumOf { it.notes }, bars, rs.sumOf { it.adjustedBars }, warnings)
    }
}
