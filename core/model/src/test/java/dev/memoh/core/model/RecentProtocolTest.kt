package dev.memoh.core.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class RecentProtocolTest {
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun memoryUsageIsIncludedInTotalsAndRetainedAsACategory() {
        val usage = json.decodeFromString<TokenUsageSummary>("""{"chat":[{"input_tokens":10,"output_tokens":2}],"memory":[{"input_tokens":30,"output_tokens":4}],"by_model":[{"input_tokens":40,"output_tokens":6}]}""")
        assertEquals(40L, usage.all.sumOf { it.inputTokens ?: 0 })
        assertEquals(6L, usage.all.sumOf { it.outputTokens ?: 0 })
        assertEquals(30L, usage.categories.single { it.first == "记忆" }.second.single().inputTokens)
    }
    @Test fun reasoningIsDeclaredAndAlwaysOnPreferencesStayDormant() {
        val model = json.decodeFromString<ChatModel>("""{"id":"m","type":"chat","config":{"thinking_mode":"toggle","max_output_tokens":8192},"reasoning":{"supported":true,"can_disable":true,"default_effort":"enabled","efforts":["enabled"]}}""")
        assertEquals(listOf("disable", "enabled"), model.efforts)
        assertEquals("enabled", model.resolveEffort("adaptive"))
        assertEquals("disable", model.resolveEffort("none"))
        assertEquals("开启", reasoningEffortLabel("enabled"))
        assertEquals(8192L, model.config?.maxOutputTokens)
        val always = model.copy(reasoning = ReasoningOptions(supported = true))
        assertTrue(always.efforts.isEmpty())
        assertEquals("disable", always.resolveEffort("none"))
    }
    @Test fun contextLifecycleIsPagedAndNotASessionTotal() {
        val page = json.decodeFromString<ContextLifecycleResponse>("""{"limit":50,"has_more":true,"legacy_history_may_exist":true,"aggregate_scope":"returned_page","turns":[{"run_id":"r","created_at":"2026-10-13T00:00:00Z","snapshot":{"counts":{"token_estimate":50},"breakdown":[{"kind":"conversation_event","token_estimate":50}],"tool_defs":[{"provider":"native","name":"read","token_estimate":10}],"budget_plan":{"window":100,"output_reserve":20},"selection":{"selected":3,"dropped":2,"trimmed":1,"drop_reason_tokens":{"history_budget":30}}}}],"aggregates":{"total_cache_read_tokens":18}}""")
        assertTrue(page.hasMore)
        assertTrue(page.legacyHistoryMayExist)
        assertEquals("returned_page", page.aggregateScope)
        val turn = page.turns.single()
        assertEquals(60L, turn.snapshot.usage.tokens)
        assertEquals(30L, turn.snapshot.selection.dropReasonTokens?.values?.sum())
        assertEquals(100L, turn.snapshot.usage.window())
    }
    @Test fun codexQuotaIsOfferedOnlyForConnectedChatGptCodexAgents() {
        val agent = json.decodeFromString<BotAgent>("""{"id":"a","runtime":"codex","agent_credential_id":"c","metadata":{"auth":"chatgpt"}}""")
        assertTrue(agent.hasCodexUsage)
        assertFalse(agent.copy(agentCredentialId = null).hasCodexUsage)
        assertFalse(agent.copy(metadata = buildJsonObject { put("auth", "api_key") }).hasCodexUsage)
        assertFalse(agent.copy(runtime = "claude-code").hasCodexUsage)
        val usage = json.decodeFromString<CodexUsage>("""{"limit_reached":true,"windows":[{"used_percent":103,"window_minutes":10080,"resets_at":"2026-10-14T01:00:00Z"}]}""")
        assertTrue(usage.limitReached)
        assertEquals("1 周", usage.windows.single().label)
        assertEquals(103.0, usage.windows.single().usedPercent, .0)
    }
    @Test fun providerImportMatchesTheOfficialFragmentAndDoesNotPrintTheKey() {
        val value = """{"v":1,"kind":"memoh-provider-import","name":"测试供应商","base_url":"https://api.example.test/v1/","api_key":"fixture-only-key","client_type":"openai-responses","template":"newapi"}"""
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
        val payload = ProviderImport.parseLink("https://memoh.example.test/providers/connect#payload=$encoded")
        assertEquals("测试供应商", payload.name)
        assertEquals("https://api.example.test/v1", payload.baseUrl)
        assertEquals("openai-responses", payload.clientType)
        assertEquals("••••-key", payload.maskedKey)
        assertFalse(payload.toString().contains("fixture-only-key"))
        assertEquals(payload.name, ProviderImport.parseLink("memoh://providers/connect#payload=$encoded").name)
    }
    @Test fun providerImportRejectsMissingWrongVersionAndUnsupportedCredentials() {
        assertThrows(IllegalArgumentException::class.java) { ProviderImport.parseLink("https://example.test/providers/connect?payload=abc") }
        assertThrows(IllegalArgumentException::class.java) { ProviderImport.parse("not a payload") }
        val malformed = "https://example.test/providers/connect#payload=%ZZ-private"
        assertTrue(ProviderImport.isLink(malformed))
        val invalid = assertThrows(IllegalArgumentException::class.java) { ProviderImport.parseLink(malformed) }
        assertFalse(invalid.message.orEmpty().contains("%ZZ-private"))
        fun encode(type: String, version: Int = 1, url: String = "https://example.test") =
            Base64.getUrlEncoder().encodeToString("""{"kind":"memoh-provider-import","v":$version,"base_url":"$url","api_key":"fixture","client_type":"$type"}""".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { ProviderImport.parse(encode("openai-codex")) }
        assertThrows(IllegalArgumentException::class.java) { ProviderImport.parse(encode("openai-completions", 2)) }
        assertThrows(IllegalArgumentException::class.java) { ProviderImport.parse(encode("openai-completions", url = "file:///tmp/key")) }
    }
}
