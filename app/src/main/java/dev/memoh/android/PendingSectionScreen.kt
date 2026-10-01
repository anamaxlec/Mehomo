package dev.memoh.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohEmptyState

/**
 * A section that is reachable but not built yet.
 *
 * It states what the section will do and which endpoint backs it, rather than
 * showing a bare "coming soon" — a user who taps an entry deserves to know
 * whether the feature is missing or merely hidden.
 */
@Composable
fun PendingSectionScreen(section: MainSection, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        MemohEmptyState(
            title = "${section.label}还没做",
            description = section.pendingNote.ifBlank { "这个分区还在计划中。" },
        )
    }
}
