package app.partitura.core

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/**
 * Medidas e máscaras da imagem, antes de ler qualquer símbolo.
 *
 * A ideia central: só é candidato a linha de pauta o pixel que está num traço vertical fino.
 * Assim um feixe de colcheias (traço vertical grosso) deixa de parecer linha de pauta, que era
 * o que fazia o leitor perder pautas inteiras cobertas de semicolcheias.
 */
object Vision {

    /** Espessura da linha e distância entre linhas (em pixels), medidas pelo padrão preto/branco mais comum. */
    class Metrics(val thickness: Int, val space: Double)

    /** Histograma dos traços verticais: o preto mais comum é a linha, o branco mais comum é o vão entre linhas. */
    fun metrics(im: OmrImage): Metrics {
        val w = im.w; val h = im.h
        val black = IntArray(40); val white = IntArray(120)
        val step = max(1, w / 500)
        var x = 0
        while (x < w) {
            var y = 0
            var prev = 0
            while (y < h) {
                if (im.ink[y * w + x]) {
                    var e = y; while (e + 1 < h && im.ink[(e + 1) * w + x]) e++
                    val len = e - y + 1
                    if (len < black.size) black[len]++
                    prev = len
                    y = e + 1
                } else {
                    var e = y; while (e + 1 < h && !im.ink[(e + 1) * w + x]) e++
                    val len = e - y + 1
                    // só conta o vão que está entre dois traços finos: é aí que mora o vão da pauta
                    if (len < white.size && prev in 1..9) white[len]++
                    y = e + 1
                }
            }
            x += step
        }
        var t = 1; for (i in 1 until black.size) if (black[i] > black[t]) t = i
        var g = 3; for (i in 3 until white.size) if (white[i] > white[g]) g = i
        return Metrics(t.coerceIn(1, 12), (g + t).toDouble().coerceIn(4.0, 110.0))
    }

    /** Tinta que está em traço vertical de até [maxRun] pixels (candidata a linha de pauta). */
    fun thinMask(im: OmrImage, maxRun: Int): BooleanArray {
        val w = im.w; val h = im.h
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var y = 0
            while (y < h) {
                if (!im.ink[y * w + x]) { y++; continue }
                var e = y; while (e + 1 < h && im.ink[(e + 1) * w + x]) e++
                if (e - y + 1 <= maxRun) for (yy in y..e) out[yy * w + x] = true
                y = e + 1
            }
        }
        return out
    }

    /** Inclinação da página em graus (positivo = linhas descem para a direita). Procura o ângulo que mais concentra a tinta fina em poucas linhas. */
    fun skewDegrees(im: OmrImage, thin: BooleanArray, maxDeg: Double = 2.5): Double {
        val w = im.w; val h = im.h
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        val stepX = max(1, w / 300); val stepY = max(1, h / 900)
        var y = 0
        while (y < h) { var x = 0; while (x < w) { if (thin[y * w + x]) { xs.add(x); ys.add(y) }; x += stepX }; y += stepY }
        if (xs.size < 200) return 0.0
        val cx = w / 2.0
        var bestA = 0.0; var bestS = -1.0
        var a = -maxDeg
        while (a <= maxDeg + 1e-9) {
            val t = tan(Math.toRadians(a))
            val prof = IntArray(h + 1)
            for (i in xs.indices) {
                val yy = (ys[i] - (xs[i] - cx) * t).roundToInt()
                if (yy in 0..h) prof[yy]++
            }
            var s = 0.0
            for (v in prof) s += v.toDouble() * v
            if (s > bestS) { bestS = s; bestA = a }
            a += 0.1
        }
        // refina
        var b = bestA - 0.1
        var bs = bestS; var ba = bestA
        while (b <= bestA + 0.1 + 1e-9) {
            val t = tan(Math.toRadians(b))
            val prof = IntArray(h + 1)
            for (i in xs.indices) { val yy = (ys[i] - (xs[i] - cx) * t).roundToInt(); if (yy in 0..h) prof[yy]++ }
            var s = 0.0
            for (v in prof) s += v.toDouble() * v
            if (s > bs) { bs = s; ba = b }
            b += 0.02
        }
        return ba
    }

    /** Gira a imagem binária por [deg] graus em torno do centro (vizinho mais próximo). */
    fun rotate(im: OmrImage, deg: Double): OmrImage {
        if (abs(deg) < 1e-6) return im
        val w = im.w; val h = im.h
        val r = Math.toRadians(deg)
        val c = cos(r); val s = sin(r)
        val cx = w / 2.0; val cy = h / 2.0
        val out = BooleanArray(w * h)
        for (y in 0 until h) {
            val dy = y - cy
            for (x in 0 until w) {
                val dx = x - cx
                val sx = (cx + dx * c + dy * s).roundToInt()
                val sy = (cy - dx * s + dy * c).roundToInt()
                if (sx in 0 until w && sy in 0 until h && im.ink[sy * w + sx]) out[y * w + x] = true
            }
        }
        return OmrImage(w, h, out)
    }

    /** Endireita a imagem se estiver torta mais de 0,15°. Devolve a imagem (a mesma, se já estava reta) e o ângulo corrigido. */
    fun deskew(im: OmrImage): Pair<OmrImage, Double> {
        val m = metrics(im)
        val thin = thinMask(im, m.thickness + 2)
        val a = skewDegrees(im, thin)
        return if (abs(a) < 0.15) im to 0.0 else rotate(im, -a) to a
    }

    private class Line(val y: Double, val thick: Int, val x0: Int, val x1: Int, val mass: Int)

    /**
     * Pautas da página. Ao contrário do método por "maior trecho de tinta da linha", aqui a linha
     * só vale se for tinta fina, e o espaçamento tem de bater com a medida global da página.
     */
    fun findStaves(im: OmrImage, m0: Metrics? = null): List<Omr.Staff> {
        val w = im.w; val h = im.h
        val m = m0 ?: metrics(im)
        val thin = thinMask(im, m.thickness + 2)
        // perfil: quantos pixels finos em cada linha
        val prof = IntArray(h)
        for (y in 0 until h) { var c = 0; val o = y * w; for (x in 0 until w) if (thin[o + x]) c++; prof[y] = c }
        val sorted = prof.sortedDescending()
        val hi = sorted[min(sorted.size - 1, max(4, h / 160))].toDouble()   // ~topo robusto
        val thr = max(0.30 * hi, 0.07 * w)
        val cands = ArrayList<Line>()
        var y = 0
        while (y < h) {
            if (prof[y] >= thr) {
                var y2 = y
                while (y2 + 1 < h && prof[y2 + 1] >= thr) y2++
                var sw = 0.0; var sm = 0
                for (k in y..y2) { sw += k.toDouble() * prof[k]; sm += prof[k] }
                val yc = if (sm > 0) sw / sm else (y + y2) / 2.0
                var x0 = w; var x1 = 0
                for (k in y..y2) { val o = k * w; for (x in 0 until w) if (thin[o + x]) { if (x < x0) x0 = x; if (x > x1) x1 = x } }
                cands.add(Line(yc, y2 - y + 1, x0, x1, sm))
                y = y2 + 1
            } else y++
        }
        if (cands.size < 5) return emptyList()
        // agrupa 5 linhas com espaçamento parecido e compatível com a medida da página
        val out = ArrayList<Omr.Staff>()
        var i = 0
        while (i + 4 < cands.size) {
            val d = DoubleArray(4) { cands[i + it + 1].y - cands[i + it].y }
            val mn = d.min(); val mx = d.max()
            val avg = d.average()
            val okShape = mn >= 3.0 && mx / mn <= 1.45
            val okGlobal = avg >= 0.62 * m.space && avg <= 1.6 * m.space
            val okThick = (0..4).all { cands[i + it].thick <= max(3.0, avg * 0.55) }
            if (okShape && okGlobal && okThick) {
                val xs0 = (0..4).map { cands[i + it].x0 }.sorted()
                val xs1 = (0..4).map { cands[i + it].x1 }.sorted()
                val th = (0..4).map { cands[i + it].thick }.sorted()[2]
                out.add(Omr.Staff(DoubleArray(5) { cands[i + it].y }, xs0[2], xs1[2], th))
                i += 5
            } else i++
        }
        return out
    }

    /** Um sinal de grupo à esquerda do sistema: chave de piano ('C') ou colchete ('['), e quais pautas ele abraça. */
    class Group(val first: Int, val last: Int, val kind: Char)

    /**
     * Chaves e colchetes na margem esquerda. Cada sinal é um borrão de tinta à esquerda das pautas,
     * e as pautas que ele abraça saem do alto e do pé desse borrão — é assim que se sabe que
     * duas pautas são um piano e as outras duas são um coro.
     */
    fun groups(im: OmrImage, staves: List<Omr.Staff>): List<Group> {
        if (staves.size < 2) return emptyList()
        val w = im.w; val h = im.h
        val sp = staves.map { it.space }.sorted()[staves.size / 2]
        val x0 = staves.minOf { it.x0 }
        val xa = max(0, (x0 - 4.5 * sp).roundToInt())
        val xb = (x0 - 0.3 * sp).roundToInt()
        if (xb <= xa + 1) return emptyList()
        val zw = xb - xa + 1
        val yA = max(0, (staves.first().top - 2 * sp).roundToInt())
        val yB = min(h - 1, (staves.last().bottom + 2 * sp).roundToInt())
        val zh = yB - yA + 1
        val sub = BooleanArray(zw * zh)
        for (y in 0 until zh) for (x in 0 until zw) sub[y * zw + x] = im.ink[(yA + y) * w + xa + x]
        // componentes do recorte
        val lab = IntArray(zw * zh)
        var n = 0
        var stk = IntArray(1 shl 14)
        val out = ArrayList<Group>()
        for (s0 in 0 until zw * zh) {
            if (!sub[s0] || lab[s0] != 0) continue
            n++
            var sp2 = 0
            stk[sp2++] = s0; lab[s0] = n
            var y0 = zh; var y1 = 0; var cx0 = zw; var cx1 = 0; var area = 0
            val leftOf = IntArray(zh) { Int.MAX_VALUE }
            val rightOf = IntArray(zh) { -1 }
            while (sp2 > 0) {
                val p = stk[--sp2]; val x = p % zw; val y = p / zw
                area++
                if (y < y0) y0 = y; if (y > y1) y1 = y
                if (x < cx0) cx0 = x; if (x > cx1) cx1 = x
                if (x < leftOf[y]) leftOf[y] = x
                if (x > rightOf[y]) rightOf[y] = x
                for (dy in -1..1) { val yy = y + dy; if (yy < 0 || yy >= zh) continue
                    for (dx in -1..1) { val xx = x + dx; if (xx < 0 || xx >= zw) continue
                        val q = yy * zw + xx
                        if (sub[q] && lab[q] == 0) { lab[q] = n; if (sp2 >= stk.size) stk = stk.copyOf(stk.size * 2); stk[sp2++] = q } } }
            }
            val top = (yA + y0).toDouble(); val bot = (yA + y1).toDouble()
            if (bot - top < 1.5 * sp) continue
            val first = staves.indexOfFirst { abs(it.top - top) <= 0.9 * sp }
            val last = staves.indexOfLast { abs(it.bottom - bot) <= 0.9 * sp }
            if (first < 0 || last <= first) continue
            // chave de piano: a barriga joga o lado esquerdo para fora no meio da altura
            val lefts = (y0..y1).map { leftOf[it] }.filter { it != Int.MAX_VALUE }
            val thicks = (y0..y1).mapNotNull { if (leftOf[it] == Int.MAX_VALUE) null else rightOf[it] - leftOf[it] + 1 }
            if (lefts.isEmpty()) continue
            val range = lefts.max() - lefts.min()
            val tMed = thicks.sorted()[thicks.size / 2]
            if (range < 0.5 * sp) continue                     // reto: é colchete ou barra, tratados em [layout]
            if (tMed > 1.2 * sp) continue
            out.add(Group(first, last, 'C'))
        }
        return out.sortedBy { it.first }
    }

    /** Leitura da clave de uma pauta. [conf] = folga até a segunda hipótese; abaixo de ~1,0 é palpite. */
    class ClefRead(val clef: Char, val conf: Double, val right: Double, val height: Double, val top: Double, val dots: Int)

    private fun sq(v: Double) = v * v

    /**
     * Clave pelo desenho, medido contra as cinco linhas.
     *
     * O que separa as três: a de Sol tem ~7 espaços de altura e começa acima da linha de cima;
     * a de Fá tem ~3,3 espaços, começa na linha de cima e leva dois pontos à direita;
     * a de Dó tem 4 espaços e cobre a pauta exatamente. O teste antigo ("desce abaixo da pauta?")
     * errava toda vez que a cauda da clave de Sol saía cortada na digitalização.
     */
    fun clef(clean: BooleanArray, w: Int, h: Int, s: Omr.Staff): ClefRead {
        val sp = s.space
        val xa = max(0, (s.x0 - 0.7 * sp).roundToInt())
        val xb = min(w - 1, (s.x0 + 5.5 * sp).roundToInt())
        val ya = max(0, (s.top - 3.4 * sp).roundToInt())
        val yb = min(h - 1, (s.bottom + 3.4 * sp).roundToInt())
        if (xb <= xa + 2 || yb <= ya + 2) return ClefRead('?', 0.0, s.x0.toDouble(), 0.0, 0.0, 0)
        val zw = xb - xa + 1; val zh = yb - ya + 1
        val sub = BooleanArray(zw * zh)
        for (y in 0 until zh) for (x in 0 until zw) sub[y * zw + x] = clean[(ya + y) * w + xa + x]
        val lab = IntArray(zw * zh)
        var n = 0
        var stk = IntArray(1 shl 14)
        class Box(var x0: Int, var y0: Int, var x1: Int, var y1: Int, var area: Int)
        val boxes = ArrayList<Box>()
        for (s0 in 0 until zw * zh) {
            if (!sub[s0] || lab[s0] != 0) continue
            n++
            var p = 0; stk[p++] = s0; lab[s0] = n
            val b = Box(zw, zh, 0, 0, 0)
            while (p > 0) {
                val q = stk[--p]; val x = q % zw; val y = q / zw
                b.area++
                if (x < b.x0) b.x0 = x; if (x > b.x1) b.x1 = x
                if (y < b.y0) b.y0 = y; if (y > b.y1) b.y1 = y
                for (dy in -1..1) { val yy = y + dy; if (yy < 0 || yy >= zh) continue
                    for (dx in -1..1) { val xx = x + dx; if (xx < 0 || xx >= zw) continue
                        val r = yy * zw + xx
                        if (sub[r] && lab[r] == 0) { lab[r] = n; if (p >= stk.size) stk = stk.copyOf(stk.size * 2); stk[p++] = r } } }
            }
            boxes.add(b)
        }
        // em unidades de espaço de pauta, com x a partir do início da pauta e y a partir da linha de cima
        class G(var x0: Double, var y0: Double, var x1: Double, var y1: Double, var area: Double)
        val zTop = (ya - s.top) / sp; val zBot = (yb - s.top) / sp
        val gl = boxes.map { G((it.x0 + xa - s.x0) / sp, (it.y0 + ya - s.top) / sp, (it.x1 + xa - s.x0) / sp, (it.y1 + ya - s.top) / sp, it.area / (sp * sp)) }
            // fora: poeira, a barra vertical de abertura (fina e comprida) e o que entra cortado pela
            // borda do recorte (a chave do piano, a pauta de cima) — clave nenhuma encosta na borda
            .filter { it.area >= 0.06 && it.y1 - it.y0 <= 9.0 && it.x1 >= -0.65 && it.x0 >= -0.65 &&
                      !(it.x1 - it.x0 <= 0.42 && it.y1 - it.y0 >= 3.0) &&
                      it.y0 > zTop + 0.12 && it.y1 < zBot - 0.12 }
            .sortedBy { it.x0 }
        if (gl.isEmpty()) return ClefRead('?', 0.0, s.x0.toDouble(), 0.0, 0.0, 0)
        // junta pedaços da mesma figura (a remoção das linhas corta a clave em dois ou três)
        // a clave tem de estar em cima da pauta, não no texto que passa por cima dela
        fun onStaff(a: G) = min(a.y1, 4.2) - max(a.y0, -0.2) >= 1.0
        val start = gl.firstOrNull { it.x0 <= 3.2 && it.area >= 0.5 && onStaff(it) } ?: return ClefRead('?', 0.0, s.x0.toDouble(), 0.0, 0.0, 0)
        val g = G(start.x0, start.y0, start.x1, start.y1, start.area)
        var grew = true
        while (grew) {
            grew = false
            for (o in gl) {
                if (o === start) continue
                if (o.x0 > g.x1 + 0.3 || o.x1 < g.x0 - 0.3) continue
                if (o.y0 > g.y1 + 0.45 || o.y1 < g.y0 - 0.45) continue     // só pedaços colados: a linha da pauta corta a clave em dois
                if (o.area < 0.35 && o.x0 > g.x1 - 0.25) continue          // pontinho da clave de Fá fica de fora
                if (o.x1 <= g.x1 && o.y0 >= g.y0 && o.y1 <= g.y1) continue
                val nx1 = max(g.x1, o.x1); val ny0 = min(g.y0, o.y0); val ny1 = max(g.y1, o.y1)
                if (nx1 - g.x0 > 4.2 || ny1 - ny0 > 8.5 || ny0 < -2.3 || ny1 > 6.3) continue
                g.x0 = min(g.x0, o.x0); g.x1 = nx1; g.y0 = ny0; g.y1 = ny1; g.area += o.area
                grew = true
            }
        }
        val hh = g.y1 - g.y0
        val top = g.y0
        val bot = g.y1 - 4.0        // em relação à linha de baixo
        val dotList = gl.filter { it.area in 0.06..0.55 && it.x1 - it.x0 <= 0.9 && it.y1 - it.y0 <= 0.9 && it.x0 in g.x1 - 0.35..g.x1 + 1.3 && it.y0 > -0.6 && it.y1 < 4.6 }
        val dots = dotList.size
        val rightSp = max(g.x1, dotList.maxOfOrNull { it.x1 } ?: g.x1)
        val sG = sq((hh - 6.9) / 1.7) + sq((top + 1.25) / 0.75)
        val sF = sq((hh - 3.3) / 0.65) + sq((top - 0.25) / 0.55) + (if (dots >= 1) -0.9 else 0.9)
        val sC = sq((hh - 4.0) / 0.5) + sq(top / 0.45) + sq(bot / 0.45) + (if (g.x1 - g.x0 < 1.6) 2.0 else 0.0)
        val all = listOf('G' to sG, 'F' to sF, 'C' to sC).sortedBy { it.second }
        val best = all[0]
        val conf = all[1].second - best.second
        val clef = if (best.second > 7.0) '?' else best.first
        return ClefRead(clef, conf, (rightSp * sp + s.x0), hh, top, dots)
    }

    /** Armadura de clave lida logo depois da clave. [fits] diz se os acidentes caem nos degraus certos para essa clave. */
    class KeyRead(val fifths: Int, val right: Double, val count: Int, val fits: Boolean)

    private val FLAT_POS = intArrayOf(4, 7, 3, 6, 2, 5, 1)      // si, mi, lá, ré, sol, dó, fá em clave de Sol (meios-espaços desde a linha de baixo)
    private val SHARP_POS = intArrayOf(8, 5, 9, 6, 3, 7, 4)

    /** Degraus esperados da armadura nesta clave (a de Fá fica dois degraus abaixo da de Sol). */
    fun keyPositions(clef: Char, sharp: Boolean, n: Int): IntArray {
        val base = if (sharp) SHARP_POS else FLAT_POS
        val shift = when (clef) { 'F' -> -2; 'C' -> -1; else -> 0 }
        return IntArray(min(n, 7)) { base[it] + shift }
    }

    /**
     * Armadura: os bemóis ou sustenidos entre a clave e a primeira nota.
     * Conta por figura (não por traço solto, que era o que fazia 2 bemóis virarem 1) e confere
     * se cada um está no degrau que a clave manda.
     */
    fun keySignature(clean: BooleanArray, w: Int, h: Int, s: Omr.Staff, clefRight: Double, clef: Char): KeyRead {
        val sp = s.space
        val xa = max(0, (clefRight + 0.12 * sp).roundToInt())
        val xb = min(w - 1, (clefRight + 9.0 * sp).roundToInt())
        val ya = max(0, (s.top - 2.0 * sp).roundToInt())
        val yb = min(h - 1, (s.bottom + 2.0 * sp).roundToInt())
        if (xb <= xa + 2) return KeyRead(0, clefRight, 0, true)
        val zw = xb - xa + 1; val zh = yb - ya + 1
        val sub = BooleanArray(zw * zh)
        for (y in 0 until zh) for (x in 0 until zw) sub[y * zw + x] = clean[(ya + y) * w + xa + x]
        val lab = IntArray(zw * zh)
        var stk = IntArray(1 shl 13)
        var n = 0
        class A(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val cells: List<Int>)
        val list = ArrayList<A>()
        for (s0 in 0 until zw * zh) {
            if (!sub[s0] || lab[s0] != 0) continue
            n++
            var p = 0; stk[p++] = s0; lab[s0] = n
            var x0 = zw; var y0 = zh; var x1 = 0; var y1 = 0
            val cells = ArrayList<Int>()
            while (p > 0) {
                val q = stk[--p]; val x = q % zw; val y = q / zw
                cells.add(q)
                if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y
                for (dy in -1..1) { val yy = y + dy; if (yy < 0 || yy >= zh) continue
                    for (dx in -1..1) { val xx = x + dx; if (xx < 0 || xx >= zw) continue
                        val r = yy * zw + xx
                        if (sub[r] && lab[r] == 0) { lab[r] = n; if (p >= stk.size) stk = stk.copyOf(stk.size * 2); stk[p++] = r } } }
            }
            val bw = (x1 - x0 + 1) / sp; val bh = (y1 - y0 + 1) / sp
            if (bh < 1.4 || bh > 3.8 || bw < 0.3 || bw > 1.28) continue
            if (cells.size < 0.18 * sp * sp) continue
            list.add(A((x0 + xa).toDouble(), (y0 + ya).toDouble(), (x1 + xa).toDouble(), (y1 + ya).toDouble(), cells))
        }
        if (list.isEmpty()) return KeyRead(0, clefRight, 0, true)
        val sortd = list.sortedBy { it.x0 }
        // a armadura é uma fila sem buracos logo depois da clave
        val run = ArrayList<A>()
        for (a in sortd) {
            // dois sinais empilhados no mesmo x são o sinal de compasso, não armadura
            if (run.any { min(it.x1, a.x1) - max(it.x0, a.x0) > 0.4 * (a.x1 - a.x0) }) break
            if (run.isEmpty()) { if (a.x0 > clefRight + 1.6 * sp) break; run.add(a) }
            else { if (a.x0 - run.last().x1 > 1.1 * sp) break; run.add(a) }
        }
        if (run.isEmpty()) return KeyRead(0, clefRight, 0, true)
        // bemol = um traço vertical só; sustenido/bequadro = dois
        var sharps = 0
        val kinds = ArrayList<Int>()        // -1 bemol, 0 bequadro, 1 sustenido
        for (a in run) {
            val x0 = a.x0.toInt(); val x1 = a.x1.toInt()
            val hgt = a.y1 - a.y0 + 1
            val colsWithTall = ArrayList<Int>()
            val colTop = HashMap<Int, Double>()
            for (x in x0..x1) {
                var best = 0; var bestEnd = 0; var r = 0
                for (y in a.y0.toInt()..a.y1.toInt()) {
                    if (lab[(y - ya) * zw + (x - xa)] != 0 && clean[y * w + x]) { r++; if (r > best) { best = r; bestEnd = y } } else r = 0
                }
                if (best >= 0.58 * hgt) { colsWithTall.add(x); colTop[x] = bestEnd - best + 1.0 }
            }
            val groups = ArrayList<MutableList<Int>>()
            for (x in colsWithTall) if (groups.isEmpty() || x - groups.last().last() > 1) groups.add(mutableListOf(x)) else groups.last().add(x)
            val kind = when {
                groups.size <= 1 -> -1
                else -> {
                    val t0 = groups[0].mapNotNull { colTop[it] }.average()
                    val t1 = groups[1].mapNotNull { colTop[it] }.average()
                    if (abs(t0 - t1) > 0.35 * sp) 0 else 1
                }
            }
            kinds.add(kind)
            if (kind == 1) sharps++
        }
        val nAcc = kinds.count { it != 0 }
        val sharp = sharps * 2 >= nAcc
        // A armadura é uma sequência fixa de degraus. Conta só enquanto cada acidente cair
        // no degrau que a clave manda: o primeiro que destoar já é acidente de nota, não armadura.
        val want = keyPositions(clef, sharp, 7)
        var acc = 0
        var right = clefRight
        var strayed = false
        for ((i, a) in run.withIndex()) {
            if (kinds[i] == 0) { right = max(right, a.x1); continue }     // bequadro de cancelamento
            val anchor = if (kinds[i] == -1) a.y1 - 0.35 * (a.y1 - a.y0) else (a.y0 + a.y1) / 2
            val pos = (s.bottom - anchor) / (sp / 2)
            if (acc < want.size && abs(pos - want[acc]) <= 0.85) { acc++; right = max(right, a.x1) }
            else { strayed = true; break }
        }
        val fifths = if (acc == 0) 0 else if (sharp) acc else -acc
        return KeyRead(fifths, right, acc, !strayed)
    }

    /** Colunas onde a tinta atravessa a pauta inteira: barras de compasso (e hastes muito longas). */
    fun barXs(im: OmrImage, s: Omr.Staff): List<Double> {
        val w = im.w; val h = im.h
        val y0 = s.top.roundToInt().coerceIn(0, h - 1)
        val y1 = s.bottom.roundToInt().coerceIn(0, h - 1)
        val out = ArrayList<Double>()
        var run = -1
        var x = max(0, s.x0)
        val xe = min(w - 1, s.x1)
        while (x <= xe + 1) {
            var full = x <= xe
            if (full) { var miss = 0; for (y in y0..y1) if (!im.ink[y * w + x]) { miss++; if (miss > 1) { full = false; break } } }
            if (full) { if (run < 0) run = x }
            else if (run >= 0) { if (out.isEmpty() || (run + x - 1) / 2.0 - out.last() > 1.2 * s.space) out.add((run + x - 1) / 2.0); run = -1 }
            x++
        }
        return out
    }

    /** Sistemas (pautas tocadas juntas) e os sinais de grupo de cada um. */
    class Layout(val systemOf: IntArray, val systems: Int, val groups: List<Group>)

    /**
     * Junta as pautas em sistemas pela tinta vertical da margem esquerda (barra inicial, colchete, chave)
     * e diz quais pautas cada chave ou colchete abraça.
     */
    fun layout(im: OmrImage, staves: List<Omr.Staff>): Layout {
        val n = staves.size
        if (n == 0) return Layout(IntArray(0), 0, emptyList())
        val w = im.w; val h = im.h
        val link = BooleanArray(max(0, n - 1))
        val runCols = HashMap<Int, MutableList<Int>>()
        // cada par de pautas vizinhas é olhado na margem dele: pautas de sistemas diferentes começam em x diferentes
        for (i in 0 until n - 1) {
            val a = staves[i]; val b = staves[i + 1]
            val sp = (a.space + b.space) / 2
            if (b.top - a.bottom > 30 * sp) continue
            val x0 = min(a.x0, b.x0)
            val xa = max(0, (x0 - 4.5 * sp).roundToInt())
            val xb = min(w - 1, (x0 + 0.8 * sp).roundToInt())
            val yA = max(0, (a.top - 2 * sp).roundToInt())
            val yB = min(h - 1, (b.bottom + 2 * sp).roundToInt())
            for (x in xa..xb) {
                var y = yA
                while (y <= yB) {
                    if (!im.ink[y * w + x]) { y++; continue }
                    var e = y; while (e + 1 <= yB && im.ink[(e + 1) * w + x]) e++
                    // a tinta liga as duas pautas: ou cobre as duas inteiras, ou atravessa o vão entre elas
                    if (e - y + 1 >= 1.5 * sp && y <= a.bottom - 0.2 * sp && e >= b.top + 0.2 * sp) link[i] = true
                    if (e - y + 1 >= 1.5 * sp && a.top >= y - 0.7 * sp && b.bottom <= e + 0.7 * sp) {
                        link[i] = true
                        val inside = (0 until n).filter { staves[it].top >= y - 0.7 * sp && staves[it].bottom <= e + 0.7 * sp }
                        if (inside.size >= 2 && abs(staves[inside.first()].top - y) <= 0.9 * sp && abs(staves[inside.last()].bottom - e) <= 0.9 * sp)
                            runCols.getOrPut(inside.first() * 64 + inside.last()) { ArrayList() }.add(x)
                    }
                    y = e + 1
                }
            }
        }
        // Segunda pista, para quando o gravador não desenhou linha nenhuma ligando os grupos:
        // se os vãos sem tinta se dividem em "curtos" e "longos" bem separados, os curtos são do mesmo sistema.
        run {
            val gapOf = DoubleArray(max(0, n - 1)) { (staves[it + 1].top - staves[it].bottom) / ((staves[it].space + staves[it + 1].space) / 2) }
            val un = (0 until n - 1).filter { !link[it] }
            if (un.size >= 2) {
                val s = un.map { gapOf[it] }.sorted()
                var thr = -1.0
                for (k in 0 until s.size - 1) if (s[k + 1] >= 1.8 * s[k] && s[k] <= 14.0) thr = (s[k] + s[k + 1]) / 2
                if (thr > 0) for (i in un) if (gapOf[i] < thr) link[i] = true
            }
        }
        val sysOf = IntArray(n)
        var sys = 0
        for (i in 1 until n) { if (!link[i - 1]) sys++; sysOf[i] = sys }
        val gs = ArrayList<Group>()
        for (s in 0..sys) {
            val idx = (0 until n).filter { sysOf[it] == s }
            if (idx.size < 2) continue
            for (g in groups(im, idx.map { staves[it] })) gs.add(Group(idx[g.first], idx[g.last], g.kind))
        }
        for ((k, cols) in runCols) {
            val srt = cols.sorted()
            var best = 1; var cur = 1
            for (i in 1 until srt.size) { if (srt[i] - srt[i - 1] <= 1) cur++ else cur = 1; if (cur > best) best = cur }
            val f = k / 64; val l = k % 64
            val sp = staves[f].space
            if (best >= max(2.0, 0.25 * sp) && l > f && gs.none { it.first == f && it.last == l }) gs.add(Group(f, l, '['))
        }
        return Layout(sysOf, sys + 1, gs.sortedWith(compareBy({ it.first }, { it.last })))
    }
}
