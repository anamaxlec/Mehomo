package dev.memoh.feature.sessions

import dev.memoh.core.model.*
import org.junit.Assert.*
import org.junit.Test

class FolderEditorTest {
    @Test fun nativeChildAndRemoteSelectionUseDifferentPathRules() {
        val native = FolderEditorState(name = "project", path = "/data", targets = listOf(WorkspaceTarget("native", kind = "native")))
        assertEquals("/data/project", native.resolvedPath)
        assertTrue(native.canSave)
        assertFalse(native.copy(name = "../escape").canSave)
        assertFalse(native.copy(name = "..").canSave)
        val remote = native.copy(targetId = "pc", targets = listOf(WorkspaceTarget("pc", kind = "remote", online = true)), path = "C:\\Users\\Alice\\project")
        assertEquals("C:\\Users\\Alice\\project", remote.resolvedPath)
        assertTrue(remote.canSave)
        assertFalse(remote.copy(targets = listOf(WorkspaceTarget("pc", kind = "remote", online = false))).canSave)
    }
    @Test fun renameDoesNotInventANewDirectoryOrTarget() {
        val folder = Workdir("w", name = "old", path = "/data/old", workspaceTargetId = "native")
        val editing = FolderEditorState(folder = folder, name = "new", path = folder.path!!)
        assertTrue(editing.canSave)
        assertEquals("/data/old", editing.path)
        assertEquals("native", editing.folder?.workspaceTargetId)
    }
}
