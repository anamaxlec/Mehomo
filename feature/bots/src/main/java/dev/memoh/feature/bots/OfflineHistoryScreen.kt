package dev.memoh.feature.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.memoh.core.data.*
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class OfflineHistoryState(val query: String = "", val chats: List<CachedChat> = emptyList(), val options: HistoryCacheOptions = HistoryCacheOptions(), val error: String? = null)
@HiltViewModel
class OfflineHistoryViewModel @Inject constructor(private val repository: SessionRepository, private val store: ChatHistoryStore) : ViewModel() {
    private val _state = MutableStateFlow(OfflineHistoryState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    fun search(query: String) { _state.update { it.copy(query = query) }; refresh() }
    fun refresh() {
        job?.cancel()
        val account = repository.state.value.account ?: return
        val generation = repository.generation
        job = viewModelScope.launch {
            try {
                val chats = store.list(account.accountId, account.teamId.orEmpty(), state.value.query)
                val options = store.options(account.accountId, account.teamId.orEmpty())
                if (generation == repository.generation) _state.update { it.copy(chats = chats, options = options, error = null) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(error = "缓存读取失败") } }
        }
    }
    fun configure(enabled: Boolean = state.value.options.enabled, count: Int = state.value.options.sessions) {
        val account = repository.state.value.account ?: return
        val generation = repository.generation
        viewModelScope.launch {
            try { store.setOptions(account.accountId, account.teamId.orEmpty(), HistoryCacheOptions(enabled, count)); if (generation == repository.generation) refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(error = "缓存设置保存失败") } }
        }
    }
    fun clear() {
        val account = repository.state.value.account ?: return
        val generation = repository.generation
        viewModelScope.launch {
            try { store.clear(account.accountId, account.teamId.orEmpty()); if (generation == repository.generation) refresh() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(error = "缓存清理失败") } }
        }
    }
}

@Composable
fun OfflineHistoryScreen(state: OfflineHistoryState, vm: OfflineHistoryViewModel, onBack: () -> Unit, onOpen: (String, String) -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, topBar = { MemohPageTopBar("离线历史", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
            contentPadding = PaddingValues(16.dp, 16.dp, 16.dp, maxOf(16.dp, LocalFloatingNavigationPadding.current)), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
            item {
                SegmentedListItem(onClick = { vm.configure(!state.options.enabled) }, shapes = ListItemDefaults.segmentedShapes(0, 2),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    leadingContent = { MemohListIcon(Icons.Filled.OfflinePin) }, supportingContent = { Text("保存最近打开的会话，每个会话最多 500 条历史。") },
                    trailingContent = { Switch(state.options.enabled, { vm.configure(it) }, thumbContent = { Icon(if (state.options.enabled) Icons.Filled.Check else Icons.Filled.Close, null) }) }) { Text("缓存近期会话") }
            }
            item {
                SegmentedListItem(onClick = {}, shapes = ListItemDefaults.segmentedShapes(1, 2),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    leadingContent = { MemohListIcon(Icons.Filled.History) }, supportingContent = {
                        ButtonGroup(modifier = Modifier.fillMaxWidth(), overflowIndicator = {}) {
                            listOf(10, 30, 50).forEach { count -> toggleableItem(checked = state.options.sessions == count, onCheckedChange = { if (it) vm.configure(count = count) }, label = "$count 个") }
                        }
                    }) { Text("缓存范围") }
            }
            item {
                OutlinedTextField(state.query, vm::search, Modifier.fillMaxWidth().padding(vertical = 12.dp), label = { Text("查找缓存中的标题和消息") }, singleLine = true, leadingIcon = { Icon(Icons.Filled.Search, null) })
                Text("仅搜索当前账号和团队的缓存。图片和文件需要网络加载。", Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MemohActionButton("清理缓存", Icons.Filled.DeleteOutline, { confirm = true }, enabled = state.chats.isNotEmpty())
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            itemsIndexed(state.chats, key = { _, chat -> "${chat.botId}:${chat.session.id}" }) { index, chat ->
                SegmentedListItem(onClick = { onOpen(chat.botId, chat.session.id) }, shapes = ListItemDefaults.segmentedShapes(index, state.chats.size),
                    colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                    leadingContent = { MemohListIcon(Icons.Filled.Chat) }, supportingContent = { Text("${chat.turns.size} 条 · 最后同步 " + DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(chat.syncedAt))) },
                    trailingContent = { Icon(Icons.Filled.ChevronRight, null) }) { Text(chat.session.title?.takeIf(String::isNotBlank) ?: "未命名会话") }
            }
            if (state.chats.isEmpty()) item { Text(if (state.query.isBlank()) "打开会话后会在这里保存近期历史。" else "缓存中没有匹配内容。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("清理离线历史") }, text = { Text("清理当前账号和团队的本地缓存。") },
        confirmButton = { TextButton({ vm.clear(); confirm = false }) { Text("清理") } }, dismissButton = { TextButton({ confirm = false }) { Text("取消") } })
}
