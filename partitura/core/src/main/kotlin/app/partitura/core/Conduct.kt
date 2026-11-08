package app.partitura.core

import kotlin.math.floor

/**
 * Padrões de regência (mão direita, visto por quem rege). Coordenadas de 0 a 1: x da esquerda para a direita,
 * y de cima para baixo. Cada tempo termina num "ictus" (o ponto onde o gesto marca o tempo).
 */
class Pt(val x: Float, val y: Float)

class ConductPattern(
    val beats: Int,                 // gestos por compasso
    val beatQuarters: Double,       // duração de cada gesto em semínimas (compasso composto: 1,5)
    val sub: Int,                   // subdivisões por gesto (6/8 = 2 gestos de 3 colcheias)
    val title: String,
    val steps: List<String>,        // instrução de cada gesto
    val note: String?,              // aviso (padrão genérico, etc.)
    val waypoints: List<Pt>,
    val ictus: List<Int>,           // índice (em waypoints) do ictus de cada gesto
) {
    val measureQuarters: Double get() = beats * beatQuarters

    /** Posição da batuta na fase [phase] (em gestos desde o início do compasso; qualquer valor ≥ 0, repete a cada [beats]). */
    fun position(phase: Double): Pt {
        val n = beats; val m = waypoints.size
        val fl = floor(phase)
        val i = Math.floorMod(fl.toInt(), n)
        val u = phase - fl
        val a = ictus[i]; val b = ictus[(i + 1) % n]
        val steps = (Math.floorMod(b - a, m)).let { if (it == 0) m else it }
        val e = u * u * (3 - 2 * u)
        val s = e * steps
        val k = minOf(s.toInt(), steps - 1)
        val f = (s - k).toFloat()
        val ia = (a + k) % m
        val p0 = waypoints[Math.floorMod(ia - 1, m)]; val p1 = waypoints[ia]; val p2 = waypoints[(ia + 1) % m]; val p3 = waypoints[(ia + 2) % m]
        fun cr(a0: Float, a1: Float, a2: Float, a3: Float) = 0.5f * ((2 * a1) + (-a0 + a2) * f + (2 * a0 - 5 * a1 + 4 * a2 - a3) * f * f + (-a0 + 3 * a1 - 3 * a2 + a3) * f * f * f)
        return Pt(cr(p0.x, p1.x, p2.x, p3.x), cr(p0.y, p1.y, p2.y, p3.y))
    }

    /** Pontos para desenhar o caminho completo do compasso. */
    fun path(samplesPerBeat: Int = 40): List<Pt> = (0 until beats * samplesPerBeat).map { position(it.toDouble() / samplesPerBeat) }

    fun ictusPoint(beat: Int): Pt = waypoints[ictus[Math.floorMod(beat, beats)]]
}

object Conducting {
    private val TOP = Pt(.54f, .12f)
    private fun pts(vararg v: Float) = (0 until v.size / 2).map { Pt(v[2 * it], v[2 * it + 1]) }

    /** Padrão adequado à fórmula de compasso. Compassos compostos (6/8, 9/8, 12/8) regem-se em 2, 3 e 4. */
    fun forMeter(beats: Int, beatType: Int): ConductPattern {
        val compound = beatType == 8 && beats % 3 == 0 && beats >= 6
        val n = if (compound) beats / 3 else if (beatType == 8 && beats == 3) 1 else beats
        val beatQ = if (compound) 1.5 else if (beatType == 8 && beats == 3) 1.5 else 4.0 / beatType
        val sub = if (compound || (beatType == 8 && beats == 3)) 3 else 1
        val unit = when (beatType) { 2 -> "mínima"; 8 -> "colcheia"; else -> "semínima" }
        val sfx = if (sub > 1) " (cada gesto = 3 colcheias)" else ""
        val title = "$beats/$beatType em $n" + if (n == 1) " (um gesto por compasso)" else ""
        return when (n) {
            1 -> ConductPattern(1, beatQ, sub, title, listOf("1 · desce, abre para a direita, sobe e volta" + sfx),
                null, pts(.5f, .86f, .82f, .62f, .74f, .22f, .52f, .10f), listOf(0))
            2 -> ConductPattern(2, beatQ, sub, title, listOf("1 · desce reto (tempo forte)$sfx", "2 · sobe um pouco para a direita e volta ao alto"),
                null, pts(.5f, .86f, .64f, .62f, .68f, .38f, .56f, .14f), listOf(0, 2))
            3 -> ConductPattern(3, beatQ, sub, title, listOf("1 · desce reto (tempo forte)$sfx", "2 · abre para a direita", "3 · sobe e volta ao ponto de partida"),
                null, pts(.5f, .86f, .70f, .80f, .84f, .58f, .66f, .28f, TOP.x, TOP.y), listOf(0, 2, 3))
            4 -> ConductPattern(4, beatQ, sub, title, listOf("1 · desce reto (tempo forte)$sfx", "2 · vai para a esquerda, para dentro do corpo", "3 · cruza para a direita", "4 · sobe e volta ao ponto de partida"),
                null, pts(.5f, .86f, .34f, .80f, .20f, .60f, .50f, .52f, .80f, .60f, .66f, .28f, TOP.x, TOP.y), listOf(0, 2, 4, 5))
            5 -> ConductPattern(5, beatQ, sub, title, listOf("1 · desce reto (tempo forte)", "2 · vai para a esquerda", "3 · um pouco à direita, ainda dentro", "4 · abre para a direita", "5 · sobe e volta ao ponto de partida"),
                "Um dos jeitos usuais de reger 5 tempos (3 + 2). Alguns regentes preferem 2 + 3.",
                pts(.5f, .86f, .34f, .80f, .18f, .62f, .42f, .58f, .62f, .54f, .82f, .60f, .66f, .28f, TOP.x, TOP.y), listOf(0, 2, 3, 5, 6))
            6 -> ConductPattern(6, beatQ, sub, title, listOf("1 · desce reto (tempo forte)", "2 · esquerda, para baixo", "3 · esquerda, mais alto", "4 · cruza para a direita", "5 · direita, para baixo", "6 · sobe e volta ao ponto de partida"),
                if (beats == 6 && beatType == 4) "6/4 também pode ser regido em 2 gestos de 3 tempos." else null,
                pts(.5f, .86f, .33f, .82f, .20f, .68f, .16f, .46f, .46f, .34f, .74f, .46f, .86f, .66f, .66f, .30f, TOP.x, TOP.y), listOf(0, 2, 3, 5, 6, 7))
            else -> generic(n, beatQ, sub, title)
        }
    }

    private fun generic(n: Int, beatQ: Double, sub: Int, title: String): ConductPattern {
        val kL = (n - 2) / 2; val kR = n - 2 - kL
        val w = ArrayList<Pt>(); val idx = ArrayList<Int>()
        w += Pt(.5f, .86f); idx += 0
        for (j in 0 until kL) { w += Pt(.20f, .70f - .30f * j / maxOf(1, kL - 1)); idx += w.size - 1 }
        w += Pt(.48f, .34f)
        for (j in 0 until kR) { w += Pt(.80f, .40f + .34f * j / maxOf(1, kR - 1)); idx += w.size - 1 }
        w += Pt(.64f, .28f); idx += w.size - 1
        w += TOP
        val steps = (1..n).map { k -> when (k) { 1 -> "1 · desce reto (tempo forte)"; n -> "$k · sobe e volta ao ponto de partida"; else -> "$k · segue o desenho" } }
        return ConductPattern(n, beatQ, sub, title, steps, "Não há um padrão tradicional único para $n gestos: este é um desenho genérico para marcar a contagem.", w, idx)
    }

    /** Fórmulas de compasso oferecidas na tela. */
    val METERS = listOf(2 to 4, 3 to 4, 4 to 4, 6 to 8, 9 to 8, 12 to 8, 2 to 2, 5 to 4, 3 to 8, 6 to 4)
}
