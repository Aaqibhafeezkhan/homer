package homer.app.models

/**
 * POSIX-style argv tokenizer. Splits a command string into arguments, respecting
 * single and double quotes and backslash escapes. Does NOT expand variables,
 * globs, or command substitutions — HOMER does not invoke a shell.
 */
object CmdArgsTokenizer {
    fun tokenize(input: String): List<String> {
        val state = State()
        input.forEach(state::accept)
        require(!state.inSingle && !state.inDouble) { "Unterminated quote in: $input" }
        require(!state.escape) { "Dangling backslash in: $input" }
        state.endToken()
        return state.result
    }

    /** The tokenizer's quote/escape state and the tokens read so far. */
    private class State {
        val result = mutableListOf<String>()
        private val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var escape = false
        private var hasToken = false

        fun accept(c: Char) {
            when {
                escape -> {
                    current.append(c)
                    escape = false
                    hasToken = true
                }

                inSingle -> {
                    acceptInSingle(c)
                }

                inDouble -> {
                    acceptInDouble(c)
                }

                else -> {
                    acceptUnquoted(c)
                }
            }
        }

        /** Inside single quotes everything is literal up to the closing quote. */
        private fun acceptInSingle(c: Char) {
            if (c == '\'') inSingle = false else current.append(c)
            hasToken = true
        }

        /** Inside double quotes a backslash escapes the next character; whitespace is literal. */
        private fun acceptInDouble(c: Char) {
            when (c) {
                '\\' -> {
                    escape = true
                }

                '"' -> {
                    inDouble = false
                    hasToken = true
                }

                else -> {
                    current.append(c)
                    hasToken = true
                }
            }
        }

        private fun acceptUnquoted(c: Char) {
            when {
                c == '\\' -> {
                    escape = true
                }

                c == '\'' -> {
                    inSingle = true
                    hasToken = true
                }

                c == '"' -> {
                    inDouble = true
                    hasToken = true
                }

                c.isWhitespace() -> {
                    endToken()
                }

                else -> {
                    current.append(c)
                    hasToken = true
                }
            }
        }

        /** Closes the token being read, if any; whitespace between tokens yields none. */
        fun endToken() {
            if (hasToken) {
                result.add(current.toString())
                current.clear()
                hasToken = false
            }
        }
    }
}
