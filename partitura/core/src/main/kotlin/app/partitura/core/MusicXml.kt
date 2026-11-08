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

    /** Decodifica o texto respeitando BOM/UTF-16/ISO-8859-1 e remove declaração e DOCTYPE (que o Android tenta baixar). */
    fun cleanText(xml: ByteArray): String {
        fun u(i: Int) = xml[i].toInt() and 255
        var text = when {
            xml.size >= 2 && u(0) == 0xFF && u(1) == 0xFE -> String(xml, 2, xml.size - 2, Charsets.UTF_16LE)
            xml.size >= 2 && u(0) == 0xFE && u(1) == 0xFF -> String(xml, 2, xml.size - 2, Charsets.UTF_16BE)
            xml.size >= 3 && u(0) == 0xEF && u(1) == 0xBB && u(2) == 0xBF -> String(xml, 3, xml.size - 3, Charsets.UTF_8)
            else -> {
                val head = String(xml, 0, minOf(xml.size, 200), Charsets.ISO_8859_1)
                if (Regex("encoding=[\"']ISO-8859-1[\"']", RegexOption.IGNORE_CASE).containsMatchIn(head)) String(xml, Charsets.ISO_8859_1)
                else String(xml, Charsets.UTF_8)
            }
        }
        text = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        text = Regex("^<\\?xml[^>]*\\?>").replace(text, "")
        text = Regex("<!DOCTYPE[^>\\[]*(\\[[^\\]]*\\])?[^>]*>", RegexOption.IGNORE_CASE).replace(text, "")
        return text
    }

    fun parse(xml: ByteArray): Score {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        for ((k, v) in listOf(
            "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
            "http://xml.org/sax/features/external-general-entities" to false,
            "http://xml.org/sax/features/external-parameter-entities" to false,
        )) { try { f.setFeature(k, v) } catch (_: Exception) {} }
        val text = cleanText(xml)
        val builder = f.newDocumentBuilder()
        val doc = try { builder.parse(org.xml.sax.InputSource(java.io.StringReader(text))) }
            catch (e: Exception) { throw IllegalArgumentException("Arquivo XML inválido (${e.message?.take(120)})") }
        val root = doc.documentElement
        require(root.tagName == "score-partwise") { "Este arquivo não é MusicXML 'score-partwise' (encontrado: ${root.tagName})" }
        val names = root.kid("part-list")?.kids("score-part")?.associate { it.getAttribute("id") to (it.text("part-name") ?: it.getAttribute("id")) } ?: emptyMap()
        val title = root.kid("work")?.text("work-title")?.takeIf { it.isNotBlank() } ?: root.text("movement-title")?.takeIf { it.isNotBlank() } ?: "Sem título"
        val score = Score(title, 1, 0, 4, 4, 100)
        var gotKey = false; var gotTime = false; var gotTempo = false; var gotDiv = false
        for (p in root.kids("part")) {
            val id = p.getAttribute("id")
            val pname = names[id] ?: id
            // staff número -> Part (pianos têm 2 pautas na mesma parte)
            val staves = java.util.TreeMap<Int, Part>()
            fun staffPart(n: Int): Part = staves.getOrPut(n) { Part(if (n == 1) id else "$id-$n", pname, mutableListOf(), if (n >= 2) 'F' else 'G', id) }
            val clefSet = HashSet<Int>()
            staffPart(1)
            var declaredStaves = 1
            var mIndex = 0
            for (m in p.kids("measure")) {
                mIndex++
                val num = m.getAttribute("number").toIntOrNull() ?: mIndex
                val ms = java.util.TreeMap<Int, Measure>()
                fun meas(n: Int): Measure = ms.getOrPut(n) { Measure(num) }
                var curStaff = 1
                var lastVoice = 1
                val els = (0 until m.childNodes.length).map { m.childNodes.item(it) }.filterIsInstance<Element>()
                for (n in els) {
                    when (n.tagName) {
                        "attributes" -> {
                            n.text("divisions")?.toIntOrNull()?.takeIf { it > 0 }?.let { if (!gotDiv) { score.divisions = it; gotDiv = true } }
                            n.text("staves")?.toIntOrNull()?.let { declaredStaves = maxOf(declaredStaves, minOf(it, 4)); for (i in 1..declaredStaves) staffPart(i) }
                            for (c in n.kids("clef")) {
                                val sn = c.getAttribute("number").toIntOrNull() ?: 1
                                if (sn in clefSet) continue
                                val sign = c.text("sign")?.firstOrNull() ?: continue
                                staffPart(sn).clef = when (sign) { 'F' -> 'F'; 'G' -> 'G'; else -> if (staffPart(sn).clef == 'F') 'F' else 'G' }
                                clefSet += sn
                            }
                            if (!gotKey) n.kid("key")?.text("fifths")?.toIntOrNull()?.let { score.fifths = it; gotKey = true }
                            if (!gotTime) n.kid("time")?.let { t ->
                                val b = t.text("beats")?.toIntOrNull(); val bt = t.text("beat-type")?.toIntOrNull()
                                if (b != null && bt != null) { score.beats = b; score.beatType = bt; gotTime = true }
                            }
                        }
                        "direction" -> if (!gotTempo) n.kid("sound")?.getAttribute("tempo")?.toDoubleOrNull()?.let { score.tempo = it.toInt().coerceIn(20, 300); gotTempo = true }
                        "sound" -> if (!gotTempo) n.getAttribute("tempo").toDoubleOrNull()?.let { score.tempo = it.toInt().coerceIn(20, 300); gotTempo = true }
                        "forward" -> {
                            val d = n.text("duration")?.toIntOrNull() ?: 0
                            val st = n.text("staff")?.toIntOrNull() ?: curStaff
                            if (d > 0) { staffPart(st); meas(st).notes += Note(null, d, n.text("voice")?.toIntOrNull() ?: lastVoice) }
                        }
                        "note" -> {
                            if (n.kid("grace") != null) continue
                            val st = (n.text("staff")?.toIntOrNull() ?: 1).coerceIn(1, 4)
                            curStaff = st
                            staffPart(st)
                            val voice = n.text("voice")?.toIntOrNull() ?: 1
                            lastVoice = voice
                            val pe = n.kid("pitch")
                            val pitch = pe?.let {
                                val step = it.text("step")?.firstOrNull() ?: return@let null
                                Pitch(step, it.text("alter")?.toDoubleOrNull()?.toInt() ?: 0, it.text("octave")?.toIntOrNull() ?: 4)
                            }
                            val unpitched = n.kid("unpitched") != null
                            val ties = n.kids("tie").map { it.getAttribute("type") } +
                                (n.kid("notations")?.kids("tied")?.map { it.getAttribute("type") } ?: emptyList())
                            val dur = n.text("duration")?.toIntOrNull() ?: 0
                            if (dur <= 0) continue
                            meas(st).notes += Note(if (unpitched) null else pitch, dur, voice,
                                n.kid("chord") != null, "start" in ties, "stop" in ties)
                        }
                    }
                }
                for (i in staves.keys.toList()) staves[i]!!.measures += (ms[i] ?: Measure(num))
            }
            val multi = staves.size > 1
            for ((i, part) in staves) {
                val np = if (multi) Part(part.id, "$pname (pauta $i)", part.measures, part.clef, id) else part
                score.parts += np
            }
        }
        require(score.parts.any { pt -> pt.measures.any { it.notes.isNotEmpty() } }) { "A partitura não contém notas" }
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
