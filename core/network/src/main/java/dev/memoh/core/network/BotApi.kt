package dev.memoh.core.network

import dev.memoh.core.model.*
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import kotlinx.serialization.serializer
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.io.IOException
import java.util.concurrent.TimeUnit

private fun botPath(botId: String) = "/bots/${segment(botId)}"
private fun sessionPath(botId: String, sessionId: String) = "${botPath(botId)}/sessions/${segment(sessionId)}"
private fun segment(value: String) = "https://localhost/".toHttpUrl().newBuilder().addPathSegment(value).build().encodedPath.substring(1)
private fun query(vararg values: Pair<String, String?>): String = "https://localhost/".toHttpUrl().newBuilder()
    .apply { values.forEach { (key, value) -> if (value != null) addQueryParameter(key, value) } }
    .build().encodedQuery?.let { "?$it" }.orEmpty()

fun apiBody(vararg values: Pair<String, Any?>): JsonObject = buildJsonObject {
    values.forEach { (key, value) ->
        when (value) {
            null -> Unit
            is JsonElement -> put(key, value)
            is Boolean -> put(key, value)
            is Number -> put(key, value)
            is String -> put(key, value)
            is List<*> -> put(key, JsonArray(value.map { JsonPrimitive(it?.toString()) }))
            else -> error("Unsupported JSON value")
        }
    }
}

suspend fun MemohApi.exportMcp(botId: String): JsonElement = managementRead("bots", botId, "mcp-ops", "export")
suspend fun MemohApi.importMcp(botId: String, config: JsonObject) = managementWrite(listOf("bots", botId, "mcp-ops", "import"), "PUT", config)

private suspend inline fun <reified T> MemohApi.get(path: String): T = call(path, "GET", null, deserializer = serializer<T>())
private suspend inline fun <reified T> MemohApi.change(path: String, method: String, body: JsonObject = apiBody()): T =
    call(path, method, body.toString(), deserializer = serializer<T>())

suspend fun MemohApi.session(botId: String, sessionId: String): Session = get(sessionPath(botId, sessionId))
suspend fun MemohApi.updateSession(botId: String, sessionId: String, body: JsonObject): Session = change(sessionPath(botId, sessionId), "PATCH", body)
suspend fun MemohApi.newSession(botId: String, body: JsonObject): Session = change("${botPath(botId)}/sessions", "POST", body)
suspend fun MemohApi.forkSession(botId: String, sessionId: String, turnId: String? = null): Session =
    change("${sessionPath(botId, sessionId)}/fork", "POST", apiBody("turn_id" to turnId))
suspend fun MemohApi.compactSession(botId: String, sessionId: String): JsonElement = change("${sessionPath(botId, sessionId)}/compact", "POST")

suspend fun MemohApi.memories(botId: String): MemorySearchResponse = get("${botPath(botId)}/memory")
suspend fun MemohApi.searchMemories(botId: String, text: String): MemorySearchResponse = change("${botPath(botId)}/memory/search", "POST", apiBody("query" to text, "limit" to 50))
suspend fun MemohApi.addMemory(botId: String, text: String): MemorySearchResponse = change("${botPath(botId)}/memory", "POST", apiBody("message" to text))
suspend fun MemohApi.editMemory(botId: String, id: String, text: String): JsonElement = change("${botPath(botId)}/memory/${segment(id)}", "PUT", apiBody("memory" to text))
suspend fun MemohApi.deleteMemory(botId: String, id: String): Unit = change("${botPath(botId)}/memory/${segment(id)}", "DELETE")
suspend fun MemohApi.memoryStatus(botId: String): MemoryStatus = get("${botPath(botId)}/memory/status")
suspend fun MemohApi.memoryGraph(botId: String): MemoryGraph = get("${botPath(botId)}/memory/graph")
suspend fun MemohApi.compactMemory(botId: String): JsonElement = change("${botPath(botId)}/memory/compact", "POST")

suspend fun MemohApi.schedules(botId: String): List<BotSchedule> = get<ScheduleList>("${botPath(botId)}/schedule").items.orEmpty()
suspend fun MemohApi.saveSchedule(botId: String, id: String?, body: JsonObject): BotSchedule =
    change("${botPath(botId)}/schedule${id?.let { "/${segment(it)}" }.orEmpty()}", if (id == null) "POST" else "PUT", body)
suspend fun MemohApi.deleteSchedule(botId: String, id: String): Unit = change("${botPath(botId)}/schedule/${segment(id)}", "DELETE")
suspend fun MemohApi.scheduleLogs(botId: String, id: String): ScheduleLogs = get("${botPath(botId)}/schedule/${segment(id)}/logs")
suspend fun MemohApi.tokenUsage(botId: String, from: String, to: String): TokenUsageSummary =
    get("${botPath(botId)}/token-usage${query("from" to from, "to" to to)}")
suspend fun MemohApi.tokenRecords(botId: String, from: String, to: String, offset: Int = 0): TokenUsageRecords =
    get("${botPath(botId)}/token-usage/records${query("from" to from, "to" to to, "limit" to "50", "offset" to offset.toString())}")
suspend fun MemohApi.containerMetrics(botId: String): ContainerMetrics = get("${botPath(botId)}/container/metrics")

suspend fun MemohApi.installedApps(botId: String): InstalledApps = get("${botPath(botId)}/apps")
suspend fun MemohApi.marketApps(page: Int = 1, text: String = ""): MarketApps = get("/supermarket/apps${query("page" to page.toString(), "limit" to "30", "q" to text.takeIf(String::isNotBlank))}")
suspend fun MemohApi.marketSkills(page: Int = 1, text: String = ""): MarketSkills = get("/supermarket/skills${query("page" to page.toString(), "limit" to "30", "q" to text.takeIf(String::isNotBlank))}")
suspend fun MemohApi.marketApp(registryId: String, appId: String): JsonObject = get("/supermarket/registries/${segment(registryId)}/apps/${segment(appId)}")
suspend fun MemohApi.checkAppUpdates(botId: String): JsonElement = change("${botPath(botId)}/apps/check-updates", "POST")
suspend fun MemohApi.appRemovalPreview(botId: String, id: String): JsonObject = get("${botPath(botId)}/apps/${segment(id)}/removal-preview")
suspend fun MemohApi.skills(botId: String): List<InstalledSkill> = get<SkillsList>("${botPath(botId)}/container/skills").skills.orEmpty()
suspend fun MemohApi.saveSkill(botId: String, raw: String, sourcePath: String? = null): Unit =
    change("${botPath(botId)}/container/skills", "POST", apiBody("skills" to listOf(raw), "source_path" to sourcePath))
suspend fun MemohApi.deleteSkill(botId: String, sourcePath: String): Unit = change("${botPath(botId)}/container/skills", "DELETE", apiBody("source_paths" to listOf(sourcePath)))
suspend fun MemohApi.skillAction(botId: String, action: String, path: String): Unit = change("${botPath(botId)}/container/skills/actions", "POST", apiBody("action" to action, "target_path" to path))

suspend fun MemohApi.mcpConnections(botId: String): List<McpConnection> = get<McpConnections>("${botPath(botId)}/mcp").items.orEmpty()
suspend fun MemohApi.saveMcp(botId: String, id: String?, body: JsonObject): McpConnection = change("${botPath(botId)}/mcp${id?.let { "/${segment(it)}" }.orEmpty()}", if (id == null) "POST" else "PUT", body)
suspend fun MemohApi.deleteMcp(botId: String, id: String): Unit = change("${botPath(botId)}/mcp/${segment(id)}", "DELETE")
suspend fun MemohApi.probeMcp(botId: String, id: String): JsonObject = change("${botPath(botId)}/mcp/${segment(id)}/probe", "POST")

suspend fun MemohApi.enqueue(botId: String, sessionId: String, text: String, steer: Boolean, invocationId: String): JsonElement =
    change("${sessionPath(botId, sessionId)}/${if (steer) "steer" else "follow-up"}-queue", "POST", apiBody("text" to text, "invocation_id" to invocationId))
suspend fun MemohApi.editQueueItem(botId: String, sessionId: String, id: String, text: String, steer: Boolean): Unit =
    change("${sessionPath(botId, sessionId)}/${if (steer) "steer" else "follow-up"}-queue/${segment(id)}", "PATCH", apiBody("text" to text))
suspend fun MemohApi.removeQueueItem(botId: String, sessionId: String, id: String, steer: Boolean): Unit =
    change("${sessionPath(botId, sessionId)}/${if (steer) "steer" else "follow-up"}-queue/${segment(id)}", "DELETE")
suspend fun MemohApi.reorderQueue(botId: String, sessionId: String, itemId: String, beforeId: String?, steer: Boolean): Unit =
    change("${sessionPath(botId, sessionId)}/${if (steer) "steer" else "follow-up"}-queue/reorder", "PUT",
        apiBody("item" to apiBody("item_id" to itemId), "before" to apiBody("item_id" to beforeId)))
suspend fun MemohApi.promoteQueueItem(botId: String, sessionId: String, id: String): Unit = change("${sessionPath(botId, sessionId)}/follow-up-queue/${segment(id)}/steer", "POST")
suspend fun MemohApi.runtimeControls(botId: String, sessionId: String): RuntimeControls = get("${sessionPath(botId, sessionId)}/runtime-controls")
suspend fun MemohApi.sessionInfo(botId: String, sessionId: String, modelId: String? = null): SessionInfo =
    get("${sessionPath(botId, sessionId)}/status${query("model_id" to modelId?.takeIf(String::isNotBlank))}")
suspend fun MemohApi.files(botId: String, path: String = "/data"): WorkspaceListing =
    get("${botPath(botId)}/container/fs/list${query("path" to path)}")
suspend fun MemohApi.readFile(botId: String, path: String): WorkspaceDocument =
    get("${botPath(botId)}/container/fs/read${query("path" to path)}")
suspend fun MemohApi.writeFile(botId: String, path: String, content: String, revision: String? = null): WorkspaceFileResult =
    change("${botPath(botId)}/container/fs/write", "POST", apiBody("path" to path, "content" to content, "expectedRevision" to revision?.takeIf(String::isNotBlank)))
suspend fun MemohApi.makeDirectory(botId: String, path: String): Unit =
    change("${botPath(botId)}/container/fs/mkdir", "POST", apiBody("path" to path))
suspend fun MemohApi.renameFile(botId: String, oldPath: String, newPath: String): Unit =
    change("${botPath(botId)}/container/fs/rename", "POST", apiBody("oldPath" to oldPath, "newPath" to newPath))
suspend fun MemohApi.deleteFile(botId: String, path: String, recursive: Boolean = false): Unit =
    change("${botPath(botId)}/container/fs/delete", "POST", apiBody("path" to path, "recursive" to recursive))
suspend fun MemohApi.uploadFile(botId: String, path: String, name: String, mime: String, bytes: ByteArray): WorkspaceFileResult =
    json.decodeFromJsonElement(WorkspaceFileResult.serializer(), multipart("${botPath(botId)}/container/fs/upload",
        okhttp3.MultipartBody.Builder().setType(okhttp3.MultipartBody.FORM).addFormDataPart("path", path)
            .addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaType())).build()))
fun workspaceDownloadPath(botId: String, path: String): String = "${botPath(botId)}/container/fs/download${query("path" to path)}"
suspend fun MemohApi.runtimeGoal(botId: String, sessionId: String): JsonObject = get("${sessionPath(botId, sessionId)}/runtime-controls/goal")
suspend fun MemohApi.setRuntimeMode(botId: String, sessionId: String, mode: String, kind: String = "permission"): JsonElement = change("${sessionPath(botId, sessionId)}/runtime-controls/mode", "PATCH", apiBody("mode_id" to mode, "mode_kind" to kind))
suspend fun MemohApi.controlRuntimeGoal(botId: String, sessionId: String, action: String): Unit = change("${sessionPath(botId, sessionId)}/runtime-controls/goal", "POST", apiBody("action" to action))
suspend fun MemohApi.runtimeCommand(botId: String, sessionId: String, body: JsonObject): JsonElement = change("${sessionPath(botId, sessionId)}/runtime-controls/commands", "POST", body)
suspend fun MemohApi.quickAction(botId: String, body: JsonObject): JsonElement = change("${botPath(botId)}/quick-actions/execute", "POST", body)
suspend fun MemohApi.acpRuntime(botId: String, sessionId: String): AcpRuntimeStatus = get("${sessionPath(botId, sessionId)}/acp-runtime")
suspend fun MemohApi.setAcpModel(botId: String, sessionId: String, modelId: String): AcpRuntimeStatus = change("${sessionPath(botId, sessionId)}/acp-runtime/model", "PATCH", apiBody("model_id" to modelId))
suspend fun MemohApi.setAcpReasoning(botId: String, sessionId: String, effort: String?): AcpRuntimeStatus = change("${sessionPath(botId, sessionId)}/acp-runtime/reasoning", "PATCH", apiBody("reasoning_effort" to (effort ?: "")))
suspend fun MemohApi.approveTool(botId: String, id: String, body: JsonObject = apiBody()): Unit = change("${botPath(botId)}/tool-approvals/${segment(id)}/approve", "POST", body)
suspend fun MemohApi.rejectTool(botId: String, id: String, reason: String?): Unit = change("${botPath(botId)}/tool-approvals/${segment(id)}/reject", "POST", apiBody("reason" to reason))

fun MemohApi.sessionActivity(botId: String): Flow<SessionActivityEvent> = sse("${botPath(botId)}/sessions/events", SessionActivityEvent.serializer())
fun MemohApi.installApp(botId: String, registryId: String, appId: String, revision: String): Flow<AppInstallEvent> =
    sse("${botPath(botId)}/apps", AppInstallEvent.serializer(), apiBody("registry_id" to registryId, "app_id" to appId, "revision" to revision), "POST")
fun MemohApi.resumeApp(botId: String, id: String): Flow<AppInstallEvent> = sse("${botPath(botId)}/apps/${segment(id)}/resume", AppInstallEvent.serializer(), apiBody(), "POST")
fun MemohApi.updateApp(botId: String, registryId: String, appId: String): Flow<AppInstallEvent> = sse("${botPath(botId)}/apps/update", AppInstallEvent.serializer(), apiBody("registry_id" to registryId, "app_id" to appId, "release" to true), "POST")
fun MemohApi.removeApp(botId: String, id: String): Flow<AppInstallEvent> = sse("${botPath(botId)}/apps/${segment(id)}", AppInstallEvent.serializer(), apiBody(), "DELETE")

internal fun <T> MemohApi.sse(path: String, serializer: KSerializer<T>, body: JsonObject? = null, method: String = "GET"): Flow<T> = callbackFlow {
    val call = networkClient.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()
        .newCall(streamRequest(path, body?.toString(), method))
    call.enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!call.isCanceled()) close(e) }
        override fun onResponse(call: Call, response: Response) {
            response.use {
                if (!it.isSuccessful) {
                    if (it.code == 401) streamUnauthorized()
                    close(ApiException(it.code, null, "连接失败（HTTP ${it.code}）")); return
                }
                try {
                    val reader = it.body.source()
                    val frame = StringBuilder()
                    fun emitFrame() {
                        if (frame.isNotEmpty()) {
                            // Reading happens on OkHttp's worker. Backpressure keeps long
                            // install logs from losing a terminal error or done event.
                            if (trySendBlocking(json.decodeFromString(serializer, frame.toString())).isFailure) return
                            frame.clear()
                        }
                    }
                    while (!call.isCanceled()) {
                        val line = reader.readUtf8Line() ?: break
                        if (line.isEmpty()) emitFrame()
                        else if (line.startsWith("data:")) {
                            if (frame.isNotEmpty()) frame.append('\n')
                            frame.append(line.removePrefix("data:").removePrefix(" "))
                        }
                    }
                    emitFrame(); close()
                } catch (e: Exception) { if (!call.isCanceled()) close(e) }
            }
        }
    })
    awaitClose { call.cancel() }
}
