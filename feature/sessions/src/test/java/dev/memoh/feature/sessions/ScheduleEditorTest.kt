package dev.memoh.feature.sessions

import dev.memoh.core.network.apiBody
import kotlinx.serialization.json.*
import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class ScheduleEditorTest {
    @Test fun `every visual rule round trips existing expressions without replacing time`() {
        val patterns = listOf("*/15 * * * *", "2 * * * *", "25 17 * * *", "30 8 * * 1,3,5", "0 6 1,15 * *", "0 10 29 2 *")
        for (pattern in patterns) assertEquals(pattern, ScheduleRule.from(pattern).expression())
        assertEquals(ScheduleMode.Cron, ScheduleRule.from("0 8,17 * * *").mode)
        assertEquals("0 8,17 * * *", ScheduleRule.from("0 8,17 * * *").expression())
    }
    @Test fun `advanced cron accepts server syntax and rejects invalid values`() {
        listOf("*/10 * * * *", "0 30 8 * * MON-FRI", "0 8 1 JAN,MAR *", "@daily", "@every 1h30m", "CRON_TZ=Asia/Singapore 0 9 * * *").forEach(::validateSchedulePattern)
        for (pattern in listOf("61 * * * *", "0 24 * * *", "0 8 * * 8", "*/0 * * * *", "@every 0m", "only four cron fields")) {
            assertTrue(pattern, runCatching { validateSchedulePattern(pattern) }.isFailure)
        }
    }
    @Test fun `monthly leap year and named weekdays preview in timezone`() {
        val now = ZonedDateTime.parse("2026-10-03T01:30:00Z")
        assertEquals("2026-10-31T09:00+08:00[Asia/Singapore]", nextPreset("0 9 31 * *", "Asia/Singapore", now).toString())
        assertEquals("2028-02-29T09:00+08:00[Asia/Singapore]", nextPreset("0 9 29 2 *", "Asia/Singapore", now).toString())
        assertEquals("2026-10-05T09:00+08:00[Asia/Singapore]", nextPreset("0 9 * * MON-FRI", "Asia/Singapore", now).toString())
        assertEquals("2026-10-03T02:00Z[UTC]", nextPreset("@hourly", "UTC", now).toString())
    }
    @Test fun `DST repeated hourly time chooses earliest instant rather than earliest clock value`() {
        assertEquals("2026-11-01T01:50-04:00[America/New_York]", nextPreset("*/10 * * * *", "America/New_York", ZonedDateTime.parse("2026-11-01T01:45-04:00[America/New_York]")).toString())
    }
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
