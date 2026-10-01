package dev.memoh.feature.chat.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Bookmarks
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.markdown.MemohMarkdown
import dev.memoh.core.model.UIMessage
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The reasoning block: a quiet, collapsible record of what the model thought.
 *
 * It auto-expands while streaming (the user can watch progress) and folds to a
 * single line once the timing arrives. It is deliberately styled as *process*
 * rather than content — smaller, lower contrast, off to the side of the reading
 * flow — so a long answer is not interleaved with walls of italic text.
 */
@Composable
fun ReasoningCard(
    message: UIMessage,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    onToggleDetails: () -> Unit = {},
) {
    // Fold once the run reports a duration, unless the user opened it manually.
    var userExpanded by remember { mutableStateOf<Boolean?>(null) }
    val timing = message.reasoningTiming
    val thinking = isStreaming && timing == null
    val settled = !thinking
    val expanded = userExpanded ?: !settled

    ActivityCard(
        expanded = expanded,
        onToggle = { onToggleDetails(); userExpanded = !expanded },
        modifier = modifier,
        header = {
            ActivityGlyph(Icons.Outlined.Psychology, reasoning = true)
            Text(
                text = when {
                    thinking -> "思考中…"
                    timing != null -> "已思考 ${formatSeconds(timing.durationMs)}"
                    else -> "思考过程"
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (thinking) LoadingIndicator(Modifier.size(18.dp))
        },
    ) {
        if (message.content.isNotBlank()) Text(
            text = message.content,
            style = MaterialTheme.typography.bodySmall.copy(
                fontStyle = FontStyle.Italic,
                lineHeight = MaterialTheme.typography.bodyMedium.lineHeight,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Tool name and summary stay in place while its details reveal below them. */
@Composable
fun ToolCard(
    message: UIMessage,
    isStreaming: Boolean,
    modifier: Modifier = Modifier,
    onToggleDetails: () -> Unit = {},
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = toolSummary(message)
    ActivityCard(
        expanded = expanded,
        onToggle = { onToggleDetails(); expanded = !expanded },
        modifier = modifier,
        header = {
            ActivityGlyph(toolIcon(message.name))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = message.name?.takeIf(String::isNotBlank) ?: "工具调用",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (summary.isNotBlank()) Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (message.running) LoadingIndicator(Modifier.size(18.dp))
            else message.elapsedTimeSeconds?.let { seconds ->
                Text(
                    text = String.format(java.util.Locale.ROOT, "%.1fs", seconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    ) {
        if (summary.isNotBlank()) Text(summary, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        message.input?.let { MonoBlock(label = "输入", text = renderJson(it)) }
        message.output?.let { MonoBlock(label = "输出", text = renderJson(it)) }
        message.diff?.takeIf(String::isNotBlank)?.let {
            MonoBlock(label = "改动", text = it, highlightDiff = true)
        }
        message.progress?.takeIf { it.isNotEmpty() }?.let { progress ->
            MonoBlock(label = "进度", text = progress.joinToString("\n") { renderJson(it) })
        }
    }
}

/** One bounded reveal, with no content crossfade or spring overshoot on reversal. */
@Composable
private fun ActivityCard(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
    header: @Composable RowScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val reveal by animateFloatAsState(
        targetValue = if (expanded) 1f else 0f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "activityDetails",
    )
    val shape = MaterialTheme.shapes.medium
    Column(modifier.fillMaxWidth().clip(shape).background(MaterialTheme.colorScheme.surfaceContainerLow)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(shape)
                .clickable(role = Role.Button, onClickLabel = if (expanded) "收起" else "展开", onClick = onToggle)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            header()
            Icon(Icons.Filled.ExpandMore, if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = reveal * 180f })
        }
        // Keep the same details until the reveal reaches zero. A quick second
        // tap retargets this value from its current height instead of composing
        // a second enter/exit transition.
        if (expanded || reveal > 0f) Column(
            modifier = Modifier.fillMaxWidth().clipToBounds().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints.copy(minHeight = 0))
                layout(placeable.width, (placeable.height * reveal.coerceIn(0f, 1f)).roundToInt()) {
                    placeable.placeRelative(0, 0)
                }
            }.padding(start = 12.dp, end = 12.dp, bottom = 10.dp, top = 6.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
private fun ActivityGlyph(icon: ImageVector, reasoning: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Box(
        modifier = Modifier.size(28.dp).background(
            color = if (reasoning) colors.tertiaryContainer else colors.surfaceContainerHigh,
            shape = MaterialTheme.shapes.small,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (reasoning) colors.onTertiaryContainer else colors.onSurfaceVariant,
        )
    }
}

private fun toolIcon(name: String?): ImageVector {
    val full = name.orEmpty().lowercase(java.util.Locale.ROOT)
    val tool = full.substringAfterLast('.').substringAfterLast("__")
    return when {
        "memory" in full || "recall" in full -> Icons.Outlined.Bookmarks
        "schedule" in full || "cron" in full -> Icons.Outlined.Schedule
        "browser" in full || "web" in full || "navigate" in full ->
            if ("search" in full) Icons.Outlined.TravelExplore else Icons.Outlined.Language
        "agent" in full || "delegate" in full -> Icons.Outlined.SmartToy
        "computer" in full || "desktop" in full || "screen" in full || tool == "click" -> Icons.Outlined.DesktopWindows
        tool in setOf("exec", "bash", "shell", "terminal", "run_command", "execute_command") -> Icons.Outlined.Terminal
        "edit" in tool || "patch" in tool || "write" in tool -> Icons.Outlined.EditNote
        "read" in tool || "open_file" in tool -> Icons.Outlined.Description
        "search" in tool || "grep" in tool || "find" in tool -> Icons.Outlined.Search
        "list" in tool || "glob" in tool || "folder" in tool -> Icons.Outlined.FolderOpen
        "mcp" in full || "connector" in full || "apps" in full -> Icons.Outlined.Extension
        else -> Icons.Outlined.Build
    }
}

/** Error banner. Never clears the transcript — it reports alongside it. */
@Composable
fun ErrorBanner(message: UIMessage, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = Icons.Filled.ErrorOutline,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = message.content.ifBlank { message.code ?: "运行出错" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
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
            text = message.content,
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
fun TextBlock(content: String, modifier: Modifier = Modifier) {
    MemohMarkdown.Markdown(markdown = content, modifier = modifier)
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

@Composable
private fun MonoBlock(
    label: String,
    text: String,
    highlightDiff: Boolean = false,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .padding(8.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = if (highlightDiff) diffColor(text) else MaterialTheme.colorScheme.onSurface,
                maxLines = 40,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Renders a JSON value compactly; long strings are truncated by the caller. */
private fun renderJson(element: JsonElement): String = when (element) {
    is JsonPrimitive -> element.content
    else -> element.toString()
}

/** Colours a diff block by its leading marker, the way a patch reader expects. */
private fun diffColor(text: String): Color = when {
    text.startsWith("+") -> Color(0xFF2E7D32)
    text.startsWith("-") -> Color(0xFFC62828)
    else -> Color.Unspecified
}

/** One-line summary of what a tool call is doing, for the collapsed row. */
private fun toolSummary(message: UIMessage): String {
    val input = message.input
    if (input is JsonPrimitive) return input.content.take(120)
    val output = message.output
    if (output is JsonPrimitive) return output.content.take(120)
    return ""
}

/** Formats a reasoning duration for the folded summary line. */
private fun formatSeconds(durationMs: Long): String {
    val seconds = durationMs / 1000.0
    return if (seconds < 10) String.format("%.1fs", seconds) else "${seconds.toInt()}s"
}
