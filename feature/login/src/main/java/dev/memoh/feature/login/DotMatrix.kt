package dev.memoh.feature.login

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The dot-matrix backdrop from the official login page: a grid of dots whose
 * opacity falls off from the centre, gently breathing.
 *
 * Recreated natively rather than shipped as an asset so it adapts to the theme
 * and to any screen size, and so the animation can be disabled wholesale when
 * the system asks for reduced motion.
 */
@Composable
fun DotMatrixBackground(
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    dotSpacing: androidx.compose.ui.unit.Dp = 22.dp,
    dotRadius: androidx.compose.ui.unit.Dp = 1.6.dp,
) {
    val baseColor = MaterialTheme.colorScheme.primary

    val progress by rememberInfiniteTransition(label = "dot-matrix").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 6000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "dot-pulse",
    )
    val pulse = if (animate) progress else 0.5f

    Canvas(modifier = modifier.fillMaxSize()) {
        val spacing = dotSpacing.toPx()
        val radius = dotRadius.toPx()
        val centre = Offset(size.width / 2f, size.height * 0.38f)
        val maxDistance = kotlin.math.hypot(size.width / 2f, size.height * 0.5f)

        var y = 0f
        while (y < size.height) {
            var x = 0f
            while (x < size.width) {
                val distance = kotlin.math.hypot(x - centre.x, y - centre.y)
                // Dots fade out towards the edges so the grid reads as a soft
                // field rather than a hard texture.
                val falloff = (1f - (distance / maxDistance)).coerceIn(0f, 1f)
                val alpha = (falloff * falloff * 0.5f) * (0.6f + 0.4f * pulse)
                if (alpha > 0.01f) {
                    drawCircle(
                        color = baseColor.copy(alpha = alpha),
                        radius = radius,
                        center = Offset(x, y),
                    )
                }
                x += spacing
            }
            y += spacing
        }
    }
}

/** The Memoh wordmark, drawn as text so it scales with the user's font size. */
@Composable
fun MemohWordmark(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    Text(
        text = "Mehomo",
        modifier = modifier,
        style = MaterialTheme.typography.headlineLarge,
        color = color,
    )
}
