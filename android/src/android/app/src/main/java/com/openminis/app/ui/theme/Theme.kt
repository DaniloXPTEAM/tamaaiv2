package com.openminis.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight // nanoMuse
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// nanoMuse: TAMA's warm Material palette. The historic Teal names stay so upstream
// call sites and stored appearance preferences remain unchanged. scripts/tama_delta.py
// reapplies these named values after scripts/rebrand.py.
private val TealPrimary = Color(0xFF9B4508)
private val TealOnPrimary = Color(0xFFFFFFFF)
private val TealPrimaryContainer = Color(0xFFFFDBA1)
private val TealOnPrimaryContainer = Color(0xFF3A1900)
private val TealSecondary = Color(0xFF6E563B)
private val TealOnSecondary = Color(0xFFFFFFFF)
private val TealSecondaryContainer = Color(0xFFF4E1BF)
private val TealOnSecondaryContainer = Color(0xFF281908)
private val TealTertiary = Color(0xFF506641)
private val TealOnTertiary = Color(0xFFFFFFFF)
private val TealTertiaryContainer = Color(0xFFD2E8B7)
private val TealOnTertiaryContainer = Color(0xFF0F2107)
private val TealBackground = Color(0xFFFFF8E9)
private val TealOnBackground = Color(0xFF281B12)
private val TealSurface = Color(0xFFFFF8E9)
private val TealOnSurface = Color(0xFF281B12)
private val TealSurfaceVariant = Color(0xFFF9EBD4)
private val TealOnSurfaceVariant = Color(0xFF514335)
private val TealOutline = Color(0xFF827363)

private val TealDarkPrimary = Color(0xFFFFCA74)
private val TealDarkOnPrimary = Color(0xFF4B2500)
private val TealDarkPrimaryContainer = Color(0xFF713500)
private val TealDarkOnPrimaryContainer = Color(0xFFFFDBA1)
private val TealDarkSecondary = Color(0xFFD9C2A2)
private val TealDarkOnSecondary = Color(0xFF3D2E1C)
private val TealDarkSecondaryContainer = Color(0xFF55432F)
private val TealDarkOnSecondaryContainer = Color(0xFFF4E1BF)
// nanoMuse: the dark tertiary accent matches the companion's gentle green.
private val TealDarkTertiary = Color(0xFFB7D2A1)
private val TealDarkOnTertiary = Color(0xFF24361A)
private val TealDarkTertiaryContainer = Color(0xFF3B4D30)
private val TealDarkOnTertiaryContainer = Color(0xFFD2E8B7)
private val TealDarkBackground = Color(0xFF19130F)
private val TealDarkOnBackground = Color(0xFFF3E4D6)
private val TealDarkSurface = Color(0xFF19130F)
private val TealDarkOnSurface = Color(0xFFF3E4D6)
private val TealDarkSurfaceVariant = Color(0xFF514538)
private val TealDarkOnSurfaceVariant = Color(0xFFD7C5B2)
private val TealDarkOutline = Color(0xFFA59583)

// nanoMuse: layered cream / cocoa surfaces keep cards distinct in both themes.
private val NeutralGroupedBg = Color(0xFFFFF8E9)
private val NeutralGroupedCard = Color(0xFFFFFCF5)
private val NeutralGroupedCardElevated = Color(0xFFFFF1DC)
private val NeutralOutline = Color(0xFFE6D5B8)

private val NeutralDarkGroupedBg = Color(0xFF19130F)
private val NeutralDarkGroupedCard = Color(0xFF30251D)
private val NeutralDarkGroupedCardElevated = Color(0xFF43352A)
private val NeutralDarkOutline = Color(0xFF5C4C3C)

private val LightColorScheme = lightColorScheme(
    primary = TealPrimary,
    onPrimary = TealOnPrimary,
    primaryContainer = TealPrimaryContainer,
    onPrimaryContainer = TealOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = TealTertiary,
    onTertiary = TealOnTertiary,
    tertiaryContainer = TealTertiaryContainer,
    onTertiaryContainer = TealOnTertiaryContainer,
    background = NeutralGroupedBg,
    onBackground = TealOnBackground,
    surface = NeutralGroupedBg,
    onSurface = TealOnSurface,
    surfaceVariant = NeutralGroupedCard,
    onSurfaceVariant = TealOnSurfaceVariant,
    surfaceContainerLowest = NeutralGroupedBg,
    surfaceContainerLow = NeutralGroupedCard,
    surfaceContainer = NeutralGroupedCard,
    surfaceContainerHigh = NeutralGroupedCardElevated,
    surfaceContainerHighest = NeutralGroupedCardElevated,
    outline = NeutralOutline,
    outlineVariant = NeutralOutline,
)

private val DarkColorScheme = darkColorScheme(
    primary = TealDarkPrimary,
    onPrimary = TealDarkOnPrimary,
    primaryContainer = TealDarkPrimaryContainer,
    onPrimaryContainer = TealDarkOnPrimaryContainer,
    secondary = TealDarkSecondary,
    onSecondary = TealDarkOnSecondary,
    secondaryContainer = TealDarkSecondaryContainer,
    onSecondaryContainer = TealDarkOnSecondaryContainer,
    // nanoMuse: dark tertiary uses the TAMA palette rather than Material's purple default.
    tertiary = TealDarkTertiary,
    onTertiary = TealDarkOnTertiary,
    tertiaryContainer = TealDarkTertiaryContainer,
    onTertiaryContainer = TealDarkOnTertiaryContainer,
    background = NeutralDarkGroupedBg,
    onBackground = TealDarkOnBackground,
    surface = NeutralDarkGroupedBg,
    onSurface = TealDarkOnSurface,
    surfaceVariant = NeutralDarkGroupedCard,
    onSurfaceVariant = TealDarkOnSurfaceVariant,
    surfaceContainerLowest = NeutralDarkGroupedBg,
    surfaceContainerLow = NeutralDarkGroupedCard,
    surfaceContainer = NeutralDarkGroupedCard,
    surfaceContainerHigh = NeutralDarkGroupedCardElevated,
    surfaceContainerHighest = NeutralDarkGroupedCardElevated,
    outline = NeutralDarkOutline,
    outlineVariant = NeutralDarkOutline,
)

// App-wide FAB accent color (warm beige, matching iOS New Chat button).
// Reads from ChatPalette so it follows the in-app theme override (theme_mode pref),
// not android.isSystemInDarkTheme(), which only tracks the system setting.
@Composable
fun minisFabColor(): Color = LocalChatPalette.current.fabAccent

// App-wide shape system — larger corners for a modern, friendly feel
// DropdownMenu uses extraSmall, Dialog uses extraLarge, BottomSheet uses extraLarge
private val MinisShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),   // DropdownMenu, Tooltip, OutlinedTextField default
    small = RoundedCornerShape(12.dp),        // Chip, TextField
    medium = RoundedCornerShape(20.dp),       // Card, Snackbar
    large = RoundedCornerShape(24.dp),        // NavigationDrawer
    extraLarge = RoundedCornerShape(28.dp),   // Dialog, BottomSheet
)

@Composable
fun MinisTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val typography = scaledTypography(fontScale)
    val chatPalette = if (darkTheme) DarkChatPalette else LightChatPalette

    MaterialTheme(
        colorScheme = colorScheme,
        shapes = MinisShapes,
        typography = typography,
    ) {
        CompositionLocalProvider(LocalChatPalette provides chatPalette, content = content)
    }
}

private fun TextStyle.scale(factor: Float): TextStyle =
    if (factor == 1f) this else copy(fontSize = fontSize * factor)

// nanoMuse: Muse's type, on the system face. Material's scale carries tracking
// (0.1–0.5sp) and small labels (11–12sp) that read as "Android"; Muse sets
// everything at zero tracking, runs body at 16 and its captions at 13, and
// weights titles SemiBold. The face stays the device's default so the app
// matches Muse on the same phone (MiSans, Roboto, …) and Chinese and Latin
// never come from two fonts.
private fun museTypography(): Typography {
    val base = Typography()
    fun TextStyle.muse(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        copy(fontSize = size.sp, lineHeight = line.sp, letterSpacing = 0.sp, fontWeight = weight)
    return Typography(
        displayLarge = base.displayLarge.muse(56, 64, FontWeight.SemiBold),
        displayMedium = base.displayMedium.muse(44, 52, FontWeight.SemiBold),
        displaySmall = base.displaySmall.muse(36, 44, FontWeight.SemiBold),
        headlineLarge = base.headlineLarge.muse(32, 40, FontWeight.SemiBold),
        headlineMedium = base.headlineMedium.muse(28, 36, FontWeight.SemiBold),
        headlineSmall = base.headlineSmall.muse(24, 32, FontWeight.SemiBold),
        titleLarge = base.titleLarge.muse(22, 28, FontWeight.SemiBold),
        titleMedium = base.titleMedium.muse(17, 22, FontWeight.SemiBold),
        titleSmall = base.titleSmall.muse(15, 20, FontWeight.Medium),
        bodyLarge = base.bodyLarge.muse(16, 24),
        bodyMedium = base.bodyMedium.muse(14, 20),
        bodySmall = base.bodySmall.muse(13, 18),
        labelLarge = base.labelLarge.muse(15, 20, FontWeight.Medium),
        labelMedium = base.labelMedium.muse(13, 18, FontWeight.Medium),
        labelSmall = base.labelSmall.muse(12, 16, FontWeight.Medium),
    )
}

private fun scaledTypography(factor: Float): Typography {
    val base = museTypography() // nanoMuse: was Material's default scale
    return Typography(
        displayLarge = base.displayLarge.scale(factor),
        displayMedium = base.displayMedium.scale(factor),
        displaySmall = base.displaySmall.scale(factor),
        headlineLarge = base.headlineLarge.scale(factor),
        headlineMedium = base.headlineMedium.scale(factor),
        headlineSmall = base.headlineSmall.scale(factor),
        titleLarge = base.titleLarge.scale(factor),
        titleMedium = base.titleMedium.scale(factor),
        titleSmall = base.titleSmall.scale(factor),
        bodyLarge = base.bodyLarge.scale(factor),
        bodyMedium = base.bodyMedium.scale(factor),
        bodySmall = base.bodySmall.scale(factor),
        labelLarge = base.labelLarge.scale(factor),
        labelMedium = base.labelMedium.scale(factor),
        labelSmall = base.labelSmall.scale(factor),
    )
}
