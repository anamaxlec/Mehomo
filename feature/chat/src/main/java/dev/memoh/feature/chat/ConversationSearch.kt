package dev.memoh.feature.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.BookmarkRemove
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.data.SavedChatItem
import dev.memoh.core.designsystem.component.MemohListIcon
import dev.memoh.core.model.UITurn
import dev.memoh.core.model.ConversationMatch
import dev.memoh.core.model.searchTurns
import dev.memoh.feature.chat.components.MessageAttachment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal typealias ConversationHit = ConversationMatch

/** Search only material actually present in the loaded transcript or its local cache. */
internal fun searchConversation(turns: List<UITurn>, query: String): List<ConversationHit> {
    return searchTurns(turns, query)
}

/** A cached result can jump into the live transcript only if its matched message still agrees. */
internal fun sameCachedSearchTarget(hit: ConversationHit, cached: UITurn, loaded: UITurn): Boolean {
    if (cached.listKey != loaded.listKey) return false
    return hit.messageIndex?.let { index ->
        cached.safeMessages.getOrNull(index)?.let { it == loaded.safeMessages.getOrNull(index) } ?: false
    } ?: (cached.text == loaded.text && cached.attachments == loaded.attachments)
}

/** Mirrors the transcript's lazy rows, including separate assistant blocks and action rows. */
internal fun conversationHitIndex(history: List<UITurn>, hit: ConversationHit): Int? {
    val assistantKeys = assistantHistoryKeys(history)
    var index = 0
    for (turn in history) {
        if (turn.isAssistant && turn.safeMessages.isNotEmpty()) {
            val items = assistantRenderItems(assistantKeys[turn.listKey] ?: turn.turnId, turn.safeMessages)
            if (turn.listKey == hit.turnKey) {
                val block = hit.messageIndex?.let { target -> items.indexOfFirst { it.lastIndex >= target } } ?: 0
                return index + block.coerceAtLeast(0)
            }
            index += items.size
        } else {
            if (turn.listKey == hit.turnKey) return index
            index++
        }
        if (turn.isAssistant) index++
    }
    return null
}

internal fun conversationHistoryRowCount(history: List<UITurn>): Int {
    val assistantKeys = assistantHistoryKeys(history)
    return history.sumOf { turn ->
        val rows = if (turn.isAssistant && turn.safeMessages.isNotEmpty())
            assistantRenderItems(assistantKeys[turn.listKey] ?: turn.turnId, turn.safeMessages).size else 1
        rows + if (turn.isAssistant) 1 else 0
    }
}

@Composable
internal fun ConversationSearchDialog(turns: List<UITurn>, onDismiss: () -> Unit, onSelect: (ConversationHit) -> Unit) {
    var query by remember { mutableStateOf("") }
    var matches by remember { mutableStateOf<List<ConversationHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(turns, query) {
        if (query.isBlank()) { matches = emptyList(); searching = false }
        else {
            searching = true
            matches = emptyList()
            delay(120)
            matches = withContext(Dispatchers.Default) { searchConversation(turns, query) }
            searching = false
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("查找会话", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onDismiss) { Icon(Icons.Filled.Close, "关闭查找") }
            }
            OutlinedTextField(query, { query = it }, label = { Text("查找消息或附件") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "清空搜索") } }) else null)
            Text(if (query.isBlank()) "搜索已加载内容和本地缓存" else if (searching) "正在查找已加载内容和本地缓存"
                else "${matches.size} 条结果 · 已加载内容和本地缓存",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (query.isNotBlank() && !searching && matches.isEmpty()) Text("没有匹配的消息", Modifier.padding(vertical = 12.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 360.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                itemsIndexed(matches, key = { _, hit -> "${hit.turnKey.length}:${hit.turnKey}:${hit.messageIndex ?: -1}" }) { index, hit ->
                    SegmentedListItem(onClick = { onSelect(hit) }, shapes = ListItemDefaults.segmentedShapes(index, matches.size),
                        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                        leadingContent = { MemohListIcon(if (hit.label == "我") Icons.Filled.Person else Icons.AutoMirrored.Filled.Chat) },
                        supportingContent = { Text(hit.label, style = MaterialTheme.typography.labelMedium) },
                        trailingContent = { Icon(Icons.Filled.ChevronRight, null, Modifier.size(20.dp)) }) {
                        Text(hit.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
internal fun SavedItemsDialog(items: List<SavedChatItem>, onDismiss: () -> Unit,
    onOpen: (SavedChatItem) -> Unit, onRemove: (SavedChatItem) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("收藏", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                IconButton(onDismiss) { Icon(Icons.Filled.Close, "关闭收藏") }
            }
            if (items.isEmpty()) Text("还没有收藏的消息。", Modifier.padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items.asReversed(), key = { it.turnKey }) { saved ->
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Column(Modifier.fillMaxWidth().padding(start = 16.dp, top = 14.dp, end = 8.dp, bottom = 8.dp)) {
                            if (saved.text.isNotBlank()) Text(saved.text, Modifier.padding(end = 8.dp),
                                style = MaterialTheme.typography.bodyMedium, maxLines = 5, overflow = TextOverflow.Ellipsis)
                            saved.attachments.forEach { MessageAttachment(it) }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onOpen(saved) }) { Text("跳转到消息") }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { onRemove(saved) }) { Icon(Icons.Filled.BookmarkRemove, "取消收藏") }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Cached results stay separate from the live transcript, which may have changed on the server. */
@Composable
internal fun CachedTurnSheet(turn: UITurn, hit: ConversationHit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.fillMaxWidth().heightIn(max = 640.dp).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本地缓存", style = MaterialTheme.typography.headlineSmall)
                    Text("缓存内容可能与当前会话不同", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onDismiss) { Icon(Icons.Filled.Close, "关闭缓存消息") }
            }
            Column(Modifier.fillMaxWidth().weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (turn.isAssistant && turn.safeMessages.isNotEmpty()) {
                    val blocks = assistantRenderItems(turn.turnId, turn.safeMessages)
                    val focusItem = hit.messageIndex?.let { target -> blocks.indexOfFirst { it.lastIndex >= target } }
                    blocks.forEachIndexed { index, item ->
                        AssistantMessageItem(item, isStreaming = false, isRunActive = false,
                            focusMessage = if (index == focusItem) hit.messageIndex?.let(turn.safeMessages::getOrNull) else null,
                            focusRevision = 1)
                    }
                } else TurnItem(turn)
            }
        }
    }
}
