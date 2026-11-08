package app.partitura.core

/** Endereços da rede de casa (onde "http" simples é aceitável): localhost, 10.x, 192.168.x, 172.16-31.x, Tailscale (100.64-127.x), .local, nomes sem ponto. */
fun isPrivateHost(host: String): Boolean {
    val h = host.lowercase()
    if (h == "localhost" || h.endsWith(".local") || !h.contains('.')) return true
    val p = h.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size != 4) return false
    return p[0] == 10 || p[0] == 127 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31) || (p[0] == 100 && p[1] in 64..127)
}

/** Normaliza o endereço do servidor: tira /omr e ?token, e escolhe http (rede de casa) ou https (resto) quando faltar o esquema. */
fun cleanServer(raw: String): String {
    var u = raw.trim().substringBefore("?").trimEnd('/')
    if (u.endsWith("/omr")) u = u.removeSuffix("/omr")
    if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) {
        val host = u.substringBefore("/").substringBefore(":")
        u = (if (isPrivateHost(host)) "http://" else "https://") + u
    }
    return u.trimEnd('/')
}

/** Mensagem de erro se o endereço não puder ser usado com segurança; null se estiver bem. */
fun serverProblem(base: String): String? {
    if (base.isBlank()) return "Digite o endereço do servidor."
    val uri = try { java.net.URI(base) } catch (e: Exception) { return "Endereço inválido." }
    val host = uri.host ?: return "Endereço inválido."
    if (uri.scheme == "http" && !isPrivateHost(host)) return "Fora da sua rede, o servidor precisa de https:// (http puro expõe a chave). Use um endereço da rede de casa, Tailscale ou https."
    return null
}

