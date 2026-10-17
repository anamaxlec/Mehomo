package dev.memoh.core.designsystem.component

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.model.*

@Composable
fun ChatExampleGallery(capabilities: Map<String, Boolean>, onPick: (ChatExample) -> Unit, onDismiss: () -> Unit, loading: Boolean = false) {
    var category by rememberSaveable { mutableStateOf("all") }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("使用示例", style = MaterialTheme.typography.headlineSmall)
                    Text("选择一个示例，编辑后发送", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onDismiss) { Icon(Icons.Filled.Close, "关闭使用示例") }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                chatExampleCategories.forEach { (id, title) -> FilterChip(category == id, { category = id }, label = { Text(title) },
                    leadingIcon = if (category == id) ({ Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }) else null) }
            }
            if (loading) LoadingIndicator(Modifier.padding(20.dp).size(40.dp))
            else {
                val examples = chatExamples.filter { category == "all" || it.category == category }.sortedBy { it.missing(capabilities).size }
                LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), contentPadding = PaddingValues(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    itemsIndexed(examples, key = { _, it -> it.id }) { index, example ->
                        val missing = example.missing(capabilities)
                        SegmentedListItem(onClick = { onPick(example) }, shapes = ListItemDefaults.segmentedShapes(index, examples.size),
                            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                            leadingContent = { MemohListIcon(exampleIcon(example.category), if (missing.isEmpty()) ListIconTone.Secondary else ListIconTone.Tertiary) },
                            trailingContent = { Icon(if (missing.isEmpty()) Icons.Filled.NorthWest else Icons.Filled.Settings, null, Modifier.size(20.dp)) },
                            supportingContent = { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(example.prompt, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (missing.isNotEmpty()) Text("先配置${missing.joinToString("、", transform = ::exampleCapabilityLabel)}", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary)
                            } }, contentPadding = PaddingValues(16.dp),
                        ) { Text(example.title, style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }
        }
    }
}

private fun exampleIcon(category: String): ImageVector = when (category) {
    "research" -> Icons.Filled.Search
    "automation" -> Icons.Filled.Bolt
    "dev" -> Icons.Filled.Terminal
    "memory" -> Icons.Filled.Psychology
    "team" -> Icons.Filled.Groups
    else -> Icons.Filled.Edit
}
