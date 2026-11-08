package app.partitura.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

object MusicXml {
    private fun Element.kids(tag: String) = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    private fun Element.kid(tag: String) = kids(tag).firstOrNull()
    private fun Element.text(tag: String) = kid(tag)?.textContent?.trim()

    /** Aceita .musicxml/.xml e .mxl (zip). Garante divisões múltiplas de 4. */
    fun parseAny(data: ByteArray): Score {
        val xml = if (data.size > 4 && data[0] == 'P'.code.toByte() && data[1] == 'K'.code.toByte()) unzipMxl(data) else data
        return normalizeDivisions(parse(xml))
    }

    private fun unzipMxl(data: ByteArray): ByteArray {
        val entries = HashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(ByteArrayInputStream(data)).use { z ->
            var e = z.nextEntry
            while (e != null) { if (!e.isDirectory) entries[e.name] = z.readBytes(); e = z.nextEntry }
        }
        val container = entries["META-INF/container.xml"]
        var path: String? = null
        if (container != null) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(container))
            val rf = doc.getElementsByTagName("rootfile")
            if (rf.length > 0) path = (rf.item(0) as Element).getAttribute("full-path")
        }
        return entries[path] ?: entries.entries.firstOrNull { !it.key.startsWith("META-INF") && it.key.endsWith(".xml") }?.value
            ?: throw IllegalArgumentException("Arquivo .mxl sem partitura")
    }

    fun normalizeDivisions(s: Score): Score {
        fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
        val f = 4 / gcd(s.divisions, 4)
        if (f == 1) return s
        s.divisions *= f
        for (p in s.parts) for (m in p.measures) for (i in m.notes.indices) m.notes[i] = m.notes[i].copy(duration = m.notes[i].duration * f)
        return s
    }

    fun parse(xml: ByteArray): Score {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
        f.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        f.setFeature("http://xml.org/sax/features/external-general-entities", false)
        f.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        val root = f.newDocumentBuilder().parse(ByteArrayInputStream(xml)).documentElement
        require(root.tagName == "score-partwise") { "Somente MusicXML 'score-partwise' é suportado" }
        val names = root.kid("part-list")?.kids("score-part")?.associate { it.getAttribute("id") to (it.text("part-name") ?: it.getAttribute("id")) } ?: emptyMap()
        val title = root.kid("work")?.text("work-title") ?: root.text("movement-title") ?: "Sem título"
        val score = Score(title, 1, 0, 4, 4, 100)
        for (p in root.kids("part")) {
            val part = Part(p.getAttribute("id"), names[p.getAttribute("id")] ?: p.getAttribute("id"))
            for (m in p.kids("measure")) {
                val measure = Measure(m.getAttribute("number").toIntOrNull() ?: (part.measures.size + 1))
                for (n in m.childNodes.let { l -> (0 until l.length).map { l.item(it) }.filterIsInstance<Element>() }) {
                    when (n.tagName) {
                        "attributes" -> {
                            n.text("divisions")?.toIntOrNull()?.let { score.divisions = it }
                            n.kid("clef")?.text("sign")?.firstOrNull()?.let { if (it == 'F') part.clef = 'F' else if (it == 'G') part.clef = 'G' }
                            n.kid("key")?.text("fifths")?.toIntOrNull()?.let { score.fifths = it }
                            n.kid("time")?.let { t -> t.text("beats")?.toIntOrNull()?.let { score.beats = it }; t.text("beat-type")?.toIntOrNull()?.let { score.beatType = it } }
                        }
                        "direction" -> n.kid("sound")?.getAttribute("tempo")?.toDoubleOrNull()?.let { score.tempo = it.toInt() }
                        "sound" -> n.getAttribute("tempo").toDoubleOrNull()?.let { score.tempo = it.toInt() }
                        "note" -> {
                            val pe = n.kid("pitch")
                            val pitch = pe?.let { Pitch(it.text("step")!![0], it.text("alter")?.toDouble()?.toInt() ?: 0, it.text("octave")!!.toInt()) }
                            val ties = n.kids("tie").map { it.getAttribute("type") }
                            measure.notes += Note(pitch, n.text("duration")?.toIntOrNull() ?: 0, n.text("voice")?.toIntOrNull() ?: 1,
                                n.kid("chord") != null, "start" in ties, "stop" in ties)
                        }
                    }
                }
                part.measures += measure
            }
            score.parts += part
        }
        return score
    }

    fun write(s: Score): String {
        fun esc(t: String) = t.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<score-partwise version=\"4.0\">\n")
        sb.append("  <work><work-title>${esc(s.title)}</work-title></work>\n  <part-list>\n")
        s.parts.forEach { sb.append("    <score-part id=\"${esc(it.id)}\"><part-name>${esc(it.name)}</part-name></score-part>\n") }
        sb.append("  </part-list>\n")
        for (p in s.parts) {
            sb.append("  <part id=\"${esc(p.id)}\">\n")
            p.measures.forEachIndexed { i, m ->
                sb.append("    <measure number=\"${m.number}\">\n")
                if (i == 0) sb.append("      <attributes><divisions>${s.divisions}</divisions><key><fifths>${s.fifths}</fifths></key><time><beats>${s.beats}</beats><beat-type>${s.beatType}</beat-type></time>${if (p.clef == 'F') "<clef><sign>F</sign><line>4</line></clef>" else "<clef><sign>G</sign><line>2</line></clef>"}</attributes>\n      <direction><sound tempo=\"${s.tempo}\"/></direction>\n")
                for (n in m.notes) {
                    sb.append("      <note>")
                    if (n.chord) sb.append("<chord/>")
                    val pt = n.pitch
                    if (pt == null) sb.append("<rest/>") else {
                        sb.append("<pitch><step>${pt.step}</step>")
                        if (pt.alter != 0) sb.append("<alter>${pt.alter}</alter>")
                        sb.append("<octave>${pt.octave}</octave></pitch>")
                    }
                    sb.append("<duration>${n.duration}</duration>")
                    if (n.tieStop) sb.append("<tie type=\"stop\"/>")
                    if (n.tieStart) sb.append("<tie type=\"start\"/>")
                    sb.append("<voice>${n.voice}</voice></note>\n")
                }
                sb.append("    </measure>\n")
            }
            sb.append("  </part>\n")
        }
        return sb.append("</score-partwise>\n").toString()
    }
}
