package dev.memoh.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ModelV2ReasoningTest {
    @Test fun modelsRemovedFromTheManagedCatalogCannotBeSelected() {
        assertFalse(ChatModel("m", type = "chat", enable = true, config = ChatModelConfig(catalogAvailable = false)).isSelectable)
        assertTrue(ChatModel("m", type = "chat", enable = true, config = ChatModelConfig(catalogAvailable = true)).isSelectable)
    }
    @Test fun serverDefaultOffWinsOverTheDefaultTierUntilTheUserChoosesAnEffort() {
        val model = Json { ignoreUnknownKeys = true }.decodeFromString<ChatModel>("""{"id":"m","type":"chat","reasoning":{"supported":true,"can_disable":true,"default_off":true,"default_effort":"high","efforts":["low","high"]},"config":{"schema_version":2,"reasoning":{"toggle":true}}}""")
        assertEquals("disable", model.resolveEffort(null))
        assertEquals("disable", model.resolveEffort("unknown"))
        assertEquals("disable", model.resolveEffort("none"))
        assertEquals("low", model.resolveEffort("low"))
        assertEquals(listOf("disable", "low", "high"), model.efforts)
    }

    @Test fun alwaysOnModelsRetainDormantPreferencesWithoutInventingControls() {
        val model = ChatModel("m", reasoning = ReasoningOptions(supported = true, canDisable = false, efforts = emptyList()))
        assertTrue(model.reasoningAlwaysOn)
        assertTrue(model.efforts.isEmpty())
        assertEquals("max", model.resolveEffort("max"))
    }
}
