package io.openflux.app.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The colours of the new interface: near-black, one warm accent, separate state colours (spec 3a). */
@Immutable
data class Colors(
    val background: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val border: Color,
    val text: Color,
    val textSecondary: Color,
    val textMuted: Color,
    val accent: Color,
    val onAccent: Color,
    val success: Color,
    val warning: Color,
    val danger: Color,
)

val DarkColors = Colors(
    background = Color(0xFF0E0E11),
    surface = Color(0xFF1A1B1F),
    surfaceHigh = Color(0xFF26272C),
    border = Color(0xFF2E2F35),
    text = Color(0xFFECECEE),
    textSecondary = Color(0xFFA9ACB2),
    textMuted = Color(0xFF6E7178),
    accent = Color(0xFFFBB26A),
    onAccent = Color(0xFF1A1205),
    success = Color(0xFF5CC98A),
    warning = Color(0xFFF2C14E),
    danger = Color(0xFFF2675C),
)

val LightColors = Colors(
    background = Color(0xFFF6F6F7),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEDEDF0),
    border = Color(0xFFDDDDE2),
    text = Color(0xFF16171A),
    textSecondary = Color(0xFF55585E),
    textMuted = Color(0xFF8A8D93),
    accent = Color(0xFFA0561A),
    onAccent = Color(0xFFFFFFFF),
    success = Color(0xFF1E8A4C),
    warning = Color(0xFF9A6B00),
    danger = Color(0xFFC6362B),
)

@Immutable
data class Type(
    val screenTitle: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val caption: TextStyle,
    val mono: TextStyle,
)

private fun type(touch: Boolean): Type {
    val bump = if (touch) 1 else 0
    return Type(
        screenTitle = TextStyle(fontSize = (22 + bump).sp, lineHeight = (28 + bump).sp, fontWeight = FontWeight.SemiBold),
        title = TextStyle(fontSize = (16 + bump).sp, lineHeight = (22 + bump).sp, fontWeight = FontWeight.Medium),
        body = TextStyle(fontSize = (14 + bump).sp, lineHeight = (20 + bump).sp),
        bodySmall = TextStyle(fontSize = (13 + bump).sp, lineHeight = (18 + bump).sp),
        caption = TextStyle(fontSize = (12 + bump).sp, lineHeight = (16 + bump).sp, fontWeight = FontWeight.Medium),
        mono = TextStyle(fontSize = (13 + bump).sp, lineHeight = (18 + bump).sp, fontFamily = FontFamily.Monospace),
    )
}

@Immutable
data class Spacing(
    val xs: Dp = 4.dp, val s: Dp = 8.dp, val m: Dp = 12.dp, val l: Dp = 16.dp,
    val xl: Dp = 24.dp, val xxl: Dp = 32.dp, val page: Dp = 16.dp,
)

/** Corner radii. */
@Immutable
data class Shapes(val small: Dp = 8.dp, val medium: Dp = 12.dp, val large: Dp = 20.dp)

val MinTouch: Dp = 48.dp

private val LocalColors = staticCompositionLocalOf { DarkColors }
private val LocalType = staticCompositionLocalOf { type(false) }
private val LocalSpacing = staticCompositionLocalOf { Spacing() }
private val LocalShapes = staticCompositionLocalOf { Shapes() }
private val LocalTouch = staticCompositionLocalOf { false }

object AppTheme {
    val colors: Colors @Composable get() = LocalColors.current
    val type: Type @Composable get() = LocalType.current
    val spacing: Spacing @Composable get() = LocalSpacing.current
    val shapes: Shapes @Composable get() = LocalShapes.current
    val touch: Boolean @Composable get() = LocalTouch.current
}

/** A row's minimum height: a finger needs more than a pointer. */
@Composable
fun rowMinHeight(): Dp = if (AppTheme.touch) 56.dp else 44.dp

@Composable
fun AppTheme(dark: Boolean, touch: Boolean, content: @Composable () -> Unit) {
    val c = if (dark) DarkColors else LightColors
    val scheme = (if (dark) darkColorScheme() else lightColorScheme()).copy(
        primary = c.accent, onPrimary = c.onAccent,
        background = c.background, onBackground = c.text,
        surface = c.surface, onSurface = c.text,
        surfaceVariant = c.surfaceHigh, onSurfaceVariant = c.textSecondary,
        surfaceContainer = c.surface, surfaceContainerHigh = c.surfaceHigh,
        surfaceContainerLow = c.surface, surfaceContainerHighest = c.surfaceHigh,
        outline = c.border, outlineVariant = c.border, error = c.danger,
    )
    CompositionLocalProvider(
        LocalColors provides c,
        LocalType provides type(touch),
        LocalSpacing provides Spacing(),
        LocalShapes provides Shapes(),
        LocalTouch provides touch,
    ) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
