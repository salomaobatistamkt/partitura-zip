package app.partitura

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

private const val REPO_URL = "https://github.com/salomaobatistamkt/partitura-zip/tree/main/partitura/omr-server"

/** Servidor PRIVADO de leitura de PDF/foto. A tela só abre depois de desbloquear (bloqueio do tablet ou PIN do app). */
@Composable
fun ServerScreen(state: AppState) {
    // refaz a leitura do tempo a cada segundo para trancar sozinho
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); tick++ } }
    val unlocked = state.serverUnlocked.also { tick }
    if (!unlocked) LockPanel(state) else ServerContent(state)
}

@Composable
private fun LockPanel(state: AppState) {
    val cs = MaterialTheme.colorScheme
    val km = androidx.compose.ui.platform.LocalContext.current.getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
    val deviceLock = remember { km.isDeviceSecure }
    var pin by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 520.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Servidor privado", style = MaterialTheme.typography.headlineMedium)
            Panel {
                Text("Área protegida", style = MaterialTheme.typography.titleMedium)
                Text("Aqui ficam a chave e o endereço do seu servidor. Só abre com o seu desbloqueio e tranca sozinha em 5 minutos ou quando você sai do app.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                if (deviceLock) {
                    Button(onClick = { state.requestUnlock() }) { Text("Desbloquear com a tela de bloqueio") }
                    Text("Usa a digital, o rosto, o padrão ou o PIN que você já usa para destravar o tablet.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else if (!state.hasPin) {
                    Text("Este tablet não tem bloqueio de tela. Crie um PIN só para esta área (4 a 12 números):", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("Novo PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    OutlinedTextField(pin2, { pin2 = it.filter(Char::isDigit).take(12) }, label = { Text("Repita o PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    Button(onClick = {
                        if (pin.length < 4) err = "Use pelo menos 4 números."
                        else if (pin != pin2) err = "Os dois PINs são diferentes."
                        else { state.setPin(pin); state.unlockServer(); pin = ""; pin2 = ""; err = null }
                    }) { Text("Criar PIN e entrar") }
                    Text("Dica: colocar um bloqueio de tela no tablet (Configurações → Tela de bloqueio) protege também o resto dos seus arquivos.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                } else {
                    OutlinedTextField(pin, { pin = it.filter(Char::isDigit).take(12) }, label = { Text("PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    Button(onClick = { if (state.tryPin(pin)) { pin = ""; err = null } else { err = "PIN errado."; pin = "" } }) { Text("Entrar") }
                }
                err?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodyMedium) }
            }
            TextButton(onClick = { state.goHome() }) { Text("‹ Voltar ao início") }
        }
    }
}

@Composable
private fun ServerContent(state: AppState) {
    val cs = MaterialTheme.colorScheme
    val clip = LocalClipboardManager.current
    var url by remember { mutableStateOf(state.serverUrl) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<String>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmForget by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 780.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Servidor privado", style = MaterialTheme.typography.headlineMedium)
                    Text("Lê PDF e foto de partitura. Só aparelhos pareados conseguem usar.", color = cs.onSurfaceVariant)
                }
                TextButton(onClick = { state.lockServer(); state.goHome() }) { Text("Trancar") }
            }

            Panel {
                val (label, color) = when {
                    result?.first == true -> "Conectado" to Color(0xFF3FA37A)
                    result?.first == false -> "Sem conexão" to cs.error
                    state.paired -> "Pareado (ainda não testado)" to cs.primary
                    else -> "Não pareado" to cs.onSurfaceVariant
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(12.dp).clip(MaterialTheme.shapes.large).background(color))
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
                if (state.paired) {
                    Text(state.serverUrl, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    Text("A chave deste aparelho está guardada criptografada (Keystore do Android) e nunca aparece na tela.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    Wrap {
                        Button(enabled = !busy, onClick = { busy = true; result = null; state.testServer { ok, t -> busy = false; result = ok to t } }) { Text(if (busy) "Testando…" else "Testar conexão") }
                        OutlinedButton(onClick = { confirmForget = true }) { Text("Desconectar este aparelho") }
                    }
                    result?.let { (ok, t) -> Text(t, color = if (ok) Color(0xFF3FA37A) else cs.error, style = MaterialTheme.typography.bodyMedium) }
                    if (result?.first == true) Text("Pronto: em Início → Abrir arquivo, escolha um PDF ou foto.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }

            if (!state.paired) Panel {
                Text("Parear com o servidor", style = MaterialTheme.typography.titleMedium)
                Text("1) Ligue o servidor no seu computador (veja abaixo). 2) Ele mostra um código de 8 letras. 3) Digite o endereço e o código aqui.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Wrap {
                    FilledTonalButton(enabled = !searching, onClick = { searching = true; found = null; state.discoverServers { found = it; searching = false } }) { Text(if (searching) "Procurando…" else "Procurar na rede") }
                }
                found?.let { list ->
                    if (list.isEmpty()) Text("Não achei nenhum. Confira se o computador está na mesma rede Wi-Fi e foi iniciado com run-local; ou digite o endereço.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    else Wrap { list.forEach { u -> AssistChip(onClick = { url = u }, label = { Text(u) }) } }
                }
                OutlinedTextField(url, { url = it; result = null }, label = { Text("Endereço do servidor") }, placeholder = { Text("http://192.168.0.10:8080") }, singleLine = true, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(code, { code = it.uppercase().take(9); result = null }, label = { Text("Código de pareamento") }, placeholder = { Text("ABCD-2345") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Button(enabled = !busy && url.isNotBlank() && code.isNotBlank(), onClick = {
                    busy = true; result = null
                    state.pairServer(url, code) { ok, t -> busy = false; result = ok to t; if (ok) code = "" }
                }) { Text(if (busy) "Pareando…" else "Parear") }
                result?.let { (ok, t) -> Text(t, color = if (ok) Color(0xFF3FA37A) else cs.error, style = MaterialTheme.typography.bodyMedium) }
                Text("Endereços de rede de casa (192.168…, 10…, Tailscale) podem usar http. Qualquer outro precisa de https.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }

            Panel {
                Text("Como ligar o servidor (no seu computador)", style = MaterialTheme.typography.titleMedium)
                Text("O servidor roda na sua casa. Nada fica aberto na internet e ninguém sem o código consegue usar.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                Step(1, "Baixe a pasta omr-server do projeto (botão abaixo → Code → Download ZIP, ou git clone) e abra no computador.")
                Step(2, "Windows: instale o Python 3.10+ e o poppler (o arquivo run-local.bat explica) e dê dois cliques em run-local.bat. Linux/macOS: ./run-local.sh.")
                Step(3, "Na primeira vez ele instala o reconhecedor (homr) e baixa os modelos: alguns minutos.")
                Step(4, "Ele mostra os endereços do computador e o CÓDIGO DE PAREAMENTO (vale 10 minutos).")
                Step(5, "Aqui no app: Procurar na rede (ou digite o endereço) → digite o código → Parear.")
                Wrap {
                    FilledTonalButton(onClick = { clip.setText(AnnotatedString(REPO_URL)) }) { Icon24(Ic.COPY, cs.onSecondaryContainer, 18.dp); Spacer(Modifier.width(6.dp)); Text("Copiar endereço da pasta") }
                }
                Text("Docker: dentro de omr-server, docker compose up -d --build; docker compose logs omr mostra o código.", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }

            Panel {
                Text("Usar fora de casa", style = MaterialTheme.typography.titleMedium)
                Text("O jeito seguro é uma VPN privada, como o Tailscale (grátis para uso pessoal): instale no computador e no tablet e use o endereço 100.x.x.x do computador aqui. Assim o servidor continua invisível para a internet.", style = MaterialTheme.typography.bodyMedium)
            }

            Panel {
                Text("O que esperar", style = MaterialTheme.typography.titleMedium)
                listOf(
                    "PDF digital limpo (exportado de programa de música) é lido com poucos erros.",
                    "Foto tem mais erros: luz uniforme, folha reta e de frente ajudam. Sempre confira as notas depois.",
                    "Leva de 10 segundos a 1–2 minutos por página, conforme o computador. PDF: até 12 páginas e 40 MB. O app mostra o andamento.",
                    "Letra e partitura escrita à mão não são reconhecidas.",
                    "Tentativas erradas bloqueiam o endereço por 15 minutos. Para desconectar tudo: python server.py --revoke-all.",
                ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
            }
        }
    }
    if (confirmForget) AlertDialog(
        onDismissRequest = { confirmForget = false },
        title = { Text("Desconectar este aparelho?") },
        text = { Text("A chave será apagada daqui e o servidor deixa de aceitá-la. Para usar de novo, é preciso parear outra vez.") },
        confirmButton = { Button(onClick = { confirmForget = false; state.unpairServer { state.message = it; result = null; url = "" } }) { Text("Desconectar") } },
        dismissButton = { TextButton(onClick = { confirmForget = false }) { Text("Cancelar") } },
    )
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
