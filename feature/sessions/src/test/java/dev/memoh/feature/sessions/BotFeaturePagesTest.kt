package dev.memoh.feature.sessions

import dev.memoh.core.model.MemoryEntry
import dev.memoh.core.model.WorkspaceDocument
import dev.memoh.core.model.WorkspaceFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotFeaturePagesTest {
    @Test
    fun `returning to memory keeps results and search without another initial load`() {
        val pages = BotFeaturePages()
        val memory = BotFeatureState(botId = "bot", feature = BotFeature.Memory,
            hasLoaded = true, query = "偏好", memories = listOf(MemoryEntry(id = "1", memory = "喜欢中文")))
        pages.save(memory, 1)
        pages.save(BotFeatureState(botId = "bot", feature = BotFeature.Usage, hasLoaded = true), 1)

        val restored = requireNotNull(pages.get("bot", BotFeature.Memory, 1))
        assertEquals(memory.memories, restored.memories)
        assertEquals("偏好", restored.query)
        assertTrue(restored.hasLoaded)
        assertFalse(restored.loading)
    }

    @Test
    fun `bots have separate pages and account changes discard old contents`() {
        val pages = BotFeaturePages()
        pages.save(BotFeatureState(botId = "a", query = "A", hasLoaded = true), 1)
        pages.save(BotFeatureState(botId = "b", query = "B", hasLoaded = true), 1)
        assertEquals("A", pages.get("a", BotFeature.Memory, 1)?.query)
        assertEquals("B", pages.get("b", BotFeature.Memory, 1)?.query)

        assertNull(pages.get("a", BotFeature.Memory, 2))
        assertNull(pages.get("b", BotFeature.Memory, 2))
        pages.save(BotFeatureState(botId = "a", query = "new account"), 2)
        assertEquals("new account", pages.get("a", BotFeature.Memory, 2)?.query)
    }

    @Test
    fun `switching away during refresh keeps a file draft without a stuck loading state`() {
        val pages = BotFeaturePages()
        val file = WorkspaceFile(name = "notes.md", path = "/data/notes.md")
        pages.save(BotFeatureState(botId = "bot", feature = BotFeature.Files,
            hasLoaded = true, loading = true, busy = true, error = "old error", notice = "old notice",
            files = listOf(file), selectedFile = file,
            fileDocument = WorkspaceDocument(file.path, content = "saved", revision = "revision"),
            fileEditing = true, fileDraft = "unsaved changes"), 1)

        val restored = requireNotNull(pages.get("bot", BotFeature.Files, 1))
        assertEquals("unsaved changes", restored.fileDraft)
        assertEquals(file, restored.selectedFile)
        assertEquals(listOf(file), restored.files)
        assertTrue(restored.fileEditing)
        assertTrue(restored.hasLoaded)
        assertFalse(restored.loading)
        assertFalse(restored.busy)
        assertNull(restored.error)
        assertNull(restored.notice)
    }

    @Test
    fun `an interrupted first load remains eligible for a skeleton on return`() {
        val pages = BotFeaturePages()
        pages.save(BotFeatureState(botId = "bot", feature = BotFeature.Apps, loading = true), 1)
        val restored = requireNotNull(pages.get("bot", BotFeature.Apps, 1))
        assertFalse(restored.hasLoaded)
    }
}
