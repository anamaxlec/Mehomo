package dev.memoh.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * A user or bot avatar.
 *
 * When there is no image, this shows a neutral monogram — deliberately *not* a
 * generated mascot or an inferred illustration, which would imply brand
 * identity the bot does not have.
 */
@Composable
fun MemohAvatar(
    label: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    containerColor: Color = MaterialTheme.colorScheme.primaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onPrimaryContainer,
) {
    val monogram = rememberMonogram(label)
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = monogram,
            color = contentColor,
            fontWeight = FontWeight.Medium,
            fontSize = (size.value * 0.4f).sp,
        )
    }
}

/** First character of the first meaningful word, upper-cased. */
private fun rememberMonogram(label: String): String {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return "?"
    val first = trimmed.firstOrNull { it.isLetterOrDigit() } ?: return "?"
    return first.uppercaseChar().toString()
}

/** What a status dot means. Each state owns one visual language, never mixed. */
enum class MemohStatus { Online, Running, Warning, Offline, Idle }

@Composable
fun MemohStatusDot(
    status: MemohStatus,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    val color = when (status) {
        MemohStatus.Online -> MaterialTheme.colorScheme.tertiary
        MemohStatus.Running -> MaterialTheme.colorScheme.primary
        MemohStatus.Warning -> Color(0xFFE0A02E)
        MemohStatus.Offline -> MaterialTheme.colorScheme.outline
        MemohStatus.Idle -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/**
 * The empty state used by lists and the chat surface: a short line of what the
 * screen is for, plus optional actions. No large logo, no marketing copy.
 */
@Composable
fun MemohEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (description != null) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (action != null) {
            Box(modifier = Modifier.padding(top = 8.dp)) { action() }
        }
    }
}

/**
 * A quiet inline error. Errors belong next to what failed, not in a modal that
 * interrupts reading.
 */
@Composable
fun MemohInlineError(
    message: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}