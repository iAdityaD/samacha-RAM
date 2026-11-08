package com.samacharam.app.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) { System("Match system"), Light("Light"), Dark("Dark") }

/** Material components inherit the tokens, so standard widgets already look like part of the system. */
private fun Palette.scheme() = (if (isDark) darkColorScheme() else lightColorScheme()).copy(
    primary = evergreen, onPrimary = onEvergreen, primaryContainer = evergreenSoft, onPrimaryContainer = evergreen,
    secondary = evergreen, onSecondary = onEvergreen, secondaryContainer = evergreenSoft, onSecondaryContainer = evergreen,
    tertiary = saffron, onTertiary = onEvergreen,
    background = paper, onBackground = ink, surface = paper, onSurface = ink, surfaceVariant = paperSunk, onSurfaceVariant = inkMuted,
    surfaceContainerLowest = paperRaised, surfaceContainerLow = paperRaised, surfaceContainer = paperRaised,
    surfaceContainerHigh = paperSunk, surfaceContainerHighest = paperSunk, surfaceBright = paperRaised, surfaceDim = paperSunk,
    inverseSurface = ink, inverseOnSurface = paper, inversePrimary = evergreenSoft,
    outline = inkFaint, outlineVariant = rule, error = danger, onError = paper, errorContainer = dangerSoft, onErrorContainer = danger,
    scrim = ink,
)

private val AppTypography = Typography(
    displaySmall = TypeRole.display, headlineMedium = TypeRole.display, headlineSmall = TypeRole.headline,
    titleLarge = TypeRole.headline.copy(fontSize = TypeRole.title.fontSize * 1.25f, lineHeight = TypeRole.title.lineHeight * 1.2f),
    titleMedium = TypeRole.title, titleSmall = TypeRole.title.copy(fontSize = TypeRole.meta.fontSize * 1.08f),
    bodyLarge = TypeRole.ui.copy(fontSize = TypeRole.ui.fontSize * 1.07f, lineHeight = TypeRole.ui.lineHeight * 1.1f),
    bodyMedium = TypeRole.ui, bodySmall = TypeRole.meta.copy(fontWeight = TypeRole.ui.fontWeight),
    labelLarge = TypeRole.title.copy(fontSize = TypeRole.ui.fontSize), labelMedium = TypeRole.meta, labelSmall = TypeRole.kicker,
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.control / 2), small = RoundedCornerShape(Radius.control), medium = RoundedCornerShape(Radius.control),
    large = RoundedCornerShape(Radius.control), extraLarge = RoundedCornerShape(Radius.control * 2),
)

@Composable
fun SamachaTheme(mode: ThemeMode, wallpaperColors: Boolean, content: @Composable () -> Unit) {
    val dark = when (mode) { ThemeMode.System -> isSystemInDarkTheme(); ThemeMode.Light -> false; ThemeMode.Dark -> true }
    val base = if (dark) DarkPalette else LightPalette
    // Wallpaper colors replace only the brand hue; paper and ink stay so the identity holds.
    val palette = if (wallpaperColors && Build.VERSION.SDK_INT >= 31) {
        val d = if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
        base.copy(evergreen = d.primary, evergreenSoft = d.primaryContainer, onEvergreen = d.onPrimary)
    } else base
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(colorScheme = palette.scheme(), typography = AppTypography, shapes = AppShapes, content = content)
    }
}
