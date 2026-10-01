package dev.memoh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Server → client WebSocket events (`UIStreamEvent` in the official web client).
 *
 * The server sends a flat JSON object with a `type` discriminator. Every field
 * is optional-with-default because the server omits irrelevant ones per event
 * type, and the client must tolerate additive changes.
 */
@Serializable
data class UIStreamEvent(
    val type: String,

    // --- run lifecycle ---
    @SerialName("run_id") val runId: String? = null,
    @SerialName("invocation_id") val invocationId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("turn_id") val turnId: String? = null,
    @SerialName("turn_position") val turnPosition: Int? = null,
    val epoch: String? = null,
    val seq: Long? = null,
    /** True when the server deduplicated a re-sent frame; do not start a second run. */
    val duplicate: Boolean? = null,

    // --- rejection / error ---
    val code: String? = null,
    val message: String? = null,
    val feedback: String? = null,

    // --- runtime frames ---
    val snapshot: RuntimeSnapshot? = null,
    val delta: RuntimeDelta? = null,

    // --- control ack ---
    val control: String? = null,
    @SerialName("control_id") val controlId: String? = null,
    val applied: Boolean? = null,

    // --- command results ---
    val terminal: Boolean? = null,
    val result: CommandActionResult? = null,
    val error: CommandActionError? = null,
) {
    companion object {
        const val RUN_ACCEPTED = "run_accepted"
        const val RUN_REJECTED = "run_rejected"
        const val ERROR = "error"
        const val SESSION_CREATED = "session_created"
        const val MODEL_PREFERENCE_SETTLED = "model_preference_settled"
        const val RUNTIME_SNAPSHOT = "runtime_snapshot"
        const val RUNTIME_DELTA = "runtime_delta"
        const val RUNTIME_DROPPED = "runtime_dropped"
        const val CONTROL_ACK = "control_ack"
        const val COMMAND_RESULT = "command_result"
        const val COMMAND_ERROR = "command_error"

        /**
         * The frames the client acts on. Anything else is ignored — matching the
         * official client's allow-list so a future server event cannot surprise
         * the state machine.
         */
        val KNOWN = setOf(
            RUN_ACCEPTED, RUN_REJECTED, ERROR, SESSION_CREATED, MODEL_PREFERENCE_SETTLED,
            RUNTIME_SNAPSHOT, RUNTIME_DELTA, RUNTIME_DROPPED, CONTROL_ACK,
            COMMAND_RESULT, COMMAND_ERROR,
        )
    }

    val isKnown: Boolean get() = type in KNOWN
}

@Serializable
data class CommandActionListItem(
    @SerialName("i18n_key") val i18nKey: String? = null,
    val id: String? = null,
    val title: String = "",
    val description: String? = null,
    val kind: String? = null,
)

@Serializable
data class CommandActionResult(
    val data: kotlinx.serialization.json.JsonElement? = null,
    val notice: String? = null,
    @SerialName("text_key") val textKey: String? = null,
    val kind: String = "",
    val title: String? = null,
    val text: String? = null,
    val items: List<CommandActionListItem>? = null,
)

@Serializable
data class CommandActionError(
    val code: String = "",
    val message: String = "",
)

// ---------------------------------------------------------------------------
// Session activity SSE (`GET /bots/{bot_id}/sessions/events`)
// ---------------------------------------------------------------------------

/**
 * A lightweight bot-wide session event. Carries no message bodies — only enough
 * to keep a sidebar list sorted, renamed, and badged.
 */
@Serializable
data class SessionActivityEvent(
    val type: String,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("session_ids") val sessionIds: List<String>? = null,
    @SerialName("session_type") val sessionType: String? = null,
    val title: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
    val reason: String? = null,
    @SerialName("schedule_id") val scheduleId: String? = null,
    val count: Int? = null,
    @SerialName("cache_invalidation") val cacheInvalidation: Boolean? = null,
) {
    companion object {
        const val ACTIVITY_READY = "activity_ready"
        const val SESSION_INVALIDATED = "session_invalidated"
        const val SESSION_TOUCHED = "session_touched"
        const val SESSION_TITLE_CHANGED = "session_title_changed"
        const val SESSION_CREATED = "session_created"
        const val SCHEDULE_CHANGED = "schedule_changed"
        const val SESSION_COMPACTION = "session_compaction"
        const val DROPPED = "dropped"
        const val PING = "ping"
    }
}
