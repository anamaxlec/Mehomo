package dev.memoh.feature.bots

import dev.memoh.core.model.ChatModel
import kotlinx.serialization.json.*

private val modelJson = Json { ignoreUnknownKeys = true; explicitNulls = false }
internal fun managementChatModel(value: JsonObject): ChatModel? =
    runCatching { modelJson.decodeFromJsonElement(ChatModel.serializer(), value) }.getOrNull()

internal val modelConfigFields = setOf("context_window", "max_output_tokens", "thinking_mode", "reasoning_efforts", "compatibilities", "description")

internal fun modelFormBody(body: JsonObject): JsonObject {
    val config = (body["config"].obj() + body.filterKeys { it in modelConfigFields && body[it] != JsonNull }).toMutableMap()
    modelConfigFields.filter { body[it] == JsonNull }.forEach(config::remove)
    if (body.text("type") == "embedding") {
        (modelConfigFields - "description").forEach(config::remove)
    } else {
        val compatibilities = (config["compatibilities"] as? JsonArray).orEmpty()
        if (JsonPrimitive("reasoning") !in compatibilities) {
            config.remove("thinking_mode"); config.remove("reasoning_efforts")
        } else {
            if ((config["thinking_mode"] as? JsonPrimitive)?.content.isNullOrBlank()) config.remove("thinking_mode")
            if (config["thinking_mode"] == JsonPrimitive("none") || config["thinking_mode"] == JsonPrimitive("always")) config.remove("reasoning_efforts")
            else {
                val efforts = (config["reasoning_efforts"] as? JsonArray).orEmpty().map { if (it == JsonPrimitive("none")) JsonPrimitive("disable") else it }
                    .filterNot { it == JsonPrimitive("adaptive") }.distinct()
                if (efforts.isEmpty()) config.remove("reasoning_efforts") else config["reasoning_efforts"] = JsonArray(efforts)
            }
        }
    }
    return JsonObject(body.filterKeys { it !in modelConfigFields && it != "config" } + ("config" to JsonObject(config)))
}
