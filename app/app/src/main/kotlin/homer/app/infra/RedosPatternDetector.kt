package homer.app.infra

/**
 * Load-time heuristic that flags regular-expression patterns whose structure is
 * prone to catastrophic backtracking (ReDoS).
 *
 * Homer feeds author-supplied `pattern`s from an agent's `inputSchema` to the
 * networknt JSON-Schema validator, which compiles them to `java.util.regex` and
 * matches them against caller-supplied request bodies. Unlike [ParamValidator]'s
 * `pattern` (bounded at match time by [BoundedRegex]'s interruptible char
 * sequence), the JSON-Schema library owns the `Matcher` and feeds it a plain
 * `String` Homer never sees, so the runtime bound cannot be applied — hence this
 * structural lint at agent load. See ADR-0040.
 *
 * Recognised evil-regex shapes:
 *  - nested unbounded quantifiers — a quantified group whose body is itself
 *    unbounded: `(X+)+`, `(X*)*`, `(X+)*`, `(X*)+`, `((X+))+`;
 *  - a quantified alternation whose branches overlap: `(a|a)*`, `(a|ab)*`,
 *    `(a|)*`, including when the alternation is non-capturing (`(?:a|ab)*`) or
 *    wrapped in nested groups (`((a|ab))*`).
 *
 * Group prefixes (`(?:`, `(?<name>`, `(?=`, `(?<=`, flag-scoped `(?i:`) are
 * normalised so the analysis sees the real group body rather than the prefix.
 *
 * It is deliberately conservative and, being a heuristic, is neither sound nor
 * complete: it can over-reject an unusual-but-safe pattern (e.g. an overlapping
 * alternation nested under a quantifier but anchored by surrounding literals)
 * and can still miss an exotic vulnerable one. An author who trips it can
 * restructure the pattern.
 */
internal object RedosPatternDetector {
    private class Group(
        val branchStarts: MutableList<Int>,
        var bodyHasUnbounded: Boolean = false,
        // Set when this group's body contains an overlapping alternation — either
        // its own top-level branches, or one carried up from a nested group — so an
        // enclosing quantifier flags e.g. ((a|ab))* the same as (a|ab)*.
        var overlapReason: String? = null,
    )

    private class Interval(
        val length: Int,
        val unbounded: Boolean,
    )

    /** Returns a human-readable reason if [pattern] looks ReDoS-prone, else `null`. */
    fun vulnerabilityReason(pattern: String): String? {
        val stack = ArrayDeque<Group>()
        var i = 0
        val n = pattern.length
        while (i < n) {
            if (pattern[i] == ')') {
                val reason = closeGroup(pattern, stack, i)
                if (reason != null) return reason
                i += 1
            } else {
                i = advance(pattern, stack, i)
            }
        }
        return null
    }

    /** Scans the token at [i] (anything but a group close) into [stack]; returns the index after it. */
    private fun advance(
        pattern: String,
        stack: ArrayDeque<Group>,
        i: Int,
    ): Int =
        when (pattern[i]) {
            '\\' -> {
                i + 2
            }

            // escaped metacharacter: skip the pair
            '[' -> {
                endOfCharClass(pattern, i)
            }

            '(' -> {
                // Start the first branch after any (?:, (?<name>, (?=, (?<=, (?flags:
                // prefix so a non-capturing/named/lookaround group is analysed by its
                // real body — otherwise the prefix chars pollute the branch strings and
                // defeat the overlap check (e.g. (?:a|ab)* would read "?:a" | "ab").
                val bodyStart = groupBodyStart(pattern, i)
                stack.addLast(Group(mutableListOf(bodyStart)))
                bodyStart
            }

            '|' -> {
                stack.lastOrNull()?.branchStarts?.add(i + 1)
                i + 1
            }

            '*', '+' -> {
                stack.lastOrNull()?.let { it.bodyHasUnbounded = true }
                i + 1
            }

            '{' -> {
                skipInterval(pattern, stack, i)
            }

            else -> {
                i + 1
            }
        }

    /** Skips the `{n,m}` interval at [i], marking the open group unbounded when it is; a literal '{' is one character. */
    private fun skipInterval(
        pattern: String,
        stack: ArrayDeque<Group>,
        i: Int,
    ): Int {
        val interval = intervalAt(pattern, i) ?: return i + 1 // a literal '{'
        if (interval.unbounded) stack.lastOrNull()?.let { it.bodyHasUnbounded = true }
        return i + interval.length
    }

    /**
     * Closes the innermost group at [closeAt]: the ReDoS reason when a quantifier follows a
     * group whose body is unbounded or has overlapping branches, else null after carrying
     * both structural flags up to the parent (the closed group is an atom in it).
     */
    private fun closeGroup(
        pattern: String,
        stack: ArrayDeque<Group>,
        closeAt: Int,
    ): String? {
        val group = stack.removeLastOrNull() ?: return null
        // Overlap in this group's body: its own top-level branches, or one a
        // nested group already carried up (e.g. ((a|ab))*).
        val overlap = overlappingBranch(pattern, group, closeAt) ?: group.overlapReason
        if (hasUnboundedQuantifierAt(pattern, closeAt + 1)) {
            if (group.bodyHasUnbounded) {
                return "nested unbounded quantifier (a quantified group whose body is " +
                    "itself unbounded, e.g. (X+)+): \"$pattern\""
            }
            if (overlap != null) {
                return "quantified alternation with overlapping branches ($overlap): \"$pattern\""
            }
        }
        stack.lastOrNull()?.let { parent ->
            if (group.bodyHasUnbounded) parent.bodyHasUnbounded = true
            if (overlap != null) parent.overlapReason = parent.overlapReason ?: overlap
        }
        return null
    }

    /**
     * Index where a group's body (its first branch) begins, skipping a `(?…)`
     * prefix: `(?:`, `(?=`, `(?!`, `(?<=`, `(?<!`, `(?<name>`, `(?'name'`, and
     * flag-scoped `(?flags:`. A plain capturing group, a bare inline-flag
     * directive `(?flags)`, or an unrecognised form falls back to `open + 1`.
     */
    private fun groupBodyStart(
        pattern: String,
        open: Int,
    ): Int {
        if (pattern.getOrNull(open + 1) != '?') return open + 1
        return when (pattern.getOrNull(open + 2)) {
            null -> open + 1
            ':', '=', '!' -> open + 3
            '<' -> angleGroupBodyStart(pattern, open)
            '\'' -> afterClosing(pattern, '\'', open)
            else -> flagGroupBodyStart(pattern, open)
        }
    }

    /** `(?<=` / `(?<!` lookbehind, else a `(?<name>` named group. */
    private fun angleGroupBodyStart(
        pattern: String,
        open: Int,
    ): Int {
        val next = pattern.getOrNull(open + 3)
        return if (next == '=' || next == '!') open + 4 else afterClosing(pattern, '>', open)
    }

    /** Just past the [close] ending a group name that starts at `open + 3`; `open + 1` if it is unterminated. */
    private fun afterClosing(
        pattern: String,
        close: Char,
        open: Int,
    ): Int {
        val end = pattern.indexOf(close, open + 3)
        return if (end < 0) open + 1 else end + 1
    }

    /** `(?flags:…)` has its body after the ':'; a bare `(?flags)` directive falls back to `open + 1`. */
    private fun flagGroupBodyStart(
        pattern: String,
        open: Int,
    ): Int {
        var j = open + 2
        while (j < pattern.length && pattern[j] != ':' && pattern[j] != ')') j++
        return if (pattern.getOrNull(j) == ':') j + 1 else open + 1
    }

    private fun endOfCharClass(
        pattern: String,
        open: Int,
    ): Int {
        var i = open + 1
        if (i < pattern.length && pattern[i] == '^') i += 1
        if (i < pattern.length && pattern[i] == ']') i += 1 // a leading ']' is a literal member
        while (i < pattern.length && pattern[i] != ']') {
            if (pattern[i] == '\\') i += 1
            i += 1
        }
        return (i + 1).coerceAtMost(pattern.length)
    }

    private fun hasUnboundedQuantifierAt(
        pattern: String,
        i: Int,
    ): Boolean {
        if (i >= pattern.length) return false
        return when (pattern[i]) {
            '*', '+' -> true
            '{' -> intervalAt(pattern, i)?.unbounded == true
            else -> false
        }
    }

    /** Parses `{n}`, `{n,}`, `{n,m}` at [open]; null if not a well-formed interval. */
    private fun intervalAt(
        pattern: String,
        open: Int,
    ): Interval? {
        val close = pattern.indexOf('}', open)
        if (close < 0) return null
        val body = pattern.substring(open + 1, close)
        if (!Regex("""\d+(,\d*)?""").matches(body)) return null
        return Interval(length = close - open + 1, unbounded = body.endsWith(",")) // {n,} has no upper bound
    }

    /** Reports the first overlapping pair among [group]'s top-level `|` branches. */
    private fun overlappingBranch(
        pattern: String,
        group: Group,
        closeAt: Int,
    ): String? {
        if (group.branchStarts.size < 2) return null
        val branches =
            group.branchStarts.mapIndexed { idx, start ->
                val end = if (idx + 1 < group.branchStarts.size) group.branchStarts[idx + 1] - 1 else closeAt
                pattern.substring(start, end)
            }
        for (a in branches.indices) {
            for (b in a + 1 until branches.size) {
                val x = branches[a]
                val y = branches[b]
                if (x.isEmpty() || y.isEmpty() || x.startsWith(y) || y.startsWith(x)) {
                    return "\"$x\" | \"$y\""
                }
            }
        }
        return null
    }
}
