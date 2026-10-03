package dev.memoh.core.markdown

data class MathExpression(val source: String, val display: Boolean)
internal data class MathDocument(val markdown: String, val expressions: Map<String, MathExpression>)
private val mathPattern = Regex("""\$\$(.+?)\$\$|\\\[(.+?)\\\]|\\\((.+?)\\\)|(?<![\w$])\$(?!\s)([^$\n]+?)(?<!\s)\$(?![\w$])""", RegexOption.DOT_MATCHES_ALL)

private val codeParser = org.commonmark.parser.Parser.builder().includeSourceSpans(org.commonmark.parser.IncludeSourceSpans.BLOCKS_AND_INLINES).build()

/** Protect TeX escaping from CommonMark; literal code blocks keep their original text. */
internal fun protectMath(markdown: String): MathDocument {
    if (!mathPattern.containsMatchIn(markdown)) return MathDocument(markdown, emptyMap())
    var prefix = "MEMOHMATHEXPRESSION"
    while (prefix in markdown) prefix += "X"
    val expressions = linkedMapOf<String, MathExpression>()
    val protected = mutableListOf<IntRange>()
    val tree = codeParser.parse(markdown)
    fun collect(node: org.commonmark.node.Node) {
        if (node is org.commonmark.node.Code || node is org.commonmark.node.FencedCodeBlock || node is org.commonmark.node.IndentedCodeBlock || node is org.commonmark.node.HtmlBlock) {
            node.sourceSpans.forEach { protected += it.inputIndex until (it.inputIndex + it.length) }
        } else {
            var child = node.firstChild
            while (child != null) { collect(child); child = child.next }
        }
    }
    collect(tree)
    val result = mathPattern.replace(markdown) { match ->
        if (protected.any { it.first <= match.range.last && it.last >= match.range.first } ||
            (match.range.first > 0 && markdown[match.range.first - 1] == '\\' && match.value.startsWith('$'))) return@replace match.value
        val token = "$prefix${expressions.size}END"
        val source = match.groups.drop(1).firstNotNullOf { it?.value }
        expressions[token] = MathExpression(source, match.groups[1] != null || match.groups[2] != null)
        token
    }
    return MathDocument(result, expressions)
}
