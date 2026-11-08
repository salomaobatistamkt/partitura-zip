package app.partitura.core

/** Altura: passo (C..B), alteração (-1 bemol, 0, +1 sustenido) e oitava científica (C4 = dó central). */
data class Pitch(val step: Char, val alter: Int, val octave: Int) {
    val midi: Int get() = (octave + 1) * 12 + STEP_SEMITONES.getValue(step) + alter
    override fun toString() = "$step${ACC[alter + 2]}$octave"
    companion object {
        val STEPS = listOf('C', 'D', 'E', 'F', 'G', 'A', 'B')
        val STEP_SEMITONES = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
        private val ACC = listOf("bb", "b", "", "#", "##")
    }
}

/** Nota ou pausa (pitch == null). [duration] em divisões do quarto de nota. */
data class Note(val pitch: Pitch?, val duration: Int, val voice: Int = 1, val chord: Boolean = false, val tieStart: Boolean = false, val tieStop: Boolean = false)

data class Measure(val number: Int, val notes: MutableList<Note> = mutableListOf())

data class Part(val id: String, val name: String, val measures: MutableList<Measure> = mutableListOf(), var clef: Char = 'G')

data class Score(
    val title: String,
    var divisions: Int,
    var fifths: Int,
    var beats: Int,
    var beatType: Int,
    var tempo: Int,
    val parts: MutableList<Part> = mutableListOf(),
)

fun Score.copyDeep(): Score = copy(parts = parts.map { p ->
    Part(p.id, p.name, p.measures.map { m -> Measure(m.number, m.notes.toMutableList()) }.toMutableList(), p.clef)
}.toMutableList())
