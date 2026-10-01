package dev.memoh.feature.chat.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.MemohSkeleton
import dev.memoh.core.designsystem.component.MemohSkeletonBlock

/** Covers initial history fetching and positioning without exposing placeholder text. */
@Composable
fun ChatHistoryPlaceholder(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxSize().clipToBounds()
            .clearAndSetSemantics { contentDescription = "正在加载会话" },
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(contentAlignment = Alignment.TopCenter) {
            MemohSkeleton(
                modifier = Modifier.widthIn(max = 704.dp).fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 24.dp),
                description = "正在加载会话",
            ) {
                MemohSkeletonBlock(Modifier.align(Alignment.End).fillMaxWidth(.68f).height(56.dp), MaterialTheme.shapes.extraLarge)
                Spacer(Modifier.height(12.dp))
                MemohSkeletonBlock(Modifier.fillMaxWidth(.34f).height(18.dp))
                listOf(.94f, 1f, .86f, .62f).forEach { width ->
                    MemohSkeletonBlock(Modifier.fillMaxWidth(width).height(12.dp))
                }
                Spacer(Modifier.height(4.dp))
                MemohSkeletonBlock(Modifier.fillMaxWidth().height(104.dp), MaterialTheme.shapes.large)
                listOf(.9f, .74f).forEach { width ->
                    MemohSkeletonBlock(Modifier.fillMaxWidth(width).height(12.dp))
                }
                Spacer(Modifier.height(20.dp))
                MemohSkeletonBlock(Modifier.align(Alignment.End).fillMaxWidth(.56f).height(48.dp), MaterialTheme.shapes.extraLarge)
                Spacer(Modifier.height(12.dp))
                listOf(.96f, .8f, .48f).forEach { width ->
                    MemohSkeletonBlock(Modifier.fillMaxWidth(width).height(12.dp))
                }
            }
        }
    }
}
