package dev.memoh.feature.chat.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.model.QueueItem
import dev.memoh.core.model.SessionQueueResponse

/** Keep queued text and its next action beside the editor that submitted it. */
@Composable
fun ComposerQueue(
    queue: SessionQueueResponse,
    isRunning: Boolean,
    busy: Boolean,
    onOpenControls: () -> Unit,
    onPromote: (QueueItem) -> Unit,
    onRemove: (QueueItem, Boolean) -> Unit,
) {
    val count = queue.followUp.size + queue.steer.size
    if (count == 0) return
    Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
        TextButton(onOpenControls, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
            Text("消息队列 · $count", style = MaterialTheme.typography.labelMedium)
        }
        listOf(false to queue.followUp, true to queue.steer).forEach { (steer, items) ->
            items.forEach { item -> key(item.itemId) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (steer) "插话" else "后续", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary)
                        Text(item.text, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (!steer && queue.steerSupported && isRunning) IconButton({ onPromote(item) }, enabled = !busy) {
                            Icon(Icons.Filled.Forum, "将排队消息转为插话", Modifier.size(18.dp))
                        }
                        IconButton({ onRemove(item, steer) }, enabled = !busy) {
                            Icon(Icons.Filled.Close, "移出队列", Modifier.size(18.dp))
                        }
                    }
                }
            } }
        }
    }
}
