package dev.memoh.core.network

import dev.memoh.core.model.MemohError
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.coroutines.flow.Flow
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MultipartBody

/** Management responses retain fields which a form does not edit. */
fun managementPath(vararg segments: String): String =
    "https://memoh.invalid".toHttpUrl().newBuilder().apply {
        segments.forEach { addPathSegment(it) }
    }.build().encodedPath

suspend fun MemohApi.managementRead(vararg segments: String): JsonElement =
    call(managementPath(*segments), "GET", null, deserializer = JsonElement.serializer())

suspend fun MemohApi.botChannelConfigs(botId: String, channelTypes: List<String>): JsonObject {
    val batch = try {
        managementRead("bots", botId, "channels")
    } catch (e: ApiException) {
        if (e.status != 404 || e.problem.code !in setOf(null, "http.not_found")) throw e
        return JsonObject(buildMap {
            for (type in channelTypes) {
                try { put(type, managementRead("bots", botId, "channel", type)) }
                catch (e: ApiException) { if (e.status != 404) throw e }
            }
        })
    }
    val items = (batch as? JsonObject)?.get("items") as? JsonArray
        ?: throw UserFacingException("无法读取渠道配置，请刷新后重试")
    return JsonObject(items.associate { item ->
        val config = item as? JsonObject
        val type = (config?.get("channel_type") as? JsonPrimitive)?.contentOrNull
        if (config == null || type.isNullOrBlank()) throw UserFacingException("无法读取渠道配置，请刷新后重试")
        type to config
    })
}

suspend fun MemohApi.managementResult(path: List<String>, method: String = "POST", body: JsonObject? = null): JsonElement =
    call(managementPath(*path.toTypedArray()), method, body?.toString(), deserializer = JsonElement.serializer())

data class ModelConnectionProbe(
    val status: String?,
    val accepted: Boolean,
    val latencyMillis: Long?,
    val problem: MemohError,
) {
    val rejected: Boolean get() = status == "error" || status == "auth_error"
}

data class ProviderModelImportResult(
    val created: Int?,
    val updated: Int?,
    val skipped: Int?,
    val staleCatalog: Boolean,
    val details: JsonObject,
)

suspend fun MemohApi.modelConnectionProbe(kind: String, id: String): ModelConnectionProbe {
    val data = managementResult(listOf(kind, id, "test")) as? JsonObject ?: JsonObject(emptyMap())
    val status = (data["status"] as? JsonPrimitive)?.contentOrNull
    return ModelConnectionProbe(
        status = status,
        accepted = status == "ok" || (data["success"] as? JsonPrimitive)?.booleanOrNull == true ||
            (data["ok"] as? JsonPrimitive)?.booleanOrNull == true,
        latencyMillis = (data["latency_ms"] as? JsonPrimitive)?.longOrNull,
        problem = MemohError.parse(data),
    )
}

suspend fun MemohApi.importProviderModels(path: List<String>): ProviderModelImportResult {
    val data = managementResult(path, body = apiBody()) as? JsonObject ?: JsonObject(emptyMap())
    val catalog = data["catalog"] as? JsonObject
    return ProviderModelImportResult(
        created = (data["created"] as? JsonPrimitive)?.intOrNull,
        updated = (data["updated"] as? JsonPrimitive)?.intOrNull,
        skipped = (data["skipped"] as? JsonPrimitive)?.intOrNull,
        staleCatalog = (catalog?.get("stale") as? JsonPrimitive)?.booleanOrNull == true,
        details = data,
    )
}

suspend fun MemohApi.managementWrite(path: List<String>, method: String, body: JsonObject? = null) {
    call(managementPath(*path.toTypedArray()), method, body?.toString(), deserializer = Unit.serializer())
}

fun MemohApi.managementStream(path: List<String>, body: JsonObject = JsonObject(emptyMap())): Flow<JsonElement> =
    sse(managementPath(*path.toTypedArray()), JsonElement.serializer(), body, "POST")

suspend fun MemohApi.testSpeech(id: String, text: String, config: JsonObject): Pair<String, ByteArray> =
    binaryPost(managementPath("speech-models", id, "test"), apiBody("text" to text, "config" to config).toString().toRequestBody("application/json".toMediaType()))

suspend fun MemohApi.testTranscription(id: String, name: String, mime: String, bytes: ByteArray, config: JsonObject): JsonElement =
    multipart(managementPath("transcription-models", id, "test"), MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("config", config.toString()).addFormDataPart("file", name, bytes.toRequestBody(mime.toMediaType())).build())

suspend fun MemohApi.exportBotBackup(botId: String, sections: List<String>, passphrase: String): ByteArray =
    binaryPost(managementPath("bots", botId, "backup", "export"), apiBody("sections" to sections, "passphrase" to passphrase.takeIf(String::isNotBlank))
        .toString().toRequestBody("application/json".toMediaType()), 64 * 1024 * 1024L).second

suspend fun MemohApi.deleteWorkspace(botId: String, preserveData: Boolean) {
    call(managementPath("bots", botId, "container") + "?preserve_data=$preserveData", "DELETE", null, deserializer = Unit.serializer())
}

suspend fun MemohApi.cancelWorkspaceRemoval(botId: String) =
    managementWrite(listOf("bots", botId, "container", "removal", "cancel"), "POST")

suspend fun MemohApi.importBotBackup(name: String, bytes: ByteArray, mode: String, botId: String?, passphrase: String,
    sections: JsonObject? = null, preview: Boolean = false): JsonElement = multipart(
        managementPath(*if (preview) arrayOf("bots", "backup", "import", "preview") else arrayOf("bots", "backup", "import")),
        MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", name, bytes.toRequestBody("application/zip".toMediaType()))
            .addFormDataPart("mode", mode).apply {
                if (mode == "overwrite" && !botId.isNullOrBlank()) addFormDataPart("target_bot_id", botId)
                if (passphrase.isNotBlank()) addFormDataPart("passphrase", passphrase)
                if (sections != null) addFormDataPart("sections", sections.toString())
            }.build())
