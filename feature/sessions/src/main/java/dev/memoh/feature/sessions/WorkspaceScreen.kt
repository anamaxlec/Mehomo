package dev.memoh.feature.sessions

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.util.Base64
import android.webkit.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.memoh.core.designsystem.component.MemohEmptyState
import dev.memoh.core.designsystem.component.MemohCompactComposer
import dev.memoh.core.designsystem.component.MemohStatus
import dev.memoh.core.designsystem.component.MemohStatusDot
import dev.memoh.core.designsystem.component.MemohTextSkeleton
import dev.memoh.core.designsystem.component.MemohActionButton
import dev.memoh.core.model.DisplayAnswer
import dev.memoh.core.model.WorkspaceSurface
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun WorkspaceScreen(botId: String, botName: String, surface: WorkspaceSurface, viewModel: WorkspaceViewModel,
    onBack: (() -> Unit)? = null, bottomControlSpace: Boolean = false) {
    val current by viewModel.state.collectAsState()
    val state = if (current.botId == botId && current.surface == surface) current
        else WorkspaceState(botId = botId, surface = surface, loading = botId.isNotBlank())
    var webView by remember(botId, surface) { mutableStateOf<WebView?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(botId, surface) { viewModel.open(botId, surface) }
    DisposableEffect(lifecycle, botId, surface) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) { viewModel.stop(botId, surface); webView?.onPause() }
            if (event == Lifecycle.Event.ON_START) { viewModel.open(botId, surface); webView?.onResume(); webView?.reload() }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); viewModel.stop(botId, surface) }
    }
    var input by remember(botId, surface) { mutableStateOf("") }
    var webProgress by remember { mutableIntStateOf(0) }
    var prepareConfirm by remember { mutableStateOf(false) }
    val keyboardVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    fun sendInput() {
        if (input.isEmpty() || !state.connected) return
        if (surface == WorkspaceSurface.Terminal && !viewModel.terminalInput((input + "\r").toByteArray())) return
        if (surface == WorkspaceSurface.Desktop) webView?.evaluateJavascript("desktopText(${JsonPrimitive(input)});", null)
        input = ""
    }
    LaunchedEffect(webView, botId, surface) {
        val view = webView ?: return@LaunchedEffect
        if (surface == WorkspaceSurface.Terminal) viewModel.output.collect { bytes ->
            view.evaluateJavascript("terminalWrite(${JsonPrimitive(Base64.encodeToString(bytes, Base64.NO_WRAP))});", null)
        }
    }
    LaunchedEffect(webView, botId, surface) {
        val view = webView ?: return@LaunchedEffect
        if (surface == WorkspaceSurface.Desktop) viewModel.commands.collect { command ->
            when (command) {
                WorkspaceCommand.StartDesktop -> view.evaluateJavascript("startDesktop();", null)
                WorkspaceCommand.StartRuntimeDisplay -> view.evaluateJavascript("startRuntimeDisplay();", null)
                WorkspaceCommand.RuntimeOpened -> view.evaluateJavascript("runtimeOpened();", null)
                is WorkspaceCommand.RuntimeOutput -> view.evaluateJavascript("runtimeWrite(${JsonPrimitive(Base64.encodeToString(command.bytes, Base64.NO_WRAP))});", null)
                is WorkspaceCommand.Answer -> if (surface == WorkspaceSurface.Desktop) view.evaluateJavascript("acceptAnswer(${Json.encodeToString(DisplayAnswer.serializer(), command.value)});", null)
                WorkspaceCommand.Stop -> if (surface == WorkspaceSurface.Desktop) view.evaluateJavascript("if(window.stopWorkspace)stopWorkspace();", null)
            }
        }
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh, topBar = {
        TopAppBar(title = { Column(Modifier.padding(start = if (onBack == null) 16.dp else 0.dp)) {
            Text(surface.title, style = MaterialTheme.typography.titleLarge)
            if (botName.isNotBlank()) Text(botName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }, navigationIcon = { onBack?.let { IconButton(onClick = it) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), actions = {
                if (state.connected || state.connecting || state.browser != null) IconButton(onClick = viewModel::disconnect) { Icon(Icons.Filled.LinkOff, "断开连接") }
                else IconButton(onClick = viewModel::refresh, enabled = !state.loading && !state.preparing) { Icon(Icons.Filled.Refresh, "刷新工作区") }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().padding(horizontal = 16.dp)
            .padding(bottom = if (bottomControlSpace && !keyboardVisible) 100.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.botId.isBlank()) { MemohEmptyState("请先选择一个 Bot"); return@Column }
            if (state.loading || state.connecting) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.error?.let { error -> Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (surface == WorkspaceSurface.Browser) {
                OutlinedTextField(state.address, viewModel::address, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge, label = { Text("云端端口和路径") }, placeholder = { Text("localhost:5173/") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go), keyboardActions = KeyboardActions(onGo = { viewModel.openBrowser() }),
                    trailingIcon = { IconButton(onClick = viewModel::openBrowser, enabled = !state.connecting) { Icon(Icons.Filled.ArrowForward, "打开预览") } })
                if (state.browser == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (state.connecting) MemohTextSkeleton(Modifier.fillMaxSize().padding(16.dp), "正在加载网页预览")
                    else MemohEmptyState("预览云端网页", description = "填写云端电脑上运行的网站端口，例如 localhost:5173/。")
                } else {
                    if (webProgress in 1..99) LinearProgressIndicator(progress = { webProgress / 100f }, modifier = Modifier.fillMaxWidth())
                    Surface(Modifier.fillMaxWidth().weight(1f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                        Box {
                        BrowserPreview(state.browser!!.url, viewModel::browserError, { webProgress = it })
                        androidx.compose.animation.AnimatedVisibility(visible = webProgress < 100 && state.error == null,
                            enter = androidx.compose.animation.fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                            exit = androidx.compose.animation.fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())) {
                            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surfaceContainerLowest) {
                                MemohTextSkeleton(Modifier.padding(16.dp), "正在加载网页预览")
                            }
                        }
                        }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = MaterialTheme.shapes.extraLarge, color = if (state.connected)
                        MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            MemohStatusDot(if (state.connected) MemohStatus.Online else if (state.connecting) MemohStatus.Running else MemohStatus.Offline)
                            Text(when { state.connecting -> "连接中"; state.connected -> "已连接"; else -> "未连接" },
                                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    if (!state.connected && !state.connecting && !state.loading && !state.preparing) MemohActionButton("连接${surface.title}", Icons.Filled.Link,
                        onClick = { if (surface == WorkspaceSurface.Terminal) viewModel.connectTerminal() else viewModel.connectDesktop() },
                        enabled = state.rendererReady && if (surface == WorkspaceSurface.Terminal) state.terminalInfo?.available == true else state.displayInfo?.available == true, primary = true)
                }
                Surface(Modifier.fillMaxWidth().weight(1f), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.inverseSurface) {
                    Box {
                    LocalWorkspaceView(surface, viewModel, { webView = it })
                    androidx.compose.animation.AnimatedVisibility(visible = state.loading || state.connecting,
                        enter = androidx.compose.animation.fadeIn(MaterialTheme.motionScheme.fastEffectsSpec()),
                        exit = androidx.compose.animation.fadeOut(MaterialTheme.motionScheme.fastEffectsSpec())) {
                        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.inverseSurface) {
                            MemohTextSkeleton(Modifier.padding(16.dp), "正在加载${surface.title}")
                        }
                    }
                    }
                }
                if (surface == WorkspaceSurface.Terminal && state.terminalInfo?.available == false) Text("当前工作空间没有可用终端", style = MaterialTheme.typography.bodySmall)
                if (surface == WorkspaceSurface.Desktop && state.displayInfo?.available == false) {
                    Text(state.displayInfo?.unavailableReason ?: "当前云端电脑没有可用桌面", style = MaterialTheme.typography.bodySmall)
                    if (state.displayInfo?.prepareSupported == true && !state.preparing) MemohActionButton("准备桌面环境", Icons.Filled.InstallDesktop, { prepareConfirm = true }, primary = true)
                }
                if (state.preparing) {
                    LinearProgressIndicator(progress = { (state.preparePercent ?: 0).coerceIn(0, 100) / 100f }, modifier = Modifier.fillMaxWidth())
                    Text(state.prepareMessage ?: "正在准备桌面环境…", style = MaterialTheme.typography.bodySmall)
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    val keys = if (surface == WorkspaceSurface.Terminal) listOf("Esc" to "\u001b", "Tab" to "\t", "Ctrl+C" to "\u0003", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "Enter" to "\r")
                        else listOf("Esc" to "Escape", "Tab" to "Tab", "↑" to "ArrowUp", "↓" to "ArrowDown", "←" to "ArrowLeft", "→" to "ArrowRight", "Enter" to "Enter", "删除" to "Backspace")
                    keys.forEach { (label, key) -> FilledTonalButton(onClick = {
                        if (surface == WorkspaceSurface.Terminal) viewModel.terminalInput(key.toByteArray())
                        else webView?.evaluateJavascript("desktopKey(${JsonPrimitive(key)});", null)
                    }, enabled = state.connected, shapes = ButtonDefaults.shapes(),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                            contentColor = MaterialTheme.colorScheme.onSurface),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) { Text(label, style = MaterialTheme.typography.labelMedium) } }
                }
                MemohCompactComposer(input, { input = it },
                    if (surface == WorkspaceSurface.Terminal) "输入命令" else "向桌面输入文字",
                    enabled = state.connected, onSend = { sendInput() })

            }
        }
    }
    if (prepareConfirm) AlertDialog(onDismissRequest = { prepareConfirm = false }, title = { Text("准备桌面环境") },
        text = { Text("将在当前云端电脑安装并启动桌面所需的组件。已有工作空间文件会保留。") },
        confirmButton = { MemohActionButton("开始准备", Icons.Filled.InstallDesktop, { prepareConfirm = false; viewModel.prepareDesktop() }, primary = true) },
        dismissButton = { MemohActionButton("取消", Icons.Filled.Close, { prepareConfirm = false }) })
}

private const val LOCAL_ORIGIN = "https://workspace.memoh.invalid/"

private class WorkspaceBridge(private val post: ((() -> Unit) -> Unit), private val viewModel: WorkspaceViewModel) {
    private val active = AtomicBoolean(true)
    private fun deliver(action: () -> Unit) { if (active.get()) post { if (active.get()) action() } }
    @JavascriptInterface fun ready() = deliver { viewModel.rendererReady() }
    @JavascriptInterface fun input(text: String) = deliver { viewModel.terminalInput(text.toByteArray()) }
    @JavascriptInterface fun binary(text: String) = deliver { runCatching { viewModel.terminalInput(Base64.decode(text, Base64.NO_WRAP)) } }
    @JavascriptInterface fun resize(cols: Int, rows: Int) = deliver { viewModel.resizeTerminal(cols, rows) }
    @JavascriptInterface fun runtimeReady() = deliver { viewModel.runtimeRendererReady() }
    @JavascriptInterface fun runtimeBinary(text: String) = deliver { runCatching { viewModel.runtimeInput(Base64.decode(text, Base64.NO_WRAP)) } }
    @JavascriptInterface fun offer(sdp: String) = deliver { viewModel.desktopOffer(sdp) }
    @JavascriptInterface fun status(status: String) = deliver { viewModel.desktopStatus(status) }
    fun close() { active.set(false) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LocalWorkspaceView(surface: WorkspaceSurface, viewModel: WorkspaceViewModel, onView: (WebView?) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val theme = "{\"background\":\"${scheme.inverseSurface.css()}\",\"foreground\":\"${scheme.inverseOnSurface.css()}\",\"cursor\":\"${scheme.primary.css()}\"}"
    var bridge by remember { mutableStateOf<WorkspaceBridge?>(null) }
    AndroidView(factory = { context ->
        WebView(context).apply {
            // WebView uses LayoutParams to calculate its CSS viewport as well as Android bounds.
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(scheme.inverseSurface.toArgb())
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val native = WorkspaceBridge({ action -> handler.post(action) }, viewModel)
            bridge = native; addJavascriptInterface(native, "NativeWorkspace")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val uri = request.url
                    val file = uri.path?.removePrefix("/").orEmpty()
                    val allowed = setOf("terminal.html", "desktop.html", "xterm.js", "xterm.css", "fit.js")
                    if (uri.scheme != "https" || uri.host != "workspace.memoh.invalid" || uri.path != "/$file" || (file !in allowed && !(file.startsWith("novnc/") && file.endsWith(".js") && ".." !in file)))
                        return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    val type = if (file.endsWith(".css")) "text/css" else if (file.endsWith(".js")) "text/javascript" else "text/html"
                    return WebResourceResponse(type, "UTF-8", context.assets.open("workspace/$file"))
                }
                override fun onPageFinished(view: WebView, url: String) { view.evaluateJavascript("if(window.workspaceTheme)workspaceTheme($theme);", null) }
            }
            onView(this)
            loadUrl(LOCAL_ORIGIN + if (surface == WorkspaceSurface.Terminal) "terminal.html" else "desktop.html")
        }
    }, update = { it.evaluateJavascript("if(window.workspaceTheme)workspaceTheme($theme);", null) },
        onRelease = { bridge?.close(); it.removeJavascriptInterface("NativeWorkspace"); it.stopLoading(); it.destroy(); onView(null) },
        modifier = Modifier.fillMaxSize())
}

private fun Color.css(): String = "#%06x".format(toArgb() and 0xffffff)

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun BrowserPreview(url: String, onError: (String) -> Unit, onProgress: (Int) -> Unit) {
    val callbacks by rememberUpdatedState(onError to onProgress)
    val parsed = Uri.parse(url)
    if (parsed.scheme !in listOf("https", "http") || parsed.host.isNullOrBlank()) {
        LaunchedEffect(url) { onError("服务端返回的预览地址无效") }; return
    }
    key(url) {
        AndroidView(factory = { context -> WebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            settings.javaScriptEnabled = true; settings.domStorageEnabled = true
            settings.allowFileAccess = false; settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
            webChromeClient = object : WebChromeClient() { override fun onProgressChanged(view: WebView, newProgress: Int) { callbacks.second(newProgress) } }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    request.url.scheme !in listOf("http", "https") || request.url.host != parsed.host
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) callbacks.first("无法访问预览页面，请检查云端端口是否正在运行")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, errorResponse: WebResourceResponse) {
                    if (request.isForMainFrame) callbacks.first("预览页面返回 HTTP ${errorResponse.statusCode}，请检查云端端口")
                }
            }
            loadUrl(url)
        } }, onRelease = { it.stopLoading(); it.destroy() }, modifier = Modifier.fillMaxSize())
    }
}
