package io.github.riggedlab.hark

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 日常记忆流语义扩展色（Apple HIG 温润极简风格）：
 * 组件只读取语义 token，深浅色自动联动，绝不在业务代码散写裸色值。
 */
@Immutable
data class DeckColors(
    val signalRed: Color,
    val signalRedContainer: Color,
    val onSignalRedContainer: Color,
    val accentBlue: Color,
    val accentBlueContainer: Color,
    val selfBubble: Color,
    val onSelfBubble: Color,
    val paperCanvas: Color,
    val glassSurface: Color,
    val ledGreen: Color,
    val ledAmber: Color,
    val place: Color,
    val placeContainer: Color,
    val tapeWell: Color,
    val tapeGroove: Color,
)

private val LightDeckColors = DeckColors(
    signalRed = Color(0xFFFF3B30),
    signalRedContainer = Color(0xFFFFE9E7),
    onSignalRedContainer = Color(0xFF69110B),
    accentBlue = Color(0xFF0071E3),
    accentBlueContainer = Color(0xFFE5F1FF),
    selfBubble = Color(0xFFE8F2FF),
    onSelfBubble = Color(0xFF132238),
    paperCanvas = Color(0xFFFBFBFD),
    glassSurface = Color(0xEBFFFFFF),
    ledGreen = Color(0xFF248A3D),
    ledAmber = Color(0xFFB26512),
    place = Color(0xFF1B7278),
    placeContainer = Color(0xFFE3F2F3),
    tapeWell = Color(0xFFF0F0F4),
    tapeGroove = Color(0xFFE2E2E8),
)

private val DarkDeckColors = DeckColors(
    signalRed = Color(0xFFFF5B52),
    signalRedContainer = Color(0xFF491916),
    onSignalRedContainer = Color(0xFFFFDAD6),
    accentBlue = Color(0xFF4DA3FF),
    accentBlueContainer = Color(0xFF17314F),
    selfBubble = Color(0xFF1B2B44),
    onSelfBubble = Color(0xFFEAF2FF),
    paperCanvas = Color(0xFF141519),
    glassSurface = Color(0xDE1E2026),
    ledGreen = Color(0xFF4EC968),
    ledAmber = Color(0xFFF0A444),
    place = Color(0xFF5AC4CC),
    placeContainer = Color(0xFF173639),
    tapeWell = Color(0xFF1F2127),
    tapeGroove = Color(0xFF2C2F37),
)

val LocalDeckColors = staticCompositionLocalOf { LightDeckColors }

object DeckTheme {
    val colors: DeckColors
        @Composable
        @ReadOnlyComposable
        get() = LocalDeckColors.current
}

private val Light = lightColorScheme(
    primary = Color(0xFF1D1D1F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8F2FF),
    onPrimaryContainer = Color(0xFF00346B),
    secondary = Color(0xFF6E6E73),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEBEBF0),
    onSecondaryContainer = Color(0xFF1D1D1F),
    tertiary = Color(0xFF0071E3),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE5F1FF),
    onTertiaryContainer = Color(0xFF002D5E),
    error = Color(0xFFD93829),
    errorContainer = Color(0xFFFFE9E7),
    onErrorContainer = Color(0xFF5C100A),
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF1D1D1F),
    surface = Color(0xFFF5F5F7),
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFEBEBF0),
    onSurfaceVariant = Color(0xFF6E6E73),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF9F9FB),
    surfaceContainer = Color(0xFFEFEFF4),
    surfaceContainerHigh = Color(0xFFE5E5EA),
    surfaceContainerHighest = Color(0xFFDADAE0),
    outline = Color(0xFF8E8E93),
    outlineVariant = Color(0xFFE4E4E9),
)

// 深色模式采用柔和深空石板灰，禁用纯黑 #000000
private val Dark = darkColorScheme(
    primary = Color(0xFFF2F2F6),
    onPrimary = Color(0xFF151619),
    primaryContainer = Color(0xFF1E324C),
    onPrimaryContainer = Color(0xFFDCEBFF),
    secondary = Color(0xFF9D9DA6),
    onSecondary = Color(0xFF1F2024),
    secondaryContainer = Color(0xFF26282F),
    onSecondaryContainer = Color(0xFFE6E6EC),
    tertiary = Color(0xFF5AA9FF),
    onTertiary = Color(0xFF002E5F),
    tertiaryContainer = Color(0xFF173353),
    onTertiaryContainer = Color(0xFFD9ECFF),
    error = Color(0xFFFF6961),
    errorContainer = Color(0xFF4A1916),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF101114),
    onBackground = Color(0xFFF2F2F6),
    surface = Color(0xFF101114),
    onSurface = Color(0xFFF2F2F6),
    surfaceVariant = Color(0xFF23252B),
    onSurfaceVariant = Color(0xFF9D9DA6),
    surfaceContainerLowest = Color(0xFF1B1C21),
    surfaceContainerLow = Color(0xFF17181D),
    surfaceContainer = Color(0xFF202228),
    surfaceContainerHigh = Color(0xFF282A32),
    surfaceContainerHighest = Color(0xFF32353E),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFF292B33),
)

/** 等宽数字特性（时间码与时长防跳） */
val TabularMono = TextStyle(
    fontFamily = FontFamily.SansSerif,
    fontFeatureSettings = "tnum",
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.1).sp),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Normal),
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
            // 圆润同心圆角阶梯：外层卡片 medium=20dp，内层胶囊/嵌块 small=14dp
            shapes = Shapes(
                extraSmall = RoundedCornerShape(10.dp),
                small = RoundedCornerShape(14.dp),
                medium = RoundedCornerShape(20.dp),
                large = RoundedCornerShape(26.dp),
            ),
            content = content,
        )
    }
}

/**
 * 柔和微缩放回弹：仅在 GPU Draw 阶段更新 graphicsLayer，不拦截滚动，满帧 120Hz。
 */
fun Modifier.pressScale(enabled: Boolean = true, pressedScale: Float = 0.97f): Modifier = composed {
    if (!enabled) return@composed this
    var pressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMedium),
        label = "pressScale",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressed = true
                waitForUpOrCancellation(pass = PointerEventPass.Initial)
                pressed = false
            }
        }
}
