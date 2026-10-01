package dev.memoh.core.designsystem.component

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** The shared capsule used by chat and compact workspace input. */
@Composable
fun MemohComposerSurface(modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge,
        color = containerColor) {
        Column(Modifier.padding(6.dp), content = content)
    }
}

@Composable
fun MemohComposerTextField(value: String, onValueChange: (String) -> Unit, placeholder: String,
    enabled: Boolean, modifier: Modifier = Modifier, singleLine: Boolean = false, onSend: () -> Unit = {}) {
    OutlinedTextField(value, onValueChange, enabled = enabled, singleLine = singleLine,
        placeholder = { Text(placeholder, style = MaterialTheme.typography.bodyLarge) },
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent, focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent, disabledBorderColor = Color.Transparent),
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Send else ImeAction.Default),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        maxLines = if (singleLine) 1 else 8,
        modifier = modifier.heightIn(min = 48.dp, max = if (singleLine) 64.dp else 220.dp))
}

@Composable
fun MemohComposerIconButton(icon: ImageVector, contentDescription: String, enabled: Boolean,
    onClick: () -> Unit, filled: Boolean = false, danger: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = { Icon(icon, contentDescription, Modifier.size(22.dp)) }
    if (filled) FilledIconButton(onClick, shapes = IconButtonDefaults.shapes(), enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (danger) scheme.errorContainer else scheme.primary,
            contentColor = if (danger) scheme.onErrorContainer else scheme.onPrimary,
            disabledContainerColor = scheme.surfaceContainerHighest,
            disabledContentColor = scheme.onSurfaceVariant), content = content)
    else IconButton(onClick, shapes = IconButtonDefaults.shapes(), enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onSurfaceVariant), content = content)
}

@Composable
fun MemohCompactComposer(value: String, onValueChange: (String) -> Unit, placeholder: String,
    enabled: Boolean, onSend: () -> Unit, modifier: Modifier = Modifier) {
    MemohComposerSurface(modifier.fillMaxWidth(), containerColor = MaterialTheme.colorScheme.surfaceContainerLowest) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MemohComposerTextField(value, onValueChange, placeholder, enabled, Modifier.weight(1f),
                singleLine = true, onSend = onSend)
            MemohComposerIconButton(Icons.Filled.ArrowUpward, "发送输入", enabled && value.isNotEmpty(),
                onSend, filled = true)
        }
    }
}
