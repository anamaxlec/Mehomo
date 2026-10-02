package dev.memoh.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.memoh.feature.sessions.SessionsViewModel

@Composable
fun ShareTargetDialog(onDismiss: () -> Unit, onSelect: (String, String) -> Unit) {
    val viewModel: SessionsViewModel = hiltViewModel(key = "share-target")
    val state by viewModel.state.collectAsState()
    LaunchedEffect(Unit) { viewModel.start(); viewModel.refresh() }
    var botsOpen by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("分享到会话") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("内容将填入草稿，确认后再发送。")
            Box {
                TextButton(onClick = { botsOpen = true }) { Text(state.bot?.displayName ?: state.bot?.name ?: "选择 Bot") }
                DropdownMenu(botsOpen, { botsOpen = false }) {
                    state.bots.forEach { bot -> DropdownMenuItem(text = { Text(bot.displayName ?: bot.name) },
                        onClick = { viewModel.selectBot(bot); botsOpen = false }) }
                }
            }
            if (state.loading && state.sessions.isEmpty()) CircularProgressIndicator()
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = viewModel::refresh) { Text("重试") } }
            if (!state.loading && state.bots.isEmpty() && state.error == null) Text("当前账号还没有 Bot，请先在网页端创建。")
            LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(state.sessions.filter { it.isLocal }, key = { it.id }) { item ->
                    TextButton(onClick = { onSelect(state.bot!!.id, item.id) }, modifier = Modifier.fillMaxWidth()) {
                        Text(item.title?.takeIf(String::isNotBlank) ?: "未命名会话")
                    }
                }
                if (state.nextCursor != null) item {
                    TextButton(onClick = viewModel::loadMore, enabled = !state.loadingMore && !state.loading) { Text(if (state.loadingMore) "加载中…" else "加载更多") }
                    state.loadMoreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                }
            }
        } },
        confirmButton = { TextButton(enabled = state.bot != null && !state.creating,
            onClick = { viewModel.createSession { onSelect(it.botId ?: state.bot!!.id, it.id) } }) { Text("新建会话") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}
