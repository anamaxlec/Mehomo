package dev.memoh.feature.chat

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import dev.memoh.core.designsystem.motion.MemohMotion
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.Chat
import dev.memoh.feature.chat.components.SessionContextIndicator
import androidx.compose.material3.*
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Close
import dev.memoh.core.designsystem.component.MemohMenuGroup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.memoh.core.designsystem.component.MemohPopupMenu
import dev.memoh.core.designsystem.component.MemohMenuRow
import dev.memoh.core.designsystem.component.MemohMenuDivider
import dev.memoh.core.designsystem.component.MemohEmptyState
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.designsystem.component.MemohStatus
import dev.memoh.core.designsystem.component.MemohStatusDot
import dev.memoh.core.network.SocketStatus
import dev.memoh.core.markdown.LocalMarkdownImageLoading
import dev.memoh.feature.chat.components.ApprovalPanel
import androidx.compose.material.icons.filled.Image
import dev.memoh.feature.chat.components.Composer
import dev.memoh.feature.chat.components.ComposerAttachments
import dev.memoh.feature.chat.components.ComposerQueue
import dev.memoh.feature.chat.components.ChatHistoryPlaceholder
import dev.memoh.feature.chat.components.MessageActions
import dev.memoh.feature.chat.components.MessageActionsHeight
import dev.memoh.feature.chat.components.replyCopyText
import dev.memoh.feature.chat.components.ModelMenu
import dev.memoh.feature.chat.components.ExternalSessionNotice
import dev.memoh.feature.chat.components.PromptStarters
import dev.memoh.feature.chat.components.RunPhaseLine
import dev.memoh.feature.chat.components.TypingIndicator
import dev.memoh.feature.chat.components.UserInputPanel
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first

/** The same item content identity keeps disclosures open when a live turn becomes history. */
private fun LazyListScope.assistantMessages(
    turnId: String,
    messages: List<dev.memoh.core.model.UIMessage>,
    isRunning: Boolean,
    onToggleDetails: () -> Unit,
    focusMessageIndex: Int? = null,
    focusRevision: Int = 0,
    onFocusHandled: () -> Unit = {},
) {
    val renderItems = assistantRenderItems(turnId, messages)
    val focusItem = focusMessageIndex?.let { target -> renderItems.indexOfFirst { it.lastIndex >= target } }
    itemsIndexed(renderItems, key = { _, item -> item.key }) { index, item ->
        Box(Modifier.padding(top = if (index == 0) 10.dp else 0.dp)) {
            AssistantMessageItem(item, isStreaming = isRunning && index == renderItems.lastIndex,
                isRunActive = isRunning, onToggleDetails = onToggleDetails,
                focusMessage = if (index == focusItem) focusMessageIndex?.let(messages::getOrNull) else null,
                focusRevision = focusRevision, onFocusHandled = onFocusHandled)
        }
    }
}
/**
 * The conversation screen.
 *
 * Layout follows the reading-flow decision: the transcript is a centred column
 * of at most 840dp so long answers stay readable on a tablet, while the composer
 * and any pending decision sit directly above the keyboard where the user's
 * attention already is.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    onBack: () -> Unit,
    onSend: (String) -> Unit,
    onStop: () -> Unit,
    onDraftChange: (String) -> Unit,
    onLoadOlder: () -> Unit,
    onRetryHistory: () -> Unit = {},
    onRegenerate: (String) -> Unit = {},
    onFork: (String) -> Unit = {},
    onApprove: (runId: String, approvalId: String, optionId: String?) -> Unit,
    onReject: (runId: String, approvalId: String, reason: String?) -> Unit,
    onUserInput: (runId: String, userInputId: String, answers: List<dev.memoh.core.model.WSUserInputAnswer>) -> Unit,
    onCancelUserInput: (runId: String, userInputId: String) -> Unit,
    onDismissError: () -> Unit,
    onSelectModel: (String) -> Unit = {},
    onSelectTarget: (String?) -> Unit = {},
    onSelectWorkdir: (String?) -> Unit = {},
    onSelectAgent: (String?) -> Unit = {},
    onSelectEffort: (String?) -> Unit = {},
    /** Opens one of the bot's other surfaces (terminal, browser, desktop). */
    onOpenSurface: (BotSurface) -> Unit = {},
    onOpenControls: () -> Unit = {},
    onPromoteQueue: (dev.memoh.core.model.QueueItem) -> Unit = {},
    onRemoveQueued: (dev.memoh.core.model.QueueItem, Boolean) -> Unit = { _, _ -> },
    onNewSession: () -> Unit = {},
    onRefreshContext: () -> Unit = {},
    onInspectContext: () -> Unit = {},
    onPinShortcut: (() -> Unit)? = null,
    onOpenFeature: (String) -> Unit = {},
    onRefreshSuggestions: () -> Unit = {},
    onConfigureExample: (String) -> Unit = {},
    onPickAttachment: (Boolean) -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onVoiceInput: (() -> Unit)? = null,
    onRemoveAttachment: (Int) -> Unit = {},
    onCancelAttachments: () -> Unit = {},
    onConfirmFolder: () -> Unit = {},
    onCancelFolder: () -> Unit = {},
    onDismissCommandOutput: () -> Unit = {},
    onRefreshSlashCatalog: () -> Unit = {},
    onQuickAction: (String) -> Unit = {},
    onToggleSavedTurn: (dev.memoh.core.model.UITurn) -> Unit = {},
    onRemoveSavedItem: (dev.memoh.core.data.SavedChatItem) -> Unit = {},
    onLoadSearchCache: suspend () -> List<dev.memoh.core.model.UITurn> = { emptyList() },
    externalSearchHit: dev.memoh.core.model.ConversationMatch? = null,
    externalCachedTurn: dev.memoh.core.model.UITurn? = null,
    onExternalSearchConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val chatContext = androidx.compose.ui.platform.LocalContext.current
    val errorActionHandler = dev.memoh.core.designsystem.component.LocalErrorActionHandler.current
    val scope = rememberCoroutineScope()

    val settled = state.settledHistory
    val assistantKeys = remember(settled) { assistantHistoryKeys(settled) }
    val retryableTurnId = state.retryableTurnId
    val retryableRowKey = if (retryableTurnId != null) state.history.lastOrNull { it.isAssistant }?.listKey else null
    val liveMessages = state.liveMessages
    val liveUsers = state.liveUserTurns
    val run = state.runtime.run
    val savedTurns = remember(settled, liveUsers, run, liveMessages) { state.turnsForSavedItems() }
    val savedTurnKeys = remember(state.savedItems, savedTurns) {
        state.savedItems.mapNotNull { dev.memoh.core.data.resolveSavedChatTurn(it, savedTurns)?.listKey }.toSet()
    }
    val composerAction = state.composerAction()
    fun runShortcut(action: ComposerQuickAction, openModelMenu: () -> Unit) {
        if (!state.composerAction(action.command).enabled) return
        onDraftChange("")
        when (action) {
            ComposerQuickAction.Help -> onQuickAction("help")
            ComposerQuickAction.Skills -> onQuickAction("skill.list")
            ComposerQuickAction.New -> onNewSession()
            ComposerQuickAction.Model -> openModelMenu()
            ComposerQuickAction.Permission, ComposerQuickAction.Plan, ComposerQuickAction.Goal -> onOpenControls()
        }
    }
    var initiallyPositioned by rememberSaveable(state.session?.id) { mutableStateOf(false) }
    val showEmptyConversation = state.hasResolvedHistory && state.isEmptyConversation && state.historyError == null
    val showHistoryPlaceholder = state.historyError == null &&
        (!state.hasResolvedHistory && state.isEmptyConversation || !state.isEmptyConversation && !initiallyPositioned)
    val historyPlaceholder = remember(state.session?.id) {
        MutableTransitionState(showHistoryPlaceholder)
    }
    historyPlaceholder.targetState = showHistoryPlaceholder
    var followingLatest by rememberSaveable(state.session?.id) { mutableStateOf(true) }
    var searchOpen by remember(state.session?.id) { mutableStateOf(false) }
    var savedOpen by remember(state.session?.id) { mutableStateOf(false) }
    var searchFocus by remember(state.session?.id) { mutableStateOf<ConversationHit?>(null) }
    var searchFocusRevision by remember(state.session?.id) { mutableIntStateOf(0) }
    var searchCache by remember(state.session?.id) { mutableStateOf<List<dev.memoh.core.model.UITurn>>(emptyList()) }
    var cachedPreview by remember(state.session?.id) { mutableStateOf<Pair<dev.memoh.core.model.UITurn, ConversationHit>?>(null) }
    LaunchedEffect(searchOpen, savedOpen, state.session?.id) {
        if (searchOpen || savedOpen) searchCache = onLoadSearchCache()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    var resumed by remember(lifecycleOwner) {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    var resumeScrollPending by remember(state.session?.id) { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { resumed = false }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        resumeScrollPending = true
        resumed = true
    }
    val connection = connectionMessage(state.socketStatus, state.awaitingSnapshot, state.runtime.sessionId == null)
    val connectionAttention = connectionNeedsAttention(state.session?.id, state.socketStatus, state.awaitingSnapshot, resumed)
    var jumpingToLatest by remember(state.session?.id) { mutableStateOf(false) }
    var loadingHistoryImages by remember(state.session?.id) { mutableIntStateOf(0) }
    val onHistoryImageLoading = remember(state.session?.id) {
        { loading: Boolean -> loadingHistoryImages += if (loading) 1 else -1 }
    }
    val latestState by androidx.compose.runtime.rememberUpdatedState(state)
    fun jumpToHit(hit: ConversationHit) {
        searchOpen = false; savedOpen = false; followingLatest = false
        scope.launch {
            if (latestState.settledHistory.none { it.listKey == hit.turnKey }) {
                val current = latestState
                val userIndex = current.liveUserTurns.indexOfFirst { it.listKey == hit.turnKey }
                if (userIndex >= 0) {
                    listState.scrollToItem(conversationHistoryRowCount(current.settledHistory) + userIndex)
                    return@launch
                }
                val liveRun = current.runtime.run
                if (liveRun != null && current.liveMessages.isNotEmpty() && hit.turnKey == "assistant:${liveRun.turnId}") {
                    val block = hit.messageIndex?.let { target ->
                        assistantRenderItems(liveRun.turnId, current.liveMessages).indexOfFirst { it.lastIndex >= target }
                    } ?: 0
                    searchFocus = hit; searchFocusRevision++
                    listState.scrollToItem(conversationHistoryRowCount(current.settledHistory) +
                        current.liveUserTurns.size + current.pending.size + block.coerceAtLeast(0))
                    return@launch
                }
                val cached = searchCache.firstOrNull { it.listKey == hit.turnKey }
                    ?: onLoadSearchCache().firstOrNull { it.listKey == hit.turnKey }
                if (cached != null) cachedPreview = cached to hit
                else android.widget.Toast.makeText(chatContext, "消息未在已加载历史或本地缓存中", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            searchFocus = hit
            searchFocusRevision++
            androidx.compose.runtime.withFrameNanos { }
            conversationHitIndex(latestState.settledHistory, hit)?.let { listState.scrollToItem(it) }
        }
    }
    val loadOlder by androidx.compose.runtime.rememberUpdatedState(onLoadOlder)
    val historyScroll = remember(listState) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val current = latestState
                // Reaching index zero through layout/following a reply is not a request for old history.
                if (source == NestedScrollSource.UserInput && consumed.y + available.y > 0f && initiallyPositioned &&
                    listState.firstVisibleItemIndex == 0 &&
                    current.hasMoreHistory && !current.historyLoading && !current.loadingMore &&
                    current.historyError == null && current.olderHistoryError == null) loadOlder()
                return Offset.Zero
            }
        }
    }
    val dragging by listState.interactionSource.collectIsDraggedAsState()
    val scrollSpec = MaterialTheme.motionScheme.fastSpatialSpec<Float>()
    val transcriptAlpha by animateFloatAsState(
        targetValue = if (initiallyPositioned) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "initial history reveal",
    )

    // Follow new content only while the user is already at the bottom; a reader
    // who scrolled up must not be yanked back by a streaming token.
    val atBottom by remember {
        androidx.compose.runtime.derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            last == null || (last.index == listState.layoutInfo.totalItemsCount - 1 &&
                last.offset + last.size <= listState.layoutInfo.viewportEndOffset + 12)
        }
    }
    LaunchedEffect(externalSearchHit, state.session?.id, initiallyPositioned) {
        val hit = externalSearchHit
        if (hit != null && state.session != null && initiallyPositioned) {
            val loaded = state.settledHistory.firstOrNull { it.listKey == hit.turnKey }
            if (loaded != null && (externalCachedTurn == null || sameCachedSearchTarget(hit, externalCachedTurn, loaded))) jumpToHit(hit)
            else if (externalCachedTurn?.listKey == hit.turnKey) cachedPreview = externalCachedTurn to hit
            else jumpToHit(hit)
            onExternalSearchConsumed()
        }
    }
    LaunchedEffect(dragging) {
        if (dragging) followingLatest = false
        else {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }.first { !it }
            followingLatest = atBottom
        }
    }
    LaunchedEffect(state.session?.id, resumed) {
        if (!resumed) return@LaunchedEffect
        // Layout also changes while a reasoning/phase panel is folding, after
        // the last socket frame. Follow those frames as well as text appends.
        androidx.compose.runtime.snapshotFlow { Triple(latestState, listState.layoutInfo, loadingHistoryImages) }.conflate().collect {
            androidx.compose.runtime.withFrameNanos { }
            if (latestState.hasResolvedHistory && latestState.isEmptyConversation) {
                initiallyPositioned = true
                return@collect
            }
            if (jumpingToLatest) return@collect
            if ((!initiallyPositioned || followingLatest) && listState.layoutInfo.totalItemsCount > 0) {
                // A drag can cancel this scroll without cancelling the follower.
                coroutineScope {
                    launch { listState.scrollToLatest(scrollSpec.takeIf { initiallyPositioned && !resumeScrollPending }) }
                }
                // The first scroll may need another measurement, especially
                // when the last Markdown block is taller than the viewport.
                androidx.compose.runtime.withFrameNanos { }
                // Images keep their own placeholders. Reveal readable content as
                // soon as it is positioned; later image growth still follows below.
                if (atBottom) {
                    initiallyPositioned = true
                    resumeScrollPending = false
                }
            }
        }
    }
    if (searchOpen) ConversationSearchDialog(mergeLatestHistory(searchCache, savedTurns), { searchOpen = false }, ::jumpToHit)
    if (savedOpen) SavedItemsDialog(state.savedItems, { savedOpen = false }, { saved ->
        savedOpen = false
        scope.launch {
            val target = dev.memoh.core.data.resolveSavedChatTurn(saved, latestState.turnsForSavedItems())
                ?: dev.memoh.core.data.resolveSavedChatTurn(saved, searchCache.ifEmpty { onLoadSearchCache() })
            if (target != null) jumpToHit(ConversationHit(target.listKey, null, "", ""))
            else android.widget.Toast.makeText(chatContext, "消息未在已加载历史或本地缓存中", android.widget.Toast.LENGTH_SHORT).show()
        }
    }, onRemoveSavedItem)
    cachedPreview?.let { (turn, hit) -> CachedTurnSheet(turn, hit, { cachedPreview = null }) }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                        )
                    }
                },
                title = {
                    Text(
                        text = state.session?.title?.takeIf(String::isNotBlank) ?: "会话",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    IconButton(onClick = { searchOpen = true }) { Icon(Icons.Filled.Search, "查找会话") }
                    var moreOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { moreOpen = !moreOpen }, modifier = Modifier.semantics {
                            if (connectionAttention) {
                                stateDescription = connection.first
                                liveRegion = LiveRegionMode.Polite
                            }
                        }) {
                            BadgedBox(badge = {
                                // The badge is an overlay; its entrance never resizes the action.
                                Box(Modifier.size(6.dp).clearAndSetSemantics {}) {
                                    androidx.compose.animation.AnimatedVisibility(connectionAttention,
                                        enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                                        exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())) {
                                        // Recovery fades the attention dot out without flashing an online color.
                                        MemohStatusDot(MemohStatus.Warning, size = 6.dp)
                                    }
                                }
                            }) { Icon(Icons.Filled.MoreVert, contentDescription = "更多") }
                        }
                        // Conversation actions stay together; workspace surfaces
                        // are opened from the adjacent + control.
                        MemohPopupMenu(
                            expanded = moreOpen,
                            onDismiss = { moreOpen = false },
                            alignment = Alignment.BottomEnd,
                        ) {
                            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("连接状态", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Row(verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    MemohStatusDot(connection.second, size = 6.dp)
                                    Text(connection.first, style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface, maxLines = 2,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                            MemohMenuDivider()
                            MemohMenuRow("收藏", Icons.Filled.Bookmark, { moreOpen = false; savedOpen = true })
                            MemohMenuRow("会话控制", Icons.Filled.Tune, { moreOpen = false; onOpenControls() })
                            onPinShortcut?.let { pin -> MemohMenuRow("固定到主屏幕", Icons.Filled.PushPin, { moreOpen = false; pin() }) }
                            MemohMenuRow("记忆", Icons.Filled.Psychology, { moreOpen = false; onOpenFeature("Memory") })
                            MemohMenuRow("日程", Icons.Filled.CalendarMonth, { moreOpen = false; onOpenFeature("Schedules") })
                        }
                    }
                    var addOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { addOpen = !addOpen }) { Icon(Icons.Filled.Add, "打开工作区") }
                        MemohPopupMenu(addOpen, { addOpen = false }, Alignment.BottomEnd) {
                            MemohMenuRow("新会话", Icons.AutoMirrored.Filled.Chat, { addOpen = false; onNewSession() })
                            BotSurface.entries.filter { it != BotSurface.Chat }.filter { surface ->
                                state.botPermissions == null || (if (surface == BotSurface.Terminal) "workspace_exec" else "manage") in state.botPermissions
                            }.forEach { surface ->
                                MemohMenuRow(
                                    title = surface.label,
                                    icon = surface.icon,
                                    onClick = {
                                        addOpen = false
                                        onOpenSurface(surface)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
        ) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    state.historyError != null && settled.isEmpty() -> {
                        Column(Modifier.align(Alignment.Center).padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text("历史消息未能加载", style = MaterialTheme.typography.titleMedium)
                            Text(state.historyError, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            MemohActionButton("重试", Icons.Filled.Refresh, onRetryHistory, primary = true)
                        }
                    }

                    showEmptyConversation -> Unit

                    else -> CompositionLocalProvider(LocalMarkdownImageLoading provides onHistoryImageLoading) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                                .nestedScroll(historyScroll)
                                .graphicsLayer { alpha = transcriptAlpha }
                                .then(if (initiallyPositioned) Modifier else Modifier.clearAndSetSemantics { }),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 12.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            settled.forEach { turn ->
                                val assistantKey = assistantKeys[turn.listKey] ?: turn.turnId
                                if (turn.isAssistant && turn.safeMessages.isNotEmpty()) {
                                    assistantMessages(assistantKey, turn.safeMessages, isRunning = false,
                                        onToggleDetails = { followingLatest = false },
                                        focusMessageIndex = searchFocus?.takeIf { it.turnKey == turn.listKey }?.messageIndex,
                                        focusRevision = searchFocusRevision, onFocusHandled = { searchFocus = null })
                                } else item(key = "turn-${turn.listKey}") {
                                    TurnItem(turn, Modifier.padding(top = 10.dp),
                                        isSaved = turn.listKey in savedTurnKeys,
                                        onSave = if (turn.isUser) {{ onToggleSavedTurn(turn) }} else null)
                                }
                                if (turn.isAssistant) item(key = "assistant-$assistantKey:actions") {
                                    MessageActions(replyCopyText(turn.safeMessages), turn.timestamp, assistant = true,
                                        enabled = !state.busy && !state.isRunning,
                                        retryEnabled = retryableRowKey == turn.listKey,
                                        onRetry = { onRegenerate(turn.turnId) },
                                        onFork = if (state.canFork(turn)) {{ onFork(turn.turnId) }} else null,
                                        isSaved = turn.listKey in savedTurnKeys,
                                        onSave = { onToggleSavedTurn(turn) })
                                }
                            }

                            items(liveUsers, key = { "turn-${it.listKey}" }) { turn ->
                                TurnItem(turn, Modifier.padding(top = 10.dp), isSaved = turn.listKey in savedTurnKeys,
                                    onSave = { onToggleSavedTurn(turn) })
                            }

                            state.pending.forEach { pending ->
                                item(key = "pending-${pending.invocationId}") {
                                    PendingUserTurn(pending.text, Modifier.padding(top = 10.dp))
                                }
                            }

                            // The live turn renders from the run view, which is
                            // authoritative while it is in flight.
                            if (liveMessages.isNotEmpty() && run != null) {
                                assistantMessages(run.turnId, liveMessages, isRunning = !run.isTerminal,
                                    onToggleDetails = { followingLatest = false },
                                    focusMessageIndex = searchFocus?.takeIf { it.turnKey == "assistant:${run.turnId}" }?.messageIndex,
                                    focusRevision = searchFocusRevision, onFocusHandled = { searchFocus = null })
                                item(key = "assistant-${run.turnId}:actions") {
                                    if (!run.isTerminal) Spacer(Modifier.height(MessageActionsHeight)) else {
                                    val turn = dev.memoh.core.model.UITurn(turnId = run.turnId, role = "assistant",
                                        timestamp = run.updatedAt.orEmpty(), messages = liveMessages)
                                    MessageActions(replyCopyText(liveMessages), turn.timestamp, assistant = true,
                                        enabled = !state.busy && !state.isRunning,
                                        retryEnabled = retryableTurnId == run.turnId,
                                        onRetry = { onRegenerate(run.turnId) },
                                        onFork = if (state.canFork(turn)) {{ onFork(run.turnId) }} else null,
                                        isSaved = turn.listKey in savedTurnKeys,
                                        onSave = { onToggleSavedTurn(turn) })
                                    }
                                }
                            }

                            if ((state.isRunning || state.pending.isNotEmpty()) && liveMessages.isEmpty()) {
                                item(key = "typing") {
                                    TypingIndicator(modifier = Modifier.padding(start = 4.dp))
                                }
                            }
                        }
                    }
                }

                androidx.compose.animation.AnimatedVisibility(
                    visible = showEmptyConversation,
                    modifier = Modifier.align(Alignment.Center),
                    enter = androidx.compose.animation.fadeIn(MemohMotion.enterSpec()),
                    exit = androidx.compose.animation.fadeOut(MemohMotion.exitSpec()),
                ) {
                    EmptyConversation(state, onDraftChange, onRefreshSuggestions, onConfigureExample)
                }

                androidx.compose.animation.AnimatedVisibility(
                    visibleState = historyPlaceholder,
                    enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                ) {
                    ChatHistoryPlaceholder()
                }

                // Pagination status is an overlay: confirming there are no older
                // turns must not remove a row and shift a short conversation.
                androidx.compose.animation.AnimatedVisibility(
                    visible = initiallyPositioned && !historyPlaceholder.currentState && !historyPlaceholder.targetState &&
                        state.loadingMore && listState.firstVisibleItemIndex == 0,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                ) {
                    ContainedLoadingIndicator(Modifier.semantics { contentDescription = "正在加载更早消息" })
                }

                // Jump-to-bottom, shown only when the reader has scrolled away.
                androidx.compose.animation.AnimatedVisibility(
                    visible = initiallyPositioned && !atBottom && !followingLatest && !jumpingToLatest,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp),
                    enter = MemohMotion.controlEnter(), exit = MemohMotion.controlExit(),
                ) {
                    val interaction = remember { MutableInteractionSource() }
                    val pressed by interaction.collectIsPressedAsState()
                    val scale by animateFloatAsState(if (pressed) .92f else 1f,
                        MaterialTheme.motionScheme.fastEffectsSpec(), label = "jumpToLatestPress")
                    SmallFloatingActionButton(
                            modifier = Modifier.size(40.dp).graphicsLayer { scaleX = scale; scaleY = scale },
                            shape = CircleShape,
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                            contentColor = MaterialTheme.colorScheme.primary,
                            elevation = FloatingActionButtonDefaults.loweredElevation(),
                            interactionSource = interaction,
                            onClick = {
                                jumpingToLatest = true
                                scope.launch {
                                    try { listState.scrollToLatest(scrollSpec) }
                                    finally { jumpingToLatest = false; followingLatest = atBottom }
                                }
                            },
                        ) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "回到最新", modifier = Modifier.size(20.dp))
                        }
                }
            }

            state.offlineSyncedAt?.takeIf { state.historyError != null }?.let { synced ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("缓存历史 · 最后同步 " + java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.ofEpochMilli(synced)),
                            Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = onRetryHistory) { Text("刷新") }
                    }
                }
            }
            state.olderHistoryError?.let { error ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.large,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    Row(Modifier.padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(error, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = onLoadOlder, enabled = !state.loadingMore) { Text("加载更早消息") }
                    }
                }
            }
            if (state.session?.isLocal == false) {
                ExternalSessionNotice(
                    channel = state.session?.channelType ?: "外部渠道",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(modifier = Modifier.widthIn(max = 704.dp).fillMaxWidth()) {
                        Composer(
                            text = state.draft,
                            onTextChange = onDraftChange,
                            onVoiceInput = onVoiceInput,
                            onSend = { openModelMenu ->
                                val shortcut = state.composerQuickAction(state.draft)
                                if (composerAction.kind == ComposerActionKind.QuickAction && shortcut != null)
                                    runShortcut(shortcut, openModelMenu)
                                else onSend(state.draft)
                            },
                            sendDescription = composerAction.description,
                            showStop = composerAction.kind == ComposerActionKind.Stop,
                            onStop = onStop,
                            isRunning = state.isRunning,
                            // Controls stay usable while the socket reconnects;
                            // only the send action itself needs a connection.
                            enabled = state.canCompose,
                            textEnabled = state.canEditDraft,
                            sendEnabled = composerAction.enabled,
                            compactForSuggestions = state.canCompose && slashMenuQuery(state.draft) != null,
                            hasAttachments = state.attachments.isNotEmpty(),
                            attachmentLoading = state.attachmentLoading,
                            attachments = {
                                ComposerAttachments(state.attachments, state.attachmentLoading,
                                    onRemoveAttachment, onCancelAttachments)
                            },
                            modelLabel = state.modelLabel,
                            modelLoading = state.modelCatalogLoading,
                            // The buttons own their open state and drive the
                            // menu slots; these two only signal that a menu
                            // exists, which is what makes the controls tappable.
                            onModelClick = {},
                            onAttachClick = {},
                            capabilities = state.capabilities(
                                onSelectTarget = onSelectTarget,
                                onSelectWorkdir = onSelectWorkdir,
                                onSelectAgent = onSelectAgent,
                            ),
                            contextIndicator = { SessionContextIndicator(state, onRefreshContext, onInspectContext) },
                            plusMenu = { expanded, dismiss ->
                                MemohPopupMenu(
                                    expanded = expanded,
                                    onDismiss = dismiss,
                                    alignment = Alignment.BottomStart,
                                ) {
                                    MemohMenuRow(
                                        title = "照片",
                                        index = 0, count = 3,
                                        icon = Icons.Filled.Image,
                                        accent = MaterialTheme.colorScheme.primaryContainer,
                                        onClick = { dismiss(); onPickAttachment(true) },
                                    )
                                    MemohMenuRow(
                                        title = "拍照",
                                        index = 1, count = 3,
                                        icon = Icons.Filled.PhotoCamera,
                                        accent = MaterialTheme.colorScheme.tertiaryContainer,
                                        onClick = { dismiss(); onTakePhoto() },
                                    )
                                    MemohMenuRow(
                                        title = "文件",
                                        index = 2, count = 3,
                                        icon = Icons.Filled.AttachFile,
                                        accent = MaterialTheme.colorScheme.secondaryContainer,
                                        onClick = { dismiss(); onPickAttachment(false) },
                                    )
                                    MemohMenuDivider()
                                    MemohMenuRow(
                                        title = "应用",
                                        icon = Icons.Filled.Extension,
                                        accent = MaterialTheme.colorScheme.tertiaryContainer,
                                        subtitle = "连接器与 MCP 服务",
                                        onClick = { dismiss(); onOpenFeature("Apps") },
                                    )
                                }
                            },
                            modelMenu = { expanded, dismiss ->
                                ModelMenu(
                                    expanded = expanded,
                                    onDismiss = dismiss,
                                    models = state.models,
                                    providers = state.modelProviders,
                                    selectedModelId = state.selectedModelId,
                                    reasoningEffort = state.reasoningEffort,
                            onSelectModel = onSelectModel,
                                    onSelectEffort = onSelectEffort,
                                    enabled = !state.busy,
                                )
                            },
                            above = { openModelMenu ->
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    state.commandOutput?.let { output ->
                                        dev.memoh.feature.chat.components.RuntimeCommandOutput(output, onDismissCommandOutput)
                                    }
                                    dev.memoh.feature.chat.components.RuntimeCommandSuggestions(state.draft,
                                        state.controls?.commands.orEmpty(), state.canEditDraft, onDraftChange,
                                        skills = if (state.nativeSkillsEnabled) state.skillCatalog else emptyList(),
                                        nativeSkills = state.nativeSkillsEnabled,
                                        loading = if (state.nativeSkillsEnabled) state.skillCatalogLoading
                                            else state.controlsLoading && state.controls?.commands.isNullOrEmpty(),
                                        error = if (state.nativeSkillsEnabled) state.skillCatalogError else state.controlsError,
                                        onRetry = onRefreshSlashCatalog,
                                        quickActions = state.composerQuickActions(),
                                        quickActionEnabled = { state.composerAction(it.command).enabled },
                                        onQuickAction = { runShortcut(it, openModelMenu) })
                                    ComposerQueue(state.queue, state.isRunning, state.busy,
                                        onOpenControls, onPromoteQueue, onRemoveQueued)
                                    run?.let { current ->
                                        current.retry?.takeUnless { current.isTerminal }?.let { retry ->
                                            RunPhaseLine(phase = retry.description())
                                        }
                                        // Pending decisions are handled in order,
                                        // one at a time.
                                        val pendingApproval = liveMessages.firstOrNull {
                                            it.approval?.isActionable == true
                                        }
                                        if (pendingApproval != null) {
                                            val approval = pendingApproval.approval!!
                                            ApprovalPanel(
                                                approval = approval,
                                                toolName = pendingApproval.name,
                                                toolInput = pendingApproval.input?.toString(),
                                                onApprove = { optionId ->
                                                    onApprove(current.runId, approval.approvalId, optionId)
                                                },
                                                onReject = { reason ->
                                                    onReject(current.runId, approval.approvalId, reason)
                                                },
                                            )
                                        }
                                        val pendingInput = liveMessages.firstOrNull {
                                            it.userInput?.isActionable == true
                                        }
                                        if (pendingInput != null) {
                                            val input = pendingInput.userInput!!
                                            UserInputPanel(
                                                input = input,
                                                onRespond = { answers ->
                                                    onUserInput(current.runId, input.userInputId, answers)
                                                },
                                                onCancel = {
                                                    onCancelUserInput(current.runId, input.userInputId)
                                                },
                                            )
                                        }
                                    }
                                    state.error?.let { message ->
                                        state.errorProblem?.takeIf { it.text() == message }?.action?.let { action ->
                                            if (errorActionHandler != null && state.botPermissions?.contains("manage") == true)
                                                TextButton({ errorActionHandler(action) }) { Text(action.title) }
                                        }
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                text = message,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.weight(1f),
                                            )
                                            Text(
                                                text = "知道了",
                                                style = MaterialTheme.typography.labelMedium,
                                                color = MaterialTheme.colorScheme.primary,
                                                modifier = Modifier
                                                    .clickable(onClick = onDismissError)
                                                    .padding(4.dp),
                                            )
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
    if (state.pendingFolderChange) AlertDialog(
        onDismissRequest = onCancelFolder,
        title = { Text("在此文件夹中新建会话") },
        text = { Text("会话创建后无法改绑文件夹。将新建一个会话，现有会话和历史会保留。") },
        confirmButton = { MemohActionButton("新建会话", Icons.Filled.Add, onConfirmFolder, enabled = !state.busy, primary = true) },
        dismissButton = { MemohActionButton("取消", Icons.Filled.Close, onCancelFolder, enabled = !state.busy) },
    )

}

private suspend fun LazyListState.scrollToLatest(animationSpec: AnimationSpec<Float>? = null) {
    val lastIndex = layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    if (layoutInfo.visibleItemsInfo.none { it.index == lastIndex }) {
        if (animationSpec == null) scrollToItem(lastIndex) else animateScrollToItem(lastIndex)
    }
    val last = layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastIndex } ?: return
    val overflow = last.offset + last.size + layoutInfo.afterContentPadding - layoutInfo.viewportEndOffset
    if (overflow > 0) {
        if (animationSpec == null) scrollBy(overflow.toFloat())
        else animateScrollBy(overflow.toFloat(), animationSpec)
    }
}

private fun connectionMessage(status: SocketStatus, awaitingSnapshot: Boolean, initialLoading: Boolean): Pair<String, MemohStatus> = when {
    status == SocketStatus.Closed -> "连接已断开" to MemohStatus.Offline
    status == SocketStatus.Connected && !awaitingSnapshot -> "已连接" to MemohStatus.Online
    initialLoading -> "正在连接会话…" to MemohStatus.Warning
    status == SocketStatus.Connected -> "正在同步会话…" to MemohStatus.Warning
    else -> "正在恢复连接…" to MemohStatus.Warning
}

/** Connection feedback stays outside the title; brief handshakes remain quiet. */
@Composable
private fun connectionNeedsAttention(sessionId: String?, status: SocketStatus, awaitingSnapshot: Boolean,
    resumed: Boolean): Boolean {
    val waiting = status != SocketStatus.Connected || awaitingSnapshot
    var showWaiting by remember(sessionId, waiting, resumed) { mutableStateOf(false) }
    LaunchedEffect(sessionId, waiting, resumed) {
        if (waiting && resumed) {
            kotlinx.coroutines.delay(1_500)
            showWaiting = true
        }
    }
    return resumed && waiting && (status == SocketStatus.Closed || showWaiting)
}

@Composable
private fun EmptyConversation(state: ChatUiState, onPick: (String) -> Unit, onRefresh: () -> Unit,
    onConfigure: (String) -> Unit, modifier: Modifier = Modifier) {
    ConversationSuggestions(state, onPick, onRefresh, onConfigure, modifier)
}
