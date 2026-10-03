package dev.memoh.core.markdown

import org.junit.Assert.*
import org.junit.Test

class MathDocumentTest {
    @Test fun `TeX escapes survive CommonMark inline and block delimiters`() {
        val document = protectMath("行内 \\(\\frac{a}{b}\\)\n\n\\[\\sum_{i=0}^{n} x_i\\]\n\n" + "$" + "x^2" + "$" + "\n\n" + "$$" + "\\int_0^1 x dx" + "$$")
        assertEquals(listOf("\\frac{a}{b}", "\\sum_{i=0}^{n} x_i", "x^2", "\\int_0^1 x dx"), document.expressions.values.map { it.source })
        assertEquals(listOf(false, true, false, true), document.expressions.values.map { it.display })
        assertFalse(document.markdown.contains("\\frac"))
    }
    @Test fun `fenced indented and multiple backtick code remain literal`() {
        val code = "```tex\n" + "$" + "x" + "$" + "\n```\n\n    \\(code\\)\n\n`` literal ` " + "$" + "y" + "$" + " ``"
        val document = protectMath(code + "\n\n公式 \\(real\\)")
        assertEquals(listOf("real"), document.expressions.values.map { it.source })
        assertTrue(document.markdown.startsWith(code))
    }
    @Test fun `ordinary currency and escaped dollar stay readable`() {
        val text = "价格 \\$20 和 $30；普通文字。"
        assertEquals(text, protectMath(text).markdown)
        assertTrue(protectMath(text).expressions.isEmpty())
    }
}
