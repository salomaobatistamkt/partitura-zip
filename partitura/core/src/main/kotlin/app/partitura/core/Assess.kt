package app.partitura.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Compara o que foi tocado (notas ouvidas) com o que o exercício pede. O alinhamento é pela sequência de notas (maior subsequência comum),
 * então o aluno pode começar quando quiser e tocar mais devagar ou mais depressa que o pedido; o andamento e o ritmo são medidos depois.
 */
object Assess {
    class Item(val midi: Int, val startSec: Double, var heardSec: Double? = null, var status: String = "faltou")
    class Result(
        val hit: Int, val expected: Int, val exact: Int, val octaveOff: Int, val missing: Int, val extra: Int,
        val tempoRatio: Double, val rhythmMs: Double, val rhythmOk: Int, val reliable: Boolean, val message: String, val items: List<Item>,
    ) {
        /** Andamento realmente tocado, dado o andamento pedido. */
        fun achievedBpm(asked: Int): Int = if (tempoRatio > 0.2) (asked / tempoRatio).toInt() else 0
    }

    private class N(val t: Double, val midi: Int)

    /** Junta notas quase simultâneas (acordes) e ordena por altura dentro do grupo. */
    private fun group(ns: List<N>, win: Double): List<N> {
        val s = ns.sortedWith(compareBy({ it.t }, { it.midi }))
        val out = ArrayList<N>(); var i = 0
        while (i < s.size) {
            var j = i; while (j + 1 < s.size && s[j + 1].t - s[i].t <= win) j++
            out += s.subList(i, j + 1).sortedBy { it.midi }.map { N(s[i].t, it.midi) }
            i = j + 1
        }
        return out
    }

    fun compare(expected: List<Synth.Event>, heard: List<NoteEvent>): Result {
        val e = group(expected.map { N(it.startSec, it.midi) }, 0.02)
        val h = group(heard.filter { it.amp > 0 }.map { N(it.start, it.midi) }, 0.06)
        val items = e.map { Item(it.midi, it.t) }
        if (e.isEmpty()) return Result(0, 0, 0, 0, 0, h.size, 1.0, 0.0, 0, false, "O exercício não tem notas para comparar.", items)
        if (h.size < max(2, e.size / 4)) return Result(0, e.size, 0, 0, e.size, 0, 1.0, 0.0, 0, false,
            "Ouvi poucas notas (${h.size} de ${e.size}). Aproxime o aparelho do instrumento, toque mais forte ou em ambiente mais silencioso e tente de novo. Não vou dar nota sem ter ouvido o suficiente.", items)
        val n = e.size; val m = h.size
        fun w(a: Int, b: Int): Double = if (a == b) 1.0 else if (Math.floorMod(a, 12) == Math.floorMod(b, 12)) 0.5 else -1.0
        val dp = Array(n + 1) { DoubleArray(m + 1) }
        for (i in 1..n) for (j in 1..m) {
            var best = max(dp[i - 1][j], dp[i][j - 1])
            val ww = w(e[i - 1].midi, h[j - 1].midi); if (ww > 0) best = max(best, dp[i - 1][j - 1] + ww)
            dp[i][j] = best
        }
        var i = n; var j = m; var exact = 0; var oct = 0
        val pairs = ArrayList<Pair<Int, Int>>()
        while (i > 0 && j > 0) {
            val ww = w(e[i - 1].midi, h[j - 1].midi)
            if (ww > 0 && abs(dp[i][j] - (dp[i - 1][j - 1] + ww)) < 1e-9) { pairs += (i - 1) to (j - 1); if (ww == 1.0) exact++ else oct++; i--; j-- }
            else if (dp[i - 1][j] >= dp[i][j - 1]) i-- else j--
        }
        pairs.reverse()
        for ((a, b) in pairs) { items[a].heardSec = h[b].t; items[a].status = if (e[a].midi == h[b].midi) "certa" else "oitava errada" }
        val matched = pairs.size; val extra = m - matched; val missing = n - matched
        // tempo e ritmo: reta heard = a + b·esperado
        var ratio = 1.0; var rms = 0.0; var rOk = 0
        if (pairs.size >= 3) {
            val xs = pairs.map { e[it.first].t }; val ys = pairs.map { h[it.second].t }
            val mx = xs.average(); val my = ys.average()
            val sxx = xs.sumOf { (it - mx) * (it - mx) }
            if (sxx > 1e-9) {
                val b = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / sxx
                if (b > 0.2 && b < 5) ratio = b
                val a = my - ratio * mx
                val res = xs.indices.map { ys[it] - (a + ratio * xs[it]) }
                rms = sqrt(res.sumOf { it * it } / res.size) * 1000
                rOk = res.count { abs(it) < 0.12 }
            }
        }
        val weight = exact + 0.5 * oct
        val hit = (100.0 * weight / (n + 0.5 * extra)).toInt().coerceIn(0, 100)
        val msg = buildString {
            append("$exact de $n notas certas")
            if (oct > 0) append(", $oct na oitava errada")
            if (missing > 0) append(", $missing faltando")
            if (extra > 0) append(", $extra a mais")
            append(". ")
            if (pairs.size >= 3) append(if (rms < 60) "Ritmo regular." else if (rms < 120) "Ritmo um pouco irregular." else "Ritmo bem irregular.")
        }
        return Result(hit, n, exact, oct, missing, extra, ratio, rms, rOk, true, msg, items)
    }
}
