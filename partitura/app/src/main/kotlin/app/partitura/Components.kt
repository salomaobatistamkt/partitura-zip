package app.partitura

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.composed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.partitura.core.Layout
import app.partitura.core.Score

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.large)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp), content = content,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Wrap(content: @Composable FlowRowScope.() -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

@Composable
fun Empty(title: String, body: String, state: AppState, onGo: (() -> Unit)? = null) {
    Panel {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = { onGo?.invoke() ?: state.openPicker() }) { Text(if (onGo != null) "Ir para o início" else "Abrir arquivo") }
    }
}

/** Pauta rolável na vertical, com seleção por toque quando [editable]. */
@Composable
fun StaffView(state: AppState, score: Score, editable: Boolean, modifier: Modifier = Modifier) {
    val sc = LocalDensity.current.density * 1.1f
    var w by remember { mutableIntStateOf(0) }
    val rev = state.rev
    val lay = remember(rev, w, score) { if (w > 0) Layout.layout(score, w / sc) else null }
    val ink = MaterialTheme.colorScheme.onSurface.toArgb()
    val accent = MaterialTheme.colorScheme.primary.toArgb()
    val rows = lay?.rows?.size ?: 1
    val heightDp = with(LocalDensity.current) { StaffRenderer.height(rows, sc).toDp() }
    val sel = if (editable) Triple(state.selP, state.selM, state.selN) else null
    Canvas(
        modifier.fillMaxWidth().height(heightDp).onSizeChanged { w = it.width }
            .pointerInput(lay, editable) {
                detectTapGestures { off ->
                    val l = lay ?: return@detectTapGestures
                    if (!editable) return@detectTapGestures
                    StaffRenderer.hit(l, sc, off.x, off.y)?.let { (p, m, n) -> state.selP = p; state.selM = m; state.selN = n }
                }
            },
    ) {
        val l = lay ?: return@Canvas
        drawIntoCanvas { StaffRenderer.draw(it.nativeCanvas, l, sc, ink, accent, sel) }
    }
}

@Composable
fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.widthIn(min = 80.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = onMinus, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("−") }
        Text(value, style = MaterialTheme.typography.titleMedium, modifier = Modifier.widthIn(min = 48.dp))
        OutlinedButton(onClick = onPlus, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("+") }
    }
}

fun Modifier.verticalScrollCompat(): Modifier = composed { this.verticalScroll(rememberScrollState()) }
