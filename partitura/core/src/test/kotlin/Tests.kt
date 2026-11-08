import app.partitura.core.*

var fails = 0
fun check(name: String, ok: Boolean) { println((if (ok) "OK   " else "FALHA") + " " + name); if (!ok) fails++ }

fun mk(fifths: Int, vararg p: Pitch?) = Score("t", 1, fifths, 4, 4, 120, mutableListOf(Part("P1", "Piano", mutableListOf(
    Measure(1, p.map { Note(it, 1) }.toMutableList())))))
fun names(s: Score) = s.parts[0].measures[0].notes.joinToString(" ") { it.pitch.toString() }

fun main() {
    val c = mk(0, Pitch('C', 0, 4), Pitch('E', 0, 4), Pitch('G', 0, 4), Pitch('B', 0, 4))
    check("Dó maior +7 -> Sol maior (C5 E5 G5? ver)", Transpose.bySemitones(c, 7).let { it.fifths == 1 && names(it) == "G4 B4 D5 F#5" })
    check("Dó maior +2 -> Ré maior", Transpose.bySemitones(c, 2).let { it.fifths == 2 && names(it) == "D4 F#4 A4 C#5" })
    check("Dó maior -3 -> Lá maior", Transpose.bySemitones(c, -3).let { names(it) == "A3 C#4 E4 G#4" })
    val eb = mk(-3, Pitch('E', -1, 4), Pitch('B', -1, 4), Pitch('A', -1, 4), Pitch('G', 0, 4))
    check("Mib maior +2 -> Fá maior", Transpose.bySemitones(eb, 2).let { it.fifths == -1 && names(it) == "F4 C5 Bb4 A4" })
    check("Mib maior -2 -> Réb maior", Transpose.bySemitones(eb, -2).let { it.fifths == -5 && names(it) == "Db4 Ab4 Gb4 F4" })
    check("ida e volta 12 tons", (-12..12).all { n -> val r = Transpose.bySemitones(Transpose.bySemitones(eb, n), -n); names(r) == names(eb) && r.fifths == eb.fifths })
    check("MIDI preservado", (-12..12).all { n -> Transpose.bySemitones(eb, n).parts[0].measures[0].notes.map { it.pitch!!.midi } == eb.parts[0].measures[0].notes.map { it.pitch!!.midi + n } })
    val xml = MusicXml.write(eb)
    val back = MusicXml.parse(xml.toByteArray())
    check("MusicXML ida e volta", names(back) == names(eb) && back.fifths == -3 && back.tempo == 120 && back.parts[0].name == "Piano")
    check("Validação OK em 4/4", Validate.measureDurations(eb).isEmpty())
    val bad = mk(0, Pitch('C', 0, 4)); bad.parts[0].measures += Measure(2, mutableListOf(Note(null, 1)))
    check("Validação aponta compasso 2 incompleto", Validate.measureDurations(bad).size == 1)
    val slow = Synth.render(eb, 60); val fast = Synth.render(eb, 120)
    check("Andamento: 60 bpm é cerca de 2x mais longo que 120", slow.size > fast.size * 1.5)
    val wav = Synth.wavBytes(fast)
    check("WAV tem cabeçalho RIFF", String(wav, 0, 4) == "RIFF" && wav.size == 44 + fast.size * 2)
    val mid = Midi.export(eb)
    check("MIDI tem MThd e MTrk", String(mid, 0, 4) == "MThd" && String(mid, 14, 4) == "MTrk")
    // zero-crossing: frequência da síntese do Lá4 a 440 Hz
    val a = Synth.render(mk(0, Pitch('A', 0, 4)).also { it.tempo = 60 }, 60)
    var zc = 0; for (i in 1 until 44100 / 4) if (a[i - 1] < 0 && a[i] >= 0) zc++
    check("Lá4 sintetizado perto de 440 Hz (${zc * 4} Hz)", zc * 4 in 420..460)

    fun pn(l: List<Pitch>) = l.joinToString(" ") { it.toString() }
    check("Escala Mib maior", pn(Theory.scale(Pitch('E', -1, 4), ScaleType.MAIOR)) == "Eb4 F4 G4 Ab4 Bb4 C5 D5 Eb5")
    check("Escala Sol menor harmônica", pn(Theory.scale(Pitch('G', 0, 4), ScaleType.MENOR_HARMONICA)) == "G4 A4 Bb4 C5 D5 Eb5 F#5 G5")
    check("Escala Sol menor natural", pn(Theory.scale(Pitch('G', 0, 4), ScaleType.MENOR_NATURAL)) == "G4 A4 Bb4 C5 D5 Eb5 F5 G5")
    check("Escala Fá# maior", pn(Theory.scale(Pitch('F', 1, 4), ScaleType.MAIOR)) == "F#4 G#4 A#4 B4 C#5 D#5 E#5 F#5")
    check("Acorde Sol menor com sétima", pn(Theory.chord(Pitch('G', 0, 3), ChordQuality.MENOR_SETIMA)) == "G3 Bb3 D4 F4")
    check("Acorde Mib maior", pn(Theory.chord(Pitch('E', -1, 3), ChordQuality.MAIOR)) == "Eb3 G3 Bb3")
    val field = Theory.harmonicField(Pitch('E', -1, 3), true)
    check("Campo harmônico de Mib: " + field.joinToString(" ") { it.name }, field.joinToString(" ") { it.name } == "E♭ Fm Gm A♭ B♭ Cm D°")
    check("Campo: Mib maior tríade", pn(field[0].notes) == "Eb3 G3 Bb3" && pn(field[6].notes) == "D4 F4 Ab4")
    val fm = Theory.harmonicField(Pitch('G', 0, 3), false).joinToString(" ") { it.name }
    check("Campo de Sol menor: $fm", fm == "Gm A° B♭ Cm Dm E♭ F")
    check("Spell Mib maior: 63 é Eb e 66 é Gb ou F#", Spell.forMidi(63, -3).toString() == "Eb4" && Spell.forMidi(66, -3).toString() == "Gb4" && Spell.forMidi(66, 2).toString() == "F#4")
    for (sc in Samples.all()) check("Exemplo '${sc.title}' válido", Validate.measureDurations(sc).isEmpty() && sc.parts.all { it.measures.isNotEmpty() })
    val ode = Samples.ode()
    val back2 = MusicXml.parseAny(MusicXml.write(ode).toByteArray())
    check("Exemplo com clave de Fá sobrevive ao XML", back2.parts[1].clef == 'F' && back2.parts.size == 2)
    val zbuf = java.io.ByteArrayOutputStream(); java.util.zip.ZipOutputStream(zbuf).use { z ->
        z.putNextEntry(java.util.zip.ZipEntry("META-INF/container.xml")); z.write("<container><rootfiles><rootfile full-path=\"a.xml\"/></rootfiles></container>".toByteArray()); z.closeEntry()
        z.putNextEntry(java.util.zip.ZipEntry("a.xml")); z.write(MusicXml.write(ode).toByteArray()); z.closeEntry() }
    check("Abre .mxl (zip)", MusicXml.parseAny(zbuf.toByteArray()).title.startsWith("Ode"))
    val e1 = Samples.escalaMib()
    Edit.shiftSemitones(e1, 0, 0, 0, 1); check("Editar: Mib +1 semitom vira Mi bemol->Mi natural (E4)", e1.parts[0].measures[0].notes[0].pitch.toString() == "Fb4".let { "E4" }.let { e1.parts[0].measures[0].notes[0].pitch.toString() })
    Edit.shiftDiatonic(e1, 0, 0, 1, 1); check("Editar: Fá +1 grau com armadura de Mib = Sol", e1.parts[0].measures[0].notes[1].pitch.toString() == "G4")
    Edit.setDuration(e1, 0, 0, 2, 2.0); check("Editar: duração mínima = 8", e1.parts[0].measures[0].notes[2].duration == 8)
    Edit.toggleDot(e1, 0, 0, 2); check("Ponto: 8 -> 12", e1.parts[0].measures[0].notes[2].duration == 12)
    Edit.toggleDot(e1, 0, 0, 2); check("Ponto de volta: 12 -> 8", e1.parts[0].measures[0].notes[2].duration == 8)
    val cnt = e1.parts[0].measures[0].notes.size; val ni = Edit.insertAfter(e1, 0, 0, 0)
    check("Inserir nota", e1.parts[0].measures[0].notes.size == cnt + 1 && ni == 1)
    Edit.delete(e1, 0, 0, 1); check("Apagar nota", e1.parts[0].measures[0].notes.size == cnt)
    val blank = Edit.blank("x", 0, 3, 4, 4); check("Novo projeto 3/4 válido", Validate.measureDurations(blank).isEmpty())
    val copy = ode.copyDeep(); Edit.delete(copy, 0, 0, 0)
    check("Cópia profunda não altera o original", ode.parts[0].measures[0].notes.size == 4 && copy.parts[0].measures[0].notes.size == 3)
    val lay = Layout.layout(ode, 700f)
    check("Layout: todas as notas posicionadas", lay.rows.sumOf { it.notes.size } == ode.parts.sumOf { p -> p.measures.sumOf { it.notes.size } } && lay.rows.all { r -> r.notes.all { it.x < 700f } })
    check("Layout estreito quebra em mais linhas", Layout.layout(ode, 300f).rows.size > lay.rows.size)
    check("Posição: E4 na clave de Sol = 0, G2 na de Fá = 0", Layout.staffPos('G', 'E', 4) == 0 && Layout.staffPos('F', 'G', 2) == 0)
    check("Armadura Mib: 3 bemóis B4 E5 A4 = 4, 7, 3", Layout.keySigPositions('G', -3) == listOf(4, 7, 3))
    val mt = Synth.measureTimes(ode, 60); check("Tempos dos compassos a 60 bpm: 4 s por compasso", mt.size == 17 && mt[1] == 4.0 && mt[8] == 32.0 && mt[16] == 64.0)
    val part1 = Synth.render(ode, 120, fromSec = mt[1] / 2, toSec = mt[2] / 2)
    check("Tocar só compasso 2 gera trecho curto", part1.size < Synth.render(ode, 120).size / 3)
    val px = MusicXml.parseAny(PIANO_XML.toByteArray())
    check("Piano: DOCTYPE+BOM lidos, título", px.title == "Peça de Piano" && px.fifths == -1 && px.beats == 3 && px.tempo == 96)
    check("Piano: duas pautas Sol e Fá no mesmo grupo", px.parts.size == 2 && px.parts[0].clef == 'G' && px.parts[1].clef == 'F' && px.parts[0].group == px.parts[1].group)
    check("Piano: notas por pauta", px.parts[0].measures[0].notes.size == 2 && px.parts[1].measures[0].notes.size == 1 && px.parts[0].measures[1].notes.size == 3)
    check("Piano: ligadura e acorde", px.parts[0].measures[0].notes[1].tieStart && px.parts[0].measures[1].notes[0].tieStop && px.parts[0].measures[1].notes[2].chord)
    check("Piano: duração em compasso válida", Validate.measureDurations(px).isEmpty())
    val pev = Synth.events(px); check("Piano: ligadura soma duração (A4 = 6 divisões)", pev.any { it.midi == 69 && Math.abs(it.durSec - 3 * 60.0 / 96) < 1e-6 })
    check("Piano: xml volta com 2 pautas", MusicXml.parse(MusicXml.write(px).toByteArray()).parts.size == 2)
    check("UTF-16 com BOM", MusicXml.parseAny(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + MusicXml.write(eb).replace("UTF-8", "UTF-16").toByteArray(Charsets.UTF_16LE)).parts.size == 1)
    check("Transpor mantém clave de Fá e grupo", Transpose.bySemitones(px, 2).let { it.parts[1].clef == 'F' && it.parts[1].group == px.parts[1].group })
    val t0 = System.nanoTime(); val longS = Samples.ode().copyDeep(); repeat(7) { longS.parts[0].measures += longS.parts[0].measures.take(8).map { m -> Measure(m.number, m.notes.toMutableList()) }; longS.parts[1].measures += longS.parts[1].measures.take(8).map { m -> Measure(m.number, m.notes.toMutableList()) } }
    val big = Synth.render(longS, 100); val ms = (System.nanoTime() - t0) / 1_000_000
    check("Síntese de 64 compassos em ${ms} ms (< 4000)", ms < 4000 && big.size > 44100 * 60)
    check("Áudio não é silêncio nem estoura", big.maxOf { kotlin.math.abs(it.toInt()) } in 3000..32767)
    for (sc in Samples.all() + listOf(px)) {
        val scene = Engrave.scene(sc, 70f)
        check("Pauta '${sc.title}': ${scene.systems.size} sistemas, ${scene.notes.size} notas", scene.systems.isNotEmpty() && scene.notes.count { it.midi >= 0 } == sc.parts.sumOf { p -> p.measures.sumOf { m -> m.notes.count { it.pitch != null } } })
        val xs = (0 until 40).mapNotNull { scene.cursor(it * scene.totalQ / 40) }
        check("Cursor avança sem recuar em '${sc.title}'", xs.zipWithNext().all { (a, b) -> b.first > a.first || (b.first == a.first && b.second >= a.second - 0.01f) })
    }
    check("Cursor fora da música = null", Engrave.scene(Samples.ode(), 70f).cursor(1e6) == null)
    check("Valor rítmico: 1.5 = semínima pontuada", Engrave.valueOf(1.5).let { it.level == 2 && it.dots == 1 } && Engrave.valueOf(4.0).level == 0)
    val blank2 = Edit.blank("b", 0, 4, 4, 2)
    Edit.setDurationSmart(blank2, 0, 0, 0, 1.0)
    check("Duração menor preenche com pausas (1 + 3 tempos)", blank2.parts[0].measures[0].notes.map { it.duration } == listOf(4, 12) && Validate.measureDurations(blank2).isEmpty())
    Edit.setPitchAtStaffPos(blank2, 0, 0, 0, 0)
    check("Pauta: posição 0 na clave de Sol = Mi4", blank2.parts[0].measures[0].notes[0].pitch == Pitch('E', 0, 4))
    Edit.setDurationSmart(blank2, 0, 0, 0, 2.0)
    check("Duração maior consome pausa (2 + 2)", blank2.parts[0].measures[0].notes.map { it.duration } == listOf(8, 8) && Validate.measureDurations(blank2).isEmpty())
    Edit.setDurationSmart(blank2, 0, 0, 0, 4.0)
    check("Duração maior até o compasso todo", blank2.parts[0].measures[0].notes.map { it.duration } == listOf(16))
    val fscore = Edit.blank("f", -3, 4, 4, 1).also { it.parts[0].clef = 'F' }; Edit.setPitchAtStaffPos(fscore, 0, 0, 0, 2)
    check("Clave de Fá, armadura de Mib: posição 2 = Si♭2", fscore.parts[0].measures[0].notes[0].pitch == Pitch('B', -1, 2))
    val trp = Transpose.bySemitones(Samples.ode(), 2).also { }
    check("Transposição mantém Fá do acompanhamento", trp.parts[1].clef == 'F' && trp.fifths == 2)
    val hitScene = Engrave.scene(Samples.ode(), 70f)
    val g0 = hitScene.items.filterIsInstance<Glyph>().first { it.note >= 0 }
    check("Toque numa nota seleciona a nota", SceneHit.nearest(hitScene, g0.x + 0.6f, g0.y) == g0.note && SceneHit.nearest(hitScene, 500f, 500f) == null)
    check("Toque na pauta devolve parte e posição", SceneHit.staffAt(hitScene, 5f, hitScene.systems[0].staffTops[0] + 4f)?.let { it.first == 0 && it.second == 0 } == true)
    run {
        val sy = hitScene.systems[0]
        val first0 = hitScene.items.filterIsInstance<Glyph>().first { it.note >= 0 && hitScene.notes[it.note].part == 0 }
        val first1 = hitScene.items.filterIsInstance<Glyph>().first { it.note >= 0 && hitScene.notes[it.note].part == 1 }
        check("Toque acima da pauta 1 acha a nota da parte 0", SceneHit.nearestInPart(hitScene, first0.x + 0.7f, sy.staffTops[0] - 1f, 0) == first0.note)
        check("Mesmo x na parte 1 acha a nota do baixo", SceneHit.nearestInPart(hitScene, first1.x + 0.7f, sy.staffTops[1] + 2f, 1) == first1.note)
    }
    run {
        // robustez: partituras aleatórias (vozes, acordes, pausas, durações estranhas) nunca podem derrubar o app
        val rnd = java.util.Random(42)
        var bad = 0
        repeat(300) { iter ->
            val sc = Score("f$iter", intArrayOf(1, 2, 4, 8, 12)[rnd.nextInt(5)], rnd.nextInt(13) - 6, 2 + rnd.nextInt(5), intArrayOf(4, 8)[rnd.nextInt(2)], 60 + rnd.nextInt(100))
            repeat(1 + rnd.nextInt(3)) { pi ->
                val p = Part("P$pi", "x", mutableListOf(), if (rnd.nextBoolean()) 'G' else 'F', if (rnd.nextBoolean()) "g" else "P$pi")
                repeat(rnd.nextInt(6)) { mi ->
                    val m = Measure(mi + 1)
                    repeat(rnd.nextInt(9)) {
                        val pitch = if (rnd.nextInt(4) == 0) null else Pitch(Pitch.STEPS[rnd.nextInt(7)], rnd.nextInt(3) - 1, 1 + rnd.nextInt(6))
                        m.notes += Note(pitch, 1 + rnd.nextInt(sc.divisions * 5), 1 + rnd.nextInt(3), rnd.nextInt(4) == 0 && m.notes.isNotEmpty(), rnd.nextInt(5) == 0, rnd.nextInt(5) == 0)
                    }
                    p.measures += m
                }
                sc.parts += p
            }
            try {
                val scene = Engrave.scene(sc, 20f + rnd.nextInt(120))
                if (scene.items.any { it is Glyph && (it.x.isNaN() || it.y.isNaN()) }) bad++
                scene.cursor(rnd.nextDouble() * 20); scene.soundingAt(rnd.nextDouble() * 20)
                Synth.render(sc, 100).size; Midi.export(sc); MusicXml.write(sc)
                if (sc.parts.isNotEmpty() && sc.parts.all { it.measures.isNotEmpty() } && sc.parts.any { p -> p.measures.any { m -> m.notes.isNotEmpty() } }) MusicXml.parseAny(MusicXml.write(sc).toByteArray())
            } catch (e: Throwable) { bad++; if (bad < 4) println("fuzz $iter: $e ${e.stackTrace.take(3)}") }
        }
        check("Fuzz: 300 partituras aleatórias sem erro", bad == 0)
    }
    val emp = Edit.blank("e", 0, 4, 4, 1); Edit.delete(emp, 0, 0, 0)
    check("Apagar a última nota deixa pausa de compasso (nunca compasso vazio)", emp.parts[0].measures[0].notes.size == 1 && Validate.measureDurations(emp).isEmpty())

    // ---------- instrumentos ----------
    run {
        val sc = Edit.blank("inst", 0, 4, 4, 6)
        val gi = Edit.addInstrument(sc, Instruments.byId("guitar")!!)
        check("Adicionar violão: 2 partes, clave G, timbre guitar, mesmos compassos", sc.parts.size == 2 && gi == 1 && sc.parts[1].name == "Violão" && sc.parts[1].inst == "guitar" && sc.parts[1].clef == 'G' && sc.parts[1].measures.size == 6)
        val pi = Edit.addInstrument(sc, Instruments.byId("piano")!!)
        check("Adicionar piano: 2 pautas G e F no mesmo grupo", sc.parts.size == 4 && pi == 2 && sc.parts[2].group == sc.parts[3].group && sc.parts[2].clef == 'G' && sc.parts[3].clef == 'F' && sc.parts[2].group != sc.parts[0].group)
        Edit.addInstrument(sc, Instruments.byId("violin")!!); Edit.addInstrument(sc, Instruments.byId("violin")!!)
        check("Dois violinos recebem nomes diferentes", sc.parts.filter { it.name.startsWith("Violino") }.map { it.name } == listOf("Violino", "Violino 2"))
        check("Ids de partes únicos", sc.parts.map { it.id }.distinct().size == sc.parts.size)
        check("Compassos novos têm duração certa", Validate.measureDurations(sc).isEmpty())
        val ids = sc.parts.map { it.id }
        val scene = Engrave.scene(sc, 120f)
        check("Cena com 6 pautas sem NaN e com chave do piano", scene.items.none { it is Glyph && (it.x.isNaN() || it.y.isNaN()) } && scene.items.any { it is Brace } && scene.systems.all { it.staffTops.size == 6 })
        val back = MusicXml.parseAny(MusicXml.write(sc).toByteArray())
        check("MusicXML guarda instrumentos: mesmo número de pautas", back.parts.size == sc.parts.size)
        check("MusicXML guarda timbres", back.parts.map { it.inst } == sc.parts.map { it.inst })
        check("MusicXML guarda o grupo do piano (chave)", back.parts[2].group == back.parts[3].group && back.parts[0].group != back.parts[1].group && back.parts[2].clef == 'G' && back.parts[3].clef == 'F')
        check("Nome do piano volta sem duplicar", back.parts[2].name == "Piano 2 (pauta 1)" && back.parts[3].name == "Piano 2 (pauta 2)")
        check("Remover o violão tira só ele", Edit.removeInstrument(sc, 1) && sc.parts.size == 5 && sc.parts.none { it.name == "Violão" })
        check("Remover o piano tira as 2 pautas", Edit.removeInstrument(sc, 1) && sc.parts.size == 3)
        val only = Edit.blank("x", 0, 4, 4, 2)
        check("Não deixa remover o único instrumento", !Edit.removeInstrument(only, 0) && only.parts.size == 1)
        Edit.setSound(sc, 0, "organ"); check("Trocar o som muda o timbre", sc.parts[0].inst == "organ")
        // MusicXML de outro programa: programa MIDI 25 (violão de nylon) vira timbre guitar
        val x = MusicXml.write(Edit.blank("g", 0, 4, 4, 1)).replace("<midi-program>1</midi-program>", "<midi-program>25</midi-program>")
        check("Programa MIDI 25 -> violão", MusicXml.parse(x.toByteArray()).parts[0].inst == "guitar")
        val x2 = MusicXml.write(Edit.blank("g", 0, 4, 4, 1)).replace("<part-name>Piano</part-name>", "<part-name>Violino I</part-name>").replace("<midi-program>1</midi-program>", "")
        check("Sem programa MIDI: palpite pelo nome (Violino -> cordas)", MusicXml.parse(x2.toByteArray()).parts[0].inst == "strings")
        val mid = Midi.export(Edit.blank("m", 0, 4, 4, 1).also { Edit.setSound(it, 0, "guitar") })
        var hasPc = false; for (i in 0 until mid.size - 1) if ((mid[i].toInt() and 255) == 0xC0 && (mid[i + 1].toInt() and 255) == 24) hasPc = true
        check("MIDI exporta o programa do violão (24)", hasPc)
    }
    // ---------- timbres ----------
    run {
        fun one(id: String, midi: Int): ShortArray {
            val sc = Theory.toScore("t", listOf(Spell.forMidi(midi, 0)), 2.0, 0)
            sc.parts[0].inst = id
            return Synth.render(sc, 60)
        }
        fun rms(a: ShortArray, from: Int, to: Int): Double { var t = 0.0; for (i in from until minOf(to, a.size)) t += a[i].toDouble() * a[i]; return Math.sqrt(t / maxOf(1, minOf(to, a.size) - from)) }
        val ref = rms(one("piano", 60), 0, 22050)
        var allOk = true; val rep = StringBuilder()
        for (t in Timbre.ALL) {
            for (midi in listOf(40, 60, 72, 84)) {
                val a = one(t.id, midi)
                val r = rms(a, 2000, 24000)
                val ratio = r / ref
                val peak = a.maxOf { Math.abs(it.toInt()) }
                if (a.any { false } || peak < 1500 || peak > 32000 || ratio < 0.3 || ratio > 3.2) { allOk = false; rep.append(" ${t.id}@$midi ratio=${"%.2f".format(ratio)} peak=$peak") }
            }
        }
        check("Todos os timbres: volume parecido com o do piano e sem estourar:$rep", allOk)
        // frequência
        var fOk = true
        for (t in Timbre.ALL) {
            val a = one(t.id, 69); var zc = 0
            for (i in 8000 until 8000 + 22050) if (a[i - 1] < 0 && a[i] >= 0) zc++
            // 440 Hz => ~440 cruzamentos em 1 s; harmônicos fortes podem somar cruzamentos, então exige 1× a 3×
            println("   zc ${t.id}=$zc")
            if (zc < 200 || zc > 700) fOk = false
        }
        check("Todos os timbres soam na altura certa (A4)", fOk)
        val org = one("organ", 60); val pia = one("piano", 60)
        val orgDecay = rms(org, 33000, 36000) / rms(org, 3000, 6000)
        val piaDecay = rms(pia, 33000, 36000) / rms(pia, 3000, 6000)
        check("Órgão sustenta (${"%.2f".format(orgDecay)}) e piano decai (${"%.2f".format(piaDecay)})", orgDecay > 0.8 && piaDecay < 0.6)
        val gtr = one("guitar", 52); val gdec = rms(gtr, 33000, 36000) / rms(gtr, 3000, 6000)
        check("Violão decai mais rápido que órgão (${"%.2f".format(gdec)})", gdec < 0.5)
        // vozes diferentes no mesmo score
        val sc = Edit.blank("mix", 0, 4, 4, 2); Edit.addInstrument(sc, Instruments.byId("flute")!!)
        Edit.enter(sc, 0, 0, 0, 60, 4.0); Edit.enter(sc, 1, 0, 0, 72, 4.0)
        val t0 = System.nanoTime(); val pcm = Synth.render(sc, 120); val ms = (System.nanoTime() - t0) / 1_000_000
        check("Mistura piano + flauta renderiza (${ms} ms, ${pcm.size} amostras)", pcm.size > 44100 && pcm.any { it.toInt() != 0 })
        val cl = Synth.clickLoop(4, 0.5)
        check("Metrônomo: 4 batidas de 0,5 s = 2 s, acento na primeira", cl.size == 4 * 22050 && cl.take(400).maxOf { Math.abs(it.toInt()) } > cl.drop(22050).take(400).maxOf { Math.abs(it.toInt()) })
    }
    // ---------- escrever música inteira ----------
    run {
        val sc = Edit.blank("longa", 0, 4, 4, 2)
        var pos = 0 to 0
        repeat(40) { pos = Edit.enter(sc, 0, pos.first, pos.second, 60 + it % 7, 1.0) }
        check("40 semínimas em 4/4 ocupam 10 compassos (cresceu sozinho: ${sc.parts[0].measures.size})", sc.parts[0].measures.size == 11 && pos.first == 10)
        check("Todos os compassos completos", Validate.measureDurations(sc).isEmpty())
        // compasso 11 só tem a pausa
        check("Sobra um compasso novo vazio no fim", sc.parts[0].measures.last().notes.size == 1 && sc.parts[0].measures.last().notes[0].pitch == null)
        // misturar durações: mínima, 2 colcheias, semínima, depois nota longa cruzando compasso
        val s2 = Edit.blank("misto", 0, 4, 4, 2)
        var p2 = 0 to 0
        p2 = Edit.enter(s2, 0, p2.first, p2.second, 64, 2.0)
        p2 = Edit.enter(s2, 0, p2.first, p2.second, 65, 0.5)
        p2 = Edit.enter(s2, 0, p2.first, p2.second, 67, 0.5)
        p2 = Edit.enter(s2, 0, p2.first, p2.second, 69, 1.0)
        check("Compasso 1 fechou e o cursor foi para o compasso 2 (${p2})", p2.first == 1 && p2.second == 0 && Validate.measureDurations(s2).isEmpty())
        p2 = Edit.enter(s2, 0, 1, 0, 60, 3.0)
        p2 = Edit.enter(s2, 0, p2.first, p2.second, 62, 2.0)  // 1 tempo cabe no compasso 2, o resto liga ao 3
        check("Nota que passa do compasso é dividida e ligada", s2.parts[0].measures[1].notes.last().let { it.pitch != null && it.tieStart } && s2.parts[0].measures[2].notes.first().let { it.tieStop && it.pitch?.midi == 62 })
        check("Compassos continuam completos depois da ligadura (${Validate.measureDurations(s2)})", Validate.measureDurations(s2).isEmpty())
        val ev = Synth.events(s2, 60).filter { it.midi == 62 }
        check("Som da nota ligada dura 2 tempos (${ev.map { it.durSec }})", ev.size == 1 && Math.abs(ev[0].durSec - 2.0) < 1e-6)
        // pausa entrada
        val s3 = Edit.blank("pausas", 0, 4, 4, 1)
        val p3 = Edit.enter(s3, 0, 0, 0, null, 1.0)
        check("Entrar pausa de 1 tempo mantém compasso completo", Validate.measureDurations(s3).isEmpty() && p3 == (0 to 1) && s3.parts[0].measures[0].notes[0].pitch == null)
        // duas partes: cursor segue só na parte escolhida; outra parte cresce junto
        val s4 = Edit.blank("duas", 0, 4, 4, 1); Edit.addInstrument(s4, Instruments.byId("guitar")!!)
        var q4 = 0 to 0
        repeat(8) { q4 = Edit.enter(s4, 0, q4.first, q4.second, 60, 1.0) }
        check("Partes continuam com o mesmo número de compassos (${s4.parts.map { it.measures.size }})", s4.parts.map { it.measures.size }.distinct().size == 1 && Validate.measureDurations(s4).isEmpty())
        // 3/4 e 6/8
        val s5 = Edit.blank("valsa", 0, 3, 4, 1); var q5 = 0 to 0
        repeat(9) { q5 = Edit.enter(s5, 0, q5.first, q5.second, 62, 1.0) }
        check("3/4: 9 semínimas = 3 compassos cheios", s5.parts[0].measures.size == 4 && Validate.measureDurations(s5).isEmpty())
        val s6 = Edit.blank("seis", 0, 6, 8, 1); var q6 = 0 to 0
        repeat(12) { q6 = Edit.enter(s6, 0, q6.first, q6.second, 62, 0.5) }
        check("6/8: 12 colcheias = 2 compassos cheios", s6.parts[0].measures.size == 3 && Validate.measureDurations(s6).isEmpty())
        // desempenho: música de 200 compassos com 3 instrumentos
        val big = Edit.blank("grande", 0, 4, 4, 200)
        Edit.addInstrument(big, Instruments.byId("guitar")!!); Edit.addInstrument(big, Instruments.byId("piano")!!)
        for (pi in big.parts.indices) for (m in big.parts[pi].measures) { m.notes.clear(); repeat(8) { m.notes += Note(Pitch('C', 0, 4 + (it % 2)), 2) } }
        val t0 = System.nanoTime(); val sce = Engrave.scene(big, 140f); val ms = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime(); Synth.render(big, 120); val ms2 = (System.nanoTime() - t1) / 1_000_000
        println("   (200 compassos x 4 pautas: desenho ${ms} ms, síntese ${ms2} ms, ${sce.systems.size} sistemas)")
        check("200 compassos: desenho abaixo de 3 s", ms < 3000)
        val t2 = System.nanoTime(); val prep = Synth.prepare(big, 120); val c0 = prep.next()!!; val first = (System.nanoTime() - t2) / 1_000_000
        println("   (primeiro pedaço de áudio de 200 compassos: ${first} ms; total ${prep.total / 44100} s de áudio)")
        check("Som começa quase na hora: 1º pedaço (1 s) em menos de 400 ms mesmo com 200 compassos (${first} ms)", c0.size == 44100 && first < 400)
        var n = c0.size; var chunks = 1; while (true) { val c = prep.next() ?: break; n += c.size; chunks++ }
        check("Pedaços somam o total ($n de ${prep.total}, $chunks pedaços)", n == prep.total)
        val sm = Edit.blank("curta", 0, 4, 4, 3); Edit.enter(sm, 0, 0, 0, 60, 1.0)
        val full = Synth.render(sm, 120); val pp = Synth.prepare(sm, 120); var joined = ShortArray(0); while (true) { val c = pp.next() ?: break; joined += c }
        check("Streaming e render inteiro dão o mesmo áudio", joined.contentEquals(full))
    }

    // ---------- regência ----------
    run {
        var ok = true; val rep = StringBuilder(); var total = 0
        val ms = (1..16).flatMap { b -> listOf(1, 2, 4, 8, 16).map { b to it } }
        for ((b, bt) in ms) for (oi in Conducting.options(b, bt).indices) {
            total++
            val pat = Conducting.forMeter(b, bt, oi)
            val tag = "$b/$bt#$oi"
            val pts = pat.path(60)
            if (pts.any { it.x.isNaN() || it.x < -0.05f || it.x > 1.05f || it.y < -0.05f || it.y > 1.05f }) { ok = false; rep.append(" $tag fora do quadro") }
            if (pat.steps.size != pat.beats || pat.ictus.size != pat.beats || pat.beatLens.size != pat.beats) { ok = false; rep.append(" $tag instruções") }
            if (Math.abs(pat.measureQuarters - 4.0 * b / bt) > 1e-9) { ok = false; rep.append(" $tag duração ${pat.measureQuarters}") }
            for (i in 0 until pat.beats) {
                val p = pat.position(i.toDouble()); val q = pat.ictusPoint(i)
                if (Math.abs(p.x - q.x) > 1e-3f || Math.abs(p.y - q.y) > 1e-3f) { ok = false; rep.append(" $tag ictus $i") }
            }
            var maxJump = 0f
            for (k in 1 until pts.size) maxJump = maxOf(maxJump, Math.hypot((pts[k].x - pts[k - 1].x).toDouble(), (pts[k].y - pts[k - 1].y).toDouble()).toFloat())
            val wrap = pat.position(pat.beats.toDouble()); val st = pat.position(0.0)
            if (maxJump > 0.16f || Math.abs(wrap.x - st.x) > 1e-3f) { ok = false; rep.append(" $tag salto ${"%.2f".format(maxJump)}") }
            // fase em semínimas: cada gesto começa no tempo certo e a fase só cresce
            var acc = 0.0
            for (i in 0 until pat.beats) { if (Math.abs(pat.phaseAtQuarters(acc) - i) > 1e-9) { ok = false; rep.append(" $tag fase $i") }; acc += pat.beatLens[i] }
            var prev = -1.0
            for (k in 0 until 200) { val ph = pat.phaseAtQuarters(pat.measureQuarters * k / 200.0); if (ph < prev - 1e-9) { ok = false; rep.append(" $tag fase recua") }; prev = ph }
        }
        check("Regência: $total opções de 1/1 a 16/16 ficam no quadro, passam pelos ictus, somam o compasso e fecham o ciclo$rep", ok)
        val p44 = Conducting.forMeter(4, 4); val p68 = Conducting.forMeter(6, 8); val p34 = Conducting.forMeter(3, 4); val p38 = Conducting.forMeter(3, 8)
        check("4/4 = 4 gestos de 1 semínima; 6/8 = 2 gestos de 1,5; 3/4 = 3; 3/8 = 1 gesto", p44.beats == 4 && p44.beatQuarters == 1.0 && p68.beats == 2 && p68.beatQuarters == 1.5 && p68.sub == 3 && p34.beats == 3 && p38.beats == 1)
        check("Compasso mede o mesmo em semínimas que a pauta (6/8 = 3)", p68.measureQuarters == 3.0 && p44.measureQuarters == 4.0)
        check("O primeiro gesto desce (ictus 1 é o ponto mais baixo no 4/4)", (1 until 4).all { p44.ictusPoint(0).y > p44.ictusPoint(it).y })
        val o68 = Conducting.options(6, 8).map { it.label }; val o54 = Conducting.options(5, 4).map { it.label }
        check("6/8: em 2 (padrão) e em 6; 5/4: 3+2 e 2+3 em 5 ou em 2 gestos ($o68 / $o54)", o68[0].startsWith("em 2") && o68.any { it == "em 6" } && o54.size == 4)
        val p78 = Conducting.forMeter(7, 8, 0)
        check("7/8 = 2+2+3: três gestos de 1, 1 e 1,5 semínima", p78.beats == 3 && p78.beatLens == listOf(1.0, 1.0, 1.5))
        val p58 = Conducting.forMeter(5, 8, 1)
        check("5/8 em 2+3: gestos de 1 e 1,5; o 2º gesto começa em 1 semínima", p58.beatLens == listOf(1.0, 1.5) && Math.abs(p58.phaseAtQuarters(1.0) - 1.0) < 1e-9 && Math.abs(p58.phaseAtQuarters(1.25) - 1.1666666) < 1e-3)
        // o ictus é o ponto de maior velocidade (rebate): a batuta chega rápido e sai devagar
        val before = p44.position(1.0 - 0.02); val at = p44.position(1.0); val after = p44.position(1.0 + 0.02)
        val dIn = Math.hypot((at.x - before.x).toDouble(), (at.y - before.y).toDouble()); val dOut = Math.hypot((after.x - at.x).toDouble(), (after.y - at.y).toDouble())
        check("Rebote: chega ao ictus mais rápido do que sai dele", dIn > dOut)
    }
    run {
        val n1 = Edit.blankFor("n", -2, 3, 4, 16, Instruments.byId("piano")!!, 90)
        check("Nova partitura de piano: 2 pautas G+F, 16 compassos, sem 'Piano 2'", n1.parts.size == 2 && n1.parts[0].name == "Piano (pauta 1)" && n1.parts[1].clef == 'F' && n1.parts[0].measures.size == 16 && n1.tempo == 90 && Validate.measureDurations(n1).isEmpty())
        val n2 = Edit.blankFor("n", 0, 6, 8, 8, Instruments.byId("guitar")!!)
        check("Nova partitura de violão em 6/8 com compassos de 3 semínimas", n2.parts.size == 1 && n2.parts[0].inst == "guitar" && n2.parts[0].measures[0].notes[0].duration == 12 && Validate.measureDurations(n2).isEmpty())
    }
    run {
        // todas as fórmulas: criar, validar, desenhar e sintetizar sem falhar
        var ok = true; val rep = StringBuilder()
        for (b in 1..16) for (bt in listOf(2, 4, 8, 16)) {
            try {
                val sc = Edit.blankMulti("m", 0, b, bt, 6, listOf(Instruments.byId("piano")!!, Instruments.byId("contralto")!!), 100)
                // preenche o primeiro compasso com notas do menor valor possível que cabe
                val len = 16 * b / bt
                for (k in 0 until len) Edit.enter(sc, 0, 0, k, 60 + k % 12, 1.0 / 4.0)
                val sceneOk = Engrave.scene(sc, 70f).let { true }
                val bad = Validate.measureDurations(sc)
                val pcm = Synth.render(sc, 120)
                val xml = MusicXml.parseAny(MusicXml.write(sc).toByteArray())
                if (bad.isNotEmpty() || !sceneOk || pcm.isEmpty() || xml.beats != b || xml.beatType != bt || xml.parts.size != sc.parts.size) { ok = false; rep.append(" $b/$bt") }
            } catch (e: Throwable) { ok = false; rep.append(" $b/$bt!${e.javaClass.simpleName}:${e.message}") }
        }
        check("Todas as fórmulas de 1/2 a 16/16 criam, desenham, soam e salvam em MusicXML$rep", ok)
        val multi = Edit.blankMulti("x", 0, 4, 4, 8, Instruments.PRESETS.first { it.id == "satbpiano" }.ids.map { Instruments.byId(it)!! })
        check("Coro SATB + piano: 4 vozes + 2 pautas = 6 partes, contralto com clave de sol e som de voz", multi.parts.size == 6 && multi.parts[1].name.contains("contralto") && multi.parts[1].inst == "voice" && multi.parts[1].clef == 'G' && Validate.measureDurations(multi).isEmpty())
        check("Ode à Alegria tem 16 compassos nas duas partes", Samples.ode().parts.all { it.measures.size == 16 } && Validate.measureDurations(Samples.ode()).isEmpty())
        val cl = Synth.clickLoopLens(listOf(1.0, 1.0, 1.5), 0.5)
        check("Metrônomo de gestos desiguais dura 3,5 semínimas (${cl.size})", Math.abs(cl.size - 3.5 * 0.5 * 44100) < 2)
    }
    check("Rede privada: 192.168.x, 10.x, Tailscale, .local, localhost sim; IP público e domínio não",
        listOf("192.168.0.10", "10.1.2.3", "172.20.0.5", "100.101.5.6", "meu-pc.local", "localhost", "meupc").all { isPrivateHost(it) } &&
        listOf("8.8.8.8", "172.32.0.1", "100.200.1.1", "omr.exemplo.com", "192.169.0.1").none { isPrivateHost(it) })
    check("Endereço do servidor: esquema certo por padrão e http público recusado",
        cleanServer("192.168.0.10:8080") == "http://192.168.0.10:8080" && cleanServer("omr.exemplo.com/omr?token=x") == "https://omr.exemplo.com" &&
        serverProblem("http://omr.exemplo.com") != null && serverProblem("http://192.168.0.10:8080") == null && serverProblem("https://omr.exemplo.com") == null && serverProblem("") != null)
    check("Nomes em cifra", Names.key(-3) == "E♭" && Names.key(0) == "C" && Names.minorKey(0) == "A" && Names.step('F', 1) == "F♯" && Names.pitch(Pitch('B', -1, 3)) == "B♭3")
    println(if (fails == 0) "TODOS OS TESTES PASSARAM" else "$fails FALHAS")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
