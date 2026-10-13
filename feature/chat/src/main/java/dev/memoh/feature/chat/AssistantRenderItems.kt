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
    messages.forEachIndexed { index, message ->
        if (message.type == UIMessage.TYPE_STATUS && message.content.isBlank() && message.name.isNullOrBlank()) return@forEachIndexed
        val previous = items.lastOrNull()
        if (message.isProcessMessage && previous?.isProcess == true) {
            items[items.lastIndex] = previous.copy(messages = previous.messages + message, lastIndex = index)
        } else {
            items += AssistantRenderItem(keys[index], listOf(message), index)
        }
    }
    return items
}
