package app.partitura.core

import kotlin.math.abs

/** Sequência de acordes escrita à mão: lê o texto, descobre o tom, aplica na partitura e volta a virar texto. */
object Progression {
    class Parsed(val bars: List<List<Harmony>>, val bad: List<String>)

    /** Compassos separados por "|" ou por linha; acordes do mesmo compasso separados por espaço. "%" repete o compasso anterior. */
    fun parse(text: String): Parsed {
        val bars = ArrayList<List<Harmony>>(); val bad = ArrayList<String>()
        for (raw in text.replace("\r", "").split('|', '\n')) {
            val toks = raw.split(' ', '\t', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }
            if (toks.isEmpty()) continue
            if (toks == listOf("%")) { bars.lastOrNull()?.let { bars += it }; continue }
            val hs = ArrayList<Harmony>()
            for (t in toks) { val h = Chords.parse(t); if (h == null) bad += t else hs += h }
            if (hs.isNotEmpty()) bars += hs
        }
        return Parsed(bars, bad)
    }

    /** Pulsos (em ticks do [Grid]) onde começam os [k] acordes de um compasso: os mais próximos das divisões iguais, em início de grupo. */
    fun positions(g: Grid, k: Int): List<Int> {
        if (k <= 1) return listOf(0)
        val res = sortedSetOf(0)
        for (i in 1 until k) {
            val ideal = i * g.T.toDouble() / k
            val p = g.groupStarts.filter { it > 0 && it < g.T }.minByOrNull { abs(it - ideal) } ?: ideal.toInt().coerceIn(1, g.T - 1)
            res += p
        }
        return res.toList()
    }

    private fun scalePcs(fifths: Int, minor: Boolean): Set<Int> {
        val k = TonalKey(fifths, minor)
        val s = (0 until 7).map { k.pcOf(it) }.toMutableSet()
        if (minor) s += Math.floorMod(k.tonicPc - 1, 12)
        return s
    }

    /** Tom que melhor explica os acordes: (armadura, menor?). */
    fun inferKey(bars: List<List<Harmony>>): Pair<Int, Boolean> {
        val all = bars.flatten()
        if (all.isEmpty()) return 0 to false
        var best = 0 to false; var bs = -1e9
        for (f in -7..7) for (minor in listOf(false, true)) {
            val sc = scalePcs(f, minor); val tonic = TonalKey(f, minor).tonicPc
            var s = 0.0
            for (h in all) {
                val inKey = h.pcs().count { it in sc }
                s += if (inKey == h.pcs().size) 1.0 else if (h.rootPc in sc) 0.25 else -0.5
            }
            s /= all.size
            val first = all.first(); val last = all.last()
            fun isTonic(h: Harmony) = h.rootPc == tonic && (h.kind.startsWith("minor") == minor)
            if (isTonic(first)) s += 0.6
            if (isTonic(last)) s += 0.9
            s += 0.15 * all.count { isTonic(it) } / all.size
            s -= 0.01 * abs(f)
            if (s > bs) { bs = s; best = f to minor }
        }
        return best
    }

    /** Texto da sequência atual (compassos completos com acorde), um compasso por "|". */
    fun format(s: Score): String =
        s.parts.firstOrNull()?.measures?.filter { it.harmony.isNotEmpty() }?.joinToString(" | ") { m -> m.harmony.sortedBy { it.at }.joinToString(" ") { it.text() } } ?: ""

    /** Compassos completos (os que não são anacruse) da primeira parte, na ordem. */
    private fun fullMeasures(s: Score): List<Int> {
        val p = s.parts.firstOrNull() ?: return emptyList()
        val full = s.divisions * 4 * s.beats / s.beatType
        return p.measures.indices.filter { i ->
            val l = p.measures[i].notes.filter { !it.chord }.groupBy { it.voice }.values.maxOfOrNull { v -> v.sumOf { it.duration } } ?: full
            l >= full
        }
    }

    /** Escreve os acordes nos compassos completos, repetindo a sequência se for mais curta que a música. */
    fun apply(s: Score, bars: List<List<Harmony>>) {
        if (bars.isEmpty() || s.parts.isEmpty()) return
        val g = Grid(s.beats, s.beatType)
        val scale = s.divisions / 4
        val p = s.parts[0]
        for (mi in p.measures.indices) p.measures[mi].harmony.clear()
        for ((n, mi) in fullMeasures(s).withIndex()) {
            val hs = bars[n % bars.size]
            val pos = positions(g, hs.size)
            hs.forEachIndexed { i, h -> if (i < pos.size) p.measures[mi].harmony += h.copy(at = pos[i] * g.tickDiv * scale) }
        }
    }

    private val KIND_MAJOR = listOf("major", "minor", "minor", "major", "major", "minor", "diminished")
    private val KIND_MINOR = listOf("minor", "diminished", "major", "minor", "major", "major", "major")

    /** Acorde de um grau (0..6) do tom; no menor o V é maior (menor harmônica). */
    fun diatonic(key: TonalKey, deg: Int, dom7: Boolean = false): Harmony {
        val d = Math.floorMod(deg, 7)
        var kind = (if (key.minor) KIND_MINOR else KIND_MAJOR)[d]
        if (dom7 && d == 4) kind = "dominant"
        val (st, al) = key.spellPc(key.pcOf(d))
        return Harmony(0, st, al, kind)
    }

    class Preset(val label: String, val degs: List<Int>)
    val MAJOR_PRESETS = listOf(
        Preset("I – V – vi – IV", listOf(0, 4, 5, 3)), Preset("I – vi – IV – V", listOf(0, 5, 3, 4)), Preset("I – IV – V – I", listOf(0, 3, 4, 0)),
        Preset("vi – IV – I – V", listOf(5, 3, 0, 4)), Preset("I – IV – I – V", listOf(0, 3, 0, 4)), Preset("I – iii – IV – V", listOf(0, 2, 3, 4)),
    )
    val MINOR_PRESETS = listOf(
        Preset("i – VI – III – VII", listOf(0, 5, 2, 6)), Preset("i – iv – V – i", listOf(0, 3, 4, 0)), Preset("i – VII – VI – V", listOf(0, 6, 5, 4)),
        Preset("i – iv – VII – III", listOf(0, 3, 6, 2)), Preset("i – VI – iv – V", listOf(0, 5, 3, 4)),
    )

    /** Texto da sequência (um acorde por compasso) de um modelo no tom dado. */
    fun presetText(key: TonalKey, p: Preset): String = p.degs.joinToString(" | ") { diatonic(key, it).text() }
}
