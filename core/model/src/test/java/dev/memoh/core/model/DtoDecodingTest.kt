package dev.memoh.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decoding discipline.
 *
 * The server evolves additively and, in one documented case, returns
 * `messages: null` after a restart. A client that throws on either would show a
 * blank screen for a healthy conversation, so both are pinned here.
 */
class DtoDecodingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        isLenient = true
        coerceInputValues = true
    }

    @Test
    fun `unknown fields are ignored`() {
        val raw = """{"id":"bot-1","name":"n","field_from_the_future":{"nested":true}}"""
        val bot = json.decodeFromString(Bot.serializer(), raw)
        assertEquals("bot-1", bot.id)
    }

    @Test
    fun `missing optional fields fall back to defaults`() {
        val bot = json.decodeFromString(Bot.serializer(), """{"id":"bot-1"}""")
        assertEquals("", bot.name)
        assertTrue(bot.isActive)
        assertNull(bot.avatarUrl)
    }

    @Test
    fun `a null messages array decodes to an empty list`() {
        // Documented server behaviour after a restart; must not crash.
        val raw = """
            {"turn_id":"t1","role":"assistant","timestamp":"2026-09-29T10:00:00Z","messages":null}
        """
        val turn = json.decodeFromString(UITurn.serializer(), raw)
        assertTrue(turn.isAssistant)
        assertTrue(turn.safeMessages.isEmpty())
    }

    @Test
    fun `an absent messages field also decodes to an empty list`() {
        val raw = """{"turn_id":"t1","role":"assistant","timestamp":"2026-09-29T10:00:00Z"}"""
        val turn = json.decodeFromString(UITurn.serializer(), raw)
        assertTrue(turn.safeMessages.isEmpty())
    }

    @Test
    fun `a user turn carries text and attachments`() {
        val raw = """
            {"turn_id":"t1","role":"user","text":"hello","timestamp":"2026-09-29T10:00:00Z",
             "attachments":[{"type":"image","name":"a.png","content_hash":"abc"}]}
        """
        val turn = json.decodeFromString(UITurn.serializer(), raw)
        assertTrue(turn.isUser)
        assertEquals("hello", turn.text)
        assertEquals("abc", turn.attachments?.first()?.contentHash)
    }

    @Test
    fun `an omitted can_approve means the decision is actionable`() {
        // The official client treats absence as permission; only an explicit
        // false blocks it.
        val pending = json.decodeFromString(
            UIToolApproval.serializer(),
            """{"approval_id":"a1","status":"pending"}""",
        )
        assertTrue(pending.isActionable)

        val refused = json.decodeFromString(
            UIToolApproval.serializer(),
            """{"approval_id":"a1","status":"pending","can_approve":false}""",
        )
        assertFalse(refused.isActionable)
    }

    @Test
    fun `a settled approval is not actionable`() {
        val approved = json.decodeFromString(
            UIToolApproval.serializer(),
            """{"approval_id":"a1","status":"approved","can_approve":true}""",
        )
        assertFalse(approved.isActionable)
    }

    @Test
    fun `tool message decodes with polymorphic input and output`() {
        val raw = """
            {"id":1,"type":"tool","name":"bash","tool_call_id":"c1","running":true,
             "input":{"command":"ls"},"output":["a","b"],"elapsed_time_seconds":3}
        """
        val message = json.decodeFromString(UIMessage.serializer(), raw)
        assertEquals(UIMessage.TYPE_TOOL, message.type)
        assertEquals("bash", message.name)
        assertTrue(message.running)
        assertEquals(3.0, message.elapsedTimeSeconds)
    }

    @Test fun `tool timings accept fractional seconds in long histories`() {
        val raw = """{"items":[{"turn_id":"turn","role":"assistant","messages":[{"id":1,"type":"tool","elapsed_time_seconds":2.375}]}]}"""
        val page = json.decodeFromString(ListResponse.serializer(UITurn.serializer()), raw)
        assertEquals(2.375, page.items.single().safeMessages.single().elapsedTimeSeconds)
    }

    @Test fun `native reasoning uses resolved server options and its default`() {
        val model = json.decodeFromString(ChatModel.serializer(), """{"id":"m","type":"chat","reasoning":{"supported":true,"can_disable":true,"default_effort":"medium","efforts":["low","medium","high"]}}""")
        assertEquals(listOf("disable", "low", "medium", "high"), model.efforts)
        assertEquals("medium", model.resolveEffort(null))
        assertEquals("disable", model.resolveEffort("none"))
        assertEquals("high", model.resolveEffort("high"))
        val unavailable = model.copy(reasoning = ReasoningOptions(supported = false),
            capabilities = ModelCapabilities(availableEfforts = listOf("high")))
        assertTrue(unavailable.efforts.isEmpty())
        assertNull(unavailable.resolveEffort("high"))
    }

    @Test fun `external catalogs retain runtime model aliases and model-specific reasoning`() {
        val catalog = json.decodeFromString(AgentModelCatalog.serializer(), """{"configured_model_id":"alias","models":[{"id":"alias","resolved_model_id":"full-model","name":"Model","default_reasoning_effort":"high","reasoning_efforts":[{"id":"low","name":"Low"},{"id":"high","name":"High"}]}]}""")
        assertEquals("alias", catalog.configuredModelId)
        val model = catalog.pickerModels.single()
        assertEquals("full-model", model.modelId)
        assertEquals(listOf("low", "high"), model.efforts)
        assertEquals("high", model.resolveEffort(null))
    }

    @Test
    fun `reasoning timing decodes`() {
        val raw = """{"id":2,"type":"reasoning","content":"hmm","reasoning_timing":{"duration_ms":1200}}"""
        val message = json.decodeFromString(UIMessage.serializer(), raw)
        assertEquals(1200L, message.reasoningTiming?.durationMs)
    }

    @Test
    fun `a runtime snapshot with no active run decodes`() {
        val raw = """
            {"bot_id":"b1","session_id":"s1","epoch":"e1","seq":7,"updated_at":"2026-09-29T10:00:00Z"}
        """
        val snapshot = json.decodeFromString(RuntimeSnapshot.serializer(), raw)
        assertNull(snapshot.currentRunView)
        assertEquals(7L, snapshot.seq)
    }

    @Test
    fun `a delta with only a full view decodes`() {
        val raw = """
            {"current_run_view":{"run_id":"r1","turn_id":"t1","status":"running",
             "messages":[{"id":1,"type":"text","content":"hi"}]}}
        """
        val delta = json.decodeFromString(RuntimeDelta.serializer(), raw)
        assertEquals("r1", delta.currentRunView?.runId)
        assertNull(delta.run)
        assertTrue(delta.messageAppends == null)
    }

    @Test
    fun `run status terminal set covers the settled states`() {
        assertTrue(RunStatus.isTerminal(RunStatus.COMPLETED))
        assertTrue(RunStatus.isTerminal(RunStatus.ABORTED))
        assertTrue(RunStatus.isTerminal(RunStatus.ERRORED))
        assertTrue(RunStatus.isTerminal(RunStatus.LOST))
        assertFalse(RunStatus.isTerminal(RunStatus.RUNNING))
        assertFalse(RunStatus.isTerminal(RunStatus.WAITING_DECISION))
        assertFalse(RunStatus.isTerminal(null))
    }

    @Test
    fun `a session with no channel type is treated as local and writable`() {
        val session = json.decodeFromString(Session.serializer(), """{"id":"s1"}""")
        assertTrue(session.isLocal)
    }

    @Test
    fun `an external channel session is read-only`() {
        val session = json.decodeFromString(
            Session.serializer(),
            """{"id":"s1","channel_type":"telegram"}""",
        )
        assertFalse(session.isLocal)
    }

    @Test
    fun `catalog models without a display alias use their provider model identity`() {
        val catalog = json.decodeFromString(AgentModelCatalog.serializer(), """{"models":[{"id":"db-uuid","resolved_model_id":"mimo-v2.6-flash","name":""}]}""")
        assertEquals("mimo-v2.6-flash", catalog.pickerModels.single().label)
        assertEquals("MiMo", catalog.pickerModels.single().copy(displayName = "MiMo").label)
    }

    @Test
    fun `only enabled chat models are selectable`() {
        val chat = json.decodeFromString(
            ChatModel.serializer(),
            """{"id":"m1","type":"chat","enable":true}""",
        )
        val embedding = json.decodeFromString(
            ChatModel.serializer(),
            """{"id":"m2","type":"embedding","enable":true}""",
        )
        val disabled = json.decodeFromString(
            ChatModel.serializer(),
            """{"id":"m3","type":"chat","enable":false}""",
        )
        assertTrue(chat.isSelectable)
        assertFalse(embedding.isSelectable)
        assertFalse(disabled.isSelectable)
    }

    @Test
    fun `reasoning efforts accept either server spelling`() {
        val a = json.decodeFromString(
            ModelCapabilities.serializer(),
            """{"reasoning_efforts":["low","high"]}""",
        )
        val b = json.decodeFromString(
            ModelCapabilities.serializer(),
            """{"available_efforts":["minimal"]}""",
        )
        assertEquals(listOf("low", "high"), a.efforts)
        assertEquals(listOf("minimal"), b.efforts)
    }

    @Test
    fun `outbound frames omit absent optional fields`() {
        // Sending "" where the server expects absence would be misleading.
        val frame = WSClientMessage(
            type = WSClientMessage.MESSAGE,
            invocationId = "inv-1",
            sessionId = "s1",
            text = "hi",
        )
        val encoded = json.encodeToString(WSClientMessage.serializer(), frame)
        assertFalse(encoded.contains("model_id"))
        assertFalse(encoded.contains("reasoning_effort"))
        assertFalse(encoded.contains("attachments"))
    }

    @Test
    fun `reliable keys are derived for turn and control frames`() {
        val message = WSClientMessage(type = WSClientMessage.MESSAGE, invocationId = "inv-1")
        assertEquals("invocation:inv-1", message.reliableKey)

        val abort = WSClientMessage(type = WSClientMessage.ABORT, controlId = "ctl-1")
        assertEquals("control:ctl-1", abort.reliableKey)

        // Subscribe frames are fire-and-forget: replaying one is harmless but
        // tracking it would leak entries.
        val subscribe = WSClientMessage(type = WSClientMessage.RUNTIME_SUBSCRIBE, sessionId = "s1")
        assertNull(subscribe.reliableKey)
        assertFalse(subscribe.isReliable)
    }

    @Test
    fun `an acknowledgement key is derived from the events that settle frames`() {
        val accepted = UIStreamEvent(type = UIStreamEvent.RUN_ACCEPTED, invocationId = "inv-1")
        assertEquals("invocation:inv-1", acknowledgedRequestKey(accepted))

        val ack = UIStreamEvent(type = UIStreamEvent.CONTROL_ACK, controlId = "ctl-1")
        assertEquals("control:ctl-1", acknowledgedRequestKey(ack))

        // A snapshot settles nothing, so it must not clear a pending frame.
        assertNull(acknowledgedRequestKey(UIStreamEvent(type = UIStreamEvent.RUNTIME_SNAPSHOT)))
    }

    @Test
    fun `stream events expose only known types`() {
        assertTrue(UIStreamEvent(type = UIStreamEvent.RUNTIME_DELTA).isKnown)
        assertFalse(UIStreamEvent(type = "something_new").isKnown)
    }
}
