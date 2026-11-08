package app.partitura.core

import kotlin.math.exp
import kotlin.math.pow

/**
 * Timbre sintetizado (soma de harmônicos). Não são gravações de instrumentos reais:
 * é uma aproximação do caráter de cada família (percussivo, sustentado, sopro, voz).
 */
class Timbre(
    val id: String,
    val label: String,
    val nh: Int,                       // máximo de harmônicos
    val amp: (Int, Double) -> Double,  // amplitude do harmônico h (1..nh) para a frequência f0
    val sustained: Boolean,            // true: nota segura enquanto dura; false: decai como corda/tecla
    val attack: Double,                // segundos
    val releaseRate: Double,           // quanto maior, mais rápido some ao soltar
    val decayScale: Double = 1.0,      // só para notas que decaem (maior = decai mais rápido)
    val harmDecay: Double = 1.5,       // os agudos somem mais rápido que o grave (notas que decaem)
    val gain: Double = 1.0,
    /** inarmonicidade das cordas (B): estica os agudos como num piano real */
    val inharm: Double = 0.0,
    /** cordas/vozes por nota e afinação entre elas (cents): dá o "coro" e o batimento naturais */
    val strings: Int = 1, val detune: Double = 0.0,
    /** barulho do martelo no ataque (0..1) */
    val hammer: Double = 0.0,
    /** vibrato: profundidade (cents), velocidade (Hz) e atraso (s) */
    val vibrato: Double = 0.0, val vibRate: Double = 5.3, val vibDelay: Double = 0.25,
    /** ponto de ataque da corda (fração do comprimento); 0 = nenhum */
    val strike: Double = 0.0,
    /** quanto o toque (intensidade) clareia o timbre */
    val bright: Double = 0.0,
    /** frequência de corte suave (Hz): tira o "estridente" dos agudos */
    val fc: Double = 7000.0,
    /** reverberação desta voz (0..1) */
    val room: Double = 0.2,
) {
    companion object {
        private fun gauss(x: Double, c: Double, w: Double) = exp(-((x - c) / w).pow(2))

        val PIANO = Timbre("piano", "Piano", 14, { h, _ -> 1.0 / h.toDouble().pow(1.15) }, false, 0.003, 14.0, 1.0, 1.7, 1.3,
            inharm = 0.0003, strings = 2, detune = 1.4, hammer = 0.35, strike = 0.125, bright = 0.6, fc = 4200.0, room = 0.22)
        val PIANO_SOFT = Timbre("piano_soft", "Piano macio", 9, { h, _ -> 1.0 / h.toDouble().pow(1.7) }, false, 0.006, 12.0, 1.15, 1.9, 0.9,
            inharm = 0.0002, strings = 2, detune = 0.9, hammer = 0.12, bright = 0.35, fc = 2300.0, room = 0.3)
        val EPIANO = Timbre("epiano", "Piano elétrico", 8, { h, _ -> when (h) { 1 -> 1.0; 2 -> 0.4; 3 -> 0.1; 4 -> 0.26; 5 -> 0.04; 6 -> 0.09; else -> 0.03 } }, false, 0.004, 12.0, 0.8, 2.6, 1.1,
            strings = 2, detune = 3.0, hammer = 0.06, bright = 0.5, fc = 3500.0, room = 0.25)
        val GUITAR = Timbre("guitar", "Violão", 10, { h, _ -> 1.0 / h.toDouble().pow(1.05) * (if (h == 1) 1.0 else 0.85) }, false, 0.003, 18.0, 1.7, 2.6, 1.25,
            inharm = 0.00005, strings = 2, detune = 0.8, hammer = 0.1, strike = 0.18, bright = 0.4, fc = 3800.0, room = 0.2)
        val HARP = Timbre("harp", "Harpa", 8, { h, _ -> 1.0 / h.toDouble().pow(1.4) }, false, 0.002, 12.0, 2.0, 2.2, 0.63, hammer = 0.05, bright = 0.3, fc = 3500.0, room = 0.35)
        val BASS = Timbre("bass", "Baixo", 7, { h, _ -> 1.0 / h.toDouble().pow(1.6) }, false, 0.006, 14.0, 1.1, 2.0, 0.52, hammer = 0.08, bright = 0.3, fc = 1500.0, room = 0.1)
        val ORGAN = Timbre("organ", "Órgão", 8, { h, _ -> when (h) { 1 -> 1.0; 2 -> 0.7; 3 -> 0.35; 4 -> 0.5; 5 -> 0.12; 6 -> 0.18; else -> 0.06 } }, true, 0.018, 22.0, gain = 0.73,
            strings = 2, detune = 2.0, fc = 4000.0, room = 0.35)
        val STRINGS = Timbre("strings", "Cordas", 14, { h, _ -> 1.0 / h.toDouble().pow(1.25) }, true, 0.11, 7.0, gain = 0.56,
            strings = 3, detune = 9.0, vibrato = 7.0, vibRate = 5.4, vibDelay = 0.3, bright = 0.3, fc = 3200.0, room = 0.4)
        val PAD = Timbre("pad", "Pad suave", 8, { h, _ -> 1.0 / h.toDouble().pow(1.6) }, true, 0.35, 4.0, gain = 0.43,
            strings = 3, detune = 12.0, vibrato = 3.0, vibRate = 4.6, vibDelay = 0.5, fc = 2000.0, room = 0.5)
        val FLUTE = Timbre("flute", "Flauta", 5, { h, _ -> when (h) { 1 -> 1.0; 2 -> 0.28; 3 -> 0.09; 4 -> 0.04; else -> 0.02 } }, true, 0.05, 16.0, gain = 0.225,
            vibrato = 6.0, vibRate = 5.0, vibDelay = 0.35, fc = 4500.0, room = 0.3)
        val REED = Timbre("reed", "Clarinete/sax", 12, { h, _ -> if (h % 2 == 1) 1.0 / h.toDouble().pow(0.9) else 0.07 / h }, true, 0.04, 16.0, gain = 0.28,
            vibrato = 4.0, vibDelay = 0.4, fc = 3000.0, room = 0.25)
        val BRASS = Timbre("brass", "Metais", 12, { h, _ -> 1.0 / h.toDouble().pow(0.95) }, true, 0.04, 14.0, gain = 0.365,
            vibrato = 3.0, vibDelay = 0.5, bright = 0.5, fc = 2800.0, room = 0.3)
        /** Coro "aah": harmônicos realçados perto de formantes de vogal aberta; 3 vozes levemente desafinadas e vibrato. */
        val VOICE = Timbre("voice", "Coro (aah)", 24, { h, f0 -> val f = h * f0; (gauss(f, 700.0, 380.0) + 0.65 * gauss(f, 1150.0, 420.0) + 0.25 * gauss(f, 2600.0, 600.0) + 0.05) / h.toDouble().pow(0.55) },
            true, 0.09, 8.0, gain = 0.565, strings = 3, detune = 11.0, vibrato = 11.0, vibRate = 5.1, vibDelay = 0.3, bright = 0.2, fc = 3600.0, room = 0.4)
        /** Coro "ooh": vogal fechada, mais escura e macia. */
        val VOICE_OO = Timbre("voice_oo", "Coro (ooh)", 20, { h, f0 -> val f = h * f0; (gauss(f, 380.0, 220.0) + 0.35 * gauss(f, 800.0, 260.0) + 0.05 * gauss(f, 2300.0, 500.0) + 0.02) / h.toDouble().pow(0.7) },
            true, 0.12, 7.0, gain = 0.41, strings = 3, detune = 10.0, vibrato = 9.0, vibRate = 4.9, vibDelay = 0.35, fc = 2400.0, room = 0.45)

        val ALL = listOf(PIANO, PIANO_SOFT, EPIANO, GUITAR, HARP, BASS, ORGAN, STRINGS, PAD, FLUTE, REED, BRASS, VOICE, VOICE_OO)
        fun of(id: String): Timbre = ALL.firstOrNull { it.id == id } ?: PIANO
    }
}

/** Instrumento do catálogo: o que aparece na lista de "Adicionar instrumento". */
class Instrument(val id: String, val name: String, val clef: Char, val timbre: Timbre, val gm: Int, val staves: Int = 1, val note: String = "")

object Instruments {
    val CATALOG = listOf(
        Instrument("piano", "Piano", 'G', Timbre.PIANO, 0, staves = 2, note = "duas pautas (G e F)"),
        Instrument("guitar", "Violão", 'G', Timbre.GUITAR, 24, note = "escrito na altura real"),
        Instrument("bass", "Baixo", 'F', Timbre.BASS, 33, note = "escrito na altura real"),
        Instrument("organ", "Órgão", 'G', Timbre.ORGAN, 19),
        Instrument("violin", "Violino", 'G', Timbre.STRINGS, 40),
        Instrument("cello", "Violoncelo", 'F', Timbre.STRINGS, 42),
        Instrument("flute", "Flauta", 'G', Timbre.FLUTE, 73),
        Instrument("clarinet", "Clarinete", 'G', Timbre.REED, 71, note = "altura real (concerto)"),
        Instrument("trumpet", "Trompete", 'G', Timbre.BRASS, 56, note = "altura real (concerto)"),
        Instrument("trombone", "Trombone", 'F', Timbre.BRASS, 57),
        Instrument("soprano", "Voz (soprano)", 'G', Timbre.VOICE, 54),
        Instrument("contralto", "Voz (contralto)", 'G', Timbre.VOICE, 54, note = "região grave da voz feminina"),
        Instrument("tenor", "Voz (tenor)", 'G', Timbre.VOICE, 54, note = "escrito na altura real"),
        Instrument("bassvoice", "Voz (baixo)", 'F', Timbre.VOICE, 54),
        Instrument("oboe", "Oboé", 'G', Timbre.REED, 68),
        Instrument("altosax", "Sax alto", 'G', Timbre.REED, 65, note = "altura real (concerto)"),
        Instrument("tenorsax", "Sax tenor", 'G', Timbre.REED, 66, note = "altura real (concerto)"),
        Instrument("horn", "Trompa", 'G', Timbre.BRASS, 60, note = "altura real (concerto)"),
        Instrument("tuba", "Tuba", 'F', Timbre.BRASS, 58),
        Instrument("ukulele", "Ukulele", 'G', Timbre.GUITAR, 24, note = "altura real"),
        Instrument("cavaco", "Cavaquinho", 'G', Timbre.GUITAR, 24, note = "altura real"),
    )

    /** Conjuntos prontos para a criação de uma partitura. */
    class Preset(val id: String, val name: String, val ids: List<String>)
    val PRESETS = listOf(
        Preset("satb", "Coro SATB", listOf("soprano", "contralto", "tenor", "bassvoice")),
        Preset("satbpiano", "Coro + piano", listOf("soprano", "contralto", "tenor", "bassvoice", "piano")),
        Preset("voicepiano", "Voz + piano", listOf("soprano", "piano")),
        Preset("voiceguitar", "Voz + violão", listOf("soprano", "guitar")),
        Preset("strings", "Cordas (2 violinos, violoncelo)", listOf("violin", "violin", "cello")),
    )

    private val GM = mapOf("piano" to 0, "piano_soft" to 0, "epiano" to 4, "guitar" to 24, "harp" to 46, "bass" to 33, "organ" to 19, "strings" to 48, "pad" to 89, "flute" to 73, "reed" to 71, "brass" to 56, "voice" to 52, "voice_oo" to 53)
    /** Programa General MIDI (0..127) que representa o timbre. */
    fun gm(timbreId: String) = GM[timbreId] ?: 0

    fun byId(id: String) = CATALOG.firstOrNull { it.id == id }

    /** Programa General MIDI → timbre mais próximo (usado ao abrir arquivos de outros programas). */
    fun timbreForProgram(gm: Int): Timbre = when (gm) {
        in 0..7 -> Timbre.PIANO
        in 8..15 -> Timbre.PIANO
        in 16..23 -> Timbre.ORGAN
        in 24..31 -> Timbre.GUITAR
        in 32..39 -> Timbre.BASS
        in 40..51 -> Timbre.STRINGS
        in 52..54 -> Timbre.VOICE
        in 56..71 -> if (gm in 64..71) Timbre.REED else Timbre.BRASS
        in 72..79 -> Timbre.FLUTE
        else -> Timbre.PIANO
    }

    /** Palpite pelo nome da parte quando o arquivo não informa o programa MIDI. */
    fun timbreForName(name: String): Timbre {
        val n = name.lowercase()
        fun has(vararg k: String) = k.any { n.contains(it) }
        return when {
            has("violão", "violao", "guitar", "guitarra", "ukulele", "banjo") -> Timbre.GUITAR
            has("baixo", "bass") && !has("voz", "vocal", "coro", "choir", "tenor") -> Timbre.BASS
            has("órgão", "orgao", "organ") -> Timbre.ORGAN
            has("violin", "viola", "cello", "violonc", "string", "cordas") -> Timbre.STRINGS
            has("flaut", "flute", "oboé", "oboe", "piccolo") -> Timbre.FLUTE
            has("clarin", "sax", "fagote", "bassoon") -> Timbre.REED
            has("trompet", "trumpet", "trombon", "horn", "trompa", "tuba", "metais", "brass") -> Timbre.BRASS
            has("voz", "vocal", "voice", "soprano", "alto", "tenor", "coro", "choir") -> Timbre.VOICE
            else -> Timbre.PIANO
        }
    }
}
