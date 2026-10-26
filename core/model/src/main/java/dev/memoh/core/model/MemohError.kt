package dev.memoh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

/** RFC 9457 Problem and stream/history failures share the same catalog. */
@Serializable
data class MemohError(
    val code: String? = null,
    val args: JsonObject? = null,
    val status: Int? = null,
    val fault: String? = null,
    @SerialName("request_id") val requestId: String? = null,
    @SerialName("trace_id") val traceId: String? = null,
) {
    val isCanceled: Boolean get() = status == 499 || fault == "canceled" || code in setOf("canceled", "runtime_control.cancelled")
    fun text(): String {
        if (isCanceled) return ""
        val template = errorMessages[code] ?: errorMessages[legacyCodes[code]] ?: errorMessages[statusCode(status)]!!
        return Regex("\\{([^}]+)\\}").replace(template) { match ->
            (args?.get(match.groupValues[1]) as? JsonPrimitive)?.contentOrNull?.take(512) ?: match.value
        }
    }
    val action: ErrorAction? get() = when (code) {
        "agent.chat_model_not_configured", "settings.reasoning_effort_invalid", "settings.reasoning_options_unavailable",
        "compaction.model_unavailable", "schedule.model_required" -> ErrorAction.BotSettings
        "agent_dependency_missing" -> ErrorAction.Dependencies
        "acp_agent_not_configured", "acp_agent_auth_invalid", "external_runtime.auth_required",
        "codex_auth_token_missing", "codex_oauth_incomplete", "agent_credential.reauthorization_required" -> ErrorAction.Agents
        "connector.oauth_client_not_configured", "connector_oauth_client_not_configured", "connector.not_configured" -> ErrorAction.Connectors
        else -> null
    }
    companion object {
        fun parse(value: JsonElement?, status: Int? = null): MemohError {
            val records = mutableListOf<JsonObject>()
            fun collect(element: JsonElement?, depth: Int) {
                if (depth > 8) return
                val record = element as? JsonObject ?: return
                records += record
                for (key in listOf("body", "data", "error", "detail", "response", "feedback", "message")) {
                    val nested = record[key]
                    collect(if (nested is JsonPrimitive && nested.isString) runCatching {
                        Json.parseToJsonElement(nested.content)
                    }.getOrNull() else nested, depth + 1)
                }
            }
            collect(value, 0)
            fun JsonObject.string(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank)
            val record = records.firstOrNull { it.string("code") != null || it.string("error_code") != null }
            return MemohError(
                code = record?.string("code") ?: record?.string("error_code"),
                args = record?.get("args") as? JsonObject,
                status = status ?: records.firstNotNullOfOrNull { (it["status"] as? JsonPrimitive)?.intOrNull ?: (it["http_status"] as? JsonPrimitive)?.intOrNull },
                fault = record?.string("fault") ?: records.firstNotNullOfOrNull { it.string("fault") },
                requestId = record?.string("request_id") ?: records.firstNotNullOfOrNull { it.string("request_id") ?: it.string("requestId") },
                traceId = record?.string("trace_id") ?: records.firstNotNullOfOrNull { it.string("trace_id") },
            )
        }
        fun parse(body: String?, status: Int? = null): MemohError =
            parse(runCatching { Json.parseToJsonElement(body.orEmpty()) }.getOrNull(), status)
    }
}

enum class ErrorAction(val title: String) {
    BotSettings("配置默认模型"), Dependencies("管理依赖"), Agents("Agent 设置"), Connectors("连接器设置"),
}

fun errorMessage(code: String?, args: Map<String, String>? = null, status: Int? = null, fault: String? = null): String =
    MemohError(code, args?.let { JsonObject(it.mapValues { (_, value) -> JsonPrimitive(value) }) }, status, fault).text()

private fun statusCode(status: Int?): String = when (status) {
    400, 422 -> "http.bad_request"
    401 -> "http.unauthorized"
    402 -> "http.payment_required"
    403 -> "http.forbidden"
    404 -> "http.not_found"
    405 -> "http.method_not_allowed"
    409 -> "http.conflict"
    413 -> "http.payload_too_large"
    415 -> "http.unsupported_media_type"
    426 -> "http.upgrade_required"
    429 -> "http.too_many_requests"
    504, 524 -> "http.gateway_timeout"
    else -> if (status != null && status in 400..499) "http.bad_request" else "internal"
}

private val legacyCodes = mapOf(
    "connector_oauth_client_not_configured" to "connector.oauth_client_not_configured",
    "session_busy" to "session_runtime.session_busy",
    "processing_failed" to "runtime_run_failed",
)
