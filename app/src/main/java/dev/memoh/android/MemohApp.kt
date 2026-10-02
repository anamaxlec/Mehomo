package dev.memoh.android

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.memoh.core.designsystem.component.MemohCornerFab
import dev.memoh.core.designsystem.component.MemohSectionBar
import dev.memoh.core.designsystem.component.collapseOnScroll
import dev.memoh.core.designsystem.motion.MemohMotion
import dev.memoh.feature.chat.ChatControlsSheet
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.memoh.feature.chat.ChatScreen
import dev.memoh.feature.chat.ChatViewModel
import dev.memoh.feature.login.CloudLoginScreen
import dev.memoh.feature.login.LoginStep
import dev.memoh.feature.login.LoginViewModel
import dev.memoh.feature.login.SelfHostedLoginScreen
import dev.memoh.feature.login.ServerPickerScreen
import dev.memoh.feature.login.TeamPickerScreen
import dev.memoh.feature.sessions.SessionsScreen
import dev.memoh.feature.sessions.SessionsViewModel
import dev.memoh.feature.sessions.BotFeature
import dev.memoh.feature.sessions.BotFeatureViewModel
import dev.memoh.feature.sessions.BotFeatureScreen
import dev.memoh.feature.sessions.BotFeatureState
import dev.memoh.feature.sessions.WorkspaceViewModel
import dev.memoh.feature.sessions.WorkspaceScreen
import dev.memoh.core.model.WorkspaceSurface
import dev.memoh.feature.settings.SettingsViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/**
 * The app's navigation graph.
 *
 * Two levels, and they behave differently on purpose:
 *
 *  - **Sections** (sessions / memory / schedules / settings) are peers, switched
 *    from the floating bar. Switching replaces the content rather than pushing,
 *    because a back stack of four top-level tabs is not a history a user wants
 *    to walk back through.
 *  - **Detail** (the chat screen) is pushed onto a stack, so back returns to the
 *    list it came from.
 *
 * The bar itself lives here rather than in each screen so it does not rebuild or
 * flicker as the section changes, and so its scroll-collapse state survives the
 * switch.
 */
@Composable
fun MemohApp(sharedContent: SharedContent? = null, onShareConsumed: () -> Unit = {},
    chatTarget: ChatTarget? = null, onTargetConsumed: () -> Unit = {}) {
    val navController = rememberNavController()
    val settings: SettingsViewModel = hiltViewModel()
    val session by settings.session.collectAsState()
    val start = remember { if (session.loggedIn) Routes.MAIN else Routes.LOGIN }
    var selectedShare by rememberSaveable(stateSaver = ShareSelectionSaver) { mutableStateOf<Pair<ChatTarget, SharedContent>?>(null) }
    if (session.loggedIn && sharedContent != null) ShareTargetDialog(onShareConsumed) { bot, id ->
        selectedShare = ChatTarget(session.account!!.accountId, bot, id) to sharedContent
        onShareConsumed()
        navController.navigate(Routes.chat(bot, id))
    }
    LaunchedEffect(chatTarget, session.loggedIn) {
        if (session.loggedIn && chatTarget != null) {
            if (session.account?.accountId == chatTarget.accountId)
                navController.navigate(Routes.chat(chatTarget.botId, chatTarget.sessionId)) { launchSingleTop = true }
            onTargetConsumed()
        }
    }
    LaunchedEffect(session.account?.accountId) {
        if (selectedShare?.first?.accountId != session.account?.accountId) selectedShare = null
    }
    LaunchedEffect(session.loggedIn) {
        if (!session.loggedIn && navController.currentDestination?.route != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) { popUpTo(navController.graph.id) { inclusive = true } }
        }
    }

    NavHost(
        navController = navController,
        startDestination = start,
        // Pushing into a conversation moves the list back and slides the chat
        // in; popping reverses it. A plain cross-fade gave no sense of depth —
        // the two screens appeared to swap places rather than one being opened
        // on top of the other.
        enterTransition = { MemohMotion.detailEnter() },
        exitTransition = { MemohMotion.detailExit() },
        popEnterTransition = { MemohMotion.detailPopEnter() },
        popExitTransition = { MemohMotion.detailPopExit() },
    ) {
        composable(Routes.LOGIN) {
            val viewModel: LoginViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()
            val signedIn by viewModel.signedIn.collectAsState()

            // The shell takes over as soon as credentials land.
            androidx.compose.runtime.LaunchedEffect(signedIn) {
                if (signedIn) {
                    navController.navigate(Routes.MAIN) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            }

            when (state.step) {
                LoginStep.ServerPicker -> ServerPickerScreen(
                    onSelectCloud = viewModel::showCloud,
                    onSelectSelfHosted = viewModel::showSelfHosted,
                )

                LoginStep.SelfHosted -> SelfHostedLoginScreen(
                    state = state.form,
                    onUrlChange = viewModel::onUrlChange,
                    onUsernameChange = viewModel::onUsernameChange,
                    onPasswordChange = viewModel::onPasswordChange,
                    onSubmit = viewModel::submitSelfHosted,
                    onBack = viewModel::backToPicker,
                )

                LoginStep.Cloud -> CloudLoginScreen(
                    state = state.form,
                    onEmailChange = viewModel::onEmailChange,
                    onCodeChange = viewModel::onCodeChange,
                    onSendCode = viewModel::sendEmailCode,
                    onVerify = { if (state.form.mfaToken != null) viewModel.verifyMfa() else viewModel.verifyEmailCode() },
                    onBack = viewModel::backToPicker,
                )

                LoginStep.TeamPicker -> TeamPickerScreen(
                    teams = state.teams,
                    busy = state.form.busy,
                    onSelect = viewModel::selectTeam,
                )
            }
        }

        composable(Routes.MAIN) {
            MainShell(
                onOpenSession = { botId, sessionId ->
                    navController.navigate(Routes.chat(botId, sessionId))
                },
            )
        }

        composable(Routes.FEATURE, arguments = listOf(navArgument("botId") { type = NavType.StringType }, navArgument("feature") { type = NavType.StringType })) { entry ->
            val botId = entry.arguments?.getString("botId").orEmpty()
            val feature = BotFeature.entries.firstOrNull { it.name == entry.arguments?.getString("feature") } ?: BotFeature.Memory
            val viewModel: BotFeatureViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()
            LaunchedEffect(botId, feature) { viewModel.open(botId, feature) }
            BotFeatureScreen(state.forScreen(botId, feature, viewModel), viewModel, onBack = { navController.popBackStack() },
                onOpenSession = { navController.navigate(Routes.chat(botId, it)) })
        }

        composable(Routes.WORKSPACE, arguments = listOf(navArgument("botId") { type = NavType.StringType }, navArgument("surface") { type = NavType.StringType })) { entry ->
            val botId = entry.arguments?.getString("botId").orEmpty()
            val surface = WorkspaceSurface.entries.firstOrNull { it.name == entry.arguments?.getString("surface") } ?: WorkspaceSurface.Terminal
            val viewModel: WorkspaceViewModel = hiltViewModel()
            WorkspaceScreen(botId, "", surface, viewModel, onBack = { navController.popBackStack() })
        }

        composable(
            route = Routes.CHAT,
            arguments = listOf(
                navArgument(Routes.ARG_BOT_ID) { type = NavType.StringType },
                navArgument(Routes.ARG_SESSION_ID) { type = NavType.StringType },
            ),
        ) { entry ->
            val botId = entry.arguments?.getString(Routes.ARG_BOT_ID).orEmpty()
            val sessionId = entry.arguments?.getString(Routes.ARG_SESSION_ID).orEmpty()
            val viewModel: ChatViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()
            val context = LocalContext.current
            var controlsOpen by remember { mutableStateOf(false) }
            val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                viewModel.attach(context, uris)
            }
            LaunchedEffect(state.navigateSessionId) {
                state.navigateSessionId?.let { id ->
                    viewModel.clearNavigation()
                    navController.navigate(Routes.chat(botId, id))
                }
            }
            LaunchedEffect(selectedShare?.second?.id, state.session?.id, state.draftRestoring) {
                selectedShare?.takeIf { it.first.botId == botId && it.first.sessionId == sessionId &&
                    state.session?.id == sessionId && !state.draftRestoring }?.let {
                    viewModel.importShared(context, it.second.text, it.second.uris)
                    selectedShare = null
                }
            }
            if (controlsOpen) ChatControlsSheet(state, viewModel, { controlsOpen = false })

            // The session object is needed for its channel type and runtime, so
            // it is rebuilt from the id rather than passed through the route.
            androidx.compose.runtime.LaunchedEffect(botId, sessionId) {
                viewModel.open(botId, sessionId)
            }

            androidx.compose.runtime.CompositionLocalProvider(
                dev.memoh.core.markdown.LocalMarkdownImageLoader provides remember(viewModel) { viewModel::loadMedia },
                dev.memoh.feature.chat.components.LocalChatBotId provides botId,
            ) {
            ChatScreen(
                state = state,
                onBack = { navController.popBackStack() },
                onSend = viewModel::send,
                onStop = viewModel::abort,
                onDraftChange = viewModel::setDraft,
                onLoadOlder = viewModel::loadOlder,
                onRetryHistory = viewModel::retryHistory,
                onRegenerate = viewModel::regenerate,
                onFork = viewModel::fork,
                onApprove = { runId, approvalId, optionId ->
                    viewModel.respondToApproval(runId, approvalId, approve = true, optionId = optionId)
                },
                onReject = { runId, approvalId, reason ->
                    viewModel.respondToApproval(runId, approvalId, approve = false, reason = reason)
                },
                onUserInput = { runId, userInputId, answers ->
                    viewModel.respondToUserInput(runId, userInputId, answers)
                },
                onCancelUserInput = { runId, userInputId ->
                    viewModel.respondToUserInput(runId, userInputId, answers = null, canceled = true)
                },
                onDismissError = viewModel::clearError,
                onSelectModel = viewModel::selectModel,
                onSelectTarget = viewModel::selectWorkspaceTarget,
                onSelectWorkdir = viewModel::selectWorkdir,
                onSelectAgent = viewModel::selectAgent,
                onSelectEffort = viewModel::selectReasoningEffort,
                onOpenControls = { controlsOpen = true },
                onNewSession = viewModel::newSession,
                onRefreshContext = viewModel::refreshSessionInfo,
                onCompactContext = viewModel::compact,
                onOpenFeature = { navController.navigate(Routes.feature(botId, it)) },
                onPickAttachment = { images -> attachmentPicker.launch(if (images) arrayOf("image/*") else arrayOf("*/*")) },
                onRemoveAttachment = viewModel::removeAttachment,
                onCancelAttachments = viewModel::cancelAttachments,
                onConfirmFolder = viewModel::confirmFolderChange,
                onCancelFolder = viewModel::cancelFolderChange,
                onOpenSurface = { navController.navigate(Routes.workspace(botId, it.name)) },
            )
            }
        }
    }
}

/**
 * The signed-in shell: one section at a time, with the floating switcher.
 *
 * Kept as a single destination so section changes are not navigation events —
 * the chat screen is the only thing that pushes, which keeps "back" meaning
 * "leave this conversation" rather than "go to the previous tab".
 */
@Composable
private fun MainShell(
    onOpenSession: (botId: String, sessionId: String) -> Unit,
) {
    var sectionIndex by rememberSaveable { mutableIntStateOf(MainSection.Chats.ordinal) }

    // Driven by the content's scroll position via `collapseOnScroll`; the bar
    // shrinks rather than disappearing so the user never loses the sense of
    // where they are.
    var barExpanded by remember { mutableStateOf(true) }

    // Reset on section changes so a page enters with all navigation choices
    // visible before its own scrolling can collapse the controls.
    androidx.compose.runtime.LaunchedEffect(sectionIndex) { barExpanded = true }

    // Hoisted to the shell so the floating bar and the new-session FAB — which
    // sit on one line and must move together — can both act on the same session
    // list. The screen renders the list; the shell owns the chrome around it.
    val sessions: SessionsViewModel = hiltViewModel()
    val sessionsState by sessions.state.collectAsState()
    val settings: SettingsViewModel = hiltViewModel()
    val configured by settings.floatingSections.collectAsState()
    val sections = configured.mapNotNull { name -> MainSection.entries.firstOrNull { it.name == name } }
        .ifEmpty { listOf(MainSection.Chats, MainSection.Profile) }
    val features: BotFeatureViewModel = hiltViewModel()
    val workspace: WorkspaceViewModel = hiltViewModel()
    val featureState by features.state.collectAsState()
    LaunchedEffect(Unit) { sessions.start() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, sessionsState.bot?.id) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    features.observeActivity(sessionsState.bot?.id.orEmpty(), sessions::onActivity)
                    sessions.observeRuns()
                    if (!sessions.state.value.loading && sessions.state.value.sessions.isNotEmpty()) sessions.refresh()
                }
                Lifecycle.Event.ON_STOP -> { features.stopObserving(); sessions.stopObservingRuns() }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer); features.stopObserving(); sessions.stopObservingRuns() }
    }
    val requestedFeature = MainSection.entries[sectionIndex].feature
        ?: BotFeature.Schedules.takeIf { MainSection.entries[sectionIndex] == MainSection.Chats && sessionsState.tab == dev.memoh.feature.sessions.BotTab.Schedules }
        ?: BotFeature.Files.takeIf { MainSection.entries[sectionIndex] == MainSection.Chats && sessionsState.tab == dev.memoh.feature.sessions.BotTab.Files }
    LaunchedEffect(requestedFeature, sessionsState.bot?.id) {
        if (requestedFeature != null) features.open(sessionsState.bot?.id.orEmpty(), requestedFeature)
    }

    val pageStates = rememberSaveableStateHolder()
    val section = MainSection.entries[sectionIndex]
    BackHandler(enabled = section.feature != null) {
        sectionIndex = MainSection.Profile.ordinal
    }
    val showNewSession = section == MainSection.Chats && sessionsState.tab == dev.memoh.feature.sessions.BotTab.Chats && sessionsState.bot != null

    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            AnimatedContent(
                modifier = Modifier.fillMaxSize().collapseOnScroll(
                    expanded = barExpanded,
                    onExpand = { barExpanded = true },
                    onCollapse = { barExpanded = false },
                ),
                targetState = section,
                transitionSpec = {
                    when {
                        initialState == MainSection.Profile && targetState.feature != null ->
                            MemohMotion.detailEnter() togetherWith MemohMotion.detailExit()
                        targetState == MainSection.Profile && initialState.feature != null ->
                            MemohMotion.detailPopEnter() togetherWith MemohMotion.detailPopExit()
                        else -> MemohMotion.horizontalSwap(forward = targetState.ordinal >= initialState.ordinal)
                    }
                },
                label = "sectionContent",
            ) { sectionState ->
                pageStates.SaveableStateProvider("${sectionState.name}:${sessionsState.bot?.id.orEmpty()}") {
                    when (sectionState) {
                        MainSection.Chats -> SessionsSection(
                            viewModel = sessions,
                            state = sessionsState,
                            onOpenSession = onOpenSession,
                            onBarExpandedChange = { barExpanded = it },
                            onBotSettings = { sectionIndex = MainSection.Profile.ordinal },
                            scheduleContent = { BotFeatureScreen(featureState.forScreen(sessionsState.bot?.id.orEmpty(), BotFeature.Schedules, features), features, showTitle = false,
                                onOpenSession = { onOpenSession(sessionsState.bot?.id.orEmpty(), it) }) },
                            fileContent = { BotFeatureScreen(featureState.forScreen(sessionsState.bot?.id.orEmpty(), BotFeature.Files, features), features, showTitle = false) },
                            onOpenFolder = { folder ->
                                features.openFolder(sessionsState.bot?.id.orEmpty(), folder.path ?: "/data")
                                sessions.selectTab(dev.memoh.feature.sessions.BotTab.Files)
                            },
                        )

                        MainSection.Profile -> ProfileScreen(
                            settings = settings,
                            bot = sessionsState.bot,
                            bots = sessionsState.bots,
                            onSelectBot = sessions::selectBot,
                        ) { feature ->
                            sectionIndex = MainSection.entries.first { it.feature == feature }.ordinal
                        }
                        MainSection.Terminal, MainSection.Desktop, MainSection.Browser -> WorkspaceScreen(
                            sessionsState.bot?.id.orEmpty(), sessionsState.bot?.displayName ?: sessionsState.bot?.name.orEmpty(),
                            WorkspaceSurface.valueOf(sectionState.name), workspace, bottomControlSpace = true)
                        else -> if (sectionState.feature != null) BotFeatureScreen(
                            featureState.forScreen(sessionsState.bot?.id.orEmpty(), sectionState.feature, features), features,
                            onBack = { sectionIndex = MainSection.Profile.ordinal },
                            onOpenSession = { onOpenSession(sessionsState.bot?.id.orEmpty(), it) })
                        else PendingSectionScreen(section = sectionState)
                    }
                }
            }

            // The bar and the FAB are one row of floating controls, so they share
            // one container with symmetric edge padding, Flare-style: the bar
            // anchors to the bottom-start, the FAB centers vertically against
            // the container the bar defines. Bottom-aligning two controls of
            // different heights put their baselines on different lines — center
            // alignment is what reads as "one horizontal line".
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                MemohSectionBar(
                    items = sections.map { it.navItem },
                    selectedIndex = sections.indexOf(section),
                    onSelect = { sectionIndex = sections[it].ordinal },
                    collapsed = !barExpanded,
                    modifier = Modifier.align(Alignment.BottomStart),
                )

                androidx.compose.animation.AnimatedVisibility(
                    visible = showNewSession,
                    modifier = Modifier.align(Alignment.CenterEnd),
                    // Flare's FAB enters with a material elevation scale — a
                    // slight grow plus fade — not a hard appearance.
                    enter = MemohMotion.controlEnter(),
                    exit = MemohMotion.controlExit(),
                ) {
                    MemohCornerFab(
                        icon = Icons.Filled.Add,
                        contentDescription = "新建会话",
                        onClick = {
                            sessions.createSession { session ->
                                onOpenSession(sessionsState.bot?.id.orEmpty(), session.id)
                            }
                        },
                        collapsed = !barExpanded,
                    )
                }
            }
        }
    }
}

private fun BotFeatureState.forScreen(botId: String, feature: BotFeature, viewModel: BotFeatureViewModel): BotFeatureState =
    if (this.botId == botId && this.feature == feature) this
    else viewModel.cachedState(botId, feature)
        ?: BotFeatureState(botId = botId, feature = feature, loading = botId.isNotBlank())

@Composable
private fun SessionsSection(
    viewModel: SessionsViewModel,
    state: dev.memoh.feature.sessions.SessionsUiState,
    onOpenSession: (botId: String, sessionId: String) -> Unit,
    onBarExpandedChange: (Boolean) -> Unit,
    onBotSettings: () -> Unit,
    scheduleContent: @Composable () -> Unit,
    fileContent: @Composable () -> Unit,
    onOpenFolder: (dev.memoh.core.model.Workdir) -> Unit,
) {
    // The bot list is this screen's own dependency: nothing else knows which bot
    // to show, so the screen asks for it on first composition.
    androidx.compose.runtime.LaunchedEffect(Unit) { viewModel.start() }

    SessionsScreen(
        state = state,
        onSelectBot = viewModel::selectBot,
        onOpenSession = { session ->
            onOpenSession(state.bot?.id.orEmpty(), session.id)
        },
        onRename = viewModel::rename,
        onBeginRename = viewModel::beginRename,
        onCancelRename = viewModel::cancelRename,
        onBeginDelete = viewModel::beginDelete,
        onCancelDelete = viewModel::cancelDelete,
        onConfirmDelete = viewModel::delete,
        onDismissError = viewModel::dismissError,
        onRetry = viewModel::refresh,
        onLoadMore = viewModel::loadMore,
        onSelectTab = viewModel::selectTab,
        onQueryChange = viewModel::setQuery,
        onBotSettings = onBotSettings,
        scheduleContent = scheduleContent,
        fileContent = fileContent,
        onOpenFolder = onOpenFolder,
        onBarExpandedChange = onBarExpandedChange,
    )
}
