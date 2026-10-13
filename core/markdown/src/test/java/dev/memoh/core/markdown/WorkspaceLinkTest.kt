package dev.memoh.core.markdown

import org.junit.Assert.*
import org.junit.Test

class WorkspaceLinkTest {
    @Test fun absoluteWorkspacePathsDropLineAnchorsAndKeepUnicodeAndPlus() {
        assertEquals(WorkspaceLink.File("/data/工程/a+b.kt"), classifyWorkspaceLink("/data/%E5%B7%A5%E7%A8%8B/a+b.kt#L10"))
        assertEquals(WorkspaceLink.File("/data/report.pdf"), classifyWorkspaceLink("/data/report.pdf?download=1"))
        assertNull(classifyWorkspaceLink("/data/%00secret"))
        assertNull(classifyWorkspaceLink("/data/%zz"))
    }
    @Test fun localhostPreviewsRequireAValidExplicitPort() {
        assertEquals(WorkspaceLink.Preview("localhost:5173/index.html?q=1#x"), classifyWorkspaceLink("http://127.0.0.1:5173/index.html?q=1#x"))
        assertEquals(WorkspaceLink.Preview("localhost:3000/"), classifyWorkspaceLink("http://[::1]:3000"))
        assertEquals(WorkspaceLink.Preview("localhost:8080/"), classifyWorkspaceLink("localhost:8080"))
        assertNull(classifyWorkspaceLink("http://localhost"))
        assertNull(classifyWorkspaceLink("http://localhost:0"))
        assertNull(classifyWorkspaceLink("http://localhost:99999"))
    }
    @Test fun relativeAndUnopenableLinksRemainPlainText() {
        for (link in listOf("summary.md", "../file.txt", "file:///tmp/private", "vscode://file/a", "javascript:alert(1)", "#L10", "https://", "https://x.test/\\evil"))
            assertNull(link, classifyWorkspaceLink(link))
        assertEquals(WorkspaceLink.External("https://example.test/a"), classifyWorkspaceLink("//example.test/a"))
        assertEquals(WorkspaceLink.Native("mailto:test@example.test"), classifyWorkspaceLink("mailto:test@example.test"))
    }
}
