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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
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
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.memoh.core.designsystem.component.MemohCornerFab
import dev.memoh.core.designsystem.component.LocalFloatingNavigationPadding
import dev.memoh.core.designsystem.motion.MemohMotion
import dev.memoh.feature.chat.ChatControlsSheet
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.memoh.core.designsystem.component.MemohActionButton
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Compress
import androidx.core.content.FileProvider
import android.net.Uri
import android.widget.Toast
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
import dev.memoh.feature.bots.ManagementPage
import dev.memoh.feature.bots.ManagementScreen
import dev.memoh.feature.bots.ManagementViewModel
import dev.memoh.feature.bots.OfflineHistoryScreen
import dev.memoh.feature.bots.OfflineHistoryViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
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
    val updates: AppUpdateViewModel = hiltViewModel()
    val updateState by updates.state.collectAsState()
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { updates.check() }
    val session by settings.session.collectAsState()
    val configured by settings.floatingSections.collectAsState()
    val sections = configured.mapNotNull { name -> MainSection.entries.firstOrNull { it.name == name } }
        .ifEmpty { listOf(MainSection.Chats, MainSection.Profile) }
    val navigation = rememberSectionNavigationState()
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
    var previousAccount by remember { mutableStateOf(session.account?.accountId) }
    LaunchedEffect(session.account?.accountId) {
        if (selectedShare?.first?.accountId != session.account?.accountId) selectedShare = null
        if (previousAccount != null && session.loggedIn && previousAccount != session.account?.accountId) {
            navController.navigate(Routes.MAIN) { popUpTo(navController.graph.id) { inclusive = true } }
        }
        previousAccount = session.account?.accountId
    }
    LaunchedEffect(session.loggedIn) {
        if (!session.loggedIn && navController.currentDestination?.route != Routes.LOGIN) {
            navController.navigate(Routes.LOGIN) { popUpTo(navController.graph.id) { inclusive = true } }
        }
    }

    FloatingNavigationHost(navController = navController, startDestination = start,
        sections = sections, navigation = navigation, enabled = session.loggedIn) {
        memohComposable(Routes.LOGIN) {
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

            androidx.activity.compose.BackHandler(state.step == LoginStep.Cloud || state.step == LoginStep.SelfHosted) { viewModel.backToPicker() }
            LoginStepContent(state.step) { step -> when (step) {
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
            } }
        }

        memohComposable(Routes.MAIN) {
            MainShell(
                navigation = navigation,
                updates = updates,
                onOpenSession = { botId, sessionId ->
                    navController.navigate(Routes.chat(botId, sessionId))
                },
                onOpenManagement = { botId, page -> navController.navigate(Routes.management(botId, page.name)) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
            )
        }

        memohComposable(Routes.HISTORY) {
            val vm: OfflineHistoryViewModel = hiltViewModel()
            val state by vm.state.collectAsState()
            LaunchedEffect(Unit) { vm.refresh() }
            OfflineHistoryScreen(state, vm, onBack = { navController.popBackStack() },
                onOpen = { bot, id -> navController.navigate(Routes.chat(bot, id)) })
        }

        memohComposable(Routes.MANAGEMENT, arguments = listOf(navArgument("botId") { type = NavType.StringType }, navArgument("page") { type = NavType.StringType })) { entry ->
            val botId = entry.arguments?.getString("botId").orEmpty().takeUnless { it == "account" }.orEmpty()
            val page = ManagementPage.entries.firstOrNull { it.name == entry.arguments?.getString("page") } ?: ManagementPage.Bot
            val vm: ManagementViewModel = hiltViewModel()
            val state by vm.state.collectAsState()
            LaunchedEffect(botId, page) { vm.open(botId, page) }
            DisposableEffect(Unit) { onDispose { vm.cancelAuthorization() } }
            ManagementScreen(state, vm, onBack = { navController.popBackStack() },
                onOpenBot = { navController.navigate(Routes.management(it, ManagementPage.Bot.name)) },
                onOpenPage = { navController.navigate(Routes.management(botId, it.name)) },
                onOpenApps = { navController.navigate(Routes.feature(botId, BotFeature.Apps.name)) })
        }

        memohComposable(Routes.FEATURE, arguments = listOf(navArgument("botId") { type = NavType.StringType }, navArgument("feature") { type = NavType.StringType })) { entry ->
            val botId = entry.arguments?.getString("botId").orEmpty()
            val feature = BotFeature.entries.firstOrNull { it.name == entry.arguments?.getString("feature") } ?: BotFeature.Memory
            val viewModel: BotFeatureViewModel = hiltViewModel()
            val state by viewModel.state.collectAsState()
            LaunchedEffect(botId, feature) { viewModel.open(botId, feature) }
            BotFeatureScreen(state.forScreen(botId, feature, viewModel), viewModel, onBack = { navController.popBackStack() },
                onOpenConnectors = { navController.navigate(Routes.management(botId, ManagementPage.Connectors.name)) },
                onOpenSession = { navController.navigate(Routes.chat(botId, it)) })
        }

        memohComposable(Routes.WORKSPACE, arguments = listOf(navArgument("botId") { type = NavType.StringType }, navArgument("surface") { type = NavType.StringType })) { entry ->
            val botId = entry.arguments?.getString("botId").orEmpty()
            val surface = WorkspaceSurface.entries.firstOrNull { it.name == entry.arguments?.getString("surface") } ?: WorkspaceSurface.Terminal
            val viewModel: WorkspaceViewModel = hiltViewModel()
            WorkspaceScreen(botId, "", surface, viewModel, onBack = { navController.popBackStack() }, bottomControlSpace = true)
        }

        memohComposable(
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
            var pendingImages by remember { mutableStateOf<List<Uri>>(emptyList()) }
            var capturePath by rememberSaveable { mutableStateOf<String?>(null) }
            val attachmentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
                if (uris.any { context.contentResolver.getType(it)?.startsWith("image/") == true }) pendingImages = uris
                else viewModel.attach(context, uris)
            }
            val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
                val file = capturePath?.let { java.io.File(it) }
                capturePath = null
                if (success && file != null) viewModel.attach(context, listOf(FileProvider.getUriForFile(context, "${context.packageName}.files", file)), onConsumed = { file.delete() })
                else file?.delete()
            }
            if (pendingImages.isNotEmpty()) AlertDialog(onDismissRequest = { pendingImages = emptyList() }, title = { Text("添加图片") },
                text = { Text("原图保留文件内容；压缩会将静态图片缩至最长边 2048 px，文件较小时保留原图。") },
                confirmButton = { MemohActionButton("保留原图", Icons.Filled.Image, {
                    val selected = pendingImages; pendingImages = emptyList(); viewModel.attach(context, selected)
                }, primary = true) },
                dismissButton = { MemohActionButton("压缩", Icons.Filled.Compress, {
                    val selected = pendingImages; pendingImages = emptyList(); viewModel.attach(context, selected, compressImages = true)
                }) })
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
                onTakePhoto = {
                    val file = java.io.File.createTempFile("photo-", ".jpg", java.io.File(context.cacheDir, "capture").apply { mkdirs() })
                    capturePath = file.absolutePath
                    try { camera.launch(FileProvider.getUriForFile(context, "${context.packageName}.files", file)) }
                    catch (e: android.content.ActivityNotFoundException) { capturePath = null; file.delete(); Toast.makeText(context, "没有可用的相机应用", Toast.LENGTH_SHORT).show() }
                },
                onRemoveAttachment = viewModel::removeAttachment,
                onCancelAttachments = viewModel::cancelAttachments,
                onConfirmFolder = viewModel::confirmFolderChange,
                onCancelFolder = viewModel::cancelFolderChange,
                onOpenSurface = { navController.navigate(Routes.workspace(botId, it.name)) },
            )
            }
        }
    }
    AppUpdateReminder(updateState, updates::dismissReminder)
}

/**
 * The signed-in shell: one section at a time, with the floating switcher.
 *
 * Section changes replace content; conversations and management pages use the
 * root back stack. Both share the floating controls owned by MemohApp.
 */
@Composable
private fun MainShell(
    navigation: SectionNavigationState,
    updates: AppUpdateViewModel,
    onOpenSession: (botId: String, sessionId: String) -> Unit,
    onOpenManagement: (botId: String, page: ManagementPage) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    fun openSection(section: MainSection, motion: SectionMotion) {
        navigation.openSection(section, motion)
    }

    // The session FAB uses the same scroll-collapse state as the root switcher.
    val sessions: SessionsViewModel = hiltViewModel()
    val sessionsState by sessions.state.collectAsState()
    val settings: SettingsViewModel = hiltViewModel()
    val features: BotFeatureViewModel = hiltViewModel()
    val workspace: WorkspaceViewModel = hiltViewModel()
    val featureState by features.state.collectAsState()
    LaunchedEffect(Unit) { sessions.start() }
    val lifecycleOwner = LocalLifecycleOwner.current
    val lifecycleState by lifecycleOwner.lifecycle.currentStateAsState()
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
    val section = navigation.destination.section
    val requestedFeature = section.feature
        ?: BotFeature.Schedules.takeIf { section == MainSection.Chats && sessionsState.tab == dev.memoh.feature.sessions.BotTab.Schedules }
        ?: BotFeature.Files.takeIf { section == MainSection.Chats && sessionsState.tab == dev.memoh.feature.sessions.BotTab.Files }
    LaunchedEffect(requestedFeature, sessionsState.bot?.id) {
        if (requestedFeature != null) features.open(sessionsState.bot?.id.orEmpty(), requestedFeature)
    }

    val pageStates = rememberSaveableStateHolder()
    val showNewSession = section == MainSection.Chats && sessionsState.tab == dev.memoh.feature.sessions.BotTab.Chats && sessionsState.bot != null && LocalFloatingNavigationPadding.current > 0.dp

    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            key(navigation.contentKey) { SectionContent(
                modifier = Modifier.fillMaxSize(),
                destination = navigation.destination,
                backDestination = SectionDestination(MainSection.Profile, SectionMotion.Pop)
                    .takeIf { section.feature != null && lifecycleState == Lifecycle.State.RESUMED },
                onBack = { openSection(MainSection.Profile, SectionMotion.Pop) },
                rtl = rtl,
            ) { destination ->
                val sectionState = destination.section
                pageStates.SaveableStateProvider("${sectionState.name}:${sessionsState.bot?.id.orEmpty()}") {
                    when (sectionState) {
                        MainSection.Chats -> SessionsSection(
                            viewModel = sessions,
                            state = sessionsState,
                            onOpenSession = onOpenSession,
                            onBarExpandedChange = { navigation.barExpanded = it },
                            onBotSettings = { openSection(MainSection.Profile, SectionMotion.Push) },
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
                            updates = updates,
                            bot = sessionsState.bot,
                            bots = sessionsState.bots,
                            onSelectBot = sessions::selectBot,
                            onOpenManagement = { page -> onOpenManagement(sessionsState.bot?.id.orEmpty(), page) },
                            onOpenHistory = onOpenHistory,
                        ) { feature ->
                            openSection(MainSection.entries.first { it.feature == feature }, SectionMotion.Push)
                        }
                        MainSection.Terminal, MainSection.Desktop, MainSection.Browser -> WorkspaceScreen(
                            sessionsState.bot?.id.orEmpty(), sessionsState.bot?.displayName ?: sessionsState.bot?.name.orEmpty(),
                            WorkspaceSurface.valueOf(sectionState.name), workspace, bottomControlSpace = true)
                        else -> if (sectionState.feature != null) BotFeatureScreen(
                            featureState.forScreen(sessionsState.bot?.id.orEmpty(), sectionState.feature, features), features,
                            onBack = { openSection(MainSection.Profile, SectionMotion.Pop) },
                            onOpenConnectors = { onOpenManagement(sessionsState.bot?.id.orEmpty(), ManagementPage.Connectors) },
                            onOpenSession = { onOpenSession(sessionsState.bot?.id.orEmpty(), it) })
                        else PendingSectionScreen(section = sectionState)
                    }
                }
            } }

            // Center the FAB against the measured root bar, including larger labels.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .heightIn(min = (LocalFloatingNavigationPadding.current - 32.dp).coerceAtLeast(0.dp)),
            ) {
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
                        collapsed = !navigation.barExpanded,
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
        onSearchAllTitles = viewModel::searchAllTitles,
        onStopTitleSearch = viewModel::stopTitleSearch,
        onSelectTab = viewModel::selectTab,
        onQueryChange = viewModel::setQuery,
        onBotSettings = onBotSettings,
        scheduleContent = scheduleContent,
        fileContent = fileContent,
        onOpenFolder = onOpenFolder,
        onBarExpandedChange = onBarExpandedChange,
    )
}
