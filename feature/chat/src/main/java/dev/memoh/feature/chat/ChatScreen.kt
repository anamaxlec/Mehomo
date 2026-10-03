package dev.memoh.feature.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
    onNewSession: () -> Unit = {},
    onRefreshContext: () -> Unit = {},
    onCompactContext: () -> Unit = {},
    onOpenFeature: (String) -> Unit = {},
    onPickAttachment: (Boolean) -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onRemoveAttachment: (Int) -> Unit = {},
    onCancelAttachments: () -> Unit = {},
    onConfirmFolder: () -> Unit = {},
    onCancelFolder: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val settled = state.settledHistory
    val liveMessages = state.liveMessages
    val liveUsers = state.liveUserTurns
    val run = state.runtime.run
    var initiallyPositioned by remember(state.session?.id) { mutableStateOf(false) }
    val historyPlaceholder = remember(state.session?.id) {
        MutableTransitionState(!initiallyPositioned && state.historyError == null)
    }
    historyPlaceholder.targetState = !initiallyPositioned && state.historyError == null
    var followingLatest by remember(state.session?.id) { mutableStateOf(true) }
    var jumpingToLatest by remember(state.session?.id) { mutableStateOf(false) }
    var loadingHistoryImages by remember(state.session?.id) { mutableIntStateOf(0) }
    val onHistoryImageLoading = remember(state.session?.id) {
        { loading: Boolean -> loadingHistoryImages += if (loading) 1 else -1 }
    }
    val latestState by androidx.compose.runtime.rememberUpdatedState(state)
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
    LaunchedEffect(dragging) {
        if (dragging) followingLatest = false
        else {
            androidx.compose.runtime.withFrameNanos { }
            androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }.first { !it }
            followingLatest = atBottom
        }
    }
    LaunchedEffect(state.session?.id) {
        // Layout also changes while a reasoning/phase panel is folding, after
        // the last socket frame. Follow those frames as well as text appends.
        androidx.compose.runtime.snapshotFlow { Triple(latestState, listState.layoutInfo, loadingHistoryImages) }.conflate().collect {
            androidx.compose.runtime.withFrameNanos { }
            if (latestState.historyLoading) return@collect
            if (jumpingToLatest) return@collect
            if (latestState.settledHistory.isEmpty() && latestState.liveMessages.isEmpty() &&
                latestState.liveUserTurns.isEmpty() && latestState.pending.isEmpty() && !latestState.isRunning) {
                initiallyPositioned = true
                return@collect
            }
            if ((!initiallyPositioned || followingLatest) && listState.layoutInfo.totalItemsCount > 0) {
                // A drag can cancel this scroll without cancelling the follower.
                coroutineScope {
                    launch { listState.scrollToLatest(scrollSpec.takeIf { initiallyPositioned }) }
                }
                // The first scroll may need another measurement, especially
                // when the last Markdown block is taller than the viewport.
                androidx.compose.runtime.withFrameNanos { }
                // A decoded image can grow beyond its loading placeholder.
                // Keep the first reveal covered until that height is measured.
                if (atBottom && loadingHistoryImages == 0) initiallyPositioned = true
            }
        }
    }
    LaunchedEffect(initiallyPositioned, state.hasMoreHistory, state.historyLoading,
        state.loadingMore, state.historyError, state.olderHistoryError) {
        if (!initiallyPositioned || !state.hasMoreHistory || state.historyLoading ||
            state.loadingMore || state.historyError != null || state.olderHistoryError != null) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }.first { it == 0 }
        onLoadOlder()
    }

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
                    Column {
                        Text(
                            text = state.session?.title?.takeIf(String::isNotBlank)
                                ?: "会话",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ConnectionLine(
                            status = state.socketStatus,
                            isRunning = state.isRunning,
                        )
                    }
                },
                actions = {
                    var moreOpen by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { moreOpen = !moreOpen }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "更多")
                        }
                        // Conversation actions stay together; workspace surfaces
                        // are opened from the adjacent + control.
                        MemohPopupMenu(
                            expanded = moreOpen,
                            onDismiss = { moreOpen = false },
                            alignment = Alignment.BottomEnd,
                        ) {
                            MemohMenuRow("会话控制", Icons.Filled.Tune, { moreOpen = false; onOpenControls() })
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

                    !state.historyLoading && settled.isEmpty() && liveMessages.isEmpty() && liveUsers.isEmpty() && state.pending.isEmpty() && !state.isRunning -> {
                        EmptyConversation(
                            onPick = onSend,
                            modifier = Modifier.align(Alignment.Center)
                                .graphicsLayer { alpha = transcriptAlpha },
                        )
                    }

                    else -> CompositionLocalProvider(LocalMarkdownImageLoading provides onHistoryImageLoading) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                                .graphicsLayer { alpha = transcriptAlpha }
                                .then(if (initiallyPositioned) Modifier else Modifier.clearAndSetSemantics { }),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                horizontal = 12.dp,
                                vertical = 12.dp,
                            ),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            settled.forEach { turn ->
                                if (turn.isAssistant && turn.safeMessages.isNotEmpty()) {
                                    val keys = assistantBlockKeys(turn.turnId, turn.safeMessages)
                                    itemsIndexed(turn.safeMessages, key = { index, _ -> keys[index] }) { index, message ->
                                        Box(Modifier.padding(top = if (index == 0) 10.dp else 0.dp)) {
                                            MessageBlock(message, isStreaming = false, onToggleDetails = { followingLatest = false })
                                        }
                                    }
                                } else item(key = "turn-${turn.listKey}") {
                                    TurnItem(turn, Modifier.padding(top = 10.dp))
                                }
                                if (turn.isAssistant) item(key = "assistant-${turn.turnId}:actions") {
                                    MessageActions(replyCopyText(turn.safeMessages), turn.timestamp, assistant = true,
                                        enabled = !state.busy && !state.isRunning,
                                        retryEnabled = state.retryableTurnId == turn.turnId,
                                        onRetry = { onRegenerate(turn.turnId) },
                                        onFork = if (state.canFork(turn)) {{ onFork(turn.turnId) }} else null)
                                }
                            }

                            items(liveUsers, key = { "turn-${it.listKey}" }) { turn -> TurnItem(turn, Modifier.padding(top = 10.dp)) }

                            state.pending.forEach { pending ->
                                item(key = "pending-${pending.invocationId}") {
                                    PendingUserTurn(pending.text, Modifier.padding(top = 10.dp))
                                }
                            }

                            // The live turn renders from the run view, which is
                            // authoritative while it is in flight.
                            if (liveMessages.isNotEmpty() && run != null) {
                                val keys = assistantBlockKeys(run.turnId, liveMessages)
                                itemsIndexed(liveMessages, key = { index, _ -> keys[index] }) { index, message ->
                                    Box(Modifier.padding(top = if (index == 0) 10.dp else 0.dp)) {
                                        MessageBlock(message, isStreaming = run?.isTerminal == false && index == liveMessages.lastIndex,
                                            isRunActive = !run.isTerminal,
                                            onToggleDetails = { followingLatest = false })
                                    }
                                }
                                item(key = "assistant-${run.turnId}:actions") {
                                    if (!run.isTerminal) Spacer(Modifier.height(MessageActionsHeight)) else {
                                    val turn = dev.memoh.core.model.UITurn(turnId = run.turnId, role = "assistant",
                                        timestamp = run.updatedAt.orEmpty(), messages = liveMessages)
                                    MessageActions(replyCopyText(liveMessages), turn.timestamp, assistant = true,
                                        enabled = !state.busy && !state.isRunning,
                                        retryEnabled = state.retryableTurnId == run.turnId,
                                        onRetry = { onRegenerate(run.turnId) },
                                        onFork = if (state.canFork(turn)) {{ onFork(run.turnId) }} else null)
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
                    visibleState = historyPlaceholder,
                    enter = fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                    exit = fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                ) {
                    ChatHistoryPlaceholder()
                }

                // Pagination status is an overlay: confirming there are no older
                // turns must not remove a row and shift a short conversation.
                if (initiallyPositioned && !historyPlaceholder.currentState && !historyPlaceholder.targetState &&
                    state.loadingMore && listState.firstVisibleItemIndex == 0) {
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 8.dp)) {
                        ContainedLoadingIndicator()
                    }
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
                            onSend = { onSend(state.draft) },
                            onStop = onStop,
                            isRunning = state.isRunning,
                            // Controls stay usable while the socket reconnects;
                            // only the send action itself needs a connection.
                            enabled = state.canCompose,
                            sendEnabled = state.canSend,
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
                            contextIndicator = { SessionContextIndicator(state, onRefreshContext, onCompactContext) },
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
                            above = {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    run?.let { current ->
                                        AnimatedVisibility(
                                            visible = !current.isTerminal,
                                            enter = expandVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                                                fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                                            exit = shrinkVertically(MaterialTheme.motionScheme.fastSpatialSpec()) +
                                                fadeOut(MaterialTheme.motionScheme.fastEffectsSpec()),
                                        ) {
                                            RunPhaseLine(phase = phaseLabel(current.status))
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

/** Connection and run state, in one line so the two never compete for space. */
@Composable
private fun ConnectionLine(status: SocketStatus, isRunning: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when {
            isRunning -> {
                MemohStatusDot(MemohStatus.Running, size = 6.dp)
                Text(
                    text = "运行中",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            status == SocketStatus.Connected -> {
                MemohStatusDot(MemohStatus.Online, size = 6.dp)
                Text(
                    text = "已连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            status == SocketStatus.Connecting || status == SocketStatus.Reconnecting -> {
                MemohStatusDot(MemohStatus.Warning, size = 6.dp)
                Text(
                    text = "重连中…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            else -> {
                MemohStatusDot(MemohStatus.Offline, size = 6.dp)
                Text(
                    text = "未连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun EmptyConversation(
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        MemohEmptyState(
            title = "开始对话",
            description = "Bot 在它的云电脑上执行任务，你可以随时查看进度",
        )
        PromptStarters(
            starters = listOf(
                "帮我查一下最近的新闻",
                "总结一个网页的内容",
                "写一个脚本处理文件",
            ),
            onPick = onPick,
        )
    }
}

/** Maps a run status to the short phrase shown above the composer. */
private fun phaseLabel(status: String): String = when (status) {
    dev.memoh.core.model.RunStatus.ADMITTING -> "正在受理…"
    dev.memoh.core.model.RunStatus.RUNNING -> "正在处理…"
    dev.memoh.core.model.RunStatus.WAITING_DECISION -> "等待你的决定"
    dev.memoh.core.model.RunStatus.ABORTING -> "正在停止…"
    dev.memoh.core.model.RunStatus.FINISHING -> "正在收尾…"
    else -> "处理中…"
}
