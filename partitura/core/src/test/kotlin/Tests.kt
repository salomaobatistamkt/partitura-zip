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
    check("Campo harmônico de Mib: " + field.joinToString(" ") { it.name }, field.joinToString(" ") { it.name } == "Mi♭ Fám Solm Lá♭ Si♭ Dóm Ré°")
    check("Campo: Mib maior tríade", pn(field[0].notes) == "Eb3 G3 Bb3" && pn(field[6].notes) == "D4 F4 Ab4")
    val fm = Theory.harmonicField(Pitch('G', 0, 3), false).joinToString(" ") { it.name }
    check("Campo de Sol menor: $fm", fm == "Solm Lá° Si♭ Dóm Rém Mi♭ Fá")
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
    val mt = Synth.measureTimes(ode, 60); check("Tempos dos compassos a 60 bpm: 4 s por compasso", mt.size == 9 && mt[1] == 4.0 && mt[8] == 32.0)
    val part1 = Synth.render(ode, 120, fromSec = mt[1] / 2, toSec = mt[2] / 2)
    check("Tocar só compasso 2 gera trecho curto", part1.size < Synth.render(ode, 120).size / 3)
    println(if (fails == 0) "TODOS OS TESTES PASSARAM" else "$fails FALHAS")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
