package dev.memoh.feature.bots

import dev.memoh.core.network.apiBody
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class RecentManagementTest {
    @Test fun conversationPresetSummaryDiffersFromDenyAll() {
        val privateOnly = listOf(apiBody("effect" to "allow", "source_scope" to apiBody("conversation_type" to "private")))
        assertEquals("全部允许", effectiveChannelAccess("deny", privateOnly, "private"))
        assertEquals("全部拒绝", effectiveChannelAccess("deny", privateOnly, "group"))
        assertEquals("全部拒绝", effectiveChannelAccess("deny", privateOnly, "thread"))
        assertEquals("全部拒绝", effectiveChannelAccess("deny", emptyList(), "private"))
    }
    @Test fun disabledOtherActionAndDefaultEffectRulesCannotChangeTheSummary() {
        val ignored = listOf(apiBody("effect" to "allow"), apiBody("effect" to "deny", "enabled" to false), apiBody("effect" to "deny", "action" to "workspace.exec"))
        assertEquals("全部允许", effectiveChannelAccess("allow", ignored, "group"))
        val narrow = ignored + apiBody("effect" to "deny", "subject_channel_type" to "qq")
        assertTrue(effectiveChannelAccess("allow", narrow, "group").startsWith("除"))
        assertEquals("全部拒绝", effectiveChannelAccess("allow", narrow + apiBody("effect" to "deny"), "group"))
    }
    @Test fun explicitThinkingModeAndMaxOutputKeepUnknownAdvancedFields() {
        val body = apiBody("name" to "m", "type" to "chat", "context_window" to 128000, "max_output_tokens" to 8192,
            "thinking_mode" to "adaptive", "compatibilities" to JsonArray(listOf(JsonPrimitive("reasoning"))),
            "reasoning_efforts" to JsonArray(listOf(JsonPrimitive("none"), JsonPrimitive("adaptive"), JsonPrimitive("max"))),
            "config" to apiBody("reasoning_dialect" to "anthropic", "future_field" to "kept"))
        val config = modelFormBody(body)["config"]!!.jsonObject
        assertEquals("adaptive", config["thinking_mode"]!!.jsonPrimitive.content)
        assertEquals(8192, config["max_output_tokens"]!!.jsonPrimitive.int)
        assertEquals("kept", config["future_field"]!!.jsonPrimitive.content)
        assertEquals(listOf("disable", "max"), config["reasoning_efforts"]!!.jsonArray.map { it.jsonPrimitive.content })
    }
    @Test fun clearingCapabilitiesAndEmbeddingDoNotLeaveActiveThinkingBehind() {
        val advanced = apiBody("thinking_mode" to "adaptive", "reasoning_efforts" to JsonArray(listOf(JsonPrimitive("high"))), "max_output_tokens" to 8192, "dimensions" to 768, "future" to true)
        val off = modelFormBody(apiBody("type" to "chat", "compatibilities" to JsonArray(emptyList()), "max_output_tokens" to JsonNull, "config" to advanced))["config"]!!.jsonObject
        assertFalse("thinking_mode" in off); assertFalse("reasoning_efforts" in off); assertFalse("max_output_tokens" in off)
        assertEquals(JsonPrimitive(true), off["future"])
        val embedding = modelFormBody(apiBody("type" to "embedding", "config" to advanced))["config"]!!.jsonObject
        assertFalse("thinking_mode" in embedding); assertFalse("max_output_tokens" in embedding)
        assertEquals(768, embedding["dimensions"]!!.jsonPrimitive.int)
    }
    @Test fun defaultBotEffortsComeFromTheSameResolvedModelAsChat() {
        val model = managementChatModel(apiBody("id" to "m", "reasoning" to apiBody("supported" to true, "can_disable" to false, "efforts" to listOf("enabled"), "default_effort" to "enabled")))!!
        assertEquals(listOf("enabled"), model.efforts)
        assertEquals("enabled", model.resolveEffort("none"))
        assertEquals("enabled", model.resolveEffort("xhigh"))
    }
}
