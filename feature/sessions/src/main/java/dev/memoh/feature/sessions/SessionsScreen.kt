package dev.memoh.feature.sessions

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PendingActions
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroup
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.motion.MemohMotion
import dev.memoh.core.designsystem.component.MemohPopupMenu
import dev.memoh.core.designsystem.component.MemohMenuRow
import dev.memoh.core.designsystem.component.MemohMenuDivider
import dev.memoh.core.designsystem.component.MemohMenuRow
import dev.memoh.core.designsystem.component.MemohAvatar
import dev.memoh.core.designsystem.component.MemohEmptyState
import dev.memoh.core.designsystem.component.MemohListIcon
import dev.memoh.core.designsystem.component.ListIconTone
import dev.memoh.core.designsystem.component.MemohStatus
import dev.memoh.core.designsystem.component.MemohStatusDot
import dev.memoh.core.designsystem.component.MemohListSkeleton
import dev.memoh.core.designsystem.component.MemohLoadingContent
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.designsystem.component.MemohFormDialog
import dev.memoh.core.designsystem.component.MemohSkeleton
import dev.memoh.core.designsystem.component.MemohSkeletonBlock
import dev.memoh.core.designsystem.component.MemohRefreshBox
import dev.memoh.core.model.Bot
import dev.memoh.core.model.Session
import dev.memoh.core.model.Workdir
import dev.memoh.core.model.RuntimeState
import dev.memoh.core.model.RunStatus

/**
 * The bot's content: chats, files, schedules.
 *
 * Folders and dated session groups use expressive segmented list items. The
 * app bar carries the bot identity and its actions; folders pin above sessions.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SessionsScreen(
    state: SessionsUiState,
    onSelectBot: (Bot) -> Unit,
    onOpenSession: (Session) -> Unit,
    onRename: (Session, String) -> Unit,
    onBeginRename: (Session) -> Unit,
    onCancelRename: () -> Unit,
    onBeginDelete: (Session) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: (Session) -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit = {},
    onSelectTab: (BotTab) -> Unit = {},
    onQueryChange: (String) -> Unit = {},
    onBotSettings: () -> Unit = {},
    scheduleContent: (@Composable () -> Unit)? = null,
    fileContent: (@Composable () -> Unit)? = null,
    onOpenFolder: (Workdir) -> Unit = {},
    modifier: Modifier = Modifier,
    onBarExpandedChange: (Boolean) -> Unit = {},
) {
    var searching by remember { mutableStateOf(false) }

    // The shell observes all panes' scrolls. Begin a tab switch with the full
    // navigation bar visible before the new pane starts scrolling.
    androidx.compose.runtime.LaunchedEffect(state.tab) {
        onBarExpandedChange(true)
    }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        topBar = {
            TopAppBar(
                colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                title = {
                    // Entering search: the field glides in from the search icon's
                    // side while the title slides out the other way and both cross
                    // fade — M3's shared axis, not a width animation. Resizing the
                    // field mid-flight fights the top bar's own measure pass and
                    // looks jittery; sliding the two full-width panes past each
                    // other keeps the top bar still and the motion legible.
                    AnimatedContent(
                        targetState = searching,
                        // Opening search moves forward, closing moves back —
                        // the same vocabulary the tab switcher uses.
                        transitionSpec = { MemohMotion.horizontalSwap(forward = targetState) },
                        label = "searchSwitch",
                    ) { isSearching ->
                        Box(modifier = Modifier.fillMaxWidth()) {
                            if (isSearching) {
                                SearchField(
                                    query = state.query,
                                    onQueryChange = onQueryChange,
                                    onClose = {
                                        searching = false
                                        onQueryChange("")
                                    },
                                )
                            } else if (!state.initialized && state.bots.isEmpty()) {
                                MemohSkeleton(Modifier.padding(start = 16.dp), "正在加载首页") {
                                    MemohSkeletonBlock(Modifier.width(100.dp).height(24.dp))
                                    MemohSkeletonBlock(Modifier.width(64.dp).height(14.dp))
                                }
                            } else {
                                BotTitle(
                                    bots = state.bots,
                                    selected = state.bot,
                                    sessionCount = state.sessions.size,
                                    countLoading = state.loading && state.sessions.isEmpty(),
                                    onSelect = onSelectBot,
                                    onSettings = onBotSettings,
                                )
                            }
                        }
                    }
                },
                actions = {
                    AnimatedVisibility(
                        visible = !searching,
                        enter = MemohMotion.controlEnter(),
                        exit = MemohMotion.controlExit(),
                    ) {
                        IconButton(onClick = { searching = true }) {
                            Icon(Icons.Filled.Search, contentDescription = "搜索")
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabSwitcher(
                selected = state.tab,
                onSelect = onSelectTab,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            )

        MemohRefreshBox(refreshing = state.loading && state.sessions.isNotEmpty() && state.tab == BotTab.Chats,
            onRefresh = onRetry, enabled = state.tab == BotTab.Chats && state.initialized,
            modifier = Modifier.fillMaxSize()) {
            // Tab switches get a shared-axis style transition: the incoming pane
            // slides in from the direction of the tapped segment while fading up,
            // the outgoing one slides the other way and fades down. Direction is
            // derived from segment order so moving Chats → Files → Schedules
            // always reads left-to-right.
            AnimatedContent(
                targetState = state.tab,
                transitionSpec = {
                    MemohMotion.horizontalSwap(forward = targetState.ordinal >= initialState.ordinal)
                },
                label = "tabContent",
            ) { tab ->
                MemohLoadingContent(
                    loading = tab == BotTab.Chats && (state.loading || !state.initialized) && state.sessions.isEmpty() && state.folders.isEmpty(),
                    placeholder = { MemohListSkeleton(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), "正在加载会话列表", rows = 7) },
                ) {
                when {
                    tab == BotTab.Schedules && scheduleContent != null -> scheduleContent()
                    tab == BotTab.Files && fileContent != null -> fileContent()

                    tab != BotTab.Chats -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            MemohEmptyState(
                                title = "${tab.label}还没做",
                                description = when (tab) {
                                    BotTab.Files ->
                                        "Bot 云电脑里的文件浏览与管理。接口是 /bots/{id}/container/fs。"
                                    else ->
                                        "用 cron 或自然语言安排 Bot 周期性执行任务。接口是 /bots/{id}/schedule。"
                                },
                            )
                        }
                    }

                    state.bot == null && state.error == null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            MemohEmptyState(
                                title = "还没有 Bot",
                                description = "这个账号下还没有可用的 Bot。先在网页端创建一个，它就会出现在这里。",
                            )
                        }
                    }

                    state.searchMissed -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            MemohEmptyState(
                                title = "没有匹配的内容",
                                description = if (state.nextCursor != null) "已加载的 ${state.sessions.size} 个会话中没有匹配，可继续加载更早的会话。" else "已搜索全部会话标题，可换个关键词试试。",
                                action = { if (state.nextCursor != null) PageFooter(state, onLoadMore) },
                            )
                        }
                    }

                    state.sessions.isEmpty() && state.visibleFolders.isEmpty() && state.error == null -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            MemohEmptyState(
                                title = "还没有会话",
                                description = "开始一段对话，Bot 会在它的云电脑上为你工作",
                            )
                        }
                    }

                    state.sessions.isEmpty() && state.visibleFolders.isEmpty() -> {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            MemohEmptyState(
                                title = "加载失败",
                                description = "检查网络后重试",
                                action = { MemohActionButton("重试", Icons.Filled.Refresh, onRetry, primary = true) },
                            )
                        }
                    }

                    else -> {
                        SessionList(
                            state = state,
                            onOpenSession = onOpenSession,
                            onBeginRename = onBeginRename,
                            onBeginDelete = onBeginDelete,
                            onOpenFolder = onOpenFolder,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
                }
            }

            state.error?.let { message ->
                ErrorBar(
                    message = message,
                    onDismiss = onDismissError,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(start = 16.dp, end = 16.dp, bottom = 88.dp),
                )
            }
        }
        }
    }

    state.renaming?.let { session ->
        RenameDialog(
            initial = session.title.orEmpty(),
            onConfirm = { onRename(session, it) },
            onDismiss = onCancelRename,
        )
    }

    state.deleting?.let { session ->
        AlertDialog(
            onDismissRequest = onCancelDelete,
            title = { Text("删除会话") },
            text = {
                Text(
                    "「${session.title?.takeIf(String::isNotBlank) ?: "未命名会话"}」及其历史将被删除，此操作不可撤销。",
                )
            },
            confirmButton = {
                MemohActionButton("删除", Icons.Filled.DeleteOutline, { onConfirmDelete(session) }, danger = true)
            },
            dismissButton = {
                MemohActionButton("取消", Icons.Filled.Close, onCancelDelete)
            },
        )
    }
}

/**
 * The chats / files / schedules switch.
 *
 * `ButtonGroup` with `toggleableItem` rather than `clickableItem`: the toggle
 * form carries the checked state, which is what draws the connected selected
 * shape and animates the segment widths as the selection moves. The clickable
 * form has no selected state at all, so it renders three flat buttons. The
 * connected shapes come from the group itself — its scope supplies them per
 * position, so they must not be passed in.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabSwitcher(
    selected: BotTab,
    onSelect: (BotTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    ButtonGroup(
        modifier = modifier,
        overflowIndicator = {},
    ) {
        BotTab.entries.forEach { tab ->
            toggleableItem(
                checked = tab == selected,
                label = tab.label,
                onCheckedChange = { onSelect(tab) },
                weight = 1f,
            )
        }
    }
}

/**
 * The app-bar title: the bot, and its menu.
 *
 * The menu holds the bot list and the bot's own settings, because both answer
 * "which bot, and how is it configured" — splitting them into two controls
 * would make the user look in two places for one question.
 */
@Composable
private fun BotTitle(
    bots: List<Bot>,
    selected: Bot?,
    sessionCount: Int,
    countLoading: Boolean,
    onSelect: (Bot) -> Unit,
    onSettings: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val name = selected?.displayName?.takeIf(String::isNotBlank)
        ?: selected?.name
        ?: "会话"

    Box(Modifier.padding(start = 8.dp)) {
        Surface(onClick = { open = true }, shape = MaterialTheme.shapes.large, color = Color.Transparent) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Icon(
                            imageVector = Icons.Filled.ArrowDropDown,
                            contentDescription = "Bot 菜单",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Box(Modifier.height(20.dp), contentAlignment = Alignment.CenterStart) {
                        if (countLoading) {
                            MemohSkeleton(description = "正在加载会话数量") {
                                MemohSkeletonBlock(Modifier.width(64.dp).height(14.dp))
                            }
                        } else Text(
                            text = "$sessionCount 个会话",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        MemohPopupMenu(
            expanded = open,
            onDismiss = { open = false },
            alignment = Alignment.BottomStart,
        ) {
            if (bots.size > 1) {
                bots.forEach { bot ->
                    val label = bot.displayName?.takeIf(String::isNotBlank) ?: bot.name
                    MemohMenuRow(
                        title = label.ifBlank { bot.id },
                        icon = Icons.Filled.SmartToy,
                        selected = bot.id == selected?.id,
                        onClick = {
                            open = false
                            onSelect(bot)
                        },
                    )
                }
                MemohMenuDivider()
            }
            MemohMenuRow(
                title = "Bot 设置",
                icon = Icons.Filled.Settings,
                onClick = {
                    open = false
                    onSettings()
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onClose: () -> Unit,
) {
    // Focus lands as the field finishes expanding — opening search should mean
    // typing, not a second tap on the field.
    val focusRequester = remember { FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
    // Material 3 Expressive's own search input: the pill-shaped field with the
    // search glyph leading and the clear action trailing — not a plain TextField
    // wearing a shape.
    // Standalone use: SearchBar's container normally paints the pill behind the
    // input, so give the field its own high-container pill and drop the focus
    // underline a bare TextField would draw.
    SearchBarDefaults.InputField(
        query = query,
        onQueryChange = onQueryChange,
        onSearch = { },
        expanded = true,
        onExpandedChange = { },
        placeholder = { Text("搜索会话和文件夹") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "关闭搜索")
            }
        },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .focusRequester(focusRequester),
    )
}

/**
 * Folders pinned above the sessions, then the sessions themselves.
 *
 * One list rather than two so the whole thing scrolls as a unit — a pinned
 * header that does not scroll is a second scroll context on a phone screen.
 */
@Composable
private fun SessionList(
    state: SessionsUiState,
    onOpenSession: (Session) -> Unit,
    onBeginRename: (Session) -> Unit,
    onBeginDelete: (Session) -> Unit,
    onOpenFolder: (Workdir) -> Unit,
    onLoadMore: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize(),
        // The floating controls hover over the last row, so the list reserves
        // room for them rather than hiding it.
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        if (state.query.isNotBlank()) item(key = "search-scope") {
            Text(if (state.nextCursor != null) "搜索已加载的 ${state.sessions.size} 个会话标题" else "搜索全部会话标题",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        }
        if (state.visibleFolders.isNotEmpty()) {
            item(key = "header-folders") { SectionHeader("文件夹") }
            itemsIndexed(state.visibleFolders, key = { _, folder -> "folder-${folder.id}" }) { index, folder ->
                FolderRow(folder = folder, index = index, count = state.visibleFolders.size, onClick = { onOpenFolder(folder) })
            }
        }

        if (state.today.isNotEmpty()) {
            item(key = "header-today") { SectionHeader("今天") }
            itemsIndexed(state.today, key = { _, session -> session.id }) { index, session ->
                SessionRow(
                    session = session,
                    index = index,
                    count = state.today.size,
                    runtime = state.runStates[session.id],
                    compacting = session.id in state.compactingSessions,
                    onClick = { onOpenSession(session) },
                    onRename = { onBeginRename(session) },
                    onDelete = { onBeginDelete(session) },
                )
            }
        }

        if (state.earlier.isNotEmpty()) {
            item(key = "header-earlier") { SectionHeader("更早") }
            itemsIndexed(state.earlier, key = { _, session -> session.id }) { index, session ->
                SessionRow(
                    session = session,
                    index = index,
                    count = state.earlier.size,
                    runtime = state.runStates[session.id],
                    compacting = session.id in state.compactingSessions,
                    onClick = { onOpenSession(session) },
                    onRename = { onBeginRename(session) },
                    onDelete = { onBeginDelete(session) },
                )
            }
        }
        if (state.nextCursor != null) item(key = "load-more") {
            androidx.compose.runtime.LaunchedEffect(state.nextCursor) {
                if (state.query.isBlank() && state.loadMoreError == null) onLoadMore()
            }
            PageFooter(state, onLoadMore)
        }
    }
}

@Composable
private fun PageFooter(state: SessionsUiState, onLoadMore: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        state.loadMoreError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (state.loadingMore) CircularProgressIndicator(Modifier.size(24.dp))
        else TextButton(onClick = onLoadMore, enabled = !state.loading) {
            Text(if (state.loadMoreError != null) "重试加载更多" else "加载更早的会话")
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/**
 * A folder, which groups sessions rather than containing messages.
 *
 * It reads as a container, not a destination: the folder icon and its name, with
 * the path as a quiet second line. Tapping opens the folder's contents once that
 * view exists; until then it is inert rather than pretending.
 */
@Composable
private fun FolderRow(folder: Workdir, index: Int, count: Int, onClick: () -> Unit) {
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        leadingContent = {
            MemohListIcon(Icons.Filled.Folder)
        },
        supportingContent = {
            folder.path?.takeIf(String::isNotBlank)?.let { path ->
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    ) {
        Text(
            text = folder.name.ifBlank { folder.path.orEmpty() },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SessionRow(
    session: Session,
    index: Int,
    count: Int,
    runtime: RuntimeState?,
    compacting: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    SegmentedListItem(
        onClick = onClick,
        shapes = ListItemDefaults.segmentedShapes(index, count),
        modifier = Modifier.fillMaxWidth(),
        colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
        supportingContent = if (!session.isLocal) ({
                Text(text = session.channelType?.takeIf(String::isNotBlank) ?: "外部渠道",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
        }) else null,
        trailingContent = { Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (compacting || (runtime?.needsSnapshot == false && runtime.isRunning)) {
                if (!compacting && runtime?.run?.status == RunStatus.WAITING_DECISION)
                    Icon(Icons.Filled.PendingActions, "等待确认", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                else CircularProgressIndicator(Modifier.size(18.dp).semantics { contentDescription = if (compacting) "正在整理上下文" else "任务进行中" },
                    color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
            }
            Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "更多操作")
            }
            MemohPopupMenu(
                expanded = menuOpen,
                onDismiss = { menuOpen = false },
                alignment = Alignment.BottomEnd,
            ) {
                MemohMenuRow(
                    title = "重命名",
                    icon = Icons.Filled.Edit,
                    onClick = { menuOpen = false; onRename() },
                )
                MemohMenuRow(
                    title = "删除",
                    icon = Icons.Filled.DeleteOutline,
                    onClick = { menuOpen = false; onDelete() },
                )
            }
            }
        } },
    ) {
        Text(
            text = session.title?.takeIf(String::isNotBlank) ?: "未命名会话",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    MemohFormDialog(
        onDismissRequest = onDismiss,
        title = "重命名会话",
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("标题") },
                shape = MaterialTheme.shapes.medium,
            )
        },
        confirmButton = {
            MemohActionButton("保存", Icons.Filled.Save, { onConfirm(text.trim()) }, enabled = text.isNotBlank(), primary = true)
        },
        dismissButton = { MemohActionButton("取消", Icons.Filled.Close, onDismiss) },
    )
}

@Composable
private fun ErrorBar(
    message: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.shapes.medium,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.weight(1f),
        )
        MemohActionButton("知道了", Icons.Filled.Check, onDismiss)
    }
}
