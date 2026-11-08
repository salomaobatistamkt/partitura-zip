package app.partitura

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import app.partitura.core.LNote
import app.partitura.core.Layout
import app.partitura.core.LayoutResult
import kotlin.math.hypot

/** Desenha a pauta com android.graphics.Canvas (tela, PNG e PDF usam o mesmo código). */
object StaffRenderer {
    const val ROW_H = 150f
    const val BOTTOM = 100f
    const val GAP = 10f

    fun height(rows: Int, sc: Float) = rows * ROW_H * sc

    fun draw(c: Canvas, lay: LayoutResult, sc: Float, ink: Int, accent: Int, sel: Triple<Int, Int, Int>? = null, rows: IntRange = lay.rows.indices) {
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; strokeWidth = 1.2f * sc; style = Paint.Style.STROKE }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.FILL }
        val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; textSize = 18f * sc }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (ink and 0x00FFFFFF) or (0x99 shl 24); textSize = 11f * sc }
        val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0x00FFFFFF) or (0x40 shl 24); style = Paint.Style.FILL }
        val score = lay.s
        val sigW = kotlin.math.abs(score.fifths) * 10f
        for (r in rows) {
            val row = lay.rows[r]
            val part = score.parts[row.part]
            val top = (r - rows.first) * ROW_H * sc
            val bottomY = top + BOTTOM * sc
            fun yOf(pos: Int) = bottomY - pos * GAP / 2f * sc
            for (i in 0..4) c.drawLine(6f * sc, bottomY - i * GAP * sc, (lay.width - 4f) * sc, bottomY - i * GAP * sc, line)
            // linhas de pauta ligadas: barra inicial
            c.drawLine(6f * sc, bottomY, 6f * sc, bottomY - 4 * GAP * sc, line)
            if (row.first) small.let { c.drawText(part.name, 8f * sc, top + 22f * sc, it) }
            c.drawText("m. ${row.firstMeasure}", row.left * sc, top + 36f * sc, small)
            val clefPaint = Paint(txt).apply { textSize = 34f * sc; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC) }
            c.drawText(if (part.clef == 'F') "F" else "G", 12f * sc, yOf(if (part.clef == 'F') 6 else 2) + 10f * sc, clefPaint)
            val keyPos = Layout.keySigPositions(part.clef, score.fifths)
            keyPos.forEachIndexed { i, pos -> c.drawText(if (score.fifths > 0) "♯" else "♭", (36f + i * 10f) * sc, yOf(pos) + 6f * sc, txt) }
            if (row.first) {
                val tx = (36f + sigW + 4f) * sc
                val big = Paint(txt).apply { textSize = 20f * sc; typeface = Typeface.DEFAULT_BOLD }
                c.drawText("${score.beats}", tx, yOf(6) + 7f * sc, big)
                c.drawText("${score.beatType}", tx, yOf(2) + 7f * sc, big)
            }
            row.bars.forEachIndexed { bi, bx ->
                val last = r == lay.rows.indexOfLast { it.part == row.part } && bi == row.bars.lastIndex
                val p = if (last) Paint(line).apply { strokeWidth = 3.4f * sc } else line
                c.drawLine(bx * sc, bottomY, bx * sc, bottomY - 4 * GAP * sc, p)
            }
            for (n in row.notes) {
                val x = n.x * sc
                val selected = sel != null && sel.first == row.part && sel.second == n.m && sel.third == n.n
                val col = if (selected) accent else ink
                fill.color = col; line.color = col
                val pos = n.pos
                if (selected) c.drawRoundRect(RectF(x - 13f * sc, top + 18f * sc, x + 15f * sc, top + (ROW_H - 8f) * sc), 8f * sc, 8f * sc, hi)
                if (pos == null) { drawRest(c, x, n, bottomY, sc, fill); line.color = ink; continue }
                var p = -2
                while (p >= pos) { c.drawLine(x - 9f * sc, yOf(p), x + 9f * sc, yOf(p), line); p -= 2 }
                p = 10
                while (p <= pos) { c.drawLine(x - 9f * sc, yOf(p), x + 9f * sc, yOf(p), line); p += 2 }
                val y = yOf(pos)
                val base = if (n.dots > 0) n.q / 1.5f else n.q
                val open = base >= 2f
                c.save(); c.rotate(-20f, x, y)
                val rect = RectF(x - 5.8f * sc, y - 4.2f * sc, x + 5.8f * sc, y + 4.2f * sc)
                if (open) { val st = Paint(line); st.style = Paint.Style.STROKE; st.strokeWidth = 1.6f * sc; c.drawOval(rect, st) } else c.drawOval(rect, fill)
                c.restore()
                if (base < 4f && !n.chord || (n.chord && base < 4f)) {
                    val sx = if (n.stemUp) x + 5f * sc else x - 5f * sc
                    val ex = if (n.stemUp) y - 30f * sc else y + 30f * sc
                    c.drawLine(sx, y, sx, ex, line)
                    val flags = when { base <= 0.125f -> 3; base <= 0.25f -> 2; base <= 0.5f -> 1; else -> 0 }
                    for (k in 0 until flags) {
                        val fy = if (n.stemUp) ex + k * 5f * sc else ex - k * 5f * sc
                        val dy = if (n.stemUp) 10f * sc else -10f * sc
                        c.drawLine(sx, fy, sx + 8f * sc, fy + dy, line)
                    }
                }
                if (n.acc != null) c.drawText(when (n.acc) { 1 -> "♯"; -1 -> "♭"; 2 -> "𝄪"; -2 -> "♭♭"; else -> "♮" }, x - 20f * sc, y + 6f * sc, Paint(txt).apply { color = col })
                if (n.dots > 0) c.drawCircle(x + 11f * sc, if (pos % 2 == 0) y - GAP / 2f * sc else y, 1.8f * sc, fill)
                if (n.tie) c.drawArc(RectF(x, y + 3f * sc, x + 24f * sc, y + 15f * sc), 10f, 160f, false, line)
                line.color = ink
            }
        }
    }

    private fun drawRest(c: Canvas, x: Float, n: LNote, bottomY: Float, sc: Float, fill: Paint) {
        val base = if (n.dots > 0) n.q / 1.5f else n.q
        when {
            base >= 4f -> c.drawRect(x - 6f * sc, bottomY - 3 * GAP * sc, x + 6f * sc, bottomY - 3 * GAP * sc + 5f * sc, fill)
            base >= 2f -> c.drawRect(x - 6f * sc, bottomY - 2 * GAP * sc - 5f * sc, x + 6f * sc, bottomY - 2 * GAP * sc, fill)
            else -> {
                val k = when { base <= 0.25f -> 2; base <= 0.5f -> 1; else -> 0 }
                c.drawRect(x - 2.5f * sc, bottomY - 3 * GAP * sc, x + 2.5f * sc, bottomY - 1.2f * GAP * sc, fill)
                for (i in 0 until k) c.drawCircle(x + 5f * sc, bottomY - (2.9f - i * 0.8f) * GAP * sc, 2.2f * sc, fill)
            }
        }
    }

    fun hit(lay: LayoutResult, sc: Float, px: Float, py: Float): Triple<Int, Int, Int>? {
        var best: Triple<Int, Int, Int>? = null
        var bd = 30f * sc
        lay.rows.forEachIndexed { r, row ->
            val top = r * ROW_H * sc
            for (n in row.notes) {
                val y = top + (BOTTOM - (n.pos ?: 4) * GAP / 2f) * sc
                val d = hypot(px - n.x * sc, py - y)
                if (d < bd) { bd = d; best = Triple(row.part, n.m, n.n) }
            }
        }
        return best
    }
}
