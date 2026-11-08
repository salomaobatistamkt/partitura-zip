package app.partitura.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Arranjo automático sobre uma melodia com acordes (a primeira parte da partitura):
 * acompanhamento de piano em vários estilos e vozes extras (coral SATB, terças, sextas, baixo).
 * Tudo é regra musical determinística (condução de vozes, sem paralelas de 5ªs/8ªs) — não é IA generativa.
 */
object Arrange {
    class Style(val id: String, val label: String, val note: String)
    val PIANO_STYLES = listOf(
        Style("bloco", "Acordes sustentados", "acorde na direita e baixo seguram o acorde inteiro"),
        Style("marcado", "Acordes marcados", "um acorde e um baixo a cada pulso"),
        Style("arpejo", "Arpejo", "esquerda em arpejo, direita sustentada"),
        Style("balada", "Balada", "baixo seguro e acorde quebrado na direita"),
        Style("valsa", "Baixo e acorde", "baixo no tempo forte, acorde nos outros (valsa, marcha)"),
        Style("colcheias", "Movido", "acordes repetidos em colcheias com baixo marcado"),
    )

    class VoiceMode(val id: String, val label: String, val note: String, val insts: List<String>)
    val VOICE_MODES = listOf(
        VoiceMode("satb", "Coral: contralto, tenor e baixo", "3 vozes novas montadas sobre os acordes, sem quintas/oitavas paralelas", listOf("contralto", "tenor", "bassvoice")),
        VoiceMode("terca", "Segunda voz em terças", "diatônica, abaixo da melodia", listOf("contralto")),
        VoiceMode("sexta", "Segunda voz em sextas", "diatônica, abaixo da melodia", listOf("contralto")),
        VoiceMode("baixo", "Linha de baixo", "fundamentais dos acordes, um som por pulso", listOf("bass")),
    )

    class Span(val bar: Int, val s: Int, val e: Int, val h: Harmony)
    private class Ev(val s: Int, val e: Int, val p: List<Int>)
    private class Mel(val bar: Int, val pos: Int, val note: Note)

    private fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    /** Cópia com divisões múltiplas de 4 (o arranjo trabalha em colcheias e semicolcheias inteiras). */
    fun rescaled(s0: Score): Score {
        val s = s0.copyDeep()
        val k = 4 / gcd(s.divisions, 4)
        if (k == 1) return s
        for (p in s.parts) for (m in p.measures) {
            m.notes.replaceAll { it.copy(duration = it.duration * k) }
            m.harmony.replaceAll { it.copy(at = it.at * k) }
        }
        s.divisions *= k
        return s
    }

    private fun fullBar(s: Score) = s.divisions * 4 * s.beats / s.beatType

    private fun lenOf(m: Measure, full: Int): Int {
        val l = m.notes.filter { !it.chord }.groupBy { it.voice }.values.maxOfOrNull { v -> v.sumOf { it.duration } } ?: full
        return if (l <= 0) full else l
    }

    fun inferMinor(s: Score): Boolean {
        val rel = Math.floorMod(7 * s.fifths, 12).let { (it + 9) % 12 }
        val last = s.parts.firstOrNull()?.measures?.flatMap { it.harmony }?.lastOrNull()
        if (last != null) return last.rootPc == rel && last.kind.startsWith("minor")
        val n = s.parts.firstOrNull()?.measures?.flatMap { it.notes }?.lastOrNull { it.pitch != null }?.pitch ?: return false
        return Math.floorMod(n.midi, 12) == rel
    }

    private fun tonicHarmony(s: Score, key: TonalKey): Harmony {
        val (st, al) = key.spellPc(key.tonicPc)
        return Harmony(0, st, al, if (key.minor) "minor" else "major")
    }

    /** Trechos de acorde (em divisões a partir do início do compasso). Compassos incompletos (anacruse) ficam sem acompanhamento. */
    fun spans(s: Score): List<Span> {
        val p = s.parts.firstOrNull() ?: return emptyList()
        val full = fullBar(s)
        val key = TonalKey(s.fifths, inferMinor(s))
        var carry: Harmony = p.measures.flatMap { it.harmony }.firstOrNull() ?: tonicHarmony(s, key)
        val res = ArrayList<Span>()
        for ((bi, m) in p.measures.withIndex()) {
            val len = lenOf(m, full)
            val hs = m.harmony.sortedBy { it.at }.filter { it.at in 0 until len }
            if (len < full) { hs.lastOrNull()?.let { carry = it }; continue }
            val firstAt = hs.firstOrNull()?.at ?: len
            if (firstAt > 0) res += Span(bi, 0, firstAt, carry)
            for ((i, h) in hs.withIndex()) {
                val e = hs.getOrNull(i + 1)?.at ?: len
                if (e > h.at) res += Span(bi, h.at, e, h)
                carry = h
            }
        }
        return res
    }

    // ------------------------------------------------------------------ piano

    private fun pulses(s: Score): List<IntArray> {
        val bd = s.divisions * 4 / s.beatType
        val gs = Grid.accentGroups(s.beats, s.beatType)
        return if (gs.size >= 2) { var p = 0; gs.map { val a = p; p += it * bd; intArrayOf(a, p) } }
        else (0 until s.beats).map { intArrayOf(it * bd, (it + 1) * bd) }
    }

    private fun bassOf(pc: Int): Int { var x = 36; while (Math.floorMod(x, 12) != pc) x++; return x }

    private fun voicing(h: Harmony, prev: IntArray?): IntArray {
        var pcs = h.pcs(); if (pcs.size > 4) pcs = pcs.filterIndexed { i, _ -> i != 2 }
        var best: IntArray? = null; var bc = 1e9
        for (inv in pcs.indices) {
            val order = pcs.drop(inv) + pcs.take(inv)
            for (base in 53..66) {
                if (Math.floorMod(base, 12) != order[0]) continue
                val n = IntArray(order.size); n[0] = base
                for (k in 1 until order.size) { var x = (n[k - 1] / 12) * 12 + order[k]; if (x <= n[k - 1]) x += 12; n[k] = x }
                if (n.last() > 72) continue
                var c = if (prev == null) abs(n.average() - 62.0) else {
                    var d = 0.0; for (i in 0 until min(n.size, prev.size)) d += abs(n[i] - prev[i]); d + abs(n.size - prev.size) * 2.0
                }
                if (n.size >= 3 && n[1] - n[0] > 7 && n[2] - n[1] > 7) c += 3.0
                if (c < bc) { bc = c; best = n }
            }
        }
        return best ?: IntArray(pcs.size) { 60 + it * 4 }
    }

    private fun clip(a: Int, b: Int, x: Int, y: Int): IntArray? { val s = max(a, x); val e = min(b, y); return if (e > s) intArrayOf(s, e) else null }

    private fun lhMarcado(s: Score, span: Span, bass: Int, out: MutableList<Ev>) {
        for ((i, p) in pulses(s).withIndex()) {
            val c = clip(span.s, span.e, p[0], p[1]) ?: continue
            out += Ev(c[0], c[1], listOf(if (i % 2 == 0) bass else bass + 7))
        }
    }

    fun addPiano(s0: Score, styleId: String): Score {
        val s = rescaled(s0)
        require(s.parts.isNotEmpty()) { "Não há melodia." }
        val full = fullBar(s)
        val lens = s.parts[0].measures.map { lenOf(it, full) }
        val key = TonalKey(s.fifths, inferMinor(s))
        val gi = Edit.addInstrument(s, Instruments.byId("piano")!!, lens)
        val nBars = lens.size
        val rh = Array(nBars) { ArrayList<Ev>() }; val lh = Array(nBars) { ArrayList<Ev>() }
        val bd = s.divisions * 4 / s.beatType
        val sub = if (s.beatType <= 4) max(1, bd / 2) else bd
        val pl = pulses(s)
        var prev: IntArray? = null
        for (sp in spans(s)) {
            val v = voicing(sp.h, prev); prev = v
            val bass = bassOf(sp.h.bassPc); val iv1 = sp.h.intervals().getOrElse(1) { 4 }
            val R = rh[sp.bar]; val L = lh[sp.bar]
            val vl = v.toList()
            when (styleId) {
                "marcado" -> { for (p in pl) clip(sp.s, sp.e, p[0], p[1])?.let { R += Ev(it[0], it[1], vl) }; lhMarcado(s, sp, bass, L) }
                "arpejo" -> {
                    R += Ev(sp.s, sp.e, vl)
                    for (p in pl) {
                        val c = clip(sp.s, sp.e, p[0], p[1]) ?: continue
                        val n = max(1, (p[1] - p[0]) / sub)
                        val seq = when (n) { 1 -> listOf(bass); 2 -> listOf(bass, bass + 7); 3 -> listOf(bass, bass + 7, bass + 12 + iv1); else -> listOf(bass, bass + 7, bass + 12 + iv1, bass + 7) }
                        var k = 0; var t = p[0]
                        while (t < p[1]) {
                            val te = min(t + sub, p[1])
                            clip(c[0], c[1], t, te)?.let { L += Ev(it[0], it[1], listOf(seq[k % seq.size])) }
                            k++; t = te
                        }
                    }
                }
                "balada" -> {
                    L += Ev(sp.s, sp.e, listOf(bass))
                    val seq = if (v.size >= 4) listOf(v[0], v[1], v[2], v[3], v[2], v[1]) else listOf(v[0], v[1], v[2 % v.size], v[1])
                    var k = 0; var t = sp.s
                    while (t < sp.e) { val te = min(t + sub, sp.e); R += Ev(t, te, listOf(seq[k % seq.size])); k++; t = te }
                }
                "valsa" -> {
                    for ((i, p) in pl.withIndex()) {
                        val c = clip(sp.s, sp.e, p[0], p[1]) ?: continue
                        val low = if (pl.size % 2 == 1) i == 0 else i % 2 == 0
                        if (low) L += Ev(c[0], c[1], listOf(if (i == 0 || pl.size % 2 == 1) bass else bass + 7)) else R += Ev(c[0], c[1], vl)
                    }
                }
                "colcheias" -> {
                    var t = sp.s
                    while (t < sp.e) { val te = min(t + sub, sp.e); R += Ev(t, te, vl); t = te }
                    lhMarcado(s, sp, bass, L)
                }
                else -> { R += Ev(sp.s, sp.e, vl); L += Ev(sp.s, sp.e, listOf(bass)) }
            }
        }
        for (b in 0 until nBars) {
            emit(s, s.parts[gi].measures[b], rh[b], lens[b], key)
            emit(s, s.parts[gi + 1].measures[b], lh[b], lens[b], key)
        }
        return s
    }

    private val SIZES = intArrayOf(64, 48, 32, 24, 16, 12, 8, 6, 4, 3, 2, 1)
    private fun split(len: Int): List<Int> { val o = ArrayList<Int>(); var r = len; for (d in SIZES) while (r >= d) { o += d; r -= d }; return o }

    private fun emit(s: Score, m: Measure, evs: List<Ev>, len: Int, key: TonalKey) {
        val out = ArrayList<Note>(); var pos = 0
        for (ev in evs.sortedBy { it.s }) {
            val st = max(ev.s, pos); val e = min(ev.e, len)
            if (e <= st) continue
            if (st > pos) for (d in split(st - pos)) out += Note(null, d)
            val ch = split(e - st)
            ch.forEachIndexed { i, d -> ev.p.sorted().forEachIndexed { k, p -> out += Note(key.spell(p), d, 1, k > 0, (i < ch.lastIndex), (i > 0)) } }
            pos = e
        }
        if (pos < len) for (d in split(len - pos)) out += Note(null, d)
        m.notes.clear(); m.notes.addAll(out)
    }

    // ------------------------------------------------------------------ vozes

    private fun melodyOf(s: Score): List<Mel> {
        val res = ArrayList<Mel>()
        for ((bi, m) in s.parts[0].measures.withIndex()) {
            var pos = 0
            for (n in m.notes) { if (n.chord || n.voice != 1) continue; res += Mel(bi, pos, n); pos += n.duration }
        }
        return res
    }

    private fun chordAt(spans: List<Span>, bar: Int, pos: Int): Harmony? =
        spans.firstOrNull { it.bar == bar && pos >= it.s && pos < it.e }?.h
            ?: spans.firstOrNull { it.bar > bar }?.h ?: spans.lastOrNull()?.h

    fun addVoices(s0: Score, modeId: String, lyrics: Boolean = true): Score {
        val mode = VOICE_MODES.firstOrNull { it.id == modeId } ?: error("Modo de vozes desconhecido: $modeId")
        val s = rescaled(s0)
        require(s.parts.isNotEmpty()) { "Não há melodia." }
        val full = fullBar(s)
        val lens = s.parts[0].measures.map { lenOf(it, full) }
        val key = TonalKey(s.fifths, inferMinor(s))
        val mel = melodyOf(s); val spans = spans(s)
        val before = s.parts.size
        val firsts = mode.insts.map { Edit.addInstrument(s, Instruments.byId(it)!!, lens) }
        fun fill(partIdx: Int, picks: Map<Int, Int?>, lyr: Boolean) {   // índice da melodia -> altura (null = pausa)
            val part = s.parts[partIdx]
            val per = Array(part.measures.size) { ArrayList<Note>() }
            for ((i, mn) in mel.withIndex()) {
                val p = picks[i]
                val n = mn.note
                per[mn.bar] += if (p == null || n.pitch == null) Note(null, n.duration)
                else Note(key.spell(p), n.duration, 1, false, n.tieStart, n.tieStop, if (lyr) n.lyric else null, n.syl)
            }
            for ((b, ns) in per.withIndex()) if (ns.isNotEmpty()) { part.measures[b].notes.clear(); part.measures[b].notes.addAll(ns) }
        }
        when (mode.id) {
            "terca", "sexta" -> {
                val down = if (mode.id == "terca") 2 else 5
                val picks = HashMap<Int, Int?>()
                for ((i, mn) in mel.withIndex()) {
                    val m = mn.note.pitch?.midi ?: continue
                    var d = (-14..35).filter { key.midi(it) == m || (key.minor && Math.floorMod(it, 7) == 6 && key.midi(it) + 1 == m) }.minByOrNull { abs(key.midi(it) - m) }
                    var t = if (d != null) key.midi(d - down) else m - (if (down == 2) 3 else 8)
                    while (t < 46) t += 12
                    while (t >= m) t -= 12
                    picks[i] = t
                }
                fill(firsts[0], picks, lyrics)
            }
            "baixo" -> {
                val out = Array(lens.size) { ArrayList<Ev>() }
                for (sp in spans) lhMarcado(s, sp, bassOf(sp.h.bassPc), out[sp.bar])
                for (b in out.indices) emit(s, s.parts[firsts[0]].measures[b], out[b], lens[b], key)
            }
            else -> {
                val sol = satb(mel, spans, key)
                fill(firsts[0], sol.mapValues { it.value?.a }, lyrics)
                fill(firsts[1], sol.mapValues { it.value?.t }, lyrics)
                fill(firsts[2], sol.mapValues { it.value?.b }, lyrics)
            }
        }
        // vozes graves escritas na altura real ganham clave de fá (senão ficam em dezenas de linhas suplementares)
        for (k in before until s.parts.size) {
            val ms = s.parts[k].measures.flatMap { it.notes }.mapNotNull { it.pitch?.midi }
            if (ms.isNotEmpty()) s.parts[k].clef = if (ms.average() < 57) 'F' else 'G'
        }
        // as vozes novas ficam logo abaixo da melodia
        val added = s.parts.subList(before, s.parts.size).toList()
        repeat(added.size) { s.parts.removeAt(s.parts.size - 1) }
        s.parts.addAll(1, added)
        return s
    }

    private class St(val a: Int, val t: Int, val b: Int)

    private fun cands(sm: Int, h: Harmony, wide: Boolean): List<St> {
        val pcs = h.pcs().toSet()
        val aLo = if (wide) 36 else 48; val tLo = if (wide) 31 else 43; val bLo = if (wide) 28 else 36
        val A = (aLo..74).filter { Math.floorMod(it, 12) in pcs && it < sm }
        val T = (tLo..67).filter { Math.floorMod(it, 12) in pcs }
        val B = (bLo..60).filter { Math.floorMod(it, 12) in pcs }
        val out = ArrayList<St>()
        for (a in A) for (t in T) { if (t >= a || a - t > 12) continue; for (b in B) if (b < t && sm - a <= 12) out += St(a, t, b) }
        return out
    }

    private fun local(sm: Int, st: St, h: Harmony, tonicPc: Int): Double {
        var c = 0.0
        c += max(0, 53 - st.a) * 2.0 + max(0, st.a - 74) * 4.0
        c += max(0, 48 - st.t) * 2.0 + max(0, st.t - 67) * 4.0
        c += max(0, 40 - st.b) * 2.0 + max(0, st.b - 60) * 4.0
        if (sm - st.a < 3) c += 1.5
        c += max(0, st.t - st.b - 19) * 1.5
        val pcs = setOf(Math.floorMod(sm, 12), Math.floorMod(st.a, 12), Math.floorMod(st.t, 12), Math.floorMod(st.b, 12))
        val ch = h.pcs()
        if (ch[0] !in pcs) c += 8.0
        if (ch.size > 1 && ch[1] !in pcs) c += 6.0
        if (ch.size > 3 && ch[3] !in pcs) c += 5.0
        if (Math.floorMod(st.b, 12) != h.bassPc) c += if (Math.floorMod(st.b, 12) == h.pcs().getOrElse(2) { -1 }) 3.5 else 1.5
        val lt = Math.floorMod(tonicPc - 1, 12)
        if (listOf(sm, st.a, st.t, st.b).count { Math.floorMod(it, 12) == lt } >= 2) c += 6.0
        return c
    }

    private fun sgn(x: Int) = if (x > 0) 1 else if (x < 0) -1 else 0

    private fun trans(ps: Int, p: St, cs: Int, c: St): Double {
        val pv = intArrayOf(ps, p.a, p.t, p.b); val cv = intArrayOf(cs, c.a, c.t, c.b)
        var cost = abs(c.a - p.a) + abs(c.t - p.t) + 0.5 * abs(c.b - p.b) + 0.0
        for (v in 1..2) { val d = abs(cv[v] - pv[v]); if (d > 5) cost += (d - 5) * 2.0 }
        for (i in 0..3) for (j in i + 1..3) {
            val pi = Math.floorMod(pv[i] - pv[j], 12); val ci = Math.floorMod(cv[i] - cv[j], 12)
            if (pi == ci && (pi == 7 || pi == 0)) {
                val mi = cv[i] != pv[i]; val mj = cv[j] != pv[j]
                if (mi && mj) cost += 1000.0
            }
        }
        return cost
    }

    private fun satb(mel: List<Mel>, spans: List<Span>, key: TonalKey): Map<Int, St?> {
        val res = HashMap<Int, St?>()
        var i = 0
        while (i < mel.size) {
            if (mel[i].note.pitch == null) { res[i] = null; i++; continue }
            var j = i
            while (j < mel.size && mel[j].note.pitch != null) j++
            val seg = (i until j).toList()
            val hs = seg.map { chordAt(spans, mel[it].bar, mel[it].pos) ?: Harmony(0, 'C', 0) }
            val cs = seg.mapIndexed { k, idx -> val m = mel[idx].note.pitch!!.midi; var c = cands(m, hs[k], false); if (c.isEmpty()) c = cands(m, hs[k], true); c }
            val ok = cs.all { it.isNotEmpty() }
            if (!ok) { for (idx in seg) res[idx] = null; i = j; continue }
            val sm = seg.map { mel[it].note.pitch!!.midi }
            var cost = DoubleArray(cs[0].size) { local(sm[0], cs[0][it], hs[0], key.tonicPc) }
            val from = ArrayList<IntArray>(); from += IntArray(cs[0].size)
            for (k in 1 until seg.size) {
                val nc = DoubleArray(cs[k].size); val fr = IntArray(cs[k].size)
                for (ci in cs[k].indices) {
                    var bst = Double.MAX_VALUE; var bj = 0
                    for (pj in cs[k - 1].indices) {
                        val v = cost[pj] + trans(sm[k - 1], cs[k - 1][pj], sm[k], cs[k][ci])
                        if (v < bst) { bst = v; bj = pj }
                    }
                    nc[ci] = bst + local(sm[k], cs[k][ci], hs[k], key.tonicPc); fr[ci] = bj
                }
                cost = nc; from += fr
            }
            var cur = cost.indices.minByOrNull { cost[it] } ?: 0
            for (k in seg.size - 1 downTo 0) { res[seg[k]] = cs[k][cur]; cur = from[k][cur] }
            i = j
        }
        return res
    }

    /** Conta quintas/oitavas paralelas entre as vozes de um grupo de partes (para teste e relatório). */
    fun parallels(s: Score, partIdx: List<Int>): Int {
        val seqs = partIdx.map { pi -> s.parts[pi].measures.flatMap { m -> m.notes.filter { !it.chord } }.map { it.pitch?.midi } }
        val n = seqs.minOf { it.size }; var cnt = 0
        for (k in 1 until n) for (i in seqs.indices) for (j in i + 1 until seqs.size) {
            val a0 = seqs[i][k - 1]; val a1 = seqs[i][k]; val b0 = seqs[j][k - 1]; val b1 = seqs[j][k]
            if (a0 == null || a1 == null || b0 == null || b1 == null) continue
            val p = Math.floorMod(a0 - b0, 12); val c = Math.floorMod(a1 - b1, 12)
            if (p == c && (p == 7 || p == 0) && a0 != a1 && b0 != b1) cnt++
        }
        return cnt
    }
}
