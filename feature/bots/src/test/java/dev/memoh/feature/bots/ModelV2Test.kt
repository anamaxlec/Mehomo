package dev.memoh.feature.bots

import dev.memoh.core.network.apiBody
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ModelV2Test {
    @Test fun legacyServersKeepTheirOriginalWriteFormatAndAdvancedFields() {
        val original = apiBody("context_window" to 128000, "compatibilities" to listOf("reasoning", "tool-call"),
            "thinking_mode" to "adaptive", "reasoning_efforts" to listOf("low", "high"), "reasoning_dialect" to "adaptive", "future" to true)
        val model = apiBody("type" to "chat", "model_id" to "m", "provider_id" to "p", "config" to original)
        assertFalse(useModelConfigV2(model, listOf(model), null))
        val fields = legacyModelFields(model, emptyList())
        val body = legacyModelFormBody(formBody(fields, fields.associate { it.key to it.value }))
        assertEquals(JsonObject(original + ("description" to JsonPrimitive(""))), body["config"])
        assertFalse("schema_version" in body["config"].obj())
        assertFalse(useModelConfigV2(null, emptyList(), null))
        assertTrue(useModelConfigV2(null, emptyList(), apiBody("models" to JsonArray(listOf(apiBody("config" to apiBody("schema_version" to 2)))))))
    }
    private val config = Json.parseToJsonElement("""{"schema_version":2,"context_window":128000,"max_output_tokens":8192,"modalities":{"input":["text","image","pdf"],"output":["text","video"]},"tool_call":true,"structured_output":true,"reasoning":{"efforts":["low","high"],"toggle":true,"dialect":"budget","off":"accepted","budget":{"min":0,"max":4096},"default":{"enabled":false,"effort":"high","budget":512},"replay":{"field":"reasoning_details","required":true}},"future_field":{"keep":true},"thinking_mode":"none","reasoning_efforts":["disable"],"compatibilities":[]}""").jsonObject

    @Test fun editingTheNamePreservesCapabilitiesUnknownModalitiesAndHiddenDefaults() {
        val model = apiBody("id" to "m", "model_id" to "model", "provider_id" to "p", "name" to "Before", "type" to "chat", "config" to config)
        val fields = modelFields(model, listOf(apiBody("id" to "p", "name" to "Provider")))
        val values = fields.associate { it.key to it.value }.toMutableMap().apply { put("name", "After") }
        val body = modelFormBody(formBody(fields, values), config)
        assertEquals("After", body.text("name"))
        val saved = body["config"].obj()
        assertEquals(config["reasoning"], saved["reasoning"])
        assertEquals(config["modalities"], saved["modalities"])
        assertEquals(config["future_field"], saved["future_field"])
        assertTrue(saved.flag("structured_output"))
        assertFalse("thinking_mode" in saved); assertFalse("compatibilities" in saved); assertFalse("reasoning_efforts" in saved)
        assertFalse(body.keys.any { it.startsWith('_') })
    }

    @Test fun anAlwaysOnModelDoesNotGainControlsWhenSaved() {
        val original = apiBody("schema_version" to 2, "reasoning" to apiBody())
        val model = apiBody("type" to "chat", "model_id" to "fixed", "provider_id" to "p", "config" to original)
        val fields = modelFields(model, emptyList())
        val saved = modelFormBody(formBody(fields, fields.associate { it.key to it.value }), original)["config"].obj()
        assertEquals(apiBody(), saved["reasoning"])
    }

    @Test fun explicitlyRemovingAdvancedFieldsKeepsTheTypedCapabilities() {
        val saved = modelFormBody(apiBody("type" to "chat", "config" to apiBody()), config)["config"].obj()
        assertFalse("future_field" in saved)
        assertEquals(config["reasoning"], saved["reasoning"])
        assertEquals(config["modalities"], saved["modalities"])
        assertEquals(config["context_window"], saved["context_window"])
    }

    @Test fun enablingAModelDoesNotReplayItsStaleLegacyView() {
        val saved = modelUpdate(apiBody("id" to "m", "model_id" to "model", "type" to "chat", "provider_id" to "p", "config" to config), true)
        assertTrue(saved.flag("enable"))
        val updated = saved["config"].obj()
        assertFalse("thinking_mode" in updated); assertFalse("reasoning_efforts" in updated); assertFalse("compatibilities" in updated)
        assertEquals(config["reasoning"], updated["reasoning"])
        assertEquals(config["modalities"], updated["modalities"])
        assertEquals(config["future_field"], updated["future_field"])
        val legacy = apiBody("thinking_mode" to "adaptive", "compatibilities" to listOf("reasoning"))
        assertEquals(legacy, modelUpdate(apiBody("config" to legacy), false)["config"])
    }

    @Test fun disablingReasoningAndChangingToEmbeddingRemoveOnlyInapplicableFields() {
        val off = modelFormBody(apiBody("type" to "chat", "_reasoning" to false), config)["config"].obj()
        assertFalse("reasoning" in off)
        assertEquals(config["future_field"], off["future_field"])
        val embedding = modelFormBody(apiBody("type" to "embedding", "dimensions" to 768), config)["config"].obj()
        assertEquals("768", embedding.text("dimensions"))
        assertTrue(listOf("reasoning", "modalities", "tool_call", "structured_output", "context_window", "max_output_tokens").none { it in embedding })
        assertEquals(config["future_field"], embedding["future_field"])
    }

    @Test fun clearedNumbersAndEffortsDoNotLeaveStaleDefaults() {
        val saved = modelFormBody(apiBody("type" to "chat", "_efforts" to listOf("low"), "context_window" to JsonNull,
            "_budget_min" to JsonNull, "_budget_max" to JsonNull), config)["config"].obj()
        assertFalse("context_window" in saved)
        val reasoning = saved["reasoning"].obj()
        assertFalse("budget" in reasoning)
        assertFalse("effort" in reasoning["default"].obj())
        assertEquals("512", reasoning["default"].obj().text("budget"))
    }

    @Test fun invalidBudgetRangesFailBeforeTheSave() {
        assertThrows(IllegalArgumentException::class.java) { modelFormBody(apiBody("type" to "chat", "_budget_min" to 20, "_budget_max" to 10), config) }
        assertThrows(IllegalArgumentException::class.java) { modelFormBody(apiBody("type" to "chat", "_budget_max" to 0), config) }
    }

    @Test fun imageModelsReadCanonicalOutputRatherThanTheLegacyView() {
        assertTrue(modelProducesImage(apiBody("config" to apiBody("schema_version" to 2, "modalities" to apiBody("output" to listOf("image"))))))
        assertFalse(modelProducesImage(apiBody("config" to apiBody("schema_version" to 2, "modalities" to apiBody("output" to listOf("text")), "compatibilities" to listOf("image-output")))))
    }

    @Test fun catalogSourcesUseServerMetadataAndNeverInferFromNamesOrEndpoints() {
        fun provider(metadata: JsonObject) = apiBody("id" to "p", "name" to "OpenAI", "metadata" to metadata)
        assertEquals("deepseek", providerCatalogSource(provider(apiBody("source" to " deepseek "))))
        val legacy = provider(apiBody("registry" to apiBody("source" to "qwen.yaml")))
        assertEquals("alibaba-cn", providerCatalogSource(legacy))
        assertEquals("import-catalog-models", providerImportPath(legacy).last())
        assertEquals("import-models", providerImportPath(legacy, fromEndpoint = true).last())
        assertNull(providerCatalogSource(provider(apiBody("catalog" to "openai"))))
        assertThrows(IllegalArgumentException::class.java) { providerImportPath(provider(apiBody("source" to "builtin"))) }
        assertNull(providerCatalogSource(provider(apiBody("source" to "openai", "managed" to true))))
    }

    @Test fun legacyOpenCodeGoSourceReportedByServerUsesCatalogImport() {
        val provider = apiBody("id" to "legacy-go", "client_type" to "opencode-go",
            "config" to apiBody("base_url" to "https://opencode.ai/zen/go/v1"),
            "metadata" to apiBody("source" to "opencode-go"))
        assertEquals("opencode-go", providerCatalogSource(provider))
        assertEquals("import-catalog-models", providerImportPath(provider).last())
        assertEquals("import-models", providerImportPath(provider, fromEndpoint = true).last())
    }

    @Test fun subscriptionProtocolsPointingAtAProxyRemainCustomProviders() {
        val sources = listOf(apiBody("client_type" to "github-copilot", "base_url" to "https://api.githubcopilot.com", "subscription" to true))
        assertTrue(providerIsSubscription(apiBody("client_type" to "github-copilot", "config" to apiBody("base_url" to "https://api.githubcopilot.com/")), sources))
        assertFalse(providerIsSubscription(apiBody("client_type" to "github-copilot", "config" to apiBody("base_url" to "https://proxy.example")), sources))
    }
}
