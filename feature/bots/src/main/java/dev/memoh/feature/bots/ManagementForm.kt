package dev.memoh.feature.bots

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.*
import kotlinx.serialization.json.*

internal enum class FieldKind { Text, Number, Decimal, Object, Array, StringSet, Toggle }
internal data class FormField(
    val key: String, val label: String, val value: String = "", val kind: FieldKind = FieldKind.Text,
    val choices: List<Pair<String, String>> = emptyList(), val multiline: Boolean = false,
    val secret: Boolean = false, val required: Boolean = false, val omitBlank: Boolean = false,
    val choicesFor: ((Map<String, String>) -> List<Pair<String, String>>)? = null,
    val visible: (Map<String, String>) -> Boolean = { true },
    val min: Long? = null, val max: Long? = null,
)
internal data class ManagementEditor(val title: String, val fields: List<FormField>, val confirmLabel: String = "保存", val save: (JsonObject, () -> Unit) -> Unit)

internal fun formBody(fields: List<FormField>, values: Map<String, String>): JsonObject = buildJsonObject {
    fields.forEach { field ->
        if (!field.visible(values)) return@forEach
        val value = values.getValue(field.key).trim()
        require(!field.required || value.isNotBlank()) { "请填写${field.label}" }
        if (field.omitBlank && value.isBlank()) return@forEach
        val parsed = when (field.kind) {
            FieldKind.Text -> JsonPrimitive(value)
            FieldKind.Toggle -> JsonPrimitive(value.toBooleanStrict())
            FieldKind.Number -> if (value.isEmpty()) JsonNull else {
                val number = value.toLongOrNull() ?: error("${field.label}应为整数")
                require(field.min == null || number >= field.min) { "${field.label}不能小于 ${field.min}" }
                require(field.max == null || number <= field.max) { "${field.label}不能大于 ${field.max}" }
                JsonPrimitive(number)
            }
            FieldKind.Decimal -> if (value.isEmpty()) JsonNull else JsonPrimitive(value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("${field.label}应为有效数字"))
            FieldKind.Object -> Json.parseToJsonElement(value.ifBlank { "{}" }).also { require(it is JsonObject) { "${field.label}应为 JSON 对象" } }
            FieldKind.Array -> Json.parseToJsonElement(value.ifBlank { "[]" }).also { require(it is JsonArray) { "${field.label}应为 JSON 数组" } }
            FieldKind.StringSet -> Json.parseToJsonElement(value.ifBlank { "[]" }).also { require(it is JsonArray && it.all { value -> value is JsonPrimitive && value.isString }) { "${field.label}应为字符串列表" } }
        }
        put(field.key, parsed)
    }
}

internal fun updateFormValue(fields: List<FormField>, values: MutableMap<String, String>, key: String, value: String) {
    val previous = fields.associate { it.key to it.choicesFor?.invoke(values) }
    values[key] = value
    fields.forEach { field ->
        val choices = field.choicesFor?.invoke(values) ?: return@forEach
        if (choices != previous[field.key] && choices.none { it.first == values[field.key] }) {
            values[field.key] = choices.firstOrNull()?.first.orEmpty()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ManagementForm(editor: ManagementEditor, busy: Boolean, serverError: String?, onDismiss: () -> Unit) {
    val values = remember(editor) { mutableStateMapOf<String, String>().apply { editor.fields.forEach { put(it.key, it.value) } } }
    var error by remember(editor) { mutableStateOf<String?>(null) }
    MemohFormDialog(editor.title, onDismissRequest = { if (!busy) onDismiss() },
        confirmButton = { MemohActionButton(if (busy) "处理中" else editor.confirmLabel, Icons.Filled.Save, onClick = {
            try { error = null; editor.save(formBody(editor.fields, values), onDismiss) }
            catch (e: Exception) { error = e.message }
        }, enabled = !busy, primary = true) },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                editor.fields.forEach { field ->
                    if (!field.visible(values)) return@forEach
                    val value = values.getValue(field.key)
                    val choices = field.choicesFor?.invoke(values) ?: field.choices
                    when {
                        field.kind == FieldKind.StringSet -> {
                            val selected = (runCatching { Json.parseToJsonElement(value) }.getOrNull() as? JsonArray)?.map { it.jsonPrimitive.content }.orEmpty()
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(field.label, style = MaterialTheme.typography.labelMedium)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    (choices + selected.filter { value -> choices.none { it.first == value } }.map { it to it }).forEach { (id, label) ->
                                        FilterChip(id in selected, { values[field.key] = JsonArray((if (id in selected) selected - id else selected + id).map(::JsonPrimitive)).toString() },
                                            label = { Text(label) }, enabled = !busy)
                                    }
                                }
                            }
                        }
                        field.kind == FieldKind.Toggle -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(field.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Switch(value == "true", { values[field.key] = it.toString() }, enabled = !busy,
                                thumbContent = { Icon(if (value == "true") Icons.Filled.Check else Icons.Filled.Close, null, Modifier.size(SwitchDefaults.IconSize)) })
                        }
                        choices.isNotEmpty() -> {
                            var expanded by remember(field.key) { mutableStateOf(false) }
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Box {
                                    OutlinedButton({ expanded = true }, modifier = Modifier.fillMaxWidth(), enabled = !busy, shapes = ButtonDefaults.shapes()) {
                                        Text(choices.firstOrNull { it.first == value }?.second ?: value.ifBlank { "未选择" }, Modifier.weight(1f))
                                        Icon(Icons.Filled.ArrowDropDown, null)
                                    }
                                    MemohPopupMenu(expanded, { expanded = false }, Alignment.TopStart) {
                                        Column(Modifier.heightIn(max = 350.dp).verticalScroll(rememberScrollState())) {
                                            var query by remember { mutableStateOf("") }
                                            if (choices.size > 8) OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(8.dp), singleLine = true,
                                                label = { Text("查找${field.label}") }, leadingIcon = { Icon(Icons.Filled.Search, null) })
                                            MemohMenuGroup {
                                                val visible = choices.filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) }
                                                visible.forEachIndexed { index, choice -> MemohMenuRow(choice.second, Icons.Filled.Check,
                                                    { updateFormValue(editor.fields, values, field.key, choice.first); expanded = false }, selected = value == choice.first,
                                                    selectable = true, index = index, count = visible.size) }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        else -> OutlinedTextField(value, { values[field.key] = it }, Modifier.fillMaxWidth(), enabled = !busy,
                            label = { Text(field.label) }, minLines = if (field.multiline) 4 else 1, maxLines = if (field.multiline) 12 else 1,
                            singleLine = !field.multiline, visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
                            keyboardOptions = KeyboardOptions(keyboardType = when { field.secret -> KeyboardType.Password; field.kind == FieldKind.Number -> KeyboardType.Number; field.kind == FieldKind.Decimal -> KeyboardType.Decimal; else -> KeyboardType.Text }))
                    }
                }
                (error ?: serverError)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            }
        })
}
