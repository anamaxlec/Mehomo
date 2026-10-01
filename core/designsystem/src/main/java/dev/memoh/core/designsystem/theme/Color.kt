package dev.memoh.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Memoh's brand palette: violet primary, near-black reading plane in dark mode,
 * and a lightly tinted navigation plane in light mode.
 *
 * Values are paired light/dark rather than derived by inversion — dark mode is
 * re-picked so the violet desaturates instead of glaring on a black plane.
 */
internal val MemohLightColors = lightColorScheme(
    primary = Color(0xFF764BE5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEEE5FE),
    onPrimaryContainer = Color(0xFF281B3C),

    secondary = Color(0xFF706A7B),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0ECF6),
    onSecondaryContainer = Color(0xFF39343F),

    tertiary = Color(0xFF3B8066),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD3EFE1),
    onTertiaryContainer = Color(0xFF10291F),

    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF191816),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF191816),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEFEDF0),
    surfaceVariant = Color(0xFFF6F5F7),
    onSurfaceVariant = Color(0xFF6A6965),

    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAF8F7),
    surfaceContainer = Color(0xFFF8F7F9),
    surfaceContainerHigh = Color(0xFFF0EEF3),
    surfaceContainerHighest = Color(0xFFEAE8ED),

    outline = Color(0xFFABA6B1),
    outlineVariant = Color(0xFFE5E2E0),

    error = Color(0xFFB83E4B),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFEEF0),
    onErrorContainer = Color(0xFF862C39),

    inverseSurface = Color(0xFF302E33),
    inverseOnSurface = Color(0xFFF3F0F4),
    inversePrimary = Color(0xFFA490FF),
    scrim = Color(0xFF000000),
)

internal val MemohDarkColors = darkColorScheme(
    primary = Color(0xFFA490FF),
    onPrimary = Color(0xFF241446),
    primaryContainer = Color(0xFF392B51),
    onPrimaryContainer = Color(0xFFF1E8FF),

    secondary = Color(0xFFB8B0C2),
    onSecondary = Color(0xFF2B2634),
    secondaryContainer = Color(0xFF302B37),
    onSecondaryContainer = Color(0xFFE7DFEF),

    tertiary = Color(0xFF8DCDB0),
    onTertiary = Color(0xFF0C2B1E),
    tertiaryContainer = Color(0xFF274536),
    onTertiaryContainer = Color(0xFFD6F2E3),

    background = Color(0xFF0C0C0C),
    onBackground = Color(0xFFDEDEDE),
    surface = Color(0xFF0C0C0C),
    onSurface = Color(0xFFDEDEDE),
    surfaceBright = Color(0xFF181818),
    surfaceDim = Color(0xFF0C0C0C),
    surfaceVariant = Color(0xFF252329),
    onSurfaceVariant = Color(0xFF9E9E9E),

    surfaceContainerLowest = Color(0xFF080808),
    surfaceContainerLow = Color(0xFF131313),
    surfaceContainer = Color(0xFF201E24),
    surfaceContainerHigh = Color(0xFF2C2931),
    surfaceContainerHighest = Color(0xFF37333D),

    outline = Color(0xFF77717F),
    outlineVariant = Color(0xFF36323D),

    error = Color(0xFFFFA6AF),
    onError = Color(0xFF5C1420),
    errorContainer = Color(0xFF41252C),
    onErrorContainer = Color(0xFFFFDADF),

    inverseSurface = Color(0xFFDEDEDE),
    inverseOnSurface = Color(0xFF302E33),
    inversePrimary = Color(0xFF764BE5),
    scrim = Color(0xFF000000),
)
