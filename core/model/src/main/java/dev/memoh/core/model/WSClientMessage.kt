package dev.memoh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Client → server WebSocket frames (`WSClientMessage` in the official client).
 *
 * Modelled as one flat class with a `type` discriminator and nullable payload
 * fields: kotlinx.serialization cannot easily emit a heterogeneous union, and
 * the wire format genuinely is a flat object.
 *
 * Frames that carry an `invocation_id` or `control_id` are *reliable*: the
 * socket keeps them in a pending table and re-sends them on reconnect until the
 * server acknowledges. The server deduplicates on those ids, so a re-send is
 * safe; re-sending an already-acknowledged frame is not, and the pending table
 * is what prevents it.
 */
@Serializable
data class WSClientMessage(
    val type: String,

    @SerialName("invocation_id") val invocationId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("composer_scope") val composerScope: String? = null,
    val text: String? = null,
    val attachments: List<ChatAttachment>? = null,
    @SerialName("requested_skills") val requestedSkills: List<RequestedSkillRequest>? = null,

    // turn options
    @SerialName("model_id") val modelId: String? = null,
    @SerialName("reasoning_effort") val reasoningEffort: String? = null,
    @SerialName("workspace_target_id") val workspaceTargetId: String? = null,

    // retry / edit
    @SerialName("turn_id") val turnId: String? = null,

    // abort
    @SerialName("run_id") val runId: String? = null,
    @SerialName("control_id") val controlId: String? = null,

    // tool approval
    @SerialName("decision_id") val decisionId: String? = null,
    val decision: String? = null,
    @SerialName("option_id") val optionId: String? = null,
    val reason: String? = null,

    // user input
    val answers: List<WSUserInputAnswer>? = null,
    val canceled: Boolean? = null,

    // runtime subscribe
    val cursor: RuntimeCursor? = null,
) {
    companion object {
        const val MESSAGE = "message"
        const val RETRY_MESSAGE = "retry_message"
        const val EDIT_MESSAGE = "edit_message"
        const val ABORT = "abort"
        const val TOOL_APPROVAL_RESPONSE = "tool_approval_response"
        const val USER_INPUT_RESPONSE = "user_input_response"
        const val RUNTIME_SUBSCRIBE = "runtime_subscribe"
        const val RUNTIME_UNSUBSCRIBE = "runtime_unsubscribe"
    }

    /**
     * Key under which this frame is tracked until acknowledged, or null for
     * fire-and-forget frames. Mirrors the official client exactly: turn-opening
     * frames are keyed by invocation, control frames by control id.
     */
    val reliableKey: String?
        get() = when (type) {
            MESSAGE, RETRY_MESSAGE, EDIT_MESSAGE ->
                invocationId?.trim()?.takeIf(String::isNotEmpty)?.let { "invocation:$it" }
            ABORT, TOOL_APPROVAL_RESPONSE, USER_INPUT_RESPONSE ->
                controlId?.trim()?.takeIf(String::isNotEmpty)?.let { "control:$it" }
            else -> null
        }

    val isReliable: Boolean get() = reliableKey != null
}

/**
 * The pending-table key an inbound event acknowledges, or null when the event
 * settles nothing. Used by the socket to drop acknowledged frames so they are
 * never re-sent.
 */
fun acknowledgedRequestKey(event: UIStreamEvent): String? = when (event.type) {
    UIStreamEvent.RUN_ACCEPTED,
    UIStreamEvent.RUN_REJECTED,
    UIStreamEvent.COMMAND_RESULT,
    UIStreamEvent.COMMAND_ERROR,
    UIStreamEvent.ERROR,
    -> event.invocationId?.trim()?.takeIf(String::isNotEmpty)?.let { "invocation:$it" }

    UIStreamEvent.CONTROL_ACK ->
        event.controlId?.trim()?.takeIf(String::isNotEmpty)?.let { "control:$it" }

    else -> null
}

@Serializable
data class RequestedSkillRequest(val name: String)

@Serializable
data class WSUserInputAnswer(
    @SerialName("question_id") val questionId: String,
    @SerialName("option_ids") val optionIds: List<String>? = null,
    @SerialName("custom_text") val customText: String? = null,
    val text: String? = null,
    val skipped: Boolean? = null,
)
