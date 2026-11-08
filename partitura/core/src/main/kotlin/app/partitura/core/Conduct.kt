package app.partitura.core

import kotlin.math.floor

/**
 * Padrões de regência (mão direita, visto por quem rege). Coordenadas de 0 a 1: x da esquerda para a direita,
 * y de cima para baixo. Cada tempo termina num "ictus" (o ponto onde o gesto marca o tempo).
 * Os gestos podem ter durações diferentes (5/8 = 3+2, 7/8 = 2+2+3 ...): [beatLens] guarda a duração de cada um em semínimas.
 */
class Pt(val x: Float, val y: Float)

class ConductPattern(
    val beats: Int,                 // gestos por compasso
    val beatLens: List<Double>,     // duração de cada gesto em semínimas
    val sub: Int,                   // 3 quando cada gesto vale 3 pulsos (compasso composto), senão 1
    val title: String,
    val steps: List<String>,        // instrução de cada gesto
    val note: String?,              // aviso / explicação
    val waypoints: List<Pt>,
    val ictus: List<Int>,           // índice (em waypoints) do ictus de cada gesto
    val label: String = "",         // nome curto da opção ("em 2", "em 5 · 3+2")
) {
    val measureQuarters: Double = beatLens.sum()
    val beatQuarters: Double get() = measureQuarters / beats
    val even: Boolean get() = beatLens.all { Math.abs(it - beatLens[0]) < 1e-9 }
    private val starts = DoubleArray(beats).also { var a = 0.0; for (i in 0 until beats) { it[i] = a; a += beatLens[i] } }

    /** Fase (gesto + fração) para um instante [q] em semínimas desde o início do compasso; repete a cada compasso. */
    fun phaseAtQuarters(q: Double): Double {
        val m = measureQuarters
        var r = q % m; if (r < 0) r += m
        var i = beats - 1
        for (k in 0 until beats) if (r < starts[k] + beatLens[k] - 1e-12) { i = k; break }
        return i + ((r - starts[i]) / beatLens[i]).coerceIn(0.0, 1.0)
    }

    /** Posição da batuta na fase [phase] (inteiro = ictus desse gesto). Acelera até o ictus e rebate, com "quina" nele. */
    fun position(phase: Double): Pt {
        val n = beats; val m = waypoints.size
        val fl = floor(phase)
        val i = Math.floorMod(fl.toInt(), n)
        val u = phase - fl
        val a = ictus[i]; val b = ictus[(i + 1) % n]
        val cnt = Math.floorMod(b - a, m).let { if (it == 0) m else it }
        val e = 0.30 * u + 0.70 * u * u
        val s = e * cnt
        val k = minOf(s.toInt(), cnt - 1)
        val f = (s - k).toFloat()
        fun w(j: Int) = waypoints[Math.floorMod(a + j, m)]
        val p1 = w(k); val p2 = w(k + 1)
        val p0 = if (k == 0) p1 else w(k - 1)
        val p3 = if (k + 1 >= cnt) p2 else w(k + 2)
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

    /** Opção de regência: grupos (em unidades da fórmula, um grupo = um gesto), forma do desenho e rótulo. */
    class Opt(val groups: List<Int>, val shape: String, val label: String)

    private fun singles(n: Int) = List(n) { 1 }
    private fun autoGroup(b: Int): List<Int> {
        val g = ArrayList<Int>(); var rem = b
        while (rem > 4) { g += 3; rem -= 3 }
        when (rem) { 4 -> { g += 2; g += 2 }; 0 -> {}; else -> g += rem }
        return g
    }
    private fun txt(g: List<Int>) = g.joinToString("+")

    /** Maneiras reconhecidas de reger a fórmula [b]/[t]; a primeira é a mais usual. */
    fun options(b: Int, t: Int): List<Opt> {
        val small = t <= 4              // 2, 4 → pulso é grande; 8, 16 → pulso é pequeno
        fun o(g: List<Int>, shape: String = "", extra: String = "") = Opt(g, shape, "em ${g.size}" + (if (g.any { it != 1 }) " · ${txt(g)}" else "") + extra)
        val r = ArrayList<Opt>()
        when {
            b <= 1 -> r += o(listOf(1))
            b == 2 -> { r += o(listOf(1, 1)); r += o(listOf(2)) }
            b == 3 -> if (small) { r += o(singles(3)); r += o(listOf(3)) } else { r += o(listOf(3)); r += o(singles(3)) }
            b == 4 -> { r += o(singles(4)); r += o(listOf(2, 2)) }
            b == 5 -> {
                if (small) { r += o(singles(5), "5a", " · 3+2"); r += o(singles(5), "5b", " · 2+3"); r += o(listOf(3, 2)); r += o(listOf(2, 3)) }
                else { r += o(listOf(3, 2)); r += o(listOf(2, 3)); r += o(singles(5), "5a", " · 3+2"); r += o(singles(5), "5b", " · 2+3") }
            }
            b == 6 -> if (small) { r += o(singles(6)); r += o(listOf(3, 3)); r += o(listOf(2, 2, 2)) } else { r += o(listOf(3, 3)); r += o(singles(6)); r += o(listOf(2, 2, 2)) }
            b == 7 -> if (small) { r += o(listOf(4, 3)); r += o(listOf(3, 4)); r += o(singles(7)) }
                      else { r += o(listOf(2, 2, 3)); r += o(listOf(3, 2, 2)); r += o(listOf(2, 3, 2)); r += o(listOf(4, 3)); r += o(listOf(3, 4)) }
            b == 8 -> if (small) { r += o(listOf(2, 2, 2, 2)); r += o(listOf(4, 4)); r += o(singles(8)) } else { r += o(listOf(3, 3, 2)); r += o(listOf(2, 2, 2, 2)); r += o(listOf(4, 4)) }
            b == 9 -> { r += o(listOf(3, 3, 3)); r += o(singles(9)) }
            b == 10 -> { r += o(listOf(3, 3, 2, 2)); r += o(listOf(2, 2, 3, 3)); r += o(listOf(5, 5)) }
            b == 11 -> { r += o(listOf(3, 3, 3, 2)); r += o(listOf(2, 3, 3, 3)) }
            b == 12 -> { r += o(listOf(3, 3, 3, 3)); r += o(listOf(6, 6)); r += o(listOf(4, 4, 4)) }
            else -> { r += o(autoGroup(b)); r += o(singles(b)) }
        }
        return r
    }

    private fun unit(t: Int, n: Int) = when (t) { 1 -> if (n == 1) "semibreve" else "semibreves"; 2 -> if (n == 1) "mínima" else "mínimas"; 8 -> if (n == 1) "colcheia" else "colcheias"; 16 -> if (n == 1) "semicolcheia" else "semicolcheias"; 32 -> if (n == 1) "fusa" else "fusas"; else -> if (n == 1) "semínima" else "semínimas" }

    private class Shape(val steps: List<String>, val w: List<Pt>, val ictus: List<Int>, val note: String? = null)

    private fun shape(n: Int, key: String): Shape = when {
        n == 1 -> Shape(listOf("1 · desce, abre para a direita, sobe e volta"), pts(.5f, .86f, .82f, .62f, .74f, .22f, .52f, .10f), listOf(0))
        n == 2 -> Shape(listOf("1 · desce reto (tempo forte)", "2 · sobe um pouco para a direita e volta ao alto"), pts(.5f, .86f, .64f, .62f, .68f, .38f, .56f, .14f), listOf(0, 2))
        n == 3 -> Shape(listOf("1 · desce reto (tempo forte)", "2 · abre para a direita", "3 · sobe e volta ao ponto de partida"), pts(.5f, .86f, .70f, .80f, .84f, .58f, .66f, .28f, TOP.x, TOP.y), listOf(0, 2, 3))
        n == 4 -> Shape(listOf("1 · desce reto (tempo forte)", "2 · vai para a esquerda, para dentro do corpo", "3 · cruza para a direita", "4 · sobe e volta ao ponto de partida"),
            pts(.5f, .86f, .34f, .80f, .20f, .60f, .50f, .52f, .80f, .60f, .66f, .28f, TOP.x, TOP.y), listOf(0, 2, 4, 5))
        n == 5 && key == "5b" -> Shape(listOf("1 · desce reto (tempo forte)", "2 · vai para a esquerda", "3 · cruza para a direita (2º apoio)", "4 · abre à direita, mais alto", "5 · sobe e volta ao ponto de partida"),
            pts(.5f, .86f, .34f, .78f, .20f, .62f, .46f, .58f, .64f, .62f, .84f, .48f, .72f, .26f, TOP.x, TOP.y), listOf(0, 2, 4, 5, 6),
            "Cinco tempos agrupados 2 + 3: o 3 é o segundo apoio.")
        n == 5 -> Shape(listOf("1 · desce reto (tempo forte)", "2 · vai para a esquerda", "3 · um pouco à direita, ainda dentro", "4 · abre para a direita (2º apoio)", "5 · sobe e volta ao ponto de partida"),
            pts(.5f, .86f, .34f, .80f, .18f, .62f, .42f, .58f, .62f, .54f, .82f, .60f, .66f, .28f, TOP.x, TOP.y), listOf(0, 2, 3, 5, 6),
            "Cinco tempos agrupados 3 + 2: o 4 é o segundo apoio.")
        n == 6 -> Shape(listOf("1 · desce reto (tempo forte)", "2 · esquerda, para baixo", "3 · esquerda, mais alto", "4 · cruza para a direita", "5 · direita, para baixo", "6 · sobe e volta ao ponto de partida"),
            pts(.5f, .86f, .33f, .82f, .20f, .68f, .16f, .46f, .46f, .34f, .74f, .46f, .86f, .66f, .66f, .30f, TOP.x, TOP.y), listOf(0, 2, 3, 5, 6, 7))
        else -> generic(n)
    }

    private fun generic(n: Int): Shape {
        val kL = (n - 2) / 2; val kR = n - 2 - kL
        val w = ArrayList<Pt>(); val idx = ArrayList<Int>()
        w += Pt(.5f, .86f); idx += 0
        for (j in 0 until kL) { w += Pt(.20f, .70f - .30f * j / maxOf(1, kL - 1)); idx += w.size - 1 }
        w += Pt(.48f, .34f)
        for (j in 0 until kR) { w += Pt(.80f, .40f + .34f * j / maxOf(1, kR - 1)); idx += w.size - 1 }
        w += Pt(.64f, .28f); idx += w.size - 1
        w += TOP
        val steps = (1..n).map { k -> when (k) { 1 -> "1 · desce reto (tempo forte)"; n -> "$k · sobe e volta ao ponto de partida"; else -> "$k · segue o desenho" } }
        return Shape(steps, w, idx, "Não há um padrão tradicional único para $n gestos: este é um desenho genérico para marcar a contagem.")
    }

    /** Padrão da opção [opt] (0 = a mais usual) da fórmula [beats]/[beatType]. */
    fun forMeter(beats: Int, beatType: Int, opt: Int = 0): ConductPattern {
        val b = beats.coerceAtLeast(1); val t = beatType.coerceAtLeast(1)
        val all = options(b, t)
        val o = all[opt.coerceIn(0, all.size - 1)]
        val n = o.groups.size
        val q = 4.0 / t
        val lens = o.groups.map { it * q }
        val sh = shape(n, o.shape)
        val grouped = o.groups.any { it != 1 }
        val steps = sh.steps.mapIndexed { i, s -> if (grouped) "$s  (vale ${o.groups[i]} ${unit(t, o.groups[i])})" else s }
        val compound = grouped && o.groups.all { it == 3 }
        val title = "$b/$t ${o.label}"
        var note = sh.note
        if (compound) note = "Cada gesto vale 3 ${unit(t, 3)}. Em andamento lento, pode-se reger cada ${unit(t, 1)} (opção \"em $b\")."
        else if (grouped && n == 2 && b == 5) note = "Cinco tempos em 2 gestos desiguais: o gesto longo vale ${o.groups.max()} e o curto, ${o.groups.min()}."
        else if (grouped && b == 7) note = "Sete tempos agrupados ${txt(o.groups)}: cada gesto vale o número de tempos indicado."
        return ConductPattern(n, lens, if (compound) 3 else 1, title, steps, note, sh.w, sh.ictus, o.label)
    }

    /** Fórmulas mais usadas, oferecidas como atalho na tela. */
    val METERS = listOf(2 to 4, 3 to 4, 4 to 4, 2 to 2, 3 to 2, 6 to 8, 9 to 8, 12 to 8, 5 to 4, 6 to 4, 7 to 4, 3 to 8, 5 to 8, 7 to 8)
}
