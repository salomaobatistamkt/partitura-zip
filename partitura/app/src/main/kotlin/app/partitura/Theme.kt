package app.partitura

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Papel da partitura: sempre claro, mesmo no tema escuro (como uma folha de verdade). */
val Paper = Color(0xFFFBF8F0)
val Ink = Color(0xFF1E1E24)
val Amber = Color(0xFFE07A00)
val Amber2 = Color(0xFFE9B35A)
val Blue = Color(0xFF2F6FDE)
val Violet = Color(0xFFA08CF7)
val Mint = Color(0xFF4DBB91)
val Sky = Color(0xFF6FB1F2)

private val Dark = darkColorScheme(
    primary = Color(0xFFE9B35A), onPrimary = Color(0xFF1B1405),
    secondary = Color(0xFF8FB8DE), onSecondary = Color(0xFF07131F),
    background = Color(0xFF0E1013), onBackground = Color(0xFFECE9E2),
    surface = Color(0xFF171A20), onSurface = Color(0xFFECE9E2),
    surfaceVariant = Color(0xFF20242C), onSurfaceVariant = Color(0xFF9EA3AF),
    outline = Color(0xFF2E3440), primaryContainer = Color(0xFF3A2D12), onPrimaryContainer = Color(0xFFF6DCA8),
    secondaryContainer = Color(0xFF1F2F40), onSecondaryContainer = Color(0xFFCFE3F7),
    error = Color(0xFFFF8A80),
)
private val Light = lightColorScheme(
    primary = Color(0xFF8A5A00), onPrimary = Color.White,
    secondary = Color(0xFF2F5F8A), onSecondary = Color.White,
    background = Color(0xFFF4F1EA), onBackground = Color(0xFF1B1B1B),
    surface = Color.White, onSurface = Color(0xFF1B1B1B),
    surfaceVariant = Color(0xFFEAE5D9), onSurfaceVariant = Color(0xFF5A5A5A),
    outline = Color(0xFFD8D1BF), primaryContainer = Color(0xFFFBE8BF), onPrimaryContainer = Color(0xFF3B2600),
    secondaryContainer = Color(0xFFD9E7F5), onSecondaryContainer = Color(0xFF10283F),
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
