package app.partitura.core

/** Arpejos de várias qualidades, arpejo diatônico e preenchimentos (fills) entre acordes, em todos os tons. */
object Fills {
    /** Um evento: uma ou mais alturas soando juntas (lista vazia = pausa) com duração em divisões. */
    private class Ev(val ps: List<Pitch>, val d: Int)
    private fun n(p: Pitch, d: Int) = Ev(listOf(p), d)
    private fun rest(d: Int) = Ev(emptyList(), d)
    private fun up(p: Pitch, o: Int) = Pitch(p.step, p.alter, p.octave + o)

    private val LET = listOf('C', 'D', 'E', 'F', 'G', 'A', 'B')

    /** Graus diatônicos de uma tonalidade maior, contando em graus acima/abaixo da tônica. */
    private class Key(val tonic: Pitch) {
        private val alters = Theory.scale(tonic, ScaleType.MAIOR).take(7).associate { it.step to it.alter }
        private val t0 = tonic.octave * 7 + LET.indexOf(tonic.step)
        fun at(k: Int): Pitch { val a = t0 + k; val l = LET[Math.floorMod(a, 7)]; return Pitch(l, alters.getValue(l), Math.floorDiv(a, 7)) }
        fun idx(p: Pitch) = p.octave * 7 + LET.indexOf(p.step) - t0
    }

    private fun score(title: String, fifths: Int, tempo: Int, rh: List<List<Ev>>, lh: List<List<Ev>>): Score {
        val s = Score(title, 4, fifths, 4, 4, tempo)
        for ((id, name, clef, bars) in listOf(Quad("P1", "Mão direita", 'G', rh), Quad("P2", "Mão esquerda", 'F', lh))) {
            val part = Part(id, name, mutableListOf(), clef, "G1")
            bars.forEachIndexed { i, b ->
                val m = Measure(i + 1)
                for (e in b) {
                    if (e.ps.isEmpty()) m.notes += Note(null, e.d)
                    else e.ps.forEachIndexed { j, p -> m.notes += Note(p, e.d, chord = j > 0) }
                }
                part.measures += m
            }
            s.parts += part
        }
        return s
    }
    private class Quad(val a: String, val b: String, val c: Char, val d: List<List<Ev>>) {
        operator fun component1() = a; operator fun component2() = b; operator fun component3() = c; operator fun component4() = d
    }

    // ------------------------------------------------------------------ arpejos por qualidade (e inversões), a partir de Dó
    private fun qualityArp(typeId: String, label: String, level: Level, inversion: Int): Lesson {
        val t = Study.CHORDS.first { it.id == typeId }
        val inv = if (inversion == 0) "" else " (${inversion}ª inversão)"
        return Lesson(
            "arp-$typeId-C-$inversion", "Arpejos", "Arpejo de Dó $label$inv",
            "Tocar o arpejo de Dó ${label.lowercase()}$inv em duas oitavas, subindo e descendo, em oitavas paralelas.",
            "Fórmula do acorde (${t.tones}), inversões, movimento contínuo da mão.", level,
            "${t.name} = ${t.tones}. O arpejo toca as notas do acorde uma de cada vez. Na inversão, a nota mais grave deixa de ser a fundamental, mas o desenho se repete igual. Use o botão de tom para praticar nos 12 tons.",
            listOf("Toque o acorde em bloco e ouça.", "Arpejo de uma oitava, mão separada.", "Duas oitavas, mãos juntas em oitavas paralelas.", "Aumente o andamento só depois de 3 execuções limpas."),
            null, 60, 100, listOf("Esquecer a nota alterada (${t.tones}).", "Perder o ritmo na passagem do polegar."),
            "Duas oitavas a 100 bpm (colcheias) com 90% de acerto.", listOf("arp-maior-C"), listOf("arpejo", "acordes", "inversões"),
        ) { tr ->
            val root = Curriculum.shift(Pitch('C', 0, 4), tr)
            var ch = Study.chordNotes(root, t)
            repeat(inversion) { ch = ch.drop(1) + up(ch.first(), 1) }
            val seq = ch + ch.map { up(it, 1) } + up(ch.first(), 2)
            Curriculum.twoHands("Arpejo ${Study.chordName("C", t)}", seq + seq.dropLast(1).reversed(), 0.5, Curriculum.keySig(root))
        }
    }

    // ------------------------------------------------------------------ aquecimento: arpejo diatônico (um compasso por acorde do campo)
    private fun diatonicArp(label: String, tonic: Pitch): Lesson = Lesson(
        "arp-diatonico-$label", "Arpejos", "Arpejo diatônico de $label maior",
        "Tocar o arpejo de cada acorde do campo harmônico de $label maior, subindo e descendo duas oitavas, em oitavas paralelas.",
        "Campo harmônico, tríades por grau, arpejo em duas oitavas.", Level.AVANCADO,
        "Um compasso para cada acorde do campo (I ii iii IV V vi vii°). O desenho da mão é o mesmo; só mudam as notas do acorde. O 7º grau é diminuto.",
        listOf("Cada acorde em bloco, ouvindo a cor.", "Um arpejo por vez, devagar, mão separada.", "Encadeie os 7 compassos sem parar.", "Suba o andamento aos poucos."),
        null, 60, 96, listOf("Errar o 7º grau (diminuto).", "Acelerar na subida e segurar na descida."),
        "Os 7 arpejos em sequência a 96 bpm com 90% de acerto.", listOf("arp-maior-C", "campo-maior-$label"), listOf("arpejo", "campo harmônico", "coordenação"),
    ) { tr ->
        val tn = Curriculum.shift(tonic, tr)
        val bars = Theory.harmonicField(tn, true).map { c ->
            val (r, th, fi) = c.notes
            val seq = listOf(r, th, fi, up(r, 1), up(th, 1), up(fi, 1), up(r, 2), up(fi, 1), up(th, 1), up(r, 1), fi, th)
            seq.map { n(it, 1) } + n(r, 4)
        }
        val lh = bars.map { b -> b.map { e -> Ev(e.ps.map { up(it, -1) }, e.d) } }
        score("Arpejo diatônico de $label", Curriculum.keySig(tn), 80, bars, lh)
    }

    // ------------------------------------------------------------------ preenchimentos entre os acordes de I–V–vi–IV
    private enum class Kind(val id: String, val title: String) { ASC("asc", "escala subindo"), DESC("desc", "escala descendo"), ENCL("encl", "aproximação cromática") }

    private fun fillLesson(label: String, tonic: Pitch, kind: Kind): Lesson = Lesson(
        "fill-${kind.id}-$label", "Preenchimentos", "Preenchimento em $label: ${kind.title} (I–V–vi–IV)",
        "Tocar a progressão I–V–vi–IV em $label com baixo na esquerda e, na direita, o acorde seguido de um preenchimento que leva até o acorde seguinte.",
        "Acordes do campo, notas da escala, nota de chegada (a nota mais aguda do próximo acorde).",
        if (kind == Kind.ENCL) Level.AVANCADO else Level.INTERMEDIARIO,
        when (kind) {
            Kind.ASC -> "Nos tempos 3 e 4 a mão direita sobe pela escala e para um grau abaixo da nota mais aguda do acorde seguinte, que então soa no tempo 1. Todas as notas do preenchimento são da escala de $label."
            Kind.DESC -> "Igual ao preenchimento subindo, mas descendo: a mão desce pela escala e chega por cima à nota mais aguda do acorde seguinte."
            Kind.ENCL -> "Antes do acorde seguinte, a mão toca a nota um grau acima e depois a nota meio tom abaixo da nota de chegada (essa é cromática, fora da escala). A tensão resolve quando o acorde cai no tempo 1."
        },
        listOf("Esquerda: raiz e quinta de cada acorde, sozinha.", "Direita: só acorde e preenchimento, sozinha.", "Junte as mãos devagar, com metrônomo.", "Repita nos outros tons (botão de tom)."),
        null, 56, 88, listOf("Chegar atrasado ao acorde seguinte.", "Preenchimento forte demais, escondendo o acorde.", "Esquecer a alteração da escala."),
        "A progressão completa a 88 bpm com 90% de acerto, sem parar.", listOf("campo-maior-$label"), listOf("preenchimento", "campo harmônico", "acompanhamento"),
    ) { tr ->
        val tn = Curriculum.shift(tonic, tr)
        val k = Key(tn)
        val field = Theory.harmonicField(tn, true)
        val degs = listOf(0, 4, 5, 3)
        val rh = ArrayList<List<Ev>>(); val lh = ArrayList<List<Ev>>()
        for ((i, d) in degs.withIndex()) {
            val c = field[d]
            val next = field[degs[(i + 1) % degs.size]]
            // o acorde seguinte é tocado numa posição próxima: usa-se a nota mais aguda dele como alvo
            val tgt = k.idx(next.notes[2])
            val fill: List<Ev> = when (kind) {
                Kind.ASC -> (4 downTo 1).map { n(k.at(tgt - it), 2) }
                Kind.DESC -> (4 downTo 1).map { n(k.at(tgt + it), 2) }
                Kind.ENCL -> { val t = k.at(tgt); listOf(rest(4), n(k.at(tgt + 1), 2), n(Pitch(t.step, t.alter - 1, t.octave), 2)) }
            }
            rh += listOf(Ev(c.notes, 8)) + fill
            lh += listOf(n(up(c.root, -1), 8), n(up(c.notes[2], -1), 8))
        }
        score("Preenchimento (${kind.title}) em $label", Curriculum.keySig(tn), 72, rh, lh)
    }

    // ------------------------------------------------------------------ frase de transição: do acorde de Dó ao de Sol (graus 1-5-8-9-10)
    private fun linkLesson(label: String, tonic: Pitch): Lesson = Lesson(
        "frase-1-5-8-$label", "Preenchimentos", "Frase de ligação em $label: 1–5–8–9–10",
        "Tocar uma frase curta de ligação (graus 1, 5, 8, 9 e 10 da escala de $label) que parte do acorde de I e chega ao acorde de V.",
        "Graus da escala, frase de ligação, arpejo curto de I.", Level.INTERMEDIARIO,
        "A frase salta da tônica à quinta (1–5), chega à oitava (8) e continua por grau conjunto (9, 10). Em Dó: Dó–Sol–Dó–Ré–Mi. Termina antes do acorde de V, que cai no tempo 1 do compasso seguinte. É um recurso muito usado para ligar acordes.",
        listOf("Toque a escala de $label até a oitava e a nona.", "Direita sozinha: 1–5–8–9–10 em colcheias.", "Esquerda: raiz do I e raiz do V.", "Mãos juntas, andamento lento."),
        null, 56, 92, listOf("Esquecer que a nona é um tom acima da oitava.", "Tocar a frase sem pausa antes do acorde de chegada."),
        "Frase limpa a 92 bpm com 90% de acerto.", listOf("campo-maior-$label"), listOf("preenchimento", "graus", "frase"),
    ) { tr ->
        val tn = Curriculum.shift(tonic, tr)
        val k = Key(tn)
        val f = Theory.harmonicField(tn, true)
        val i1 = f[0]; val v = f[4]
        val ph = listOf(0, 4, 7, 8, 9).map { n(k.at(it), 2) }
        val bar1 = listOf(Ev(i1.notes, 8)) + ph.take(4)
        val rh = listOf(bar1, listOf(ph[4], rest(2), rest(4), Ev(v.notes, 8)))
        score("Frase de ligação 1-5-8-9-10 em $label", Curriculum.keySig(tn), 80, rh, listOf(listOf(n(up(i1.root, -1), 16)), listOf(n(up(v.root, -1), 16))))
    }

    fun lessons(): List<Lesson> {
        val out = ArrayList<Lesson>()
        // arpejos de várias qualidades, em Dó (use o botão de tom para os outros)
        out += qualityArp("min", "menor", Level.BASICO, 0)
        out += qualityArp("maj", "maior", Level.INTERMEDIARIO, 1)
        out += qualityArp("maj", "maior", Level.INTERMEDIARIO, 2)
        out += qualityArp("min", "menor", Level.INTERMEDIARIO, 1)
        out += qualityArp("min", "menor", Level.INTERMEDIARIO, 2)
        out += qualityArp("dim", "diminuto", Level.INTERMEDIARIO, 0)
        out += qualityArp("aug", "aumentado", Level.INTERMEDIARIO, 0)
        out += qualityArp("7", "dominante (7)", Level.AVANCADO, 0)
        out += qualityArp("maj7", "com sétima maior", Level.AVANCADO, 0)
        out += qualityArp("m7", "menor com sétima", Level.AVANCADO, 0)
        out += qualityArp("add9", "com nona (add9)", Level.AVANCADO, 0)
        out += qualityArp("9", "dominante com nona", Level.ESPECIALIZACAO, 0)
        val easy = listOf("C", "G", "F", "D", "A", "E")
        for ((lab, t) in Study.MAJOR_TONICS) if (lab in easy) out += diatonicArp(lab, t)
        for ((lab, t) in Study.MAJOR_TONICS) for (k in Kind.values()) out += fillLesson(lab, t, k)
        for ((lab, t) in Study.MAJOR_TONICS) out += linkLesson(lab, t)
        return out
    }
}
