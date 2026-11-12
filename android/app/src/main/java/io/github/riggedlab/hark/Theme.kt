package io.github.riggedlab.hark

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// 中性底色 + 一个有精神的蓝；青绿只给人物，暖橙只给地点
private val Light = lightColorScheme(
    primary = Color(0xFF3F5FE0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8ECFC),
    onPrimaryContainer = Color(0xFF1A2F85),
    secondary = Color(0xFF5F626B),
    secondaryContainer = Color(0xFFECECE8),
    onSecondaryContainer = Color(0xFF1C1D20),
    tertiary = Color(0xFF1F8A70),
    tertiaryContainer = Color(0xFFD9F1E9),
    onTertiaryContainer = Color(0xFF0B3A30),
    background = Color(0xFFF7F7F5),
    onBackground = Color(0xFF1C1D20),
    surface = Color(0xFFF7F7F5),
    onSurface = Color(0xFF1C1D20),
    surfaceVariant = Color(0xFFECECE8),
    onSurfaceVariant = Color(0xFF6B6D75),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF1F1EE),
    surfaceContainerHigh = Color(0xFFECECE8),
    surfaceContainerHighest = Color(0xFFE6E6E2),
    outline = Color(0xFF9A9CA3),
    outlineVariant = Color(0xFFE6E6E2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA8B8FF),
    onPrimary = Color(0xFF12246B),
    primaryContainer = Color(0xFF2A3A7D),
    onPrimaryContainer = Color(0xFFE0E6FF),
    secondary = Color(0xFFC4C5CB),
    secondaryContainer = Color(0xFF34353A),
    onSecondaryContainer = Color(0xFFE6E6E8),
    tertiary = Color(0xFF7FD6BE),
    tertiaryContainer = Color(0xFF14513F),
    onTertiaryContainer = Color(0xFFD9F1E9),
    background = Color(0xFF121315),
    onBackground = Color(0xFFE6E6E8),
    surface = Color(0xFF121315),
    onSurface = Color(0xFFE6E6E8),
    surfaceVariant = Color(0xFF2C2D31),
    onSurfaceVariant = Color(0xFFA5A7AE),
    surfaceContainerLowest = Color(0xFF1B1C1F),
    surfaceContainerLow = Color(0xFF1B1C1F),
    surfaceContainer = Color(0xFF1F2023),
    surfaceContainerHigh = Color(0xFF292A2E),
    surfaceContainerHighest = Color(0xFF333438),
    outline = Color(0xFF85878F),
    outlineVariant = Color(0xFF303136),
)

val PlaceColor = Color(0xFFB3712F)

@Composable
fun DitingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        shapes = Shapes(
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(14.dp),
            large = RoundedCornerShape(22.dp),
        ),
        content = content,
    )
}
