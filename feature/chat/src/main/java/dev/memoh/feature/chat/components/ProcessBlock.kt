package dev.memoh.feature.chat.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.memoh.core.model.UIMessage
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** One process owns its disclosure state through streaming, regrouping and history refreshes. */
@Composable
fun ProcessBlock(
    messages: List<UIMessage>,
    isStreaming: Boolean,
    isRunActive: Boolean,
    modifier: Modifier = Modifier,
    onToggleDetails: () -> Unit = {},
) {
    if (messages.isEmpty()) return
    var expanded by rememberSaveable { mutableStateOf(false) }
    var openSteps by rememberSaveable { mutableStateOf(mapOf<String, Boolean>()) }
    val ordinals = mutableMapOf<String, Int>()
    val keys = messages.map { message ->
        val ordinal = ordinals.getOrDefault(message.type, 0)
        ordinals[message.type] = ordinal + 1
        message.toolCallId.takeIf(String::isNotBlank) ?: "${message.type}:$ordinal"
    }
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        if (messages.size == 1) {
            ProcessStep(messages.single(), isStreaming, isRunActive, expanded,
                onToggle = {
                    onToggleDetails()
                    expanded = !expanded
                    openSteps = openSteps + (keys.single() to expanded)
                }, onToggleDetails = onToggleDetails)
        } else {
            val (phase, details) = processSummary(messages, isStreaming)
            val running = isStreaming || isRunActive && messages.any { it.running }
            val totals = messages.filterNot(::toolResultFailed).map { diffCounts(it.diff) }
            DisclosureRow(expanded, onToggle = { onToggleDetails(); expanded = !expanded }) {
                ProcessLabel(phase, running, if (details.isEmpty()) Modifier.weight(1f, fill = false) else Modifier)
                if (details.isNotEmpty()) Text(details, Modifier.weight(1f, fill = false),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!running) DiffCounts(totals.sumOf { it.first }, totals.sumOf { it.second })
            }
            if (isStreaming && !expanded) {
                ProcessLabel(
                    if (messages.last().type == UIMessage.TYPE_REASONING) "正在思考如何处理" else toolDisplay(messages.last()).label,
                    active = false,
                    modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                )
            }
            ProcessDetails(expanded) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 10.dp, vertical = 4.dp)) {
                    messages.forEachIndexed { index, message ->
                        key(keys[index]) {
                            ProcessStep(message, isStreaming && index == messages.lastIndex, isRunActive,
                                expanded = openSteps[keys[index]] ?: false, inGroup = true,
                                onToggle = {
                                    onToggleDetails()
                                    openSteps = openSteps + (keys[index] to !(openSteps[keys[index]] ?: false))
                                }, onToggleDetails = onToggleDetails)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ReasoningCard(message: UIMessage, isStreaming: Boolean, modifier: Modifier = Modifier, onToggleDetails: () -> Unit = {}) {
    ProcessBlock(listOf(message), isStreaming, isStreaming, modifier, onToggleDetails)
}

@Composable
fun ToolCard(message: UIMessage, isRunActive: Boolean, modifier: Modifier = Modifier, onToggleDetails: () -> Unit = {}) {
    if (isCompletedQuestion(message)) AnsweredQuestion(message, modifier)
    else ProcessBlock(listOf(message), isRunActive && message.running, isRunActive, modifier, onToggleDetails)
}

@Composable
private fun ProcessStep(message: UIMessage, isStreaming: Boolean, isRunActive: Boolean, expanded: Boolean,
    onToggle: () -> Unit, inGroup: Boolean = false, onToggleDetails: () -> Unit = {}) {
    val files = patchFileDiffs(message)
    if (files.isNotEmpty()) {
        files.forEach { file -> key(file.path) { PatchFileStep(file, inGroup, onToggleDetails = onToggleDetails) } }
        return
    }
    val reasoning = message.type == UIMessage.TYPE_REASONING
    val running = if (reasoning) isStreaming else isRunActive && message.running
    val display = if (reasoning) null else toolDisplay(message)
    val title = display?.label ?: reasoningLabel(message, isStreaming)
    val expandable = !reasoning || message.content.isNotBlank()
    val failed = toolResultFailed(message)
    DisclosureRow(expanded, onToggle, enabled = expandable, compact = inGroup) {
        ProcessLabel(title, running, Modifier.weight(1f, fill = false), compact = inGroup)
        if (!reasoning && !failed) {
            val (add, remove) = diffCounts(message.diff)
            DiffCounts(add, remove)
        }
        val status = when (message.approval?.status) {
            "pending" -> "等待审核"; "rejected" -> "已拒绝"; "canceled", "cancelled" -> "已取消"; "expired" -> "已过期"
            else -> if (message.userInput?.isPending == true) "等待输入" else ""
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (running && status.isBlank()) message.elapsedTimeSeconds?.takeIf { it.isFinite() && it >= 0 }?.let {
            Text("执行中 · ${it.toInt()} 秒", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    ProcessDetails(expanded && expandable) {
        if (reasoning) SelectionContainer {
            Text(message.content.trim(), style = if (inGroup) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp))
        } else {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                .background(if (inGroup) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow)
                .padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ToolDetails(message)
            }
        }
    }
}

@Composable
private fun DisclosureRow(expanded: Boolean, onToggle: () -> Unit, enabled: Boolean = true, compact: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, tween(200), label = "processChevron")
    Row(Modifier.fillMaxWidth().heightIn(min = if (compact) 36.dp else 40.dp)
        .then(if (enabled) Modifier.semantics { stateDescription = if (expanded) "已展开" else "已收起" }
            .clickable(role = Role.Button, onClickLabel = if (expanded) "收起" else "展开", onClick = onToggle) else Modifier),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        content()
        if (enabled) Icon(Icons.Filled.ChevronRight, null, Modifier.size(16.dp).graphicsLayer { rotationZ = rotation },
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
    }
}

@Composable
private fun ProcessLabel(text: String, active: Boolean, modifier: Modifier = Modifier, compact: Boolean = false) {
    var shimmer by remember { mutableStateOf(false) }
    LaunchedEffect(active) { shimmer = false; if (active) { delay(250); shimmer = true } }
    val colors = MaterialTheme.colorScheme
    val style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium
    val textStyle: TextStyle = if (shimmer) {
        val position by rememberInfiniteTransition(label = "processShimmer").animateFloat(-200f, 800f,
            infiniteRepeatable(tween(1800), RepeatMode.Restart), label = "processHighlight")
        style.copy(brush = Brush.linearGradient(listOf(colors.onSurfaceVariant, colors.onSurface, colors.onSurfaceVariant),
            start = Offset(position, 0f), end = Offset(position + 160f, 0f)))
    } else style.copy(color = colors.onSurfaceVariant)
    Text(text, modifier, style = textStyle, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Keep a single body through rapid reversals, without spring overshoot or duplicate content. */
@Composable
private fun ProcessDetails(expanded: Boolean, content: @Composable ColumnScope.() -> Unit) {
    val reveal by animateFloatAsState(if (expanded) 1f else 0f,
        tween(220, easing = FastOutSlowInEasing), label = "processDetails")
    if (expanded || reveal > 0f) Column(Modifier.fillMaxWidth().clipToBounds()
        .then(if (expanded) Modifier else Modifier.clearAndSetSemantics { })
        .layout { measurable, constraints ->
            val placeable = measurable.measure(constraints.copy(minHeight = 0))
            layout(placeable.width, (placeable.height * reveal.coerceIn(0f, 1f)).roundToInt()) { placeable.placeRelative(0, 0) }
        }.padding(top = 2.dp, bottom = 4.dp), content = content)
}

@Composable
private fun DiffCounts(add: Int, remove: Int) {
    if (add > 0) Text("+$add", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = diffAdditionColor())
    if (remove > 0) Text("−$remove", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
        color = MaterialTheme.colorScheme.error)
}

@Composable
private fun diffAdditionColor(): Color = if (MaterialTheme.colorScheme.surface.luminance() < .5f) Color(0xFF89D49F) else Color(0xFF2E7D32)

@Composable
private fun PatchFileStep(file: ToolFileDiff, inGroup: Boolean, onToggleDetails: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val (add, remove) = diffCounts(file.diff)
    val filename = file.path.substringAfterLast('/')
    DisclosureRow(expanded, onToggle = { onToggleDetails(); expanded = !expanded }, enabled = file.diff.isNotBlank(), compact = inGroup) {
        ProcessLabel("${if (file.deleted) "删除" else if (file.oldPath.isBlank()) "新增" else "编辑"} $filename",
            active = false, Modifier.weight(1f, fill = false), compact = inGroup)
        DiffCounts(add, remove)
    }
    ProcessDetails(expanded && file.diff.isNotBlank()) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
            .background(if (inGroup) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainerLow).padding(10.dp)) {
            DetailText(file.diff, diff = true)
        }
    }
}

@Composable
private fun ToolDetails(message: UIMessage) {
    val input = message.input.objectValue()
    val result = toolResult(message)
    val command = input.string("command", "cmd")
    val failed = toolResultFailed(message)
    if (command.isNotBlank()) DetailText("$ $command")
    else if (input.isNotEmpty()) input.forEach { (name, value) ->
        val label = when (name) {
            "path", "file_path" -> "路径"; "query", "q", "keyword" -> "搜索"; "url" -> "网址"
            "line_offset", "lineOffset" -> "起始行"; "n_lines", "nLines" -> "行数"
            else -> name
        }
        val rendered = renderToolValue(value)
        if (rendered.isNotBlank()) DetailText("$label: $rendered")
    } else message.input?.let { DetailText(renderToolValue(it)) }
    message.executionLocation?.let { location ->
        location.name?.takeIf(String::isNotBlank)?.let { Text("执行位置 · $it", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    result.string("exit_code", "exitCode").takeIf { it.isNotBlank() && it != "0" }?.let { DetailText("退出码 $it", error = true) }
    if (!message.progress.isNullOrEmpty()) DetailText(message.progress!!.joinToString("\n", transform = ::renderToolValue))
    message.backgroundTask?.let { task ->
        listOfNotNull(task.outputTail, task.chunk).firstOrNull(String::isNotBlank)?.let { DetailText(it) }
    }
    val output = toolOutputText(message)
    if (failed) DetailText(output.ifBlank { "工具执行失败。" }, error = true)
    else {
        if (!message.diff.isNullOrBlank()) DetailText(message.diff!!, diff = true)
        if (output.isNotBlank()) DetailText(output)
    }
    if (output.isBlank() && message.diff.isNullOrBlank() && message.progress.isNullOrEmpty()) Text(
        if (message.running) "等待输出中" else "暂无输出", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DetailText(text: String, error: Boolean = false, diff: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val addition = diffAdditionColor()
    val annotated = remember(text, diff, error, colors, addition) {
        buildAnnotatedString {
            text.lineSequence().forEachIndexed { index, line ->
                if (index > 0) append('\n')
                val color = when {
                    error -> colors.error
                    diff && line.startsWith('+') && !line.startsWith("+++") -> addition
                    diff && line.startsWith('-') && !line.startsWith("---") -> colors.error
                    diff && line.startsWith("@@") -> colors.onSurfaceVariant
                    else -> colors.onSurface
                }
                withStyle(SpanStyle(color = color)) { append(line) }
            }
        }
    }
    SelectionContainer {
        Text(annotated, Modifier.fillMaxWidth().heightIn(max = 240.dp).verticalScroll(rememberScrollState())
            .horizontalScroll(rememberScrollState()), softWrap = false,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
    }
}

/** Settled questions remain in the conversation, outside the machinery they interrupted. */
@Composable
private fun AnsweredQuestion(message: UIMessage, modifier: Modifier) {
    val answers = message.userInput?.answers.orEmpty()
    val questions = message.userInput?.questions.orEmpty()
    Column(modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(when (message.userInput?.status ?: toolResult(message).string("status")) {
            "canceled", "cancelled" -> "已取消回答"; "expired" -> "问题已过期"; "failed" -> "回答未成功"; else -> "已回答"
        }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (answers.isNotEmpty()) answers.forEach { answer ->
            val question = answer.question ?: questions.firstOrNull { it.id == answer.questionId }?.text
            question?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Text(answer.text?.takeIf(String::isNotBlank) ?: listOfNotNull(
                answer.selected?.joinToString("、") { it.label }?.takeIf(String::isNotBlank), answer.customText?.takeIf(String::isNotBlank)
            ).joinToString("、").ifBlank { if (answer.skipped == true) "已跳过" else "未提供回答" },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            questions.forEach { Text(it.text, style = MaterialTheme.typography.bodyMedium) }
            toolOutputText(message).takeIf(String::isNotBlank)?.let { DetailText(it) }
        }
    }
}
