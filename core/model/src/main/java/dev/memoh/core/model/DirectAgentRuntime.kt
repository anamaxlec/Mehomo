package dev.memoh.core.model

/** The direct runtimes share model selection, but keep their own login modes. */
enum class DirectAgentRuntime(
    val id: String,
    val label: String,
    val authModes: List<Pair<String, String>>,
    val apiKeyKind: String,
    val deviceAuthMode: String? = null,
) {
    Codex("codex", "Codex", listOf("chatgpt" to "ChatGPT 账号", "api_key" to "API key"), "openai_api_key", "chatgpt"),
    ClaudeCode("claude-code", "Claude Code", listOf("workspace" to "工作空间已有登录", "oauth_token" to "账号 token", "api_key" to "API key"), "anthropic_api_key"),
    GrokBuild("grok-build", "Grok Build", listOf("grok_account" to "Grok 账号", "api_key" to "API key"), "xai_api_key", "grok_account");

    val defaultAuthMode: String get() = authModes.first().first

    companion object {
        fun fromId(id: String?): DirectAgentRuntime? = entries.firstOrNull { it.id == id }
    }
}
