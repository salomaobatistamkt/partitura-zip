package app.partitura

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private const val REPO = "salomaobatistamkt/partitura-zip"
private const val BASE_DIR = "/partitura/omr-server"

private val COMPOSE = """services:
  omr:
    build: .
    restart: unless-stopped
    environment:
      - OMR_TOKEN=${'$'}{OMR_TOKEN}
    ports:
      - "8080:8080"
"""

private fun newToken(): String {
    val chars = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val r = java.security.SecureRandom()
    return (1..28).map { chars[r.nextInt(chars.length)] }.joinToString("")
}

/** Espaço do servidor de leitura (OMR): configurar, testar a conexão e aprender a colocar no ar. */
@Composable
fun ServerScreen(state: AppState) {
    var url by remember { mutableStateOf(state.serverUrl) }
    var token by remember { mutableStateOf(state.serverToken) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val clip = LocalClipboardManager.current
    val cs = MaterialTheme.colorScheme
    val saved = state.serverUrl.isNotBlank()

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 780.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Text("Servidor de leitura", style = MaterialTheme.typography.headlineMedium)
                Text("Lê PDF e foto de partitura e devolve as notas para o app.", color = cs.onSurfaceVariant)
            }

            Panel {
                val (label, color) = when {
                    result?.first == true -> "Conectado" to Color(0xFF3FA37A)
                    result?.first == false -> "Sem conexão" to cs.error
                    saved -> "Configurado (ainda não testado)" to cs.primary
                    else -> "Não configurado" to cs.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).clip(MaterialTheme.shapes.large).background(color))
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
                OutlinedTextField(
                    value = url, onValueChange = { url = it; result = null }, label = { Text("Endereço do servidor") },
                    placeholder = { Text("https://omr.seudominio.com") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it; result = null }, label = { Text("Chave de acesso (recomendado)") },
                    placeholder = { Text("a mesma que está em OMR_TOKEN no servidor") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Wrap {
                    Button(enabled = !testing, onClick = {
                        state.applyServer(url, token); url = state.serverUrl; testing = true; result = null
                        state.testServer(url, token) { ok, text -> testing = false; result = ok to text }
                    }) { Text(if (testing) "Testando…" else "Salvar e testar conexão") }
                    FilledTonalButton(onClick = { token = newToken(); result = null }) { Text("Gerar chave") }
                    if (token.isNotBlank()) FilledTonalButton(onClick = { clip.setText(AnnotatedString(token)) }) { Icon24(Ic.COPY, cs.onSecondaryContainer, 18.dp); Spacer(Modifier.width(6.dp)); Text("Copiar chave") }
                }
                result?.let { (ok, text) ->
                    Text(text, color = if (ok) Color(0xFF3FA37A) else cs.error, style = MaterialTheme.typography.bodyMedium)
                }
                if (result?.first == true) Text("Pronto: em Início → Abrir arquivo, escolha um PDF ou foto.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }

            Panel {
                Text("Como colocar no ar (Coolify)", style = MaterialTheme.typography.titleMedium)
                Text("Você só faz isso uma vez. A primeira montagem baixa os modelos de reconhecimento (cerca de 100 MB) e leva alguns minutos.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Step(1, "Gere uma chave aqui (botão acima) e copie.")
                Step(2, "No Coolify: Projects → seu projeto → + New → Public Repository (o repositório do projeto é público).")
                Step(3, "Repositório: https://github.com/$REPO · Branch: main · Build Pack: Dockerfile.")
                Step(4, "Base Directory: $BASE_DIR (a pasta onde está o Dockerfile). Porta exposta: 8080.")
                Step(5, "Em Environment Variables, crie OMR_TOKEN e cole a chave do passo 1.")
                Step(6, "Em Domains, coloque um endereço com https (ex.: https://omr.seudominio.com) e aponte o DNS para o servidor.")
                Step(7, "Clique em Deploy. Quando terminar, volte aqui, digite o endereço e a chave e toque em \"Salvar e testar conexão\".")
                Wrap {
                    FilledTonalButton(onClick = { clip.setText(AnnotatedString(BASE_DIR)) }) { Text("Copiar pasta (Base Directory)") }
                    FilledTonalButton(onClick = { clip.setText(AnnotatedString("https://github.com/$REPO")) }) { Text("Copiar endereço do repositório") }
                }
            }

            Panel {
                Text("Outro jeito: docker compose", style = MaterialTheme.typography.titleMedium)
                Text("Em qualquer servidor com Docker, dentro da pasta omr-server do projeto:", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Text(
                    "OMR_TOKEN=sua-chave docker compose up -d --build",
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(cs.surfaceVariant).padding(10.dp),
                )
                FilledTonalButton(onClick = { clip.setText(AnnotatedString(COMPOSE)) }) { Text("Copiar docker-compose.yml") }
            }

            Panel {
                Text("O que esperar", style = MaterialTheme.typography.titleMedium)
                listOf(
                    "PDF digital limpo (exportado de programa de música) é lido com poucos erros.",
                    "Foto tem mais erros: luz uniforme, folha reta e de frente ajudam muito. Sempre confira as notas depois.",
                    "Leva de 10 segundos a 1–2 minutos por página, conforme o servidor. PDF: até 12 páginas e 40 MB.",
                    "Letra escrita à mão não é reconhecida.",
                    "Se o domínio passa pelo proxy da Cloudflare, pedidos acima de 100 s são cortados: use DNS sem proxy (nuvem cinza) para este endereço.",
                    "Sem chave, qualquer pessoa que descubra o endereço pode usar o servidor. Use sempre a chave.",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(24.dp).clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Text("$n", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold)
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
