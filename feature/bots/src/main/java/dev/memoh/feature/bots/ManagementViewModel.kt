package dev.memoh.feature.bots

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.memoh.core.data.SessionRepository
import dev.memoh.core.model.Bot
import dev.memoh.core.network.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import javax.inject.Inject
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ManagementPage(val title: String, val description: String) {
    Bots("Bot 管理", "创建、查看和管理 Bot"),
    Bot("Bot 设置", "名称、默认对话、上下文和工具策略"),
    Agents("Agent 设置", "启停、认证和默认 Agent"),
    Models("模型设置", "供应商、模型目录和连接测试"),
    Media("语音与视频", "语音合成、转录和视频模型"),
    Services("工具服务", "记忆、搜索和网页读取服务"),
    Connectors("连接器", "授权、重连和外部服务管理"),
    Computers("Computers", "接入电脑、Bot 绑定和执行权限"),
    Hooks("Hooks", "事件、配置和测试"),
    Channels("消息渠道", "渠道认证、启停和路由"),
    Workspace("工作空间管理", "运行状态、资源限制和快照"),
    Access("成员与权限", "Bot 成员和访问权限"),
    Backup("备份与恢复", "导入、导出和选择恢复内容"),
    Network("网络设置", "连接状态、网络配置和出口节点"),
    Account("个人资料", "资料、渠道身份和电脑访问"),
    Team("Cloud 团队与额度", "团队资料、成员、邀请和套餐用量"),
    ;
    val needsBot: Boolean get() = this !in listOf(Bots, Models, Media, Services, Computers, Backup, Account, Team)
}

internal fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.text(key: String): String = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
internal fun JsonObject.flag(key: String, fallback: Boolean = false): Boolean = (get(key) as? JsonPrimitive)?.booleanOrNull ?: fallback
internal fun JsonElement?.objects(key: String? = null): List<JsonObject> =
    ((if (key == null) this else obj()[key]) as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

data class ManagementState(
    val botId: String = "", val page: ManagementPage = ManagementPage.Bot,
    val loading: Boolean = false, val loaded: Boolean = false, val busy: Boolean = false,
    val bot: Bot? = null, val data: Map<String, JsonElement> = emptyMap(),
    val error: String? = null, val notice: String? = null, val result: JsonElement? = null,
    val authorization: JsonObject? = null,
    val agentOptions: Map<String, JsonObject> = emptyMap(),
    val dependencyPrompt: JsonObject? = null,
    val operationLog: String? = null,
    val audio: AudioPreview? = null,
    val cloud: Boolean = false,
    val backupImport: BackupImport? = null,
) {
    val canManage: Boolean get() = if (page == ManagementPage.Team) teamRole(data["membership"].obj().text("role")) in listOf("owner", "admin") else !page.needsBot || bot?.can("manage") == true
    val canManageBot: Boolean get() = bot?.can("manage") == true
}

@HiltViewModel
class ManagementViewModel @Inject constructor(private val repository: SessionRepository) : ViewModel() {
    private val _state = MutableStateFlow(ManagementState())
    val state = _state.asStateFlow()
    private var job: Job? = null
    private var authJob: Job? = null
    private var authId: String? = null

    fun open(botId: String, page: ManagementPage) {
        if (state.value.botId == botId && state.value.page == page && state.value.loaded) return
        job?.cancel()
        _state.value = ManagementState(botId, page, cloud = repository.state.value.account?.kind == "cloud")
        refresh()
    }

    fun refresh() {
        if (state.value.busy) return
        job?.cancel()
        val current = state.value
        val generation = repository.generation
        job = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val api = repository.api() ?: error("请先登录")
                val loaded = load(api, current)
                if (generation == repository.generation) _state.update {
                    it.copy(bot = loaded.first, data = loaded.second, loading = false, loaded = true)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(loading = false, error = errorText(e)) } }
        }
    }

    private suspend fun load(api: MemohApi, current: ManagementState): Pair<Bot?, Map<String, JsonElement>> {
        val bot = if (current.botId.isNotBlank() && (current.page.needsBot || current.page in listOf(ManagementPage.Computers, ManagementPage.Backup))) api.bot(current.botId) else null
        val base = arrayOf("bots", current.botId)
        val data = mutableMapOf<String, JsonElement>()
        suspend fun get(name: String, vararg path: String) { data[name] = api.managementRead(*path) }
        suspend fun optional(name: String, vararg path: String) {
            try { get(name, *path) } catch (e: CancellationException) { throw e }
            catch (e: Exception) { data["$name-error"] = JsonPrimitive(errorText(e)) }
        }
        when (current.page) {
            ManagementPage.Team -> {
                check(current.cloud) { "此功能用于 Memoh Cloud" }
                val teams = repository.cloudTeams()
                val teamId = repository.state.value.account?.teamId
                val membership = teams.firstOrNull { it.team?.teamId == teamId }
                data["teams"] = Json.encodeToJsonElement(teams)
                membership?.let { data["membership"] = Json.encodeToJsonElement(it) }
                if (teamId != null) {
                    suspend fun cloud(key: String, path: String) {
                        try { data[key] = repository.cloudRead(path) } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { data["$key-error"] = JsonPrimitive(errorText(e)) }
                    }
                    cloud("members", managementPath("teams", teamId, "members"))
                    val account = repository.state.value.account
                    val self = data["members"].objects("members").firstOrNull {
                        it.text("user_id") == account?.userId || it.text("email").equals(account?.username, true)
                    }
                    self?.let { data["self-user-id"] = JsonPrimitive(it.text("user_id")) }
                    if (teamRole(membership?.role.orEmpty()) in listOf("owner", "admin")) cloud("invitations", managementPath("teams", teamId, "invitations"))
                    cloud("entitlements", "/subscription/entitlements/memoh")
                    cloud("credits", "/llm/billing-usage")
                    cloud("subscriptions", "/subscription/subscriptions?namespace=memoh&include_history=false")
                }
            }
            ManagementPage.Account -> {
                if (current.cloud) data["user"] = cloudUserProfile(repository.cloudRead("/users/me", unscoped = true))
                else get("user", "users", "me")
                optional("identities", "users", "me", "channel-identities")
                optional("computer-access", "users", "me", "computer-access")
                optional("channels", "channels")
            }
            ManagementPage.Access -> {
                get("grants", *base, "user-access")
                get("candidates", *base, "user-access", "candidates")
                if (current.cloud) {
                    try { data["access-current-user"] = cloudUserProfile(repository.cloudRead("/users/me", unscoped = true)) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    repository.state.value.account?.teamId?.let { teamId ->
                        try { data["access-members"] = repository.cloudRead(managementPath("teams", teamId, "members")) }
                        catch (e: CancellationException) { throw e } catch (_: Exception) { }
                    }
                } else optional("access-current-user", "users", "me")
                optional("channel-managers", *base, "channel-managers")
                optional("acl-rules", *base, "acl", "rules")
                optional("acl-default", *base, "acl", "default-effect")
                optional("acl-identities", *base, "acl", "channel-identities")
                optional("channels", "channels")
            }
            ManagementPage.Workspace -> {
                optional("container", *base, "container")
                optional("metrics", *base, "container", "metrics")
                optional("capabilities", "ping")
                if (data["capabilities"].obj().flag("snapshot_supported", true)) optional("snapshots", *base, "container", "snapshots")
                optional("settings", *base, "settings")
                optional("display", *base, "container", "display")
            }
            ManagementPage.Backup -> if (bot != null) optional("summary", *base, "backup", "summary")
            ManagementPage.Network -> {
                get("settings", *base, "settings")
                get("network-meta", "network", "meta")
                if (data["settings"].obj().flag("overlay_enabled")) {
                    optional("network-status", *base, "network", "status")
                    optional("network-nodes", *base, "network", "nodes")
                }
            }
            ManagementPage.Bots -> get("bots", "bots")
            ManagementPage.Bot -> {
                get("settings", *base, "settings")
                get("agents", *base, "agents")
                get("models", "models")
                for (kind in listOf("speech", "transcription", "video")) {
                    try { get("$kind-models", "$kind-models") } catch (e: ApiException) { if (e.status != 404) throw e }
                }
                for (kind in listOf("search", "fetch", "memory")) {
                    try { get("$kind-providers", "$kind-providers") }
                    catch (e: ApiException) { if (e.status != 404) throw e }
                }
            }
            ManagementPage.Agents -> {
                get("agents", *base, "agents")
                get("settings", *base, "settings")
                get("profiles", "acp", "profiles")
            }
            ManagementPage.Models -> {
                get("models", "models"); get("providers", "providers")
                try { get("templates", "provider-templates") } catch (e: ApiException) { if (e.status != 404) throw e }
            }
            ManagementPage.Services -> for (kind in listOf("search", "fetch", "memory")) {
                try { get("$kind-providers", "$kind-providers"); get("$kind-meta", "$kind-providers", "meta") }
                catch (e: ApiException) { if (e.status != 404) throw e }
            }
            ManagementPage.Media -> {
                try { get("templates", "provider-templates") } catch (e: ApiException) { if (e.status != 404) throw e }
                for (kind in listOf("speech", "transcription", "video")) {
                    try { get("$kind-providers", "$kind-providers"); get("$kind-models", "$kind-models") }
                    catch (e: ApiException) { if (e.status != 404) throw e }
                }
            }
            ManagementPage.Connectors -> {
                get("catalog", "connectors", "catalog")
                get("connections", *base, "connectors")
                get("apps", *base, "apps")
            }
            ManagementPage.Computers -> { get("runtimes", "users", "me", "runtimes"); if (bot != null) get("targets", *base, "workspace-targets") }
            ManagementPage.Hooks -> {
                get("events", *base, "hooks", "events")
                try { data["hooks"] = JsonPrimitive(api.readFile(current.botId, "/data/.memoh/hooks.json").content.orEmpty()) }
                catch (e: ApiException) { if (e.status == 404) data["hooks"] = JsonPrimitive("{\"version\":1,\"enabled\":false,\"hooks\":[]}") else throw e }
            }
            ManagementPage.Channels -> {
                get("channels", "channels")
                optional("channel-current-user", "users", "me")
                val configs = mutableMapOf<String, JsonElement>()
                data["channels"].objects().forEach { channel ->
                    val type = channel.text("type")
                    if (type.isNotBlank()) {
                        try { configs[type] = api.managementRead(*base, "channel", type) }
                        catch (e: ApiException) { if (e.status != 404) throw e }
                    }
                }
                data["configs"] = JsonObject(configs)
            }
        }
        return bot to data
    }

    fun write(path: List<String>, method: String, body: JsonObject? = null, result: Boolean = false, onSaved: () -> Unit = {}) {
        val botWrite = path.firstOrNull() == "bots" && path.size > 1 && method != "GET"
        val allowed = if (state.value.page == ManagementPage.Bots) state.value.data["bots"].objects("items")
            .firstOrNull { it.text("id") == path.getOrNull(1) }?.get("current_user_permissions").let { it is JsonArray && JsonPrimitive("manage") in it }
            else state.value.canManageBot
        if (state.value.busy || botWrite && !allowed || !botWrite && !state.value.canManage) return
        job?.cancel()
        val generation = repository.generation
        val current = state.value
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                val api = repository.api() ?: error("请先登录")
                val response = if (result) api.managementResult(path, method, body) else { api.managementWrite(path, method, body); null }
                val loaded = try { load(api, current) } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { null }
                if (generation == repository.generation) {
                    _state.update { it.copy(busy = false, bot = loaded?.first ?: it.bot, data = loaded?.second ?: it.data,
                        notice = if (loaded == null) "操作已完成，刷新页面可读取最新配置" else if (result) null else "已保存", result = response) }
                    onSaved()
                    if (result && response.obj().text("connection_id").isNotBlank() && response.obj().text("authorization_url").isNotBlank()) watchConnector(response.obj().text("connection_id"))
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(busy = false, error = errorText(e)) } }
        }
    }

    fun saveHooks(content: String, onSaved: () -> Unit) {
        val config = Json.parseToJsonElement(content) as? JsonObject ?: error("Hooks 配置应为 JSON 对象")
        require(config["hooks"] is JsonArray) { "hooks 应为数组" }
        config["hooks"].objects().forEachIndexed { index, hook ->
            require(hook.text("event").isNotBlank()) { "第 ${index + 1} 条 Hook 缺少 event" }
            require(hook["actions"] is JsonArray) { "第 ${index + 1} 条 Hook 的 actions 应为数组" }
        }
        write(listOf("bots", state.value.botId, "container", "fs", "write"), "POST",
            apiBody("path" to "/data/.memoh/hooks.json", "content" to content), onSaved = onSaved)
    }

    fun authorizeAgent(agent: JsonObject, kind: String, secret: String = "") {
        if (state.value.busy || !state.value.canManage) return
        cancelAuthorization()
        val botId = state.value.botId
        val path = listOf("bots", botId, "agents", agent.text("id"), "credential", "claim")
        val generation = repository.generation
        authJob = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val api = repository.api() ?: error("请先登录")
                var auth = api.managementResult(listOf("agent-authorizations"), body = apiBody("runtime" to agent.text("runtime"), "auth_kind" to kind,
                    "secret" to secret.takeIf(String::isNotBlank)?.let { apiBody((if (kind == "claude_code_oauth") "oauth_token" else "api_key") to it) })).obj()
                authId = auth.text("id")
                _state.update { it.copy(busy = false, authorization = auth) }
                while (generation == repository.generation && auth.text("status") == "pending") {
                    delay(((auth["interval_seconds"] as? JsonPrimitive)?.longOrNull ?: 5L).coerceAtLeast(5) * 1000)
                    auth = api.managementResult(listOf("agent-authorizations", auth.text("id"), "poll")).obj()
                    _state.update { it.copy(authorization = auth) }
                }
                if (generation == repository.generation && auth.text("status") == "ready") {
                    api.managementWrite(path, "POST", apiBody("authorization_id" to auth.text("id")))
                    val authMode = when (kind) { "openai_codex_oauth" -> "chatgpt"; "claude_code_oauth" -> "oauth_token"; else -> "api_key" }
                    api.managementWrite(listOf("bots", botId, "agents", agent.text("id")), "PATCH",
                        apiBody("metadata" to JsonObject(agent["metadata"].obj() + mapOf("auth" to JsonPrimitive(authMode)))))
                    authId = null
                    _state.update { it.copy(authorization = null, notice = "Agent 已连接") }
                    refresh()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(busy = false, error = errorText(e)) } }
        }
    }

    fun cancelAuthorization() {
        val wasAuthorizing = authJob?.isActive == true && state.value.busy
        authJob?.cancel()
        val id = authId
        authId = null
        _state.update { it.copy(authorization = null, busy = if (wasAuthorizing) false else it.busy) }
        if (!id.isNullOrBlank()) viewModelScope.launch { runCatching { repository.api()?.managementWrite(listOf("agent-authorizations", id), "DELETE") } }
    }

    fun providerAuthorization(id: String) {
        if (state.value.busy) return
        authJob?.cancel()
        val generation = repository.generation
        authJob = viewModelScope.launch {
            try {
                val api = repository.api() ?: error("请先登录")
                _state.update { it.copy(busy = true, error = null) }
                var result = api.managementRead("providers", id, "oauth", "authorize").obj()
                _state.update { it.copy(busy = false, result = result) }
                val expires = (result["device"].obj()["expires_at"] as? JsonPrimitive)?.longOrNull
                val deadline = System.currentTimeMillis() + 15 * 60 * 1000
                while (generation == repository.generation && state.value.result != null && System.currentTimeMillis() < deadline) {
                    delay(((result["device"].obj()["interval_seconds"] as? JsonPrimitive)?.longOrNull ?: 5).coerceAtLeast(5) * 1000)
                    result = if (result["device"] != null) api.managementResult(listOf("providers", id, "oauth", "poll")).obj()
                        else api.managementRead("providers", id, "oauth", "status").obj()
                    if (generation != repository.generation) break
                    if (result.flag("has_token") && !result.flag("expired")) {
                        _state.update { it.copy(result = result, notice = "供应商已连接") }; refresh(); break
                    }
                    _state.update { it.copy(result = JsonObject(it.result.obj() + result)) }
                    if (expires != null && System.currentTimeMillis() > expires * 1000) break
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(busy = false, error = errorText(e)) } }
        }
    }

    private fun watchConnector(id: String) {
        authJob?.cancel()
        val generation = repository.generation
        val bot = state.value.botId
        authJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + 15 * 60 * 1000
            try {
                while (generation == repository.generation && state.value.result != null && System.currentTimeMillis() < deadline) {
                    delay(5000)
                    val connection = repository.api()?.managementRead("bots", bot, "connectors", id).obj()
                    if (connection.text("status") in listOf("connected", "linked", "active")) {
                        _state.update { it.copy(result = connection, notice = "连接器已授权") }; refresh(); break
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(error = errorText(e)) } }
        }
    }

    fun agentOptions(id: String) {
        if (id in state.value.agentOptions) return
        val generation = repository.generation
        val bot = state.value.botId
        viewModelScope.launch {
            val api = repository.api() ?: return@launch
            val catalog = runCatching { api.managementRead("bots", bot, "agents", id, "models") }.getOrNull()
            val controls = runCatching { api.managementRead("bots", bot, "agents", id, "runtime-controls") }.getOrNull()
            if (generation == repository.generation) _state.update { it.copy(agentOptions = it.agentOptions + (id to apiBody("catalog" to catalog, "controls" to controls))) }
        }
    }

    fun enableAgent(agent: JsonObject, enabled: Boolean) {
        if (!enabled || agent["dependency"].obj().text("dependency_id").isBlank()) {
            write(listOf("bots", state.value.botId, "agents", agent.text("id")), "PATCH", apiBody("enabled" to enabled)); return
        }
        if (state.value.busy || !state.value.canManageBot) return
        val generation = repository.generation
        val bot = state.value.botId
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val api = repository.api() ?: error("请先登录")
                val dependency = agent["dependency"].obj().text("dependency_id")
                val response = api.managementResult(listOf("bots", bot, "dependencies", "preflight"), body = apiBody("dependency_ids" to listOf(dependency))).obj()
                val item = response["items"].objects().firstOrNull { it.text("dependency_id") == dependency }.obj()
                if (generation != repository.generation) return@launch
                _state.update { it.copy(busy = false) }
                if (response.text("workspace_state") != "running") error("请先启动 Bot 的云端工作空间")
                when (item.text("state")) {
                    "satisfied" -> write(listOf("bots", bot, "agents", agent.text("id")), "PATCH", apiBody("enabled" to true))
                    "missing" -> _state.update { it.copy(dependencyPrompt = apiBody("agent" to agent, "dependency" to dependency, "name" to item.text("name"))) }
                    "platform_unsupported" -> error("此工作空间的平台不支持 ${item.text("name")}")
                    else -> error("服务端未确认此 Agent 的运行依赖")
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(busy = false, error = errorText(e)) } }
        }
    }

    fun installAgentDependency() {
        val prompt = state.value.dependencyPrompt ?: return
        val bot = state.value.botId
        val generation = repository.generation
        _state.update { it.copy(dependencyPrompt = null, operationLog = "正在安装 ${prompt.text("name")}…", busy = true) }
        job = viewModelScope.launch {
            try {
                val api = repository.api() ?: error("请先登录")
                var done = false
                api.managementStream(listOf("bots", bot, "dependencies", prompt.text("dependency"), "install")).collect { event ->
                    if (generation != repository.generation) throw CancellationException()
                    val frame = event.obj()
                    when (frame.text("type")) {
                        "done" -> done = true
                        "error" -> error(frame.text("message").ifBlank { frame.text("detail").ifBlank { "运行依赖安装失败" } })
                        else -> _state.update { it.copy(operationLog = (it.operationLog.orEmpty() + "\n" + frame.text("data")).takeLast(12000)) }
                    }
                }
                check(done) { "连接已结束，尚未收到安装完成结果" }
                _state.update { it.copy(busy = false, operationLog = null) }
                enableAgent(prompt["agent"].obj(), true)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(busy = false, operationLog = null, error = errorText(e)) } }
        }
    }
    fun dismissDependency() { _state.update { it.copy(dependencyPrompt = null) } }
    fun dismissAudio() { _state.update { it.copy(audio = null) } }
    fun mediaModelFields(kind: String, model: JsonObject, onLoaded: (JsonObject) -> Unit) {
        if (state.value.busy) return
        val generation = repository.generation
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val api = repository.api() ?: error("请先登录")
                val capabilities = if (kind != "video") api.managementRead("$kind-models", model.text("id"), "capabilities").obj()
                    else api.managementRead("video-providers", model.text("provider_id"), "models").objects().firstOrNull { it.text("id") == model.text("model_id") }.obj()
                if (generation == repository.generation) { _state.update { it.copy(busy = false) }; onLoaded(capabilities) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) { _state.update { it.copy(busy = false, notice = "未获取到参数目录，可编辑现有配置") }; onLoaded(JsonObject(emptyMap())) } }
        }
    }
    fun synthesize(model: JsonObject, text: String, onSaved: () -> Unit) {
        if (state.value.busy) return
        val generation = repository.generation
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val audio = repository.api()?.testSpeech(model.text("id"), text, model["config"].obj()) ?: error("请先登录")
                if (generation == repository.generation) { _state.update { it.copy(busy = false, audio = AudioPreview(audio.first, audio.second)) }; onSaved() }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(busy = false, error = errorText(e)) } }
        }
    }
    fun transcribe(context: Context, model: JsonObject, uri: Uri) {
        if (state.value.busy) return
        val generation = repository.generation
        val app = context.applicationContext
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null) }
            try {
                val file = withContext(Dispatchers.IO) {
                    val name = app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "audio"
                    val output = java.io.ByteArrayOutputStream()
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val read = input.read(buffer); if (read < 0) break; require(output.size() + read <= 20 * 1024 * 1024) { "请使用 20 MB 以内的音频" }; output.write(buffer, 0, read) }
                    } ?: error("无法读取音频")
                    Triple(name, app.contentResolver.getType(uri) ?: "application/octet-stream", output.toByteArray())
                }
                val result = repository.api()?.testTranscription(model.text("id"), file.first, file.second, file.third, model["config"].obj()) ?: error("请先登录")
                if (generation == repository.generation) _state.update { it.copy(busy = false, result = result) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(busy = false, error = errorText(e)) } }
        }
    }
    fun dismiss() { _state.update { it.copy(error = null, notice = null, result = null) } }

    fun cloudWrite(path: List<String>, method: String, body: JsonObject? = null, removedTeam: Boolean = false, onSaved: () -> Unit = {}) {
        val current = state.value
        val profile = current.page == ManagementPage.Account && path == listOf("users", "me") && method == "PATCH"
        if (!current.cloud || !profile && current.page != ManagementPage.Team || current.busy || !current.canManage && !(path == listOf("teams") && method == "POST")) return
        val generation = repository.generation
        transfer("已保存") { api, snapshot ->
            repository.cloudWrite(managementPath(*path.toTypedArray()), method, body, unscoped = profile)
            if (removedTeam) { repository.reloadAfterCloudTeamRemoved(); return@transfer }
            val loaded = try { load(api, snapshot) } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
            if (generation == repository.generation) {
                loaded?.let { data -> _state.update { it.copy(data = data.second) } }
                onSaved()
            }
        }
    }

    private fun transfer(notice: String?, task: suspend (MemohApi, ManagementState) -> Unit) {
        if (state.value.busy) return
        job?.cancel()
        val current = state.value
        val generation = repository.generation
        job = viewModelScope.launch {
            _state.update { it.copy(busy = true, error = null, notice = null) }
            try {
                task(repository.api() ?: error("请先登录"), current)
                if (generation == repository.generation) _state.update { it.copy(busy = false, notice = notice) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { if (generation == repository.generation) _state.update { it.copy(busy = false, error = errorText(e)) } }
        }
    }

    fun removeWorkspace(preserveData: Boolean, onSaved: () -> Unit) {
        if (!state.value.canManageBot) return
        val generation = repository.generation
        transfer("工作空间已删除") { api, current ->
            api.deleteWorkspace(current.botId, preserveData)
            val loaded = load(api, current)
            if (generation == repository.generation) { _state.update { it.copy(bot = loaded.first, data = loaded.second) }; onSaved() }
        }
    }

    fun exportBackup(context: Context, uri: Uri, sections: List<String>, passphrase: String) {
        if (!state.value.canManageBot || sections.isEmpty()) return
        val generation = repository.generation
        transfer("备份已导出") { api, current ->
            val bytes = api.exportBotBackup(current.botId, sections, passphrase)
            if (generation != repository.generation) return@transfer
            withContext(Dispatchers.IO) { requireNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(bytes) } }
        }
    }

    fun inspectBackup(context: Context? = null, uri: Uri? = null, mode: String = "create", passphrase: String = "") {
        if (mode == "overwrite" && !state.value.canManageBot) return
        val existing = state.value.backupImport
        val generation = repository.generation
        transfer(null) { api, current ->
            val file = if (context != null && uri != null) withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "backup.memoh.zip"
                val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                    while (true) { val count = input.read(buffer); if (count < 0) break; require(output.size() + count <= 64 * 1024 * 1024) { "备份文件超过 64 MB" }; output.write(buffer, 0, count) }
                    output.toByteArray()
                }
                name to bytes
            } else requireNotNull(existing) { "请先选择备份文件" }.let { it.name to it.bytes }
            val preview = api.importBotBackup(file.first, file.second, mode, current.botId.takeIf(String::isNotBlank), passphrase, preview = true).obj()
            if (generation == repository.generation) _state.update { it.copy(backupImport = BackupImport(file.first, file.second, mode, passphrase, preview)) }
        }
    }

    fun importBackup(sections: JsonObject, onImported: (String) -> Unit) {
        val draft = state.value.backupImport ?: return
        if (draft.mode == "overwrite" && !state.value.canManageBot) return
        val generation = repository.generation
        transfer("备份已恢复") { api, current ->
            val result = api.importBotBackup(draft.name, draft.bytes, draft.mode, current.botId.takeIf(String::isNotBlank), draft.passphrase, sections).obj()
            if (generation != repository.generation) return@transfer
            _state.update { it.copy(backupImport = null, result = result) }
            result.text("bot_id").takeIf(String::isNotBlank)?.let(onImported)
        }
    }

    fun clearBackup() { if (!state.value.busy) _state.update { it.copy(backupImport = null, error = null) } }
    fun showResult(value: JsonElement) { _state.update { it.copy(result = value) } }
    fun runtimeCommand(runtime: JsonObject): String {
        val origin = repository.state.value.account?.let { repository.endpointFor(it).memoh("").trimEnd('/') }.orEmpty()
        return runtimeConnectCommand(origin, runtime.text("key"), runtime.text("team_id"))
    }
}

internal fun errorText(error: Exception): String {
    if (error is ApiException) {
        val detail = runCatching { Json.parseToJsonElement(error.body.orEmpty()).obj().let { it.text("detail").ifBlank { it.text("message") } } }.getOrDefault("")
        return when (error.status) {
            403 -> "当前账号没有此功能的管理权限"
            404 -> "当前服务未提供此功能"
            else -> detail.ifBlank { "请求失败（${error.status}）" }
        }
    }
    return error.message ?: "连接失败，请检查网络后重试"
}

internal fun runtimeConnectCommand(origin: String, key: String, team: String): String {
    fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    require(key.isNotBlank()) { "服务端未返回接入凭据" }
    return "npm install -g @memohai/runtime@latest && memoh-runtime enroll --server ${quote(origin)} --key ${quote(key)}" +
        (if (team.isBlank()) "" else " --team-id ${quote(team)}") + " && memoh-runtime service install && memoh-runtime service start"
}
