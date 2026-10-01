package dev.memoh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Bots, sessions, and the supporting catalogs. Field sets come from
 * `spec/swagger.json` (`bots.Bot`, `session.Session`, `workdir.Workdir`, ...).
 */

@Serializable
data class Bot(
    val id: String,
    val name: String = "",
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("is_active") val isActive: Boolean = true,
    val status: String? = null,
    val timezone: String? = null,
    val metadata: JsonObject? = null,
    @SerialName("check_state") val checkState: String? = null,
    @SerialName("check_issue_count") val checkIssueCount: Int? = null,
    @SerialName("owner_user_id") val ownerUserId: String? = null,
    /**
     * Permissions the current user holds on this bot. The UI hides management
     * affordances when the relevant permission is absent.
     */
    @SerialName("current_user_permissions") val currentUserPermissions: List<String>? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    val permissions: List<String> get() = currentUserPermissions.orEmpty()
    fun can(permission: String): Boolean = permission in permissions
}

@Serializable
data class Session(
    val id: String,
    @SerialName("bot_id") val botId: String? = null,
    val title: String? = null,
    val type: String? = null,
    @SerialName("session_mode") val sessionMode: String? = null,
    /** "model" | "acp_agent" | ... — decides how model switching is applied. */
    @SerialName("runtime_type") val runtimeType: String? = null,
    /** "local" for app-created sessions; anything else is an external channel. */
    @SerialName("channel_type") val channelType: String? = null,
    @SerialName("workdir_id") val workdirId: String? = null,
    @SerialName("bot_agent_id") val botAgentId: String? = null,
    @SerialName("preferred_chat_model_id") val preferredChatModelId: String? = null,
    @SerialName("preferred_reasoning_effort") val preferredReasoningEffort: String? = null,
    @SerialName("model_preference_revision") val modelPreferenceRevision: String? = null,
    val metadata: JsonObject? = null,
    @SerialName("runtime_metadata") val runtimeMetadata: JsonObject? = null,
    @SerialName("parent_session_id") val parentSessionId: String? = null,
    @SerialName("route_conversation_type") val routeConversationType: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** External-channel sessions are read-only: the server rejects writes. */
    val isLocal: Boolean get() = channelType == null || channelType == "local"

    val isAcpRuntime: Boolean get() = runtimeType == "acp_agent"

    companion object {
        const val TYPE_CHAT = "chat"
        const val TYPE_DISCUSS = "discuss"
        const val TYPE_ACP_AGENT = "acp_agent"

        /** The session types the chat UI lists; excludes server-side schedule runs. */
        const val CHAT_TYPES = "chat,discuss,acp_agent"
    }
}

@Serializable
data class ListResponse<T>(
    val items: List<T> = emptyList(),
    @SerialName("next_cursor") val nextCursor: String? = null,
)

@Serializable
data class Workdir(
    val id: String,
    @SerialName("bot_id") val botId: String? = null,
    val name: String = "",
    val path: String? = null,
    val archived: Boolean? = null,
    @SerialName("target_kind") val targetKind: String? = null,
    @SerialName("workspace_target_id") val workspaceTargetId: String? = null,
    @SerialName("created_by_user_id") val createdByUserId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
) {
    /** Archived folders stay listed but sort last and read as muted. */
    val isActive: Boolean get() = archived != true
}

/**
 * The folder list for a bot.
 *
 * Note the envelope: this endpoint answers `{"workdirs": [...]}`, not the
 * `{"items": [...]}` every other list endpoint uses. Decoding it as
 * `ListResponse` yields a permanently empty list with no error, so the shape is
 * spelled out here rather than reused.
 */
@Serializable
data class WorkdirsResponse(
    val workdirs: List<Workdir> = emptyList(),
)

@Serializable
data class WorkspaceTarget(
    @SerialName("target_id") val targetId: String,
    val kind: String? = null,
    val name: String? = null,
    val online: Boolean? = null,
    val primary: Boolean? = null,
    @SerialName("runtime_id") val runtimeId: String? = null,
    val status: String? = null,
)

@Serializable
data class WorkspaceTargetsResponse(
    val targets: List<WorkspaceTarget> = emptyList(),
)

@Serializable
data class BotAgent(
    val id: String,
    @SerialName("bot_id") val botId: String? = null,
    val name: String = "",
    /** The runtime host this agent runs on (native Pi, Codex, Claude Code, ...). */
    val runtime: String? = null,
    val enabled: Boolean? = null,
    val metadata: JsonObject? = null,
    @SerialName("agent_credential_id") val agentCredentialId: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

// ---------------------------------------------------------------------------
// Models & reasoning effort
// ---------------------------------------------------------------------------

@Serializable
data class ChatModel(
    val id: String,
    @SerialName("model_id") val modelId: String? = null,
    /** "chat" | "embedding" | ... — only `chat` models are offered in the composer. */
    val type: String? = null,
    val enable: Boolean? = null,
    val name: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("provider_id") val providerId: String? = null,
    @SerialName("provider_name") val providerName: String? = null,
    val capabilities: ModelCapabilities? = null,
    val reasoning: ReasoningOptions? = null,
    @SerialName("default_reasoning_effort") val defaultReasoningEffort: String? = null,
    val config: ChatModelConfig? = null,
) {
    val isSelectable: Boolean get() = type == "chat" && enable != false
    val label: String get() = displayName?.takeIf(String::isNotBlank) ?: name.orEmpty().ifBlank { id }
    val efforts: List<String> get() = reasoning?.selectableEfforts
        ?: capabilities?.takeUnless { it.supportsReasoning == false }?.efforts.orEmpty()

    /** Use the server's resolved default; the first tier may mean thinking is off. */
    fun resolveEffort(stored: String?): String? {
        reasoning?.let { options ->
            if (!options.supported) return null
            val normalized = if (stored == "none") "disable" else stored
            if (!options.canDisable && options.efforts.isNullOrEmpty()) return normalized?.takeIf(String::isNotBlank)
            return normalized?.takeIf { it in options.selectableEfforts }
                ?: options.defaultEffort?.takeIf(String::isNotBlank)
        }
        return stored?.takeIf { it in efforts } ?: defaultReasoningEffort?.takeIf { it in efforts }
            ?: efforts.firstOrNull()
    }
}

@Serializable
data class ChatModelConfig(@SerialName("context_window") val contextWindow: Long? = null)

@Serializable
data class ReasoningOptions(
    val supported: Boolean = false,
    @SerialName("can_disable") val canDisable: Boolean = false,
    @SerialName("default_effort") val defaultEffort: String? = null,
    val efforts: List<String>? = null,
    @SerialName("efforts_without_off") val effortsWithoutOff: List<String>? = null,
) {
    val selectableEfforts: List<String> get() = if (!supported) emptyList()
        else ((if (canDisable) listOf("disable") else emptyList()) + efforts.orEmpty()).distinct()
}

fun reasoningEffortLabel(value: String): String = when (value) {
    "disable", "none" -> "关闭"
    "adaptive" -> "自适应"
    "minimal" -> "最小"
    "low" -> "低"
    "medium" -> "中"
    "high" -> "高"
    "xhigh" -> "极高"
    "max" -> "最大"
    else -> value
}

@Serializable
data class ModelProvider(val id: String, val name: String = "")

@Serializable
data class AgentModelCatalog(
    val models: List<AgentModelOption> = emptyList(),
    @SerialName("configured_model_id") val configuredModelId: String? = null,
    @SerialName("configured_reasoning_effort") val configuredReasoningEffort: String? = null,
) {
    val pickerModels: List<ChatModel> get() = models.filter { it.id.isNotBlank() }.map {
        ChatModel(id = it.id, modelId = it.resolvedModelId, type = "chat", name = it.name,
            defaultReasoningEffort = it.defaultReasoningEffort,
            capabilities = ModelCapabilities(availableEfforts = it.reasoningEfforts.orEmpty().map { effort -> effort.id }))
    }
}

@Serializable
data class AgentModelOption(
    val id: String,
    val name: String? = null,
    val default: Boolean = false,
    @SerialName("resolved_model_id") val resolvedModelId: String? = null,
    @SerialName("default_reasoning_effort") val defaultReasoningEffort: String? = null,
    @SerialName("reasoning_efforts") val reasoningEfforts: List<AgentReasoningEffort>? = null,
)

@Serializable
data class AgentReasoningEffort(val id: String, val name: String? = null)

@Serializable
data class ModelCapabilities(
    /** Effort levels this model accepts, in the server's order. */
    @SerialName("reasoning_efforts") val reasoningEfforts: List<String>? = null,
    @SerialName("available_efforts") val availableEfforts: List<String>? = null,
    @SerialName("supports_reasoning") val supportsReasoning: Boolean? = null,
) {
    /** Accepts either spelling the server uses across its two catalogs. */
    val efforts: List<String> get() = reasoningEfforts ?: availableEfforts.orEmpty()
}

// ---------------------------------------------------------------------------
// Session queue
// ---------------------------------------------------------------------------

@Serializable
data class QueueItem(
    @SerialName("item_id") val itemId: String,
    val status: String? = null,
    val text: String = "",
    val position: Int? = null,
    @SerialName("target_run_id") val targetRunId: String? = null,
    @SerialName("enqueued_during_run_id") val enqueuedDuringRunId: String? = null,
)

@Serializable
data class SessionQueueResponse(
    @SerialName("steer_supported") val steerSupported: Boolean = false,
    val steer: List<QueueItem> = emptyList(),
    @SerialName("follow_up") val followUp: List<QueueItem> = emptyList(),
)

// ---------------------------------------------------------------------------
// Auth & server info
// ---------------------------------------------------------------------------

@Serializable
data class LoginResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    /** ISO timestamp; drives proactive refresh. */
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("user_id") val userId: String? = null,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val role: String? = null,
    val timezone: String? = null,
)

@Serializable
data class RefreshResponse(
    @SerialName("access_token") val accessToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_at") val expiresAt: String? = null,
)

@Serializable
data class LoginRequest(
    /** Matched against username *or* email by the server. */
    val username: String,
    val password: String,
)

@Serializable
data class PingResponse(
    val status: String? = null,
    val version: String? = null,
    @SerialName("commit_hash") val commitHash: String? = null,
    @SerialName("container_backend") val containerBackend: String? = null,
    val connectors: Boolean? = null,
    @SerialName("snapshot_supported") val snapshotSupported: Boolean? = null,
) {
    val isOk: Boolean get() = status == "ok"
}

@Serializable
data class CurrentUser(
    @SerialName("user_id") val userId: String? = null,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    val role: String? = null,
    val timezone: String? = null,
)

// ---------------------------------------------------------------------------
// Official Cloud (platform) auth
// ---------------------------------------------------------------------------

@Serializable
data class EmailCodeSendRequest(
    val email: String,
    @SerialName("preferred_locale") val preferredLocale: String? = null,
)

@Serializable
data class EmailCodeSendResponse(
    /** Seconds before the user may request another code. */
    @SerialName("resend_after") val resendAfter: Int? = null,
)

@Serializable
data class EmailCodeVerifyRequest(
    val email: String,
    val code: String,
)

@Serializable
data class EmailCodeVerifyResponse(
    @SerialName("mfa_required") val mfaRequired: Boolean? = null,
    @SerialName("mfa_token") val mfaToken: String? = null,
)

@Serializable
data class VerifyMfaRequest(
    @SerialName("mfa_token") val mfaToken: String,
    @SerialName("totp_code") val totpCode: String,
)

@Serializable
data class TeamMembership(
    val team: Team? = null,
    val role: String? = null,
)

@Serializable
data class Team(
    @SerialName("team_id") val teamId: String,
    val name: String = "",
    val slug: String? = null,
)

@Serializable
data class TeamsResponse(
    val teams: List<TeamMembership> = emptyList(),
)

@Serializable
data class WsTicketResponse(
    val ticket: String,
)
