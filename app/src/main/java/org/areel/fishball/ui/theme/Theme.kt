package org.areel.fishball.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Design tokens lifted from areel.org's index.html, not approximated.
 *
 *   --concrete #c9c9c5 · --concrete-2 #d3d3cf · --ink #101010 · --magenta #ec0a6e
 *   --rule 1px solid ink-20 (the CAD hairline) · --glass rgba(255,255,255,.42)
 */
object Areel {
    val Concrete = Color(0xFFC9C9C5)
    val Concrete2 = Color(0xFFD3D3CF)
    val Paper = Color(0xFFF2F2EE)
    val Ink = Color(0xFF101010)
    val Ink60 = Color(0x99101010)
    val Ink40 = Color(0x66101010)
    val Ink20 = Color(0x33101010)
    val Ink10 = Color(0x1A101010)
    val Ink06 = Color(0x0F101010)
    val Magenta = Color(0xFFEC0A6E)
    val MagentaLo = Color(0x24EC0A6E)

    /**
     * The confidence ramp: ink at full confidence, magenta at the bottom, linearly mixed at
     * 33% and 66%. Label and meter cells share it, so count and hue say the same thing twice —
     * the reading survives squinting, greyscale, and colour-blindness.
     */
    val Conf4 = Color(0xFF101010)
    val Conf3 = Color(0xFF560E2F)
    val Conf2 = Color(0xFFAC0C4E)
    val Conf1 = Color(0xFFEC0A6E)

    /**
     * Glass. areel.org builds its "clear plastic panel" from layered translucent white plus a
     * specular streak — there is no backdrop blur anywhere on the site, which is why this is
     * affordable on a phone. See Modifier.glassSurface().
     */
    val GlassBorder = Color(0x99FFFFFF)
    val GlassLip = Color(0xB3FFFFFF)
    val GlassHi = Color(0x42FFFFFF)
    val GlassMid = Color(0x0FFFFFFF)
    val GlassLo = Color(0x2EFFFFFF)
    val GlassStreak = Color(0x38FFFFFF)
    val GlassShade = Color(0x66101010)
}

/*
 * TODO(fonts): areel.org uses Archivo (display) and JetBrains Mono. Both are on Google Fonts,
 * so the real fix is androidx.compose.ui.text.googlefonts with a downloadable provider, or
 * bundling the TTFs in res/font. System families below keep the dummy renderable meanwhile —
 * the geometry and colour are faithful, the letterforms are not.
 */
private val Display = FontFamily.SansSerif
private val Mono = FontFamily.Monospace

val AreelTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Black,
        fontSize = 28.sp, letterSpacing = (-0.5).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.SemiBold, fontSize = 16.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 25.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Display, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 21.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, letterSpacing = 0.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = Mono, fontWeight = FontWeight.Normal, fontSize = 12.sp,
    ),
)

private val LightScheme = lightColorScheme(
    primary = Areel.Magenta,
    onPrimary = Color.White,
    primaryContainer = Areel.MagentaLo,
    onPrimaryContainer = Areel.Ink,
    background = Areel.Concrete,
    onBackground = Areel.Ink,
    surface = Areel.Paper,
    onSurface = Areel.Ink,
    surfaceVariant = Areel.Concrete2,
    onSurfaceVariant = Areel.Ink60,
    outline = Areel.Ink20,
)

/**
 * The album-cover look is a light one. Rather than invent a dark palette the site doesn't
 * have, dark mode inverts only the ground and keeps ink/magenta relationships intact.
 */
private val DarkScheme = darkColorScheme(
    primary = Areel.Magenta,
    onPrimary = Color.White,
    primaryContainer = Color(0x33EC0A6E),
    onPrimaryContainer = Color(0xFFF2F2EE),
    background = Areel.Ink,
    onBackground = Areel.Concrete,
    surface = Color(0xFF1A1A1A),
    onSurface = Areel.Concrete,
    surfaceVariant = Color(0xFF232323),
    onSurfaceVariant = Color(0xFFA0A09C),
    outline = Color(0x33F2F2EE),
)

@Composable
fun FishBallTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = AreelTypography,
        content = content,
    )
}
