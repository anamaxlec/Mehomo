package dev.memoh.feature.bots

import dev.memoh.core.network.apiBody
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ManagementFormTest {
    @Test fun `Cloud names come from platform profile when the Bot service account is unnamed`() {
        val wire = Json.parseToJsonElement("""{"user":{"user_id":"u1","username":"login"},"user_profile":{"display_name":"Cloud name","timezone":"Asia/Singapore"}}""")
        val profile = cloudUserProfile(wire)
        assertEquals("u1", profile.text("id"))
        assertEquals("Cloud name", profile.text("display_name"))
        assertEquals("Asia/Singapore", profile.text("timezone"))
        assertEquals("Cloud name", memberLabel("u1", listOf(profile, apiBody("id" to "u1", "display_name" to "", "username" to ""))))
        assertEquals("other", memberLabel("other", listOf(profile)))
    }
    @Test fun `switching model resets an unsupported effort while keeping other fields`() {
        val fields = listOf(FormField("model", "模型"), FormField("effort", "思考强度", choicesFor = {
            if (it["model"] == "a") listOf("" to "默认", "high" to "高") else listOf("" to "默认", "low" to "低")
        }), FormField("name", "名称"))
        val values = mutableMapOf("model" to "a", "effort" to "high", "name" to "保留")
        updateFormValue(fields, values, "model", "b")
        assertEquals("", values["effort"])
        assertEquals("保留", values["name"])
    }
    @Test fun `channel rule changes target without retaining the previous identity`() {
        val body = channelRuleBody(apiBody("subject" to "channel", "subject_channel_type" to "telegram", "channel_identity_id" to "previous-member",
            "effect" to "allow", "enabled" to true, "conversation_type" to "group", "conversation_id" to "group-1", "thread_id" to ""))
        assertEquals(JsonNull, body["channel_identity_id"])
        assertEquals("telegram", body["subject_channel_type"]!!.jsonPrimitive.content)
        assertEquals("group-1", body["source_scope"]!!.jsonObject["conversation_id"]!!.jsonPrimitive.content)
        assertFalse("thread_id" in body["source_scope"]!!.jsonObject)
    }
    @Test fun `blank secret is omitted and typed settings retain JSON types`() {
        val fields = listOf(FormField("secret", "密钥", secret = true, omitBlank = true), FormField("count", "数量", kind = FieldKind.Number, min = 0), FormField("enabled", "启用", kind = FieldKind.Toggle))
        val body = formBody(fields, mapOf("secret" to " ", "count" to "0", "enabled" to "false"))
        assertFalse("secret" in body)
        assertEquals(0, body["count"]!!.jsonPrimitive.int)
        assertFalse(body["enabled"]!!.jsonPrimitive.boolean)
    }
    @Test fun `out of range and malformed config do not reach the save callback`() {
        assertThrows(IllegalArgumentException::class.java) { formBody(listOf(FormField("percent", "占比", kind = FieldKind.Number, min = 1, max = 100)), mapOf("percent" to "101")) }
        assertThrows(IllegalArgumentException::class.java) { formBody(listOf(FormField("config", "配置", kind = FieldKind.Object)), mapOf("config" to "[]")) }
    }
    @Test fun `media parameters retain decimal and boolean values`() {
        val fields = listOf(FormField("speed", "语速", kind = FieldKind.Decimal), FormField("enabled", "启用", kind = FieldKind.Toggle))
        val body = formBody(fields, mapOf("speed" to "1.25", "enabled" to "true"))
        assertEquals(1.25, body["speed"]!!.jsonPrimitive.double, 0.0)
        assertTrue(body["enabled"]!!.jsonPrimitive.boolean)
    }
    @Test fun `model switch carries required model identity and capabilities`() {
        val model = Json.parseToJsonElement("""{"id":"db-id","provider_id":"provider","model_id":"model","name":"Model","type":"chat","config":{"context_window":128000,"reasoning_efforts":["high"]}}""").jsonObject
        val body = modelUpdate(model, false)
        assertEquals(model["provider_id"], body["provider_id"])
        assertEquals(model["model_id"], body["model_id"])
        assertEquals(model["config"], body["config"])
        assertFalse(body["enable"]!!.jsonPrimitive.boolean)
        assertFalse("id" in body)
    }
    @Test fun `remote enrollment quotes shell metacharacters without executing them`() {
        val command = runtimeConnectCommand("https://cloud/api/memoh", "k'\$(whoami)", "team")
        assertTrue(command.contains("--key 'k'\\''\$(whoami)'"))
        assertTrue(command.contains("--team-id 'team'"))
    }
}
