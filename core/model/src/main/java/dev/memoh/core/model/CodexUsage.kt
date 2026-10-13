package dev.memoh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

@Serializable
data class CodexUsage(
    @SerialName("limit_reached") val limitReached: Boolean = false,
    val windows: List<CodexUsageWindow> = emptyList(),
)

@Serializable
data class CodexUsageWindow(
    @SerialName("used_percent") val usedPercent: Double = 0.0,
    @SerialName("window_minutes") val windowMinutes: Int = 0,
    @SerialName("resets_at") val resetsAt: String? = null,
) {
    val label: String get() = when {
        windowMinutes == 0 -> "未知窗口"
        windowMinutes % 10080 == 0 -> "${windowMinutes / 10080} 周"
        windowMinutes % 1440 == 0 -> "${windowMinutes / 1440} 天"
        windowMinutes % 60 == 0 -> "${windowMinutes / 60} 小时"
        else -> "$windowMinutes 分钟"
    }
}

val BotAgent.hasCodexUsage: Boolean get() = runtime == "codex" && !agentCredentialId.isNullOrBlank() &&
    (metadata?.get("auth") as? JsonPrimitive)?.contentOrNull == "chatgpt"
