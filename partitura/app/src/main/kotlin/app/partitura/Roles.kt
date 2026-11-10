package app.partitura

import app.partitura.core.*

/** Papel de cada nota na execução: 0 = melodia (amarelo), 1 = mão direita (roxo), 2 = mão esquerda (vermelho). */
object Roles {
    const val MELODY = 0; const val RIGHT = 1; const val LEFT = 2
    val ARGB = intArrayOf(0xFFE07A00.toInt(), 0xFF8E3FD1.toInt(), 0xFFD62F2F.toInt())
    val NAME = arrayOf("melodia", "mão direita", "mão esquerda")

    private fun isVoice(p: Part) = p.inst == "voice" || p.inst == "voice_oo"

    /** Papel base da pauta; nas pautas de Sol de instrumento, a nota mais aguda do acorde vira melodia ([topIsMelody]). */
    private fun base(p: Part) = if (isVoice(p)) MELODY else if (p.clef == 'F') LEFT else RIGHT

    /** Um papel por nota da cena. */
    fun forScene(s: Score, scene: Scene): IntArray {
        val topAt = HashMap<Long, Int>()
        fun key(part: Int, q: Double) = part.toLong() * 1_000_000L + Math.round(q * 96)
        for (n in scene.notes) if (n.midi >= 0) { val k = key(n.part, n.startQ); topAt[k] = maxOf(topAt[k] ?: -1, n.midi) }
        return IntArray(scene.notes.size) { i ->
            val n = scene.notes[i]; val p = s.parts.getOrNull(n.part) ?: return@IntArray RIGHT
            val b = base(p)
            if (b == RIGHT && topAt[key(n.part, n.startQ)] == n.midi) MELODY else b
        }
    }

    /** Papel de cada evento sonoro (para o teclado). */
    fun forEvents(s: Score, ev: List<Synth.Event>): IntArray {
        val topAt = HashMap<Long, Int>()
        fun key(part: Int, t: Double) = part.toLong() * 10_000_000L + Math.round(t * 200)
        for (e in ev) { val k = key(e.part, e.startSec); topAt[k] = maxOf(topAt[k] ?: -1, e.midi) }
        return IntArray(ev.size) { i ->
            val e = ev[i]; val p = s.parts.getOrNull(e.part) ?: return@IntArray RIGHT
            val b = base(p)
            if (b == RIGHT && topAt[key(e.part, e.startSec)] == e.midi) MELODY else b
        }
    }
}
