package app.partitura.core

import java.io.ByteArrayOutputStream

object Midi {
    private fun vlq(o: ByteArrayOutputStream, v: Int) {
        val b = mutableListOf(v and 0x7F); var x = v shr 7
        while (x > 0) { b.add(0, (x and 0x7F) or 0x80); x = x shr 7 }
        b.forEach { o.write(it) }
    }
    /** MIDI formato 1, uma trilha por parte, 480 ticks por semínima. */
    fun export(s: Score): ByteArray {
        val out = ByteArrayOutputStream()
        fun be32(v: Int) { out.write(v shr 24 and 255); out.write(v shr 16 and 255); out.write(v shr 8 and 255); out.write(v and 255) }
        fun be16(v: Int) { out.write(v shr 8 and 255); out.write(v and 255) }
        out.write("MThd".toByteArray()); be32(6); be16(1); be16(s.parts.size); be16(480)
        val tpq = 480.0 / s.divisions
        for ((pi, _) in s.parts.withIndex()) {
            val ev = Synth.events(s.copy(parts = mutableListOf(s.parts[pi])), 60 )
            val items = mutableListOf<Triple<Int, Int, Int>>() // tick, midi, on(1)/off(0)
            // com tempo=60, segundos == tempo em semínimas
            for (e in ev) { items += Triple((e.startSec * 480).toInt(), e.midi, 1); items += Triple(((e.startSec + e.durSec) * 480).toInt(), e.midi, 0) }
            items.sortWith(compareBy({ it.first }, { it.third }))
            val tr = ByteArrayOutputStream()
            if (pi == 0) { vlq(tr, 0); tr.write(byteArrayOf(0xFF.toByte(), 0x51, 3)); val mp = 60_000_000 / s.tempo; tr.write(mp shr 16 and 255); tr.write(mp shr 8 and 255); tr.write(mp and 255) }
            val ch = (if (pi >= 9) pi + 1 else pi).coerceAtMost(15)
            vlq(tr, 0); tr.write(0xC0 or ch); tr.write(Instruments.gm(s.parts[pi].inst))
            var last = 0
            for ((tick, midi, on) in items) { vlq(tr, tick - last); last = tick; tr.write((if (on == 1) 0x90 else 0x80) or ch); tr.write(midi); tr.write(if (on == 1) 80 else 0) }
            vlq(tr, 0); tr.write(byteArrayOf(0xFF.toByte(), 0x2F, 0))
            out.write("MTrk".toByteArray()); be32(tr.size()); out.write(tr.toByteArray())
        }
        return out.toByteArray()
    }
}
