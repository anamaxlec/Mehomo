package dev.memoh.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.memoh.core.data.SettingsStore
import dev.memoh.core.data.ShareQuickTask
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ShareQuickTasksViewModel @Inject constructor(private val settings: SettingsStore) : ViewModel() {
    private val _tasks = MutableStateFlow<List<ShareQuickTask>>(emptyList())
    val tasks = _tasks.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    private val _saving = MutableStateFlow(false)
    val saving = _saving.asStateFlow()
    private var observation: Job? = null
    private var boundAddress: Pair<String, String>? = null

    fun bind(account: String, team: String) {
        observation?.cancel()
        boundAddress = account to team
        _tasks.value = emptyList()
        _error.value = null
        observation = viewModelScope.launch {
            try { settings.shareQuickTasks(account, team).collect { _tasks.value = it } }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { _error.value = "快捷任务读取失败" }
        }
    }

    fun save(account: String, team: String, task: ShareQuickTask, onSaved: () -> Unit) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                settings.saveShareQuickTask(account, team, task)
                if (boundAddress == (account to team)) {
                    _tasks.value = _tasks.value.filterNot { it.id == task.id } + task
                    _error.value = null
                    onSaved()
                }
            }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { _error.value = "快捷任务保存失败" }
            finally { _saving.value = false }
        }
    }

    fun remove(account: String, team: String, id: String) = viewModelScope.launch {
        try { settings.removeShareQuickTask(account, team, id); _error.value = null }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { _error.value = "快捷任务删除失败" }
    }
}
