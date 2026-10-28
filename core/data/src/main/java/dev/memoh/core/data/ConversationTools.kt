package dev.memoh.core.data

import dev.memoh.core.model.UIAttachment
import dev.memoh.core.model.UITurn
import kotlinx.serialization.Serializable

/** A user-defined prompt and the bot normally used for shared content. */
@Serializable
data class ShareQuickTask(val id: String, val name: String, val prompt: String, val botId: String)

/** A saved reply and its attachment references, independent of history-cache eviction. */
@Serializable
data class SavedChatItem(
    val botId: String,
    val sessionId: String,
    val turnKey: String,
    val text: String,
    val timestamp: String = "",
    val attachments: List<UIAttachment> = emptyList(),
    val provisionalTurnId: String? = null,
)

/** A live turn may later acquire a server message ID; persisted records keep their exact ID. */
fun resolveSavedChatTurn(item: SavedChatItem, turns: List<UITurn>): UITurn? =
    turns.firstOrNull { it.listKey == item.turnKey } ?: item.provisionalTurnId?.let { id ->
        val role = item.turnKey.substringBefore(':')
        turns.lastOrNull { it.role == role && it.turnId == id }
    }
