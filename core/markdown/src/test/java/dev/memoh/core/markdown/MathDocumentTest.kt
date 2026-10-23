package dev.memoh.core.markdown

import org.junit.Assert.*
import org.junit.Test

class MathDocumentTest {
    @Test fun `chat prices and single dollar pairs stay literal while explicit math renders`() {
        val prices = "US\$3–6/月；优质 US\$10–30/月，\$3–\$6/小时；\$x^2\$"
        assertEquals(prices, protectMath(prices, singleDollarMath = false).markdown)
        assertTrue(protectMath(prices, singleDollarMath = false).expressions.isEmpty())
        val text = prices + "，公式 \\(x^2\\)，\\[y\\] 和 \$\$z\$\$。"
        val chat = protectMath(text, singleDollarMath = false)
        assertTrue(chat.markdown.startsWith(prices))
        assertEquals(listOf("x^2", "y", "z"), chat.expressions.values.map { it.source })
        assertEquals(listOf(false, true, true), chat.expressions.values.map { it.display })
        assertEquals("x^2", protectMath("\$x^2\$").expressions.values.single().source)
    }
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
