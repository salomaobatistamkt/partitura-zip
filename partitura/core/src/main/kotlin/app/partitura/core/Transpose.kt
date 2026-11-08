package app.partitura.core

object Transpose {
    private val FIFTHS_STEPS = listOf('F', 'C', 'G', 'D', 'A', 'E', 'B')
    /** Posição na linha das quintas (F=-1 ... B=5) somada às alterações dá o "fifths" da nota. */
    private fun fifthsOf(p: Pitch) = (FIFTHS_STEPS.indexOf(p.step) - 1) + 7 * p.alter

    private fun fromFifths(f: Int, midiClass: Int, octaveRef: Int): Pitch {
        val idx = Math.floorMod(f + 1, 7)
        val alter = Math.floorDiv(f + 1, 7)
        val step = FIFTHS_STEPS[idx]
        val natural = Pitch.STEP_SEMITONES.getValue(step) + alter
        var oct = octaveRef
        // escolhe a oitava que reproduz exatamente a classe de altura alvo
        val base = (oct + 1) * 12 + natural
        val diff = midiClass - base
        oct += Math.floorDiv(diff, 12) + if (Math.floorMod(diff, 12) != 0) 1 else 0
        return Pitch(step, alter, oct)
    }

    /** Transpõe por semitons; a grafia segue a armadura de destino (bemóis para baixo da armadura, sustenidos para cima). */
    fun bySemitones(score: Score, semitones: Int): Score {
        val newFifths = (score.fifths + 7 * semitones).let { var f = it; while (f > 6) f -= 12; while (f < -6) f += 12; f }
        val out = score.copy(fifths = newFifths, parts = mutableListOf())
        for (p in score.parts) {
            val np = Part(p.id, p.name, mutableListOf(), p.clef, p.group, p.inst)
            for (m in p.measures) np.measures += m.dup().also { it.notes.clear(); it.harmony.clear(); it.fifths = m.fifths?.let { f -> var g = f + 7 * semitones; while (g > 6) g -= 12; while (g < -6) g += 12; g } }.let { base -> Measure(m.number, m.notes.map { n ->
                val pt = n.pitch ?: return@map n
                // desloca na linha das quintas pelo equivalente a semitons (7 quintas por semitom, mod 12)
                var f = fifthsOf(pt) + 7 * semitones
                val target = pt.midi + semitones
                // normaliza para a faixa da armadura de destino
                val lo = newFifths - 5; val hi = newFifths + 6
                while (f > hi) f -= 12
                while (f < lo) f += 12
                n.copy(pitch = fromFifths(f, target, pt.octave))
            }.toMutableList(), m.harmony.map { h ->
                val r = Spell.forMidi(60 + h.rootPc + semitones, newFifths)
                val b = h.bassStep?.let { Spell.forMidi(60 + h.bassPc + semitones, newFifths) }
                h.copy(step = r.step, alter = r.alter, bassStep = b?.step, bassAlter = b?.alter ?: 0)
            }.toMutableList(), base.dyn, base.tempo, base.fifths, base.beats, base.beatType, base.repeatStart, base.repeatEnd, base.volta, base.rit, base.clef, base.mark) }
            out.parts += np
        }
        return out
    }
}
