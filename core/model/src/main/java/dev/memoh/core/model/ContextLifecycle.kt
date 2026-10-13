package dev.memoh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ContextLifecycleResponse(
    val turns: List<ContextLifecycleTurn> = emptyList(),
    val aggregates: ContextLifecycleAggregates = ContextLifecycleAggregates(),
    val limit: Int = 50,
    @SerialName("has_more") val hasMore: Boolean = false,
    @SerialName("legacy_source") val legacySource: Boolean = false,
    @SerialName("legacy_history_may_exist") val legacyHistoryMayExist: Boolean = false,
    @SerialName("aggregate_scope") val aggregateScope: String? = null,
)

@Serializable
data class ContextLifecycleTurn(
    @SerialName("run_id") val runId: String = "",
    val status: String? = null,
    @SerialName("error_code") val errorCode: String? = null,
    @SerialName("assistant_message_id") val assistantMessageId: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    val snapshot: ContextLifecycleSnapshot = ContextLifecycleSnapshot(),
)

@Serializable
data class ContextLifecycleSnapshot(
    val model: String? = null,
    val counts: ContextLifecycleCounts = ContextLifecycleCounts(),
    val breakdown: List<ContextKindUsage> = emptyList(),
    @SerialName("tool_defs") val toolDefs: List<ContextToolUsage> = emptyList(),
    @SerialName("budget_plan") val budgetPlan: ContextBudgetPlan? = null,
    val selection: ContextSelection = ContextSelection(),
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long = 0,
) {
    val usage: ContextUsage get() = ContextUsage(usedTokens = counts.tokenEstimate, breakdown = breakdown,
        toolDefs = toolDefs, budgetPlan = budgetPlan)
}

@Serializable
data class ContextLifecycleCounts(
    val fragments: Int = 0,
    val messages: Int = 0,
    @SerialName("token_estimate") val tokenEstimate: Long = 0,
)

@Serializable
data class ContextSelection(
    val selected: Int = 0,
    val dropped: Int = 0,
    val trimmed: Int = 0,
    @SerialName("drop_reasons") val dropReasons: Map<String, Int> = emptyMap(),
    @SerialName("drop_reason_tokens") val dropReasonTokens: Map<String, Long>? = null,
)

@Serializable
data class ContextLifecycleAggregates(
    val turns: Int = 0,
    @SerialName("total_cache_read_tokens") val totalCacheReadTokens: Long = 0,
    @SerialName("total_cache_write_tokens") val totalCacheWriteTokens: Long = 0,
    @SerialName("drop_reasons") val dropReasons: Map<String, Int> = emptyMap(),
)
