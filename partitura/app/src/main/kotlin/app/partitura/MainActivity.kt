package app.partitura

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import app.partitura.core.Names
import app.partitura.core.Synth

class MainActivity : ComponentActivity() {
    private val state by lazy { AppState(applicationContext) }
    private var pending: ByteArray? = null

    private val opener = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) state.importUri(contentResolver, uri)
    }
    private val saver = registerForActivityResult(ActivityResultContracts.CreateDocument("*/*")) { uri: Uri? ->
        val data = pending
        if (uri != null && data != null) {
            try { contentResolver.openOutputStream(uri)?.use { it.write(data) }; state.message = "Arquivo salvo." }
            catch (e: Exception) { state.message = "Não foi possível salvar: ${e.message}" }
        }
        pending = null
    }

    private val credential = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == android.app.Activity.RESULT_OK) state.unlockServer()
    }

    override fun onStop() { state.lockServer(); super.onStop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.init(assets)
        state.requestUnlock = {
            val km = getSystemService(KEYGUARD_SERVICE) as android.app.KeyguardManager
            @Suppress("DEPRECATION")
            val i = km.createConfirmDeviceCredentialIntent("Servidor privado", "Confirme que é você para abrir a chave do servidor.")
            if (i != null) credential.launch(i) else state.message = "Este tablet não tem bloqueio de tela."
        }
        state.openPicker = { opener.launch(arrayOf("*/*")) }
        state.saveAs = { name, bytes -> pending = bytes; saver.launch(name) }
        setContent { PartituraTheme(state.dark) { Surface(color = MaterialTheme.colorScheme.background) { Root(state) } } }
    }

    override fun onDestroy() { state.player.release(); super.onDestroy() }
}

private class Area(val idx: Int, val name: String, val sub: String, val ic: Ic, val color: Color)

private val AREAS = listOf(
    Area(0, "Início", "Abrir ou criar", Ic.HOME, Amber2),
    Area(1, "Estúdio", "Escrever e tocar", Ic.STUDIO, Amber2),
    Area(2, "Regência", "Aprender a reger", Ic.CONDUCT, Violet),
    Area(3, "Teoria", "Escalas e círculo", Ic.THEORY, Sky),
    Area(4, "Transpor", "Mudar de tom", Ic.SWAP, Sky),
    Area(5, "Arquivos", "Salvar e exportar", Ic.FOLDER, Mint),
    Area(6, "Servidor", "Ler PDF e foto", Ic.SERVER, Mint),   // escondido: só abre por Ajustes → Servidor privado
    Area(7, "Criatividade", "Letra vira música", Ic.STUDIO, Violet),
    Area(8, "Ouvir", "Tocar vira partitura", Ic.PIANO, Violet),
    Area(9, "Ler", "PDF vira notas", Ic.OPEN, Mint),
)
private val SECTIONS = listOf("CRIAR" to listOf(0, 1, 9, 8, 7, 2), "APRENDER" to listOf(3, 4), "ARQUIVOS" to listOf(5))
private val PHONE_TABS = listOf(0, 1, 9, 8, 99)

@Composable
fun Root(state: AppState) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val snack = remember { SnackbarHostState() }
    state.goStudio = { tab = 1 }
    state.goScan = { tab = 9 }
    state.goServer = { tab = 6 }
    state.goHome = { tab = 0 }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); state.message = null } }
    // respeita a barra de status (relógio/bateria), a barra de gestos e o recorte da tela: nada fica por baixo deles
    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val wide = maxWidth >= 720.dp
        val full = maxWidth >= 980.dp
        val content: @Composable () -> Unit = {
            when (tab) {
                0 -> HomeScreen(state)
                1 -> StudioScreen(state, wide) { tab = 0 }
                2 -> ConductScreen(state)
                3 -> TheoryScreen(state, wide)
                4 -> TransposeScreen(state)
                5 -> FilesScreen(state)
                6 -> ServerScreen(state)
                7 -> CreativeScreen(state)
                8 -> ListenScreen(state)
                9 -> ScanScreen(state)
                else -> MoreScreen { tab = it }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                val mode = if (state.sideMode < 0) (if (full) 0 else 1) else state.sideMode
                Sidebar(state, tab, mode) { tab = it }
                Scaffold(modifier = Modifier.weight(1f), snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background) { pad -> Box(Modifier.padding(pad).fillMaxSize()) { content() } }
            }
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        PHONE_TABS.forEach { i ->
                            val a = AREAS.getOrNull(i)
                            val sel = if (i == 99) tab in listOf(9, 3, 2, 4, 5, 6) else tab == i
                            val name = a?.name ?: "Mais"
                            NavigationBarItem(
                                selected = sel, onClick = { tab = i },
                                icon = { Icon24(a?.ic ?: Ic.MORE, if (sel) (a?.color ?: MaterialTheme.colorScheme.primary) else MaterialTheme.colorScheme.onSurfaceVariant) },
                                label = { Text(name, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                            )
                        }
                    }
                },
            ) { pad -> Box(Modifier.padding(pad).fillMaxSize()) { content() } }
        }
    }
    if (state.importing) {
        AlertDialog(
            onDismissRequest = {}, confirmButton = {},
            title = { Text("Lendo a partitura…") },
            text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) { CircularProgressIndicator(); Spacer(Modifier.height(12.dp)); Text(if (state.importStage.isBlank()) "O servidor está reconhecendo as notas. Pode levar um minuto." else state.importStage.replaceFirstChar { it.uppercase() } + "…") } },
        )
    }
}

@Composable
private fun MoreScreen(go: (Int) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Mais", style = MaterialTheme.typography.headlineMedium)
        listOf(9, 3, 2, 4, 5).forEach { i ->
            val a = AREAS[i]
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(MaterialTheme.colorScheme.surface).clickable { go(i) }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(Modifier.size(44.dp).clip(MaterialTheme.shapes.medium).background(a.color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { Icon24(a.ic, a.color) }
                Column { Text(a.name, style = MaterialTheme.typography.titleMedium); Text(a.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

/** Menu lateral: marca, seções coloridas e um cartão "Agora" com a partitura aberta e o play. Pode ser recolhido ou escondido. */
@Composable
private fun Sidebar(state: AppState, tab: Int, mode: Int, go: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    if (mode == 2) {
        // escondido: só uma faixa fina com o botão de voltar
        Box(Modifier.width(30.dp).fillMaxHeight().background(cs.surface).clickable { state.applySide(1) }, contentAlignment = Alignment.TopCenter) {
            Text("›", style = MaterialTheme.typography.headlineMedium, color = cs.primary, modifier = Modifier.padding(top = 10.dp))
        }
        return
    }
    val full = mode == 0
    Column(Modifier.width(if (full) 248.dp else 96.dp).fillMaxHeight().background(cs.surface).padding(horizontal = if (full) 12.dp else 8.dp, vertical = 14.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = if (full) Alignment.Start else Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // marca
            Row(Modifier.padding(horizontal = if (full) 6.dp else 0.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.size(42.dp).clip(MaterialTheme.shapes.medium).background(Brush.linearGradient(listOf(Color(0xFFF2C46B), Color(0xFFD9822B)))), contentAlignment = Alignment.Center) {
                    GlyphText(0xE050, 30.dp, Color(0xFF1B1405), base = 0.78f)
                }
                if (full) Column {
                    Text("Compasso", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Partituras", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
            SECTIONS.forEach { (title, ids) ->
                if (full) Text(title, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp, top = 10.dp, bottom = 2.dp), letterSpacing = 1.2.sp)
                else Spacer(Modifier.height(6.dp))
                ids.forEach { i -> NavItem(AREAS[i], tab == i, full) { go(i) } }
            }
        }
        if (full) { Spacer(Modifier.height(10.dp)); NowCard(state, go) }
        Spacer(Modifier.height(6.dp))
        // minimizar / expandir
        if (full) {
            TextButton(onClick = { state.applySide(1) }, modifier = Modifier.fillMaxWidth()) { Text("‹  Recolher menu") }
        } else {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                TextButton(onClick = { state.applySide(0) }, contentPadding = PaddingValues(4.dp)) { Text("abrir ›", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { state.applySide(2) }, contentPadding = PaddingValues(4.dp)) { Text("‹ ocultar", style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

@Composable
private fun NavItem(a: Area, selected: Boolean, full: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val tile: @Composable () -> Unit = {
        Box(
            Modifier.size(if (full) 38.dp else 46.dp).clip(MaterialTheme.shapes.medium).background(if (selected) a.color else a.color.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center,
        ) { Icon24(a.ic, if (selected) Color(0xFF14110A) else a.color, 22.dp) }
    }
    if (full) {
        Row(
            Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(if (selected) cs.surfaceVariant else Color.Transparent).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            tile()
            Column(Modifier.weight(1f)) {
                Text(a.name, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) cs.onSurface else cs.onSurface.copy(alpha = 0.9f))
                Text(a.sub, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1)
            }
            if (selected) Box(Modifier.size(width = 4.dp, height = 22.dp).clip(MaterialTheme.shapes.small).background(a.color))
        }
    } else {
        Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).clickable(onClick = onClick).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            tile()
            Text(a.name, style = MaterialTheme.typography.labelSmall, color = if (selected) cs.onSurface else cs.onSurfaceVariant, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
        }
    }
}

@Composable
private fun NowCard(state: AppState, go: (Int) -> Unit) {
    val cs = MaterialTheme.colorScheme
    val s = state.score
    val p = state.player
    Column(Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(cs.surfaceVariant).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("AGORA", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, letterSpacing = 1.2.sp)
        if (s == null) {
            Text("Nenhuma partitura aberta", style = MaterialTheme.typography.titleSmall)
            TextButton(onClick = { state.openPicker() }, contentPadding = PaddingValues(0.dp)) { Text("Abrir arquivo") }
        } else {
            Text(s.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { go(1) })
            val bars = s.parts.firstOrNull()?.measures?.size ?: 0
            Text("${Names.key(s.fifths)} · ${s.beats}/${s.beatType} · ♩ ${state.tempo} · $bars compassos", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            val total = remember(s, state.rev, p.usedTempo) { Synth.measureTimes(s, if (p.active) p.usedTempo else state.tempo).last() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ToolBtn(
                    if (p.playing) Ic.PAUSE else Ic.PLAY,
                    { when { p.busy -> {}; p.playing -> p.pause(); p.paused -> p.resume(); else -> state.play(null) } },
                    primary = true,
                )
                val prog by remember(total) { derivedStateOf { if (p.active && p.kind == "music" && total > 0) (p.posSec / total).toFloat().coerceIn(0f, 1f) else 0f } }
                Column(Modifier.weight(1f)) {
                    LinearProgressIndicator(progress = { prog }, Modifier.fillMaxWidth(), color = cs.primary, trackColor = cs.outline)
                    Text(if (p.busy) "Preparando…" else if (p.playing) "Tocando" else if (p.paused) "Pausado" else "Pronto", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}
