package dev.memoh.feature.chat.components

import androidx.activity.compose.BackHandler
import dev.memoh.core.designsystem.motion.MemohMotion
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ManageSearch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohPopupMenu
import dev.memoh.core.model.ContextUsage
import dev.memoh.feature.chat.ChatUiState
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun SessionContextIndicator(state: ChatUiState, onRefresh: () -> Unit, onInspect: () -> Unit = {}) {
    var open by remember(state.session?.id) { mutableStateOf(false) }
    BackHandler(open) { open = false }
    val usage = state.sessionInfo?.contextUsage ?: ContextUsage()
    val window = state.sessionInfo?.contextUsage?.window(state.modelContextWindow)
    val fraction = window?.let { usage.tokens.toDouble() / it }
    val percent = (fraction ?: 0.0) * 100
    val roundedPercent = percent.roundToInt()
    val colors = MaterialTheme.colorScheme
    val tint = if (percent >= 70) contextPressureColor(percent) else colors.onSurfaceVariant
    val progress by animateFloatAsState((fraction?.coerceIn(0.0, 1.0) ?: 0.0).toFloat(),
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "contextUsage")
    Box {
        TextButton(onClick = { open = !open; if (open) onRefresh() }, enabled = state.session != null,
            shape = MaterialTheme.shapes.large, contentPadding = PaddingValues(horizontal = 8.dp),
            colors = ButtonDefaults.textButtonColors(contentColor = tint,
                containerColor = if (open) colors.surfaceContainerHighest else Color.Transparent),
            modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 48.dp).semantics {
                contentDescription = if (window == null) "会话信息，点击查看上下文"
                    else "会话信息，上下文已用 $roundedPercent%"
            }) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(16.dp),
                    color = tint, trackColor = tint.copy(alpha = .4f), strokeWidth = 2.dp, strokeCap = StrokeCap.Round)
                AnimatedContent(if (window != null) "$roundedPercent%" else "--",
                    transitionSpec = {
                        (fadeIn(MemohMotion.enterSpec()) togetherWith
                            fadeOut(MemohMotion.exitSpec()))
                            .using(SizeTransform { _, _ -> MemohMotion.enterSpec() })
                    }, label = "context percentage") { label ->
                    Text(label, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
        MemohPopupMenu(open, { open = false }, Alignment.BottomEnd, width = 320.dp, maxHeight = 320.dp, aboveAnchor = true) {
            Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (window != null) Text("$roundedPercent%", style = MaterialTheme.typography.titleMedium, color = contextPressureColor(percent))
                    val estimated = if (usage.estimatedTokens != null) "~" else ""
                    Text(if (state.sessionInfo == null) "正在加载上下文" else "$estimated${formatTokens(usage.tokens)} / ${window?.let(::formatTokens) ?: "--"}",
                        Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton({ open = false; onInspect() }, modifier = Modifier.size(32.dp), enabled = state.session != null) {
                        Icon(Icons.Outlined.ManageSearch, "上下文检查器", Modifier.size(20.dp))
                    }
                }
                if (state.sessionInfo == null && state.sessionInfoLoading) LinearWavyProgressIndicator(Modifier.fillMaxWidth())
                if (window != null) ContextWaffle(usage, state.modelContextWindow)
                ContextLegend(usage)
                if (percent >= 70 && usage.autoCompactTokens != null) {
                    Text("快满了，较早的对话会被自动整理", color = contextPressureColor(percent), style = MaterialTheme.typography.bodyMedium)
                }
                state.sessionInfoError?.let {
                    Text("用量暂不可用：$it", style = MaterialTheme.typography.bodySmall, color = colors.error)
                    TextButton(onRefresh) { Text("重试") }
                }
            }
        }
    }
}

internal fun formatTokens(tokens: Long): String = when {
    tokens >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1000 -> String.format(Locale.ROOT, "%.1fK", tokens / 1000.0)
    else -> tokens.toString()
}
