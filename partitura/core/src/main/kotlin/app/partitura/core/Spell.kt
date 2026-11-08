package app.partitura.core

object Spell {
    private val FS = listOf('F', 'C', 'G', 'D', 'A', 'E', 'B')

    fun fromFifths(f: Int, targetMidi: Int): Pitch {
        val idx = Math.floorMod(f + 1, 7)
        val alter = Math.floorDiv(f + 1, 7)
        val step = FS[idx]
        val nat = Pitch.STEP_SEMITONES.getValue(step) + alter
        return Pitch(step, alter, (targetMidi - nat) / 12 - 1)
    }

    /** Grafia de uma altura MIDI de acordo com a armadura (fifths). */
    fun forMidi(midi: Int, keyFifths: Int): Pitch {
        var f = Math.floorMod(7 * Math.floorMod(midi, 12), 12)
        val lo = keyFifths - 5; val hi = keyFifths + 6
        while (f < lo) f += 12
        while (f > hi) f -= 12
        return fromFifths(f, midi)
    }

    /** Alteração que a armadura impõe a uma letra. */
    fun keyAlter(fifths: Int, step: Char): Int {
        val sharps = listOf('F', 'C', 'G', 'D', 'A', 'E', 'B')
        val flats = listOf('B', 'E', 'A', 'D', 'G', 'C', 'F')
        return when {
            fifths > 0 && sharps.indexOf(step) < fifths -> 1
            fifths < 0 && flats.indexOf(step) < -fifths -> -1
            else -> 0
        }
    }
}

/** Nomes de notas em cifras (C D E F G A B), o jeito que o músico lê. */
object Names {
    fun step(step: Char, alter: Int) = step.toString() + when (alter) { 1 -> "♯"; -1 -> "♭"; 2 -> "♯♯"; -2 -> "♭♭"; else -> "" }
    fun pitch(p: Pitch) = step(p.step, p.alter) + p.octave
    fun key(fifths: Int) = when (fifths) {
        -7 -> "C♭"; -6 -> "G♭"; -5 -> "D♭"; -4 -> "A♭"; -3 -> "E♭"; -2 -> "B♭"; -1 -> "F"; 0 -> "C"
        1 -> "G"; 2 -> "D"; 3 -> "A"; 4 -> "E"; 5 -> "B"; 6 -> "F♯"; 7 -> "C♯"; else -> "?"
    }
    /** Relativa menor (só a letra; o app escreve "menor" ao lado). */
    fun minorKey(fifths: Int) = when (fifths) {
        -7 -> "A♭"; -6 -> "E♭"; -5 -> "B♭"; -4 -> "F"; -3 -> "C"; -2 -> "G"; -1 -> "D"; 0 -> "A"
        1 -> "E"; 2 -> "B"; 3 -> "F♯"; 4 -> "C♯"; 5 -> "G♯"; 6 -> "D♯"; 7 -> "A♯"; else -> "?"
    }
}
