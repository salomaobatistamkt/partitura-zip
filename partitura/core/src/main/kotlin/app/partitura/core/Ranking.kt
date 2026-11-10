package app.partitura.core

/**
 * Ranking pessoal, só neste aparelho: cada tentativa medida (microfone) ou quiz de leitura vira pontos.
 * Pontos de um exercício = acerto% × nível (1 a 5) × min(1, andamento tocado ÷ meta). Quiz de leitura = acerto%.
 * O total conta só a melhor pontuação de cada exercício, então repetir o mesmo não infla o ranking.
 */
class Ranking {
    class Row(val time: Long, val kind: String, val id: String, val title: String, val level: Int, val hit: Int, val bpm: Int, val points: Int)
    val rows = mutableListOf<Row>()

    fun add(r: Row) { rows += r }
    fun bestById(): Map<String, Row> = rows.groupBy { it.id }.mapValues { (_, v) -> v.maxByOrNull { it.points }!! }
    val total: Int get() = bestById().values.sumOf { it.points }
    fun top(n: Int): List<Row> = bestById().values.sortedByDescending { it.points }.take(n)
    fun recent(n: Int): List<Row> = rows.sortedByDescending { it.time }.take(n)

    fun serialize(): String = rows.joinToString("\n") { "${it.time}\t${it.kind}\t${it.id}\t${it.title.replace('\t', ' ').replace('\n', ' ')}\t${it.level}\t${it.hit}\t${it.bpm}\t${it.points}" }

    companion object {
        fun points(hit: Int, levelOrdinal: Int, bpmPlayed: Int, bpmTarget: Int): Int {
            val speed = if (bpmTarget <= 0) 1.0 else minOf(1.0, bpmPlayed.toDouble() / bpmTarget)
            return Math.round(hit.coerceIn(0, 100) * (levelOrdinal + 1) * speed).toInt()
        }
        /** Faixas simples de título (arbitrárias, só para motivar). */
        fun title(total: Int) = when {
            total >= 8000 -> "Mestre"; total >= 3000 -> "Avançado"; total >= 1000 -> "Praticante"; total >= 300 -> "Aprendiz"; else -> "Iniciante"
        }
        fun parse(t: String): Ranking {
            val r = Ranking()
            for (ln in t.lines()) {
                val f = ln.split('\t'); if (f.size < 8) continue
                r.rows += Row(f[0].toLongOrNull() ?: 0, f[1], f[2], f[3], f[4].toIntOrNull() ?: 0, f[5].toIntOrNull() ?: 0, f[6].toIntOrNull() ?: 0, f[7].toIntOrNull() ?: 0)
            }
            return r
        }
    }
}
