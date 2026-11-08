package app.partitura.core

object Validate {
    /** Compassos cuja soma de durações (por voz) difere da fórmula de compasso. */
    fun measureDurations(s: Score): List<String> {
        val expected = s.divisions * 4 * s.beats / s.beatType
        val issues = mutableListOf<String>()
        for (p in s.parts) for ((i, m) in p.measures.withIndex()) {
            val byVoice = m.notes.filter { !it.chord }.groupBy { it.voice }.mapValues { e -> e.value.sumOf { it.duration } }
            for ((v, total) in byVoice) {
                val pickup = i == 0 && total < expected
                if (total != expected && !pickup) issues += "${p.name}, compasso ${m.number}, voz $v: ${total}/${expected} divisões"
            }
        }
        return issues
    }
}
