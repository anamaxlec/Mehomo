package dev.memoh.core.markdown

import androidx.compose.runtime.staticCompositionLocalOf
import java.net.URI

sealed interface WorkspaceLink {
    data class File(val path: String) : WorkspaceLink
    data class Preview(val address: String) : WorkspaceLink
    data class External(val url: String) : WorkspaceLink
    data class Native(val url: String) : WorkspaceLink
}

val LocalWorkspaceLinkHandler = staticCompositionLocalOf<((WorkspaceLink) -> Unit)?> { null }

/** Never resolve a relative filename against the App, or open the phone's localhost. */
fun classifyWorkspaceLink(raw: String): WorkspaceLink? {
    val value = raw.trim()
    if (value.isBlank() || value.contains('\\') || value.any { it.code < 32 || it.code == 127 }) return null
    val uri = runCatching { URI(if (value.startsWith("//")) "https:$value" else value) }.getOrNull()
    if (value.startsWith("/") && !value.startsWith("//")) {
        val path = uri?.path ?: return null
        return path.takeIf { it.isNotBlank() && '\\' !in it && it.none { c -> c.code < 32 || c.code == 127 } }?.let(WorkspaceLink::File)
    }
    val localValue = if (Regex("^(localhost|127\\.0\\.0\\.1|\\[::1]):[0-9]+", RegexOption.IGNORE_CASE).containsMatchIn(value)) "http://$value" else value
    val local = runCatching { URI(localValue) }.getOrNull()
    val localHost = local?.host?.lowercase() in listOf("localhost", "127.0.0.1", "[::1]", "::1")
    if (localHost) {
        if (local == null || local.scheme?.lowercase() !in listOf("http", "https") || local.port !in 1..65535 || local.userInfo != null) return null
        return WorkspaceLink.Preview("localhost:${local.port}" + (local.rawPath?.ifBlank { "/" } ?: "/") +
            (local.rawQuery?.let { "?$it" } ?: "") + (local.rawFragment?.let { "#$it" } ?: ""))
    }
    return when (uri?.scheme?.lowercase()) {
        "http", "https" -> if (!uri.host.isNullOrBlank() && uri.userInfo == null) WorkspaceLink.External(uri.toASCIIString()) else null
        "mailto", "tel" -> if (!uri.schemeSpecificPart.isNullOrBlank()) WorkspaceLink.Native(value) else null
        else -> null
    }
}

internal class MarkdownLinkHandler(val workspaceEnabled: Boolean, val click: (String) -> Unit) : (String) -> Unit {
    override fun invoke(url: String) = click(url)
}
