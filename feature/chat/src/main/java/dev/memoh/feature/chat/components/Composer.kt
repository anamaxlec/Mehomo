package dev.memoh.feature.chat.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.activity.compose.BackHandler
import dev.memoh.core.designsystem.motion.MemohMotion
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.memoh.core.designsystem.component.LocalMemohPopupFocusable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import dev.memoh.core.designsystem.component.MemohComposerSurface
import dev.memoh.core.designsystem.component.MemohComposerTextField
import dev.memoh.core.designsystem.component.MemohComposerIconButton
import dev.memoh.core.designsystem.component.MemohSkeleton
import dev.memoh.core.designsystem.component.MemohSkeletonBlock
import dev.memoh.core.designsystem.component.MemohMenuRow
import dev.memoh.core.designsystem.component.MemohPopupMenu
import dev.memoh.core.designsystem.component.LocalMemohPopupBounds
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * The composer.
 *
 * Structure follows the web client: one rounded field that holds the text and,
 * on its bottom line, the attach control, the model selector, and send. The
 * capability selectors that qualify *where and how* the message runs — computer,
 * folder, agent — sit below the field rather than inside it, because they are
 * not part of writing the message and mixing them into the same row makes the
 * field look like a toolbar.
 *
 * Capability controls keep their place while their options load.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalLayoutApi::class)
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: (openModelMenu: () -> Unit) -> Unit,
    onStop: () -> Unit,
    isRunning: Boolean,
    /** Controls (attach, model, capability) are usable. */
    enabled: Boolean,
    modifier: Modifier = Modifier,
    /**
     * The message can actually be delivered.
     *
     * Separate from [enabled] because the two answer different questions: a
     * reconnecting socket makes sending impossible while every control still
     * works, and collapsing them left the composer inert for no visible reason.
     */
    sendEnabled: Boolean = enabled,
    hasAttachments: Boolean = false,
    attachmentLoading: Boolean = false,
    /** Files are managed inside the same surface as the editor. */
    attachments: @Composable () -> Unit = {},
    /** Queue chips, decisions and run status above the composer. */
    above: @Composable (openModelMenu: () -> Unit) -> Unit = {},
    onAttachClick: (() -> Unit)? = null,
    /** The model and reasoning effort in use, shown inside the field. */
    modelLabel: String? = null,
    modelLoading: Boolean = false,
    onModelClick: (() -> Unit)? = null,
    /** Capability selectors below the field: computer, folder, agent. */
    capabilities: List<ComposerCapability> = emptyList(),
    contextIndicator: @Composable () -> Unit = {},
    /**
     * Menu content for the + button.
     *
     * Passed as a slot rather than opened by the caller because a `Popup`
     * positions itself against *its own parent*: it has to live inside the
     * button's Box to unfold from the button. The button owns the open state,
     * so the caller cannot leave it stuck open.
     */
    plusMenu: (@Composable (expanded: Boolean, dismiss: () -> Unit) -> Unit)? = null,
    /** Menu content for the model selector; same anchoring rule. */
    modelMenu: (@Composable (expanded: Boolean, dismiss: () -> Unit) -> Unit)? = null,
    sendDescription: String = "发送",
    textEnabled: Boolean = enabled,
    showStop: Boolean = isRunning && text.isBlank() && !hasAttachments && !attachmentLoading,
    /** Keep selector entry compact while the slash menu already supplies its controls. */
    compactForSuggestions: Boolean = false,
) {
    var popupBounds by remember { mutableStateOf<IntRect?>(null) }
    var editorFocused by remember { mutableStateOf(false) }
    var plusOpen by remember { mutableStateOf(false) }
    var modelOpen by remember { mutableStateOf(false) }
    val openModelMenu = { plusOpen = false; modelOpen = true }
    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    // A hidden keyboard must not reopen just because the window regains focus.
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) {
        if (!imeVisible) focusManager.clearFocus(force = true)
    }
    var imeWasVisible by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible, editorFocused) {
        if (!editorFocused) imeWasVisible = false
        else if (imeVisible) imeWasVisible = true
    }
    val hasAttachmentArea = hasAttachments || attachmentLoading
    val editorExpanded = hasAttachmentArea || !compactForSuggestions &&
        (text.isNotBlank() || editorFocused && (imeVisible || !imeWasVisible))
    val animatedExpansion by animateFloatAsState(if (editorExpanded) 1f else 0f,
        MaterialTheme.motionScheme.defaultSpatialSpec(), label = "composerExpansion")
    val expansion = animatedExpansion.coerceIn(0f, 1f)
    BackHandler(plusOpen) { plusOpen = false }
    BackHandler(modelOpen) { modelOpen = false }
    CompositionLocalProvider(
        LocalMemohPopupBounds provides popupBounds,
        LocalMemohPopupFocusable provides false,
    ) {
        Column(
            modifier = modifier.fillMaxWidth().onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInWindow()
                popupBounds = IntRect(
                    bounds.left.toInt(), bounds.top.toInt(),
                    bounds.right.toInt(), bounds.bottom.toInt(),
                )
            },
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Measure the editor and its controls first. The menu gets the
            // remaining height when the keyboard or large text reduces space.
            Box(Modifier.weight(1f, fill = false)) { above(openModelMenu) }

            MemohComposerSurface(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)) {
                AnimatedVisibility(
                    visible = hasAttachmentArea,
                    enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                        fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                    exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                        fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                ) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) { attachments() }
                }
                // One editor stays composed in both layouts, preserving text,
                // selection and IME focus while controls move to the bottom row.
                Box(Modifier.fillMaxWidth()) {
                    val compactStart = if (onAttachClick != null) 48.dp else 0.dp
                    val compactEnd = if (onModelClick != null) 102.dp else 48.dp
                    MemohComposerTextField(
                        value = text, onValueChange = onTextChange, enabled = textEnabled,
                        placeholder = if (isRunning) "追加到后续队列…" else "问点什么",
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = compactStart * (1f - expansion), end = compactEnd * (1f - expansion),
                                bottom = 48.dp * expansion)
                            .heightIn(min = if (hasAttachmentArea) 80.dp else 48.dp,
                                max = if (hasAttachmentArea) 220.dp else 48.dp + 172.dp * expansion)
                            .onFocusChanged { editorFocused = it.isFocused },
                    )
                    Row(Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomCenter),
                        verticalAlignment = Alignment.CenterVertically) {
                        if (onAttachClick != null) Box {
                            MemohComposerIconButton(
                                icon = Icons.Filled.Add,
                                contentDescription = "添加文件或应用",
                                enabled = enabled,
                                onClick = { modelOpen = false; plusOpen = !plusOpen },
                            )
                            plusMenu?.invoke(plusOpen) { plusOpen = false }
                        }
                        if (onModelClick != null) {
                            Box(Modifier.weight(1f).heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) {
                                Box {
                                ModelSelector(
                                        label = modelLabel,
                                        loading = modelLoading,
                                        onClick = { plusOpen = false; modelOpen = !modelOpen },
                                        enabled = enabled && modelLabel != null,
                                        expansion = expansion,
                                    )
                                modelMenu?.invoke(modelOpen) { modelOpen = false }
                                }
                            }
                            Spacer(Modifier.width(6.dp))
                        } else Spacer(Modifier.weight(1f))

                        val canPressSend = sendEnabled && (showStop || text.isNotBlank() || hasAttachments)
                        MemohComposerIconButton(
                            icon = if (showStop) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                            contentDescription = if (showStop) "停止生成" else sendDescription,
                            enabled = canPressSend,
                            filled = true,
                            danger = showStop,
                            onClick = { if (showStop) onStop() else onSend(openModelMenu) },
                        )
                    }
                }
            }

            if (capabilities.isNotEmpty()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    CapabilityRow(capabilities, Modifier.weight(1f))
                    contextIndicator()
                }
            }
        }
    }
}

/** The model and effort, shown as one tappable label rather than two controls. */
@Composable
private fun ModelSelector(
    label: String?,
    loading: Boolean,
    onClick: () -> Unit,
    enabled: Boolean,
    expansion: Float = 1f,
) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp).widthIn(min = 48.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Keep the Tune control in place, even before the catalogue is available.
        // Only the expanded label needs a placeholder or a content transition.
        AnimatedContent(
            targetState = label ?: if (loading) null else "暂无可用模型",
            transitionSpec = {
                (fadeIn(MemohMotion.enterSpec()) togetherWith
                    fadeOut(MemohMotion.exitSpec()))
                    .using(SizeTransform { _, _ -> MemohMotion.enterSpec() })
            }, label = "model label",
            modifier = Modifier.weight(1f, fill = false).clipToBounds().layout { measurable, constraints ->
                val label = measurable.measure(constraints)
                layout((label.width * expansion).roundToInt(), label.height) { label.placeRelative(0, 0) }
            }.graphicsLayer { alpha = ((expansion - .35f) / .65f).coerceIn(0f, 1f) },
        ) { displayed ->
            if (displayed == null) MemohSkeleton(description = "正在加载模型") {
                MemohSkeletonBlock(Modifier.width(154.dp).height(18.dp), MaterialTheme.shapes.large)
            } else Text(displayed, style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Crossfade(targetState = expansion >= .5f, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "modelControl") { expanded ->
            Icon(if (expanded) Icons.Filled.ArrowDropDown else Icons.Filled.Tune,
                if (expanded) null else if (label == null && loading) "模型选项加载中" else "模型：${label ?: "暂无可用模型"}", Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A capability selector: the computer to run on, the folder to work in, the
 * agent to use.
 *
 * [value] is null while the default applies, and the default is shown as the
 * label rather than as "none" — the user needs to know what *will* happen, not
 * that they have not chosen.
 */
data class ComposerCapability(
    val label: String,
    val icon: ImageVector,
    val value: String?,
    val options: List<CapabilityOption>,
    val onSelect: (String?) -> Unit,
    /** The option currently in effect, so the menu can mark it. */
    val selectedId: String? = null,
    /** Keep the control visible while its options are unavailable. */
    val enabled: Boolean = true,
    /** Keep the slot addressable until its enter/exit transition finishes. */
    val visible: Boolean = true,
)

/** One choice inside a [ComposerCapability]; a null [id] means "follow the default". */
data class CapabilityOption(
    val id: String?,
    val label: String,
)

@Composable
private fun CapabilityRow(capabilities: List<ComposerCapability>, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        capabilities.forEachIndexed { index, capability ->
            key(capability.label) {
                androidx.compose.animation.AnimatedVisibility(capability.visible,
                    enter = expandHorizontally(MemohMotion.enterSpec(), expandFrom = Alignment.Start) + fadeIn(MemohMotion.enterSpec()),
                    exit = shrinkHorizontally(MemohMotion.enterSpec(), shrinkTowards = Alignment.Start) + fadeOut(MemohMotion.exitSpec()),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Spacing collapses with the item instead of snapping away on the final frame.
                        if (index > 0) Spacer(Modifier.width(4.dp))
                        CapabilityChip(capability)
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityChip(capability: ComposerCapability, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    BackHandler(open && capability.visible) { open = false }

    Box(modifier) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.large)
                .clickable(enabled = capability.enabled && capability.visible, role = Role.Button,
                    onClickLabel = "选择${capability.label}") { open = true }
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = capability.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            AnimatedContent(
                targetState = capability.value ?: capability.label,
                modifier = Modifier.widthIn(max = 128.dp),
                contentAlignment = Alignment.CenterStart,
                transitionSpec = {
                    (fadeIn(MemohMotion.enterSpec()) togetherWith
                        fadeOut(MemohMotion.exitSpec()))
                        .using(SizeTransform { _, _ -> MemohMotion.enterSpec() })
                }, label = "capability value",
            ) { value ->
                Text(value, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }

        // The same popup the + and model menus use, so every menu in the
        // composer opens the same way. A bare DropdownMenu here rendered a
        // different shape, elevation and motion from its neighbours, which made
        // the row look like it belonged to a different app.
        MemohPopupMenu(
            expanded = open && capability.visible,
            onDismiss = { open = false },
            alignment = Alignment.BottomStart,
        ) {
            capability.options.forEachIndexed { index, option ->
                MemohMenuRow(
                    title = option.label,
                    index = index, count = capability.options.size,
                    icon = capability.icon,
                    selected = option.id == capability.selectedId,
                    selectable = true,
                    enabled = capability.enabled && capability.visible,
                    onClick = {
                        open = false
                        capability.onSelect(option.id)
                    },
                )
            }
        }
    }
}

/** The capability icons, so callers do not each pick their own. */
object ComposerIcons {
    val Computer: ImageVector get() = Icons.Filled.Computer
    val Folder: ImageVector get() = Icons.Filled.Folder
    val Agent: ImageVector get() = Icons.Filled.SmartToy
}

/** A removable chip used for attachments, queue items, and the workspace target. */
@Composable
fun ComposerChip(
    label: String,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onRemove != null) {
            Text(
                text = "×",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .clickable(onClick = onRemove)
                    .padding(horizontal = 4.dp),
            )
        }
    }
}

/**
 * A thin progress line for the current run's phase.
 *
 * This is the "glance at the phone and see where it is" affordance: the phase
 * name matters, the tool log does not.
 */
@Composable
fun RunPhaseLine(
    phase: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        androidx.compose.material3.LoadingIndicator(
            modifier = Modifier.size(14.dp),
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = phase,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The starter suggestions shown on an empty conversation. */
@Composable
fun PromptStarters(
    starters: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        starters.forEach { starter ->
            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraLarge)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onPick(starter) }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = starter,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
