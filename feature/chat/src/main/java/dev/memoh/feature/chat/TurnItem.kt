package dev.memoh.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UITurn
import dev.memoh.core.model.UIUserTurn
import dev.memoh.feature.chat.components.ErrorBanner
import dev.memoh.feature.chat.components.NoticeBar
import dev.memoh.feature.chat.components.ReasoningCard
import dev.memoh.feature.chat.components.StatusLine
import dev.memoh.feature.chat.components.StreamingCaret
import dev.memoh.feature.chat.components.TextBlock
import dev.memoh.feature.chat.components.ToolCard
import dev.memoh.feature.chat.components.MessageAttachment
import dev.memoh.feature.chat.components.MessageActions

/** REST renumbers blocks after dropping runtime status rows; their visual order survives. */
internal fun assistantBlockKeys(turnId: String, messages: List<UIMessage>): List<String> {
    val ordinals = mutableMapOf<String, Int>()
    return messages.map { message ->
        val ordinal = ordinals.getOrDefault(message.type, 0)
        ordinals[message.type] = ordinal + 1
        val block = message.toolCallId.takeIf { message.type == "tool" && it.isNotBlank() }
            ?.let { "tool:call:$it" } ?: "${message.type}:$ordinal"
        "assistant-$turnId:block-$block"
    }
}

/**
 * One settled turn.
 *
 * User turns are a right-aligned tinted bubble; assistant turns are a plain
 * reading column. The asymmetry is deliberate — an answer is prose to be read,
 * a question is an utterance to be recognised at a glance.
 */
@Composable
fun TurnItem(turn: UITurn, modifier: Modifier = Modifier) {
    when {
        turn.isUser -> UserTurnItem(turn, modifier)
        turn.isAssistant -> AssistantTurnItem(
            messages = turn.safeMessages,
            isStreaming = false,
            modifier = modifier,
        )
        else -> SystemTurnItem(turn, modifier)
    }
}

/** The user's own message, right-aligned. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserTurnItem(turn: UITurn, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp),
            horizontalAlignment = Alignment.End,
        ) {
            // Attachments sit above the text, so the media is seen before the
            // caption rather than after it.
            val attachments = turn.attachments.orEmpty()
            if (attachments.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.padding(bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    attachments.forEach { attachment ->
                        MessageAttachment(attachment)
                    }
                }
            }

            val text = turn.text.orEmpty()
            if (text.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .clip(userBubbleShape())
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // A sender label only matters when the message came from someone
            // else over a shared channel.
            turn.senderDisplayName?.takeIf { it.isNotBlank() }?.let { name ->
                Text(
                    text = name,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, end = 4.dp),
                )
            }
            MessageActions(text, turn.timestamp, assistant = false)
        }
    }
}

/**
 * The assistant's turn: a reading column of message blocks.
 *
 * Consecutive blocks are spaced tightly because they belong to one utterance;
 * the surrounding turn spacing is what separates one answer from the next.
 */
@Composable
fun AssistantTurnItem(
    messages: List<UIMessage>,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        messages.forEachIndexed { index, message ->
            MessageBlock(message = message, isStreaming = isStreaming && index == messages.lastIndex)
        }
    }
}

/** The in-flight turn, rendered from the run view rather than history. */
@Composable
fun LiveRunItem(
    messages: List<UIMessage>,
    status: String?,
    modifier: Modifier = Modifier,
) {
    AssistantTurnItem(
        messages = messages,
        isStreaming = status != null && !dev.memoh.core.model.RunStatus.isTerminal(status),
        modifier = modifier,
    )
}

/** A turn still awaiting server acceptance. */
@Composable
fun PendingUserTurn(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(userBubbleShape())
                // Muted while unconfirmed: the message is not yet the server's.
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SystemTurnItem(turn: UITurn, modifier: Modifier = Modifier) {
    val task = turn.backgroundTask
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "后台任务",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        task?.command?.takeIf(String::isNotBlank)?.let { command ->
            Text(
                text = command,
                style = MaterialTheme.typography.bodySmall.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        task?.status?.takeIf(String::isNotBlank)?.let { status ->
            Text(
                text = status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Dispatches on the server's message `type`. Unknown types fall through to a
 * status line rather than being dropped, so a future server message is visible
 * instead of silently lost.
 */
@Composable
internal fun MessageBlock(message: UIMessage, isStreaming: Boolean, onToggleDetails: () -> Unit = {}) {
    when (message.type) {
        UIMessage.TYPE_TEXT -> Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.Bottom) {
            TextBlock(content = message.content, modifier = Modifier.weight(1f))
            // Keep the same text width when the stream ends, so removing the
            // caret cannot re-wrap the answer and shift every following line.
            Box(Modifier.width(9.dp).padding(start = 2.dp)) {
                if (isStreaming) StreamingCaret()
            }
        }

        UIMessage.TYPE_REASONING -> ReasoningCard(message = message, isStreaming = isStreaming, onToggleDetails = onToggleDetails)

        UIMessage.TYPE_TOOL -> ToolCard(message = message, isStreaming = isStreaming, onToggleDetails = onToggleDetails)

        UIMessage.TYPE_ERROR -> ErrorBanner(message)

        UIMessage.TYPE_NOTICE -> NoticeBar(message)

        UIMessage.TYPE_STATUS -> StatusLine(message)

        UIMessage.TYPE_COMMAND -> StatusLine(message)

        UIMessage.TYPE_ATTACHMENTS -> {
            val attachments = message.attachments.orEmpty()
            if (attachments.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    attachments.forEach { attachment ->
                        MessageAttachment(attachment)
                    }
                }
            }
        }

        else -> StatusLine(message)
    }
}

@Composable
private fun AttachmentChip(label: String) {
    Box(
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The user bubble's shape: fully rounded except the bottom-trailing corner,
 * which is squared off so the bubble reads as coming from the sender.
 */
private fun userBubbleShape(): RoundedCornerShape = RoundedCornerShape(
    topStart = 20.dp,
    topEnd = 20.dp,
    bottomStart = 20.dp,
    bottomEnd = 6.dp,
)
