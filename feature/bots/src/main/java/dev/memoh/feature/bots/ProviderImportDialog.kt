package dev.memoh.feature.bots

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.model.ProviderImport
import dev.memoh.core.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.serialization.json.JsonObject

data class ProviderImportState(val busy: Boolean = false, val error: String? = null, val createdId: String? = null, val modelsImported: Boolean = true)

@HiltViewModel
class ProviderImportViewModel @Inject constructor(private val repository: SessionRepository, @ApplicationContext private val context: Context) : ViewModel() {
    private val _state = MutableStateFlow(ProviderImportState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    fun reset() { job?.cancel(); _state.value = ProviderImportState() }
    fun confirm(payload: ProviderImport) {
        if (state.value.busy || state.value.createdId != null) return
        val generation = repository.generation
        _state.value = ProviderImportState(busy = true)
        job = viewModelScope.launch {
            try {
                val api = repository.api() ?: throw SessionExpiredException()
                val source = payload.template?.let { wanted -> loadProviderSources(context).firstOrNull {
                    it.text("id").equals(wanted, true) && it.text("client_type") == payload.clientType
                } }
                val template = if (source != null) null else payload.template?.let { wanted ->
                    api.managementRead("provider-templates").objects().firstOrNull {
                        (it.text("key").equals(wanted, true) || it.text("id") == wanted) &&
                            it.text("driver").ifBlank { "openai-completions" } == payload.clientType && it.text("domain").let { domain -> domain.isBlank() || domain == "llm" }
                    }
                }
                if (generation != repository.generation) return@launch
                val config = apiBody("base_url" to payload.baseUrl, "api_key" to payload.apiKey)
                val created = if (template != null) api.managementResult(listOf("providers", "from-template"), body = apiBody("template_id" to template.text("id"), "domain" to "llm", "name" to payload.name, "config" to config))
                    else api.managementResult(listOf("providers"), body = apiBody("name" to payload.name, "client_type" to payload.clientType,
                        "config" to config, "metadata" to source?.let(::sourceMetadata)))
                val id = created.obj().text("id")
                if (id.isBlank()) {
                    if (generation == repository.generation) _state.value = ProviderImportState(createdId = "", modelsImported = false,
                        error = "服务端已受理，但未返回 ID。请在供应商列表确认结果，避免重复创建。")
                    return@launch
                }
                var imported = true
                var importError: String? = null
                try { if (generation == repository.generation) {
                    val verified = api.managementResult(listOf("providers", id, "test")).obj()
                    if (verified.text("status") in listOf("auth_error", "error")) throw UserFacingException(verified.text("code").takeIf(String::isNotBlank)?.let {
                        dev.memoh.core.model.errorMessage(it)
                    } ?: "连接验证失败，请检查 API key 和地址。")
                    api.managementResult(providerImportPath(JsonObject(created.obj() +
                        (if (source != null) mapOf("metadata" to sourceMetadata(source)) else emptyMap()))))
                } }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { imported = false; importError = e.userMessage() }
                if (generation == repository.generation) _state.value = ProviderImportState(createdId = id, modelsImported = imported, error = importError)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.value = ProviderImportState(error = e.userMessage()) }
        }
    }
}

@Composable
fun ProviderImportDialog(payload: ProviderImport, destination: String, state: ProviderImportState,
    onConfirm: () -> Unit, onDismiss: () -> Unit, onDone: () -> Unit) {
    AlertDialog(onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text(if (state.createdId == null) "导入供应商" else "供应商已添加") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("保存到 · $destination", style = MaterialTheme.typography.labelLarge)
            Text(payload.name, style = MaterialTheme.typography.titleMedium)
            Text(payload.baseUrl)
            Text(payload.clientType, style = MaterialTheme.typography.bodySmall)
            Text("API key · ${payload.maskedKey}", style = MaterialTheme.typography.bodyMedium)
            if (state.busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (state.createdId == null) Text("确认后会保存配置，并尝试从此供应商导入模型。", style = MaterialTheme.typography.bodySmall)
            else if (!state.modelsImported) Text("模型导入暂未完成，可在供应商页面重试。", style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(if (state.createdId == null) onConfirm else onDone, enabled = !state.busy) { Text(if (state.createdId == null) "确认添加" else "查看供应商") } },
        dismissButton = { TextButton(onDismiss, enabled = !state.busy) { Text("关闭") } })
}
