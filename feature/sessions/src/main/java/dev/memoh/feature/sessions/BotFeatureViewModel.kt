package dev.memoh.feature.sessions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.model.*
import dev.memoh.core.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlinx.serialization.json.*
import javax.inject.Inject
import java.time.LocalDate
import java.time.ZoneOffset

enum class BotFeature(val title: String) {
    Memory("记忆"), Schedules("日程"), Usage("用量与状态"), Apps("应用"), Skills("技能"), Mcp("MCP"), Files("文件")
}

data class McpAuthorization(val id: String, val name: String, val status: JsonObject = JsonObject(emptyMap()), val discovered: Boolean = false,
    val needsClientId: Boolean = false, val url: String? = null, val oauthState: String? = null)

data class BotFeatureState(
    val botId: String = "",
    val feature: BotFeature = BotFeature.Memory,
    val loading: Boolean = false,
    val hasLoaded: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val query: String = "",
    val memories: List<MemoryEntry> = emptyList(),
    val memoryStatus: MemoryStatus? = null,
    val graph: MemoryGraph? = null,
    val graphMemories: List<MemoryEntry> = emptyList(),
    val schedules: List<BotSchedule> = emptyList(),
    val scheduleWire: Map<String, JsonObject> = emptyMap(),
    val scheduleOptions: ScheduleOptions = ScheduleOptions(),
    val scheduleAgentModels: Map<String, List<ChatModel>> = emptyMap(),
    val logSchedule: BotSchedule? = null,
    val logs: ScheduleLogs? = null,
    val usage: TokenUsageSummary? = null,
    val usageDays: Int = 30,
    val usageFrom: String = LocalDate.now(ZoneOffset.UTC).minusDays(29).toString(),
    val usageTo: String = LocalDate.now(ZoneOffset.UTC).plusDays(1).toString(),
    val recordsError: String? = null,
    val records: List<TokenUsageRecord> = emptyList(),
    val recordCount: Long = 0,
    val metrics: ContainerMetrics? = null,
    val apps: List<InstalledApp> = emptyList(),
    val marketApps: List<MarketApp> = emptyList(),
    val marketSkills: List<MarketSkill> = emptyList(),
    val marketPage: Int = 1,
    val marketTotal: Long = 0,
    val browsing: Boolean = false,
    val skills: List<InstalledSkill> = emptyList(),
    val connections: List<McpConnection> = emptyList(),
    val mcpExport: String? = null,
    val mcpAuthorization: McpAuthorization? = null,
    val progress: List<String> = emptyList(),
    val removal: Pair<InstalledApp, JsonObject>? = null,
    val filePath: String = "/data",
    val files: List<WorkspaceFile> = emptyList(),
    val selectedFile: WorkspaceFile? = null,
    val fileDocument: WorkspaceDocument? = null,
    val fileDraft: String = "",
    val fileEditing: Boolean = false,
    val fileNew: Boolean = false,
    val fileWritable: Boolean = true,
)

@HiltViewModel
class BotFeatureViewModel @Inject constructor(private val repository: SessionRepository) : ViewModel() {
    private val _state = MutableStateFlow(BotFeatureState())
    val state = _state.asStateFlow()
    private var loadJob: Job? = null
    private var subscription: Job? = null
    private var mcpAuthJob: Job? = null
    private val pages = BotFeaturePages()
    private var pageGeneration = repository.generation

    fun cachedState(botId: String, feature: BotFeature): BotFeatureState? =
        pages.get(botId, feature, repository.generation)

    fun open(botId: String, feature: BotFeature) {
        val generation = repository.generation
        if (pageGeneration == generation) {
            if (_state.value.botId == botId && _state.value.feature == feature) return
            pages.save(_state.value, generation)
        }
        closeMcpAuthorization()
        pageGeneration = generation
        loadJob?.cancel()
        _state.value = cachedState(botId, feature)
            ?: BotFeatureState(botId = botId, feature = feature, loading = botId.isNotBlank())
        refresh()
    }

    fun query(value: String) { _state.update { it.copy(query = value) } }
    fun usageDays(days: Int) {
        if (state.value.busy || days == state.value.usageDays) return
        val today = LocalDate.now(ZoneOffset.UTC)
        _state.update { it.copy(usageDays = days, usageFrom = today.minusDays(days.toLong() - 1).toString(),
            usageTo = today.plusDays(1).toString(), recordsError = null) }
        refresh()
    }
    fun dismiss() { _state.update { it.copy(error = null, notice = null) } }
    fun browse(value: Boolean) {
        _state.update { it.copy(browsing = value, query = "", marketPage = 1) }
        refresh()
    }

    fun refresh() {
        loadJob?.cancel()
        val current = _state.value
        if (current.botId.isBlank()) return
        val generation = repository.generation
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = repository.api() ?: error("请先登录")
                var loaded = current
                when (current.feature) {
                    BotFeature.Memory -> {
                        loaded = loaded.copy(memories = (if (current.query.isBlank()) api.memories(current.botId)
                            else api.searchMemories(current.botId, current.query)).results.orEmpty())
                        try { loaded = loaded.copy(memoryStatus = api.memoryStatus(current.botId)) }
                        catch (e: ApiException) { loaded = loaded.copy(notice = "记忆状态：${e.message}") }
                    }
                    BotFeature.Schedules -> {
                        val wire = api.managementRead("bots", current.botId, "schedule")
                        loaded = loaded.copy(schedules = Json { ignoreUnknownKeys = true }.decodeFromJsonElement<ScheduleList>(wire).items.orEmpty(),
                            scheduleWire = (wire.jsonObject["items"] as? JsonArray).orEmpty().mapNotNull { entry ->
                                val id = entry.jsonObject["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                                id to entry.jsonObject
                            }.toMap())
                    }
                    BotFeature.Usage -> {
                        loaded = loaded.copy(usage = api.tokenUsage(current.botId, current.usageFrom, current.usageTo))
                        try {
                            val records = api.tokenRecords(current.botId, current.usageFrom, current.usageTo)
                            loaded = loaded.copy(records = records.items.orEmpty(), recordCount = records.total ?: 0, recordsError = null)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { loaded = loaded.copy(recordsError = e.message ?: "使用记录加载失败") }
                        try { loaded = loaded.copy(metrics = api.containerMetrics(current.botId)) }
                        catch (e: ApiException) { loaded = loaded.copy(notice = "容器状态：${e.message}") }
                    }
                    BotFeature.Apps -> if (current.browsing) {
                        val page = api.marketApps(text = current.query)
                        loaded = loaded.copy(marketApps = page.data.orEmpty(), marketPage = 1, marketTotal = page.total ?: 0)
                    } else loaded = loaded.copy(apps = api.installedApps(current.botId).items.orEmpty())
                    BotFeature.Skills -> if (current.browsing) {
                        val page = api.marketSkills(text = current.query)
                        loaded = loaded.copy(marketSkills = page.data.orEmpty(), marketPage = 1, marketTotal = page.total ?: 0)
                    } else loaded = loaded.copy(skills = api.skills(current.botId))
                    BotFeature.Mcp -> loaded = loaded.copy(connections = api.mcpConnections(current.botId))
                    BotFeature.Files -> {
                        val listing = api.files(current.botId, current.filePath)
                        loaded = loaded.copy(filePath = listing.path, files = listing.entries.sortedWith(compareByDescending<WorkspaceFile> { it.isDir }.thenBy { it.name.lowercase() }))
                        try { loaded = loaded.copy(fileWritable = api.bot(current.botId).currentUserPermissions?.contains("workspace_write") != false) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { }
                    }
                }
                if (generation == repository.generation && itMatches(current)) _state.update {
                    // A list refresh can run while the user types into a file editor.
                    loaded.copy(loading = false, hasLoaded = true, busy = it.busy, progress = it.progress, removal = it.removal,
                        query = it.query, selectedFile = it.selectedFile, fileDocument = it.fileDocument,
                        fileDraft = it.fileDraft, fileNew = it.fileNew, fileEditing = it.fileEditing)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation && itMatches(current)) _state.update { it.copy(loading = false, error = e.message ?: "加载失败") } }
        }
    }

    /** Bot activity invalidates the currently visible list while the shell is active. */
    fun observeActivity(botId: String, onActivity: (SessionActivityEvent) -> Unit = {}) {
        subscription?.cancel()
        if (botId.isBlank()) return
        subscription = viewModelScope.launch {
            while (true) {
                try {
                    repository.api()?.sessionActivity(botId)?.collect { event ->
                        onActivity(event)
                        if (_state.value.botId == botId && !state.value.busy && !state.value.fileEditing &&
                            (event.type == "schedule_changed" || event.cacheInvalidation == true)) refresh()
                    } ?: return@launch
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { kotlinx.coroutines.delay(5000) }
            }
        }
    }
    fun stopObserving() { subscription?.cancel(); subscription = null }

    private fun action(success: String? = null, refresh: Boolean = true, work: suspend (MemohApi, String) -> Unit) {
        if (_state.value.busy) return
        val current = _state.value
        val generation = repository.generation
        viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                work(repository.api() ?: error("请先登录"), current.botId)
                if (generation == repository.generation && itMatches(current)) {
                    _state.update { it.copy(busy = false, notice = success ?: it.notice) }
                    if (refresh && itMatches(current)) refresh()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation && itMatches(current)) _state.update { it.copy(busy = false, error = e.message ?: "操作失败") } }
        }
    }
    private fun itMatches(current: BotFeatureState) = current.botId == state.value.botId && current.feature == state.value.feature

    fun openFolder(botId: String, path: String) {
        val generation = repository.generation
        if (pageGeneration == generation) pages.save(_state.value, generation)
        pageGeneration = generation
        loadJob?.cancel()
        _state.value = BotFeatureState(botId = botId, feature = BotFeature.Files, filePath = path, loading = botId.isNotBlank())
        refresh()
    }
    fun openFolder(path: String) = openFolder(state.value.botId, path)
    fun fileDraft(text: String) { _state.update { it.copy(fileDraft = text) } }
    fun fileEditing(editing: Boolean) { _state.update { it.copy(fileEditing = editing) } }
    fun closeFile() { _state.update { it.copy(selectedFile = null, fileDocument = null, fileDraft = "", fileNew = false, fileEditing = false, error = null) } }
    fun openFile(file: WorkspaceFile) {
        if (file.isDir) { openFolder(file.path); return }
        _state.update { it.copy(selectedFile = file, fileDocument = null, fileDraft = "", fileNew = false, fileEditing = false, error = null) }
        if (!workspaceTextFile(file)) return
        val generation = repository.generation
        action(refresh = false) { api, bot ->
            val document = api.readFile(bot, file.path)
            _state.update { if (generation == repository.generation && it.botId == bot && it.selectedFile?.path == file.path)
                it.copy(fileDocument = document, fileDraft = document.content) else it }
        }
    }
    fun newFile(name: String) {
        val current = state.value
        val path = workspaceChild(current.filePath, name)
        require(current.files.none { it.path == path || it.name == name }) { "同名文件已经存在，请打开后编辑" }
        _state.update { it.copy(selectedFile = WorkspaceFile(name = name, path = path),
            fileDocument = WorkspaceDocument(path), fileDraft = "", fileNew = true, fileEditing = true) }
    }
    fun saveFile() {
        val current = state.value
        val file = current.selectedFile ?: return
        val document = current.fileDocument ?: return
        val generation = repository.generation
        action("文件已保存", refresh = false) { api, bot ->
            if (!current.fileNew) require(document.revision.isNotBlank()) { "服务端没有返回文件版本，请重新打开文件后再保存" }
            try {
                val result = api.writeFile(bot, file.path, current.fileDraft, document.revision.takeIf(String::isNotBlank))
                _state.update { if (generation == repository.generation && it.botId == bot && it.selectedFile?.path == file.path)
                    it.copy(fileDocument = document.copy(content = current.fileDraft, revision = result.revision), fileNew = false) else it }
            } catch (e: ApiException) {
                if (e.status == 409) error("文件已在云端更改，草稿已保留。请先复制草稿，再重新打开文件合并修改。")
                throw e
            }
        }
    }
    fun createDirectory(name: String) {
        val path = workspaceChild(state.value.filePath, name)
        action("文件夹已创建") { api, bot -> api.makeDirectory(bot, path) }
    }
    fun renameFile(file: WorkspaceFile, name: String) {
        val path = workspaceChild(workspaceParent(file.path), name)
        action("已重命名") { api, bot -> api.renameFile(bot, file.path, path) }
    }
    fun deleteFile(file: WorkspaceFile, recursive: Boolean) = action("已删除") { api, bot -> api.deleteFile(bot, file.path, recursive) }
    suspend fun fileMedia(path: String): ByteArray {
        val generation = repository.generation
        val data = (repository.api() ?: error("请先登录")).media(path)
        check(generation == repository.generation) { "账号已切换" }
        return data
    }
    fun uploadFile(context: android.content.Context, uri: android.net.Uri) {
        val current = state.value
        action("文件已上传") { api, bot ->
            val payload = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                var name = "upload"
                context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) name = it.getString(0) ?: name
                }
                require(current.files.none { it.name == name }) { "同名文件已经存在，请先重命名或移走原文件" }
                val output = java.io.ByteArrayOutputStream()
                requireNotNull(context.contentResolver.openInputStream(uri)).use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val count = stream.read(buffer)
                        if (count < 0) break
                        require(output.size() + count <= 16 * 1024 * 1024) { "文件超过 16 MB" }
                        output.write(buffer, 0, count)
                    }
                }
                Triple(name, mime, output.toByteArray())
            }
            api.uploadFile(bot, workspaceChild(current.filePath, payload.first), payload.first, payload.second, payload.third)
        }
    }

    fun saveMemory(id: String?, text: String) = action("记忆已保存") { api, bot ->
        if (id == null) api.addMemory(bot, text) else api.editMemory(bot, id, text)
    }
    fun deleteMemory(id: String) = action("记忆已删除") { api, bot -> api.deleteMemory(bot, id) }
    fun compactMemory() = action("已提交记忆整理") { api, bot -> api.compactMemory(bot) }
    fun showGraph() = action(refresh = false) { api, bot ->
        val graph = api.memoryGraph(bot)
        val entries = api.memories(bot).results.orEmpty()
        _state.update { it.copy(graph = graph, graphMemories = entries) }
    }
    fun closeGraph() { _state.update { it.copy(graph = null) } }
    fun saveSchedule(id: String?, body: JsonObject, onSaved: () -> Unit = {}) = action("日程已保存") { api, bot -> api.saveSchedule(bot, id, body); onSaved() }
    fun scheduleOptions() = action(refresh = false) { api, bot ->
        val zone = api.bot(bot).timezone ?: runCatching { api.managementRead("users", "me").jsonObject["timezone"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        _state.update { it.copy(scheduleOptions = ScheduleOptions(api.models(), api.agents(bot), api.workdirs(bot), api.sessions(bot).items, zone)) }
    }
    fun scheduleAgentOptions(id: String) = action(refresh = false) { api, bot ->
        try { val models = api.agentModels(bot, id).pickerModels; _state.update { it.copy(scheduleAgentModels = it.scheduleAgentModels + (id to models)) } }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { _state.update { it.copy(scheduleAgentModels = it.scheduleAgentModels + (id to emptyList()), error = "Agent 模型列表读取失败") } }
    }
    fun deleteSchedule(id: String) = action("日程已删除") { api, bot -> api.deleteSchedule(bot, id) }
    fun toggleSchedule(schedule: BotSchedule) = action { api, bot -> api.saveSchedule(bot, requireNotNull(schedule.id), apiBody("enabled" to (schedule.enabled != true))) }
    fun logs(schedule: BotSchedule) = action(refresh = false) { api, bot ->
        val logs = api.scheduleLogs(bot, requireNotNull(schedule.id))
        _state.update { it.copy(logSchedule = schedule, logs = logs) }
    }
    fun closeLogs() { _state.update { it.copy(logSchedule = null, logs = null) } }
    fun moreRecords() = action(refresh = false) { api, bot ->
        val current = state.value
        val page = api.tokenRecords(bot, current.usageFrom, current.usageTo, current.records.size)
        _state.update { if (it.botId == bot && it.feature == BotFeature.Usage && it.usageFrom == current.usageFrom && it.usageTo == current.usageTo)
            it.copy(records = (it.records + page.items.orEmpty()).distinctBy { record -> record.id }, recordCount = page.total ?: it.recordCount, recordsError = null) else it }
    }
    fun moreMarket() = action(refresh = false) { api, _ ->
        val current = state.value
        if (current.feature == BotFeature.Apps) {
            val page = api.marketApps(current.marketPage + 1, current.query)
            _state.update { it.copy(marketApps = it.marketApps + page.data.orEmpty(), marketPage = current.marketPage + 1, marketTotal = page.total ?: it.marketTotal) }
        } else {
            val page = api.marketSkills(current.marketPage + 1, current.query)
            _state.update { it.copy(marketSkills = it.marketSkills + page.data.orEmpty(), marketPage = current.marketPage + 1, marketTotal = page.total ?: it.marketTotal) }
        }
    }
    fun install(registryId: String, appId: String) = installOperation { api, bot ->
        val descriptor = api.marketApp(registryId, appId)
        val revision = descriptor["revision"]?.jsonPrimitive?.contentOrNull ?: error("应用没有可安装版本")
        api.installApp(bot, registryId, appId, revision)
    }
    fun resume(app: InstalledApp) = installOperation { api, bot -> api.resumeApp(bot, requireNotNull(app.installationId)) }
    fun update(app: InstalledApp) = installOperation { api, bot -> api.updateApp(bot, requireNotNull(app.registryId), requireNotNull(app.appId)) }
    fun previewRemove(app: InstalledApp) = action(refresh = false) { api, bot ->
        val preview = api.appRemovalPreview(bot, requireNotNull(app.installationId))
        _state.update { it.copy(removal = app to preview) }
    }
    fun cancelRemove() { _state.update { it.copy(removal = null) } }
    fun confirmRemove() {
        val app = state.value.removal?.first ?: return
        cancelRemove()
        installOperation { api, bot -> api.removeApp(bot, requireNotNull(app.installationId)) }
    }
    fun checkUpdates() = action("已检查更新") { api, bot -> api.checkAppUpdates(bot) }
    private fun installOperation(stream: suspend (MemohApi, String) -> Flow<AppInstallEvent>) = action("操作完成") { api, bot ->
        _state.update { it.copy(progress = emptyList()) }
        var terminal: AppInstallEvent? = null
        stream(api, bot).collect { event ->
            val line = event.message ?: event.data ?: listOfNotNull(event.type, event.kind, event.id, event.status).joinToString(" · ")
            _state.update { it.copy(progress = (it.progress + line).takeLast(100)) }
            if (event.type == "error") error(event.message ?: event.detail ?: "安装失败")
            if (event.type == "done") terminal = event
        }
        val done = terminal ?: error("连接结束，尚未确认操作完成。请刷新应用状态。")
        if (done.status in listOf("failed", "partial", "error")) error(done.message ?: "操作未完整完成（${done.status}），请查看应用状态并重试。")
        _state.update { it.copy(browsing = false) }
    }
    fun saveSkill(path: String?, raw: String) = action("技能已保存") { api, bot -> api.saveSkill(bot, raw, path) }
    fun deleteSkill(path: String) = action("技能已删除") { api, bot -> api.deleteSkill(bot, path) }
    fun toggleSkill(skill: InstalledSkill) = action { api, bot -> api.skillAction(bot, if (skill.state == "disabled") "enable" else "disable", requireNotNull(skill.sourcePath)) }
    fun saveMcp(id: String?, body: JsonObject) = action("MCP 已保存") { api, bot -> api.saveMcp(bot, id, body) }
    fun deleteMcp(id: String) = action("MCP 已删除") { api, bot -> api.deleteMcp(bot, id) }
    fun importMcp(config: JsonObject) = action("MCP 已导入") { api, bot -> api.importMcp(bot, config) }
    fun exportMcp() = action(refresh = false) { api, bot ->
        val config = api.exportMcp(bot)
        _state.update { it.copy(mcpExport = Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), config)) }
    }
    fun closeMcpExport() { _state.update { it.copy(mcpExport = null) } }
    fun mcpAuthorization(connection: McpConnection) {
        val id = connection.id ?: return
        val generation = repository.generation
        action(refresh = false) { api, bot ->
            val status = api.managementRead("bots", bot, "mcp", id, "oauth", "status") as? JsonObject ?: error("授权状态不可用")
            if (generation == repository.generation && state.value.botId == bot) _state.update { it.copy(mcpAuthorization = McpAuthorization(id, connection.name ?: "MCP", status)) }
        }
    }
    fun authorizeMcp(clientId: String, clientSecret: String) {
        val auth = state.value.mcpAuthorization ?: return
        val generation = repository.generation
        action(refresh = false) { api, bot ->
            val base = listOf("bots", bot, "mcp", auth.id, "oauth")
            val needsId = if (auth.discovered) auth.needsClientId else {
                val discovery = api.managementResult(base + "discover", "POST", apiBody()).jsonObject
                (discovery["registration_endpoint"] as? JsonPrimitive)?.contentOrNull.isNullOrBlank()
            }
            if (generation != repository.generation || state.value.botId != bot) return@action
            _state.update { it.copy(mcpAuthorization = auth.copy(discovered = true, needsClientId = needsId)) }
            require(!needsId || clientId.isNotBlank()) { "此服务需要填写 OAuth client ID" }
            val result = api.managementResult(base + "authorize", "POST", apiBody("client_id" to clientId.takeIf(String::isNotBlank), "client_secret" to clientSecret.takeIf(String::isNotBlank)))
            val url = (result.jsonObject["authorization_url"] as? JsonPrimitive)?.contentOrNull?.toHttpUrlOrNull() ?: error("服务未返回授权地址")
            if (generation != repository.generation || state.value.botId != bot) return@action
            _state.update { it.copy(mcpAuthorization = auth.copy(discovered = true, needsClientId = needsId, url = url.toString(), oauthState = url.queryParameter("state"))) }
            pollMcpAuthorization(bot, auth.id, generation)
        }
    }
    private fun pollMcpAuthorization(bot: String, id: String, generation: Long) {
        mcpAuthJob?.cancel()
        mcpAuthJob = viewModelScope.launch {
            try {
                repeat(120) {
                    delay(2500)
                    if (generation != repository.generation || state.value.botId != bot || state.value.mcpAuthorization?.id != id) return@launch
                    val status = repository.api()?.managementRead("bots", bot, "mcp", id, "oauth", "status") as? JsonObject ?: return@launch
                    _state.update { current -> current.copy(mcpAuthorization = current.mcpAuthorization?.copy(status = status)) }
                    if ((status["has_token"] as? JsonPrimitive)?.booleanOrNull == true && (status["expired"] as? JsonPrimitive)?.booleanOrNull != true) {
                        _state.update { it.copy(notice = "MCP 已授权") }; refresh(); return@launch
                    }
                }
                _state.update { it.copy(notice = "授权等待已结束，可重新检查授权状态") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation && state.value.botId == bot) _state.update { it.copy(error = "检查授权状态失败，请重试") } }
        }
    }
    fun exchangeMcpCallback(callback: String) {
        val auth = state.value.mcpAuthorization ?: return
        val generation = repository.generation
        action("MCP 已授权") { api, bot ->
            api.managementWrite(listOf("bots", bot, "mcp", auth.id, "oauth", "exchange"), "POST", mcpCallbackBody(callback, auth.oauthState))
            val status = api.managementRead("bots", bot, "mcp", auth.id, "oauth", "status").jsonObject
            if (generation == repository.generation && state.value.botId == bot) _state.update { it.copy(mcpAuthorization = auth.copy(status = status, url = null, oauthState = null)) }
            mcpAuthJob?.cancel()
        }
    }
    fun revokeMcpAuthorization() {
        val auth = state.value.mcpAuthorization ?: return
        val generation = repository.generation
        action("MCP 授权已断开") { api, bot ->
            api.managementWrite(listOf("bots", bot, "mcp", auth.id, "oauth", "token"), "DELETE")
            if (generation == repository.generation && state.value.botId == bot) closeMcpAuthorization()
        }
    }
    fun checkMcpAuthorization() {
        val auth = state.value.mcpAuthorization ?: return
        val generation = repository.generation
        action(refresh = true) { api, bot ->
            val status = api.managementRead("bots", bot, "mcp", auth.id, "oauth", "status").jsonObject
            if (generation == repository.generation && state.value.botId == bot) _state.update { it.copy(mcpAuthorization = auth.copy(status = status)) }
        }
    }
    fun closeMcpAuthorization() { mcpAuthJob?.cancel(); _state.update { it.copy(mcpAuthorization = null) } }
    fun toggleMcp(connection: McpConnection) = action("MCP 状态已更新") { api, bot ->
        api.saveMcp(bot, requireNotNull(connection.id), JsonObject(connection.config.orEmpty() + apiBody(
            "name" to connection.name, "transport" to connection.type, "is_active" to (connection.isActive != true))))
    }
    fun probeMcp(id: String) = action(refresh = true) { api, bot ->
        val result = api.probeMcp(bot, id)
        _state.update { it.copy(notice = result["status"]?.jsonPrimitive?.contentOrNull ?: "连通性检查已完成") }
    }
}

internal fun mcpCallbackBody(callback: String, expectedState: String?): JsonObject {
    val url = callback.toHttpUrlOrNull() ?: error("请粘贴完整的回调地址")
    require(url.queryParameter("error").isNullOrBlank()) { "服务未完成授权，请重新授权" }
    val code = url.queryParameterValues("code").singleOrNull()?.takeIf(String::isNotBlank) ?: error("回调地址缺少有效 code")
    val returnedState = url.queryParameterValues("state").singleOrNull()?.takeIf(String::isNotBlank) ?: error("回调地址缺少有效 state")
    require(!expectedState.isNullOrBlank() && returnedState == expectedState) { "此回调不属于当前授权，请重新授权" }
    return apiBody("code" to code, "state" to returnedState)
}
