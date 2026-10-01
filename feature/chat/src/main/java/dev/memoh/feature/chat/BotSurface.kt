package dev.memoh.feature.chat

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.DesktopWindows
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * The bot's other surfaces, as listed in the chat screen's overflow menu.
 *
 * These match the web client's menu exactly (对话 / 终端 / 浏览器 / 桌面) because
 * they answer the same question — "where else can I see this bot working" — and
 * a client that offered a different set would make the same bot feel like two
 * different products.
 *
 * 对话 is the screen you are already on; it is listed for parity and because the
 * menu would otherwise look like it is missing an entry.
 */
enum class BotSurface(
    val label: String,
    val icon: ImageVector,
    val implemented: Boolean,
    /** Endpoint backing the surface, shown while it is not built. */
    val endpoint: String = "",
) {
    Chat(
        label = "对话",
        icon = Icons.AutoMirrored.Filled.Chat,
        implemented = true,
    ),
    Terminal(
        label = "终端",
        icon = Icons.Filled.Terminal,
        implemented = true,
        endpoint = "/bots/{id}/container/terminal/ws",
    ),
    Browser(
        label = "浏览器",
        icon = Icons.Filled.Public,
        implemented = true,
        endpoint = "/bots/{id}/container/browser/sessions",
    ),
    Desktop(
        label = "桌面",
        icon = Icons.Filled.DesktopWindows,
        implemented = true,
        endpoint = "/bots/{id}/container/display",
    ),
}
