package dev.memoh.android

/**
 * Navigation destinations.
 *
 * Plain constants rather than a sealed graph: the app is a single stack — the
 * session list pushes the chat screen, and everything else is a sheet or dialog
 * layered over those two.
 *
 * The top-level sections switch rather than stack: they are peers of one
 * another, so selecting one from the bottom bar replaces the current section
 * instead of pushing onto it.
 */
object Routes {
    const val LOGIN = "login"

    /**
     * The signed-in shell.
     *
     * A single destination hosting all top-level sections: switching between
     * them is state, not navigation, so "back" from a section never walks
     * through the tabs the user visited.
     */
    const val MAIN = "main"

    const val FEATURE = "feature/{botId}/{feature}"
    fun feature(botId: String, feature: String) = "feature/${android.net.Uri.encode(botId)}/${android.net.Uri.encode(feature)}"
    const val WORKSPACE = "workspace/{botId}/{surface}"
    fun workspace(botId: String, surface: String) = "workspace/${android.net.Uri.encode(botId)}/${android.net.Uri.encode(surface)}"

    const val CHAT = "chat/{botId}/{sessionId}"

    const val ARG_BOT_ID = "botId"
    const val ARG_SESSION_ID = "sessionId"

    fun chat(botId: String, sessionId: String): String = "chat/$botId/$sessionId"
}
