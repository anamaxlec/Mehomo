package dev.memoh.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.memoh.core.model.MemohAccent
import dev.memoh.core.model.ThemeMode

/**
 * Spacing scale. Every gap in the app comes from here so vertical rhythm stays
 * consistent across features.
 */
data class MemohSpacing(
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 12.dp,
    val lg: Dp = 16.dp,
    val xl: Dp = 24.dp,
    val xxl: Dp = 32.dp,
    val xxxl: Dp = 48.dp,
    /** Horizontal page gutter. */
    val gutter: Dp = 20.dp,
    /** Max width of the reading column; matches the official web client. */
    val readingMaxWidth: Dp = 840.dp,
    /** Narrower column used by the empty-state composer. */
    val composerMaxWidth: Dp = 704.dp,
)

val LocalMemohSpacing = staticCompositionLocalOf { MemohSpacing() }

/** The breakpoint the official web client uses to switch to its mobile shell. */
val MemohCompactBreakpoint = 768.dp

/** Duration/easing constants for hand-rolled motion, kept in one place. */
object MemohMotion {
    /** Streaming caret pulse period. */
    const val CARET_PULSE_MS = 800
    /** How long a settled reasoning card stays expanded before folding. */
    const val REASONING_FOLD_DELAY_MS = 800
    /** Typing-indicator dot stagger. */
    const val TYPING_DOT_PERIOD_MS = 600
    const val TYPING_DOT_DELAY_MS = 150
    /** Connection dot breathing period while disconnected. */
    const val DISCONNECT_BREATH_MS = 1200
}

@Composable
fun MemohTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    accent: MemohAccent = MemohAccent.Violet,
    content: @Composable () -> Unit,
) {
    val scheme = memohColorScheme(darkTheme = darkTheme, accent = accent)
    CompositionLocalProvider(LocalMemohSpacing provides MemohSpacing()) {
        MaterialExpressiveTheme(
            colorScheme = scheme,
            motionScheme = MotionScheme.expressive(),
            shapes = MemohShapes,
            typography = MemohTypography,
            content = content,
        )
    }
}

/**
 * Applies an accent by overriding only the primary quartet; the neutral planes
 * and error/tertiary roles keep their brand values in every accent.
 */
private fun memohColorScheme(darkTheme: Boolean, accent: MemohAccent): ColorScheme {
    val base = if (darkTheme) MemohDarkColors else MemohLightColors
    if (accent == MemohAccent.Violet) return base
    val (primary, onPrimary, container, onContainer) = accentQuartet(accent, darkTheme)
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = primary,
    )
}

private data class Quartet(
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
)

private fun accentQuartet(accent: MemohAccent, dark: Boolean): Quartet = when (accent) {
    MemohAccent.Violet -> Quartet(
        Color(0xFF764BE5), Color(0xFFFFFFFF), Color(0xFFEEE5FE), Color(0xFF281B3C),
    )
    MemohAccent.Ocean -> if (dark) Quartet(
        Color(0xFF8FCDFF), Color(0xFF00344F), Color(0xFF00496C), Color(0xFFC9E6FF),
    ) else Quartet(
        Color(0xFF00639B), Color(0xFFFFFFFF), Color(0xFFCDE5FF), Color(0xFF001D33),
    )
    MemohAccent.Forest -> if (dark) Quartet(
        Color(0xFF9BD3A8), Color(0xFF0A391C), Color(0xFF245030), Color(0xFFB6EFC2),
    ) else Quartet(
        Color(0xFF2C6A40), Color(0xFFFFFFFF), Color(0xFFAEF2BE), Color(0xFF00210E),
    )
    MemohAccent.Rose -> if (dark) Quartet(
        Color(0xFFFFB0C8), Color(0xFF5E1133), Color(0xFF7B2949), Color(0xFFFFD9E2),
    ) else Quartet(
        Color(0xFFB0004F), Color(0xFFFFFFFF), Color(0xFFFFD9E2), Color(0xFF3E0018),
    )
    MemohAccent.Amber -> if (dark) Quartet(
        Color(0xFFF0C048), Color(0xFF3E2E00), Color(0xFF584400), Color(0xFFFFDF9B),
    ) else Quartet(
        Color(0xFF7A5900), Color(0xFFFFFFFF), Color(0xFFFFDF9B), Color(0xFF261A00),
    )
}
