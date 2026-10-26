package dev.memoh.feature.bots

import android.content.Context
import java.net.URI
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.animateContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohFormDialog
import dev.memoh.core.designsystem.component.MemohActionButton
import kotlinx.serialization.json.*

// Public gallery metadata from felinics/Memoh, 110635b, model-sources.ts.
internal fun loadProviderSources(context: Context): List<JsonObject> =
    context.assets.open("provider-sources.json").bufferedReader().use { Json.parseToJsonElement(it.readText()).objects() }

private val legacySources = mapOf("anthropic" to "anthropic", "cerebras" to "cerebras", "cloudflare" to "cloudflare-workers-ai",
    "deepseek" to "deepseek", "fireworks" to "fireworks-ai", "google" to "google", "groq" to "groq", "huggingface" to "huggingface",
    "minimax" to "minimax", "mistral" to "mistral", "moonshot" to "moonshotai-cn", "openai" to "openai", "opencode-go" to "opencode-go",
    "openrouter" to "openrouter", "perplexity" to "perplexity", "qwen" to "alibaba-cn", "together" to "togetherai", "xai" to "xai", "zai" to "zai", "zhipu" to "zhipuai")

internal fun providerManaged(provider: JsonObject): Boolean = provider["metadata"].obj().let {
    it.flag("managed") || it.text("source").trim().equals("builtin", true)
}

internal fun providerCatalogSource(provider: JsonObject): String? {
    if (providerManaged(provider)) return null
    val metadata = provider["metadata"].obj()
    metadata.text("source").trim().takeIf(String::isNotBlank)?.let { return it }
    return listOf("preset", "registry").firstNotNullOfOrNull { key ->
        legacySources[metadata[key].obj().text("source").trim().removeSuffix(".yaml")]
    }
}

internal fun providerImportPath(provider: JsonObject, fromEndpoint: Boolean = false): List<String> {
    require(!providerManaged(provider)) { "此供应商由平台管理，无法手动导入模型。" }
    return listOf("providers", provider.text("id"), if (!fromEndpoint && providerCatalogSource(provider) != null) "import-catalog-models" else "import-models")
}

internal fun sourceMetadata(source: JsonObject): JsonObject = buildJsonObject {
    put("catalog", source.text("id"))
    source.text("source").takeIf(String::isNotBlank)?.let { put("source", it) }
}

internal fun providerUsesOAuth(clientType: String): Boolean = clientType in listOf("openai-codex", "github-copilot")
internal fun providerNeedsApiKey(source: JsonObject): Boolean = !providerUsesOAuth(source.text("client_type")) &&
    runCatching { URI(source.text("base_url")).host !in listOf("localhost", "127.0.0.1") }.getOrDefault(true)
internal fun providerProtocolLabel(value: String): String = when (value) {
    "openai-completions" -> "OpenAI Chat Completions"; "openai-responses" -> "OpenAI Responses"
    "anthropic-messages" -> "Anthropic Messages"; "google-generative-ai" -> "Google Gemini"
    "openai-codex" -> "ChatGPT / Codex"; "github-copilot" -> "GitHub Copilot"; "opencode-go" -> "OpenCode Go"; else -> value
}

internal fun providerIsSubscription(provider: JsonObject, sources: List<JsonObject>): Boolean {
    val base = provider["config"].obj().text("base_url").trim().trimEnd('/')
    return sources.any { source -> source.flag("subscription") && source.text("client_type") == provider.text("client_type") &&
        (source.text("base_url").isBlank() || base.isBlank() || base.equals(source.text("base_url").trimEnd('/'), true)) }
}

@Composable
internal fun ProviderSourcePicker(sources: List<JsonObject>, onSelect: (JsonObject) -> Unit, onCustom: () -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(sources, query) { sources.filter { query.isBlank() ||
        (it.text("name") + it.text("id") + it.text("client_type")).contains(query, true) } }
    MemohFormDialog("接入服务商", onDismiss, confirmButton = { TextButton(onCustom) { Text("自定义接入") } }, dismissButton = { TextButton(onDismiss) { Text("关闭") } }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("查找服务商") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) })
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 460.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                listOf(true, false).forEach { subscription ->
                    val group = filtered.filter { it.flag("subscription") == subscription }
                    if (group.isNotEmpty()) item("heading-$subscription") {
                        Text(if (subscription) "订阅服务" else "其他 LLM 服务商", Modifier.padding(12.dp), style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    itemsIndexed(group, key = { _, source -> source.text("id") }) { index, source ->
                        SegmentedListItem(onClick = { onSelect(source) }, shapes = ListItemDefaults.segmentedShapes(index, group.size),
                            leadingContent = { Icon(if (subscription) Icons.Filled.AccountCircle else Icons.Filled.Cloud, null) },
                            supportingContent = { Text(if (providerUsesOAuth(source.text("client_type"))) "账号授权" else if (providerNeedsApiKey(source)) "API key" else "本地服务", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall) }) { Text(source.text("name"), style = MaterialTheme.typography.titleSmall) }
                    }
                }
                if (filtered.isEmpty()) item { Text("没有匹配的服务商", Modifier.padding(16.dp)) }
            }
        }
    })
}

@Composable
internal fun ProviderCatalogDialog(provider: JsonObject, data: JsonObject?, busy: Boolean, error: String?,
    onRetry: () -> Unit, onImport: () -> Unit, onDismiss: () -> Unit) {
    var query by remember(provider.text("id")) { mutableStateOf("") }
    val entries = data?.get("models").objects().filter { query.isBlank() || (it.text("name") + it.text("model_id")).contains(query, true) }
    MemohFormDialog("${provider.text("name")} · 模型目录", onDismiss, confirmButton = {
        TextButton(onImport, enabled = !busy && data != null && data["models"].objects().isNotEmpty()) { Text("导入模型") }
    }, dismissButton = { TextButton(onDismiss) { Text("关闭") } }, text = {
        Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.fillMaxWidth().height(WavyProgressIndicatorDefaults.LinearContainerHeight)) { if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth()) }
            if (data?.flag("stale") == true) Text("暂时无法刷新，正在显示缓存目录。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            if (error != null) { Text(error, color = MaterialTheme.colorScheme.error); TextButton(onRetry, enabled = !busy) { Text("重试") } }
            if (data != null) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("查找模型") }, singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, null) })
                LazyColumn(Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 400.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    itemsIndexed(entries, key = { _, model -> model.text("model_id") }) { index, model ->
                        val config = model["config"].obj()
                        ManagementRow(model.text("name").ifBlank { model.text("model_id") },
                            listOf(model.text("model_id"), config.text("context_window").takeIf(String::isNotBlank)?.let { "$it tokens" }).filterNotNull().joinToString(" · "),
                            Icons.Filled.ModelTraining, index, entries.size)
                    }
                    if (entries.isEmpty()) item { Text(if (query.isBlank()) "目录中暂无模型" else "没有匹配的模型", Modifier.padding(16.dp)) }
                }
                Text("导入后选择需要启用的模型。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    })
}

@Composable
internal fun ProviderModelsDialog(provider: JsonObject, allModels: List<JsonObject>, busy: Boolean, error: String?, notice: String?,
    onSave: (List<JsonObject>, Set<String>) -> Unit, onReload: () -> Unit, onDiscover: () -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val models = allModels.filter { it.text("provider_id") == provider.text("id") }
    val enabled = models.filter { it.flag("enable", true) }.map { it.text("id") }.toSet()
    var selected by remember(provider.text("id"), models) { mutableStateOf(enabled) }
    var query by remember(provider.text("id")) { mutableStateOf("") }
    val visible = models.filter { query.isBlank() || (it.text("name") + it.text("model_id")).contains(query, true) }
    MemohFormDialog("选择模型", { if (!busy) onDismiss() }, confirmButton = {
        MemohActionButton(if (selected == enabled) "完成" else "保存选择（${selected.size}）", Icons.Filled.Check,
            { onSave(models, selected) }, enabled = !busy && models.isNotEmpty(), primary = true)
    }, dismissButton = { TextButton(onDismiss, enabled = !busy) { Text("取消") } }, text = {
        Column(Modifier.animateContentSize(MaterialTheme.motionScheme.defaultSpatialSpec()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(provider.text("name"), style = MaterialTheme.typography.bodyMedium)
            Box(Modifier.fillMaxWidth().height(WavyProgressIndicatorDefaults.LinearContainerHeight)) { if (busy) LinearWavyProgressIndicator(Modifier.fillMaxWidth()) }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!providerManaged(provider)) {
                    TextButton(onReload, enabled = !busy) { Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (models.isEmpty()) "获取模型" else "重新获取") }
                    TextButton(onSettings, enabled = !busy) { Text("连接设置") }
                }
            }
            if (error != null && providerCatalogSource(provider) != null && !providerManaged(provider)) {
                TextButton(onDiscover, enabled = !busy) { Text("从接口发现模型") }
            }
            if (models.size > 6 || query.isNotBlank()) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("查找模型") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) })
            LazyColumn(Modifier.fillMaxWidth().heightIn(min = if (busy && models.isEmpty()) 96.dp else 0.dp, max = 380.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                itemsIndexed(visible, key = { _, model -> model.text("id") }) { index, model ->
                    val id = model.text("id")
                    val available = model["config"].obj()["catalog_available"] != JsonPrimitive(false)
                    SegmentedListItem(onClick = { selected = if (id in selected) selected - id else selected + id }, enabled = !busy && available,
                        shapes = ListItemDefaults.segmentedShapes(index, visible.size),
                        supportingContent = { Text((if (!available) "暂不可用 · " else "") + model.text("model_id"), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        trailingContent = { Checkbox(id in selected, { checked -> selected = if (checked) selected + id else selected - id }, enabled = !busy && available) }) {
                        Text(model.text("name").ifBlank { model.text("model_id") }, style = MaterialTheme.typography.titleSmall)
                    }
                }
                if (!busy && error == null && visible.isEmpty()) item { Text(if (models.isEmpty()) "暂无可选模型" else "没有匹配的模型", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    })
}
