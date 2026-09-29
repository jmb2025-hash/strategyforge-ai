package app.strategyforge.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * StrategyForge design system (D-038): a dark "trading terminal" look with a matching light theme,
 * following the phone's setting. Gains and losses always carry an arrow and a sign as well as colour
 * (NFR-009); every text/background pair here meets WCAG AA.
 */
@Immutable
data class SfColors(
    val gain: Color,
    val loss: Color,
    val neutral: Color,
    val grid: Color,
    val axisText: Color,
    val cardBorder: Color,
    val heroStart: Color,
    val heroEnd: Color,
    /** Distinct series colours for multi-line and comparison charts (colour-blind friendly order). */
    val series: List<Color>,
)

private val DarkSf =
    SfColors(
        gain = Color(0xFF2EDB8F),
        loss = Color(0xFFFF6B7D),
        neutral = Color(0xFF9AA7B8),
        grid = Color(0xFF223041),
        axisText = Color(0xFF8595A8),
        cardBorder = Color(0xFF1F2A38),
        heroStart = Color(0xFF14323A),
        heroEnd = Color(0xFF121A26),
        series = listOf(Color(0xFF4DA3FF), Color(0xFFFFB547), Color(0xFF2EDB8F), Color(0xFFC792EA), Color(0xFFFF6B7D), Color(0xFF5FE1E6)),
    )

private val LightSf =
    SfColors(
        gain = Color(0xFF0B7A4B),
        loss = Color(0xFFC62839),
        neutral = Color(0xFF5B6878),
        grid = Color(0xFFE3E8EF),
        axisText = Color(0xFF5B6878),
        cardBorder = Color(0xFFE1E6ED),
        heroStart = Color(0xFFE3F5EC),
        heroEnd = Color(0xFFF3F6FA),
        series = listOf(Color(0xFF1F6FD1), Color(0xFFC77700), Color(0xFF0B7A4B), Color(0xFF8A4FBF), Color(0xFFC62839), Color(0xFF00838F)),
    )

val LocalSfColors = staticCompositionLocalOf { DarkSf }

/** Theme tokens beyond Material's colour scheme. */
object Sf {
    val colors: SfColors
        @Composable get() = LocalSfColors.current
}

private val DarkScheme =
    darkColorScheme(
        primary = Color(0xFF2EDB8F),
        onPrimary = Color(0xFF00241A),
        primaryContainer = Color(0xFF0E3B2C),
        onPrimaryContainer = Color(0xFFB4F5D6),
        secondary = Color(0xFF4DA3FF),
        onSecondary = Color(0xFF00213F),
        secondaryContainer = Color(0xFF16304D),
        onSecondaryContainer = Color(0xFFD2E6FF),
        tertiary = Color(0xFFFFB547),
        tertiaryContainer = Color(0xFF3D2C0B),
        onTertiaryContainer = Color(0xFFFFE0B0),
        error = Color(0xFFFF6B7D),
        errorContainer = Color(0xFF4A1620),
        onErrorContainer = Color(0xFFFFD9DD),
        background = Color(0xFF0A0E14),
        onBackground = Color(0xFFE6EDF5),
        surface = Color(0xFF0A0E14),
        onSurface = Color(0xFFE6EDF5),
        surfaceVariant = Color(0xFF1A2330),
        onSurfaceVariant = Color(0xFFA9B6C6),
        surfaceContainerLowest = Color(0xFF070A0F),
        surfaceContainerLow = Color(0xFF0F151D),
        surfaceContainer = Color(0xFF121A24),
        surfaceContainerHigh = Color(0xFF18212D),
        surfaceContainerHighest = Color(0xFF1E2835),
        outline = Color(0xFF3A4757),
        outlineVariant = Color(0xFF243040),
    )

private val LightScheme =
    lightColorScheme(
        primary = Color(0xFF0B7A4B),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCFF3E1),
        onPrimaryContainer = Color(0xFF00301D),
        secondary = Color(0xFF1F6FD1),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFDCE9FB),
        onSecondaryContainer = Color(0xFF0B2A52),
        tertiary = Color(0xFF9A5B00),
        tertiaryContainer = Color(0xFFFFE6C2),
        onTertiaryContainer = Color(0xFF3A2200),
        error = Color(0xFFC62839),
        errorContainer = Color(0xFFFFDADF),
        onErrorContainer = Color(0xFF410009),
        background = Color(0xFFF4F6FA),
        onBackground = Color(0xFF0F1722),
        surface = Color(0xFFF4F6FA),
        onSurface = Color(0xFF0F1722),
        surfaceVariant = Color(0xFFE8EDF3),
        onSurfaceVariant = Color(0xFF465364),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF9FAFC),
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color(0xFFF1F4F8),
        surfaceContainerHighest = Color(0xFFE8EDF3),
        outline = Color(0xFFB5C0CD),
        outlineVariant = Color(0xFFDCE2EA),
    )

/** Tabular figures so prices and amounts line up in columns. */
private fun TextStyle.numeric() = copy(fontFeatureSettings = "tnum")

private val base = Typography()

private val SfTypography =
    Typography(
        displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp).numeric(),
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp).numeric(),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp).numeric(),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold).numeric(),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold).numeric(),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold).numeric(),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold).numeric(),
        bodyLarge = base.bodyLarge.numeric(),
        bodyMedium = base.bodyMedium.numeric(),
        bodySmall = base.bodySmall.numeric(),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold).numeric(),
        labelMedium = base.labelMedium.copy(fontWeight = FontWeight.Medium).numeric(),
        labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp).numeric(),
    )

private val SfShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

@Composable
fun SfTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(LocalSfColors provides if (dark) DarkSf else LightSf) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, typography = SfTypography, shapes = SfShapes) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}

// ------------------------------------------------------------------ icons not in material-icons-core

private fun icon(
    name: String,
    path: String,
): ImageVector =
    ImageVector
        .Builder(name, 24.dp, 24.dp, 24f, 24f)
        .addPath(addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

object SfIcons {
    val ShowChart: ImageVector by lazy { icon("ShowChart", "M3.5,18.49l6,-6.01 4,4L22,6.92l-1.41,-1.41 -7.09,7.97 -4,-4L2,16.99z") }
    val Candles: ImageVector by lazy { icon("Candles", "M9,4H7v2H5v12h2v2h2v-2h2V6H9V4zM19,8h-2V4h-2v4h-2v7h2v5h2v-5h2V8z") }
    val Wallet: ImageVector by lazy {
        icon(
            "Wallet",
            "M21,18v1c0,1.1 -0.9,2 -2,2L5,21c-1.11,0 -2,-0.9 -2,-2L3,5c0,-1.1 0.89,-2 2,-2h14c1.1,0 2,0.9 2,2v1h-9c-1.11,0 -2,0.9 -2,2v8c0,1.1 0.89,2 2,2h9z" +
                "M12,16h10L22,8L12,8v8zM16,13.5c-0.83,0 -1.5,-0.67 -1.5,-1.5s0.67,-1.5 1.5,-1.5 1.5,0.67 1.5,1.5 -0.67,1.5 -1.5,1.5z",
        )
    }
    val Bolt: ImageVector by lazy { icon("Bolt", "M11,21h-1l1,-7H7.5c-0.88,0 -0.33,-0.75 -0.31,-0.78C8.48,10.94 10.42,7.54 13.01,3h1l-1,7h3.51c0.4,0 0.62,0.19 0.4,0.66C12.97,17.55 11,21 11,21z") }
}
