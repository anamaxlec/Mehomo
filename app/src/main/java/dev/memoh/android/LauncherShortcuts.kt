package dev.memoh.android

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.util.Base64

/** Launcher shortcuts only carry an address; the app still checks the active account. */
object LauncherShortcuts {
    const val NEW_CHAT = "dev.memoh.android.action.NEW_CHAT"
    const val RECENT_CHAT = "dev.memoh.android.action.RECENT_CHAT"
    private const val OPEN_CHAT = "dev.memoh.android.action.OPEN_CHAT"
    private const val PREFS = "launcher_shortcuts"

    private fun key(account: String, team: String): String = listOf(account, team).joinToString(".") {
        Base64.encodeToString(it.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun openIntent(context: Context, account: String, bot: String, session: String): Intent =
        Intent(context, MainActivity::class.java).setAction(OPEN_CHAT)
            .putExtra("account_id", account).putExtra("bot_id", bot).putExtra("session_id", session)

    fun record(context: Context, account: String, team: String, bot: String, session: String) {
        if (account.isBlank() || bot.isBlank() || session.isBlank()) return
        val prefix = key(account, team)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val hadRecent = !prefs.getString("$prefix.bot", null).isNullOrBlank() && !prefs.getString("$prefix.session", null).isNullOrBlank()
        if (prefs.getString("$prefix.bot", null) == bot && prefs.getString("$prefix.session", null) == session) return
        prefs.edit().putString("$prefix.bot", bot).putString("$prefix.session", session).apply()
        if (!hadRecent) publish(context, account, team)
    }

    fun recent(context: Context, account: String, team: String): ChatTarget? {
        val prefix = key(account, team)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bot = prefs.getString("$prefix.bot", null)?.takeIf(String::isNotBlank) ?: return null
        val session = prefs.getString("$prefix.session", null)?.takeIf(String::isNotBlank) ?: return null
        return ChatTarget(account, bot, session)
    }

    fun publish(context: Context, account: String?, team: String?) {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        val icon = Icon.createWithResource(context, R.mipmap.ic_launcher)
        val shortcuts = mutableListOf(ShortcutInfo.Builder(context, "new-chat")
            .setShortLabel("新建会话").setIcon(icon)
            .setIntent(Intent(context, MainActivity::class.java).setAction(NEW_CHAT)).build())
        if (!account.isNullOrBlank()) {
            if (recent(context, account, team.orEmpty()) != null) {
                shortcuts += ShortcutInfo.Builder(context, "recent-chat")
                    .setShortLabel("继续最近任务").setIcon(icon)
                    .setIntent(Intent(context, MainActivity::class.java).setAction(RECENT_CHAT)).build()
            }
        }
        runCatching {
            if (manager.dynamicShortcuts.map { it.id to it.intent?.action } != shortcuts.map { it.id to it.intent?.action })
                manager.dynamicShortcuts = shortcuts
        }
    }

    fun pin(context: Context, account: String, bot: String, session: String, title: String): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (!manager.isRequestPinShortcutSupported || account.isBlank() || bot.isBlank() || session.isBlank()) return false
        val id = "chat." + key(account, "$bot\u0000$session")
        val shortcut = ShortcutInfo.Builder(context, id)
            .setShortLabel(title.take(30).ifBlank { "会话" })
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(openIntent(context, account, bot, session)).build()
        return runCatching { manager.requestPinShortcut(shortcut, null) }.getOrDefault(false)
    }
}
