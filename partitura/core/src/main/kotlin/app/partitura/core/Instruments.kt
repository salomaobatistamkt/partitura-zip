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
) {
    companion object {
        private fun gauss(x: Double, c: Double, w: Double) = exp(-((x - c) / w).pow(2))

        val PIANO = Timbre("piano", "Piano", 6, { h, _ -> 1.0 / h.toDouble().pow(1.3) }, false, 0.004, 14.0)
        val GUITAR = Timbre("guitar", "Violão", 8, { h, _ -> 1.0 / h.toDouble().pow(1.05) * (if (h == 1) 1.0 else 0.85) }, false, 0.003, 18.0, 1.7, 2.6, 1.15)
        val BASS = Timbre("bass", "Baixo", 5, { h, _ -> 1.0 / h.toDouble().pow(1.6) }, false, 0.006, 14.0, 1.1, 2.0, 1.35)
        val ORGAN = Timbre("organ", "Órgão", 6, { h, _ -> when (h) { 1 -> 1.0; 2 -> 0.7; 3 -> 0.35; 4 -> 0.5; 5 -> 0.12; else -> 0.18 } }, true, 0.018, 22.0, gain = 0.8)
        val STRINGS = Timbre("strings", "Cordas", 12, { h, _ -> 1.0 / h.toDouble() }, true, 0.09, 9.0, gain = 0.8)
        val FLUTE = Timbre("flute", "Flauta", 4, { h, _ -> when (h) { 1 -> 1.0; 2 -> 0.28; 3 -> 0.09; else -> 0.04 } }, true, 0.05, 16.0, gain = 1.5)
        val REED = Timbre("reed", "Clarinete/sax", 9, { h, _ -> if (h % 2 == 1) 1.0 / h.toDouble().pow(0.9) else 0.07 / h }, true, 0.04, 16.0, gain = 1.0)
        val BRASS = Timbre("brass", "Metais", 10, { h, _ -> 1.0 / h.toDouble().pow(0.85) }, true, 0.035, 14.0, gain = 0.85)
        /** Voz "aah": harmônicos realçados perto de formantes de vogal aberta. */
        val VOICE = Timbre("voice", "Voz", 22, { h, f0 -> val f = h * f0; (gauss(f, 700.0, 380.0) + 0.65 * gauss(f, 1150.0, 420.0) + 0.25 * gauss(f, 2600.0, 600.0) + 0.05) / h.toDouble().pow(0.55) },
            true, 0.07, 10.0, gain = 1.1)

        val ALL = listOf(PIANO, GUITAR, BASS, ORGAN, STRINGS, FLUTE, REED, BRASS, VOICE)
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

    private val GM = mapOf("piano" to 0, "guitar" to 24, "bass" to 33, "organ" to 19, "strings" to 40, "flute" to 73, "reed" to 71, "brass" to 56, "voice" to 54)
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
