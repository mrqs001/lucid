package lucid.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import lucid.core.model.SleepStage

/**
 * Night palette: low-luminance surfaces so the phone can be glanced at in a dark bedroom.
 * Stage colours were checked with the dataviz palette validator against the chart surface
 * (adjacent hypnogram lanes pass the colour-blind and normal-vision separation checks).
 */
object LucidColors {
    val Night = Color(0xFF0B0D14)
    val Surface = Color(0xFF12141C)
    val SurfaceHigh = Color(0xFF1A1D28)
    val SurfaceHighest = Color(0xFF232737)
    val Ink = Color(0xFFECEAF4)
    val InkMuted = Color(0xFFA9A8BC)
    val InkFaint = Color(0xFF6E6D82)
    val Grid = Color(0xFF2A2E3E)
    val Moon = Color(0xFFC9C3FF)
    val Dawn = Color(0xFFF2B880)

    val Wake = Color(0xFFD95926)
    val Rem = Color(0xFF9085E9)
    val Light = Color(0xFF199E70)
    val Deep = Color(0xFF3987E5)

    val Good = Color(0xFF0CA30C)
    val Warning = Color(0xFFFAB219)
    val Critical = Color(0xFFD03B3B)

    fun stage(s: SleepStage) = when (s) {
        SleepStage.WAKE -> Wake
        SleepStage.REM -> Rem
        SleepStage.LIGHT -> Light
        SleepStage.DEEP -> Deep
    }

    fun stage(ordinal: Int) = stage(SleepStage.entries[ordinal])
}

private val scheme = darkColorScheme(
    primary = LucidColors.Moon,
    onPrimary = Color(0xFF1B1640),
    primaryContainer = Color(0xFF2E2860),
    onPrimaryContainer = Color(0xFFE5E0FF),
    secondary = Color(0xFF7FD1B9),
    onSecondary = Color(0xFF003828),
    secondaryContainer = Color(0xFF1E3B33),
    onSecondaryContainer = Color(0xFFBDEFE0),
    tertiary = LucidColors.Dawn,
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = Color(0xFF4A3420),
    onTertiaryContainer = Color(0xFFFFDDB8),
    background = LucidColors.Night,
    onBackground = LucidColors.Ink,
    surface = LucidColors.Night,
    onSurface = LucidColors.Ink,
    surfaceVariant = LucidColors.SurfaceHigh,
    onSurfaceVariant = LucidColors.InkMuted,
    surfaceContainerLowest = LucidColors.Night,
    surfaceContainerLow = LucidColors.Surface,
    surfaceContainer = LucidColors.Surface,
    surfaceContainerHigh = LucidColors.SurfaceHigh,
    surfaceContainerHighest = LucidColors.SurfaceHighest,
    outline = Color(0xFF3A3E52),
    outlineVariant = LucidColors.Grid,
    error = Color(0xFFFF8A80),
)

private val base = Typography()
private val typography = base.copy(
    displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.Normal),
    headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif),
    headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif),
    titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif),
    labelSmall = base.labelSmall.copy(letterSpacing = 0.8.sp),
)

/** Tabular figures so live numbers don't jitter. */
val NumberStyle = TextStyle(fontFeatureSettings = "tnum")

@Composable
fun LucidTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typography, content = content)
}
