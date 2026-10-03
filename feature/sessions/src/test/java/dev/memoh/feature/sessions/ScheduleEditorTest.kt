package dev.memoh.feature.sessions

import dev.memoh.core.network.apiBody
import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ScheduleEditorTest {
    @Test fun `editing basics preserves opaque runtime execution`() {
        val original = Json.parseToJsonElement("""{"id":"s","name":"old","runtime_type":"acp_agent","acp_agent_id":"claude","acp_model_id":"opus","custom_runtime":{"key":true}}""").jsonObject
        val body = scheduleBody(original, apiBody("name" to "new"), apiBody("runtime_type" to "acp_agent", "acp_agent_id" to "claude", "acp_model_id" to "opus"))
        assertFalse("execution" in body)
        val changed = scheduleBody(original, apiBody("name" to "new"), apiBody("acp_model_id" to "sonnet"))
        assertEquals("claude", changed["execution"]!!.jsonObject["acp_agent_id"]!!.jsonPrimitive.content)
        assertEquals(original["custom_runtime"], changed["execution"]!!.jsonObject["custom_runtime"])
        assertFalse("id" in changed["execution"]!!.jsonObject)
    }
    @Test fun `weekly and daily previews follow Bot timezone`() {
        val now = ZonedDateTime.parse("2026-10-03T01:30:00Z")
        assertEquals("2026-10-04T09:00+08:00[Asia/Singapore]", nextPreset("0 9 * * *", "Asia/Singapore", now).toString())
        assertEquals("2026-10-05T09:00+08:00[Asia/Singapore]", nextPreset("0 9 * * 1", "Asia/Singapore", now).toString())
        assertNull(nextPreset("@every 1h", "Asia/Singapore", now))
    }
    @Test fun `nonexistent DST time is skipped and repeated time remains a future candidate`() {
        assertEquals("2026-03-09T02:30-04:00[America/New_York]", nextPreset("30 2 * * *", "America/New_York", ZonedDateTime.parse("2026-03-08T00:00-05:00[America/New_York]")).toString())
        assertEquals("2026-11-01T01:30-05:00[America/New_York]", nextPreset("30 1 * * *", "America/New_York", ZonedDateTime.parse("2026-11-01T01:45-04:00[America/New_York]")).toString())
    }
}
