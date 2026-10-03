package dev.memoh.core.network

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

suspend fun MemohApi.managementResult(path: List<String>, method: String = "POST", body: JsonObject? = null): JsonElement =
    call(managementPath(*path.toTypedArray()), method, body?.toString(), deserializer = JsonElement.serializer())

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
