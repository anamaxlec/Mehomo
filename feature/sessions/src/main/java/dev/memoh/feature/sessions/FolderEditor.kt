package dev.memoh.feature.sessions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.model.*
import dev.memoh.core.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FolderEditorState(
    val botId: String = "", val folder: Workdir? = null, val name: String = "",
    val targets: List<WorkspaceTarget> = emptyList(), val targetId: String = "native",
    val path: String = "", val directories: List<WorkdirDirectory> = emptyList(),
    val parents: List<String> = emptyList(), val createChild: Boolean = true,
    val loading: Boolean = false, val busy: Boolean = false, val error: String? = null,
    val saved: Boolean = false,
) {
    val target: WorkspaceTarget? get() = targets.firstOrNull { it.targetId == targetId }
    val native: Boolean get() = target?.kind != "remote"
    val resolvedPath: String get() = if (native && createChild) path.trimEnd('/') + "/" + name.trim() else path.trim()
    val canSave: Boolean get() = !loading && !busy && name.isNotBlank() && (folder != null ||
        target?.online != false && path.isNotBlank() && (!native || !createChild || name.trim().let { value ->
            value !in listOf(".", "..") && '/' !in value && '\\' !in value && value.none { it.code < 32 || it.code == 127 }
        }))
}

@HiltViewModel
class FolderEditorViewModel @Inject constructor(private val repository: SessionRepository) : ViewModel() {
    private val _state = MutableStateFlow(FolderEditorState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var listingJob: Job? = null
    private var epoch = 0L
    fun open(botId: String, folder: Workdir?) {
        stop()
        val attempt = epoch
        val generation = repository.generation
        _state.value = FolderEditorState(botId, folder, folder?.name.orEmpty(), targetId = folder?.workspaceTargetId ?: "native",
            path = folder?.path.orEmpty(), loading = folder == null)
        if (folder != null) return
        job = viewModelScope.launch {
            try {
                val targets = (repository.api() ?: throw SessionExpiredException()).workspaceTargets(botId).targets
                if (attempt != epoch || generation != repository.generation) return@launch
                _state.update { it.copy(targets = targets, targetId = targets.firstOrNull { t -> t.kind != "remote" }?.targetId ?: targets.firstOrNull()?.targetId ?: "native", loading = false) }
                browse("")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == epoch && generation == repository.generation) _state.update { it.copy(loading = false, error = e.userMessage()) } }
        }
    }
    fun name(value: String) { _state.update { it.copy(name = value) } }
    fun path(value: String) { _state.update { it.copy(path = value, directories = emptyList(), parents = emptyList()) } }
    fun createChild(value: Boolean) { _state.update { it.copy(createChild = value) } }
    fun target(id: String) {
        if (state.value.busy) return
        listingJob?.cancel()
        _state.update { it.copy(targetId = id, path = "", directories = emptyList(), parents = emptyList(), error = null) }
        browse("")
    }
    fun browse(path: String = state.value.path, back: Boolean = false) {
        listingJob?.cancel()
        val current = state.value
        if (current.target?.online == false) { _state.update { it.copy(error = "这台电脑当前离线，请连接后再选择目录。", loading = false) }; return }
        val attempt = epoch
        val generation = repository.generation
        _state.update { it.copy(loading = true, error = null) }
        listingJob = viewModelScope.launch {
            try {
                val listing = (repository.api() ?: throw SessionExpiredException()).workdirDirectories(current.botId, current.targetId, path)
                if (attempt == epoch && generation == repository.generation && state.value.targetId == current.targetId)
                    _state.update { it.copy(path = listing.path, directories = listing.directories.filter { d -> d.path.isNotBlank() }.sortedBy { d -> d.name.lowercase() }, loading = false,
                        parents = if (back) current.parents.dropLast(1) else if (current.path.isNotBlank() && current.path != listing.path) current.parents + current.path else current.parents) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == epoch && generation == repository.generation && state.value.targetId == current.targetId)
                _state.update { it.copy(loading = false, error = if (e is ApiException && e.status == 503) "目标电脑暂不可达，请连接后重试。" else e.userMessage()) } }
        }
    }
    fun save() {
        val current = state.value
        if (!current.canSave) return
        val generation = repository.generation
        val attempt = epoch
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val api = repository.api() ?: throw SessionExpiredException()
                if (current.folder != null) api.renameWorkdir(current.botId, current.folder.id, current.name.trim())
                else {
                    if (current.native && current.createChild) api.managementWrite(listOf("bots", current.botId, "container", "fs", "mkdir"), "POST",
                        apiBody("path" to current.resolvedPath, "workspace_target_id" to current.targetId))
                    if (attempt != epoch || generation != repository.generation) return@launch
                    api.createWorkdir(current.botId, current.name.trim(), current.resolvedPath, current.targetId)
                }
                if (attempt == epoch && generation == repository.generation) _state.update { it.copy(busy = false, saved = true) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (attempt == epoch && generation == repository.generation) _state.update { it.copy(busy = false, error = e.userMessage()) } }
        }
    }
    fun stop() { epoch++; job?.cancel(); listingJob?.cancel() }
}

@Composable
fun FolderEditorSheet(state: FolderEditorState, vm: FolderEditorViewModel, onDismiss: () -> Unit) {
    ModalBottomSheet({ if (!state.busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 650.dp), contentPadding = PaddingValues(20.dp, 0.dp, 20.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(if (state.folder == null) "新建文件夹" else "编辑文件夹", style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(state.name, vm::name, Modifier.fillMaxWidth().padding(top = 12.dp), label = { Text("名称") }, singleLine = true, enabled = !state.busy)
            }
            if (state.folder == null) {
                item { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    state.targets.forEach { target -> FilterChip(state.targetId == target.targetId, { vm.target(target.targetId) }, enabled = !state.busy,
                        label = { Text(target.name?.takeIf(String::isNotBlank) ?: if (target.kind == "remote") "远程电脑" else "云端工作空间") }) }
                } }
                item {
                    OutlinedTextField(state.path, vm::path, Modifier.fillMaxWidth(), label = { Text(if (state.native) "目录" else "远程电脑上的目录") }, singleLine = true, enabled = !state.busy)
                    TextButton({ vm.browse() }, enabled = !state.loading && !state.busy) { Text("浏览此目录") }
                    if (state.native) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("在此目录下新建子目录")
                        Switch(state.createChild, vm::createChild, enabled = !state.busy)
                    }
                    Text("工作目录：${state.resolvedPath}", style = MaterialTheme.typography.bodySmall)
                    Text(if (state.native && state.createChild) "目录不存在时会创建；已有目录会直接使用。" else "使用目标电脑上的已有目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.loading) item { LinearWavyProgressIndicator(Modifier.fillMaxWidth()) }
                if (state.parents.isNotEmpty()) item { TextButton({ vm.browse(state.parents.last(), back = true) }, enabled = !state.loading && !state.busy) { Text("返回上一级") } }
                items(state.directories, key = { it.path }) { directory ->
                    TextButton({ vm.browse(directory.path) }, Modifier.fillMaxWidth(), enabled = !state.loading && !state.busy) {
                        Icon(Icons.Filled.Folder, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text(directory.name, Modifier.weight(1f)); Icon(Icons.Filled.ChevronRight, null)
                    }
                }
            } else item { Text("目录和设备保持绑定：${state.path}", style = MaterialTheme.typography.bodySmall) }
            state.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
            item { Button(vm::save, Modifier.fillMaxWidth(), enabled = state.canSave) { Text(if (state.busy) "保存中…" else "保存") } }
        }
    }
}
