package app.partitura.core

object Samples {
    private fun p(t: String): Pitch? {
        if (t == "r") return null
        val step = t[0]
        var i = 1; var alter = 0
        while (i < t.length && (t[i] == '#' || t[i] == 'b')) { alter += if (t[i] == '#') 1 else -1; i++ }
        return Pitch(step, alter, t.substring(i).toInt())
    }

    /** Linha "E4:1 F4:0.5 r:2 ..." onde o número é a duração em semínimas; barras "|" separam compassos. */
    private fun part(id: String, name: String, clef: Char, line: String): Part {
        val part = Part(id, name, mutableListOf(), clef)
        line.split("|").forEachIndexed { i, m ->
            val me = Measure(i + 1)
            m.trim().split(" ").filter { it.isNotBlank() }.forEach { tok ->
                val (a, b) = tok.split(":")
                me.notes += Note(p(a), (b.toDouble() * 4).toInt())
            }
            part.measures += me
        }
        return part
    }

    fun all(): List<Score> = listOf(ode(), escalaMib(), arpejoSolMenor())

    fun ode(): Score = Score("Ode à Alegria (exemplo)", 4, 0, 4, 4, 100).also {
        it.parts += part("P1", "Melodia", 'G', "E4:1 E4:1 F4:1 G4:1|G4:1 F4:1 E4:1 D4:1|C4:1 C4:1 D4:1 E4:1|E4:1.5 D4:0.5 D4:2|E4:1 E4:1 F4:1 G4:1|G4:1 F4:1 E4:1 D4:1|C4:1 C4:1 D4:1 E4:1|D4:1.5 C4:0.5 C4:2|D4:1 D4:1 E4:1 C4:1|D4:1 E4:0.5 F4:0.5 E4:1 C4:1|D4:1 E4:0.5 F4:0.5 E4:1 D4:1|C4:1 D4:1 G3:2|E4:1 E4:1 F4:1 G4:1|G4:1 F4:1 E4:1 D4:1|C4:1 C4:1 D4:1 E4:1|D4:1.5 C4:0.5 C4:2")
        it.parts += part("P2", "Acompanhamento", 'F', "C3:4|G2:4|C3:4|G2:2 C3:2|C3:4|G2:4|C3:4|G2:2 C3:2|G2:4|C3:4|G2:4|C3:2 G2:2|C3:4|G2:4|C3:4|G2:2 C3:2")
    }

    fun escalaMib(): Score = Score("Escala de Mib maior (exemplo)", 4, -3, 4, 4, 90).also {
        it.parts += part("P1", "Piano", 'G', "Eb4:1 F4:1 G4:1 Ab4:1|Bb4:1 C5:1 D5:1 Eb5:1|Eb5:1 D5:1 C5:1 Bb4:1|Ab4:1 G4:1 F4:1 Eb4:1")
    }

    fun arpejoSolMenor(): Score = Score("Arpejo de Sol menor (exemplo)", 4, -2, 4, 4, 80).also {
        it.parts += part("P1", "Piano", 'G', "G3:0.5 Bb3:0.5 D4:0.5 G4:0.5 Bb4:0.5 G4:0.5 D4:0.5 Bb3:0.5|G3:0.5 Bb3:0.5 D4:0.5 G4:0.5 Bb4:0.5 G4:0.5 D4:0.5 Bb3:0.5|A3:0.5 C4:0.5 Eb4:0.5 A4:0.5 C5:0.5 A4:0.5 Eb4:0.5 C4:0.5|G3:4")
    }
}
