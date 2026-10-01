package dev.memoh.core.model

import kotlinx.serialization.Serializable

@Serializable
data class WorkspaceFile(
    val name: String = "", val path: String = "", val size: Long = 0,
    val mode: String = "", val modTime: String = "", val isDir: Boolean = false,
)
@Serializable
data class WorkspaceListing(val path: String = "/data", val entries: List<WorkspaceFile> = emptyList())
@Serializable
data class WorkspaceDocument(val path: String, val content: String = "", val size: Long = 0, val revision: String = "")
@Serializable
data class WorkspaceFileResult(val ok: Boolean = false, val revision: String = "", val path: String? = null, val size: Long = 0)
