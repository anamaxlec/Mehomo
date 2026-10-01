package dev.memoh.core.designsystem.component

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** Native expressive refresh indicator, revealed from beneath the page's app bar. */
@Composable
fun MemohRefreshBox(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val refreshState = rememberPullToRefreshState()
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, state = refreshState,
        modifier = modifier, enabled = enabled,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(state = refreshState, isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter))
        }, content = content)
}
