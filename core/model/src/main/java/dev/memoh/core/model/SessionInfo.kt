package dev.memoh.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionInfo(
    @SerialName("message_count") val messageCount: Long = 0,
    @SerialName("context_usage") val contextUsage: ContextUsage = ContextUsage(),
    @SerialName("cache_stats") val cacheStats: SessionCacheStats = SessionCacheStats(),
    val skills: List<String> = emptyList(),
)

@Serializable
data class ContextUsage(
    @SerialName("used_tokens") val usedTokens: Long = 0,
    @SerialName("context_window") val contextWindow: Long? = null,
    val breakdown: List<ContextKindUsage> = emptyList(),
    @SerialName("tool_defs") val toolDefs: List<ContextToolUsage> = emptyList(),
    @SerialName("budget_plan") val budgetPlan: ContextBudgetPlan? = null,
    val compaction: ContextCompaction? = null,
) {
    /** Match the server's budgeting basis, including schemas outside fragments. */
    val estimatedTokens: Long? get() = (breakdown.sumOf { it.tokenEstimate.coerceAtLeast(0) } +
        toolDefs.sumOf { it.tokenEstimate.coerceAtLeast(0) }).takeIf { it > 0 }
    val tokens: Long get() = estimatedTokens ?: usedTokens.coerceAtLeast(0)
    fun window(fallback: Long? = null): Long? = budgetPlan?.window?.takeIf { it > 0 }
        ?: contextWindow?.takeIf { it > 0 } ?: fallback?.takeIf { it > 0 }
    fun fraction(fallback: Long? = null): Float? = window(fallback)?.let { tokens.toFloat() / it }
    val autoCompactTokens: Long? get() = compaction?.autoTokens?.takeIf {
        it > 0 && compaction?.enabled == true && budgetPlan != null
    }
}

@Serializable
data class ContextKindUsage(
    val kind: String = "",
    val fragments: Int = 0,
    @SerialName("token_estimate") val tokenEstimate: Long = 0,
)

@Serializable
data class ContextToolUsage(
    val provider: String = "",
    val tools: Int = 0,
    @SerialName("token_estimate") val tokenEstimate: Long = 0,
)

@Serializable
data class ContextBudgetPlan(
    val window: Long = 0,
    @SerialName("output_reserve") val outputReserve: Long = 0,
)

@Serializable
data class ContextCompaction(
    val enabled: Boolean = false,
    @SerialName("auto_tokens") val autoTokens: Long = 0,
)

@Serializable
data class SessionCacheStats(
    @SerialName("cache_read_tokens") val cacheReadTokens: Long = 0,
    @SerialName("total_input_tokens") val totalInputTokens: Long = 0,
    @SerialName("cache_hit_rate") val cacheHitRate: Double = 0.0,
)
