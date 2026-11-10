package app.partitura.core

import kotlin.random.Random

/** Treino de leitura: nota na pauta (clave de Sol/Fá) e valor das figuras em tempos. */
object Reading {
    class NoteQ(val clef: Char, val pitch: Pitch, val answer: Char) { fun score(): Score {
        val sc = Score("Nota", 4, 0, 4, 4, 60)
        sc.parts += Part("P1", "Piano", mutableListOf(Measure(1).also { it.notes += Note(pitch, 16) }), clef); return sc
    } }
    class DurQ(val title: String, val notes: List<Pair<Pitch?, Int>>, val answerBeats: Double)

    val NOTE_NAMES = listOf('C', 'D', 'E', 'F', 'G', 'A', 'B')
    fun noteName(c: Char) = Study.ptShort(c, 0)

    /** [ledger] inclui notas acima/abaixo da pauta (linhas suplementares). Só notas naturais. */
    fun noteQuiz(clef: Char, count: Int = 10, ledger: Boolean = false, seed: Long = 1): List<NoteQ> {
        val rnd = Random(seed)
        // posição diatônica absoluta (oitava*7 + índice da letra)
        fun dia(step: Char, oct: Int) = oct * 7 + NOTE_NAMES.indexOf(step)
        val (lo, hi) = if (clef == 'G') (dia('E', 4) to dia('F', 5)) else (dia('G', 2) to dia('A', 3))
        val ext = if (ledger) 3 else 0
        val pool = (lo - ext..hi + ext).toList()
        val out = ArrayList<NoteQ>(); var last = -1
        while (out.size < count) {
            val d = pool[rnd.nextInt(pool.size)]; if (d == last) continue
            last = d
            val step = NOTE_NAMES[Math.floorMod(d, 7)]; val oct = Math.floorDiv(d, 7)
            out += NoteQ(clef, Pitch(step, 0, oct), step)
        }
        return out
    }

    val DUR_OPTIONS = listOf(0.25, 0.5, 1.0, 1.5, 2.0, 3.0, 4.0)
    fun durLabel(b: Double) = when (b) { 0.25 -> "¼ de tempo"; 0.5 -> "½ tempo"; 1.0 -> "1 tempo"; 1.5 -> "1½ tempo"; 2.0 -> "2 tempos"; 3.0 -> "3 tempos"; else -> "4 tempos" }

    /** Compassos de 4/4 (divisões = 4 por semínima) cheios de uma figura; a resposta é a duração da primeira figura em tempos (semínima = 1). */
    fun durQuiz(count: Int = 10, seed: Long = 1): List<DurQ> {
        val c = Pitch('C', 0, 5); val r: Pitch? = null
        val bank = listOf(
            DurQ("semibreve", listOf(c to 16), 4.0), DurQ("mínima", List(2) { c to 8 }, 2.0), DurQ("semínima", List(4) { c to 4 }, 1.0),
            DurQ("colcheia", List(8) { c to 2 }, 0.5), DurQ("semicolcheia", List(16) { c to 1 }, 0.25),
            DurQ("mínima pontuada", listOf(c to 12, c to 4), 3.0), DurQ("semínima pontuada", listOf(c to 6, c to 2, c to 6, c to 2), 1.5),
            DurQ("pausa de semibreve", listOf(r to 16), 4.0), DurQ("pausa de mínima", listOf(c to 8, r to 8), 2.0), DurQ("pausa de semínima", listOf(c to 4, r to 4, c to 4, c to 4), 1.0),
            DurQ("pausa de colcheia", listOf(c to 4, c to 4, c to 4, c to 2, r to 2), 0.5),
        )
        val rnd = Random(seed)
        val out = ArrayList<DurQ>(); var last = -1
        while (out.size < count) { val i = rnd.nextInt(bank.size); if (i == last) continue; last = i; out += bank[i] }
        return out
    }
    /** Para pausas: a pergunta é sobre a figura marcada; índice do elemento perguntado. */
    fun askIndex(q: DurQ): Int = if (q.title.startsWith("pausa de") && q.notes.size > 1) q.notes.indexOfFirst { it.first == null } else 0
    fun durScore(q: DurQ): Score {
        val s = Score("Duração", 4, 0, 4, 4, 60)
        val m = Measure(1); for ((p, d) in q.notes) m.notes += Note(p, d)
        s.parts += Part("P1", "Piano", mutableListOf(m), 'G'); return s
    }
    /** Resposta certa levando em conta a figura perguntada (pausas perguntam a pausa, não a nota). */
    fun answer(q: DurQ): Double = if (q.title.startsWith("pausa de")) q.notes[askIndex(q)].second / 4.0 else q.answerBeats

    fun verdict(hit: Int, total: Int = 10): String = when {
        hit >= 90 -> "Excelente! $hit% de acerto."
        hit >= 70 -> "Muito bom! $hit% de acerto."
        hit >= 50 -> "Parabéns, você acertou $hit%. Continue treinando."
        else -> "Você acertou $hit%. Vamos revisar e tentar de novo."
    }
}
