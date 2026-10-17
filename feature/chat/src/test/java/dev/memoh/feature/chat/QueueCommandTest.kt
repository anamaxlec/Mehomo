package dev.memoh.feature.chat

import dev.memoh.core.model.Session
import dev.memoh.core.network.SocketStatus
import org.junit.Assert.*
import org.junit.Test

class QueueCommandTest {
    @Test fun `queue selectors preserve multiline content and symbols as text`() {
        val body = "line one\nline two $(literal); && | `text`"
        assertEquals(QueueCommand(body, false), parseQueueCommand(" /QUEUE $body "))
        assertEquals(QueueCommand(body, true), parseQueueCommand("/steer\n$body"))
        assertEquals(QueueCommand("", true), parseQueueCommand("/steer"))
    }

    @Test fun `similar prefixes and runtime-owned commands are not queue selectors`() {
        assertNull(parseQueueCommand("/steering hello"))
        assertNull(parseQueueCommand("/queueing hello"))
        assertNull(parseQueueCommand("hello /queue world"))
        assertNull(parseQueueCommand("/queue hello", listOf("queue")))
        assertEquals(QueueCommand("hello", false), parseQueueCommand("/queue hello", listOf("status")))
    }

    @Test fun `unresolved or restoring sessions disable composition while reconnecting remains editable`() {
        assertFalse(ChatUiState().canCompose)
        val resolved = ChatUiState(session = Session("s"), socketStatus = SocketStatus.Reconnecting)
        assertTrue(resolved.canCompose)
        assertFalse(resolved.canSend)
        assertFalse(resolved.copy(draftRestoring = true).canCompose)
        assertFalse(resolved.copy(session = Session("external", channelType = "telegram")).canCompose)
    }
}
