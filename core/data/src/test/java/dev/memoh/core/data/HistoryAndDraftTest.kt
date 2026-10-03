package dev.memoh.core.data

import dev.memoh.core.model.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HistoryAndDraftTest {
    @get:Rule val temp = TemporaryFolder()
    private val json = Json { ignoreUnknownKeys = true }
    @Test fun `history persists readable messages and isolates account team and bot`() = runBlocking {
        val root = temp.newFolder()
        val store = ChatHistoryStore(root, json)
        val turns = listOf(UITurn("u", role = "user", text = "question"), UITurn("a", role = "assistant", messages = listOf(UIMessage(1, "text", "答案"))))
        store.save("account", "team", "bot", Session("session", title = "Conversation"), turns)
        val reloaded = ChatHistoryStore(root, json)
        assertEquals(turns, reloaded.read("account", "team", "bot", "session")!!.turns)
        assertEquals(1, reloaded.list("account", "team", "答案").size)
        assertTrue(reloaded.list("other", "team").isEmpty())
        assertTrue(reloaded.list("account", "other").isEmpty())
        assertNull(reloaded.read("account", "team", "other", "session"))
    }
    @Test fun `history range and disabled cache are respected`() = runBlocking {
        val store = ChatHistoryStore(temp.newFolder(), json)
        store.setOptions("a", "t", HistoryCacheOptions(sessions = 10))
        repeat(11) { store.save("a", "t", "b", Session("$it"), (0..500).map { n -> UITurn("$n", role = "user", text = "$n") }) }
        assertEquals(10, store.list("a", "t").size)
        assertNull(store.read("a", "t", "b", "0"))
        assertEquals("1", store.read("a", "t", "b", "10")!!.turns.first().text)
        store.setOptions("a", "t", HistoryCacheOptions(false, 10))
        store.save("a", "t", "b", Session("disabled"), emptyList())
        assertNull(store.read("a", "t", "b", "disabled"))
        store.clear("a", "t")
        assertTrue(store.list("a", "t").isEmpty())
        assertFalse(store.options("a", "t").enabled)
    }
    @Test fun `attachment bytes survive URI loss and ordered clear does not restore sent files`() = runBlocking {
        val root = temp.newFolder()
        val store = AttachmentDraftStore(root, json)
        val file = ChatAttachment("file", "data:text/plain;base64,bWVtb2g=", "text/plain", "draft.txt")
        store.save("a", "t", "b", "s", listOf(file))
        assertEquals(listOf(file), store.read("a", "t", "b", "s"))
        assertEquals(listOf(file), AttachmentDraftStore(root, json).read("a", "t", "b", "s"))
        assertTrue(store.read("a", "other", "b", "s").isEmpty())
        assertTrue(store.read("other", "t", "b", "s").isEmpty())
        store.save("a", "t", "b", "s", emptyList())
        assertTrue(store.read("a", "t", "b", "s").isEmpty())
    }
}
