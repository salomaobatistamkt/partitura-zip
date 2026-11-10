package app.partitura.core

/** Níveis do método (pré-requisitos sempre apontam para níveis iguais ou inferiores). */
enum class Level(val label: String) { INICIANTE("Iniciante"), BASICO("Básico"), INTERMEDIARIO("Intermediário"), AVANCADO("Avançado"), ESPECIALIZACAO("Especialização") }

/**
 * Exercício de piano com os 14 campos do método. A partitura é gerada pela teoria (nunca copiada de um PDF).
 * [fingering] fica nulo quando não tenho uma digitação padrão confiável: o app não inventa.
 */
class Lesson(
    val id: String, val module: String, val name: String, val objective: String, val knowledge: String, val level: Level,
    val explanation: String, val steps: List<String>, val fingering: String?, val bpmStart: Int, val bpmTarget: Int,
    val commonErrors: List<String>, val completion: String, val prerequisites: List<String>, val skills: List<String>,
    private val build: (Int) -> Score,
) {
    /** Partitura/áudio de demonstração; [transpose] muda a tonalidade em semitons (troca de tom). */
    fun score(transpose: Int = 0): Score = build(transpose)
}

/** Progresso do aluno, persistido em texto simples (uma linha por exercício). */
class Progress {
    class Entry(var attempts: Int = 0, var bestBpm: Int = 0, var bestHit: Int = 0, var done: Boolean = false, var seconds: Int = 0, var last: Long = 0)
    val map = linkedMapOf<String, Entry>()
    fun entry(id: String) = map.getOrPut(id) { Entry() }

    /** Registra uma tentativa. [hit] é a % de acerto (0..100) medida pelo app (ou informada pelo aluno). Concluído = critério do exercício cumprido. */
    fun record(l: Lesson, bpm: Int, hit: Int, seconds: Int, now: Long) {
        val e = entry(l.id)
        e.attempts++; e.seconds += seconds; e.last = now
        if (hit >= e.bestHit) e.bestHit = hit
        if (bpm > e.bestBpm && hit >= 90) e.bestBpm = bpm
        if (hit >= 90 && bpm >= l.bpmTarget) e.done = true
    }

    fun serialize(): String = map.entries.joinToString("\n") { (k, e) -> "$k\t${e.attempts}\t${e.bestBpm}\t${e.bestHit}\t${if (e.done) 1 else 0}\t${e.seconds}\t${e.last}" }
    companion object {
        fun parse(t: String): Progress {
            val p = Progress()
            for (ln in t.lines()) {
                val f = ln.split('\t'); if (f.size < 7) continue
                p.map[f[0]] = Entry(f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0, f[3].toIntOrNull() ?: 0, f[4] == "1", f[5].toIntOrNull() ?: 0, f[6].toLongOrNull() ?: 0)
            }
            return p
        }
    }
}

object Curriculum {
    private val MAJ = Study.MAJOR_TONICS
    private val MIN = Study.MINOR_TONICS

    // Digitação padrão de uma oitava, subindo (mão direita, mão esquerda). Fonte: prática pedagógica corrente; o app avisa para conferir com o professor.
    private val MAJOR_FING = mapOf(
        "C" to ("1 2 3 1 2 3 4 5" to "5 4 3 2 1 3 2 1"), "G" to ("1 2 3 1 2 3 4 5" to "5 4 3 2 1 3 2 1"), "D" to ("1 2 3 1 2 3 4 5" to "5 4 3 2 1 3 2 1"),
        "A" to ("1 2 3 1 2 3 4 5" to "5 4 3 2 1 3 2 1"), "E" to ("1 2 3 1 2 3 4 5" to "5 4 3 2 1 3 2 1"), "B" to ("1 2 3 1 2 3 4 5" to "4 3 2 1 4 3 2 1"),
        "F" to ("1 2 3 4 1 2 3 4" to "5 4 3 2 1 3 2 1"), "D♭" to ("2 3 1 2 3 4 1 2" to "3 2 1 4 3 2 1 3"), "E♭" to ("3 1 2 3 4 1 2 3" to "3 2 1 4 3 2 1 3"),
        "A♭" to ("3 4 1 2 3 1 2 3" to "3 2 1 4 3 2 1 3"), "B♭" to ("4 1 2 3 1 2 3 4" to "3 2 1 4 3 2 1 3"), "F♯" to ("2 3 4 1 2 3 1 2" to "4 3 2 1 3 2 1 4"),
    )
    private val MINOR_NAT_FING = mapOf("A" to ("1 2 3 1 2 3 4 5" to "5 4 3 2 1 3 2 1"), "E" to ("1 2 3 1 2 3 4 5" to "5 4 3 2 1 3 2 1"), "D" to ("1 2 3 1 2 3 4 5" to "5 4 3 2 1 3 2 1"))

    private fun shift(p: Pitch, semis: Int): Pitch {
        if (semis == 0) return p
        val m = p.midi + semis
        // grafia por sustenido/bemol conforme o sentido da transposição
        val names = if (semis > 0) arrayOf("C", "C", "D", "D", "E", "F", "F", "G", "G", "A", "A", "B") else arrayOf("C", "D", "D", "E", "E", "F", "G", "G", "A", "A", "B", "B")
        val pc = Math.floorMod(m, 12); val step = names[pc][0]
        val nat = Pitch(step, 0, Math.floorDiv(m, 12) - 1).midi
        return Pitch(step, m - nat, Math.floorDiv(m, 12) - 1)
    }

    /** Duas pautas (direita em clave de Sol, esquerda em Fá, uma oitava abaixo), notas de [q] semínimas, em 4/4. */
    fun twoHands(title: String, notes: List<Pitch>, q: Double, fifths: Int): Score {
        val sc = Score(title, 4, fifths, 4, 4, 90)
        val d = (q * 4).toInt()
        for ((name, clef, off) in listOf(Triple("Mão direita", 'G', 0), Triple("Mão esquerda", 'F', -1))) {
            val part = Part(if (off == 0) "P1" else "P2", name, mutableListOf(), clef, "G1")
            var m = Measure(1); var used = 0
            for (n in notes) {
                if (used + d > 16) { part.measures += m; m = Measure(part.measures.size + 1); used = 0 }
                m.notes += Note(Pitch(n.step, n.alter, n.octave + off), d); used += d
            }
            if (used < 16) m.notes += Note(null, 16 - used)
            part.measures += m
            sc.parts += part
        }
        return sc
    }

    private fun upDown(sc: List<Pitch>): List<Pitch> = sc + sc.dropLast(1).reversed()
    private fun scaleLesson(label: String, tonic: Pitch, type: ScaleType, level: Level, fing: Pair<String, String>?, prereq: List<String>): Lesson {
        val minor = type != ScaleType.MAIOR
        val id = "esc-${type.name.lowercase()}-$label"
        val bpmT = if (level <= Level.BASICO) 100 else 120
        return Lesson(
            id, "Escalas", "Escala de $label ${type.label.lowercase()}", "Tocar a escala de $label ${type.label.lowercase()} em duas oitavas, mãos separadas e juntas, com dedilhado constante.",
            "Fórmula de tons/semitons da escala ${type.label.lowercase()}, acidentes de $label, dedilhado.", level,
            "Cada escala é uma sequência de 7 notas definida por tons e semitons: ${type.intervals.zipWithNext { a, b -> if (b - a == 2) "T" else "S" }.joinToString(" ")} T. Memorize quais notas têm acidente e mantenha SEMPRE o mesmo dedilhado, subindo e descendo.",
            listOf("Toque só a mão direita, bem devagar, olhando os acidentes.", "Repita só a mão esquerda.", "Junte as mãos em oitavas paralelas, no andamento inicial.", "Suba o metrônomo de 4 em 4 bpm só depois de 3 execuções perfeitas.", "Repita em duas oitavas, subindo e descendo."),
            fing?.let { "Uma oitava, subindo — mão direita: ${it.first} · mão esquerda: ${it.second} (dedilhado padrão; confira com seu professor)." },
            60, bpmT, listOf("Trocar o dedilhado entre subida e descida.", "Esquecer um acidente.", "Acelerar em vez de manter o pulso.", "Virar a mão tarde demais na passagem do polegar."),
            "Escala em 2 oitavas, mãos juntas, a $bpmT bpm com 90% de acerto, três vezes seguidas.", prereq, listOf("escala", "dedilhado", "tonalidade", if (minor) "modo menor" else "modo maior"),
        ) { t ->
            val tn = shift(tonic, t)
            val s1 = Theory.scale(tn, type); val s2 = Theory.scale(Pitch(tn.step, tn.alter, tn.octave + 1), type)
            val notes = upDown(s1.dropLast(1) + s2)
            twoHands("Escala de $label ${type.label}", notes, 0.5, if (type == ScaleType.MAIOR) keySig(tn) else 0)
        }
    }

    /** Armadura (nº de ♯ positivo, ♭ negativo) de uma tônica maior; 0 se improvável. */
    private fun keySig(t: Pitch): Int {
        val order = listOf("F♭", "C♭", "G♭", "D♭", "A♭", "E♭", "B♭", "F", "C", "G", "D", "A", "E", "B", "F♯", "C♯")
        val n = Study.ptShort(t.step, t.alter).let { _ -> "${t.step}${when (t.alter) { 1 -> "♯"; -1 -> "♭"; else -> "" }}" }
        val i = order.indexOf(n); return if (i < 0) 0 else i - 8
    }

    private fun arpLesson(label: String, tonic: Pitch, level: Level, prereq: List<String>): Lesson {
        val id = "arp-maior-$label"
        return Lesson(
            id, "Arpejos", "Arpejo de $label maior", "Tocar o arpejo da tríade de $label em duas oitavas, subindo e descendo, com a mão girando sobre o polegar.",
            "Tríade (1-3-5), grafia das notas, movimento da mão em arco.", level,
            "O arpejo toca as notas do acorde uma de cada vez. Use o dedilhado 1-2-3-1-2-3-5 e deixe o pulso acompanhar o desenho, sem tensão no braço.",
            listOf("Toque o acorde em bloco para ouvir o som.", "Arpejo de uma oitava, mão direita.", "Mesmo com a mão esquerda.", "Duas oitavas, mãos juntas.", "Repita com as inversões."),
            "Mão direita 1 2 3 1 2 3 5 · mão esquerda 5 3 2 1 3 2 1 (padrão para tríades em posição fundamental; confira com seu professor).",
            60, 112, listOf("Tocar duas notas ao mesmo tempo.", "Salto do polegar feito com o braço, não com o pulso."), "Duas oitavas a 112 bpm (colcheias) com 90% de acerto.", prereq, listOf("arpejo", "tríade", "coordenação"),
        ) { t ->
            val tn = shift(tonic, t)
            val ch = Theory.chord(tn, ChordQuality.MAIOR)
            val up = ch + ch.map { Pitch(it.step, it.alter, it.octave + 1) } + Pitch(tn.step, tn.alter, tn.octave + 2)
            twoHands("Arpejo de $label maior", upDown(up), 0.5, keySig(tn))
        }
    }

    private fun fieldLesson(label: String, tonic: Pitch, major: Boolean, level: Level, prereq: List<String>): Lesson {
        val id = "campo-${if (major) "maior" else "menor"}-$label"
        return Lesson(
            id, "Harmonia", "Campo harmônico de $label ${if (major) "maior" else "menor"}", "Tocar as 7 tríades do campo harmônico em sequência subindo e descendo, sabendo o grau e a função de cada uma.",
            "Formação das tríades por graus, funções (tônica, subdominante, dominante), graus em algarismos romanos.", level,
            "Cada nota da escala vira a raiz de uma tríade que usa só notas da própria escala. As tríades se repetem em padrão: ${if (major) "I ii iii IV V vi vii°" else "i ii° III iv v VI VII"}.",
            listOf("Escreva/ouça as 7 tríades e diga o grau de cada uma.", "Toque mão direita em tríades, mão esquerda só na raiz.", "Faça a sequência subindo e descendo sem olhar.", "Encadeie I–IV–V–I e I–V–vi–IV."),
            null, 60, 80, listOf("Esquecer que o 7º grau é diminuto.", "Errar acidentes das tríades fora do C."), "Sequência completa subindo e descendo a 80 bpm, sem olhar, com 90% de acerto.", prereq, listOf("campo harmônico", "tríades", "graus"),
        ) { t ->
            val tn = shift(tonic, t)
            val f = Theory.harmonicField(tn, major)
            val sc = Score("Campo harmônico de $label ${if (major) "maior" else "menor"}", 4, if (major) keySig(tn) else 0, 4, 4, 80)
            val part = Part("P1", "Piano", mutableListOf(), 'G')
            for ((i, c) in (f + f.dropLast(1).reversed()).withIndex()) {
                val m = Measure(i + 1); c.notes.forEachIndexed { j, p -> m.notes += Note(p, 16, chord = j > 0) }; part.measures += m
            }
            sc.parts += part; sc
        }
    }

    /** Todos os exercícios de piano, em ordem pedagógica. */
    val piano: List<Lesson> by lazy {
        val out = ArrayList<Lesson>()
        val easy = listOf("C", "G", "F", "D", "A", "E")
        var prev: String? = null
        fun lvOf(lab: String) = when (lab) { "C" -> Level.INICIANTE; "G", "F", "D", "A", "E", "B" -> Level.BASICO; else -> Level.INTERMEDIARIO }
        for ((lab, t) in MAJ.sortedWith(compareBy({ lvOf(it.first).ordinal }, { if (it.first in easy) easy.indexOf(it.first) else 10 + MAJ.indexOf(it) }))) {
            val l = scaleLesson(lab, t, ScaleType.MAIOR, lvOf(lab), MAJOR_FING[lab], listOfNotNull(prev))
            out += l; prev = l.id
        }
        for ((lab, t) in MAJ) if (lab in easy || lab == "B") out += arpLesson(lab, t, if (lab == "C") Level.BASICO else Level.INTERMEDIARIO, listOf("esc-maior-$lab"))
        for ((lab, t) in MIN) out += scaleLesson(lab, t, ScaleType.MENOR_NATURAL, if (lab == "A") Level.BASICO else Level.INTERMEDIARIO, MINOR_NAT_FING[lab], listOf("esc-maior-C"))
        for ((lab, t) in MIN) out += scaleLesson(lab, t, ScaleType.MENOR_HARMONICA, Level.AVANCADO, null, listOf("esc-menor_natural-$lab"))
        for ((lab, t) in MIN) out += scaleLesson(lab, t, ScaleType.MENOR_MELODICA, Level.AVANCADO, null, listOf("esc-menor_harmonica-$lab"))
        for (m in listOf(ScaleType.DORICO, ScaleType.FRIGIO, ScaleType.LIDIO, ScaleType.MIXOLIDIO)) out += scaleLesson("C", Pitch('C', 0, 4), m, Level.ESPECIALIZACAO, null, listOf("esc-maior-C"))
        for ((lab, t) in MAJ) out += fieldLesson(lab, t, true, if (lab in easy) Level.BASICO else Level.INTERMEDIARIO, listOf("esc-maior-$lab"))
        for ((lab, t) in MIN) out += fieldLesson(lab, t, false, Level.INTERMEDIARIO, listOf("esc-menor_natural-$lab"))
        out
    }

    fun byId(id: String) = piano.firstOrNull { it.id == id }

    /** Exercícios liberados: todos os pré-requisitos concluídos. */
    fun unlocked(l: Lesson, p: Progress) = l.prerequisites.all { p.map[it]?.done == true }

    /** Próximo exercício recomendado: o primeiro liberado e não concluído, preferindo o que tem menor acerto já tentado. */
    fun next(p: Progress): Lesson? {
        val open = piano.filter { p.map[it.id]?.done != true && unlocked(it, p) }
        return open.firstOrNull { (p.map[it.id]?.attempts ?: 0) > 0 && (p.map[it.id]?.bestHit ?: 0) < 90 } ?: open.firstOrNull()
    }
}
