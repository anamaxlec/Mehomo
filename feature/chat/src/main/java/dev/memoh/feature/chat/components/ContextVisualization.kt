package dev.memoh.feature.chat.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.memoh.core.model.ContextUsage
import kotlin.math.roundToInt

// sRGB equivalents of the official Memoh chart-2/3/1 OKLCH tokens.
// felinics/ui 4b4d5ed: src/style.css, light/dark Memoh theme.
// Light empty cells use a deeper neutral against Android's tinted popup surface.
@Composable
private fun contextColor(cell: ContextCell): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < .5f
    return when (cell) {
        ContextCell.Instructions -> Color(if (dark) 0xFF4CB86A else 0xFF20A04E)
        ContextCell.Tools -> Color(if (dark) 0xFF3FB1EA else 0xFF0092D2)
        ContextCell.Conversation -> Color(if (dark) 0xFFA490FF else 0xFF764BE5)
        ContextCell.Empty -> Color(if (dark) 0xFF242424 else 0xFFDCD9E0)
        ContextCell.Used -> MaterialTheme.colorScheme.onSurface
    }
}

@Composable
internal fun contextPressureColor(percent: Double): Color = when {
    percent >= 90 -> MaterialTheme.colorScheme.error
    percent >= 70 -> Color(if (MaterialTheme.colorScheme.surface.luminance() < .5f) 0xFFEBA941 else 0xFFDA950B)
    else -> MaterialTheme.colorScheme.onSurface
}

@Composable
internal fun ContextWaffle(usage: ContextUsage, fallback: Long? = null) {
    val window = usage.window(fallback)?.toDouble() ?: return
    val percent = usage.tokens / window * 100
    val cells = remember(usage, window) { contextWaffleCells(percent, contextGroups(usage)) }
    val colors = ContextCell.entries.associateWith { if (it == ContextCell.Used) contextPressureColor(percent) else contextColor(it) }
    val description = "上下文方格图，已用 ${percent.roundToInt()}%，${formatTokens(usage.tokens)} tokens"
    Canvas(Modifier.fillMaxWidth().aspectRatio(4f).semantics { contentDescription = description }) {
        val gap = 2.dp.toPx()
        val cell = ((size.width - 19 * gap) / 20).coerceAtLeast(0f)
        cells.forEachIndexed { index, kind ->
            drawRoundRect(colors.getValue(kind), Offset((index % 20) * (cell + gap), (index / 20) * (cell + gap)),
                Size(cell, cell), CornerRadius(2.dp.toPx()))
        }
    }
}

@Composable
internal fun ContextLegend(usage: ContextUsage, showShares: Boolean = false) {
    val groups = remember(usage) { contextGroups(usage) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        groups.forEach { group ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(8.dp).background(contextColor(group.id.cell), RoundedCornerShape(2.dp)))
                Text(group.id.label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(formatTokens(group.tokens), style = MaterialTheme.typography.bodyMedium)
                if (showShares && usage.tokens > 0) Text("${(group.tokens.toDouble() * 100 / usage.tokens).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
