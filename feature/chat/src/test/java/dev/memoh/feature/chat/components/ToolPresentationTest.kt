package dev.memoh.feature.chat.components

import dev.memoh.core.model.UIMessage
import dev.memoh.core.model.UIReasoningTiming
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ToolPresentationTest {
    @Test fun historicalExternalOutputsRemainReadableWithoutJsonWrappers() {
        for ((raw, expected) in listOf(
            """{"output":"old command output","exitCode":0}""" to "old command output",
            "\"plain command output\"" to "plain command output",
            """[{"type":"text","text":"block output"}]""" to "block output",
            """{"content":"search source text","results":[]}""" to "search source text",
            """{"isError":true,"content":[{"type":"text","text":"search failed"}]}""" to "search failed")) {
            assertEquals(expected, toolOutputText(UIMessage(0, "tool", output = Json.parseToJsonElement(raw))))
        }
    }
    @Test fun descriptiveExecTitlesAndFileTargetsDoNotExposeRawJson() {
        val exec = UIMessage(0, "tool", name = "exec", input = Json.parseToJsonElement("""{"command":"git status --short","description":"检查工作区状态"}"""))
        assertEquals("检查工作区状态", toolDisplay(exec).label)
        val read = UIMessage(1, "tool", name = "read", input = Json.parseToJsonElement("""{"path":"/data/project/app.kt"}"""))
        assertEquals("读取 app.kt", toolDisplay(read).label)
        assertEquals("正在准备下一步", toolDisplay(exec.copy(input = null, running = true)).label)
    }

    @Test fun failedStructuredResultsPreserveTheirDiagnosticsWithoutGuessingFromText() {
        val failed = UIMessage(0, "tool", output = Json.parseToJsonElement("""{"structuredContent":{"isError":true,"content":[{"type":"text","text":"permission denied"}]}}"""))
        assertTrue(toolResultFailed(failed))
        assertEquals("permission denied", toolOutputText(failed))
        assertFalse(toolResultFailed(failed.copy(output = Json.parseToJsonElement("""{"content":[{"type":"text","text":"error count: 0"}]}"""))))
        val logs = failed.copy(output = Json.parseToJsonElement("""{"structuredContent":{"stdout":"done","stderr":"warning","exit_code":1}}"""))
        assertEquals("done\nwarning", toolOutputText(logs))
        assertEquals("1", toolResult(logs).string("exit_code"))
    }

    @Test fun summariesCountFileOperationsAndSearchesWithoutCountingReasoningAsATool() {
        val tools = listOf("read", "web_search", "exec", "read", "web_fetch").mapIndexed { i, name -> UIMessage(i, "tool", name = name) }
        assertEquals("已探索" to "2 次文件操作、2 次搜索、1 条命令",
            processSummary(listOf(UIMessage(7, "reasoning")) + tools, active = false))
    }

    @Test fun unifiedDiffCountsExcludeFileHeadersAndThoughtLabelsUseRealTiming() {
        assertEquals(1 to 1, diffCounts("--- a/Main.kt\n+++ b/Main.kt\n@@ -1 +1 @@\n-old\n+new\n context"))
        val reasoning = UIMessage(0, "reasoning", reasoningTiming = UIReasoningTiming(3500))
        assertEquals("已思考 4 秒", reasoningLabel(reasoning, false))
        assertEquals("正在思考如何处理", reasoningLabel(reasoning, true))
        assertEquals("思考了几秒", reasoningLabel(reasoning.copy(reasoningTiming = null), false))
    }

    @Test fun patchesSplitByHunkLengthsAndMalformedDiffsKeepTheRawFallback() {
        val diff = "--- a/one.kt\n+++ b/one.kt\n@@ -1 +1 @@\n--- comment\n+++ replacement\n--- a/two.kt\n+++ b/two.kt\n@@ -1 +1 @@\n-old\n+new"
        val message = UIMessage(0, "tool", name = "apply_patch", diff = diff)
        val files = patchFileDiffs(message)
        assertEquals(listOf("one.kt", "two.kt"), files.map { it.path })
        assertEquals(1 to 1, diffCounts(files.first().diff))
        assertTrue(patchFileDiffs(message.copy(diff = diff.replace("@@ -1 +1 @@", "@@ -8,5 +8,5 @@"))).isEmpty())
        assertTrue(patchFileDiffs(message.copy(output = Json.parseToJsonElement("""{"isError":true}"""))).isEmpty())
    }
}
