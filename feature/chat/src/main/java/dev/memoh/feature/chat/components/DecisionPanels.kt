package dev.memoh.feature.chat.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import dev.memoh.core.designsystem.component.MemohActionButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.memoh.core.model.UIToolApproval
import dev.memoh.core.model.UIUserInput
import dev.memoh.core.model.UIUserInputQuestion
import dev.memoh.core.model.WSUserInputAnswer

/**
 * A pending tool approval.
 *
 * Rendered inline above the composer rather than as a modal: the user needs to
 * read the command in the context of the conversation to judge it, and a dialog
 * would hide exactly the transcript they need.
 */
@Composable
fun ApprovalPanel(
    approval: UIToolApproval,
    toolName: String?,
    toolInput: String?,
    onApprove: (optionId: String?) -> Unit,
    onReject: (reason: String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var rejectReason by remember { mutableStateOf("") }
    var showRejectField by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "需要你的确认",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        Text(
            text = toolName?.takeIf(String::isNotBlank) ?: "工具调用",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        toolInput?.takeIf(String::isNotBlank)?.let { input ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(8.dp),
            ) {
                Text(
                    text = input.take(600),
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }

        if (showRejectField) {
            OutlinedTextField(
                value = rejectReason,
                onValueChange = { rejectReason = it },
                label = { Text("拒绝原因（可选）") },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Agent-provided permission options come first: when the server names
        // them, picking one is more precise than a bare approve.
        val options = approval.options.orEmpty()
        if (options.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                options.forEach { option ->
                    MemohActionButton(option.name?.takeIf(String::isNotBlank) ?: option.kind.orEmpty(), Icons.Filled.Check,
                        { onApprove(option.id) }, modifier = Modifier.fillMaxWidth())
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (options.isEmpty()) {
                MemohActionButton("批准", Icons.Filled.Check, { onApprove(null) }, modifier = Modifier.weight(1f), primary = true)
            }
            if (showRejectField) {
                MemohActionButton("确认拒绝", Icons.Filled.Close, { onReject(rejectReason.takeIf(String::isNotBlank)) }, modifier = Modifier.weight(1f), danger = true)
            } else {
                MemohActionButton("拒绝", Icons.Filled.Close, { showRejectField = true }, modifier = Modifier.weight(1f), danger = true)
            }
        }
    }
}

/**
 * A structured question from the agent.
 *
 * Answers are collected locally and submitted together, because the server
 * settles a whole question set in one response.
 */
@Composable
fun UserInputPanel(
    input: UIUserInput,
    onRespond: (List<WSUserInputAnswer>) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val questions = input.questions.orEmpty()
    // questionId -> selected option ids, or the custom text.
    val selections = remember(input.userInputId) { mutableStateMapOf<String, Set<String>>() }
    val customText = remember(input.userInputId) { mutableStateMapOf<String, String>() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "需要你的回答",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )

        questions.forEach { question ->
            QuestionBlock(
                question = question,
                selected = selections[question.id].orEmpty(),
                custom = customText[question.id].orEmpty(),
                onSelect = { optionId ->
                    val current = selections[question.id].orEmpty()
                    selections[question.id] = when (question.kind) {
                        "multi_select" ->
                            if (optionId in current) current - optionId else current + optionId
                        else -> setOf(optionId)
                    }
                    // A custom answer excludes listed options when the server
                    // says the two are mutually exclusive.
                    if (question.customExclusive == true) customText.remove(question.id)
                },
                onCustomChange = { text ->
                    customText[question.id] = text
                    if (question.customExclusive == true && text.isNotBlank()) {
                        selections.remove(question.id)
                    }
                },
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MemohActionButton("提交", Icons.Filled.Check,
                onClick = {
                    onRespond(
                        questions.map { question ->
                            WSUserInputAnswer(
                                questionId = question.id,
                                optionIds = selections[question.id]?.toList()?.takeIf { it.isNotEmpty() },
                                customText = customText[question.id]?.takeIf(String::isNotBlank),
                                // Optional questions may be left blank.
                                skipped = question.required != true &&
                                    selections[question.id].isNullOrEmpty() &&
                                    customText[question.id].isNullOrBlank(),
                            )
                        },
                    )
                },
                modifier = Modifier.weight(1f),
                primary = true,
            )
            MemohActionButton("取消", Icons.Filled.Close, onCancel)
        }
    }
}

@Composable
private fun QuestionBlock(
    question: UIUserInputQuestion,
    selected: Set<String>,
    custom: String,
    onSelect: (String) -> Unit,
    onCustomChange: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = question.text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            if (question.required == true) {
                Text(
                    text = " *",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        question.options.orEmpty().forEach { option ->
            val checked = option.id in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable { onSelect(option.id) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (question.kind == "multi_select") {
                    Checkbox(checked = checked, onCheckedChange = { onSelect(option.id) })
                } else {
                    RadioButton(selected = checked, onClick = { onSelect(option.id) })
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    option.description?.takeIf(String::isNotBlank)?.let { description ->
                        Text(
                            text = description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        val wantsText = question.kind == "text" || question.allowCustom == true
        if (wantsText) {
            OutlinedTextField(
                value = custom,
                onValueChange = onCustomChange,
                label = { Text(question.placeholder ?: if (question.kind == "text") "回答" else "其他…") },
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** The read-only notice shown in place of the composer for external sessions. */
@Composable
fun ExternalSessionNotice(
    channel: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "此会话来自 $channel，只能查看",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
