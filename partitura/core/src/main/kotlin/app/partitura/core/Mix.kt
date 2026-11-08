package app.partitura.core

/**
 * Faixa de reprodução: uma parte inteira ([voice] = 0) ou uma voz de uma pauta (1 = voz de cima, 2 = voz de baixo).
 * Assim um coro escrito em duas pautas (S+C e T+B) pode ser ouvido ou silenciado voz por voz.
 * [unisonIn]: compassos em que a voz 2 não existe porque as duas cantam igual; nesses a faixa da voz 2 toca a voz 1.
 */
class Track(val id: String, val name: String, val part: Int, val voice: Int, val unisonIn: Set<Int> = emptySet())

/** Mesa de som: volume, silêncio, solo e timbre de cada faixa. */
class Mix(val tracks: List<Track>) {
    val volume = DoubleArray(tracks.size) { 1.0 }
    val mute = BooleanArray(tracks.size)
    val solo = BooleanArray(tracks.size)
    /** null = timbre original da parte */
    val timbre = arrayOfNulls<String>(tracks.size)

    private val byPart: Map<Int, List<Int>> = tracks.indices.groupBy { tracks[it].part }

    fun audible(i: Int): Boolean = !mute[i] && (solo.none { it } || solo[i])

    fun timbreId(i: Int, s: Score): String = timbre[i] ?: s.parts.getOrNull(tracks[i].part)?.inst ?: "piano"

    /** Faixas que tocam esta nota. */
    fun tracksFor(part: Int, voice: Int, measure: Int): List<Int> {
        val l = byPart[part] ?: return emptyList()
        if (l.size == 1) return l
        val out = ArrayList<Int>(2)
        for (i in l) {
            val t = tracks[i]
            val ok = when (t.voice) {
                0 -> true
                1 -> voice != 2
                else -> voice == t.voice || (voice == 1 && measure in t.unisonIn)
            }
            if (ok) out += i
        }
        return out
    }

    /** Cópia independente (o player recebe uma cópia, então mexer na mesa durante o som não causa corrida). */
    fun snapshot(): Mix { val m = Mix(tracks); copyTo(m); return m }

    private fun copyTo(m: Mix) {
        for (i in tracks.indices) { m.volume[i] = volume[i]; m.mute[i] = mute[i]; m.solo[i] = solo[i]; m.timbre[i] = timbre[i] }
    }

    /** Aproveita os ajustes de uma mesa anterior (pelo id da faixa). */
    fun inherit(old: Mix?) {
        if (old == null) return
        for (i in tracks.indices) {
            val j = old.tracks.indexOfFirst { it.id == tracks[i].id }
            if (j >= 0) { volume[i] = old.volume[j]; mute[i] = old.mute[j]; solo[i] = old.solo[j]; timbre[i] = old.timbre[j] }
        }
    }

    fun setAllTimbre(id: String?) { for (i in tracks.indices) timbre[i] = id }
}

object Tracks {
    private val VOICE_WORD = Regex("sopr|contralto|alto|tenor|bar[ií]tono|bass|baixo|solo", RegexOption.IGNORE_CASE)

    private fun voiceMeasures(p: Part, v: Int): Set<Int> =
        p.measures.withIndex().filter { (_, m) -> m.notes.any { it.voice == v && it.pitch != null } }.map { it.index }.toSet()

    /** Monta as faixas: vozes separadas para coros (S/C/T/B), uma faixa por instrumento nos demais. */
    fun derive(s: Score): Mix {
        val tracks = ArrayList<Track>()
        val choir = s.parts.indices.filter { s.parts[it].inst == "voice" }
        val unnamed = choir.filter { !VOICE_WORD.containsMatchIn(s.parts[it].name) }
        val sOrder = HashMap<Int, Int>()
        unnamed.forEachIndexed { k, pi -> sOrder[pi] = k }
        for ((pi, p) in s.parts.withIndex()) {
            if (p.inst != "voice") {
                val staves = s.parts.count { it.group == p.group }
                val base = p.name.substringBefore(" (pauta")
                val name = if (staves > 1) "$base — " + (if (p.clef == 'F') "mão esquerda" else "mão direita") else base
                tracks += Track("p$pi", name, pi, 0)
                continue
            }
            val v2 = voiceMeasures(p, 2)
            val two = v2.size >= 2
            val k = sOrder[pi]
            if (k == null) {            // já tem nome de voz (Soprano, Tenor, Solo…)
                if (two) {
                    val v1 = voiceMeasures(p, 1)
                    tracks += Track("p${pi}v1", p.name, pi, 1)
                    tracks += Track("p${pi}v2", p.name + " (2ª voz)", pi, 2, v1 - v2)
                } else tracks += Track("p$pi", p.name, pi, 0)
                continue
            }
            val n = unnamed.size
            val names: List<String> = when {
                n == 2 && two -> if (k == 0) listOf("Soprano", "Contralto") else listOf("Tenor", "Baixo")
                n == 2 -> listOf(if (k == 0) "Soprano + Contralto" else "Tenor + Baixo")
                n == 4 -> listOf(listOf("Soprano", "Contralto", "Tenor", "Baixo")[k])
                n == 1 && two -> listOf("Soprano", "Contralto")
                n == 1 -> listOf("Coro")
                else -> if (two) listOf("Voz ${k + 1}a", "Voz ${k + 1}b") else listOf("Voz ${k + 1}")
            }
            if (two && names.size == 2) {
                val v1 = voiceMeasures(p, 1)
                tracks += Track("p${pi}v1", names[0], pi, 1)
                tracks += Track("p${pi}v2", names[1], pi, 2, v1 - v2)
            } else tracks += Track("p$pi", names[0], pi, 0)
        }
        return Mix(tracks)
    }
}
