package homer.app.models

/**
 * Shape rules for an agent's optional `group` label — the operator-facing bucket
 * the console's Agents page renders one tab per. A label, not an identifier: any
 * printable text is allowed, but it is bounded and free of control characters
 * because it is echoed verbatim onto every process response. Sibling of
 * [RunnerTagValidation]; see ADR-0049.
 */
object AgentGroupValidation {
    /** Longest accepted `group`; bounds the label repeated on every process row (§12 rule 4). */
    const val MAX_GROUP_LENGTH = 64

    /**
     * Validates an optional [group] and returns it unchanged. [owner] prefixes every
     * message (e.g. `Agent 'deploy'`) so a config-load failure names the offending
     * block. The offending value is never echoed back: it is the untrusted input.
     */
    fun validate(
        owner: String,
        group: String?,
    ): String? {
        if (group == null) return null
        require(group.isNotBlank()) { "$owner: group must not be blank" }
        require(group.length <= MAX_GROUP_LENGTH) {
            "$owner: group must be at most $MAX_GROUP_LENGTH characters, got ${group.length}"
        }
        require(group.none { it.isISOControl() }) { "$owner: group must not contain control characters" }
        return group
    }
}
