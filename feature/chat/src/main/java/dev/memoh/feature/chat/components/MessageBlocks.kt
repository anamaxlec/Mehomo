package dev.memoh.feature.chat.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.ErrorDetails
import dev.memoh.core.designsystem.component.LocalErrorActionHandler
import dev.memoh.core.designsystem.component.MemohFeedbackCard
import dev.memoh.core.markdown.MemohMarkdown
import dev.memoh.core.model.MemohError
import dev.memoh.core.model.UIMessage

@Composable
fun ErrorBanner(message: UIMessage, modifier: Modifier = Modifier, onToggleDetails: () -> Unit = {}) {
    val problem = message.problem
    ChatErrorCard(problem.text(), problem, modifier, onToggleDetails = onToggleDetails)
}

@Composable
internal fun ChatErrorCard(
    text: String,
    problem: MemohError?,
    modifier: Modifier = Modifier,
    onDismiss: (() -> Unit)? = null,
    onToggleDetails: () -> Unit = {},
) {
    val handler = LocalErrorActionHandler.current
    val action = problem?.action
    if (text.isBlank()) return
    MemohFeedbackCard(text, modifier, error = true) {
        val actions: (ButtonGroupScope.() -> Unit)? = if ((handler != null && action != null) || onDismiss != null) ({
            if (handler != null && action != null) clickableItem(onClick = { handler(action) }, label = action.title,
                icon = { Icon(Icons.Filled.Settings, null) })
            if (onDismiss != null) clickableItem(onClick = onDismiss, label = "知道了",
                icon = { Icon(Icons.Filled.Check, null) })
        }) else null
        ErrorDetails(problem, text, onToggle = onToggleDetails, actions = actions)
    }
}
/** Runtime degradation notice — informational, not an error. */
@Composable
fun NoticeBar(message: UIMessage, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = if (message.code.isNullOrBlank()) message.content else dev.memoh.core.model.errorMessage(message.code, message.args),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** A one-line status such as "正在启动容器…". */
@Composable
fun StatusLine(message: UIMessage, modifier: Modifier = Modifier) {
    Text(
        text = message.content.ifBlank { message.name.orEmpty() },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}

/** The assistant's answer itself: plain Markdown in the reading flow. */
@Composable
fun TextBlock(content: String, modifier: Modifier = Modifier, streaming: Boolean = false) {
    MemohMarkdown.Markdown(markdown = content, modifier = modifier, singleDollarMath = false, streaming = streaming,
        workspaceImagesAsLinks = true)
}

/** A pulsing caret marking the live end of a streaming answer. */
@Composable
fun StreamingCaret(color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "caret")
    val alpha by transition.animateFloat(
        initialValue = 0.25f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "caret-alpha",
    )
    Box(
        modifier = Modifier
            .size(width = 7.dp, height = 15.dp)
            .alpha(alpha)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(color),
    )
}

/** Three-dot indicator shown while waiting for the server to accept a run. */
@Composable
fun TypingIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "typing")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Restart,
        ),
        label = "typing-phase",
    )
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(3) { index ->
            val active = (phase.toInt() % 3) == index
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .alpha(if (active) 1f else 0.3f)
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
    }
}
