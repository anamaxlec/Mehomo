package dev.memoh.feature.sessions

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.memoh.core.designsystem.component.*
import dev.memoh.core.markdown.*
import dev.memoh.core.model.WorkspaceFile
import dev.memoh.core.network.workspaceDownloadPath
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun workspaceParent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }
internal fun workspaceChild(parent: String, name: String): String {
    require(name.isNotBlank() && name !in listOf(".", "..") && '/' !in name && '\\' !in name && '\u0000' !in name) { "请输入有效的文件名" }
    return parent.trimEnd('/') + "/" + name
}
internal fun workspaceImageFile(file: WorkspaceFile) = file.name.substringAfterLast('.').lowercase() in setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
internal fun workspaceTextFile(file: WorkspaceFile): Boolean = !file.isDir && file.size <= 1_048_576 &&
    (file.name.substringAfterLast('.').lowercase() in setOf("md", "markdown", "txt", "json", "jsonl", "yaml", "yml", "toml", "xml", "html", "css", "js", "jsx", "ts", "tsx", "kt", "kts", "java", "go", "py", "rb", "rs", "sh", "bash", "zsh", "sql", "csv", "log", "ini", "conf", "cfg", "properties", "env", "gitignore") ||
        file.name.lowercase() in setOf("dockerfile", "makefile", "readme", "license"))

private data class FileNameAction(val title: String, val initial: String = "", val apply: (String) -> Unit)

@Composable
fun WorkspaceFilesScreen(state: BotFeatureState, viewModel: BotFeatureViewModel, onBack: (() -> Unit)? = null, showTitle: Boolean = true) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var nameAction by remember { mutableStateOf<FileNameAction?>(null) }
    var deleting by remember { mutableStateOf<WorkspaceFile?>(null) }
    var discard by remember { mutableStateOf(false) }
    var download by remember { mutableStateOf<WorkspaceFile?>(null) }
    var savingDownload by remember { mutableStateOf(false) }
    val selected = state.selectedFile
    val dirty = state.fileDraft != state.fileDocument?.content.orEmpty() || state.fileNew
    fun closeFile() { if (dirty) discard = true else { viewModel.closeFile(); viewModel.refresh() } }
    BackHandler(selected != null && !state.busy) { closeFile() }
    val uploader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.uploadFile(context, uri)
    }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val file = download
        if (uri != null && file != null) scope.launch {
            savingDownload = true
            try {
                val bytes = viewModel.fileMedia(workspaceDownloadPath(state.botId, file.path))
                withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) } }
                Toast.makeText(context, "文件已保存", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { Toast.makeText(context, e.message ?: "下载失败", Toast.LENGTH_LONG).show() }
            finally { savingDownload = false; download = null }
        }
    }
    fun saveDownload(file: WorkspaceFile) { download = file; saver.launch(file.name) }
    val imageLoader: suspend (String) -> ByteArray = remember(viewModel, state.botId, selected?.path) {
        { location ->
            val source = when {
                location.startsWith("https://") || location.startsWith("http://") || location.startsWith("data:") || location.startsWith("/bots/") || location.startsWith("/api/memoh/") -> location
                else -> workspaceDownloadPath(state.botId, if (location.startsWith('/')) location else workspaceParent(selected?.path ?: state.filePath) + "/" + location)
            }
            viewModel.fileMedia(source)
        }
    }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    CompositionLocalProvider(LocalMarkdownImageLoader provides imageLoader) {
    if (selected != null && selected.name.substringAfterLast('.').lowercase() in setOf("pdf", "docx", "pptx", "odt", "svg")) {
        dev.memoh.core.markdown.DocumentPreview(workspaceDownloadPath(state.botId, selected.path), selected.name, onDismiss = { viewModel.closeFile() })
    }
    Scaffold(modifier = if (showTitle && selected == null) Modifier.nestedScroll(scrollBehavior.nestedScrollConnection) else Modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentWindowInsets = if (showTitle) ScaffoldDefaults.contentWindowInsets else WindowInsets(0, 0, 0, 0), topBar = {
        if (showTitle || selected != null) MemohPageTopBar(selected?.name ?: "文件",
            large = selected == null, scrollBehavior = scrollBehavior,
            windowInsets = if (showTitle) TopAppBarDefaults.windowInsets else WindowInsets(0, 0, 0, 0),
            onBack = if (selected != null || onBack != null) ({ if (selected != null) closeFile() else onBack?.invoke() }) else null,
            backEnabled = !state.busy, actions = {
            if (selected != null) {
                if (state.fileDocument != null) {
                    IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(android.content.ClipData.newPlainText(selected.name, state.fileDraft))) } }) {
                        Icon(Icons.Outlined.ContentCopy, "复制文件内容")
                    }
                    if (state.fileWritable) IconButton(onClick = { viewModel.fileEditing(!state.fileEditing) }) {
                        Icon(if (state.fileEditing) Icons.Outlined.Visibility else Icons.Outlined.Edit, if (state.fileEditing) "预览文件" else "编辑文件")
                    }
                    if (dirty && state.fileWritable) MemohActionButton("保存", Icons.Outlined.Save, viewModel::saveFile, enabled = !state.busy, primary = true)
                }
                IconButton(onClick = { saveDownload(selected) }, enabled = !savingDownload && !state.fileNew) { Icon(Icons.Outlined.Download, "下载文件") }
            } else IconButton(onClick = viewModel::refresh, enabled = !state.loading && !state.busy) { Icon(Icons.Outlined.Refresh, "刷新文件") }
        })
    }) { padding ->
        MemohRefreshBox(refreshing = (state.loading && state.hasLoaded) || state.busy || savingDownload,
            onRefresh = viewModel::refresh, enabled = selected == null && !state.busy && !savingDownload,
            modifier = Modifier.fillMaxSize().padding(padding)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            state.error?.let { message ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(message, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                        IconButton(onClick = viewModel::dismiss, shapes = IconButtonDefaults.shapes()) { Icon(Icons.Outlined.Close, "关闭错误提示", Modifier.size(18.dp)) }
                    }
                }
            }
            state.notice?.let { Text(it, Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
            if (selected != null) {
                Text(selected.path, Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (workspaceImageFile(selected)) MarkdownImage(workspaceDownloadPath(state.botId, selected.path), selected.name)
                else MemohLoadingContent(state.busy && state.fileDocument == null, Modifier.weight(1f),
                    placeholder = { MemohTextSkeleton(Modifier.padding(vertical = 16.dp), "正在加载文件内容") }) {
                when {
                    state.fileDocument != null && state.fileEditing -> OutlinedTextField(value = state.fileDraft, onValueChange = viewModel::fileDraft,
                        modifier = Modifier.fillMaxSize().padding(bottom = 16.dp), enabled = !state.busy,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace), placeholder = { Text("输入文件内容") })
                    state.fileDocument != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 100.dp)) {
                        if (selected.name.substringAfterLast('.').lowercase() in setOf("md", "markdown")) MemohMarkdown.Markdown(state.fileDraft,
                            onLinkClick = { url ->
                                if (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:")) runCatching { uriHandler.openUri(url) }
                                else {
                                    val path = runCatching { java.net.URI("file://" + selected.path).resolve(url).path }.getOrNull()
                                    if (path != null) viewModel.openFile(WorkspaceFile(path.substringAfterLast('/'), path))
                                }
                            })
                        else MemohMarkdown.Code(state.fileDraft, selected.name.substringAfterLast('.', "text"))
                    }
                    !state.busy -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        MemohEmptyState(title = "下载后查看", description = "${fileSize(selected.size)} · ${if (selected.size > 1_048_576) "文件较大" else "此格式暂不提供文本预览"}")
                    }
                }
                }
            } else {
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { viewModel.openFolder(workspaceParent(state.filePath)) }, enabled = state.filePath != "/" && !state.busy) { Icon(Icons.Outlined.DriveFolderUpload, "上一级文件夹") }
                    Text(state.filePath, Modifier.weight(1f).clickable { nameAction = FileNameAction("打开文件夹", state.filePath, viewModel::openFolder) },
                        style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (state.fileWritable) Box {
                        var open by remember { mutableStateOf(false) }
                        IconButton(onClick = { open = !open }, enabled = !state.busy) { Icon(Icons.Outlined.Add, "添加文件") }
                        MemohPopupMenu(open, { open = false }, Alignment.TopEnd) {
                            MemohMenuRow("新建文件", Icons.Outlined.NoteAdd, { open = false; nameAction = FileNameAction("新建文件", "untitled.md", viewModel::newFile) })
                            MemohMenuRow("新建文件夹", Icons.Outlined.CreateNewFolder, { open = false; nameAction = FileNameAction("新建文件夹", apply = viewModel::createDirectory) })
                            MemohMenuRow("上传文件", Icons.Outlined.UploadFile, { open = false; uploader.launch(arrayOf("*/*")) })
                        }
                    }
                    if (!showTitle) IconButton(onClick = viewModel::refresh, enabled = !state.loading && !state.busy) { Icon(Icons.Outlined.Refresh, "刷新文件") }
                }
                SearchBarDefaults.InputField(query = state.query, onQueryChange = viewModel::query, onSearch = {}, expanded = false, onExpandedChange = {},
                    placeholder = { Text("筛选当前文件夹") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                val entries = state.files.filter { state.query.isBlank() || it.name.contains(state.query, ignoreCase = true) }
                MemohLoadingContent(state.loading && !state.hasLoaded && state.files.isEmpty(), Modifier.weight(1f),
                    placeholder = { MemohListSkeleton(description = "正在加载文件列表", leading = true, rows = 6) }) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 108.dp), verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                    if (!state.loading && entries.isEmpty()) item { MemohEmptyState(title = if (state.query.isBlank()) "文件夹为空" else "没有匹配的文件", description = "") }
                    itemsIndexed(entries, key = { _, file -> file.path }) { index, file ->
                        SegmentedListItem(onClick = { if (!state.busy) viewModel.openFile(file) }, shapes = ListItemDefaults.segmentedShapes(index, entries.size),
                            colors = ListItemDefaults.segmentedColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest),
                            leadingContent = { Icon(if (file.isDir) Icons.Outlined.Folder else if (workspaceImageFile(file)) Icons.Outlined.Image else Icons.Outlined.Description, null) },
                            supportingContent = { Text(if (file.isDir) "文件夹" else fileSize(file.size), style = MaterialTheme.typography.bodySmall) },
                            trailingContent = {
                                Box {
                                    var open by remember { mutableStateOf(false) }
                                    IconButton(onClick = { open = !open }, enabled = !state.busy) { Icon(Icons.Outlined.MoreVert, "操作 ${file.name}") }
                                    MemohPopupMenu(open, { open = false }, Alignment.TopEnd) {
                                        if (!file.isDir) MemohMenuRow("下载", Icons.Outlined.Download, { open = false; saveDownload(file) })
                                        if (state.fileWritable) {
                                            MemohMenuRow("重命名", Icons.Outlined.DriveFileRenameOutline, { open = false; nameAction = FileNameAction("重命名", file.name) { viewModel.renameFile(file, it) } })
                                            MemohMenuRow("删除", Icons.Outlined.DeleteOutline, { open = false; deleting = file }, accent = MaterialTheme.colorScheme.errorContainer)
                                        }
                                    }
                                }
                            }) { Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge) }
                    }
                }
                }
            }
        }
    }
    }
    }
    nameAction?.let { action ->
        var value by remember(action) { mutableStateOf(action.initial) }
        var error by remember(action) { mutableStateOf<String?>(null) }
        MemohFormDialog(onDismissRequest = { nameAction = null }, title = action.title, text = {
            OutlinedTextField(value, { value = it; error = null }, singleLine = true, supportingText = { error?.let { Text(it) } }, isError = error != null)
        }, confirmButton = { MemohActionButton("确定", Icons.Outlined.Check,
            { try { action.apply(value.trim()); nameAction = null } catch (e: Exception) { error = e.message } }, enabled = value.isNotBlank(), primary = true) },
            dismissButton = { MemohActionButton("取消", Icons.Outlined.Close, { nameAction = null }) })
    }
    deleting?.let { file ->
        var recursive by remember(file) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${file.name}？") }, text = {
            Column {
                Text("此操作会从云端电脑删除该${if (file.isDir) "文件夹" else "文件"}。")
                if (file.isDir) Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(recursive, { recursive = it }); Text("同时删除文件夹内的内容", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }, confirmButton = { MemohActionButton("删除", Icons.Outlined.DeleteOutline, { viewModel.deleteFile(file, recursive); deleting = null }, danger = true) },
            dismissButton = { MemohActionButton("取消", Icons.Outlined.Close, { deleting = null }) })
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("保留文件修改？") }, text = { Text("文件还有未保存的修改。") },
        confirmButton = { MemohActionButton("保存", Icons.Outlined.Save, { discard = false; viewModel.saveFile() }, enabled = !state.busy, primary = true) },
        dismissButton = { MemohActionButton("放弃修改", Icons.Outlined.Undo, { discard = false; viewModel.closeFile(); viewModel.refresh() }) })
}

private fun fileSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}
