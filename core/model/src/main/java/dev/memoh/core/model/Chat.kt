package dev.memoh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Wire models, transcribed from the official web client
 * (`apps/web/src/composables/api/useChat.types.ts`) and `spec/swagger.json`.
 *
 * Discipline for every DTO in this file:
 *  - `@SerialName` carries the server's snake_case name.
 *  - Server fields are nullable with defaults — the server adds fields and
 *    sometimes omits whole objects (a `null` `messages` after a restart is a
 *    documented case), and a strict decoder would crash on both.
 *  - No business defaults. Absent stays absent so we never send a misleading
 *    empty string back.
 */

private typealias JsonMap = Map<String, JsonElement>

// ---------------------------------------------------------------------------
// Attachments
// ---------------------------------------------------------------------------

/** Outbound attachment: bytes ride inline as a data URL. */
@Serializable
data class ChatAttachment(
    val type: String,
    /** `data:<mime>;base64,<payload>`. */
    val base64: String,
    val mime: String? = null,
    val name: String? = null,
)

/** Inbound attachment reference. Prefer `contentHash` over `url` when present. */
@Serializable
data class UIAttachment(
    val id: String? = null,
    val type: String? = null,
    val url: String? = null,
    val base64: String? = null,
    val mime: String? = null,
    val name: String? = null,
    val size: Long? = null,
    @SerialName("content_hash") val contentHash: String? = null,
    val path: String? = null,
    @SerialName("storage_key") val storageKey: String? = null,
    @SerialName("bot_id") val botId: String? = null,
    val metadata: JsonMap? = null,
)

// ---------------------------------------------------------------------------
// Interactive decisions
// ---------------------------------------------------------------------------

/** One agent-provided answer to a permission request. */
@Serializable
data class UIToolApprovalOption(
    val id: String,
    val name: String? = null,
    /** allow_once | allow_always | reject_once | reject_always | ... */
    val kind: String? = null,
)

@Serializable
data class UIToolApproval(
    @SerialName("approval_id") val approvalId: String,
    @SerialName("short_id") val shortId: Int? = null,
    val status: String,
    @SerialName("decision_reason") val decisionReason: String? = null,
    /**
     * Absent means "yes" — the official client treats an omitted flag as
     * permitted. Only an explicit `false` blocks the action.
     */
    @SerialName("can_approve") val canApprove: Boolean? = null,
    val options: List<UIToolApprovalOption>? = null,
    @SerialName("selected_option_id") val selectedOptionId: String? = null,
) {
    val isPending: Boolean get() = status == "pending"
    val isActionable: Boolean get() = isPending && canApprove != false
}

@Serializable
data class UIUserInputOption(
    @SerialName("label_key") val labelKey: String? = null,
    val id: String,
    val label: String,
    val description: String? = null,
)

@Serializable
data class UIUserInputQuestion(
    val id: String,
    val text: String,
    /** single_select | multi_select | text */
    val kind: String,
    val options: List<UIUserInputOption>? = null,
    @SerialName("allow_custom") val allowCustom: Boolean? = null,
    /** When true, a custom answer excludes picking any listed option. */
    @SerialName("custom_exclusive") val customExclusive: Boolean? = null,
    val required: Boolean? = null,
    val placeholder: String? = null,
)

/** An answer as stored on the server after a question was settled. */
@Serializable
data class UIUserInputAnswer(
    @SerialName("question_id") val questionId: String,
    val question: String? = null,
    val selected: List<UIUserInputOption>? = null,
    @SerialName("custom_text") val customText: String? = null,
    val text: String? = null,
    val skipped: Boolean? = null,
)

@Serializable
data class UIUserInput(
    @SerialName("user_input_id") val userInputId: String,
    @SerialName("short_id") val shortId: Int? = null,
    val status: String,
    val questions: List<UIUserInputQuestion>? = null,
    val answers: List<UIUserInputAnswer>? = null,
    @SerialName("can_respond") val canRespond: Boolean? = null,
) {
    val isPending: Boolean get() = status == "pending"
    val isActionable: Boolean get() = isPending && canRespond != false
}

// ---------------------------------------------------------------------------
// Messages inside a turn
// ---------------------------------------------------------------------------

@Serializable
data class UIReasoningTiming(
    @SerialName("duration_ms") val durationMs: Long = 0L,
)

@Serializable
data class UIExecutionLocation(
    val kind: String? = null,
    val name: String? = null,
)

@Serializable
data class UIBackgroundTask(
    val event: String? = null,
    @SerialName("task_id") val taskId: String? = null,
    @SerialName("bot_id") val botId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    val command: String? = null,
    @SerialName("agent_id") val agentId: String? = null,
    @SerialName("agent_session_id") val agentSessionId: String? = null,
    val status: String? = null,
    val stream: String? = null,
    val chunk: String? = null,
    val tail: String? = null,
    @SerialName("output_file") val outputFile: String? = null,
    @SerialName("output_tail") val outputTail: String? = null,
    @SerialName("exit_code") val exitCode: Int? = null,
    val duration: String? = null,
    val stalled: Boolean? = null,
)

/**
 * A message block. The server's `type` discriminator selects which fields are
 * populated; this is modelled as one flat class rather than a sealed hierarchy
 * because the runtime delta path patches individual fields in place, and a
 * sealed type would force lossy re-wrapping on every append.
 *
 * `type` is one of: text, reasoning, tool, attachments, error, notice,
 * command, status.
 */
@Serializable
data class UIMessage(
    val id: Int,
    val type: String,
    val content: String = "",
    @SerialName("reasoning_timing") val reasoningTiming: UIReasoningTiming? = null,
    val name: String? = null,
    val input: JsonElement? = null,
    val output: JsonElement? = null,
    @SerialName("tool_call_id") val toolCallId: String = "",
    val running: Boolean = false,
    val progress: List<JsonElement>? = null,
    @SerialName("elapsed_time_seconds") val elapsedTimeSeconds: Double? = null,
    val approval: UIToolApproval? = null,
    @SerialName("user_input") val userInput: UIUserInput? = null,
    @SerialName("background_task") val backgroundTask: UIBackgroundTask? = null,
    @SerialName("execution_location") val executionLocation: UIExecutionLocation? = null,
    val diff: String? = null,
    val code: String? = null,
    val args: Map<String, String>? = null,
    val attachments: List<UIAttachment>? = null,
) {
    companion object {
        const val TYPE_TEXT = "text"
        const val TYPE_REASONING = "reasoning"
        const val TYPE_TOOL = "tool"
        const val TYPE_ATTACHMENTS = "attachments"
        const val TYPE_ERROR = "error"
        const val TYPE_NOTICE = "notice"
        const val TYPE_COMMAND = "command"
        const val TYPE_STATUS = "status"
    }
}

// ---------------------------------------------------------------------------
// Turns
// ---------------------------------------------------------------------------

@Serializable
data class UISkillActivationSkill(
    val name: String,
    @SerialName("display_name") val displayName: String? = null,
    val description: String? = null,
    @SerialName("source_kind") val sourceKind: String? = null,
    val state: String? = null,
)

@Serializable
data class UISkillActivation(
    val skills: List<UISkillActivationSkill>? = null,
    val prompt: String? = null,
)

@Serializable
data class UIReplyRef(
    val id: String? = null,
    val text: String? = null,
)

@Serializable
data class UIForwardRef(
    val id: String? = null,
    val text: String? = null,
)

@Serializable
data class UIUserTurn(
    @SerialName("turn_id") val turnId: String,
    /** Immutable turn sequence allocated at admission; orders live vs settled. */
    @SerialName("turn_position") val turnPosition: Int? = null,
    val role: String = ROLE_USER,
    val text: String = "",
    @SerialName("user_message_kind") val userMessageKind: String? = null,
    @SerialName("skill_activation") val skillActivation: UISkillActivation? = null,
    val attachments: List<UIAttachment>? = null,
    val reply: UIReplyRef? = null,
    val forward: UIForwardRef? = null,
    val timestamp: String = "",
    val platform: String? = null,
    @SerialName("sender_display_name") val senderDisplayName: String? = null,
    @SerialName("sender_avatar_url") val senderAvatarUrl: String? = null,
    @SerialName("sender_user_id") val senderUserId: String? = null,
    @SerialName("external_message_id") val externalMessageId: String? = null,
    val id: String? = null,
) {
    companion object { const val ROLE_USER = "user" }
}

@Serializable
data class UIAssistantTurn(
    @SerialName("turn_id") val turnId: String,
    @SerialName("turn_position") val turnPosition: Int? = null,
    val role: String = ROLE_ASSISTANT,
    /** Nullable on purpose: the server omits it after a restart. */
    val messages: List<UIMessage>? = null,
    val timestamp: String = "",
    @SerialName("runtime_forkable") val runtimeForkable: Boolean? = null,
    val platform: String? = null,
    @SerialName("external_message_id") val externalMessageId: String? = null,
    val id: String? = null,
) {
    companion object { const val ROLE_ASSISTANT = "assistant" }

    val safeMessages: List<UIMessage> get() = messages.orEmpty()
}

@Serializable
data class UISystemTurn(
    @SerialName("turn_id") val turnId: String,
    @SerialName("turn_position") val turnPosition: Int? = null,
    val role: String = ROLE_SYSTEM,
    val kind: String? = null,
    @SerialName("background_task") val backgroundTask: UIBackgroundTask? = null,
    val timestamp: String = "",
    val platform: String? = null,
    val id: String? = null,
) {
    companion object { const val ROLE_SYSTEM = "system" }
}

/**
 * A turn in the conversation. Kept flat (with `role` as a string) because the
 * history endpoint returns a heterogeneous array and a polymorphic decoder
 * would need a registered discriminator for every future role.
 */
@Serializable
data class UITurn(
    @SerialName("turn_id") val turnId: String,
    @SerialName("turn_position") val turnPosition: Int? = null,
    val role: String,
    val timestamp: String = "",

    // user-role fields
    val text: String? = null,
    @SerialName("user_message_kind") val userMessageKind: String? = null,
    @SerialName("skill_activation") val skillActivation: UISkillActivation? = null,
    val attachments: List<UIAttachment>? = null,
    val reply: UIReplyRef? = null,
    val forward: UIForwardRef? = null,
    @SerialName("sender_display_name") val senderDisplayName: String? = null,
    @SerialName("sender_avatar_url") val senderAvatarUrl: String? = null,

    // assistant-role fields
    val messages: List<UIMessage>? = null,
    @SerialName("runtime_forkable") val runtimeForkable: Boolean? = null,

    // system-role fields
    val kind: String? = null,
    @SerialName("background_task") val backgroundTask: UIBackgroundTask? = null,

    // shared
    val platform: String? = null,
    @SerialName("external_message_id") val externalMessageId: String? = null,
    val id: String? = null,
) {
    /** User and assistant entries share a turn ID; their roles distinguish list rows. */
    val listKey: String get() = "$role:${id?.takeIf(String::isNotBlank) ?: turnId}"

    val isUser: Boolean get() = role == UIUserTurn.ROLE_USER
    val isAssistant: Boolean get() = role == UIAssistantTurn.ROLE_ASSISTANT
    val isSystem: Boolean get() = role == UISystemTurn.ROLE_SYSTEM
    val safeMessages: List<UIMessage> get() = messages.orEmpty()
}

// ---------------------------------------------------------------------------
// Runtime run state
// ---------------------------------------------------------------------------

object RunStatus {
    const val ADMITTING = "admitting"
    const val RUNNING = "running"
    const val WAITING_DECISION = "waiting_decision"
    const val ABORTING = "aborting"
    const val FINISHING = "finishing"
    const val COMPLETED = "completed"
    const val ABORTED = "aborted"
    const val ERRORED = "errored"
    const val LOST = "lost"

    /** Terminal statuses — a settled run must never be resurrected by an old frame. */
    val TERMINAL = setOf(COMPLETED, ABORTED, ERRORED, LOST)

    fun isTerminal(status: String?): Boolean = status != null && status in TERMINAL
}

@Serializable
data class RuntimeCursor(
    val epoch: String,
    val seq: Long,
)

@Serializable
data class RuntimeRunOperation(
    /** retry | edit */
    val kind: String,
    @SerialName("replace_from_message_id") val replaceFromMessageId: String? = null,
    @SerialName("replacement_user_turn") val replacementUserTurn: UIUserTurn? = null,
)

@Serializable
data class RuntimeSteerTurnView(
    @SerialName("item_id") val itemId: String,
    /** claimed | applied */
    val status: String,
    val text: String = "",
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("turn_position") val turnPosition: Int? = null,
    @SerialName("after_message_id") val afterMessageId: Int? = null,
    val timestamp: String = "",
)

@Serializable
data class RuntimeCurrentRunView(
    @SerialName("configuration_only") val configurationOnly: Boolean? = null,
    @SerialName("run_id") val runId: String,
    @SerialName("turn_id") val turnId: String,
    @SerialName("turn_position") val turnPosition: Int? = null,
    @SerialName("invocation_id") val invocationId: String? = null,
    val generation: String? = null,
    val status: String = RunStatus.RUNNING,
    @SerialName("owner_id") val ownerId: String? = null,
    @SerialName("owner_lease_expires_at") val ownerLeaseExpiresAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val messages: List<UIMessage>? = null,
    @SerialName("request_user_turn") val requestUserTurn: UIUserTurn? = null,
    @SerialName("user_turns") val userTurns: List<UIUserTurn>? = null,
    @SerialName("steer_turns") val steerTurns: List<RuntimeSteerTurnView>? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val error: String? = null,
    @SerialName("proposed_terminal_status") val proposedTerminalStatus: String? = null,
    @SerialName("finish_proposed_at") val finishProposedAt: String? = null,
    val operation: RuntimeRunOperation? = null,
) {
    val safeMessages: List<UIMessage> get() = messages.orEmpty()
    val isTerminal: Boolean get() = RunStatus.isTerminal(status)
}

@Serializable
data class RuntimeSnapshot(
    @SerialName("bot_id") val botId: String? = null,
    @SerialName("session_id") val sessionId: String,
    val epoch: String,
    val seq: Long,
    @SerialName("current_run_view") val currentRunView: RuntimeCurrentRunView? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class RuntimeCurrentRunPatch(
    @SerialName("run_id") val runId: String,
    val status: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    val error: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    @SerialName("owner_lease_expires_at") val ownerLeaseExpiresAt: String? = null,
)

@Serializable
data class RuntimeMessageAppend(
    val id: Int,
    /** text | reasoning */
    val type: String,
    val content: String = "",
)

@Serializable
data class RuntimeProgressAppend(
    val id: Int,
    val progress: JsonElement? = null,
    val input: JsonElement? = null,
)

@Serializable
data class RuntimeDelta(
    @SerialName("current_run_view") val currentRunView: RuntimeCurrentRunView? = null,
    val run: RuntimeCurrentRunPatch? = null,
    @SerialName("user_turn_upserts") val userTurnUpserts: List<UIUserTurn>? = null,
    @SerialName("steer_turn_upserts") val steerTurnUpserts: List<RuntimeSteerTurnView>? = null,
    @SerialName("steer_turn_removals") val steerTurnRemovals: List<String>? = null,
    @SerialName("message_appends") val messageAppends: List<RuntimeMessageAppend>? = null,
    @SerialName("progress_appends") val progressAppends: List<RuntimeProgressAppend>? = null,
    @SerialName("message_upserts") val messageUpserts: List<UIMessage>? = null,
    @SerialName("reset_messages") val resetMessages: Boolean = false,
)

/**
 * Client-side runtime state: the authoritative run view plus the (epoch, seq)
 * cursor needed to accept or reject the next delta.
 */
data class RuntimeState(
    val sessionId: String? = null,
    val epoch: String? = null,
    val seq: Long = 0L,
    val run: RuntimeCurrentRunView? = null,
    /** Set when a gap or mismatch is seen; the caller must re-subscribe. */
    val needsSnapshot: Boolean = false,
) {
    companion object { val EMPTY = RuntimeState() }

    val isRunning: Boolean
        get() = run != null && run.configurationOnly != true && !run.isTerminal
}
