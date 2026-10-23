package dev.memoh.feature.chat

import dev.memoh.core.model.RuntimeCommand
import dev.memoh.core.model.SkillCatalogItem
import dev.memoh.core.network.SocketStatus

internal enum class ComposerActionKind { Idle, Waiting, Stop, Send, Enqueue, RuntimeCommand, QuickAction }

internal data class ComposerAction(
    val kind: ComposerActionKind,
    val text: String,
    val steer: Boolean = false,
    val blockedReason: String? = null,
) {
    val enabled: Boolean get() = blockedReason == null && kind !in listOf(ComposerActionKind.Idle, ComposerActionKind.Waiting)
    val description: String get() = when (kind) {
        ComposerActionKind.Stop -> "停止生成"
        ComposerActionKind.Enqueue -> if (steer) "插话" else "加入后续队列"
        else -> "发送"
    }
}

/** Resolve intent first, so an unavailable draft never turns into a Stop action. */
internal fun ChatUiState.composerAction(text: String = draft): ComposerAction {
    val trimmed = text.trim()
    val commands = controls?.commands.orEmpty()
    val queue = parseQueueCommand(trimmed, commands.mapNotNull { it.name })
    val selector = trimmed.takeIf { it.startsWith('/') }?.drop(1)?.takeWhile { !it.isWhitespace() }
    val command = commands.firstOrNull { it.name == selector }
    val quickAction = composerQuickAction(trimmed)
    val nativeSkill = nativeSkillsEnabled && skillCatalog.any { it.name == selector }
    val kind = when {
        pending.any { it.acceptedTurnId == null } && !isRunning -> ComposerActionKind.Waiting
        trimmed.isEmpty() && attachments.isEmpty() && !attachmentLoading ->
            if (isRunning) ComposerActionKind.Stop else ComposerActionKind.Idle
        command != null && command.kind in listOf("read", "operation") -> ComposerActionKind.RuntimeCommand
        quickAction != null -> ComposerActionKind.QuickAction
        isRunning || queue != null -> ComposerActionKind.Enqueue
        else -> ComposerActionKind.Send
    }
    val action = ComposerAction(kind, queue?.text ?: trimmed, steer = queue?.steer == true)
    val reason = when {
        session?.isLocal == false -> "此会话来自外部渠道，只能查看"
        session == null -> "会话正在加载"
        kind == ComposerActionKind.Stop ->
            if (socketStatus != SocketStatus.Connected || runtime.run?.isTerminal != false) "会话正在连接，请稍后停止" else null
        kind in listOf(ComposerActionKind.Idle, ComposerActionKind.Waiting) -> null
        kind == ComposerActionKind.QuickAction -> when {
            attachments.isNotEmpty() || attachmentLoading -> "快捷操作不支持附件，请先移除附件"
            !canCompose -> "请稍后再使用此操作"
            else -> null
        }
        kind == ComposerActionKind.Enqueue && (attachments.isNotEmpty() || attachmentLoading) ->
            "排队和插话仅支持文字，请在当前任务结束后发送附件"
        kind == ComposerActionKind.Enqueue && action.text.isBlank() -> "请输入要加入队列的内容"
        kind == ComposerActionKind.Enqueue && (nativeSkill || command != null || queue == null && selector != null && session.runtimeType !in listOf(null, "model") && controls == null) ->
            "请等当前任务结束后执行此命令"
        kind == ComposerActionKind.Enqueue && action.steer && !isRunning ->
            "当前任务暂不支持插话"
        kind == ComposerActionKind.RuntimeCommand && (attachments.isNotEmpty() || attachmentLoading) -> "运行命令不支持附件"
        nativeSkill && (attachments.isNotEmpty() || attachmentLoading) -> "Skill 调用不支持附件，请移除附件后再发送"
        kind == ComposerActionKind.RuntimeCommand && isRunning && (command?.kind == "operation" || session.runtimeType == "grok-build") ->
            "请等当前任务结束后执行此命令"
        !canSend -> "会话正在连接，请稍后发送"
        else -> null
    }
    return action.copy(blockedReason = reason)
}

/** Completion only replaces the slash selector; choosing a skill never sends it. */
internal fun runtimeCommandSuggestions(text: String, commands: List<RuntimeCommand>): List<RuntimeCommand> {
    val query = slashMenuQuery(text) ?: return emptyList()
    return commands.filter { it.name?.takeIf(String::isNotBlank)?.startsWith(query, ignoreCase = true) == true }
        .distinctBy { it.name }
}

/** Leading spaces are harmless; arguments or a newline finish selector completion. */
internal fun slashMenuQuery(text: String): String? {
    if ('\n' in text || '\r' in text) return null
    val selector = text.trimStart()
    return selector.takeIf { it.startsWith('/') && it.none(Char::isWhitespace) }?.drop(1)
}

internal fun nativeSkillSuggestions(text: String, skills: List<SkillCatalogItem>): List<SkillCatalogItem> {
    val query = slashMenuQuery(text) ?: return emptyList()
    return skills.filter { skill -> skill.name.isNotBlank() &&
        listOfNotNull(skill.name, skill.displayName, skill.description).any { it.contains(query, ignoreCase = true) }
    }.distinctBy { it.name }
}
