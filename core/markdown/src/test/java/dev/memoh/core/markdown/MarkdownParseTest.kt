package dev.memoh.core.markdown

import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Link
import org.commonmark.node.OrderedList
import org.commonmark.node.StrongEmphasis
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser-level checks.
 *
 * The Compose layout itself needs a device, but the parse — which is where a
 * renderer silently drops content — is testable here.
 */
class MarkdownParseTest {

    @Test fun `unsafe link schemes remain readable without a launch action`() {
        val text = MemohMarkdown.inline(parse("[example](javascript:alert(1))").firstChild, null, androidx.compose.ui.graphics.Color.Blue)
        assertEquals("example", text.text)
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }

    @Test fun `link icon shares the same target as the text instead of a separate tap area`() {
        val text = MemohMarkdown.inline(parse("[Memoh](https://app.memoh.net)").firstChild, null,
            androidx.compose.ui.graphics.Color.Blue, decorateLinks = true)
        val link = text.getLinkAnnotations(0, text.length).single()
        assertEquals(0, link.start)
        assertEquals(text.length, link.end)
        assertTrue(text.text.endsWith("Memoh"))
    }

    @Test fun `links are actionable Compose annotations including table cells`() {
        var opened: String? = null
        val paragraph = parse("[**Memoh**](https://app.memoh.net)").firstChild
        val text = MemohMarkdown.inline(paragraph, { opened = it }, androidx.compose.ui.graphics.Color.Blue)
        val link = text.getLinkAnnotations(0, text.length).single().item as androidx.compose.ui.text.LinkAnnotation.Url
        assertEquals("https://app.memoh.net", link.url)
        link.linkInteractionListener!!.onClick(link)
        assertEquals(link.url, opened)
        assertEquals("Memoh", text.text)
    }

    @Test fun `bare URLs and email addresses become links`() {
        val links = collect(parse("https://app.memoh.net and user@example.com"), Link::class.java)
        assertEquals(listOf("https://app.memoh.net", "mailto:user@example.com"), links.map { it.destination })
    }

    @Test fun `task list markers preserve checked and unchecked states and nested tasks`() {
        val markers = collect(parse("- [ ] First\n- [x] Second\n  - [X] Nested"),
            org.commonmark.ext.task.list.items.TaskListItemMarker::class.java)
        assertEquals(listOf(false, true, true), markers.map { it.isChecked })
        assertEquals(listOf("First", "Second", "Nested"),
            collect(parse("- [ ] First\n- [x] Second\n  - [X] Nested"), org.commonmark.node.Text::class.java).map { it.literal })
    }

    private fun parse(md: String) = MemohMarkdown.parse(md)

    /**
     * Collects every node of type [T] in the tree.
     *
     * The caller passes the type explicitly rather than relying on a reified
     * parameter, because a local recursive function cannot live inside an
     * inline function.
     */
    private fun <T : org.commonmark.node.Node> collect(
        node: org.commonmark.node.Node,
        type: Class<T>,
    ): List<T> {
        val found = mutableListOf<T>()
        fun walk(n: org.commonmark.node.Node) {
            var child = n.firstChild
            while (child != null) {
                if (type.isInstance(child)) found.add(type.cast(child))
                walk(child)
                child = child.next
            }
        }
        walk(node)
        return found
    }

    @Test
    fun `a plain paragraph parses`() {
        val doc = parse("hello world")
        assertTrue(doc is Document)
        assertEquals(1, collect(doc, org.commonmark.node.Paragraph::class.java).size)
    }

    @Test
    fun `headings carry their level`() {
        val doc = parse("# One\n\n## Two\n\n### Three")
        val headings = collect(doc, Heading::class.java)
        assertEquals(listOf(1, 2, 3), headings.map { it.level })
    }

    @Test
    fun `bold italic and inline code are recognised`() {
        val doc = parse("**bold** and *italic* and `code`")
        assertEquals(1, collect(doc, StrongEmphasis::class.java).size)
        assertEquals(1, collect(doc, org.commonmark.node.Emphasis::class.java).size)
        assertEquals(1, collect(doc, Code::class.java).size)
    }

    @Test
    fun `a link carries its destination`() {
        val doc = parse("[Memoh](https://memoh.ai)")
        val links = collect(doc, Link::class.java)
        assertEquals(1, links.size)
        assertEquals("https://memoh.ai", links.first().destination)
    }

    @Test
    fun `bullet and ordered lists parse with their start number`() {
        val bullets = collect(parse("- a\n- b\n- c"), BulletList::class.java)
        assertEquals(1, bullets.size)

        val ordered = collect(parse("3. three\n4. four"), OrderedList::class.java)
        assertEquals(1, ordered.size)
        // The renderer honours the author's start number rather than forcing 1.
        assertEquals(3, ordered.first().startNumber)
    }

    @Test
    fun `a fenced code block keeps its language and literal body`() {
        val doc = parse("```kotlin\nval x = 1\n```")
        val blocks = collect(doc, FencedCodeBlock::class.java)
        assertEquals(1, blocks.size)
        assertEquals("kotlin", blocks.first().info)
        assertEquals("val x = 1\n", blocks.first().literal)
    }

    @Test
    fun `a gfm table parses into rows and cells`() {
        val md = """
            | Name | Value |
            | --- | --- |
            | a | 1 |
            | b | 2 |
        """.trimIndent()
        val tables = collect(parse(md), TableBlock::class.java)
        assertEquals("the tables extension must be enabled", 1, tables.size)

        val rows = collect(tables.first(), org.commonmark.ext.gfm.tables.TableRow::class.java)
        assertEquals(3, rows.size)
        val header = collect(tables.first(), org.commonmark.ext.gfm.tables.TableHead::class.java)
        assertEquals(1, header.size)
    }

    @Test
    fun `strikethrough parses when the extension is enabled`() {
        val doc = parse("~~gone~~")
        assertEquals(1, collect(doc, org.commonmark.ext.gfm.strikethrough.Strikethrough::class.java).size)
    }

    @Test
    fun `a block quote parses`() {
        val doc = parse("> quoted text")
        assertEquals(1, collect(doc, org.commonmark.node.BlockQuote::class.java).size)
    }

    @Test
    fun `an image is recognised so its alt text can be surfaced`() {
        val doc = parse("![a cat](https://example.com/cat.png)")
        assertEquals(1, collect(doc, org.commonmark.node.Image::class.java).size)
    }

    @Test
    fun `raw html does not break the parse`() {
        val doc = parse("<div>raw</div>\n\nnormal text")
        // Whatever the parser does with it, the surrounding prose survives.
        assertTrue(collect(doc, org.commonmark.node.Paragraph::class.java).isNotEmpty())
    }

    @Test
    fun `an unclosed fence still yields a code block`() {
        // Streaming delivers partial markdown; the tail must render as code
        // rather than disappearing until the closing fence arrives.
        val doc = parse("```python\nprint(1)")
        assertEquals(1, collect(doc, FencedCodeBlock::class.java).size)
    }

    @Test
    fun `a partial table during streaming does not throw`() {
        val doc = parse("| a | b |\n| --- |")
        assertTrue(doc is Document)
    }

    @Test
    fun `empty input parses to an empty document`() {
        val doc = parse("")
        assertTrue(doc is Document)
        assertEquals(null, doc.firstChild)
    }

    @Test
    fun `nested lists parse`() {
        val doc = parse("- outer\n  - inner\n- outer2")
        assertTrue(collect(doc, BulletList::class.java).size >= 2)
    }
}
