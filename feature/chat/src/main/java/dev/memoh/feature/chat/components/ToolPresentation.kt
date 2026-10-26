package dev.memoh.feature.chat.components

import dev.memoh.core.model.UIMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.Locale
import kotlin.math.roundToLong

internal enum class ToolKind(val fragment: String, val active: String, val settled: String) {
    File("次文件操作", "探索中", "已探索"),
    Search("次搜索", "探索中", "已探索"),
    Edit("次文件操作", "修改中", "已修改"),
    Command("条命令", "运行中", "已运行"),
    Message("条消息", "发送中", "已发送"),
    Schedule("个定时任务", "调整中", "已调整"),
    Media("个媒体文件", "生成中", "已生成"),
    Agent("个 Agent", "委派中", "已委派"),
    Other("步", "处理中", "已处理"),
}

internal data class ToolDisplay(val action: String, val target: String, val kind: ToolKind) {
    val label: String get() = listOf(action, target).filter(String::isNotBlank).joinToString(" ")
}

internal fun toolName(message: UIMessage): String = message.name.orEmpty().substringAfterLast('.').substringAfterLast("__").lowercase(Locale.ROOT)
internal fun JsonElement?.objectValue(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
internal fun JsonObject.string(vararg names: String): String = names.firstNotNullOfOrNull {
    (this[it] as? JsonPrimitive)?.takeUnless { value -> value == JsonNull }?.content?.takeIf(String::isNotBlank)
}.orEmpty()

internal fun toolDisplay(message: UIMessage): ToolDisplay {
    val input = message.input.objectValue()
    val name = toolName(message)
    val path = input.string("path", "file_path", "filePath")
    val filename = path.trimEnd('/').substringAfterLast('/').ifBlank { path }
    val description = input.string("description").trim()
    val query = input.string("query", "q", "keyword", "pattern")
    val url = input.string("url")
    val (action, target, kind) = when (name) {
        "read", "read_file", "open_file" -> Triple("读取", filename, ToolKind.File)
        "list", "list_dir", "list_files", "glob" -> Triple("列出目录内容", path, ToolKind.File)
        "write", "write_file" -> Triple("写入", filename, ToolKind.Edit)
        "edit", "edit_file", "apply_patch" -> Triple(if (name == "apply_patch") "应用补丁" else "编辑",
            if (name == "apply_patch") patchFileDiffs(message).let { files ->
                when (files.size) { 0 -> filename; 1 -> files.single().path.substringAfterLast('/'); else -> "${files.size} 个文件" }
            } else filename, ToolKind.Edit)
        "exec", "exec_command", "bash", "shell", "terminal", "run_command", "execute_command" ->
            Triple(if (description.isNotBlank()) "" else "执行", description.ifBlank { input.string("command", "cmd") }, ToolKind.Command)
        "web_search", "search", "grep", "rg", "find" -> Triple(if (name == "web_search") "搜索网络" else "搜索", query, ToolKind.Search)
        "web_fetch", "fetch" -> Triple("抓取", runCatching { java.net.URI(url).host?.removePrefix("www.") }.getOrNull() ?: url, ToolKind.Search)
        "search_memory" -> Triple("搜索记忆", query, ToolKind.Search)
        "search_messages" -> Triple("搜索历史", query, ToolKind.Search)
        "list_models" -> Triple("列出模型", "", ToolKind.Search)
        "list_workdirs" -> Triple("列出工作目录", "", ToolKind.Search)
        "list_execution_locations" -> Triple("查看可用的电脑", "", ToolKind.Search)
        "list_acp_agents" -> Triple("列出 ACP Agent", input.string("agent_id"), ToolKind.Search)
        "list_skills" -> Triple("列出技能", "", ToolKind.Search)
        "use_skill" -> Triple("使用技能", input.string("name", "skill_name"), ToolKind.Other)
        "get_contacts" -> Triple("查看联系人", input.string("platform"), ToolKind.Search)
        "list_sessions", "get_messages" -> Triple(if (name == "list_sessions") "列出会话" else "读取会话消息", input.string("session_id"), ToolKind.Search)
        "send", "send_message" -> Triple("发送消息", input.string("target", "agent_id"), ToolKind.Message)
        "react" -> Triple("添加表情", input.string("emoji"), ToolKind.Message)
        "list_schedule", "get_schedule" -> Triple(if (name == "list_schedule") "列出计划" else "查看计划", input.string("name", "id"), ToolKind.Search)
        "create_schedule", "update_schedule", "delete_schedule" -> Triple(when (name) {
            "create_schedule" -> "创建计划"; "update_schedule" -> "更新计划"; else -> "删除计划"
        }, input.string("name", "id"), ToolKind.Schedule)
        "generate_image", "generate_video", "transcribe_audio", "speak" -> Triple(when (name) {
            "generate_image" -> "生成图像"; "generate_video" -> "生成视频"; "transcribe_audio" -> "转录音频"; else -> "朗读"
        }, input.string("prompt", "path", "text"), ToolKind.Media)
        "spawn_agent", "list_agents" -> Triple(if (name == "spawn_agent") "派遣智能体" else "列出智能体", input.string("name", "task_name"), ToolKind.Agent)
        "browser_action", "browser_observe", "computer_action", "computer_observe" -> Triple(
            if (name.startsWith("browser")) "浏览器" else "桌面", listOf(input.string("action"), url).filter(String::isNotBlank).joinToString(" "), ToolKind.Other)
        "ask_user" -> Triple("询问用户", message.userInput?.questions?.firstOrNull()?.text ?: input.string("question"), ToolKind.Other)
        "wait", "wait_until" -> Triple("等待", input.string("task_id", "duration"), ToolKind.Other)
        else -> Triple(message.name?.takeIf(String::isNotBlank) ?: "工具调用", input.string("description", "path", "query", "url"), ToolKind.Other)
    }
    if (message.running && message.input == null) return ToolDisplay("正在准备下一步", "", kind)
    return ToolDisplay(action, target.lineSequence().firstOrNull().orEmpty(), kind)
}

internal fun processSummary(messages: List<UIMessage>, active: Boolean): Pair<String, String> {
    val tools = messages.filter { it.type == UIMessage.TYPE_TOOL }
    if (tools.isEmpty()) return (if (active) "正在思考如何处理" else "已思考") to ""
    if (tools.size == 1) return toolDisplay(tools.single()).label to ""
    val displays = tools.map(::toolDisplay)
    val counts = displays.groupingBy { if (it.kind == ToolKind.Search) ToolKind.File else it.kind }.eachCount()
    val phase = ToolKind.entries.maxBy { counts.getOrDefault(it, 0) }
    val fragments = displays.groupingBy { it.kind.fragment }.eachCount()
    val details = ToolKind.entries.map { it.fragment }.distinct().filter { it in fragments }
        .joinToString("、") { fragment -> "${fragments.getValue(fragment)} $fragment" }
    return (if (active) phase.active else phase.settled) to details
}

internal fun toolResult(message: UIMessage): JsonObject {
    val result = message.output.objectValue()
    return (result["structuredContent"] as? JsonObject)?.takeIf { it.isNotEmpty() } ?: result
}

internal fun toolResultFailed(message: UIMessage): Boolean =
    listOf(message.output.objectValue(), toolResult(message)).any { (it["isError"] as? JsonPrimitive)?.booleanOrNull == true }

private val prettyJson = Json { prettyPrint = true }
internal fun renderToolValue(value: JsonElement): String = if (value is JsonPrimitive) {
    if (value == JsonNull) "" else value.content
} else prettyJson.encodeToString(JsonElement.serializer(), value)

private fun contentText(value: JsonElement?): String = when (value) {
    is JsonPrimitive -> renderToolValue(value)
    is JsonArray -> value.mapNotNull { part -> part.objectValue().takeIf { it.string("type") == "text" }?.string("text") }.joinToString("\n")
    else -> ""
}

internal fun toolOutputText(message: UIMessage): String {
    val output = message.output ?: return ""
    if (output is JsonPrimitive) return renderToolValue(output)
    if (output is JsonArray) return contentText(output).ifBlank { renderToolValue(output) }
    val result = toolResult(message)
    val text = contentText(message.output.objectValue()["content"]).ifBlank { contentText(result["content"]) }
    val logs = listOf(result.string("stdout").ifBlank { result.string("output") }, result.string("stderr"))
        .filter(String::isNotBlank).joinToString("\n")
    if (toolResultFailed(message)) return listOf(logs, text).filter(String::isNotBlank).distinct().joinToString("\n")
        .ifBlank { renderToolValue(output) }
    return logs.ifBlank { text }.ifBlank { renderToolValue(output) }
}

internal fun isCompletedQuestion(message: UIMessage): Boolean {
    if (message.type != UIMessage.TYPE_TOOL || toolName(message) != "ask_user") return false
    val terminal = setOf("submitted", "canceled", "cancelled", "expired", "failed")
    val result = toolResult(message)
    return message.userInput?.status in terminal || result.string("status") in terminal || result["answers"] is JsonArray
}

internal fun reasoningLabel(message: UIMessage, streaming: Boolean): String = when {
    streaming -> "正在思考如何处理"
    (message.reasoningTiming?.durationMs ?: 0) >= 1000 -> "已思考 ${(message.reasoningTiming!!.durationMs / 1000.0).roundToLong()} 秒"
    else -> "思考了几秒"
}

internal fun diffCounts(diff: String?): Pair<Int, Int> {
    var inHunk = false
    var oldLeft = 0
    var newLeft = 0
    var add = 0
    var remove = 0
    diff.orEmpty().lineSequence().forEach { line ->
        hunkHeader.find(line)?.let { match ->
            inHunk = true
            oldLeft = match.groupValues[1].toIntOrNull() ?: 1
            newLeft = match.groupValues[2].toIntOrNull() ?: 1
        }
        if (!inHunk && (line.startsWith("---") || line.startsWith("+++"))) return@forEach
        if (line.startsWith('+')) add++ else if (line.startsWith('-')) remove++
        if (inHunk) {
            when (line.firstOrNull()) {
                '+' -> newLeft--
                '-' -> oldLeft--
                ' ' -> { oldLeft--; newLeft-- }
            }
            if (oldLeft <= 0 && newLeft <= 0) inHunk = false
        }
    }
    return add to remove
}

internal data class ToolFileDiff(val path: String, val oldPath: String, val diff: String, val deleted: Boolean = false)
private val hunkHeader = Regex("^@@ -\\d+(?:,(\\d+))? \\+\\d+(?:,(\\d+))? @@")

/** Hunk lengths disambiguate file headers from removed '--' / added '++' content. */
internal fun patchFileDiffs(message: UIMessage): List<ToolFileDiff> {
    if (toolName(message) != "apply_patch" || toolResultFailed(message) || message.diff.isNullOrBlank()) return emptyList()
    val lines = message.diff!!.removeSuffix("\n").split('\n')
    val files = mutableListOf<ToolFileDiff>()
    var index = 0
    fun path(header: String, prefix: String): String = header.drop(4).let { if (it == "/dev/null") "" else it.removePrefix(prefix) }
    while (index < lines.size) {
        val start = index
        val from = lines[index]
        val to = lines.getOrNull(index + 1).orEmpty()
        if (!from.startsWith("--- ") || !to.startsWith("+++ ")) return emptyList()
        index += 2
        while (lines.getOrNull(index)?.startsWith("@@") == true) {
            val match = hunkHeader.find(lines[index++]) ?: return emptyList()
            var oldLeft = match.groupValues[1].toIntOrNull() ?: 1
            var newLeft = match.groupValues[2].toIntOrNull() ?: 1
            while (oldLeft > 0 || newLeft > 0) {
                val line = lines.getOrNull(index++) ?: return emptyList()
                when (line.firstOrNull()) {
                    '\\' -> continue
                    '-' -> oldLeft--
                    '+' -> newLeft--
                    ' ' -> { oldLeft--; newLeft-- }
                    else -> return emptyList()
                }
                if (oldLeft < 0 || newLeft < 0) return emptyList()
            }
            while (lines.getOrNull(index)?.startsWith('\\') == true) index++
        }
        val oldPath = path(from, "a/")
        val newPath = path(to, "b/")
        if (oldPath.isBlank() && newPath.isBlank()) return emptyList()
        files += ToolFileDiff(newPath.ifBlank { oldPath }, oldPath, lines.subList(start, index).joinToString("\n"), newPath.isBlank())
    }
    // The server may report deleted files without producing their diff.
    val result = toolResult(message)
    val changes = (result["changes"] ?: result["files"]) as? JsonArray
    changes.orEmpty().forEach { value ->
        val change = value.objectValue()
        val path = change.string("path").removePrefix("/")
        if (path.isBlank() || files.any { it.path == path || it.oldPath == path }) return@forEach
        val operation = change["kind"].objectValue().string("type").ifBlank { change.string("operation", "kind") }
        if (operation !in setOf("delete", "deleted")) return emptyList()
        files += ToolFileDiff(path, path, "", deleted = true)
    }
    return files
}
