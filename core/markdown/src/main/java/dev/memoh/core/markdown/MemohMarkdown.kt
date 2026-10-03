package dev.memoh.core.markdown

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Checkbox
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.AlternateEmail
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.runtime.rememberCoroutineScope
import android.content.ClipData
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

/**
 * A Compose Markdown renderer.
 *
 * Written against Compose directly rather than wrapping a View-based renderer:
 * assistant answers are the main reading surface, so they need native text
 * layout (selection, font scaling, dynamic colour) and no WebView in the tree.
 *
 * Supported: paragraphs, headings, bold/italic/strikethrough, inline code,
 * links, ordered/unordered lists, block quotes, fenced and indented code,
 * thematic breaks, GFM tables/tasks/autolinks, and images loaded through the
 * caller's authenticated media loader.
 */
object MemohMarkdown {
    private val inlineMath = androidx.compose.runtime.staticCompositionLocalOf<Map<String, MathExpression>> { emptyMap() }
    private val htmlRenderer = org.commonmark.renderer.html.HtmlRenderer.builder().extensions(listOf(TablesExtension.create(), StrikethroughExtension.create())).escapeHtml(true).sanitizeUrls(true).build()

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create(),
            AutolinkExtension.create(), TaskListItemsExtension.create()))
        .build()

    fun parse(markdown: String): Node = parser.parse(markdown)

    @Composable
    fun Code(code: String, language: String = "") { CodeBlock(code, language) }

    /**
     * Renders [markdown]. Parsing is memoised by the caller via [remember];
     * during streaming the text changes on every frame, so this stays cheap by
     * only re-parsing when the string actually differs.
     */
    @Composable
    fun Markdown(
        markdown: String,
        modifier: Modifier = Modifier,
        onLinkClick: ((String) -> Unit)? = null,
    ) {
        val math = remember(markdown) { protectMath(markdown) }
        val document = remember(math.markdown) { parse(math.markdown) }
        val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
        val linkHandler = onLinkClick ?: { url: String -> runCatching { uriHandler.openUri(url) }; Unit }
        androidx.compose.runtime.CompositionLocalProvider(inlineMath provides math.expressions) {
        SelectionContainer {
            Column(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NodeChildren(document, linkHandler)
            }
        }
        }
    }

    @Composable
    private fun NodeChildren(node: Node, onLinkClick: ((String) -> Unit)?) {
        var child = node.firstChild
        while (child != null) {
            Block(child, onLinkClick)
            child = child.next
        }
    }

    @Composable
    private fun Block(node: Node, onLinkClick: ((String) -> Unit)?) {
        when (node) {
            is Paragraph -> ParagraphBlock(node, onLinkClick)
            is Heading -> HeadingBlock(node, onLinkClick)
            is BulletList -> ListBlock(node, ordered = false, onLinkClick)
            is OrderedList -> ListBlock(node, ordered = true, onLinkClick)
            is FencedCodeBlock -> CodeBlock(node.literal.orEmpty(), node.info.orEmpty())
            is IndentedCodeBlock -> CodeBlock(node.literal.orEmpty(), "")
            is BlockQuote -> BlockQuoteBlock(node, onLinkClick)
            is ThematicBreak -> HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            is TableBlock -> TableBlock(node, onLinkClick)
            is HtmlBlock -> {
                // Raw HTML is shown as text rather than interpreted: rendering it
                // would need a browser engine, and silently dropping it would
                // hide content the model intended the user to see.
                val raw = node.literal.orEmpty().trim()
                if (raw.startsWith("<svg", true)) {
                    RichContent(RichFormat.Svg, raw)
                } else if (raw.isNotEmpty()) {
                    Text(
                        text = raw,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            is Document -> NodeChildren(node, onLinkClick)
            else -> NodeChildren(node, onLinkClick)
        }
    }

    @Composable
    private fun ParagraphBlock(node: Paragraph, onLinkClick: ((String) -> Unit)?) {
        val expressions = inlineMath.current
        val html = remember(node) { htmlRenderer.render(node) }
        if (expressions.keys.any { it in html } && generateSequence(node.firstChild) { it.next }.none { it is Image }) {
            RichContent(RichFormat.Paragraph, html, expressions = expressions, onLinkClick = onLinkClick)
            return
        }
        // Images are block surfaces; split only their containing paragraph so
        // prose before/after an inline image is still visible and selectable.
        var child = node.firstChild
        if (generateSequence(child) { it.next }.any { it is Image }) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                var start = child
                while (child != null) {
                    if (child is Image) {
                        if (start != child) InlineRange(node, onLinkClick, start, child)
                        MarkdownImage(child.destination.orEmpty(), plainText(child))
                        start = child.next
                    }
                    child = child.next
                }
                if (start != null) InlineRange(node, onLinkClick, start, null)
            }
            return
        }
        InlineRange(node, onLinkClick, node.firstChild, null)
    }

    @Composable
    private fun InlineRange(node: Node, onLinkClick: ((String) -> Unit)?, start: Node?, end: Node?) {
        val expressions = inlineMath.current
        val html = remember(node, start, end) { generateSequence(start) { it.next }.takeWhile { it != end }.joinToString("") { htmlRenderer.render(it) } }
        if (expressions.keys.any { it in html }) {
            RichContent(RichFormat.Paragraph, "<p>$html</p>", expressions = expressions, onLinkClick = onLinkClick)
            return
        }
        Text(
            text = inline(node, onLinkClick, linkColor = MaterialTheme.colorScheme.primary, start = start, end = end, decorateLinks = true),
            inlineContent = linkIcons(),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }

    @Composable
    private fun HeadingBlock(node: Heading, onLinkClick: ((String) -> Unit)?) {
        val expressions = inlineMath.current
        val html = remember(node) { htmlRenderer.render(node) }
        if (expressions.keys.any { it in html }) {
            RichContent(RichFormat.Paragraph, html, expressions = expressions, onLinkClick = onLinkClick)
            return
        }
        val linkColor = MaterialTheme.colorScheme.primary
        val style = when (node.level) {
            1 -> MaterialTheme.typography.headlineSmall
            2 -> MaterialTheme.typography.titleLarge
            3 -> MaterialTheme.typography.titleMedium
            else -> MaterialTheme.typography.titleSmall
        }
        Text(
            text = inline(node, onLinkClick, linkColor, decorateLinks = true),
            inlineContent = linkIcons(),
            style = style,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = if (node.level <= 2) 8.dp else 4.dp),
        )
    }

    @Composable
    private fun ListBlock(
        node: Node,
        ordered: Boolean,
        onLinkClick: ((String) -> Unit)?,
    ) {
        val start = (node as? OrderedList)?.startNumber ?: 1
        var index = 0
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            var item = node.firstChild
            while (item != null) {
                if (item is ListItem) {
                    val task = item.firstChild as? TaskListItemMarker
                    Row(modifier = Modifier.fillMaxWidth().then(if (task != null) Modifier.semantics(mergeDescendants = true) {
                        role = Role.Checkbox
                        toggleableState = if (task.isChecked) ToggleableState.On else ToggleableState.Off
                    } else Modifier)) {
                        if (task != null) Box(Modifier.width(26.dp).padding(top = 5.dp)) {
                            Box(Modifier.size(16.dp).clip(RoundedCornerShape(5.dp))
                                .background(if (task.isChecked) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface)
                                .border(1.dp, if (task.isChecked) MaterialTheme.colorScheme.primary.copy(alpha = .5f) else MaterialTheme.colorScheme.outline,
                                    RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                                if (task.isChecked) Icon(Icons.Outlined.Check, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.primary)
                            }
                        } else Text(
                            text = if (ordered) "${start + index}." else "•",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(if (ordered) 28.dp else 18.dp),
                        )
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // A list item's children are blocks; nested lists
                            // recurse through the same path.
                            var sub = item.firstChild
                            while (sub != null) {
                                Block(sub, onLinkClick)
                                sub = sub.next
                            }
                        }
                    }
                    index++
                }
                item = item.next
            }
        }
    }

    @Composable
    private fun BlockQuoteBlock(node: BlockQuote, onLinkClick: ((String) -> Unit)?) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .padding(end = 0.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.outlineVariant),
            )
            Column(
                modifier = Modifier.padding(start = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                var child = node.firstChild
                while (child != null) {
                    Block(child, onLinkClick)
                    child = child.next
                }
            }
        }
    }

    @Composable
    private fun CodeBlock(code: String, language: String) {
        when (language.trim().lowercase()) {
            "mermaid" -> { RichContent(RichFormat.Mermaid, code); return }
            "svg" -> { RichContent(RichFormat.Svg, code); return }
            "math", "latex", "tex" -> { RichContent(RichFormat.Math, code); return }
        }
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        var copied by remember(code) { mutableStateOf(false) }
        val colors = MaterialTheme.colorScheme
        val displayCode = code.trimEnd('\n')
        val tokens = remember(displayCode, language) { codeTokens(displayCode, language) }
        val highlighted = remember(displayCode, tokens, colors) {
            AnnotatedString.Builder(displayCode).apply {
                tokens.forEach { token -> addStyle(SpanStyle(color = when (token.kind) {
                    CodeTokenKind.Keyword -> colors.error
                    CodeTokenKind.Function, CodeTokenKind.Property -> colors.primary
                    CodeTokenKind.String -> colors.tertiary
                    CodeTokenKind.Number -> colors.error
                    CodeTokenKind.Comment -> colors.onSurfaceVariant
                }, fontStyle = if (token.kind == CodeTokenKind.Comment) FontStyle.Italic else null), token.start, token.end) }
            }.toAnnotatedString()
        }
        val scroll = rememberScrollState()
        LaunchedEffect(copied) { if (copied) { delay(1800); copied = false } }
        Column(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .border(.5.dp, colors.outlineVariant, RoundedCornerShape(12.dp))
                    .background(colors.surfaceContainerLowest),
            ) {
                Column {
                    Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = language.ifBlank { "代码" },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("代码", displayCode)))
                                copied = true
                            }
                        }, modifier = Modifier.size(32.dp)) {
                            Icon(if (copied) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                                if (copied) "代码已复制" else "复制代码", Modifier.size(16.dp),
                                tint = if (copied) colors.primary else colors.onSurfaceVariant)
                        }
                    }
                    Text(
                        text = highlighted,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 22.sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        softWrap = false,
                        modifier = Modifier
                            .horizontalScroll(scroll)
                            .padding(start = 12.dp, end = 12.dp, bottom = 12.dp, top = 2.dp),
                    )
                    ScrollHint(scroll)
                }
            }
        }
    }

    /**
     * GFM table. Rendered as aligned columns inside a horizontal scroller so a
     * wide table stays readable instead of wrapping into mush.
     */
    @Composable
    private fun TableBlock(table: TableBlock, onLinkClick: ((String) -> Unit)?) {
        val expressions = inlineMath.current
        val html = remember(table) { htmlRenderer.render(table) }
        if (expressions.keys.any { it in html }) {
            RichContent(RichFormat.Paragraph, html, expressions = expressions, onLinkClick = onLinkClick)
            return
        }
        val linkColor = MaterialTheme.colorScheme.primary
        val rows = remember(table) { collectRows(table) }
        if (rows.isEmpty()) return
        val columnCount = rows.maxOf { it.size }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .border(.5.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surface),
        ) {
            val cellWidth = if (columnCount <= 3) (maxWidth / columnCount).coerceAtLeast(100.dp) else 164.dp
            val scroll = rememberScrollState()
            Column {
            Box(modifier = Modifier.horizontalScroll(scroll)) {
                Column {
                    rows.forEachIndexed { rowIndex, cells ->
                        Row(
                            modifier = Modifier.height(IntrinsicSize.Min).background(
                                if (cells.any { it.isHeader }) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surface),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            repeat(columnCount) { columnIndex ->
                                val cell = cells.getOrNull(columnIndex)
                                Text(
                                    text = cell?.let { inline(it.node, onLinkClick, linkColor, decorateLinks = true) } ?: AnnotatedString(""),
                                    inlineContent = linkIcons(),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontWeight = if (cell?.isHeader == true) {
                                            FontWeight.SemiBold
                                        } else {
                                            FontWeight.Normal
                                        },
                                    ),
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = when (cell?.node?.alignment) {
                                        TableCell.Alignment.CENTER -> TextAlign.Center
                                        TableCell.Alignment.RIGHT -> TextAlign.End
                                        else -> TextAlign.Start
                                    },
                                    modifier = Modifier
                                        .width(cellWidth - if (columnIndex < columnCount - 1) .5.dp else 0.dp)
                                        .padding(horizontal = 12.dp, vertical = 12.dp),
                                )
                                if (columnIndex < columnCount - 1) VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = .5.dp)
                            }
                        }
                        if (rowIndex < rows.lastIndex) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = .5.dp)
                        }
                    }
                }
            }
            ScrollHint(scroll)
            }
        }
    }

    @Composable
    private fun ScrollHint(scroll: ScrollState) {
        if (scroll.maxValue > 0 && scroll.maxValue < Int.MAX_VALUE) {
            val track = MaterialTheme.colorScheme.outlineVariant
            val thumb = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f)
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 4.dp).height(3.dp)) {
                val width = size.width * (scroll.viewportSize.toFloat() / (scroll.viewportSize + scroll.maxValue)).coerceIn(.1f, 1f)
                val x = (size.width - width) * scroll.value / scroll.maxValue.toFloat()
                drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
                drawRoundRect(thumb, topLeft = androidx.compose.ui.geometry.Offset(x, 0f), size = androidx.compose.ui.geometry.Size(width, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height))
            }
        }
    }

    @Composable
    private fun linkIcons(): Map<String, InlineTextContent> = listOf("external", "file", "mail").associate { kind ->
        "memoh-link-$kind" to InlineTextContent(Placeholder(18.sp, 16.sp, PlaceholderVerticalAlign.TextCenter)) {
            Icon(when (kind) { "file" -> Icons.Outlined.Description; "mail" -> Icons.Outlined.AlternateEmail; else -> Icons.Outlined.Public },
                null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }

    private data class TableCellText(val node: TableCell, val isHeader: Boolean)

    private fun collectRows(table: TableBlock): List<List<TableCellText>> {
        val rows = mutableListOf<List<TableCellText>>()
        var section = table.firstChild
        while (section != null) {
            val isHeader = section is TableHead
            var row = section.firstChild
            while (row != null) {
                if (row is TableRow) {
                    val cells = mutableListOf<TableCellText>()
                    var cell = row.firstChild
                    while (cell != null) {
                        if (cell is TableCell) {
                            cells.add(TableCellText(cell, isHeader))
                        }
                        cell = cell.next
                    }
                    rows.add(cells)
                }
                row = row.next
            }
            section = section.next
        }
        return rows
    }

    /** Flattens a subtree to plain text — used for table cells. */
    private fun plainText(node: Node): String {
        val builder = StringBuilder()
        fun walk(n: Node) {
            var child = n.firstChild
            while (child != null) {
                when (child) {
                    is Text -> builder.append(child.literal)
                    is Code -> builder.append(child.literal)
                    else -> walk(child)
                }
                child = child.next
            }
        }
        walk(node)
        return builder.toString()
    }

    /**
     * Builds an [AnnotatedString] for a subtree, applying inline styles.
     * Links are annotated with a URL tag so a future tap handler can route them
     * without the renderer needing a navigation dependency.
     */
    internal fun inline(
        node: Node,
        onLinkClick: ((String) -> Unit)?,
        linkColor: androidx.compose.ui.graphics.Color,
        start: Node? = node.firstChild,
        end: Node? = null,
        decorateLinks: Boolean = false,
    ): AnnotatedString {
        val builder = AnnotatedString.Builder()
        builder.appendInlineChildren(
            node = node,
            onLinkClick = onLinkClick,
            linkColor = linkColor,
            bold = false,
            italic = false,
            strike = false,
            code = false,
            start = start,
            end = end,
            decorateLinks = decorateLinks,
        )
        return builder.toAnnotatedString()
    }

    /**
     * Walks a subtree appending styled text. Written as an extension on the
     * builder so nested `withStyle`/`pushStringAnnotation` calls resolve against
     * the builder rather than needing an explicit receiver at each site.
     */
    private fun AnnotatedString.Builder.appendInlineChildren(
        node: Node,
        onLinkClick: ((String) -> Unit)?,
        linkColor: androidx.compose.ui.graphics.Color,
        bold: Boolean,
        italic: Boolean,
        strike: Boolean,
        code: Boolean,
        start: Node? = node.firstChild,
        end: Node? = null,
        decorateLinks: Boolean = false,
    ) {
        val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        var child = start
        while (child != null && child != end) {
            val current = child
            when (current) {
                is Text -> withStyle(
                    SpanStyle(
                        fontWeight = if (bold) FontWeight.SemiBold else null,
                        fontStyle = if (italic) FontStyle.Italic else null,
                        textDecoration = if (strike) TextDecoration.LineThrough else null,
                        fontFamily = if (code) FontFamily.Monospace else null,
                    ),
                ) { append(current.literal) }

                is Code -> withStyle(codeStyle) { append(current.literal) }

                is StrongEmphasis -> appendInlineChildren(current, onLinkClick, linkColor, true, italic, strike, code, decorateLinks = decorateLinks)
                is Emphasis -> appendInlineChildren(current, onLinkClick, linkColor, bold, true, strike, code, decorateLinks = decorateLinks)
                is Strikethrough ->
                    appendInlineChildren(current, onLinkClick, linkColor, bold, italic, true, code, decorateLinks = decorateLinks)

                is Link -> {
                    val url = current.destination.orEmpty()
                    val parsed = runCatching { java.net.URI(url) }.getOrNull()
                    val scheme = parsed?.scheme?.lowercase()
                    val safe = parsed != null && (scheme == null || scheme in setOf("http", "https", "mailto", "tel"))
                    if (!safe) {
                        appendInlineChildren(current, onLinkClick, linkColor, bold, italic, strike, code)
                        child = child.next
                        continue
                    }
                    pushLink(LinkAnnotation.Url(url,
                        styles = TextLinkStyles(style = SpanStyle(color = linkColor),
                            hoveredStyle = SpanStyle(textDecoration = TextDecoration.Underline),
                            pressedStyle = SpanStyle(background = linkColor.copy(alpha = .12f))),
                        linkInteractionListener = onLinkClick?.let { handler -> LinkInteractionListener { handler(url) } },
                    ))
                    if (decorateLinks) appendInlineContent("memoh-link-${if (scheme == "mailto") "mail" else if (scheme == null) "file" else "external"}")
                    appendInlineChildren(current, onLinkClick, linkColor, bold, italic, strike, code)
                    pop()
                }

                is Image -> {
                    // Images need an authenticated fetch; the alt text at least
                    // tells the reader something was there.
                    val alt = plainText(current)
                    append(if (alt.isBlank()) "[图片]" else "[图片: $alt]")
                }

                is SoftLineBreak -> append("\n")
                is HardLineBreak -> append("\n")
                is HtmlInline -> append(current.literal)

                else -> appendInlineChildren(current, onLinkClick, linkColor, bold, italic, strike, code, decorateLinks = decorateLinks)
            }
            child = child.next
        }
    }
}
