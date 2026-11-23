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
    ClaudeCode("claude-code", "Claude Code", listOf("claude_account" to "Claude 账号", "api_key" to "API key"), "anthropic_api_key", "claude_account"),
    GrokBuild("grok-build", "Grok Build", listOf("grok_account" to "Grok 账号", "api_key" to "API key"), "xai_api_key", "grok_account");

    val defaultAuthMode: String get() = authModes.first().first

    fun authChoices(currentMode: String): List<Pair<String, String>> =
        if (this == ClaudeCode && currentMode in listOf("workspace", "oauth_token"))
            listOf("workspace" to "工作空间已有登录", "oauth_token" to "账号 token", "api_key" to "API key")
        else authModes

    /**
     * A Claude Code workspace login and the device-code sign-ins (a ChatGPT or
     * Grok account) are kept by the runtime in the Agent's own workspace, so the
     * server stores no credential for them.
     */
    fun storesCredential(authMode: String): Boolean =
        authMode != deviceAuthMode && !(this == ClaudeCode && authMode == "workspace")

    companion object {
        fun fromId(id: String?): DirectAgentRuntime? = entries.firstOrNull { it.id == id }
    }
}
