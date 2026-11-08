package app.partitura.core

import kotlin.math.roundToInt

/** Operações de edição. Todas mudam a partitura recebida (o chamador guarda o desfazer). */
object Edit {
    private fun note(s: Score, p: Int, m: Int, n: Int): Note? = s.parts.getOrNull(p)?.measures?.getOrNull(m)?.notes?.getOrNull(n)
    private fun put(s: Score, p: Int, m: Int, n: Int, v: Note) { s.parts[p].measures[m].notes[n] = v }

    fun shiftSemitones(s: Score, p: Int, m: Int, n: Int, d: Int) {
        val nt = note(s, p, m, n) ?: return
        val pt = nt.pitch ?: return
        put(s, p, m, n, nt.copy(pitch = Spell.forMidi((pt.midi + d).coerceIn(24, 108), s.fifths)))
    }

    fun shiftDiatonic(s: Score, p: Int, m: Int, n: Int, d: Int) {
        val nt = note(s, p, m, n) ?: return
        val pt = nt.pitch ?: return
        val idx = pt.octave * 7 + Pitch.STEPS.indexOf(pt.step) + d
        val step = Pitch.STEPS[Math.floorMod(idx, 7)]
        put(s, p, m, n, nt.copy(pitch = Pitch(step, Spell.keyAlter(s.fifths, step), Math.floorDiv(idx, 7))))
    }

    fun setAlter(s: Score, p: Int, m: Int, n: Int, alter: Int) {
        val nt = note(s, p, m, n) ?: return
        val pt = nt.pitch ?: return
        put(s, p, m, n, nt.copy(pitch = pt.copy(alter = alter)))
    }

    fun setPitch(s: Score, p: Int, m: Int, n: Int, midi: Int) {
        val nt = note(s, p, m, n) ?: return
        put(s, p, m, n, nt.copy(pitch = Spell.forMidi(midi, s.fifths)))
    }

    fun setDuration(s: Score, p: Int, m: Int, n: Int, quarters: Double) {
        val nt = note(s, p, m, n) ?: return
        put(s, p, m, n, nt.copy(duration = (quarters * s.divisions).roundToInt().coerceAtLeast(1)))
    }

    private val STD = listOf(16, 12, 8, 6, 4, 3, 2, 1) // em 1/4 de semínima (divisions = 4): 4, 3, 2, 1.5, 1, 0.75, 0.5, 0.25

    /** Troca a duração e preenche/consome pausas vizinhas para manter o compasso completo. Devolve a duração aplicada (em divisões). */
    fun setDurationSmart(s: Score, p: Int, m: Int, n: Int, quarters: Double): Int {
        val list = s.parts.getOrNull(p)?.measures?.getOrNull(m)?.notes ?: return 0
        val nt = list.getOrNull(n) ?: return 0
        val want = (quarters * s.divisions).roundToInt().coerceAtLeast(1)
        val old = nt.duration
        if (want == old) return old
        if (want < old) {
            list[n] = nt.copy(duration = want, tieStart = false)
            var rest = old - want; var at = n + 1
            val unit = s.divisions / 4
            while (rest > 0) {
                val piece = STD.map { it * unit }.firstOrNull { it <= rest } ?: rest
                list.add(at++, Note(null, piece, nt.voice)); rest -= piece
            }
            return want
        }
        var have = old; var i = n + 1
        while (have < want && i < list.size && list[i].pitch == null && list[i].voice == nt.voice && !list[i].chord) {
            val r = list[i]
            if (have + r.duration <= want) { have += r.duration; list.removeAt(i) }
            else { list[i] = r.copy(duration = r.duration - (want - have)); have = want }
        }
        list[n] = nt.copy(duration = have)
        return have
    }

    /** Define a altura pela posição na pauta (meios-espaços desde a linha de baixo), respeitando a armadura. */
    fun setPitchAtStaffPos(s: Score, p: Int, m: Int, n: Int, pos: Int) {
        val nt = note(s, p, m, n) ?: return
        val idx = pos + (if (s.parts[p].clef == 'F') 2 * 7 + 4 else 4 * 7 + 2)
        val step = Pitch.STEPS[Math.floorMod(idx, 7)]
        val oct = Math.floorDiv(idx, 7)
        if (oct < 0 || oct > 8) return
        put(s, p, m, n, nt.copy(pitch = Pitch(step, Spell.keyAlter(s.fifths, step), oct)))
    }

    fun toggleDot(s: Score, p: Int, m: Int, n: Int) {
        val nt = note(s, p, m, n) ?: return
        val d = nt.duration
        val base = if (d % 3 == 0 && (d / 3 * 2) > 0 && isPow2(d / 3 * 2)) d / 3 * 2 else null
        put(s, p, m, n, nt.copy(duration = base ?: (d * 3 / 2).coerceAtLeast(1)))
    }
    private fun isPow2(x: Int) = x > 0 && (x and (x - 1)) == 0

    fun toggleRest(s: Score, p: Int, m: Int, n: Int) {
        val nt = note(s, p, m, n) ?: return
        val clefDefault = if (s.parts[p].clef == 'F') Pitch('C', 0, 3) else Pitch('B', 0, 4)
        put(s, p, m, n, nt.copy(pitch = if (nt.pitch == null) clefDefault else null, chord = false))
    }

    fun toggleTie(s: Score, p: Int, m: Int, n: Int) {
        val nt = note(s, p, m, n) ?: return
        put(s, p, m, n, nt.copy(tieStart = !nt.tieStart))
        // marca o fim da ligadura na próxima nota
        val meas = s.parts[p].measures
        var mm = m; var nn = n + 1
        if (nn >= meas[mm].notes.size) { mm++; nn = 0 }
        val nx = meas.getOrNull(mm)?.notes?.getOrNull(nn) ?: return
        meas[mm].notes[nn] = nx.copy(tieStop = !nt.tieStart)
    }

    /** Insere depois da nota selecionada uma cópia dela (ou pausa se não houver nota). Devolve o novo índice. */
    fun insertAfter(s: Score, p: Int, m: Int, n: Int): Int {
        val list = s.parts[p].measures[m].notes
        val base = list.getOrNull(n)
        val new = (base ?: Note(null, s.divisions)).copy(chord = false, tieStart = false, tieStop = false)
        val at = (n + 1).coerceIn(0, list.size)
        list.add(at, new)
        return at
    }

    fun delete(s: Score, p: Int, m: Int, n: Int) {
        val list = s.parts[p].measures.getOrNull(m)?.notes ?: return
        if (n in list.indices) list.removeAt(n)
        if (list.isEmpty()) list.add(Note(null, s.divisions * 4 * s.beats / s.beatType))
    }

    fun addMeasure(s: Score) {
        for (part in s.parts) part.measures += Measure((part.measures.lastOrNull()?.number ?: 0) + 1,
            mutableListOf(Note(null, s.divisions * 4 * s.beats / s.beatType)))
    }

    fun deleteMeasure(s: Score, m: Int) {
        for (part in s.parts) if (part.measures.size > 1 && m in part.measures.indices) part.measures.removeAt(m)
        for (part in s.parts) part.measures.forEachIndexed { i, mm -> part.measures[i] = mm.copy(number = i + 1) }
    }

    /** Duração (em divisões) de cada compasso, tomada da primeira parte (cobre compasso anacrusico). */
    private fun measureLengths(s: Score): List<Int> {
        val first = s.parts.firstOrNull() ?: return emptyList()
        val full = s.divisions * 4 * s.beats / s.beatType
        return first.measures.map { m ->
            val byVoice = m.notes.filter { !it.chord }.groupBy { it.voice }.values.map { l -> l.sumOf { it.duration } }
            (byVoice.maxOrNull() ?: full).coerceAtLeast(1)
        }
    }

    private fun freshId(s: Score): String {
        var k = s.parts.size + 1
        while (s.parts.any { it.id == "P$k" || it.id.startsWith("P$k-") }) k++
        return "P$k"
    }

    /** Acrescenta um instrumento (piano = 2 pautas) com pausas em todos os compassos. Devolve o índice da primeira pauta nova. */
    fun addInstrument(s: Score, inst: Instrument, lengths: List<Int>? = null): Int {
        val lens = lengths ?: measureLengths(s)
        val firstIdx = s.parts.size
        val id = freshId(s)
        val same = s.parts.map { it.group }.distinct().count { g -> s.parts.first { it.group == g }.name.substringBefore(" (pauta").trim().startsWith(inst.name) }
        val baseName = if (same > 0) "${inst.name} ${same + 1}" else inst.name
        val clefs = if (inst.staves == 2) listOf('G', 'F') else listOf(inst.clef)
        clefs.forEachIndexed { k, clef ->
            val pid = if (k == 0) id else "$id-${k + 1}"
            val name = if (clefs.size > 1) "$baseName (pauta ${k + 1})" else baseName
            s.parts += Part(pid, name, MutableList(lens.size) { Measure(it + 1, mutableListOf(Note(null, lens[it]))) }, clef, id, inst.timbre.id)
        }
        return firstIdx
    }

    /** Remove o instrumento da parte [p] (todas as pautas dele). Falso se for o único. */
    fun removeInstrument(s: Score, p: Int): Boolean {
        val g = s.parts.getOrNull(p)?.group ?: return false
        if (s.parts.all { it.group == g }) return false
        s.parts.removeAll { it.group == g }
        return true
    }

    /** Troca o som (timbre do catálogo) do instrumento da parte [p]. */
    fun setSound(s: Score, p: Int, inst: String) {
        val g = s.parts.getOrNull(p)?.group ?: return
        for (part in s.parts) if (part.group == g) part.inst = inst
    }

    fun addMeasures(s: Score, n: Int) { repeat(n.coerceIn(0, 400)) { addMeasure(s) } }

    /**
     * Escreve uma nota (ou pausa se [midi] == null) de [q] semínimas na posição (m, n), como num editor de partitura:
     * ocupa pausas vizinhas, divide o que sobra em pausas e, se passar do fim do compasso, liga a nota ao compasso seguinte
     * (criando compassos novos no fim). Devolve a próxima posição (compasso, índice).
     */
    fun enter(s: Score, p: Int, m0: Int, n0: Int, midi: Int?, q: Double): Pair<Int, Int> {
        var m = m0; var n = n0
        var want = (q * s.divisions).roundToInt().coerceAtLeast(1)
        val pitch = midi?.let { Spell.forMidi(it.coerceIn(24, 108), s.fifths) }
        var firstPiece = true
        var guard = 0
        while (want > 0 && guard++ < 400) {
            val part = s.parts.getOrNull(p) ?: return m to n
            val list = part.measures.getOrNull(m)?.notes ?: return m to n
            if (n !in list.indices) return m to n
            val got = setDurationSmart(s, p, m, n, want.toDouble() / s.divisions)
            val atEnd = n == list.size - 1
            val carry = want - got > 0 && atEnd
            list[n] = list[n].copy(pitch = pitch, chord = false, tieStart = carry && pitch != null, tieStop = !firstPiece && pitch != null)
            want -= got
            firstPiece = false
            if (!carry) {
                want = 0
                return if (n + 1 < list.size) m to n + 1 else {
                    if (m + 1 >= part.measures.size) addMeasure(s)
                    m + 1 to 0
                }
            }
            if (m + 1 >= part.measures.size) addMeasure(s)
            m += 1; n = 0
        }
        return m to n
    }

    /** Partitura nova já com o instrumento escolhido (piano = 2 pautas). */
    fun blankFor(title: String, fifths: Int, beats: Int, beatType: Int, measures: Int, inst: Instrument, tempo: Int = 100): Score {
        val s = Score(title, 4, fifths, beats, beatType, tempo)
        val len = 4 * 4 * beats / beatType
        addInstrument(s, inst, List(measures.coerceAtLeast(1)) { len })
        return s
    }

    /** Partitura nova com vários instrumentos de uma vez (na ordem dada). */
    fun blankMulti(title: String, fifths: Int, beats: Int, beatType: Int, measures: Int, insts: List<Instrument>, tempo: Int = 100): Score {
        val s = Score(title, 4, fifths, beats, beatType, tempo)
        val len = 4 * 4 * beats / beatType
        val lens = List(measures.coerceAtLeast(1)) { len }
        for (i in insts.ifEmpty { listOf(Instruments.CATALOG[0]) }) addInstrument(s, i, lens)
        return s
    }

    fun blank(title: String, fifths: Int, beats: Int, beatType: Int, measures: Int, tempo: Int = 100): Score {
        val s = Score(title, 4, fifths, beats, beatType, tempo)
        val len = 4 * 4 * beats / beatType
        s.parts += Part("P1", "Piano", MutableList(measures) { Measure(it + 1, mutableListOf(Note(null, len))) })
        return s
    }
}
