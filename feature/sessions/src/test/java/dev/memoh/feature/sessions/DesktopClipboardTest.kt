package dev.memoh.feature.sessions

import org.junit.Assert.*
import org.junit.Test

class DesktopClipboardTest {
    @Test fun `display text limit counts UTF8 bytes and rejects NUL without stripping text`() {
        assertTrue(validDesktopClipboard("中文🙂\n原样粘贴"))
        assertTrue(validDesktopClipboard("中".repeat(16 * 1024)))
        assertFalse(validDesktopClipboard("中".repeat(16 * 1024) + "a"))
        assertFalse(validDesktopClipboard("\u0000"))
        assertTrue(validDesktopClipboard(""))
    }
}
