package dev.memoh.core.model

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UpstreamCapabilitiesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun unchangedAndFailedGenerationKeepTheHeldPromptSet() {
        val held = StarterPromptsResponse("server-material", listOf(StarterPrompt("旧标题", "旧问题")))
        assertSame(held, resolveStarterPrompts(held, StarterPromptsResponse(unchanged = true)))
        assertSame(held, resolveStarterPrompts(held, StarterPromptsResponse()))
        assertTrue(resolveStarterPrompts(null, StarterPromptsResponse()).items.isEmpty())
        val fresh = StarterPromptsResponse("new-material", listOf(StarterPrompt("新标题", "新问题")))
        assertEquals(fresh, resolveStarterPrompts(held, fresh))
    }

    @Test fun capabilityChecksOnlyDisableKnownMissingFeatures() {
        val search = chatExamples.single { it.id == "research-brief" }
        assertTrue(search.missing(exampleCapabilities(null)).isEmpty())
        assertEquals(listOf("web_search"), search.missing(exampleCapabilities(buildJsonObject { put("memory_enabled", false) })))
        val unknown = exampleCapabilities(null)
        assertFalse(unknown.containsKey("workspace"))
        assertFalse(unknown.containsKey("email"))
        assertFalse(unknown.containsKey("channel"))
        assertTrue(chatExamples.filter { it.requires.isEmpty() }.size >= 3)
        assertEquals(chatExamples.size, chatExamples.map { it.id }.distinct().size)
    }

    @Test fun liveRuntimeAllotmentsWinAndMissingStorageUsageRemainsUnknown() {
        val metrics = json.decodeFromString<ContainerMetrics>("""{"metrics":{"cpu":{"cores":4,"usage_percent":25},"memory":{"limit_bytes":2147483648,"usage_bytes":0},"storage":{"limit_bytes":10737418240}},"resource_limits":{"desired":{"cpu_millicores":1000,"memory_bytes":1073741824,"storage_bytes":2147483648}}}""")
        val rows = metrics.resourceMetrics()
        assertEquals("CPU · 4 核", rows[0].label)
        assertEquals("25.0%", rows[0].value)
        assertEquals("内存 · 2.0 GiB", rows[1].label)
        assertEquals("0 MiB", rows[1].value)
        assertEquals("存储 · 10.0 GiB", rows[2].label)
        assertEquals("—", rows[2].value)
    }

    @Test fun configuredLimitsAreOnlyFallbacksAndPausedIsAnExplicitState() {
        val metrics = ContainerMetrics(resourceLimits = GetContainerResourceLimitsResponse(desired = ContainerResourceLimitValuesResponse(cpuMillicores = 500)))
        assertEquals("CPU · 0.5 核", metrics.resourceMetrics()[0].label)
        assertTrue(metrics.resourceMetrics().all { it.value == "—" })
        assertEquals("已暂停", runtimeStatusLabel(" PAUSED "))
        assertEquals("已退出", runtimeStatusLabel("exited"))
        assertEquals("状态未知", runtimeStatusLabel(null))
        val connector = json.decodeFromString<Connector>("""{"status":"connected","enabled":true,"tool_discovery_error":"provider unavailable"}""")
        assertEquals("connected", connector.status)
        assertEquals("provider unavailable", connector.toolDiscoveryError)
    }
}
