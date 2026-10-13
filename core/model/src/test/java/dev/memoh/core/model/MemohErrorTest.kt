package dev.memoh.core.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MemohErrorTest {
    @Test fun problemAndNestedLegacyFeedbackKeepArgsAndDiagnostics() {
        val problem = MemohError.parse("""{"type":"about:blank","status":422,"code":"request.field_invalid","args":{"field":"max_output_tokens"},"detail":"private upstream text","request_id":"request-1","trace_id":"trace-1"}""")
        assertEquals("max_output_tokens 的值无效。", problem.text())
        assertEquals("request-1", problem.requestId)
        assertEquals("trace-1", problem.traceId)
        val legacy = MemohError.parse("""{"response":{"data":{"feedback":{"error_code":"agent_dependency_missing","args":{"dep_id":"codex"},"i18n_key":"untrusted.key","message":"private text"}}}}""")
        assertTrue(legacy.text().contains("codex 尚未安装"))
        assertEquals(ErrorAction.Dependencies, legacy.action)
    }
    @Test fun unknownCodesNeverExposeServerTextOrUseTheFaultAsAnHttpStatus() {
        for (status in listOf(400, 403, 404, 429, 500, 504, 524)) {
            val error = MemohError.parse("""{"code":"future.error","message":"do not show this","fault":"client"}""", status)
            assertFalse(error.text().contains("do not show"))
            assertFalse(error.text().contains("future.error"))
            assertTrue(error.text().isNotBlank())
        }
        assertTrue(MemohError("unknown", status = 429).text().contains("过于频繁"))
        assertTrue(MemohError("unknown", status = 500, fault = "client").text().contains("服务器出错"))
    }
    @Test fun cancellationIsQuietAcrossHttpAndStreams() {
        assertEquals("", MemohError.parse("{}", 499).text())
        assertEquals("", MemohError("future", fault = "canceled").text())
        assertEquals("", errorMessage("canceled"))
        assertEquals("", errorMessage("runtime_control.cancelled"))
        val event = Json.decodeFromString<UIStreamEvent>("""{"type":"run_rejected","code":"canceled","fault":"canceled","args":{},"request_id":"r"}""")
        assertEquals("", event.problem.text())
        assertEquals("r", event.problem.requestId)
    }
    @Test fun streamFeedbackMayBeAnObjectOrAnOlderString() {
        val modern = Json.decodeFromString<UIStreamEvent>("""{"type":"error","feedback":{"code":"session_runtime.session_busy"}}""")
        assertTrue(modern.problem.text().contains("上一条消息"))
        val legacy = Json.decodeFromString<UIStreamEvent>("""{"type":"error","feedback":"old text","message":"private exception"}""")
        assertEquals(errorMessage("internal"), legacy.problem.text())
    }
    @Test fun desktopPreparationUsesTheSameCodeArgsAndCancellationRules() {
        val event = Json.decodeFromString<DisplayPrepareEvent>("""{"type":"error","code":"request.field_required","args":{"field":"desktop"},"request_id":"display-r"}""")
        val problem = MemohError(event.code, event.args, fault = event.fault, requestId = event.requestId)
        assertEquals("缺少 desktop。", problem.text())
        assertEquals("display-r", problem.requestId)
    }
    @Test fun knownErrorsSupplySpecificSetupActions() {
        assertEquals(ErrorAction.BotSettings, MemohError("agent.chat_model_not_configured").action)
        assertEquals(ErrorAction.Agents, MemohError("external_runtime.auth_required").action)
        assertTrue(errorMessage("connector_oauth_client_not_configured").contains("OAuth App"))
        assertEquals(ErrorAction.Connectors, MemohError("connector.oauth_client_not_configured").action)
        assertTrue(errorMessage("schedule.model_required").contains("模型"))
        assertTrue(errorMessage("mcp.name_taken").contains("同名"))
    }
}
