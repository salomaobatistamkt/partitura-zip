package app.partitura.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Dicionário de acordes, braço do violão, digitação da flauta doce e exercícios de piano gerados a partir de uma partitura. */
object Study {
    // ------------------------------------------------------------------ nomes

    private val PT = mapOf('C' to "Dó", 'D' to "Ré", 'E' to "Mi", 'F' to "Fá", 'G' to "Sol", 'A' to "Lá", 'B' to "Si")
    /** "Fá sustenido", "Si bemol"… */
    fun ptName(p: Pitch): String = PT.getValue(p.step) + when (p.alter) { 1 -> " sustenido"; -1 -> " bemol"; 2 -> " dobrado sustenido"; -2 -> " dobrado bemol"; else -> "" }
    fun ptShort(step: Char, alter: Int) = PT.getValue(step) + when (alter) { 1 -> "♯"; -1 -> "♭"; else -> "" }
    fun freq(midi: Int) = 440.0 * 2.0.pow((midi - 69) / 12.0)

    /** Nome de uma altura por classe (0..11), preferindo sustenidos ou bemóis. */
    fun pcName(pc: Int, flats: Boolean = false): String {
        val sh = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        val fl = arrayOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")
        return (if (flats) fl else sh)[Math.floorMod(pc, 12)]
    }

    /** Tônicas oferecidas (grafia correta para escalas maiores e menores). */
    val MAJOR_TONICS = listOf("C" to Pitch('C', 0, 4), "D♭" to Pitch('D', -1, 4), "D" to Pitch('D', 0, 4), "E♭" to Pitch('E', -1, 4), "E" to Pitch('E', 0, 4), "F" to Pitch('F', 0, 4),
        "F♯" to Pitch('F', 1, 4), "G" to Pitch('G', 0, 4), "A♭" to Pitch('A', -1, 4), "A" to Pitch('A', 0, 4), "B♭" to Pitch('B', -1, 4), "B" to Pitch('B', 0, 4))
    val MINOR_TONICS = listOf("C" to Pitch('C', 0, 4), "C♯" to Pitch('C', 1, 4), "D" to Pitch('D', 0, 4), "E♭" to Pitch('E', -1, 4), "E" to Pitch('E', 0, 4), "F" to Pitch('F', 0, 4),
        "F♯" to Pitch('F', 1, 4), "G" to Pitch('G', 0, 4), "G♯" to Pitch('G', 1, 4), "A" to Pitch('A', 0, 4), "B♭" to Pitch('B', -1, 4), "B" to Pitch('B', 0, 4))

    /** Escalas que têm digitação padrão de piano que posso afirmar (mão direita, subindo). Outras tonalidades variam: o app não inventa. */
    private val FING_RH_C = listOf(1, 2, 3, 1, 2, 3, 4, 1)
    private val FING_LH_C = listOf(5, 4, 3, 2, 1, 3, 2, 1)
    private val FING_RH_F = listOf(1, 2, 3, 4, 1, 2, 3, 1)
    /** (RH, LH) para a escala maior/menor natural de [tonicName], ou null se não tenho a digitação confiável. */
    fun fingering(tonicName: String, minor: Boolean): Pair<List<Int>, List<Int>>? = when {
        !minor && tonicName in listOf("C", "G", "D", "A", "E") -> FING_RH_C to FING_LH_C
        !minor && tonicName == "F" -> FING_RH_F to FING_LH_C
        minor && tonicName in listOf("A", "E", "D") -> FING_RH_C to FING_LH_C
        else -> null
    }

    // ------------------------------------------------------------------ acordes

    /** Tipo de acorde: intervalos em semitons e a "letra" (terça=2, quinta=4…) de cada nota, para grafar certo. */
    class ChordType(val id: String, val name: String, val suffix: String, val semis: List<Int>, val letters: List<Int>, val tones: String)
    val CHORDS = listOf(
        ChordType("maj", "Maior", "", listOf(0, 4, 7), listOf(0, 2, 4), "1 3 5"),
        ChordType("min", "Menor", "m", listOf(0, 3, 7), listOf(0, 2, 4), "1 ♭3 5"),
        ChordType("7", "Dominante (7)", "7", listOf(0, 4, 7, 10), listOf(0, 2, 4, 6), "1 3 5 ♭7"),
        ChordType("maj7", "Sétima maior (7M)", "7M", listOf(0, 4, 7, 11), listOf(0, 2, 4, 6), "1 3 5 7"),
        ChordType("m7", "Menor com sétima (m7)", "m7", listOf(0, 3, 7, 10), listOf(0, 2, 4, 6), "1 ♭3 5 ♭7"),
        ChordType("m7b5", "Meio-diminuto (m7♭5)", "m7♭5", listOf(0, 3, 6, 10), listOf(0, 2, 4, 6), "1 ♭3 ♭5 ♭7"),
        ChordType("dim", "Diminuto", "dim", listOf(0, 3, 6), listOf(0, 2, 4), "1 ♭3 ♭5"),
        ChordType("dim7", "Diminuto com sétima (dim7)", "dim7", listOf(0, 3, 6, 9), listOf(0, 2, 4, 6), "1 ♭3 ♭5 ♭♭7"),
        ChordType("aug", "Aumentado", "aug", listOf(0, 4, 8), listOf(0, 2, 4), "1 3 ♯5"),
        ChordType("sus2", "Suspenso 2 (sus2)", "sus2", listOf(0, 2, 7), listOf(0, 1, 4), "1 2 5"),
        ChordType("sus4", "Suspenso 4 (sus4)", "sus4", listOf(0, 5, 7), listOf(0, 3, 4), "1 4 5"),
        ChordType("6", "Com sexta (6)", "6", listOf(0, 4, 7, 9), listOf(0, 2, 4, 5), "1 3 5 6"),
        ChordType("m6", "Menor com sexta (m6)", "m6", listOf(0, 3, 7, 9), listOf(0, 2, 4, 5), "1 ♭3 5 6"),
        ChordType("add9", "Com nona (add9)", "add9", listOf(0, 4, 7, 14), listOf(0, 2, 4, 1), "1 3 5 9"),
        ChordType("9", "Dominante com nona (9)", "9", listOf(0, 4, 7, 10, 14), listOf(0, 2, 4, 6, 1), "1 3 5 ♭7 9"),
    )

    /** Raiz → pitch na oitava dada (usa a lista de tônicas maiores/sustenidos comuns para acordes). */
    val CHORD_ROOTS = listOf("C" to Pitch('C', 0, 3), "C♯" to Pitch('C', 1, 3), "D♭" to Pitch('D', -1, 3), "D" to Pitch('D', 0, 3), "E♭" to Pitch('E', -1, 3), "E" to Pitch('E', 0, 3),
        "F" to Pitch('F', 0, 3), "F♯" to Pitch('F', 1, 3), "G♭" to Pitch('G', -1, 3), "G" to Pitch('G', 0, 3), "A♭" to Pitch('A', -1, 3), "A" to Pitch('A', 0, 3),
        "B♭" to Pitch('B', -1, 3), "B" to Pitch('B', 0, 3))

    private fun spell(root: Pitch, semis: Int, letterOffset: Int): Pitch {
        val idx = root.octave * 7 + Pitch.STEPS.indexOf(root.step) + letterOffset
        val step = Pitch.STEPS[Math.floorMod(idx, 7)]
        val oct = Math.floorDiv(idx, 7)
        val natural = (oct + 1) * 12 + Pitch.STEP_SEMITONES.getValue(step)
        return Pitch(step, root.midi + semis - natural, oct)
    }
    fun chordNotes(root: Pitch, t: ChordType): List<Pitch> = t.semis.zip(t.letters).map { (s, l) -> spell(root, s, l) }
    fun chordName(rootName: String, t: ChordType) = rootName + t.suffix

    /** Posição fundamental e inversões, cada uma como lista de notas subindo a partir da mais grave. */
    fun inversions(notes: List<Pitch>): List<List<Pitch>> {
        val out = ArrayList<List<Pitch>>()
        var cur = notes
        for (i in 0 until min(notes.size, 4)) {
            out += cur
            val first = cur.first()
            var up = Pitch(first.step, first.alter, first.octave + 1)
            while (up.midi <= cur.last().midi) up = Pitch(up.step, up.alter, up.octave + 1)
            cur = cur.drop(1) + up
        }
        return out
    }

    /** Dedos padrão para tríades e acordes de 4 notas em posição fechada (mão direita / esquerda, da nota mais grave à mais aguda). */
    fun chordFingers(size: Int): Pair<List<Int>, List<Int>>? = when (size) {
        3 -> listOf(1, 3, 5) to listOf(5, 3, 1)
        4 -> listOf(1, 2, 3, 5) to listOf(5, 3, 2, 1)
        else -> null
    }

    // ------------------------------------------------------------------ violão

    val GUITAR_TUNING = intArrayOf(40, 45, 50, 55, 59, 64)           // corda 6 (grave) … corda 1 (aguda): E2 A2 D3 G3 B3 E4
    val GUITAR_STRING_NAMES = listOf("Mi (6ª)", "Lá (5ª)", "Ré (4ª)", "Sol (3ª)", "Si (2ª)", "Mi (1ª)")
    const val GUITAR_FRETS = 19

    fun guitarMidi(string: Int, fret: Int) = GUITAR_TUNING[string] + fret

    /** Todas as casas do braço onde a classe de altura [pc] aparece: (corda 0..5 grave→aguda, casa 0..GUITAR_FRETS). */
    fun guitarPositions(pc: Int): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        for (s in 0 until 6) for (f in 0..GUITAR_FRETS) if (Math.floorMod(guitarMidi(s, f), 12) == Math.floorMod(pc, 12)) out += s to f
        return out
    }

    /** Uma forma de acorde: casa por corda (grave→aguda); -1 = corda não tocada. */
    class Shape(val frets: IntArray, val baseFret: Int, val barre: Boolean) {
        val notes: List<Int> get() = frets.indices.filter { frets[it] >= 0 }.map { guitarMidi(it, frets[it]) }
        val bassMidi: Int get() = notes.first()
    }

    /**
     * Gera formas de acorde no braço (casas 0..[maxFret]), todas as notas essenciais presentes, cordas tocadas contíguas, abertura de até 4 casas.
     * Ordena das mais fáceis (mais perto da pestana, mais cordas soltas, menos cordas mudas) para as mais agudas. Baixo = fundamental.
     */
    fun guitarShapes(root: Pitch, t: ChordType, maxFret: Int = 12, limit: Int = 6, allowInversions: Boolean = false): List<Shape> {
        val rootPc = Math.floorMod(root.midi, 12)
        val pcs = t.semis.map { Math.floorMod(root.midi + it, 12) }
        val needed = if (pcs.size >= 4) pcs.filterIndexed { i, _ -> t.semis[i] != 7 }.toSet() else pcs.toSet()   // com 4+ notas pode omitir a quinta
        val res = ArrayList<Pair<Shape, Double>>()
        val cur = IntArray(6) { -1 }
        fun rec(s: Int) {
            if (s == 6) {
                val played = cur.indices.filter { cur[it] >= 0 }
                if (played.size < 4 && !(played.size == 3 && pcs.size == 3)) return
                if (played.last() - played.first() + 1 != played.size) return          // sem corda muda no meio
                val mids = played.map { guitarMidi(it, cur[it]) }
                val have = mids.map { Math.floorMod(it, 12) }.toSet()
                if (!have.containsAll(needed)) return
                if (!allowInversions && Math.floorMod(mids.first(), 12) != rootPc) return
                val fr = played.map { cur[it] }.filter { it > 0 }
                val minF = fr.minOrNull() ?: 0; val maxF = fr.maxOrNull() ?: 0
                if (maxF - minF > 3) return
                val barre = fr.isNotEmpty() && played.count { cur[it] == minF } >= 2 && played.any { cur[it] > minF } && played.filter { cur[it] in 1..minF }.size >= 2 && minF > 0 &&
                    played.none { cur[it] == 0 && it > played.first { p -> cur[p] == minF } }
                val opens = played.count { cur[it] == 0 }
                if (minF > 4 && opens > 0) return                                       // cordas soltas só perto da pestana
                val mutes = 6 - played.size
                val score = minF * 1.0 + (maxF - minF) * 0.7 + mutes * 1.2 - opens * 0.9 + (if (barre) 1.5 else 0.0) + (if (fr.isEmpty()) 0.0 else 0.0)
                res += Shape(cur.copyOf(), if (minF == 0) 1 else minF, barre) to score
                return
            }
            cur[s] = -1; rec(s + 1)
            for (f in 0..maxFret) if (Math.floorMod(guitarMidi(s, f), 12) in pcs) { cur[s] = f; rec(s + 1) }
            cur[s] = -1
        }
        rec(0)
        // remove formas repetidas e muito parecidas (mesmas casas em posição diferente já são distintas)
        val seen = HashSet<String>()
        val sorted = res.sortedBy { it.second }.map { it.first }.filter { seen.add(it.frets.joinToString(",")) }
        // diversidade: no máximo 2 formas por região de 3 casas
        val out = ArrayList<Shape>(); val perRegion = HashMap<Int, Int>()
        for (sh in sorted) { val reg = sh.baseFret / 3; if ((perRegion[reg] ?: 0) < 2) { out += sh; perRegion[reg] = (perRegion[reg] ?: 0) + 1 }; if (out.size >= limit) break }
        return out
    }

    // ------------------------------------------------------------------ flauta doce (soprano, digitação barroca/inglesa)

    /** Furos: [0]=polegar, [1..3]=indicador/médio/anular da mão de cima, [4..7]=mão de baixo (6 e 7 são os furos duplos). true = tampado. */
    class Fingering(val pitch: Pitch, val holes: BooleanArray, val note: String = "")
    private fun fg(p: Pitch, s: String, note: String = "") = Fingering(p, BooleanArray(8) { s[it] == '1' }, note)
    val RECORDER: List<Fingering> = listOf(
        fg(Pitch('C', 0, 5), "11111111"), fg(Pitch('D', 0, 5), "11111110"), fg(Pitch('E', 0, 5), "11111100"),
        fg(Pitch('F', 0, 5), "11111011", "dedilhado barroco (inglês); na alemã o Fá é diferente"),
        fg(Pitch('F', 1, 5), "11110110"), fg(Pitch('G', 0, 5), "11110000"), fg(Pitch('G', 1, 5), "11101100"),
        fg(Pitch('A', 0, 5), "11100000"), fg(Pitch('B', -1, 5), "11011000"), fg(Pitch('B', 0, 5), "11000000"),
        fg(Pitch('C', 0, 6), "10100000", "polegar e só o 2º furo da mão de cima: é o Dó oitavado"),
        fg(Pitch('D', 0, 6), "00100000", "confira no seu modelo: as notas agudas variam entre flautas"),
    )

    // ------------------------------------------------------------------ exercícios de piano a partir da partitura

    class Exercise(val id: String, val title: String, val what: String, val score: Score)

    private class Step(val midis: List<Int>, val dur: Int)       // dur em divisões (semínima = 4); midis vazio = pausa
    private fun r(dur: Int) = Step(emptyList(), dur)
    private fun n(m: Int, dur: Int) = Step(listOf(m), dur)
    private fun ch(ms: List<Int>, dur: Int) = Step(ms, dur)

    private fun fill(steps: List<Step>, key: TonalKey, measures: Int): MutableList<Measure> {
        val out = MutableList(measures) { Measure(it + 1) }
        var bar = 0; var used = 0
        fun room() = 16 - used
        for (st in steps + if (steps.sumOf { it.dur } % 16 != 0) listOf(r(16 - steps.sumOf { it.dur } % 16)) else emptyList()) {
            if (bar >= measures) break
            val d = min(st.dur, room())
            val m = out[bar]
            if (st.midis.isEmpty()) m.notes += Note(null, d) else st.midis.sorted().forEachIndexed { k, mid -> m.notes += Note(key.spell(mid), d, 1, k > 0) }
            used += d
            if (used >= 16) { bar++; used = 0 }
        }
        for (m in out) if (m.notes.isEmpty()) m.notes += Note(null, 16)
        return out
    }

    private fun build(title: String, key: TonalKey, fifths: Int, tempo: Int, rh: List<Step>, lh: List<Step>): Score {
        val bars = max(1, max(rh.sumOf { it.dur }, lh.sumOf { it.dur }).let { (it + 15) / 16 })
        val s = Edit.blankFor(title, fifths, 4, 4, bars, Instruments.byId("piano")!!, tempo)
        val g = s.parts.filter { it.inst == "piano" || true }
        val rhPart = g.first { it.clef == 'G' }; val lhPart = g.first { it.clef == 'F' }
        rhPart.measures.clear(); rhPart.measures.addAll(fill(rh, key, bars))
        lhPart.measures.clear(); lhPart.measures.addAll(fill(lh, key, bars))
        return s
    }

    /** Acordes da tonalidade (grau 0-based, intervalos a partir da fundamental). */
    private class Chord(val rootMidi: Int, val semis: List<Int>, val label: String)

    private fun harmonyFrom(s: Score, key: TonalKey): List<Chord> {
        val seen = ArrayList<Chord>()
        val p0 = s.parts.firstOrNull()
        if (p0 != null) for (m in p0.measures) for (h in m.harmony) {
            val sem = h.intervals().map { it % 12 }.let { it }
            val root = 48 + h.rootPc   // dó 3 como referência
            val c = Chord(root, h.intervals(), h.text())
            if (seen.isEmpty() || seen.last().label != c.label) seen += c
            if (seen.size >= 8) return seen
        }
        return seen
    }

    private fun diatonicProg(key: TonalKey): List<Chord> {
        val min = key.minor
        fun deg(d: Int, semis: List<Int>, label: String) = Chord(48 + key.pcOf(d), semis, label)
        val tn = if (min) pcName(key.tonicPc) + "m" else pcName(key.tonicPc)
        return if (min) listOf(deg(0, listOf(0, 3, 7), "i"), deg(3, listOf(0, 3, 7), "iv"), deg(4, listOf(0, 4, 7, 10), "V7"), deg(0, listOf(0, 3, 7), "i"))
        else listOf(deg(0, listOf(0, 4, 7), "I"), deg(3, listOf(0, 4, 7), "IV"), deg(4, listOf(0, 4, 7, 10), "V7"), deg(0, listOf(0, 4, 7), "I"))
    }

    /** Gera os exercícios no tom da partitura [s]. */
    fun exercises(s: Score): List<Exercise> {
        val minor = Arrange.inferMinor(s)
        val key = TonalKey(s.fifths, minor)
        val tonicName = pcName(key.tonicPc, s.fifths < 0)
        val keyName = tonicName + if (minor) " menor" else " maior"
        val f = s.fifths
        val out = ArrayList<Exercise>()
        val base = key.midi(0)                                                  // tônica perto do dó central
        val rhBase = if (base > 66) base - 12 else base
        val lhBase = rhBase - 24

        // 1. Escala, duas oitavas, mãos juntas em movimento paralelo
        run {
            val up = (0..14).map { it }; val seq = up + up.reversed().drop(1)
            val rh = seq.map { n(key.midi(it) - (base - rhBase), 2) }
            val lh = seq.map { n(key.midi(it) - 24 - (base - rhBase), 2) }
            out += Exercise("escala", "Escala de $keyName, duas oitavas", "Mãos juntas em colcheias, subindo e descendo. Mão direita no Sol, esquerda no Fá, duas oitavas de distância.",
                build("Escala de $keyName", key, f, 80, rh, lh))
        }
        // 2. Oitavas
        run {
            val seq = (0..7) + (6 downTo 0)
            val rh = seq.map { ch(listOf(key.midi(it) - (base - rhBase), key.midi(it) - (base - rhBase) + 12), 4) }
            val lh = seq.map { ch(listOf(key.midi(it) - 24 - (base - rhBase), key.midi(it) - 12 - (base - rhBase)), 4) }
            out += Exercise("oitavas", "Oitavas em $keyName", "Cada nota da escala dobrada na oitava, nas duas mãos. Mantenha a mão aberta e o pulso solto; pare se sentir dor.",
                build("Oitavas em $keyName", key, f, 70, rh, lh))
        }
        // 3. Cinco dedos e alongamento
        run {
            val pat = listOf(0, 1, 2, 3, 4, 3, 2, 1)
            val skip = listOf(0, 2, 1, 3, 2, 4, 3, 1)
            val rh = (pat + pat + skip + skip).map { n(key.midi(it) - (base - rhBase), 2) }
            val lh = (pat + pat + skip + skip).map { n(key.midi(it) - 12 - (base - rhBase), 2) }
            out += Exercise("cincodedos", "Cinco dedos e saltos em $keyName", "Posição de cinco dedos (1-2-3-4-5-4-3-2-1) e depois saltos de terça, para soltar e alongar a mão. Um dedo por tecla, sem mover a mão.",
                build("Cinco dedos em $keyName", key, f, 76, rh, lh))
        }
        // 4. Alongamento: salto de oitava
        run {
            val seq = ((0..6)).flatMap { listOf(it, it + 7) } + listOf(7)
            val rh = seq.map { n(key.midi(it) - (base - rhBase), 4) }
            val lh = seq.map { n(key.midi(it) - 24 - (base - rhBase), 4) }
            out += Exercise("saltos", "Salto de oitava em $keyName", "Cada grau da escala seguido da sua oitava: treina a abertura da mão e a precisão do salto.",
                build("Saltos de oitava em $keyName", key, f, 66, rh, lh))
        }
        // 5. Arpejos nos acordes
        val prog = harmonyFrom(s, key).ifEmpty { diatonicProg(key) }
        val progLabel = prog.joinToString(" – ") { it.label }
        run {
            val rh = ArrayList<Step>(); val lh = ArrayList<Step>()
            for (c in prog) {
                val pcs = c.semis.map { it % 12 }.take(4)
                val rootR = c.rootMidi + 12 + (if (c.rootMidi + 12 > 66) -12 else 0)
                val ups = pcs + pcs.map { it + 12 }
                val notes = (ups.take(5) + ups.take(4).drop(1).reversed()).map { rootR + it }
                for (m in notes) rh += n(m, 2)
                lh += n(c.rootMidi - 12, 16)
            }
            out += Exercise("arpejos", "Arpejos: $progLabel", "Mão direita faz o arpejo de cada acorde (subindo e descendo) enquanto a esquerda segura a fundamental. Acordes tirados da sua partitura" + (if (harmonyFrom(s, key).isEmpty()) " (ela não tem cifras; usei os graus do tom)." else "."),
                build("Arpejos em $keyName", key, f, 72, rh, lh))
        }
        // 6. Acompanhamento: baixo alternado na esquerda, acorde na direita
        run {
            val rh = ArrayList<Step>(); val lh = ArrayList<Step>()
            for (c in prog) {
                val triad = c.semis.take(4).map { c.rootMidi + 12 + it }.map { if (it > 74) it - 12 else it }
                rh += ch(triad, 8); rh += ch(triad, 8)
                val root = c.rootMidi - 12; val fifth = c.rootMidi - 12 + 7
                lh += listOf(n(root, 4), n(fifth, 4), n(root, 4), n(fifth, 4))
            }
            out += Exercise("acomp", "Acompanhamento: $progLabel", "Esquerda (Fá) alterna fundamental e quinta; direita (Sol) toca o acorde em meias notas. Treina as duas claves ao mesmo tempo.",
                build("Acompanhamento em $keyName", key, f, 78, rh, lh))
        }
        // 7. Alberti
        run {
            val rh = ArrayList<Step>(); val lh = ArrayList<Step>()
            for (c in prog) {
                val tones = c.semis.take(3).map { c.rootMidi + it }
                val low = tones[0] - 12; val mid = tones[1] - 12; val high = tones[2] - 12
                val pattern = listOf(low, high, mid, high)
                repeat(2) { for (m in pattern) lh += n(m, 2) }
                rh += ch(tones.map { it + 12 }.map { if (it > 74) it - 12 else it }, 16)
            }
            out += Exercise("alberti", "Baixo de Alberti: $progLabel", "A esquerda toca grave–agudo–médio–agudo (baixo de Alberti) e a direita sustenta o acorde. Mantenha o pulso da esquerda parelho.",
                build("Alberti em $keyName", key, f, 70, rh, lh))
        }
        // 8. Cadência em acordes
        run {
            val rh = ArrayList<Step>(); val lh = ArrayList<Step>()
            var prev: List<Int>? = null
            for (c in prog) {
                val tones = c.semis.take(3).map { (c.rootMidi + 12 + it) }
                val inv = (0 until 3).flatMap { i -> (0..1).map { o -> tones.mapIndexed { k, x -> if (k < i) x + 12 else x }.map { it + (o - 1) * 12 } } }
                val pick = inv.minByOrNull { cand -> if (prev == null) abs(cand.average() - 66) else abs(cand.average() - prev!!.average()) } ?: tones
                rh += ch(pick.sorted(), 16); prev = pick
                lh += ch(listOf(c.rootMidi - 12, c.rootMidi), 16)
            }
            out += Exercise("cadencia", "Cadência em acordes: $progLabel", "Acordes na direita com o menor movimento possível entre eles (inversões) e fundamental em oitava na esquerda. É o esqueleto de muitos hinos.",
                build("Cadência em $keyName", key, f, 60, rh, lh))
        }
        return out
    }
}
