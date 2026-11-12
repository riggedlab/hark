package io.github.riggedlab.hark

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 精密磁带录音机语义扩展色（Braun / TP-7 克制机械风）：
 * 组件一律读取语义 token，绝不在业务代码散写硬编码色值。
 */
@Immutable
data class DeckColors(
    val signalRed: Color,
    val signalRedContainer: Color,
    val onSignalRedContainer: Color,
    val ledGreen: Color,
    val ledAmber: Color,
    val place: Color,
    val placeContainer: Color,
    val tapeWell: Color,
    val tapeGroove: Color,
)

private val LightDeckColors = DeckColors(
    signalRed = Color(0xFFD64527),
    signalRedContainer = Color(0xFFFBE7E2),
    onSignalRedContainer = Color(0xFF5E1608),
    ledGreen = Color(0xFF248257),
    ledAmber = Color(0xFFC47A1B),
    place = Color(0xFF9A6122),
    placeContainer = Color(0xFFF3E6D6),
    tapeWell = Color(0xFFECE9E1),
    tapeGroove = Color(0xFFDCD8CE),
)

private val DarkDeckColors = DeckColors(
    signalRed = Color(0xFFFF6647),
    signalRedContainer = Color(0xFF4A1D14),
    onSignalRedContainer = Color(0xFFFFDAD2),
    ledGreen = Color(0xFF4CC38A),
    ledAmber = Color(0xFFF0A944),
    place = Color(0xFFE3AA6B),
    placeContainer = Color(0xFF3B2B1A),
    tapeWell = Color(0xFF212429),
    tapeGroove = Color(0xFF30343C),
)

val LocalDeckColors = staticCompositionLocalOf { LightDeckColors }

object DeckTheme {
    val colors: DeckColors
        @Composable
        @ReadOnlyComposable
        get() = LocalDeckColors.current
}

// 阳极氧化暖铝灰底 + 石墨黑主控 + 国际信号朱砂红走带针；青绿给声纹人物，黄铜赭石给地点
private val Light = lightColorScheme(
    primary = Color(0xFF202226),
    onPrimary = Color(0xFFFAF9F6),
    primaryContainer = Color(0xFFE5E2DA),
    onPrimaryContainer = Color(0xFF17181B),
    secondary = Color(0xFF5A5D64),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE8E5DD),
    onSecondaryContainer = Color(0xFF1C1E22),
    tertiary = Color(0xFF1E6E62),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD7ECE8),
    onTertiaryContainer = Color(0xFF0A3730),
    error = Color(0xFFC93B2B),
    errorContainer = Color(0xFFFCE6E3),
    onErrorContainer = Color(0xFF5A120A),
    background = Color(0xFFF4F2ED),
    onBackground = Color(0xFF1A1B1E),
    surface = Color(0xFFF4F2ED),
    onSurface = Color(0xFF1A1B1E),
    surfaceVariant = Color(0xFFEAE7DF),
    onSurfaceVariant = Color(0xFF63666D),
    surfaceContainerLowest = Color(0xFFFCFBF8),
    surfaceContainerLow = Color(0xFFF7F5F0),
    surfaceContainer = Color(0xFFECE9E1),
    surfaceContainerHigh = Color(0xFFE3DFD6),
    surfaceContainerHighest = Color(0xFFDAD5CA),
    outline = Color(0xFF8C8F96),
    outlineVariant = Color(0xFFDEDAD0),
)

// 深色模式采用哑光阳极石墨灰，禁用刺眼纯黑 #000000
private val Dark = darkColorScheme(
    primary = Color(0xFFECEAE4),
    onPrimary = Color(0xFF16181B),
    primaryContainer = Color(0xFF2D3138),
    onPrimaryContainer = Color(0xFFF2F0EA),
    secondary = Color(0xFFBFC2C9),
    onSecondary = Color(0xFF25272C),
    secondaryContainer = Color(0xFF2A2D33),
    onSecondaryContainer = Color(0xFFE4E5E9),
    tertiary = Color(0xFF74C9BC),
    onTertiary = Color(0xFF083730),
    tertiaryContainer = Color(0xFF15473F),
    onTertiaryContainer = Color(0xFFD6F2ED),
    error = Color(0xFFFF6B5B),
    errorContainer = Color(0xFF4A1914),
    onErrorContainer = Color(0xFFFFDAD5),
    background = Color(0xFF131416),
    onBackground = Color(0xFFE8E7E3),
    surface = Color(0xFF131416),
    onSurface = Color(0xFFE8E7E3),
    surfaceVariant = Color(0xFF26292E),
    onSurfaceVariant = Color(0xFF9EA1A8),
    surfaceContainerLowest = Color(0xFF1A1C1F),
    surfaceContainerLow = Color(0xFF1D2024),
    surfaceContainer = Color(0xFF212429),
    surfaceContainerHigh = Color(0xFF292D33),
    surfaceContainerHighest = Color(0xFF32373E),
    outline = Color(0xFF777B84),
    outlineVariant = Color(0xFF2B2F36),
)

/** 等宽走带计数与时间码字体特性（防止跳秒引起整行抖动） */
val TabularMono = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontFeatureSettings = "tnum",
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.5.sp, lineHeight = 25.sp, fontWeight = FontWeight.Normal),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    bodySmall = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp, fontWeight = FontWeight.Normal),
    labelLarge = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = "tnum"),
)

@Composable
fun DitingTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val deckColors = if (darkTheme) DarkDeckColors else LightDeckColors
    androidx.compose.runtime.CompositionLocalProvider(LocalDeckColors provides deckColors) {
        MaterialTheme(
            colorScheme = if (darkTheme) Dark else Light,
            typography = AppTypography,
            // 同心圆角阶梯：外层卡片 medium=14dp，内嵌磁带视窗 small=8dp（r = R - p）
            shapes = Shapes(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(8.dp),
                medium = RoundedCornerShape(14.dp),
                large = RoundedCornerShape(20.dp),
            ),
            content = content,
        )
    }
}
