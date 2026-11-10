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
data class Note(val pitch: Pitch?, val duration: Int, val voice: Int = 1, val chord: Boolean = false, val tieStart: Boolean = false, val tieStop: Boolean = false,
                /** Sílaba da letra (null = sem letra); [syl]: 's' inteira, 'b' começa palavra, 'm' meio, 'e' fim de palavra. */
                val lyric: String? = null, val syl: Char = 's')

/** Cifra de acorde num ponto do compasso ([at] em divisões desde o início do compasso). [kind] segue os nomes do MusicXML. */
data class Harmony(val at: Int, val step: Char, val alter: Int, val kind: String = "major", val bassStep: Char? = null, val bassAlter: Int = 0) {
    val rootPc: Int get() = Math.floorMod(Pitch.STEP_SEMITONES.getValue(step) + alter, 12)
    val bassPc: Int get() = bassStep?.let { Math.floorMod(Pitch.STEP_SEMITONES.getValue(it) + bassAlter, 12) } ?: rootPc
    fun intervals(): List<Int> = KINDS[kind]?.first ?: KINDS.getValue("major").first
    /** Classes de altura (0..11) das notas do acorde. */
    fun pcs(): List<Int> = intervals().map { (rootPc + it) % 12 }
    fun text(): String = Names.step(step, alter) + (KINDS[kind]?.second ?: "") + (if (bassStep != null && bassPc != rootPc) "/" + Names.step(bassStep, bassAlter) else "")
    companion object {
        /** tipo → (intervalos em semitons a partir da fundamental, sufixo da cifra) */
        val KINDS: Map<String, Pair<List<Int>, String>> = linkedMapOf(
            "major" to (listOf(0, 4, 7) to ""), "minor" to (listOf(0, 3, 7) to "m"), "dominant" to (listOf(0, 4, 7, 10) to "7"),
            "major-seventh" to (listOf(0, 4, 7, 11) to "maj7"), "minor-seventh" to (listOf(0, 3, 7, 10) to "m7"),
            "diminished" to (listOf(0, 3, 6) to "dim"), "half-diminished" to (listOf(0, 3, 6, 10) to "m7♭5"), "diminished-seventh" to (listOf(0, 3, 6, 9) to "dim7"),
            "augmented" to (listOf(0, 4, 8) to "aug"), "suspended-fourth" to (listOf(0, 5, 7) to "sus4"), "suspended-second" to (listOf(0, 2, 7) to "sus2"),
            "major-sixth" to (listOf(0, 4, 7, 9) to "6"), "minor-sixth" to (listOf(0, 3, 7, 9) to "m6"), "dominant-ninth" to (listOf(0, 4, 7, 10, 14) to "9"),
        )
    }
}

/**
 * Dinâmica ou gesto de intensidade num ponto do compasso ([at] em divisões desde o início do compasso).
 * [mark]: pppp ppp pp p mp mf f ff fff ffff sf sfz fp rfz; "cresc" / "dim" = início de crescendo / diminuendo; "end" = fim dele.
 */
data class Dyn(val at: Int, val mark: String)

/**
 * Compasso de uma pauta. Os campos depois de [harmony] são opcionais e valem para o compasso inteiro (todas as pautas):
 * [dyn] dinâmicas; [tempo] novo andamento (♩/min) a partir daqui; [fifths]/[beats]/[beatType] mudança de armadura/fórmula;
 * [repeatStart]/[repeatEnd] ritornelos; [volta] casa de repetição (1, 2…; 0 = nenhuma); [rit] % de mudança de andamento ("rit." < 0, "accel." > 0);
 * [clef] mudança de clave nesta pauta (G, F ou C).
 */
data class Measure(
    val number: Int, val notes: MutableList<Note> = mutableListOf(), val harmony: MutableList<Harmony> = mutableListOf(),
    val dyn: MutableList<Dyn> = mutableListOf(),
    var tempo: Int? = null, var fifths: Int? = null, var beats: Int? = null, var beatType: Int? = null,
    var repeatStart: Boolean = false, var repeatEnd: Boolean = false, var volta: Int = 0, var rit: Int = 0, var clef: Char? = null,
    /** "fermata" quando há fermata no compasso (a última nota soa mais longa). */
    var mark: String? = null,
    /** Motivo pelo qual a leitura deste compasso é incerta (soma de tempos não fecha); null = sem dúvida. */
    var doubt: String? = null,
) {
    /** Cópia independente (listas novas) mantendo todos os campos opcionais. */
    fun dup(): Measure = Measure(number, notes.toMutableList(), harmony.toMutableList(), dyn.toMutableList(), tempo, fifths, beats, beatType, repeatStart, repeatEnd, volta, rit, clef, mark, doubt)
}

data class Part(val id: String, val name: String, val measures: MutableList<Measure> = mutableListOf(), var clef: Char = 'G', val group: String = id, var inst: String = "piano")

data class Score(
    val title: String,
    var divisions: Int,
    var fifths: Int,
    var beats: Int,
    var beatType: Int,
    var tempo: Int,
    val parts: MutableList<Part> = mutableListOf(),
    /** Textos do cabeçalho da partitura (compositor, letrista, arranjador…): [Credit.align] 'L', 'C' ou 'R'; linhas em ordem. */
    var credits: List<Credit> = emptyList(),
)

data class Credit(val text: String, val align: Char, val row: Int)

fun Score.copyDeep(): Score = copy(parts = parts.map { p ->
    Part(p.id, p.name, p.measures.map { m -> m.dup() }.toMutableList(), p.clef, p.group, p.inst)
}.toMutableList())
