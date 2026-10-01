package dev.memoh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class WorkspaceSurface(val title: String) { Terminal("终端"), Browser("浏览器"), Desktop("桌面") }

@Serializable data class TerminalInfo(val available: Boolean = false, val shell: String = "")
@Serializable data class BrowserSession(val id: String, val url: String,
    @SerialName("expires_at") val expiresAt: String? = null)
@Serializable data class DisplayInfo(
    val enabled: Boolean = false, val available: Boolean = false, val running: Boolean = false,
    val transport: String = "", val encoder: String = "",
    @SerialName("encoder_available") val encoderAvailable: Boolean = false,
    @SerialName("desktop_available") val desktopAvailable: Boolean = false,
    @SerialName("browser_available") val browserAvailable: Boolean = false,
    @SerialName("toolkit_available") val toolkitAvailable: Boolean = false,
    @SerialName("prepare_supported") val prepareSupported: Boolean = false,
    @SerialName("prepare_system") val prepareSystem: String? = null,
    @SerialName("unavailable_reason") val unavailableReason: String? = null,
)
@Serializable data class DisplayAnswer(val type: String, val sdp: String,
    @SerialName("session_id") val sessionId: String)
@Serializable data class RuntimeDisplaySession(@SerialName("session_id") val sessionId: String, val token: String) {
    override fun toString() = "RuntimeDisplaySession(sessionId=$sessionId)"
}
@Serializable data class DisplayPrepareEvent(val type: String, val step: String? = null,
    val detail: String? = null, val message: String? = null, val percent: Int? = null)
