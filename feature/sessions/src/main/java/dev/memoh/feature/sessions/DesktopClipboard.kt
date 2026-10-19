package dev.memoh.feature.sessions

/** The display protocol caps UTF-8 clipboard text at 48 KiB. */
internal fun validDesktopClipboard(text: String): Boolean = '\u0000' !in text && text.toByteArray(Charsets.UTF_8).size <= 48 * 1024
