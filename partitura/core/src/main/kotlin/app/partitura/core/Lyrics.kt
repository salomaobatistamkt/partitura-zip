package app.partitura.core

/**
 * Letra de música em português do Brasil: divisão em sílabas e sílaba tônica (força de cada sílaba).
 * As regras são as da gramática (encontros vocálicos, dígrafos, grupos consonantais); casos raros (hiatos fora da regra) podem escapar:
 * a letra fica editável na partitura.
 */
object Lyrics {
    /** [stress]: 2 sílaba tônica, 1 secundária/palavra átona de uma sílaba, 0 átona. [syl]: 's' palavra de uma sílaba, 'b' início, 'm' meio, 'e' fim. */
    class Syl(val text: String, val stress: Int, val syl: Char, val lineLast: Boolean = false)
    /** [cadence]: 2 fim de estrofe, 1 ponto final / exclamação / interrogação, 0 vírgula ou sem pontuação. */
    class Line(val syls: List<Syl>, val cadence: Int, val text: String)

    private const val VOWELS = "aeiouáéíóúâêôãõàüy"
    private fun isV(c: Char) = c.lowercaseChar() in VOWELS
    private fun base(c: Char): Char = when (c.lowercaseChar()) { 'á', 'â', 'ã', 'à' -> 'a'; 'é', 'ê' -> 'e'; 'í' -> 'i'; 'ó', 'ô', 'õ' -> 'o'; 'ú', 'ü' -> 'u'; else -> c.lowercaseChar() }
    private fun accented(c: Char) = c.lowercaseChar() in "áéíóúâêôàãõ"
    private val FUNCTION = setOf("a", "o", "as", "os", "um", "uns", "de", "da", "do", "das", "dos", "em", "no", "na", "nos", "nas", "ao", "aos", "e", "ou", "que", "se", "me", "te", "lhe", "lhes", "vos", "por", "com", "sem", "mas", "pra", "pro", "num", "numa", "dum", "duma", "meu", "teu", "seu", "nem", "até", "como", "quando", "onde", "pois", "ti", "si", "eu", "tu", "ele", "ela", "mim")
    // hiatos que a regra de encontros não pega (a segunda vogal tem o som de vogal própria)
    private val HIATUS = mapOf("dia" to listOf("di", "a"), "dias" to listOf("di", "as"), "tua" to listOf("tu", "a"), "tuas" to listOf("tu", "as"), "sua" to listOf("su", "a"), "suas" to listOf("su", "as"),
        "rua" to listOf("ru", "a"), "ruas" to listOf("ru", "as"), "lua" to listOf("lu", "a"), "luas" to listOf("lu", "as"), "via" to listOf("vi", "a"), "pia" to listOf("pi", "a"), "mia" to listOf("mi", "a"),
        "tia" to listOf("ti", "a"), "tio" to listOf("ti", "o"), "rio" to listOf("ri", "o"), "rios" to listOf("ri", "os"), "frio" to listOf("fri", "o"), "fria" to listOf("fri", "a"), "nua" to listOf("nu", "a"),
        "vazio" to listOf("va", "zi", "o"), "navio" to listOf("na", "vi", "o"), "criança" to listOf("cri", "an", "ça"), "poesia" to listOf("po", "e", "si", "a"), "alegria" to listOf("a", "le", "gri", "a"),
        "magia" to listOf("ma", "gi", "a"), "harmonia" to listOf("har", "mo", "ni", "a"), "melodia" to listOf("me", "lo", "di", "a"), "valia" to listOf("va", "li", "a"), "saudade" to listOf("sau", "da", "de"),
        "poeta" to listOf("po", "e", "ta"), "coelho" to listOf("co", "e", "lho"), "voo" to listOf("vo", "o"), "boa" to listOf("bo", "a"), "boas" to listOf("bo", "as"), "pessoa" to listOf("pes", "so", "a"),
        "lagoa" to listOf("la", "go", "a"), "canoa" to listOf("ca", "no", "a"), "perdoa" to listOf("per", "do", "a"), "leão" to listOf("le", "ão"), "real" to listOf("re", "al"), "ideal" to listOf("i", "de", "al"),
        "seara" to listOf("se", "a", "ra"), "leal" to listOf("le", "al"), "céu" to listOf("céu"), "deus" to listOf("deus"))

    private class U(val s: String, val vowel: Boolean)

    private fun units(w: String): List<U> {
        val out = ArrayList<U>(); var i = 0
        while (i < w.length) {
            val c = w[i]; val l = c.lowercaseChar()
            val nx = w.getOrNull(i + 1)?.lowercaseChar()
            val nn = w.getOrNull(i + 2)?.lowercaseChar()
            when {
                isV(c) -> { out += U(c.toString(), true); i++ }
                (l == 'c' && nx == 'h') || (l == 'l' && nx == 'h') || (l == 'n' && nx == 'h') -> { out += U(w.substring(i, i + 2), false); i += 2 }
                l == 'q' && nx == 'u' -> { out += U(w.substring(i, i + 2), false); i += 2 }
                l == 'g' && nx == 'u' && nn != null && isV(nn) && base(nn) in "aeio" -> { out += U(w.substring(i, i + 2), false); i += 2 }
                else -> { out += U(c.toString(), false); i++ }
            }
        }
        return out
    }

    private val ONSET2 = setOf("br", "cr", "dr", "fr", "gr", "pr", "tr", "vr", "bl", "cl", "fl", "gl", "pl", "tl")

    private fun fallingPair(a: Char, b: Char): Boolean = !accented(b) && base(b) in "iu" && base(a) != base(b) && b.lowercaseChar() !in "íú"

    /** Divide uma palavra (só letras) em sílabas. */
    fun syllabify(word: String): List<String> {
        val low = word.lowercase()
        HIATUS[low]?.let { parts ->
            if (parts.joinToString("") == low) {
                var k = 0
                return parts.map { p -> word.substring(k, k + p.length).also { k += p.length } }
            }
        }
        val us = units(word)
        // núcleos: grupos de vogais seguidas, divididos em ditongos/hiatos
        val nuclei = ArrayList<IntArray>()          // [início, fim] em índices de unidades
        var i = 0
        while (i < us.size) {
            if (!us[i].vowel) { i++; continue }
            var j = i
            while (j + 1 < us.size && us[j + 1].vowel) j++
            var s = i
            var k = i
            while (k < j) {
                val a = us[k].s[0]; val b = us[k + 1].s[0]
                val inGroup = k - s + 1 // vogais já no núcleo atual
                val joins = when {
                    inGroup >= 2 -> false
                    (a == 'ã' || a == 'Ã') && base(b) in "oe" -> true       // ão, ãe
                    (a == 'õ' || a == 'Õ') && base(b) == 'e' -> true         // õe
                    fallingPair(a, b) -> true                                  // ai ei oi ui au eu ou iu
                    // ditongo crescente no fim da palavra, com i/u átono: história, glória, água, série
                    base(a) in "iu" && !accented(a) && j == k + 1 && j == us.lastIndex - (if (us.last().vowel) 0 else 0) && i > 0 && us[i - 1].s.length >= 1 && !us[i - 1].vowel && (k - 1 >= 0 && us[k - 1].s.let { it.length == 1 && it[0].lowercaseChar() in "rgqbcdfptvl" || it.length == 2 }) && base(b) in "aeo" && us.size - j <= 2 -> true
                    else -> false
                }
                if (!joins) { nuclei += intArrayOf(s, k); s = k + 1 }
                k++
            }
            nuclei += intArrayOf(s, j)
            i = j + 1
        }
        if (nuclei.isEmpty()) return listOf(word)
        // fronteiras entre núcleos: reparte as consoantes
        val cuts = IntArray(nuclei.size - 1)    // índice (de unidade) onde começa a sílaba seguinte
        for (n in 0 until nuclei.size - 1) {
            val a = nuclei[n][1] + 1; val b = nuclei[n + 1][0] // consoantes em [a, b)
            val cnt = b - a
            cuts[n] = when {
                cnt <= 0 -> b
                cnt == 1 -> a
                else -> {
                    val last2 = us[b - 2].s.lowercase() + us[b - 1].s.lowercase()
                    if (cnt >= 2 && last2 in ONSET2 && us[b - 2].s.length == 1 && us[b - 1].s.length == 1) b - 2 else b - 1
                }
            }
        }
        val out = ArrayList<String>()
        var start = 0
        var pos = 0
        val unitStart = IntArray(us.size + 1); for (u in us.indices) unitStart[u + 1] = unitStart[u] + us[u].s.length
        for (c in cuts) { out += word.substring(unitStart[start], unitStart[c]); start = c }
        out += word.substring(unitStart[start])
        return out.filter { it.isNotEmpty() }
    }

    /** Índice da sílaba tônica. */
    fun tonic(word: String, syls: List<String>): Int {
        if (syls.size == 1) return 0
        fun hasAcute(s: String) = s.any { it.lowercaseChar() in "áéíóúâêôà" }
        syls.indexOfFirst { hasAcute(it) }.let { if (it >= 0) return it }
        syls.indexOfFirst { s -> s.any { it.lowercaseChar() in "ãõ" } }.let { if (it >= 0) return it }
        var w = word.lowercase()
        if (w.length > 3 && w.endsWith("s")) w = w.dropLast(1)
        val oxy = w.last() in "iulrzx" || w.endsWith("im") || w.endsWith("um")
        return if (oxy) syls.size - 1 else syls.size - 2
    }

    private val WORD = Regex("[\\p{L}]+")

    /** Quebra o texto em linhas cantáveis. Linhas em branco separam estrofes. */
    fun parse(text: String, maxSyl: Int = 12): List<Line> {
        val out = ArrayList<Line>()
        val raw = text.replace("\r", "").split("\n")
        for ((li, ln) in raw.withIndex()) {
            val trimmed = ln.trim()
            if (trimmed.isEmpty()) {
                if (out.isNotEmpty() && out.last().cadence < 2) out[out.lastIndex] = Line(out.last().syls, 2, out.last().text)
                continue
            }
            // trechos separados por vírgula/ponto-e-vírgula longos viram frases próprias quando passam do limite
            val syls = ArrayList<Syl>()
            val words = WORD.findAll(trimmed).map { it.value }.toList()
            for (wd in words) {
                val parts = syllabify(wd)
                val t = tonic(wd, parts)
                val mono = parts.size == 1
                parts.forEachIndexed { k, p ->
                    val stress = when {
                        mono -> if (wd.lowercase() in FUNCTION) 1 else 2
                        k == t -> 2
                        parts.size >= 4 && k == t - 2 -> 1
                        else -> 0
                    }
                    val type = if (mono) 's' else when (k) { 0 -> 'b'; parts.size - 1 -> 'e'; else -> 'm' }
                    syls += Syl(p, stress, type)
                }
            }
            if (syls.isEmpty()) continue
            val last = trimmed.trimEnd().lastOrNull { !it.isWhitespace() }
            val cad = when (last) { '.', '!', '?', '…' -> 1; else -> 0 }
            // frases longas: divide no meio, de preferência depois de uma palavra inteira
            val pieces = ArrayList<List<Syl>>()
            var cur: List<Syl> = syls
            while (cur.size > maxSyl) {
                val half = cur.size / 2
                var cut = half
                for (d in 0..half / 2) { val c1 = half + d; val c2 = half - d
                    if (c1 in 1 until cur.size && cur[c1 - 1].syl in "es") { cut = c1; break }
                    if (c2 in 1 until cur.size && cur[c2 - 1].syl in "es") { cut = c2; break } }
                pieces += cur.subList(0, cut); cur = cur.subList(cut, cur.size)
            }
            pieces += cur
            pieces.forEachIndexed { k, pc ->
                val fixed = pc.mapIndexed { i, s -> Syl(s.text, s.stress, s.syl, i == pc.lastIndex) }
                out += Line(fixed, if (k == pieces.lastIndex) cad else 0, fixed.joinToString("") { it.text })
            }
        }
        if (out.isNotEmpty()) out[out.lastIndex] = Line(out.last().syls, 2, out.last().text)
        return out
    }
}
