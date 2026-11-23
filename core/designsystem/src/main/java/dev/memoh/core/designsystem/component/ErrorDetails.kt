package dev.memoh.core.designsystem.component

import android.content.ClipData
import android.content.ClipDescription
import android.os.PersistableBundle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ButtonGroupScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilledTonalToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.memoh.core.model.MemohError
import kotlinx.coroutines.launch

@Composable
fun ErrorDetails(
    problem: MemohError?,
    summary: String,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit = {},
    actions: (ButtonGroupScope.() -> Unit)? = null,
) {
    val ids = remember(problem?.requestId, problem?.traceId) {
        listOfNotNull(
            problem?.requestId?.takeIf(String::isNotBlank)?.let { "请求 ID  $it" },
            problem?.traceId?.takeIf(String::isNotBlank)?.let { "追踪 ID  $it" },
        )
    }
    val detail = problem?.upstreamDetail
    val hasDetails = detail != null || ids.isNotEmpty()
    if (!hasDetails && actions == null) return

    var expanded by remember(problem) { mutableStateOf(false) }
    var copied by remember(problem) { mutableStateOf(false) }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    val colors = MaterialTheme.colorScheme
    val label = if (expanded) "收起详情" else "错误详情"
    val toggle: (Boolean) -> Unit = { onToggle(); expanded = it; copied = false }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ButtonGroup(overflowIndicator = { ButtonGroupDefaults.OverflowIndicator(it) }) {
            if (hasDetails) customItem(
                buttonGroupContent = {
                    val interaction = remember { MutableInteractionSource() }
                    FilledTonalToggleButton(
                        checked = expanded, onCheckedChange = toggle, interactionSource = interaction,
                        modifier = Modifier.animateWidth(interaction),
                        colors = ToggleButtonDefaults.colors(
                            containerColor = colors.secondaryContainer, contentColor = colors.onSecondaryContainer,
                            checkedContainerColor = colors.secondaryContainer, checkedContentColor = colors.onSecondaryContainer,
                        ),
                    ) {
                        Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null,
                            Modifier.size(ToggleButtonDefaults.IconSize))
                        Spacer(Modifier.width(ToggleButtonDefaults.IconSpacing))
                        Text(label)
                    }
                },
                menuContent = { menu ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { menu.dismiss(); toggle(!expanded) })
                },
            )
            actions?.invoke(this)
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(motion.fastSpatialSpec(), expandFrom = Alignment.Top) +
                fadeIn(motion.fastEffectsSpec()),
            exit = shrinkVertically(motion.fastSpatialSpec(), shrinkTowards = Alignment.Top) +
                fadeOut(motion.fastEffectsSpec()),
        ) {
            Row(
                Modifier.fillMaxWidth().clip(MaterialTheme.shapes.large).background(colors.surfaceContainerHighest)
                    .then(if (expanded) Modifier else Modifier.clearAndSetSemantics {}).padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f).padding(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    detail?.let {
                        Text(it, Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState()),
                            style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                            color = colors.onSurface)
                    }
                    ids.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                }
                FilledTonalIconButton(
                    enabled = expanded,
                    shapes = IconButtonDefaults.shapes(),
                    onClick = {
                        scope.launch {
                            val report = (listOfNotNull(summary.takeIf(String::isNotBlank), detail) + ids).joinToString("\n")
                            val clip = ClipData.newPlainText("Memoh", report)
                            if (detail != null) clip.description.extras = PersistableBundle().apply {
                                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
                            }
                            clipboard.setClipEntry(ClipEntry(clip))
                            copied = true
                        }
                    },
                ) {
                    Crossfade(copied, animationSpec = motion.fastEffectsSpec(), label = "error details copied") { done ->
                        Icon(if (done) Icons.Filled.Check else Icons.Filled.ContentCopy, if (done) "已复制" else "复制错误")
                    }
                }
            }
        }
    }
}
