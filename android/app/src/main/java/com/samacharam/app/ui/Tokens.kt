package com.samacharam.app.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens for "Ink and paper" (see docs/design-system.md). Components read these; they never
 * hard-code colors, sizes, or durations.
 */
@Immutable
data class Palette(
    val paper: Color, val paperRaised: Color, val paperSunk: Color,
    val ink: Color, val inkMuted: Color, val inkFaint: Color, val rule: Color,
    val evergreen: Color, val evergreenSoft: Color, val onEvergreen: Color,
    val saffron: Color, val danger: Color, val dangerSoft: Color, val isDark: Boolean,
)

val LightPalette = Palette(
    paper = Color(0xFFF7F4EE), paperRaised = Color(0xFFFFFDF9), paperSunk = Color(0xFFEFEBE3),
    ink = Color(0xFF1C1B18), inkMuted = Color(0xFF5E5A52), inkFaint = Color(0xFF8D877C), rule = Color(0xFFE2DDD3),
    evergreen = Color(0xFF1E5B45), evergreenSoft = Color(0xFFDCEBE2), onEvergreen = Color(0xFFFFFFFF),
    saffron = Color(0xFFC97B12), danger = Color(0xFFA8321F), dangerSoft = Color(0xFFF6DDD6), isDark = false,
)

val DarkPalette = Palette(
    paper = Color(0xFF12110F), paperRaised = Color(0xFF1B1A17), paperSunk = Color(0xFF22201C),
    ink = Color(0xFFECE7DE), inkMuted = Color(0xFFA8A296), inkFaint = Color(0xFF7A7468), rule = Color(0xFF2E2B26),
    evergreen = Color(0xFF8FCBAE), evergreenSoft = Color(0xFF1F3A2F), onEvergreen = Color(0xFF0E2A20),
    saffron = Color(0xFFE8A94A), danger = Color(0xFFF0937F), dangerSoft = Color(0xFF3A1E18), isDark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** 4 dp spacing scale; the screen gutter is 20 dp. */
object Space {
    val xs = 4.dp; val s = 8.dp; val m = 12.dp; val l = 16.dp; val xl = 20.dp; val xxl = 24.dp; val xxxl = 32.dp; val huge = 48.dp
    val gutter = 20.dp
    val touch = 48.dp
}

object Radius {
    /** Sheets, dialogs, inputs, grouped settings. Content rows are square: the page is the container. */
    val control = 12.dp
}

object Motion {
    const val QUICK = 120
    const val STANDARD = 220
    const val EXIT = 160
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)
}

/** Type roles. Serif carries reading; sans carries interface. All sizes in sp so they follow system font scale. */
object TypeRole {
    private val serif = FontFamily.Serif
    private val sans = FontFamily.SansSerif
    val display = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.3).sp)
    val headline = TextStyle(fontFamily = serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp)
    val story = TextStyle(fontFamily = serif, fontWeight = FontWeight.Medium, fontSize = 18.sp, lineHeight = 24.sp)
    val body = TextStyle(fontFamily = serif, fontWeight = FontWeight.Normal, fontSize = 17.sp, lineHeight = 27.sp)
    val title = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    val ui = TextStyle(fontFamily = sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 21.sp)
    val meta = TextStyle(fontFamily = sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
    val kicker = TextStyle(fontFamily = sans, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.8.sp)
}
