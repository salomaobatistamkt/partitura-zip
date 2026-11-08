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
    println(if (fails == 0) "TODOS OS TESTES PASSARAM" else "$fails FALHAS")
    kotlin.system.exitProcess(if (fails == 0) 0 else 1)
}
