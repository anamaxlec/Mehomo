package dev.memoh.core.designsystem.component

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

/** The shared capsule used by chat and compact workspace input. */
@Composable
fun MemohComposerSurface(modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    contentPadding: PaddingValues = PaddingValues(6.dp), content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier, shape = MaterialTheme.shapes.extraLarge,
        color = containerColor) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun MemohComposerTextField(value: String, onValueChange: (String) -> Unit, placeholder: String,
    enabled: Boolean, modifier: Modifier = Modifier, singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else 8, onSend: () -> Unit = {}) {
    val interaction = remember { MutableInteractionSource() }
    BasicTextField(value, onValueChange, enabled = enabled, singleLine = singleLine,
        interactionSource = interaction,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Send else ImeAction.Default),
        keyboardActions = KeyboardActions(onSend = { onSend() }),
        maxLines = maxLines,
        modifier = modifier.heightIn(min = 48.dp, max = if (singleLine) 64.dp else 220.dp),
        decorationBox = { input ->
            OutlinedTextFieldDefaults.DecorationBox(value = value, innerTextField = input,
                enabled = enabled, singleLine = singleLine, visualTransformation = VisualTransformation.None,
                interactionSource = interaction,
                placeholder = {
                    Crossfade(placeholder, animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "composer placeholder") {
                        Text(it, style = MaterialTheme.typography.bodyLarge)
                    }
                },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                container = {})
        })
}

@Composable
fun MemohComposerIconButton(icon: ImageVector, contentDescription: String, enabled: Boolean,
    onClick: () -> Unit, filled: Boolean = false, danger: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val content: @Composable () -> Unit = {
        Crossfade(icon, modifier = Modifier.size(22.dp).semantics { this.contentDescription = contentDescription },
            animationSpec = MaterialTheme.motionScheme.defaultEffectsSpec(), label = "composer action") {
            Icon(it, null, Modifier.size(22.dp))
        }
    }
    val containerColor by animateColorAsState(
        if (!enabled) scheme.surfaceContainerHighest else if (danger) scheme.errorContainer else scheme.primary,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "composer action background")
    val contentColor by animateColorAsState(
        if (!enabled) scheme.onSurfaceVariant else if (danger) scheme.onErrorContainer else scheme.onPrimary,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "composer action color")
    val plainColors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onSurfaceVariant)
    val plainTint by animateColorAsState(if (enabled) plainColors.contentColor else plainColors.disabledContentColor,
        MaterialTheme.motionScheme.defaultEffectsSpec(), label = "composer control color")
    if (filled) FilledIconButton(onClick, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes(), enabled = enabled,
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = containerColor, contentColor = contentColor,
            disabledContainerColor = containerColor, disabledContentColor = contentColor), content = content)
    else IconButton(onClick, modifier = Modifier.size(48.dp), shapes = IconButtonDefaults.shapes(), enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = plainTint, disabledContentColor = plainTint), content = content)
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
