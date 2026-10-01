package dev.memoh.core.markdown

import org.junit.Assert.*
import org.junit.Test

class CodeHighlightTest {
    @Test fun `Python keeps strings and comments separate from keywords and function names`() {
        val code = "def build(session_id):\n    # return is a comment\n    return {\"session\": session_id, \"value\": 42}\n"
        val tokens = codeTokens(code, "python")
        fun values(kind: CodeTokenKind) = tokens.filter { it.kind == kind }.map { code.substring(it.start, it.end) }
        assertEquals(listOf("def", "return"), values(CodeTokenKind.Keyword))
        assertEquals(listOf("build"), values(CodeTokenKind.Function))
        assertEquals(listOf("# return is a comment"), values(CodeTokenKind.Comment))
        assertEquals(listOf("\"session\"", "\"value\""), values(CodeTokenKind.String))
        assertEquals(listOf("42"), values(CodeTokenKind.Number))
    }
    @Test fun `JSON distinguishes object keys from escaped string values`() {
        val code = """{"name": "escaped \"true\" // string", "ok": true}"""
        val tokens = codeTokens(code, "json")
        assertEquals(listOf("\"name\"", "\"ok\""), tokens.filter { it.kind == CodeTokenKind.Property }.map { code.substring(it.start, it.end) })
        assertEquals(1, tokens.count { it.kind == CodeTokenKind.String })
        assertEquals(1, tokens.count { it.kind == CodeTokenKind.Keyword })
        assertEquals(0, tokens.count { it.kind == CodeTokenKind.Comment })
    }
    @Test fun `incomplete streaming literals and comments retain the complete visible suffix`() {
        for (code in listOf("const x = \"not finished", "/* unfinished comment", "val x = \"\"\"multiline\npartial")) {
            val tokens = codeTokens(code, "kotlin")
            assertEquals(code.length, tokens.last().end)
            assertTrue(tokens.all { it.start >= 0 && it.end <= code.length && it.end > it.start })
        }
        assertTrue(codeTokens("def plain(): return true", "text").isEmpty())
    }
}
