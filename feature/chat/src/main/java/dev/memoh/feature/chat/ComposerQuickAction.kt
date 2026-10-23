package dev.memoh.feature.chat

internal enum class ComposerQuickAction(val command: String, val label: String) {
    Help("/help", "帮助"),
    Skills("/skill list", "技能列表"),
    New("/new", "新建会话"),
    Model("/model", "切换模型"),
    Permission("/permission", "权限模式"),
    Plan("/plan", "计划模式"),
    Goal("/goal", "任务目标"),
}

/** These actions are local UI affordances, not entries in the runtime command catalogue. */
internal fun ChatUiState.composerQuickActions(): List<ComposerQuickAction> = buildList {
    add(ComposerQuickAction.Help)
    if (nativeSkillsEnabled) add(ComposerQuickAction.Skills)
    add(ComposerQuickAction.New)
    if (nativeSkillsEnabled) add(ComposerQuickAction.Model)
    if (!controls?.modes?.availableModes.isNullOrEmpty()) add(ComposerQuickAction.Permission)
    if (controls?.capabilities?.planMode == true) add(ComposerQuickAction.Plan)
    if (controls?.capabilities?.goal == true) add(ComposerQuickAction.Goal)
}

internal fun ChatUiState.composerQuickAction(text: String): ComposerQuickAction? =
    composerQuickActions().firstOrNull { it.command.equals(text.trim(), ignoreCase = true) }

internal fun quickActionSuggestions(text: String, actions: List<ComposerQuickAction>): List<ComposerQuickAction> {
    val query = slashMenuQuery(text) ?: return emptyList()
    return actions.filter { it.command.drop(1).contains(query, ignoreCase = true) || it.label.contains(query, ignoreCase = true) }
}
