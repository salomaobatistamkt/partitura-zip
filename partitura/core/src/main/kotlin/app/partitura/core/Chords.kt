package app.partitura.core

/** Lê cifras digitadas (Am7, F♯m, Bb/D, C°, Gsus4, D9) para o modelo de acorde. */
object Chords {
    private val SUFFIX: List<Pair<String, String>> = listOf(
        "maj7" to "major-seventh", "M7" to "major-seventh", "Δ" to "major-seventh", "m7b5" to "half-diminished", "m7♭5" to "half-diminished", "ø" to "half-diminished",
        "dim7" to "diminished-seventh", "°7" to "diminished-seventh", "dim" to "diminished", "°" to "diminished", "aug" to "augmented", "+" to "augmented",
        "sus4" to "suspended-fourth", "sus2" to "suspended-second", "sus" to "suspended-fourth", "m7" to "minor-seventh", "min7" to "minor-seventh", "-7" to "minor-seventh",
        "m6" to "minor-sixth", "min6" to "minor-sixth", "min" to "minor", "m" to "minor", "-" to "minor", "9" to "dominant-ninth", "7" to "dominant", "6" to "major-sixth", "maj" to "major", "" to "major",
    )

    /** Devolve null se o texto não for uma cifra reconhecível. */
    fun parse(text: String, at: Int = 0): Harmony? {
        val t = text.trim().replace('#', '♯').replace('♭', 'b').replace("♯", "#")
        if (t.isEmpty()) return null
        val root = t[0].uppercaseChar()
        if (root !in "ABCDEFG") return null
        var i = 1; var alter = 0
        if (i < t.length && t[i] == '#') { alter = 1; i++ } else if (i < t.length && t[i] == 'b' && !(t.length > i + 1 && false)) { alter = -1; i++ }
        val rest = t.substring(i)
        val slash = rest.indexOf('/')
        val suf = if (slash >= 0) rest.substring(0, slash) else rest
        var bass: Pair<Char, Int>? = null
        if (slash >= 0) {
            val b = rest.substring(slash + 1)
            if (b.isEmpty() || b[0].uppercaseChar() !in "ABCDEFG") return null
            val al = when (b.getOrNull(1)) { '#' -> 1; 'b' -> -1; else -> 0 }
            if (b.length > (if (al != 0) 2 else 1)) return null
            bass = b[0].uppercaseChar() to al
        }
        val kind = SUFFIX.firstOrNull { it.first == suf }?.second ?: return null
        return Harmony(at, root, alter, kind, bass?.first, bass?.second ?: 0)
    }
}
