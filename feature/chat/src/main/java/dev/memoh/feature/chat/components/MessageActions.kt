package dev.memoh.feature.chat.components

import android.content.ClipData
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ForkRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohMenuDivider
import dev.memoh.core.designsystem.component.MemohMenuRow
import dev.memoh.core.designsystem.component.MemohPopupMenu
import dev.memoh.core.model.UIMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

internal val MessageActionsHeight = 36.dp

/** Copies the reply itself, with Markdown preserved, excluding internal reasoning and tools. */
internal fun replyCopyText(messages: List<UIMessage>): String = messages
    .filter { it.type in setOf("text", "command") && it.content.isNotBlank() }
    .joinToString("\n\n") { it.content }

private fun messageTime(timestamp: String): String {
    if (timestamp.isBlank()) return "时间暂不可用"
    return runCatching {
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(Locale.getDefault()).format(Instant.parse(timestamp).atZone(ZoneId.systemDefault()))
    }.getOrDefault(timestamp)
}

/** One action row per complete reply; history rows remain accessible on touch screens. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun MessageActions(
    copyText: String,
    timestamp: String,
    assistant: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    retryEnabled: Boolean = false,
    onRetry: () -> Unit = {},
    onFork: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember(copyText) { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1500); copied = false } }
    val colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)
    // The 4 dp inset plus the glyph's 8 dp inset matches the message text's 12 dp inset.
    val buttonModifier = Modifier.size(32.dp)
    val shapes = IconButtonDefaults.shapes(
        shape = IconButtonDefaults.extraSmallRoundShape,
        pressedShape = IconButtonDefaults.extraSmallPressedShape,
    )
    Row(modifier.fillMaxWidth().height(MessageActionsHeight).padding(horizontal = 4.dp),
        horizontalArrangement = if (assistant) Arrangement.Start else Arrangement.End,
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = {
            scope.launch {
                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("消息", copyText)))
                copied = true
            }
        }, modifier = buttonModifier, enabled = copyText.isNotBlank(), colors = colors, shapes = shapes) {
            Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                if (copied) "已复制" else "复制消息", Modifier.size(16.dp))
        }
        if (assistant) IconButton(onClick = onRetry, modifier = buttonModifier, enabled = enabled && retryEnabled,
            colors = colors, shapes = shapes) {
            Icon(Icons.Outlined.Replay, if (retryEnabled) "重新生成" else "仅最新回复可重新生成", Modifier.size(16.dp))
        }
        Box {
            IconButton(onClick = { moreOpen = !moreOpen }, modifier = buttonModifier, colors = colors, shapes = shapes) {
                Icon(Icons.Outlined.MoreHoriz, "更多消息操作", Modifier.size(16.dp))
            }
            MemohPopupMenu(moreOpen, { moreOpen = false }, Alignment.BottomStart, maxHeight = 200.dp) {
                Text(messageTime(timestamp), Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                onFork?.let { fork ->
                    MemohMenuDivider()
                    MemohMenuRow("新分支", Icons.Outlined.ForkRight, { moreOpen = false; fork() }, enabled = enabled)
                }
            }
        }
    }
}
