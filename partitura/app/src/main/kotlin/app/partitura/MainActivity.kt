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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state.openPicker = { opener.launch(arrayOf("*/*")) }
        state.saveAs = { name, bytes -> pending = bytes; saver.launch(name) }
        setContent { PartituraTheme(state.dark) { Surface(color = MaterialTheme.colorScheme.background) { Root(state) } } }
    }

    override fun onDestroy() { state.player.stop(); super.onDestroy() }
}

private val AREAS = listOf("Início" to "⌂", "Editor" to "✎", "Reprodução" to "▶", "Transposição" to "⇅", "Teoria" to "♯", "Arquivos" to "≡")

@Composable
fun Root(state: AppState) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(state.message) { state.message?.let { snack.showSnackbar(it); state.message = null } }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val content: @Composable () -> Unit = {
            when (tab) {
                0 -> HomeScreen(state) { tab = 1 }
                1 -> EditorScreen(state, wide) { tab = 0 }
                2 -> PlaybackScreen(state) { tab = 0 }
                3 -> TransposeScreen(state) { tab = 0 }
                4 -> TheoryScreen(state) { tab = 1 }
                else -> FilesScreen(state) { tab = 0 }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(12.dp))
                    AREAS.forEachIndexed { i, (n, ic) ->
                        NavigationRailItem(selected = tab == i, onClick = { tab = i }, icon = { Text(ic, style = MaterialTheme.typography.titleLarge) }, label = { Text(n) })
                    }
                }
                Scaffold(modifier = Modifier.weight(1f), snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background) { pad -> Box(Modifier.padding(pad).fillMaxSize()) { content() } }
            }
        } else {
            Scaffold(
                snackbarHost = { SnackbarHost(snack) }, containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        AREAS.forEachIndexed { i, (n, ic) -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(ic) }, label = { Text(n, maxLines = 1, style = MaterialTheme.typography.labelSmall) }) }
                    }
                },
            ) { pad -> Box(Modifier.padding(pad).fillMaxSize()) { content() } }
        }
    }
}
