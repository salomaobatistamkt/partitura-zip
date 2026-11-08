package app.partitura.core

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

object MusicXml {
    private fun Element.kids(tag: String) = (0 until childNodes.length).map { childNodes.item(it) }.filterIsInstance<Element>().filter { it.tagName == tag }
    private fun Element.kid(tag: String) = kids(tag).firstOrNull()
    private fun Element.text(tag: String) = kid(tag)?.textContent?.trim()

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
                if (i == 0) sb.append("      <attributes><divisions>${s.divisions}</divisions><key><fifths>${s.fifths}</fifths></key><time><beats>${s.beats}</beats><beat-type>${s.beatType}</beat-type></time></attributes>\n      <direction><sound tempo=\"${s.tempo}\"/></direction>\n")
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
