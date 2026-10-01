package dev.memoh.feature.chat.components

import androidx.compose.animation.Crossfade
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.style.TextOverflow
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
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun Composer(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
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
    /** Rendered above the field: attachments, queue chips, decisions. */
    above: @Composable () -> Unit = {},
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
) {
    var popupBounds by remember { mutableStateOf<IntRect?>(null) }
    CompositionLocalProvider(LocalMemohPopupBounds provides popupBounds) {
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
            above()

            MemohComposerSurface(Modifier.fillMaxWidth()) {
                MemohComposerTextField(
                    value = text, onValueChange = onTextChange, enabled = enabled,
                    placeholder = if (isRunning) "补充到当前回复…" else "问点什么",
                    modifier = Modifier.fillMaxWidth(),
                )

                // The field's own bottom line: attach on the left, model and send on
                // the right. Keeping it inside the capsule is what makes the whole
                // thing read as one input rather than a text box with a toolbar.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (onAttachClick != null) {
                        var plusOpen by remember { mutableStateOf(false) }
                        Box {
                            MemohComposerIconButton(
                                icon = Icons.Filled.Add,
                                contentDescription = "添加文件或应用",
                                enabled = enabled,
                                onClick = { plusOpen = !plusOpen },
                            )
                            plusMenu?.invoke(plusOpen) { plusOpen = false }
                        }
                    }

                    if (onModelClick != null) {
                        var modelOpen by remember { mutableStateOf(false) }
                        Box(Modifier.weight(1f).heightIn(min = 40.dp), contentAlignment = Alignment.CenterEnd) {
                            Crossfade(modelLoading, animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(), label = "model loaded") { loading ->
                                if (loading) MemohSkeleton(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), "正在加载模型") {
                                    MemohSkeletonBlock(Modifier.width(172.dp).height(18.dp), MaterialTheme.shapes.large)
                                } else ModelSelector(
                                    label = modelLabel ?: "暂无可用模型",
                                    onClick = { modelOpen = !modelOpen },
                                    enabled = enabled && modelLabel != null,
                                )
                            }
                            modelMenu?.invoke(modelOpen) { modelOpen = false }
                        }
                        Spacer(Modifier.width(6.dp))
                    } else Spacer(Modifier.weight(1f))

                    // One button, two states: send when there is text, stop while a
                    // run is active. The icon swap is what tells the user what a tap
                    // does, so the two never coexist.
                    val showStop = isRunning && text.isBlank() && !hasAttachments
                    val canPressSend = sendEnabled && (showStop || text.isNotBlank() || hasAttachments)
                    MemohComposerIconButton(
                        icon = if (showStop) Icons.Filled.Stop else Icons.Filled.ArrowUpward,
                        contentDescription = if (showStop) "停止生成" else "发送",
                        enabled = canPressSend,
                        filled = true,
                        danger = showStop,
                        onClick = { if (showStop) onStop() else onSend() },
                    )
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
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Icon(
            imageVector = Icons.Filled.ArrowDropDown,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
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
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        capabilities.forEach { capability ->
            CapabilityChip(capability)
        }
    }
}

@Composable
private fun CapabilityChip(capability: ComposerCapability) {
    var open by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(MaterialTheme.shapes.medium)
                .clickable(enabled = capability.enabled) { open = true }
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
            Text(
                text = capability.value ?: capability.label,
                modifier = Modifier.widthIn(max = 128.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(
                imageVector = Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }

        // The same popup the + and model menus use, so every menu in the
        // composer opens the same way. A bare DropdownMenu here rendered a
        // different shape, elevation and motion from its neighbours, which made
        // the row look like it belonged to a different app.
        MemohPopupMenu(
            expanded = open,
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
