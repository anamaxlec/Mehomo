package dev.memoh.feature.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.ChatExampleGallery
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.designsystem.component.MemohListIcon
import dev.memoh.core.designsystem.motion.MemohMotion
import dev.memoh.core.model.*

@Composable
fun ConversationSuggestions(state: ChatUiState, onPrefill: (String) -> Unit, onRefresh: () -> Unit,
    onConfigure: (String) -> Unit, modifier: Modifier = Modifier) = key(state.session?.id) {
    var gallery by rememberSaveable { mutableStateOf(false) }
    var replacement by rememberSaveable { mutableStateOf<String?>(null) }
    val suggestions = updateTransition(state.starterPrompts.items, label = "conversation suggestions")
    fun pick(prompt: String) {
        val missing = chatExamples.firstOrNull { it.prompt == prompt }?.missing(state.exampleCapabilities).orEmpty()
        if (missing.isNotEmpty()) { onConfigure(missing.first()); return }
        if (state.draft.isNotBlank() && state.draft != prompt) replacement = prompt else onPrefill(prompt)
    }
    if (gallery) ChatExampleGallery(state.exampleCapabilities, onPick = { example ->
        gallery = false
        val missing = example.missing(state.exampleCapabilities)
        if (missing.isNotEmpty()) onConfigure(missing.first()) else pick(example.prompt)
    }, onDismiss = { gallery = false })
    replacement?.let { prompt -> AlertDialog(onDismissRequest = { replacement = null },
        title = { Text("替换当前草稿") }, text = { Text("输入框中已有内容，使用这个推荐会替换文字草稿。") },
        confirmButton = { TextButton({ replacement = null; onPrefill(prompt) }) { Text("替换") } },
        dismissButton = { TextButton({ replacement = null }) { Text("取消") } }) }
    Column(modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MemohListIcon(Icons.Filled.ChatBubbleOutline)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("开始对话", style = MaterialTheme.typography.headlineSmall)
                Text("选择一个建议，或写下你的问题", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // The slot remains measured when idle, so loading never moves the cards.
            Box(Modifier.fillMaxWidth().height(12.dp), contentAlignment = Alignment.Center) {
                // Local shuffling only shows progress while its card transition runs.
                // Background capability reads never change this transition's target.
                androidx.compose.animation.AnimatedVisibility(suggestions.isRunning || suggestions.currentState != suggestions.targetState,
                    enter = fadeIn(MemohMotion.enterSpec()), exit = fadeOut(MemohMotion.exitSpec())) {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "正在加载建议" })
                }
            }
            suggestions.AnimatedContent(
                transitionSpec = {
                    (fadeIn(MemohMotion.enterSpec()) togetherWith fadeOut(MemohMotion.exitSpec()))
                        .using(SizeTransform { _, _ -> MemohMotion.enterSpec() })
                }) { suggestions ->
                Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    suggestions.forEachIndexed { index, suggestion ->
                        SegmentedListItem(onClick = { pick(suggestion.prompt) }, enabled = state.canEditDraft,
                            shapes = ListItemDefaults.segmentedShapes(index, suggestions.size),
                            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                            supportingContent = {
                                Text(suggestion.prompt, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            },
                            trailingContent = { Icon(Icons.Filled.NorthWest, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            contentPadding = PaddingValues(16.dp),
                        ) { Text(suggestion.title, style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }
        }
        state.suggestionsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onRefresh, enabled = state.canEditDraft,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("换一组")
            }
            MemohActionButton("更多示例", Icons.Filled.Lightbulb, { gallery = true }, enabled = state.canEditDraft)
        }
    }
}
