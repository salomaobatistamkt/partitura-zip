package app.partitura

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Dark = darkColorScheme(
    primary = Color(0xFFE8B866), onPrimary = Color(0xFF1B1405),
    secondary = Color(0xFF8FB8DE), onSecondary = Color(0xFF07131F),
    background = Color(0xFF0F1115), onBackground = Color(0xFFE9E6DF),
    surface = Color(0xFF171A21), onSurface = Color(0xFFE9E6DF),
    surfaceVariant = Color(0xFF222733), onSurfaceVariant = Color(0xFFB4B8C3),
    outline = Color(0xFF3A4150), primaryContainer = Color(0xFF3A2D12), onPrimaryContainer = Color(0xFFF6DCA8),
    error = Color(0xFFFF8A80),
)
private val Light = lightColorScheme(
    primary = Color(0xFF8A5A00), onPrimary = Color.White,
    secondary = Color(0xFF2F5F8A), onSecondary = Color.White,
    background = Color(0xFFFAF7F0), onBackground = Color(0xFF1B1B1B),
    surface = Color.White, onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFEFE9DC), onSurfaceVariant = Color(0xFF4A4A4A),
    outline = Color(0xFFC9C1AE), primaryContainer = Color(0xFFFBE8BF), onPrimaryContainer = Color(0xFF3B2600),
    error = Color(0xFFB3261E),
)

@Composable
fun PartituraTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        shapes = Shapes(small = RoundedCornerShape(10.dp), medium = RoundedCornerShape(14.dp), large = RoundedCornerShape(20.dp)),
        content = content,
    )
}
