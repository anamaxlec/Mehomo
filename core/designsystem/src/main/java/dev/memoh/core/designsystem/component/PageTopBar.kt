package dev.memoh.core.designsystem.component

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Native large-page title that folds into the compact navigation bar on scroll. */
@Composable
fun MemohPageTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    backEnabled: Boolean = true,
    large: Boolean = true,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        titleContentColor = MaterialTheme.colorScheme.primary,
        actionIconContentColor = MaterialTheme.colorScheme.primary,
    )
    val titleContent: @Composable () -> Unit = {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    val navigation: @Composable () -> Unit = {
        onBack?.let { back ->
            FilledTonalIconButton(onClick = back, enabled = backEnabled,
                shapes = IconButtonDefaults.shapes(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                )) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", Modifier.size(22.dp))
            }
        }
    }
    if (large) LargeTopAppBar(title = titleContent, navigationIcon = navigation,
        actions = actions, colors = colors, scrollBehavior = scrollBehavior, windowInsets = windowInsets)
    else TopAppBar(title = titleContent, navigationIcon = navigation,
        actions = actions, colors = colors, windowInsets = windowInsets)
}
