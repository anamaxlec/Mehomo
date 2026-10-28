package dev.memoh.feature.chat

import dev.memoh.core.model.UIMessage
import dev.memoh.feature.chat.components.isCompletedQuestion

internal data class AssistantRenderItem(
    val key: String,
    val messages: List<UIMessage>,
    val lastIndex: Int,
) {
    val isProcess: Boolean get() = messages.all { it.isProcessMessage }
}

private val UIMessage.isProcessMessage: Boolean
    get() = type == UIMessage.TYPE_REASONING || type == UIMessage.TYPE_TOOL && !isCompletedQuestion(this)

/** Text and decisions keep their place; only consecutive process steps are folded together. */
internal fun assistantRenderItems(turnId: String, messages: List<UIMessage>): List<AssistantRenderItem> {
    val keys = assistantBlockKeys(turnId, messages)
    val items = mutableListOf<AssistantRenderItem>()
    var processMessages: MutableList<UIMessage>? = null
    var processKey = ""
    var processLastIndex = -1
    fun finishProcess() {
        processMessages?.let { items += AssistantRenderItem(processKey, it.toList(), processLastIndex) }
        processMessages = null
    }
    messages.forEachIndexed { index, message ->
        if (message.type == UIMessage.TYPE_STATUS && message.content.isBlank() && message.name.isNullOrBlank()) return@forEachIndexed
        if (message.isProcessMessage) {
            val group = processMessages ?: mutableListOf<UIMessage>().also {
                processMessages = it
                processKey = keys[index]
            }
            group += message
            processLastIndex = index
        } else {
            finishProcess()
            items += AssistantRenderItem(keys[index], listOf(message), index)
        }
    }
    finishProcess()
    return items
}
