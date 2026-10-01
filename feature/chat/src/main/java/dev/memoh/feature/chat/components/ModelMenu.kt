package dev.memoh.feature.chat.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.graphics.SolidColor
import dev.memoh.core.designsystem.component.MemohMenuRow
import dev.memoh.core.designsystem.component.MemohMenuSection
import dev.memoh.core.designsystem.component.MemohPopupMenu
import dev.memoh.core.model.ChatModel
import dev.memoh.core.model.ModelProvider
import dev.memoh.core.model.reasoningEffortLabel

private sealed interface CatalogRow {
    data class Provider(val name: String, val count: Int) : CatalogRow
    data class Model(val value: ChatModel, val index: Int, val count: Int) : CatalogRow
}

/** A bounded catalog, with model-specific effort controls outside the scrolling list. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalFoundationApi::class)
@Composable
fun ModelMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    models: List<ChatModel>,
    providers: List<ModelProvider> = emptyList(),
    selectedModelId: String?,
    reasoningEffort: String?,
    onSelectModel: (String) -> Unit,
    onSelectEffort: (String?) -> Unit,
    enabled: Boolean = true,
) {
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val searchFocus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val dismiss = { searching = false; onDismiss() }
    LaunchedEffect(expanded) { if (expanded) query = "" else searching = false }
    LaunchedEffect(searching) {
        if (searching) {
            androidx.compose.runtime.withFrameNanos { }
            searchFocus.requestFocus()
            keyboard?.show()
        }
    }
    val selectedModel = models.firstOrNull { it.id == selectedModelId } ?: models.firstOrNull()
    val providerNames = remember(providers) { providers.associate { it.id to it.name } }
    fun providerName(model: ChatModel) = providerNames[model.providerId]?.takeIf(String::isNotBlank)
        ?: model.providerName?.takeIf(String::isNotBlank) ?: "其他供应商"
    val filteredModels = remember(models, providerNames, query) {
        val search = query.trim()
        if (search.isEmpty()) models else models.filter {
            it.label.contains(search, ignoreCase = true) ||
                it.modelId.orEmpty().contains(search, ignoreCase = true) ||
                providerName(it).contains(search, ignoreCase = true)
        }
    }
    val rows = remember(filteredModels, providerNames) {
        filteredModels.groupBy { providerName(it) }.flatMap { (name, group) ->
            listOf<CatalogRow>(CatalogRow.Provider(name, group.size)) +
                group.mapIndexed { index, model -> CatalogRow.Model(model, index, group.size) }
        }
    }

    MemohPopupMenu(
        expanded = expanded,
        onDismiss = dismiss,
        alignment = Alignment.BottomEnd,
        width = 240.dp,
        maxHeight = 400.dp,
        scrollable = false,
        focusable = searching,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (models.size > 6) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                        .height(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .clickable(enabled = !searching) { searching = true }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!searching) Text("搜索模型或供应商", Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f).focusRequester(searchFocus),
                        textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        decorationBox = { input ->
                            Box {
                                if (query.isEmpty()) Text("搜索模型或供应商", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                input()
                            }
                        },
                    )
                    if (query.isNotEmpty()) IconButton(onClick = { query = "" }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, "清除搜索", Modifier.size(16.dp))
                    }
                }
            }

            val listState = rememberLazyListState()
            val headerGap = with(LocalDensity.current) { 2.dp.roundToPx() }
            LaunchedEffect(expanded, query, rows) {
                if (query.isBlank()) {
                    val selectedIndex = rows.indexOfFirst { it is CatalogRow.Model && it.value.id == selectedModel?.id }.coerceAtLeast(0)
                    val headerIndex = rows.take(selectedIndex + 1).indexOfLast { it is CatalogRow.Provider }.coerceAtLeast(0)
                    if (selectedIndex - headerIndex < 4) {
                        listState.scrollToItem(headerIndex)
                    } else {
                        listState.scrollToItem(selectedIndex)
                        androidx.compose.runtime.withFrameNanos { }
                        val provider = rows.getOrNull(headerIndex) as? CatalogRow.Provider
                        val headerHeight = listState.layoutInfo.visibleItemsInfo
                            .firstOrNull { it.key == "provider:${provider?.name}" }?.size ?: 0
                        listState.scrollToItem(selectedIndex, -headerHeight - headerGap)
                    }
                } else listState.scrollToItem(0)
            }
            if (filteredModels.isEmpty()) {
                Text(
                    text = if (models.isEmpty()) "暂无可用模型" else "没有匹配的模型",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    rows.forEach { row ->
                        when (row) {
                            is CatalogRow.Provider -> stickyHeader(key = "provider:${row.name}") {
                                Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)) {
                                    MemohMenuSection("${row.name} · ${row.count}")
                                }
                            }
                            is CatalogRow.Model -> item(key = "model:${row.value.id}") { MemohMenuRow(
                                title = row.value.label,
                                index = row.index, count = row.count,
                                icon = Icons.Filled.SmartToy,
                                selected = row.value.id == selectedModel?.id,
                                selectable = true,
                                enabled = enabled,
                                onClick = { onSelectModel(row.value.id); dismiss() },
                            ) }
                        }
                    }
                }
            }

            val efforts = selectedModel?.efforts.orEmpty()
            if (efforts.isNotEmpty()) {
                val selectedEffort = selectedModel?.resolveEffort(reasoningEffort)
                MemohMenuSection("思考强度")
                BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                    val density = LocalDensity.current
                    val measurer = rememberTextMeasurer()
                    val labelWidth = efforts.maxOf {
                        measurer.measure(reasoningEffortLabel(it), style = MaterialTheme.typography.labelLarge, maxLines = 1).size.width
                    }
                    val preferredWidth = (with(density) { labelWidth.toDp() } + 48.dp) * efforts.size +
                        4.dp * (efforts.size - 1)
                    ButtonGroup(
                        modifier = Modifier.horizontalScroll(rememberScrollState()).width(maxOf(maxWidth, preferredWidth)),
                        overflowIndicator = {},
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        efforts.forEach { effort ->
                            toggleableItem(
                                checked = effort == selectedEffort,
                                label = reasoningEffortLabel(effort),
                                onCheckedChange = { onSelectEffort(effort); dismiss() },
                                weight = 1f,
                                enabled = enabled,
                            )
                        }
                    }
                }
            }
        }
    }
}
