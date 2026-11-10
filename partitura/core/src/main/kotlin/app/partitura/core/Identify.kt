package app.partitura.core

/** Reconhece o acorde a partir das notas tocadas (teclas ou casas), como um dicionário ao contrário. */
object Identify {
    class Match(val name: String, val rootLabel: String, val type: Study.ChordType, val bass: Int, val inversion: Int, val notes: List<Pitch>, val omitted: String?) {
        val description: String get() = type.name + (if (inversion > 0) " · ${inversion}ª inversão" else "") + (omitted?.let { " · sem $it" } ?: "")
    }

    private val PREF = mapOf(0 to "C", 1 to "D♭", 2 to "D", 3 to "E♭", 4 to "E", 5 to "F", 6 to "F♯", 7 to "G", 8 to "A♭", 9 to "A", 10 to "B♭", 11 to "B")

    /** Acordes compatíveis com exatamente essas classes de nota; a fundamental no baixo vem primeiro. */
    fun chords(midis: Collection<Int>): List<Match> {
        if (midis.size < 3) return emptyList()
        val pcs = midis.map { Math.floorMod(it, 12) }.toSet()
        val bass = midis.min()
        val out = ArrayList<Match>()
        for (rootPc in 0..11) {
            val label = PREF.getValue(rootPc)
            val rootP = Study.CHORD_ROOTS.first { it.first == label }.second
            for (t in Study.CHORDS) {
                val full = t.semis.map { Math.floorMod(rootPc + it, 12) }.toSet()
                var omitted: String? = null
                val ok = if (full == pcs) true else if (t.semis.size >= 4 && t.semis.contains(7) && !t.semis.contains(6)) {
                    // tétrade/nona sem a quinta (comum ao tocar): aceita só se todas as outras notas estão presentes
                    val noFifth = t.semis.filter { it != 7 }.map { Math.floorMod(rootPc + it, 12) }.toSet()
                    if (noFifth == pcs) { omitted = "a quinta"; true } else false
                } else false
                if (!ok) continue
                val notes = Study.chordNotes(rootP, t)
                val bassPc = Math.floorMod(bass, 12)
                val inv = if (bassPc == rootPc) 0 else t.semis.indexOfFirst { Math.floorMod(rootPc + it, 12) == bassPc }.let { if (it < 0) 0 else it }
                val bassName = if (inv == 0) "" else "/" + noteLabel(notes.getOrNull(inv) ?: rootP)
                out += Match(Study.chordName(label, t) + bassName, label, t, bass, inv, notes, omitted)
            }
        }
        return out.sortedWith(compareBy({ it.inversion }, { it.omitted != null }, { it.type.semis.size }))
    }

    private fun noteLabel(p: Pitch) = "${p.step}${when (p.alter) { 1 -> "♯"; -1 -> "♭"; else -> "" }}"
}
