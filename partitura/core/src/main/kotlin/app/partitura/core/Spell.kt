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

object Names {
    private val PT = mapOf('C' to "Dó", 'D' to "Ré", 'E' to "Mi", 'F' to "Fá", 'G' to "Sol", 'A' to "Lá", 'B' to "Si")
    fun step(step: Char, alter: Int) = PT.getValue(step) + when (alter) { 1 -> "♯"; -1 -> "♭"; 2 -> "𝄪"; -2 -> "♭♭"; else -> "" }
    fun pitch(p: Pitch) = step(p.step, p.alter) + p.octave
    fun key(fifths: Int) = when (fifths) {
        -7 -> "Dó♭"; -6 -> "Sol♭"; -5 -> "Ré♭"; -4 -> "Lá♭"; -3 -> "Mi♭"; -2 -> "Si♭"; -1 -> "Fá"; 0 -> "Dó"
        1 -> "Sol"; 2 -> "Ré"; 3 -> "Lá"; 4 -> "Mi"; 5 -> "Si"; 6 -> "Fá♯"; 7 -> "Dó♯"; else -> "?"
    }
    fun minorKey(fifths: Int) = when (fifths) {
        -7 -> "Lá♭"; -6 -> "Mi♭"; -5 -> "Si♭"; -4 -> "Fá"; -3 -> "Dó"; -2 -> "Sol"; -1 -> "Ré"; 0 -> "Lá"
        1 -> "Mi"; 2 -> "Si"; 3 -> "Fá♯"; 4 -> "Dó♯"; 5 -> "Sol♯"; 6 -> "Ré♯"; 7 -> "Lá♯"; else -> "?"
    }
}
