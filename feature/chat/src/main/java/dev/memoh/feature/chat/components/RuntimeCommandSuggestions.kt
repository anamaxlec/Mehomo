package dev.memoh.feature.chat.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.AdminPanelSettings
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.motion.MemohMotion
import dev.memoh.core.model.RuntimeCommand
import dev.memoh.core.model.SkillCatalogItem
import dev.memoh.feature.chat.nativeSkillSuggestions
import dev.memoh.feature.chat.runtimeCommandSuggestions
import dev.memoh.feature.chat.slashMenuQuery
import dev.memoh.feature.chat.ComposerQuickAction
import dev.memoh.feature.chat.quickActionSuggestions

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun RuntimeCommandSuggestions(
    text: String,
    commands: List<RuntimeCommand>,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    skills: List<SkillCatalogItem> = emptyList(),
    nativeSkills: Boolean = false,
    loading: Boolean = false,
    error: String? = null,
    onRetry: () -> Unit = {},
    quickActions: List<ComposerQuickAction> = emptyList(),
    quickActionEnabled: (ComposerQuickAction) -> Boolean = { true },
    onQuickAction: (ComposerQuickAction) -> Unit = {},
) {
    val visible = enabled && slashMenuQuery(text) != null
    // Keep the outgoing rows until the exit finishes instead of replacing them
    // with an empty state as soon as the selector is completed or deleted.
    var lastSelector by remember { mutableStateOf(text) }
    SideEffect { if (visible) lastSelector = text }
    val selector = if (visible) text else lastSelector
    val suggestions = remember(selector, commands) { runtimeCommandSuggestions(selector, commands) }
    val nativeSuggestions = remember(selector, skills) { nativeSkillSuggestions(selector, skills) }
    val shortcuts = remember(selector, quickActions) { quickActionSuggestions(selector, quickActions) }
    AnimatedVisibility(visible,
        // Reserve height immediately. Animate the layer from its bottom edge;
        // growing the layout would remeasure the conversation every frame.
        enter = fadeIn(MemohMotion.enterSpec()) + scaleIn(MemohMotion.enterSpec(),
            initialScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 1f)),
        exit = fadeOut(MemohMotion.exitSpec()) + scaleOut(MemohMotion.exitSpec(),
            targetScale = 0.98f, transformOrigin = TransformOrigin(0.5f, 1f))) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.extraLarge,
            modifier = Modifier.fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 336.dp).semantics { paneTitle = "命令与技能" },
                contentPadding = PaddingValues(8.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                if (shortcuts.isNotEmpty()) {
                    item("quick-actions", contentType = "quick-actions") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            MenuSectionLabel("快捷操作", Modifier.padding(top = 6.dp))
                            // Minimum width grows with text size, allowing a single
                            // column on narrow windows or with accessibility fonts.
                            val minimumWidth = 128.dp * LocalDensity.current.fontScale
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
                                shortcuts.forEach { action ->
                                    FilledTonalButton(onClick = { onQuickAction(action) }, enabled = visible && quickActionEnabled(action),
                                        shape = MaterialTheme.shapes.medium,
                                        modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                            .widthIn(min = minimumWidth),
                                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)) {
                                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Icon(action.icon, null, Modifier.size(18.dp))
                                            Text(action.label, style = MaterialTheme.typography.labelLarge,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                if (loading) item("loading") {
                    LinearWavyProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .semantics { contentDescription = if (nativeSkills) "正在加载 Skills" else "正在加载命令" })
                }
                listOf(false, true).forEach { skill ->
                    val group = suggestions.filter { it.skill == skill }
                    if (group.isNotEmpty()) {
                        item("group-$skill") {
                            MenuSectionLabel(if (skill) "Skills" else "命令", Modifier.padding(top = 8.dp))
                        }
                        itemsIndexed(group, key = { _, command -> "command-$skill-${command.name}" }) { index, command ->
                            SegmentedListItem(onClick = { onSelect("/${command.name} ") }, enabled = visible,
                                shapes = ListItemDefaults.segmentedShapes(index, group.size),
                                leadingContent = { Icon(if (skill) Icons.Outlined.Extension else Icons.Outlined.Terminal,
                                    null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary) },
                                supportingContent = (command.description ?: command.inputHint)?.takeIf(String::isNotBlank)?.let { description ->
                                    { Text(description, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis) }
                                }) {
                                Text("/${command.name}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
                if (nativeSuggestions.isNotEmpty()) {
                    item("native-skills-heading") {
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Skills", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            IconButton(onClick = onRetry, enabled = visible && !loading) {
                                Icon(Icons.Outlined.Refresh, "刷新 Skills", Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    itemsIndexed(nativeSuggestions, key = { _, skill -> "native-skill-${skill.name}" }) { index, skill ->
                        SegmentedListItem(onClick = { onSelect("/${skill.name} ") }, enabled = visible,
                            shapes = ListItemDefaults.segmentedShapes(index, nativeSuggestions.size),
                            leadingContent = { Icon(Icons.Outlined.Extension, null, Modifier.size(18.dp),
                                tint = MaterialTheme.colorScheme.primary) },
                            supportingContent = (skill.description ?: skill.displayName)?.takeIf(String::isNotBlank)?.let { description ->
                                { Text(description, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }) {
                            Text("/${skill.name}", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleSmall)
                        }
                    }
                }
                if (error != null) item("error") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = onRetry, enabled = visible) { Text("重新加载") }
                    }
                }
                else if (!loading && suggestions.isEmpty() && nativeSuggestions.isEmpty() &&
                    (shortcuts.isEmpty() || nativeSkills && slashMenuQuery(selector).isNullOrEmpty())) item("empty") {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(if (slashMenuQuery(selector).isNullOrEmpty()) {
                            if (nativeSkills) "暂无可用的 Skill" else "暂无可用的命令"
                        } else "没有匹配的命令或 Skill", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onRetry, enabled = visible) { Text("重新加载") }
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private val ComposerQuickAction.icon: ImageVector
    get() = when (this) {
        ComposerQuickAction.Help -> Icons.Outlined.HelpOutline
        ComposerQuickAction.Skills -> Icons.Outlined.Extension
        ComposerQuickAction.New -> Icons.Outlined.AddComment
        ComposerQuickAction.Model -> Icons.Outlined.Tune
        ComposerQuickAction.Permission -> Icons.Outlined.AdminPanelSettings
        ComposerQuickAction.Plan -> Icons.Outlined.Route
        ComposerQuickAction.Goal -> Icons.Outlined.Flag
    }
