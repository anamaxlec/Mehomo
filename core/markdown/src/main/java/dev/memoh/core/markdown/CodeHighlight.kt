package dev.memoh.core.markdown

/** Small lexical highlighter: never alters the code, including incomplete fences. */
internal enum class CodeTokenKind { Keyword, String, Comment, Number, Function, Property }
internal data class CodeToken(val start: Int, val end: Int, val kind: CodeTokenKind)

internal fun codeTokens(code: String, language: String): List<CodeToken> {
    val lang = language.substringBefore(' ').lowercase()
    if (lang.isBlank() || lang in setOf("text", "plaintext", "plain", "log", "output", "markdown", "md")) return emptyList()
    val hashComments = lang in setOf("python", "py", "bash", "sh", "shell", "zsh", "yaml", "yml", "ruby", "rb", "toml", "dockerfile", "powershell", "ps1", "r")
    val cComments = lang !in setOf("json", "jsonc", "yaml", "yml", "toml", "python", "py", "bash", "sh", "shell", "zsh", "sql", "html", "xml") || lang == "jsonc"
    val keywords = when (lang) {
        "python", "py" -> "False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield"
        "json", "jsonc" -> "true false null"
        "yaml", "yml", "toml" -> "true false null yes no on off"
        "bash", "sh", "shell", "zsh" -> "if then else elif fi for while do done case esac in function return export local readonly echo exit"
        "sql" -> "SELECT FROM WHERE JOIN LEFT RIGHT INNER OUTER ON AS AND OR NOT NULL INSERT INTO VALUES UPDATE SET DELETE CREATE TABLE INDEX DROP ALTER GROUP BY ORDER HAVING LIMIT OFFSET DISTINCT UNION ALL CASE WHEN THEN ELSE END WITH ASC DESC"
        else -> "abstract as async await break case catch class const continue data default defer do else enum export extends false final finally for fun func function if implements import in interface internal is let namespace new nil null object override package private protected public readonly return sealed static struct super suspend switch this throw throws trait true try type typeof val var void when while yield"
    }.split(' ').toSet()
    val result = mutableListOf<CodeToken>()
    fun nextChar(start: Int): Char? {
        var cursor = start
        while (cursor < code.length && code[cursor].isWhitespace()) cursor++
        return code.getOrNull(cursor)
    }
    var i = 0
    while (i < code.length) {
        val start = i
        val kind: CodeTokenKind? = when {
            (hashComments && code[i] == '#') || (cComments && code.startsWith("//", i)) || (lang == "sql" && code.startsWith("--", i)) -> {
                i = code.indexOf('\n', i).takeIf { it >= 0 } ?: code.length
                CodeTokenKind.Comment
            }
            cComments && code.startsWith("/*", i) -> {
                i = code.indexOf("*/", i + 2).takeIf { it >= 0 }?.plus(2) ?: code.length
                CodeTokenKind.Comment
            }
            (lang == "html" || lang == "xml") && code.startsWith("<!--", i) -> {
                i = code.indexOf("-->", i + 4).takeIf { it >= 0 }?.plus(3) ?: code.length
                CodeTokenKind.Comment
            }
            code[i] == '\'' || code[i] == '"' || code[i] == '`' -> {
                val quote = code[i]
                val triple = lang in setOf("python", "py", "kotlin", "kt") && code.startsWith("$quote$quote$quote", i)
                val delimiter = if (triple) "$quote$quote$quote" else "$quote"
                i += delimiter.length
                while (i < code.length) {
                    if (code[i] == '\\') { i = (i + 2).coerceAtMost(code.length); continue }
                    if (code.startsWith(delimiter, i)) { i += delimiter.length; break }
                    i++
                }
                if (lang in setOf("json", "jsonc") && nextChar(i) == ':') CodeTokenKind.Property else CodeTokenKind.String
            }
            code[i].isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                i++
                while (i < code.length && (code[i].isDigit() || code[i] in ".xXabcdefABCDEF_")) i++
                CodeTokenKind.Number
            }
            code[i].isLetter() || code[i] == '_' -> {
                i++
                while (i < code.length && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                val word = code.substring(start, i)
                val after = nextChar(i)
                when {
                    word in keywords || (lang == "sql" && word.uppercase() in keywords) -> CodeTokenKind.Keyword
                    after == '(' -> CodeTokenKind.Function
                    lang in setOf("yaml", "yml", "toml") && after != null && after in ":=" -> CodeTokenKind.Property
                    else -> null
                }
            }
            else -> { i++; null }
        }
        if (kind != null) result += CodeToken(start, i, kind)
    }
    return result
}
