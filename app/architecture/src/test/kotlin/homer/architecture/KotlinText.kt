package homer.architecture

/**
 * Just enough Kotlin lexing for the text-based rules: skips comments, character literals and string
 * literals (raw strings and `${…}` templates included), so a brace or quote inside them is not code.
 */
internal object KotlinText {
    /** Index just past the literal or comment starting at [start], or [start] when none starts there. */
    fun skipNonCode(
        text: String,
        start: Int,
    ): Int =
        when {
            text.startsWith("//", start) -> text.indexOf('\n', start).let { if (it < 0) text.length else it }
            text.startsWith("/*", start) -> text.indexOf("*/", start + 2).let { if (it < 0) text.length else it + 2 }
            text.startsWith("\"\"\"", start) -> skipString(text, start + 3, raw = true)
            text[start] == '"' -> skipString(text, start + 1, raw = false)
            text[start] == '\'' -> skipChar(text, start)
            else -> start
        }

    /** Index of the brace closing the one at [open], or the text end when it is unbalanced. */
    fun closingBrace(
        text: String,
        open: Int,
    ): Int {
        var depth = 0
        var i = open
        while (i < text.length) {
            val next = skipNonCode(text, i)
            if (next != i) {
                i = next
                continue
            }
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return i
            }
            i++
        }
        return text.length - 1
    }

    /**
     * [text] with every comment and literal content blanked, so a regex sees code only. Offsets and
     * newlines are kept, and so are a string's quotes: the source at the same range is its content.
     */
    fun codeOnly(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {
            val next = skipNonCode(text, i)
            if (next == i) {
                out.append(text[i++])
            } else {
                val segment = text.substring(i, next)
                val quotes =
                    if (segment.startsWith("\"\"\"")) {
                        3
                    } else if (segment.startsWith("\"")) {
                        1
                    } else {
                        0
                    }
                segment.forEachIndexed { k, c ->
                    out.append(if (c == '\n' || k < quotes || k >= segment.length - quotes) c else ' ')
                }
                i = next
            }
        }
        return out.toString()
    }

    /**
     * Index of the `{` or `=` opening the body of the function whose parameter list opens at or after
     * [from] in [code] (already [codeOnly]), or null when it has none (an abstract function). Brackets of
     * parameters, default values and generic or function return types are skipped.
     */
    fun bodyStart(
        code: String,
        from: Int,
    ): Int? {
        var i = code.indexOf('(', from).takeIf { it >= 0 } ?: return null
        // Angle brackets count only outside parentheses: inside them `<` and `>` may be comparisons.
        var parens = 0
        var angles = 0
        while (i < code.length) {
            if (code.startsWith("->", i) || code.startsWith(">=", i) || code.startsWith("==", i)) {
                i += 2
                continue
            }
            when (code[i]) {
                '(', '[' -> parens++
                ')', ']' -> parens--
                '<' -> if (parens == 0) angles++
                '>' -> if (parens == 0) angles--
                '{', '=' -> if (parens == 0 && angles == 0) return i
            }
            i++
        }
        return null
    }

    /** Lines of a function's body, from the line opening it through its last line; null without a body. */
    fun bodyLines(functionText: String): Int? {
        val code = codeOnly(functionText)
        val start = bodyStart(code, FUN.find(code)?.range?.last ?: 0) ?: return null
        return code
            .substring(start)
            .trimEnd()
            .lines()
            .size
    }

    private val FUN = Regex("""\bfun\b""")

    private fun skipString(
        text: String,
        from: Int,
        raw: Boolean,
    ): Int {
        var i = from
        while (i < text.length) {
            when {
                !raw && text[i] == '\\' -> i += 2
                text.startsWith("\${", i) -> i = closingBrace(text, i + 1) + 1
                raw && text.startsWith("\"\"\"", i) -> return i + 3 + text.substring(i + 3).takeWhile { it == '"' }.length
                !raw && text[i] == '"' -> return i + 1
                else -> i++
            }
        }
        return text.length
    }

    private fun skipChar(
        text: String,
        start: Int,
    ): Int {
        val close = text.indexOf('\'', start + if (text.getOrNull(start + 1) == '\\') 3 else 2)
        return if (close < 0 || close - start > 8) start + 1 else close + 1
    }
}
