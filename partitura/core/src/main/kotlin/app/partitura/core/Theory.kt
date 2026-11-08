package app.partitura.core

enum class ScaleType(val label: String, val intervals: List<Int>) {
    MAIOR("Maior", listOf(0, 2, 4, 5, 7, 9, 11)),
    MENOR_NATURAL("Menor natural", listOf(0, 2, 3, 5, 7, 8, 10)),
    MENOR_HARMONICA("Menor harmônica", listOf(0, 2, 3, 5, 7, 8, 11)),
    MENOR_MELODICA("Menor melódica", listOf(0, 2, 3, 5, 7, 9, 11)),
    DORICO("Dórico", listOf(0, 2, 3, 5, 7, 9, 10)),
    FRIGIO("Frígio", listOf(0, 1, 3, 5, 7, 8, 10)),
    LIDIO("Lídio", listOf(0, 2, 4, 6, 7, 9, 11)),
    MIXOLIDIO("Mixolídio", listOf(0, 2, 4, 5, 7, 9, 10)),
}

enum class ChordQuality(val label: String, val intervals: List<Int>) {
    MAIOR("Maior", listOf(0, 4, 7)),
    MENOR("Menor", listOf(0, 3, 7)),
    DIMINUTO("Diminuto", listOf(0, 3, 6)),
    AUMENTADO("Aumentado", listOf(0, 4, 8)),
    SETIMA("Com sétima (7)", listOf(0, 4, 7, 10)),
    SETIMA_MAIOR("Sétima maior (7M)", listOf(0, 4, 7, 11)),
    MENOR_SETIMA("Menor com sétima (m7)", listOf(0, 3, 7, 10)),
}

data class FieldChord(val degree: String, val root: Pitch, val quality: ChordQuality, val notes: List<Pitch>) {
    val name: String get() = Names.step(root.step, root.alter) + when (quality) { ChordQuality.MENOR -> "m"; ChordQuality.DIMINUTO -> "°"; ChordQuality.AUMENTADO -> "+"; else -> "" }
}

object Theory {
    /** Notas ascendentes da escala, incluindo a oitava (8 notas), com a grafia correta por letra. */
    fun scale(tonic: Pitch, type: ScaleType): List<Pitch> = build(tonic, type.intervals + 12, (0..7).toList())

    fun chord(root: Pitch, q: ChordQuality): List<Pitch> {
        val letters = listOf(0, 2, 4, 6).take(q.intervals.size)
        return q.intervals.zip(letters).map { (iv, l) -> spell(root, iv, l) }
    }

    private fun spell(root: Pitch, semis: Int, letterOffset: Int): Pitch {
        val idx = root.octave * 7 + Pitch.STEPS.indexOf(root.step) + letterOffset
        val step = Pitch.STEPS[Math.floorMod(idx, 7)]
        val oct = Math.floorDiv(idx, 7)
        val natural = (oct + 1) * 12 + Pitch.STEP_SEMITONES.getValue(step)
        return Pitch(step, root.midi + semis - natural, oct)
    }

    private fun build(tonic: Pitch, intervals: List<Int>, letters: List<Int>) =
        intervals.zip(letters).map { (iv, l) -> spell(tonic, iv, l) }

    private fun above(p: Pitch, from: Pitch): Pitch {
        var o = from.octave - 1
        while (Pitch(p.step, p.alter, o).midi <= from.midi) o++
        return Pitch(p.step, p.alter, o)
    }

    /** Acordes do campo harmônico de uma tonalidade maior ou menor natural (tríades, ou tétrades com sétima). */
    fun harmonicField(tonic: Pitch, major: Boolean, sevenths: Boolean = false): List<FieldChord> {
        val sc = scale(tonic, if (major) ScaleType.MAIOR else ScaleType.MENOR_NATURAL).take(7)
        val romanMaj = listOf("I", "ii", "iii", "IV", "V", "vi", "vii°")
        val romanMin = listOf("i", "ii°", "III", "iv", "v", "VI", "VII")
        return sc.indices.map { i ->
            val root = sc[i]
            val third = above(sc[(i + 2) % 7], root)
            val fifth = above(sc[(i + 4) % 7], third)
            val q = when (listOf(third.midi - root.midi, fifth.midi - third.midi)) {
                listOf(4, 3) -> ChordQuality.MAIOR
                listOf(3, 4) -> ChordQuality.MENOR
                listOf(3, 3) -> ChordQuality.DIMINUTO
                else -> ChordQuality.AUMENTADO
            }
            val notes = mutableListOf(root, third, fifth)
            if (sevenths) notes += above(sc[(i + 6) % 7], fifth)
            FieldChord((if (major) romanMaj else romanMin)[i], root, q, notes)
        }
    }

    /** (maior, relativa menor, armadura) em ordem do ciclo das quintas. */
    fun circleOfFifths(): List<Triple<String, String, Int>> =
        (-6..6).map { Triple(Names.key(it), Names.minorKey(it), it) }.sortedBy { it.third }

    /** Partitura com as notas dadas em sequência (para tocar ou abrir no editor). */
    fun toScore(title: String, notes: List<Pitch>, q: Double = 1.0, fifths: Int = 0, chord: Boolean = false, clef: Char = 'G'): Score {
        val s = Score(title, 4, fifths, 4, 4, 90)
        val part = Part("P1", "Piano", mutableListOf(), clef)
        val d = (q * 4).toInt()
        if (chord) {
            val m = Measure(1)
            notes.forEachIndexed { i, p -> m.notes += Note(p, 16, chord = i > 0) }
            part.measures += m
        } else {
            var m = Measure(1); var used = 0
            for (p in notes) {
                if (used + d > 16) { part.measures += m; m = Measure(part.measures.size + 1); used = 0 }
                m.notes += Note(p, d); used += d
            }
            if (used < 16) m.notes += Note(null, 16 - used)
            part.measures += m
        }
        s.parts += part
        return s
    }
}
